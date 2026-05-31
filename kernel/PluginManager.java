// Shared file — included via //SOURCES in Kernel.java. No JBang header.

import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

/**
 * PluginManager — Plugin discovery, catalog indexing, and process lifecycle management.
 *
 * Combines static manifest scanning with dynamic process orchestration into a unified
 * runtime engine implementing PluginRuntime. At boot, a virtual thread scans the
 * project root for plugin.json files, building a capability catalog of all available
 * services. It can dynamically hot-reload the catalog on plugin.installed events.
 *
 * Responsible for launching on-demand tools when an invoke event arrives without an
 * active provider. Processes are run in individual directories, redirecting log
 * streams to logs/<pluginId>.log. Spawning is protected by concurrency gates.
 * Also keeps track of plugin usage timestamps and executes a background sweeper
 * every 60 seconds to terminate on-demand processes that have exceeded their idle limit.
 * Ensures full thread safety across concurrent readers and dynamic process mutations.
 */
class PluginManager implements PluginRuntime {

    // ── Managed process ───────────────────────────────────────────────────────
    // CatalogEntry is defined in Kernel.java (top-level record) so CapabilityInterceptor
    // can reference it without importing PluginManager.

    record ManagedProcess(String pluginId, long pid, Path pluginDir, Process process) {}

    // ── Catalog fields ────────────────────────────────────────────────────────

    private final Path mk8Root;
    private final ConcurrentHashMap<String, CatalogEntry>       byCapName  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<CatalogEntry>> byPluginId = new ConcurrentHashMap<>();
    private volatile boolean     ready = false;
    private final CountDownLatch latch = new CountDownLatch(1);

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

    PluginManager(KernelBus bus, Path mk8Root) {
        this.bus     = bus;
        this.mk8Root = mk8Root;
        this.logsDir = mk8Root.resolve("logs").toFile();
        this.logsDir.mkdirs();
        scheduler.scheduleAtFixedRate(this::checkIdlePlugins, 60, 60, TimeUnit.SECONDS);
    }

    // ── PluginRuntime: catalog ────────────────────────────────────────────────

    @Override
    public void awaitReady(long timeoutMs) {
        if (ready) return;
        try { latch.await(timeoutMs, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Override
    public CatalogEntry getByCapName(String capName) {
        return byCapName.get(capName);
    }

    @Override
    public Collection<CatalogEntry> allEntries() {
        return Collections.unmodifiableCollection(byCapName.values());
    }

    @Override
    public void refresh() {
        System.out.println("[PLUGIN-MGR] Catalog refreshing...");
        scan();
    }

    // ── PluginRuntime: lifecycle ──────────────────────────────────────────────

    @Override
    public void trackUsage(String capName) {
        CatalogEntry entry = byCapName.get(capName);
        if (entry == null) return;
        if (managed.containsKey(entry.pluginId())) {
            lastUsed.put(entry.pluginId(), System.currentTimeMillis());
        }
    }

    @Override
    public void spawnOnDemand(String capName) throws Exception {
        awaitReady(500);
        CatalogEntry entry = byCapName.get(capName);

        if (entry == null || !entry.onDemand()) {
            System.err.println("[PLUGIN-MGR] No on-demand plugin found for: " + capName);
            return;
        }

        String pluginId  = entry.pluginId();
        Path   pluginDir = Path.of(entry.pluginDir());

        // Already running — confirm to drain CapabilityInterceptor pendingInvokes
        if (managed.containsKey(pluginId)) {
            System.out.println("[PLUGIN-MGR] Already running: " + pluginId
                    + " (spawn for " + capName + ")");
            publishSpawned(pluginId, managed.get(pluginId).pid(), capName);
            return;
        }

        // Prevent double-spawn on concurrent spawnOnDemand calls
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

            System.out.println("[PLUGIN-MGR] On-demand spawn: " + pluginId
                    + " pid=" + pid + " for " + capName);
            publishSpawned(pluginId, pid, capName);

            // Watch for unexpected process exit
            process.onExit().thenAccept(p2 -> {
                if (!managed.containsKey(pluginId)) return; // already removed by terminatePlugin()
                managed.remove(pluginId);
                lastUsed.remove(pluginId);
                int exitCode = p2.exitValue();
                System.out.println("[PLUGIN-MGR] Plugin died unexpectedly: "
                        + pluginId + " exitCode=" + exitCode);
                try {
                    bus.route(KernelEvent.of("system.plugin.died",
                            KernelEvent.MAPPER.writeValueAsString(Map.of(
                                    "pluginId", pluginId,
                                    "pid",      pid,
                                    "exitCode", exitCode)),
                            "kernel"));
                } catch (Exception ignored) {}
            });

        } finally {
            spawning.remove(pluginId);
        }
    }

    @Override
    public String listPlugins() throws Exception {
        List<Map<String, Object>> list = managed.values().stream()
                .map(mp -> {
                    Long   last = lastUsed.get(mp.pluginId());
                    String idle = last != null
                            ? (System.currentTimeMillis() - last) / 1000 + "s ago"
                            : "unknown";
                    return Map.<String, Object>of(
                            "pluginId", mp.pluginId(),
                            "pid",      mp.pid(),
                            "alive",    mp.process().isAlive(),
                            "lastUsed", idle);
                })
                .toList();
        return KernelEvent.MAPPER.writeValueAsString(list);
    }

    // ── Catalog scan ──────────────────────────────────────────────────────────

    /**
     * Walks the project root, reads every plugin.json, populates lookup maps.
     * Called in a virtual thread at boot; can be called again for refresh.
     */
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
                    JsonNode json   = KernelEvent.MAPPER.readTree(pf.toFile());
                    String pluginId = json.path("id").asText("");
                    String type     = json.path("type").asText("");
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
                        capCount++;
                    }
                } catch (Exception e) {
                    System.err.println("[PLUGIN-MGR] Error scanning " + pf + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("[PLUGIN-MGR] Walk error: " + e.getMessage());
        }

        ready = true;
        latch.countDown(); // no-op on subsequent refresh calls (latch already 0)
        System.out.println("[PLUGIN-MGR] Scan complete: " + capCount
                + " capabilities across " + pluginCount + " plugins.");
    }

    // ── Idle-kill periodic check ──────────────────────────────────────────────

    void checkIdlePlugins() {
        long now = System.currentTimeMillis();
        for (String pluginId : new ArrayList<>(managed.keySet())) {
            List<CatalogEntry> entries = byPluginId.getOrDefault(pluginId, List.of());
            if (entries.isEmpty()) continue;

            int     timeoutSec = entries.stream()
                    .mapToInt(CatalogEntry::idleTimeoutSeconds).max().orElse(0);
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

    // ── Internal helpers ──────────────────────────────────────────────────────

    private void terminatePlugin(String pluginId, String reason) throws Exception {
        ManagedProcess mp = managed.remove(pluginId);
        if (mp == null) return;
        lastUsed.remove(pluginId);

        mp.process().destroy();
        boolean exited = mp.process().waitFor(5, TimeUnit.SECONDS);
        if (!exited) mp.process().destroyForcibly();

        System.out.println("[PLUGIN-MGR] Stopped: " + pluginId
                + " pid=" + mp.pid() + " reason=" + reason);
        bus.route(KernelEvent.of("system.plugin.stopped",
                KernelEvent.MAPPER.writeValueAsString(Map.of(
                        "pluginId", pluginId,
                        "pid",      mp.pid(),
                        "reason",   reason)),
                "kernel"));
    }

    private void publishSpawned(String pluginId, long pid, String capabilityName) {
        try {
            bus.route(KernelEvent.of("system.plugin.spawned",
                    KernelEvent.MAPPER.writeValueAsString(Map.of(
                            "pluginId",   pluginId,
                            "pid",        pid,
                            "capability", capabilityName)),
                    "kernel"));
        } catch (Exception ignored) {}
    }
}
