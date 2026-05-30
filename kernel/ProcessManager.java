// Shared file — included via //SOURCES in Kernel.java. No JBang header.

import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * ProcessManager — runtime process lifecycle manager.
 * Replaces the external PluginManager plugin (system/plugin-manager/).
 *
 * Implements EventInterceptor — intercepts agent/process lifecycle events.
 *
 * Responsibilities:
 *   - On-demand plugin spawning  via plugin.load (triggered by CapabilityIndex)
 *   - Agent spawning             via agent.spawn  (AgentSpawner delegations)
 *   - Idle-kill timer            via periodic 60s check
 *   - Plugin list                via capability.system.plugin.list (P2: literal event type)
 *
 * P1 resolution: trackUsage() is called directly by CapabilityIndex.handleInvoke()
 * before routing. ProcessManager never receives capability.invoke directly.
 *
 * P5 correction: uses entry.launchCommand() (full String[] array) not a single String.
 * P7 correction: Files.walk excludes root/kernel/ directory (delegated to PluginCatalog).
 */
class ProcessManager implements EventInterceptor {

    // ── Inner types ───────────────────────────────────────────────────────────

    record ManagedProcess(String pluginId, long pid, Path pluginDir, Process process) {}

    // ── Fields ────────────────────────────────────────────────────────────────

    private final PluginCatalog                       catalog;
    private final KernelBus                           bus;
    private final Path                                mk7Root;  // project root (same anchor as PluginCatalog)
    private final File                                logsDir;
    private final Map<String, ManagedProcess>         managed   = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>>     timers    = new ConcurrentHashMap<>();
    // pluginId → last capability.invoke timestamp (ms) — for idle-kill
    private final Map<String, Long>                   lastUsed  = new ConcurrentHashMap<>();
    // pluginIds currently being spawned (prevents double-spawn on burst plugin.load)
    private final Set<String>                         spawning  = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "procmgr-timer");
        t.setDaemon(true);
        return t;
    });

    // ── Construction ──────────────────────────────────────────────────────────

    ProcessManager(PluginCatalog catalog, KernelBus bus, Path mk7Root) {
        this.catalog  = catalog;
        this.bus      = bus;
        this.mk7Root  = mk7Root;
        this.logsDir  = mk7Root.resolve("logs").toFile();
        this.logsDir.mkdirs();

        // Idle-kill check every 60 seconds (same interval as MK7 PluginManager)
        scheduler.scheduleAtFixedRate(this::checkIdlePlugins, 60, 60, TimeUnit.SECONDS);
    }

    // ── EventInterceptor ──────────────────────────────────────────────────────

    @Override
    public boolean intercept(Event event, String json) throws Exception {
        return switch (event.type()) {
            case "plugin.load"                   -> { handlePluginLoad(event);   yield true;  }
            case "agent.spawn"                   -> { handleSpawn(event);        yield true;  }
            case "agent.stop"                    -> { handleStop(event);         yield true;  }
            case "agent.ready"                   -> { handleReady(event);        yield false; }
            case "agent.idle"                    -> { handleIdle(event);         yield false; }
            case "agent.busy"                    -> { handleBusy(event);         yield false; }
            // P2: "capability.system.plugin.list" is a literal event type (not a capability.invoke)
            case "capability.system.plugin.list" -> { handlePluginList(event);  yield true;  }
            default                              ->                                    false;
        };
    }

    // ── Usage tracking (called by CapabilityIndex — P1 resolution) ─────────────

    /**
     * Updates the lastUsed timestamp for the plugin that provides capName.
     * Called directly from CapabilityIndex.handleInvoke() before routing,
     * because ProcessManager intercepts after CapabilityIndex (which returns true,
     * stopping the chain). This is the P1 resolution.
     */
    void trackUsage(String capName) {
        PluginCatalog.CatalogEntry entry = catalog.getByCapName(capName);
        if (entry == null) return;
        String pluginId = entry.pluginId();
        if (managed.containsKey(pluginId)) {
            lastUsed.put(pluginId, System.currentTimeMillis());
        }
    }

    // ── On-demand spawn (plugin.load) ─────────────────────────────────────────

    void handlePluginLoad(Event event) throws Exception {
        catalog.awaitReady(500);
        String capName = Event.MAPPER.readTree(event.payload()).path("capability").asText("");
        PluginCatalog.CatalogEntry entry = catalog.getByCapName(capName);

        if (entry == null || !entry.onDemand()) {
            System.err.println("[PROC-MGR] No on-demand plugin found for: " + capName);
            return;
        }

        String pluginId  = entry.pluginId();
        Path   pluginDir = Path.of(entry.pluginDir());

        // Already running — confirm to drain CapabilityIndex pendingInvokes
        if (managed.containsKey(pluginId)) {
            System.out.println("[PROC-MGR] Already running: " + pluginId
                    + " (load for " + capName + ")");
            publishSpawned(pluginId, managed.get(pluginId).pid(), capName);
            return;
        }

        // Prevent double-spawn on burst plugin.load events for the same plugin
        if (!spawning.add(pluginId)) {
            System.out.println("[PROC-MGR] Already spawning: " + pluginId);
            return;
        }

        try {
            // P5: use full launchCommand array, not just first element
            String[] command = entry.launchCommand();
            if (command == null || command.length == 0) {
                System.err.println("[PROC-MGR] No launch.command for: " + pluginId);
                return;
            }

            logsDir.mkdirs(); // ensure logs dir exists
            File    logFile = new File(logsDir, pluginId + ".log");
            Process process = new ProcessBuilder(command)
                    .directory(pluginDir.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                    .redirectErrorStream(true)
                    .start();

            long pid = process.pid();
            managed.put(pluginId, new ManagedProcess(pluginId, pid, pluginDir, process));
            lastUsed.put(pluginId, System.currentTimeMillis());

            System.out.println("[PROC-MGR] On-demand spawn: " + pluginId
                    + " pid=" + pid + " for " + capName);
            publishSpawned(pluginId, pid, capName);

            // Watch for unexpected process exit
            process.onExit().thenAccept(p2 -> {
                if (!managed.containsKey(pluginId)) return; // already removed by handleStop()
                managed.remove(pluginId);
                timers.remove(pluginId);
                lastUsed.remove(pluginId);
                int exitCode = p2.exitValue();
                System.out.println("[PROC-MGR] Plugin died unexpectedly: "
                        + pluginId + " exitCode=" + exitCode);
                try {
                    bus.route(Event.of("system.plugin.died",
                            Event.MAPPER.writeValueAsString(Map.of(
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

    void publishSpawned(String pluginId, long pid, String capabilityName) {
        try {
            bus.route(Event.of("system.plugin.spawned",
                    Event.MAPPER.writeValueAsString(Map.of(
                            "pluginId",    pluginId,
                            "pid",         pid,
                            "capability",  capabilityName)),
                    "kernel"));
        } catch (Exception ignored) {}
    }

    // ── Agent spawn (agent.spawn — AgentSpawner delegations) ─────────────────

    void handleSpawn(Event event) throws Exception {
        JsonNode p            = Event.MAPPER.readTree(event.payload());
        String   agentId      = p.path("agentId").asText();
        String   skillsDirRaw = p.path("skillsDir").asText();
        String   correlationId = p.has("correlationId") ? p.get("correlationId").asText() : null;

        if (agentId.isBlank() || skillsDirRaw.isBlank()) {
            System.err.println("[PROC-MGR] agent.spawn missing agentId or skillsDir — ignored");
            return;
        }
        if (managed.containsKey(agentId)) {
            System.out.println("[PROC-MGR] agent.spawn: " + agentId + " already managed — ignored");
            return;
        }

        // Resolve skillsDir: absolute if given, else relative to mk7Root
        Path skillsDir = Path.of(skillsDirRaw);
        if (!skillsDir.isAbsolute()) skillsDir = mk7Root.resolve(skillsDirRaw).normalize();

        // Read launch.command from the agent's plugin.json (fresh from disk for agents)
        Path     pluginJsonPath = skillsDir.resolve("plugin.json");
        JsonNode pluginCfg      = Event.MAPPER.readTree(pluginJsonPath.toFile());
        JsonNode cmdNode        = pluginCfg.path("launch").path("command");
        if (!cmdNode.isArray() || cmdNode.isEmpty()) {
            System.err.println("[PROC-MGR] No launch.command in "
                    + pluginJsonPath + " — cannot spawn " + agentId);
            return;
        }

        String[] command = new String[cmdNode.size()];
        for (int i = 0; i < command.length; i++) command[i] = cmdNode.get(i).asText();

        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(skillsDir.toFile())
                .inheritIO(); // agents get interactive I/O

        if (correlationId != null) pb.environment().put("MK7_SPAWN_CORRELATION_ID", correlationId);

        Process process = pb.start();
        long    pid     = process.pid();

        managed.put(agentId, new ManagedProcess(agentId, pid, skillsDir, process));
        System.out.println("[PROC-MGR] Spawned agent: " + agentId
                + " pid=" + pid + " skillsDir=" + skillsDir);

        bus.route(Event.of("system.plugin.spawned",
                Event.MAPPER.writeValueAsString(Map.of(
                        "agentId",   agentId,
                        "pid",       pid,
                        "skillsDir", skillsDir.toString())),
                "kernel"));

        process.onExit().thenAccept(p2 -> {
            if (!managed.containsKey(agentId)) return;
            managed.remove(agentId);
            timers.remove(agentId);
            int exitCode = p2.exitValue();
            System.out.println("[PROC-MGR] Agent exited: " + agentId + " exitCode=" + exitCode);
            try {
                bus.route(Event.of("system.plugin.died",
                        Event.MAPPER.writeValueAsString(Map.of(
                                "agentId",  agentId,
                                "pid",      pid,
                                "exitCode", exitCode)),
                        "kernel"));
            } catch (Exception ignored) {}
        });
    }

    // ── Ready ─────────────────────────────────────────────────────────────────

    void handleReady(Event event) throws Exception {
        JsonNode p      = Event.MAPPER.readTree(event.payload());
        String   agentId = p.has("agentId") ? p.get("agentId").asText() : null;
        if (agentId != null && managed.containsKey(agentId)) {
            System.out.println("[PROC-MGR] agent.ready confirmed: " + agentId
                    + " corrId=" + event.correlationId());
        }
    }

    // ── Idle timer (agent.idle/busy — used by AgentSpawner flow) ─────────────

    void handleIdle(Event event) throws Exception {
        JsonNode p      = Event.MAPPER.readTree(event.payload());
        String agentId  = p.has("agentId") ? p.get("agentId").asText() : null;
        if (agentId == null || !managed.containsKey(agentId)) return;

        ManagedProcess mp = managed.get(agentId);

        // Read idleTimeout from plugin.json fresh (allows dynamic config changes)
        int timeout = 0;
        try {
            timeout = Event.MAPPER.readTree(mp.pluginDir().resolve("plugin.json").toFile())
                    .path("lifecycle").path("idleTimeoutSeconds").asInt(0);
        } catch (Exception e) {
            // Fallback to catalog value
            List<PluginCatalog.CatalogEntry> entries = catalog.getByPluginId(agentId);
            if (!entries.isEmpty()) timeout = entries.get(0).idleTimeoutSeconds();
        }
        if (timeout <= 0) return;

        final String agent   = agentId;
        final int    seconds = timeout;
        ScheduledFuture<?> timer = scheduler.schedule(() -> {
            try {
                bus.route(Event.of("agent.stop",
                        Event.MAPPER.writeValueAsString(Map.of(
                                "agentId", agent, "reason", "idle-timeout")),
                        "kernel"));
            } catch (Exception ignored) {}
        }, seconds, TimeUnit.SECONDS);

        timers.put(agentId, timer);
        System.out.println("[PROC-MGR] Idle timer set for " + agentId + " (" + seconds + "s)");
    }

    void handleBusy(Event event) throws Exception {
        JsonNode p      = Event.MAPPER.readTree(event.payload());
        String agentId  = p.has("agentId") ? p.get("agentId").asText() : null;
        if (agentId == null) return;
        ScheduledFuture<?> timer = timers.remove(agentId);
        if (timer != null) {
            timer.cancel(false);
            System.out.println("[PROC-MGR] Idle timer cancelled for " + agentId);
        }
    }

    // ── Graceful stop ─────────────────────────────────────────────────────────

    void handleStop(Event event) throws Exception {
        JsonNode p      = Event.MAPPER.readTree(event.payload());
        String agentId  = p.has("agentId") ? p.get("agentId").asText() : null;
        String reason   = p.has("reason")  ? p.get("reason").asText()  : "requested";
        if (agentId == null) return;

        ManagedProcess mp = managed.remove(agentId);
        if (mp == null) return;

        ScheduledFuture<?> timer = timers.remove(agentId);
        if (timer != null) timer.cancel(false);
        lastUsed.remove(agentId);

        mp.process().destroy();
        boolean exited = mp.process().waitFor(5, TimeUnit.SECONDS);
        if (!exited) mp.process().destroyForcibly();

        System.out.println("[PROC-MGR] Stopped: " + agentId
                + " pid=" + mp.pid() + " reason=" + reason);

        bus.route(Event.of("system.plugin.stopped",
                Event.MAPPER.writeValueAsString(Map.of(
                        "agentId", agentId,
                        "pid",     mp.pid(),
                        "reason",  reason)),
                "kernel"));
    }

    // ── Plugin list (P2: "capability.system.plugin.list" literal event type) ──

    void handlePluginList(Event event) throws Exception {
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

        bus.route(Event.withCorrelation("capability.result",
                Event.MAPPER.writeValueAsString(
                        Map.of("result", Event.MAPPER.writeValueAsString(list))),
                "kernel", event.correlationId(), event.sessionId()));
    }

    // ── Idle-kill periodic check ──────────────────────────────────────────────

    void checkIdlePlugins() {
        long now = System.currentTimeMillis();
        for (String pluginId : managed.keySet()) {
            // Only enforce idle-kill for on-demand plugins (persistent plugins don't idle-kill)
            List<PluginCatalog.CatalogEntry> entries = catalog.getByPluginId(pluginId);
            if (entries.isEmpty()) continue;
            // Use the maximum idleTimeout across all capabilities (some may differ)
            int timeoutSec = entries.stream()
                    .mapToInt(PluginCatalog.CatalogEntry::idleTimeoutSeconds)
                    .max().orElse(0);
            boolean onDemand = entries.stream().anyMatch(PluginCatalog.CatalogEntry::onDemand);
            if (timeoutSec <= 0 || !onDemand) continue;

            long idleMs = now - lastUsed.getOrDefault(pluginId, 0L);
            if (idleMs > (long) timeoutSec * 1000) {
                System.out.println("[PROC-MGR] Idle timeout: killing " + pluginId
                        + " (idle " + idleMs / 1000 + "s, limit " + timeoutSec + "s)");
                try {
                    bus.route(Event.of("agent.stop",
                            Event.MAPPER.writeValueAsString(Map.of(
                                    "agentId", pluginId, "reason", "idle-timeout")),
                            "kernel"));
                } catch (Exception ignored) {}
            }
        }
    }
}
