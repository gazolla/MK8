// Shared file — included via //SOURCES in Kernel.java. No JBang header.

import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

/**
 * PluginManager — Plugin discovery, catalog publishing, and process lifecycle management.
 *
 * Implements EventInterceptor to participate in the Kernel's interceptor chain.
 * At boot, a virtual thread scans the project root for plugin.json files and publishes
 * system.catalog.entry events for each capability found, followed by system.catalog.ready.
 * CapabilityInterceptor maintains its own local copy — zero direct coupling between the two.
 *
 * Responds to system.plugin.spawn (on-demand launch), system.plugin.usage (idle tracking),
 * and system.catalog.refresh (hot-reload). Publishes system.plugin.spawned/died/stopped.
 */
class PluginManager implements EventInterceptor, InterceptorLifecycle {

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
    private final ScheduledExecutorService    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "pluginmgr-timer");
        t.setDaemon(true);
        return t;
    });

    // ── Construction ──────────────────────────────────────────────────────────

    PluginManager(KernelBus bus, KernelConfig config) {
        this.bus     = bus;
        this.mk8Root = config.scanRoot();
        this.logsDir = (config.logsOverride() != null
                ? config.logsOverride()
                : config.scanRoot().resolve("logs")).toFile();
        this.logsDir.mkdirs();
        scheduler.scheduleAtFixedRate(this::checkIdlePlugins, 60, 60, TimeUnit.SECONDS);
    }

    @Override
    public void onStart() {
        Thread.ofVirtual().start(this::scan);
    }

    @Override public Set<String> publishes() {
        return Set.of(EVT_CATALOG_ENTRY, EVT_CATALOG_READY,
                      EVT_SPAWNED, EVT_DIED, EVT_STOPPED);
    }

    @Override public Set<String> subscribes() {
        return Set.of(EVT_PLUGIN_SPAWN, EVT_PLUGIN_USAGE,
                      EVT_CATALOG_REFRESH, EVT_INSTALLED, EVT_LIST_REQUEST);
    }

    // ── EventInterceptor ──────────────────────────────────────────────────────

    @Override
    public boolean handles(String type) {
        return type.equals(EVT_PLUGIN_SPAWN)
            || type.equals(EVT_PLUGIN_USAGE)
            || type.equals(EVT_CATALOG_REFRESH)
            || type.equals(EVT_INSTALLED)
            || type.equals(EVT_LIST_REQUEST);
    }

    @Override
    public boolean intercept(KernelEvent event, String json) throws Exception {
        return switch (event.type()) {
            case EVT_PLUGIN_SPAWN    -> { handleSpawn(event);           yield true;  }
            case EVT_PLUGIN_USAGE    -> { handleUsage(event);           yield false; }
            case EVT_LIST_REQUEST    -> { handleListRequest(event);     yield true;  }
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

                    JsonNode caps = json.path("capabilities");
                    if (!caps.isArray() || caps.isEmpty()) continue;

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
        List<Map<String, Object>> list = managed.values().stream()
                .map(mp -> {
                    Long   last = lastUsed.get(mp.pluginId());
                    String idle = last != null
                            ? (System.currentTimeMillis() - last) / 1000 + "s ago"
                            : "unknown";
                    return Map.<String, Object>of(
                            F_PLUGIN_ID, mp.pluginId(),
                            "pid",       mp.pid(),
                            "alive",     mp.process().isAlive(),
                            "lastUsed",  idle);
                })
                .toList();
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

            logsDir.mkdirs();
            File    logFile = new File(logsDir, pluginId + ".log");
            Process process = new ProcessBuilder(command)
                    .directory(pluginDir.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                    .redirectErrorStream(true)
                    .start();

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
        mp.process().destroy();
        boolean exited = mp.process().waitFor(5, TimeUnit.SECONDS);
        if (!exited) mp.process().destroyForcibly();
        System.out.println("[PLUGIN-MGR] Stopped: " + pluginId + " pid=" + mp.pid() + " reason=" + reason);
        bus.route(KernelEvent.of(EVT_STOPPED,
                KernelEvent.MAPPER.writeValueAsString(Map.of(
                        F_PLUGIN_ID, pluginId, "pid", mp.pid(), "reason", reason)),
                SOURCE));
    }
}
