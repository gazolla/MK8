// Shared file — included via //SOURCES in Kernel.java. No JBang header.

import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

/**
 * PluginInterceptor — Plugin discovery, catalog publishing, and process lifecycle management.
 *
 * Implements EventInterceptor to participate in the Kernel's interceptor chain.
 * At boot, a virtual thread scans the project root for plugin.json files and publishes
 * system.catalog.entry events for each capability found, followed by system.catalog.ready.
 * CapabilityInterceptor maintains its own local copy — zero direct coupling between the two.
 *
 * Responds to system.plugin.spawn (on-demand launch), system.plugin.usage (idle tracking),
 * and system.catalog.refresh (hot-reload). Publishes system.plugin.spawned/died/stopped.
 */
class PluginInterceptor implements EventInterceptor, InterceptorLifecycle {

    // ── Eventos que publica ───────────────────────────────────────────────────
    private static final String EVT_CATALOG_ENTRY   = "system.catalog.entry";
    private static final String EVT_CATALOG_READY   = "system.catalog.ready";
    private static final String EVT_SPAWNED         = "system.plugin.spawned";
    private static final String EVT_DIED            = "system.plugin.died";
    private static final String EVT_STOPPED         = "system.plugin.stopped";

    // ── Eventos que subscreve ─────────────────────────────────────────────────
    private static final String EVT_PLUGIN_SPAWN    = "system.plugin.spawn";
    private static final String EVT_PLUGIN_USAGE    = "system.plugin.usage";
    private static final String EVT_CATALOG_REFRESH = "system.catalog.refresh";
    private static final String EVT_INSTALLED       = "plugin.installed";
    private static final String EVT_LIST_REQUEST    = "system.plugin.list.request";
    // ── Supervision (folded-in lifecycle: crash detection + auto-restart) ──────
    private static final String EVT_PLUGIN_READY    = "plugin.ready";
    private static final String EVT_DISCONNECTED    = "system.plugin.disconnected";
    private static final String EVT_CRASHED         = "system.plugin.crashed";
    private static final String EVT_RESTART_REQUEST = "system.plugin.restart.request";
    private static final String CAP_RESTART         = "system.plugin.restart";
    private static final long   RESTART_WINDOW_MS   = 60_000L;
    private static final int    RESTART_MAX         = 3;
    // JSON field keys — payload de catalog.entry
    private static final String F_PLUGIN_ID         = "pluginId";
    private static final String F_PLUGIN_DIR        = "pluginDir";
    private static final String F_CAP_NAME          = "capabilityName";
    private static final String F_TRIGGER           = "triggerEvent";
    private static final String F_ON_DEMAND         = "onDemand";
    private static final String F_PERSISTENT        = "persistent";
    private static final String F_BID_WEIGHT        = "bidWeight";
    private static final String F_IDLE_TIMEOUT      = "idleTimeoutSeconds";
    private static final String F_LAUNCH_CMD        = "launchCommand";
    private static final String SOURCE              = "plugin-lifecycle";

    // ── Inner types ───────────────────────────────────────────────────────────

    record ManagedProcess(String pluginId, long pid, Path pluginDir, Process process) {}

    /** A plugin.json with a launch command — revivable independent of capabilities. */
    record Launchable(String pluginId, Path dir, String[] command, boolean persistent, boolean autoRestart) {}

    /** Per-plugin supervision state (pid from plugin.ready, restart accounting). */
    static final class Supervision {
        volatile long pid = -1;
        int           restarts = 0;
        long          lastRestartMs = 0;
        volatile boolean stopping = false; // true = intentional stop → suppress auto-restart
    }

    // ── Catalog fields ────────────────────────────────────────────────────────

    private final Path mk8Root;
    private final ConcurrentHashMap<String, CatalogEntry>       byCapName  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<CatalogEntry>> byPluginId = new ConcurrentHashMap<>();

    // ── Lifecycle fields ──────────────────────────────────────────────────────

    private final KernelBus                   bus;
    private final File                        logsDir;
    private final Map<String, ManagedProcess> managed  = new ConcurrentHashMap<>();
    private final Map<String, Long>           lastUsed = new ConcurrentHashMap<>();
    private final Set<String>                 spawning = ConcurrentHashMap.newKeySet();
    // ── Supervision state ──────────────────────────────────────────────────────
    private final Map<String, Launchable>     launchables = new ConcurrentHashMap<>();
    private final Map<String, Supervision>    supervised  = new ConcurrentHashMap<>();
    private final Set<String>                 recovering  = ConcurrentHashMap.newKeySet();
    private volatile boolean                   shuttingDown = false;
    private final ScheduledExecutorService    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "pluginmgr-timer");
        t.setDaemon(true);
        return t;
    });

    // ── Construction ──────────────────────────────────────────────────────────

    PluginInterceptor(KernelBus bus, KernelConfig config) {
        this.bus     = bus;
        this.mk8Root = config.scanRoot();
        this.logsDir = (config.logsOverride() != null
                ? config.logsOverride()
                : config.scanRoot().resolve("logs")).toFile();
        this.logsDir.mkdirs();
        scheduler.scheduleAtFixedRate(this::checkIdlePlugins, 60, 60, TimeUnit.SECONDS);
        // Suppress auto-restart while the kernel is tearing down, so we don't spawn orphans.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shuttingDown = true));
    }

    @Override
    public void onStart() {
        Thread.ofVirtual().start(this::scan);
    }

    @Override public Set<String> publishes() {
        return Set.of(EVT_CATALOG_ENTRY, EVT_CATALOG_READY,
                      EVT_SPAWNED, EVT_DIED, EVT_STOPPED, EVT_CRASHED);
    }

    @Override public Set<String> subscribes() {
        return Set.of(EVT_PLUGIN_SPAWN, EVT_PLUGIN_USAGE,
                      EVT_CATALOG_REFRESH, EVT_INSTALLED, EVT_LIST_REQUEST,
                      EVT_PLUGIN_READY, EVT_DISCONNECTED, EVT_RESTART_REQUEST);
    }

    // ── EventInterceptor ──────────────────────────────────────────────────────

    @Override
    public boolean handles(String type) {
        return type.equals(EVT_PLUGIN_SPAWN)
            || type.equals(EVT_PLUGIN_USAGE)
            || type.equals(EVT_CATALOG_REFRESH)
            || type.equals(EVT_INSTALLED)
            || type.equals(EVT_LIST_REQUEST)
            || type.equals(EVT_PLUGIN_READY)
            || type.equals(EVT_DISCONNECTED)
            || type.equals(EVT_RESTART_REQUEST);
    }

    @Override
    public boolean intercept(KernelEvent event, String json) throws Exception {
        return switch (event.type()) {
            case EVT_PLUGIN_SPAWN    -> { handleSpawn(event);           yield true;  }
            case EVT_PLUGIN_USAGE    -> { handleUsage(event);           yield false; }
            case EVT_LIST_REQUEST    -> { handleListRequest(event);     yield true;  }
            case EVT_PLUGIN_READY    -> { bindPid(event);              yield false; }
            case EVT_DISCONNECTED    -> { handleDisconnected(event);    yield false; }
            case EVT_RESTART_REQUEST -> { handleRestartRequest(event);  yield true;  }
            case EVT_CATALOG_REFRESH,
                 EVT_INSTALLED       -> { System.out.println("[PLUGIN-MGR] Catalog refreshing...");
                                          Thread.ofVirtual().start(this::scan); yield false; }
            default                  ->                                           false;
        };
    }

    // ── Catalog scan ──────────────────────────────────────────────────────────

    void scan() {
        byCapName.clear();
        byPluginId.clear();
        launchables.clear();

        int pluginCount = 0;
        int capCount    = 0;

        try (Stream<Path> walk = Files.walk(mk8Root)) {
            List<Path> pluginFiles = walk
                    .filter(p -> p.getFileName().toString().equals("plugin.json"))
                    .filter(p -> !p.startsWith(mk8Root.resolve("kernel")))
                    .sorted()
                    .toList();

            for (Path pf : pluginFiles) {
                try {
                    JsonNode json    = KernelEvent.MAPPER.readTree(pf.toFile());
                    String pluginId  = json.path("id").asText("");
                    String type      = json.path("type").asText("");
                    if (pluginId.isBlank()) continue;
                    if (!"tool".equals(type) && !"agent".equals(type) && !"system".equals(type)) continue;

                    String  lifecycleMode = json.path("lifecycle").path("mode").asText("persistent");
                    boolean onDemand      = "on-demand".equals(lifecycleMode);
                    boolean persistent    = "persistent".equals(lifecycleMode);
                    int     idleTimeout   = json.path("lifecycle").path("idleTimeoutSeconds").asInt(300);

                    String[] launchCommand = null;
                    JsonNode cmdNode = json.path("launch").path("command");
                    if (cmdNode.isArray() && !cmdNode.isEmpty()) {
                        launchCommand = new String[cmdNode.size()];
                        for (int i = 0; i < cmdNode.size(); i++) launchCommand[i] = cmdNode.get(i).asText();
                    }

                    String pluginDir = pf.getParent().toAbsolutePath().toString();

                    // Record a launchable for lifecycle supervision — independent of capabilities,
                    // so capability-less system plugins (pub/sub, UIs) are also revivable.
                    if (launchCommand != null) {
                        boolean autoRestart = persistent
                                && !"never".equals(json.path("lifecycle").path("restart").asText(""));
                        launchables.put(pluginId, new Launchable(
                                pluginId, Path.of(pluginDir), launchCommand, persistent, autoRestart));
                    }

                    JsonNode caps = json.path("capabilities");
                    if (!caps.isArray() || caps.isEmpty()) continue;
                    pluginCount++;

                    for (JsonNode cap : caps) {
                        String capName = cap.path("name").asText("");
                        if (capName.isBlank()) continue;
                        String triggerEvent = cap.path("triggerEvent").asText(null);
                        if (triggerEvent != null && triggerEvent.isBlank()) triggerEvent = null;
                        double bidWeight = cap.path("bidWeight").asDouble(1.0);

                        CatalogEntry entry = new CatalogEntry(
                                pluginId, pluginDir, capName, triggerEvent,
                                onDemand, persistent, bidWeight, idleTimeout, launchCommand);

                        byCapName.put(capName, entry);
                        byPluginId.computeIfAbsent(pluginId, k -> new CopyOnWriteArrayList<>()).add(entry);

                        // Publish catalog entry so CapabilityInterceptor builds its local copy
                        publishCatalogEntry(entry);
                        capCount++;
                    }
                } catch (Exception e) {
                    System.err.println("[PLUGIN-MGR] Error scanning " + pf + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("[PLUGIN-MGR] Walk error: " + e.getMessage());
        }

        // Register system.plugin.list as a routable capability
        try {
            String reg = KernelEvent.MAPPER.writeValueAsString(Map.of(
                    "name",         "system.plugin.list",
                    F_PLUGIN_ID,    SOURCE,
                    F_BID_WEIGHT,   1.0,
                    F_TRIGGER,      EVT_LIST_REQUEST));
            bus.route(KernelEvent.of("capability.register", reg, SOURCE));

            String regRestart = KernelEvent.MAPPER.writeValueAsString(Map.of(
                    "name",         CAP_RESTART,
                    F_PLUGIN_ID,    SOURCE,
                    F_BID_WEIGHT,   1.0,
                    F_TRIGGER,      EVT_RESTART_REQUEST));
            bus.route(KernelEvent.of("capability.register", regRestart, SOURCE));
        } catch (Exception ignored) {}

        System.out.println("[PLUGIN-MGR] Scan complete: " + capCount
                + " capabilities across " + pluginCount + " plugins.");

        // Signal CapabilityInterceptor that the catalog is ready
        try { bus.route(KernelEvent.of(EVT_CATALOG_READY, "{}", SOURCE)); }
        catch (Exception ignored) {}
    }

    // ── Intercept handlers ────────────────────────────────────────────────────

    private void handleSpawn(KernelEvent event) throws Exception {
        JsonNode p = KernelEvent.MAPPER.readTree(event.payload());
        String capName = p.path(F_CAP_NAME).asText(null);
        if (capName != null) spawnOnDemand(capName);
    }

    private void handleUsage(KernelEvent event) throws Exception {
        JsonNode p = KernelEvent.MAPPER.readTree(event.payload());
        String capName = p.path(F_CAP_NAME).asText(null);
        if (capName != null) trackUsage(capName);
    }

    private void handleListRequest(KernelEvent event) throws Exception {
        Set<String> ids = new TreeSet<>();
        ids.addAll(managed.keySet());
        ids.addAll(supervised.keySet());

        List<Map<String, Object>> list = new ArrayList<>();
        for (String id : ids) {
            Supervision    s   = supervised.get(id);
            ManagedProcess mp  = managed.get(id);
            long           pid = mp != null ? mp.pid() : (s != null ? s.pid : -1);
            boolean        alive = pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
            list.add(Map.of(
                    F_PLUGIN_ID, id,
                    "pid",       pid,
                    "alive",     alive,
                    "restarts",  s != null ? s.restarts : 0,
                    "managed",   mp != null));
        }
        String result = KernelEvent.MAPPER.writeValueAsString(
                Map.of("result", KernelEvent.MAPPER.writeValueAsString(list)));
        bus.route(KernelEvent.withCorrelation("capability.result", result,
                SOURCE, event.correlationId(), event.sessionId()));
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    private void trackUsage(String capName) {
        CatalogEntry entry = byCapName.get(capName);
        if (entry == null) return;
        if (managed.containsKey(entry.pluginId()))
            lastUsed.put(entry.pluginId(), System.currentTimeMillis());
    }

    private void spawnOnDemand(String capName) throws Exception {
        CatalogEntry entry = byCapName.get(capName);
        if (entry == null || !entry.onDemand()) {
            System.err.println("[PLUGIN-MGR] No on-demand plugin found for: " + capName);
            return;
        }

        String pluginId  = entry.pluginId();
        Path   pluginDir = Path.of(entry.pluginDir());

        if (managed.containsKey(pluginId)) {
            System.out.println("[PLUGIN-MGR] Already running: " + pluginId + " (spawn for " + capName + ")");
            publishSpawned(pluginId, managed.get(pluginId).pid(), capName);
            return;
        }

        if (!spawning.add(pluginId)) {
            System.out.println("[PLUGIN-MGR] Already spawning: " + pluginId);
            return;
        }

        try {
            String[] command = entry.launchCommand();
            if (command == null || command.length == 0) {
                System.err.println("[PLUGIN-MGR] No launch.command for: " + pluginId);
                return;
            }

            Process process = startProcess(command, pluginDir, pluginId);

            long pid = process.pid();
            managed.put(pluginId, new ManagedProcess(pluginId, pid, pluginDir, process));
            lastUsed.put(pluginId, System.currentTimeMillis());
            System.out.println("[PLUGIN-MGR] On-demand spawn: " + pluginId + " pid=" + pid + " for " + capName);
            publishSpawned(pluginId, pid, capName);

            process.onExit().thenAccept(p2 -> {
                if (!managed.containsKey(pluginId)) return;
                managed.remove(pluginId);
                lastUsed.remove(pluginId);
                int exitCode = p2.exitValue();
                System.out.println("[PLUGIN-MGR] Plugin died unexpectedly: " + pluginId + " exitCode=" + exitCode);
                try {
                    bus.route(KernelEvent.of(EVT_DIED,
                            KernelEvent.MAPPER.writeValueAsString(Map.of(
                                    F_PLUGIN_ID, pluginId, "pid", pid, "exitCode", exitCode)),
                            SOURCE));
                } catch (Exception ignored) {}
            });
        } finally {
            spawning.remove(pluginId);
        }
    }

    void checkIdlePlugins() {
        long now = System.currentTimeMillis();
        for (String pluginId : new ArrayList<>(managed.keySet())) {
            List<CatalogEntry> entries = byPluginId.getOrDefault(pluginId, List.of());
            if (entries.isEmpty()) continue;
            int     timeoutSec = entries.stream().mapToInt(CatalogEntry::idleTimeoutSeconds).max().orElse(0);
            boolean onDemand   = entries.stream().anyMatch(CatalogEntry::onDemand);
            if (timeoutSec <= 0 || !onDemand) continue;
            long idleMs = now - lastUsed.getOrDefault(pluginId, 0L);
            if (idleMs > (long) timeoutSec * 1000) {
                System.out.println("[PLUGIN-MGR] Idle timeout: killing " + pluginId
                        + " (idle " + idleMs / 1000 + "s, limit " + timeoutSec + "s)");
                try { terminatePlugin(pluginId, "idle-timeout"); } catch (Exception ignored) {}
            }
        }
    }

    // ── Publish helpers ───────────────────────────────────────────────────────

    private void publishCatalogEntry(CatalogEntry e) {
        try {
            var m = new LinkedHashMap<String, Object>();
            m.put(F_PLUGIN_ID,    e.pluginId());
            m.put(F_PLUGIN_DIR,   e.pluginDir());
            m.put(F_CAP_NAME,     e.capabilityName());
            if (e.triggerEvent() != null) m.put(F_TRIGGER, e.triggerEvent());
            m.put(F_ON_DEMAND,    e.onDemand());
            m.put(F_PERSISTENT,   e.persistent());
            m.put(F_BID_WEIGHT,   e.bidWeight());
            m.put(F_IDLE_TIMEOUT, e.idleTimeoutSeconds());
            if (e.launchCommand() != null) m.put(F_LAUNCH_CMD, e.launchCommand());
            bus.route(KernelEvent.of(EVT_CATALOG_ENTRY,
                    KernelEvent.MAPPER.writeValueAsString(m), SOURCE));
        } catch (Exception ignored) {}
    }

    private void publishSpawned(String pluginId, long pid, String capabilityName) {
        try {
            bus.route(KernelEvent.of(EVT_SPAWNED,
                    KernelEvent.MAPPER.writeValueAsString(Map.of(
                            F_PLUGIN_ID, pluginId, "pid", pid, "capability", capabilityName)),
                    SOURCE));
        } catch (Exception ignored) {}
    }

    private void terminatePlugin(String pluginId, String reason) throws Exception {
        ManagedProcess mp = managed.remove(pluginId);
        if (mp == null) return;
        lastUsed.remove(pluginId);
        Supervision sv = supervised.get(pluginId);
        if (sv != null) sv.stopping = true; // intentional stop → no auto-restart on the ensuing disconnect
        mp.process().destroy();
        boolean exited = mp.process().waitFor(5, TimeUnit.SECONDS);
        if (!exited) mp.process().destroyForcibly();
        System.out.println("[PLUGIN-MGR] Stopped: " + pluginId + " pid=" + mp.pid() + " reason=" + reason);
        bus.route(KernelEvent.of(EVT_STOPPED,
                KernelEvent.MAPPER.writeValueAsString(Map.of(
                        F_PLUGIN_ID, pluginId, "pid", mp.pid(), "reason", reason)),
                SOURCE));
    }

    // ── Supervision: crash detection + auto-restart ───────────────────────────

    /** plugin.ready carries {id, pid} — learn the OS pid of every plugin (spawned or not). */
    private void bindPid(KernelEvent event) throws Exception {
        JsonNode p = KernelEvent.MAPPER.readTree(event.payload());
        String id  = p.path("id").asText("");
        long   pid = p.path("pid").asLong(-1);
        if (id.isBlank() || pid < 0) return;
        Supervision s = supervised.computeIfAbsent(id, k -> new Supervision());
        s.pid = pid;
        s.stopping = false; // a fresh ready means it's up again
    }

    /** Kernel announced a dropped connection — decide whether it was a crash to revive. */
    private void handleDisconnected(KernelEvent event) throws Exception {
        String id = KernelEvent.MAPPER.readTree(event.payload()).path("id").asText("");
        if (id.isBlank()) return;

        Supervision s = supervised.get(id);
        if (s != null && s.stopping) { s.stopping = false; return; } // intentional stop

        Launchable l = launchables.get(id);
        if (l == null || !l.autoRestart()) return; // not supervised / on-demand / opt-out

        if (recovering.add(id)) {
            Thread.ofVirtual().start(() -> {
                try { restartWithBackoff(id); }
                finally { recovering.remove(id); }
            });
        }
    }

    private void restartWithBackoff(String id) {
        if (shuttingDown) return;
        Launchable l = launchables.get(id);
        if (l == null) return;

        Supervision s = supervised.computeIfAbsent(id, k -> new Supervision());
        long now = System.currentTimeMillis();
        if (now - s.lastRestartMs < RESTART_WINDOW_MS) {
            if (s.restarts >= RESTART_MAX) {
                System.err.println("[PLUGIN-MGR] " + id + " exceeded " + RESTART_MAX
                        + " restarts in " + (RESTART_WINDOW_MS / 1000) + "s — giving up.");
                publishCrashed(id, "restart limit reached");
                return;
            }
        } else {
            s.restarts = 0; // window elapsed → stable → reset
        }

        s.restarts++;
        s.lastRestartMs = now;
        long delayMs = 1000L * (1L << (s.restarts - 1)); // 1s, 2s, 4s...
        System.out.println("[PLUGIN-MGR] Auto-restart " + id + " (attempt " + s.restarts
                + "/" + RESTART_MAX + ") in " + (delayMs / 1000.0) + "s");
        try { Thread.sleep(delayMs); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        if (shuttingDown) return;
        spawnLaunchable(l);
    }

    private void spawnLaunchable(Launchable l) {
        try {
            Process p = startProcess(l.command(), l.dir(), l.pluginId());
            supervised.computeIfAbsent(l.pluginId(), k -> new Supervision()).pid = p.pid();
            System.out.println("[PLUGIN-MGR] Re-spawned: " + l.pluginId() + " pid=" + p.pid());
            publishSpawned(l.pluginId(), p.pid(), "restart");
        } catch (Exception e) {
            System.err.println("[PLUGIN-MGR] Re-spawn failed for " + l.pluginId() + ": " + e.getMessage());
        }
    }

    /** Built-in capability system.plugin.restart — invoke with payload {id: "<pluginId>"}. */
    private void handleRestartRequest(KernelEvent event) {
        Thread.ofVirtual().start(() -> {
            String result;
            try {
                JsonNode p = KernelEvent.MAPPER.readTree(event.payload());
                String id  = p.path("id").asText(p.path(F_PLUGIN_ID).asText(""));
                Launchable l = launchables.get(id);
                if (id.isBlank() || l == null) {
                    result = "No launchable plugin: " + id;
                } else {
                    Supervision s = supervised.computeIfAbsent(id, k -> new Supervision());
                    s.stopping = true;          // suppress auto-restart from the kill below
                    if (s.pid > 0) ProcessHandle.of(s.pid).ifPresent(ProcessHandle::destroy);
                    s.restarts = 0;             // manual restart resets the backoff window
                    Thread.sleep(500);          // grace for the old socket to drop
                    spawnLaunchable(l);
                    result = "Restarted: " + id;
                }
            } catch (Exception e) {
                result = "Restart error: " + e.getMessage();
            }
            try {
                String payload = KernelEvent.MAPPER.writeValueAsString(Map.of("result", result));
                bus.route(KernelEvent.withCorrelation("capability.result", payload,
                        SOURCE, event.correlationId(), event.sessionId()));
            } catch (Exception ignored) {}
        });
    }

    private void publishCrashed(String pluginId, String reason) {
        try {
            bus.route(KernelEvent.of(EVT_CRASHED,
                    KernelEvent.MAPPER.writeValueAsString(Map.of(
                            F_PLUGIN_ID, pluginId, "reason", reason)),
                    SOURCE));
        } catch (Exception ignored) {}
    }

    /** Shared process launcher (DRY) — used by on-demand spawn and supervision restart. */
    private Process startProcess(String[] command, Path dir, String pluginId) throws Exception {
        logsDir.mkdirs();
        File logFile = new File(logsDir, pluginId + ".log");
        return new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                .redirectErrorStream(true)
                .start();
    }
}
