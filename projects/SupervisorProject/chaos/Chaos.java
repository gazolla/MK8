///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../../kernel/KernelEvent.java
//SOURCES ../../../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../../../kernel/interceptors/plugin/PluginBase.java

import com.fasterxml.jackson.databind.JsonNode;
import java.io.OutputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Chaos — Crash-and-restart tester for the PluginInterceptor's supervision.
 *
 * Triggered by its own plugin.ready (always delivered), so it is immune to the
 * boot-order race of observing another plugin's ready. It then:
 *   1. queries system.plugin.list to discover the Heartbeat's pid;
 *   2. kills that process (simulating a crash);
 *   3. waits for the kernel-announced disconnect to drive an auto-restart,
 *      observed as a system.plugin.spawned event for "heartbeat";
 *   4. invokes the built-in system.plugin.restart capability and awaits its result.
 * Then it prints a summary and exits.
 *
 * capability.invoke calls are request-reply: each carries a fresh correlationId
 * and a future completed when the matching capability.result arrives.
 */
public class Chaos {

    static final String EVT_PLUGIN_READY = "plugin.ready";
    static final String EVT_SPAWNED      = "system.plugin.spawned";
    static final String EVT_CRASHED      = "system.plugin.crashed";
    static final String EVT_CAP_RESULT   = "capability.result";

    static final String SOURCE_ID   = "chaos";
    static final String TARGET      = "heartbeat";
    static final String CAP_LIST    = "system.plugin.list";
    static final String CAP_RESTART = "system.plugin.restart";

    static final long WARMUP_MS = 1_500;
    static final long REPLY_S   = 5;
    static final long WAIT_S    = 12;

    final AtomicBoolean started = new AtomicBoolean(false);
    final ConcurrentHashMap<String, CompletableFuture<KernelEvent>> pending = new ConcurrentHashMap<>();
    final CountDownLatch autoRestarted = new CountDownLatch(1);

    public static void main(String[] args) throws Exception {
        KernelEvent.initLogging();
        System.out.println("[CHAOS] Starting...");
        new Chaos().start();
    }

    void start() throws Exception {
        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, this::handle);
    }

    void handle(String json, OutputStream out) throws Exception {
        KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);
        switch (event.type()) {
            case EVT_PLUGIN_READY -> { if (started.compareAndSet(false, true)) runSequence(out); }
            case EVT_SPAWNED      -> onSpawned(event);
            case EVT_CRASHED      -> System.out.println("[CHAOS] ⛔ system.plugin.crashed: " + event.payload());
            case EVT_CAP_RESULT   -> resolve(event);
        }
    }

    void onSpawned(KernelEvent event) throws Exception {
        JsonNode p = KernelEvent.MAPPER.readTree(event.payload());
        if (TARGET.equals(p.path("pluginId").asText(""))) autoRestarted.countDown();
    }

    void resolve(KernelEvent event) {
        String corrId = event.correlationId();
        CompletableFuture<KernelEvent> f = corrId == null ? null : pending.remove(corrId);
        if (f != null) f.complete(event);
    }

    void runSequence(OutputStream out) {
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(WARMUP_MS);
                System.out.println("\n[CHAOS] === CRASH DETECTION & AUTO-RESTART TEST ===\n");

                long pid = findHeartbeatPid(out);
                System.out.println("[CHAOS] 1) Heartbeat discovered via system.plugin.list — pid=" + pid);
                if (pid <= 0) { System.out.println("[CHAOS] ⚠️  heartbeat not found"); System.exit(1); }

                System.out.println("[CHAOS] 2) Simulating crash — killing pid=" + pid);
                ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);

                boolean autoOk = autoRestarted.await(WAIT_S, TimeUnit.SECONDS);
                long newPid = autoOk ? findHeartbeatPid(out) : -1;
                System.out.println("[CHAOS] 3) Auto-restart " + (autoOk
                        ? "✅ observed (new pid=" + newPid + ", was " + pid + ")"
                        : "⚠️  NOT observed within " + WAIT_S + "s"));

                System.out.println("\n[CHAOS] 4) Invoking manual capability " + CAP_RESTART + " {id:heartbeat}...");
                KernelEvent r = invoke(CAP_RESTART, Map.of("name", CAP_RESTART, "id", TARGET), out);
                String manualMsg = KernelEvent.MAPPER.readTree(r.payload()).path("result").asText("?");
                boolean manOk = manualMsg.startsWith("Restarted");
                System.out.println("[CHAOS] 5) Manual restart " + (manOk ? "✅" : "⚠️ ") + " result: \"" + manualMsg + "\"");

                System.out.println("\n[CHAOS] " + (autoOk && manOk
                        ? "✅ Supervision test complete — crash detection, auto-restart, and manual restart all work."
                        : "⚠️  Supervision test finished with warnings."));
                System.exit(autoOk && manOk ? 0 : 1);
            } catch (Exception e) {
                System.err.println("[CHAOS] Error: " + e.getMessage());
                System.exit(1);
            }
        });
    }

    /** Queries system.plugin.list and returns the heartbeat's pid (-1 if absent). */
    long findHeartbeatPid(OutputStream out) throws Exception {
        KernelEvent res = invoke(CAP_LIST, Map.of("name", CAP_LIST), out);
        // capability.result wraps {"result": "<json-array-string>"}
        String arrJson = KernelEvent.MAPPER.readTree(res.payload()).path("result").asText("[]");
        for (JsonNode e : KernelEvent.MAPPER.readTree(arrJson)) {
            if (TARGET.equals(e.path("pluginId").asText("")) && e.path("alive").asBoolean(false))
                return e.path("pid").asLong(-1);
        }
        return -1;
    }

    /** Publishes a correlated capability.invoke and blocks until its capability.result arrives. */
    KernelEvent invoke(String name, Map<String, Object> payloadMap, OutputStream out) throws Exception {
        String corrId = UUID.randomUUID().toString();
        CompletableFuture<KernelEvent> f = new CompletableFuture<>();
        pending.put(corrId, f);
        try {
            String payload = KernelEvent.MAPPER.writeValueAsString(payloadMap);
            PluginBase.publish(KernelEvent.withCorrelation("capability.invoke", payload,
                    SOURCE_ID, corrId, null), out);
            return f.get(REPLY_S, TimeUnit.SECONDS);
        } finally {
            pending.remove(corrId);
        }
    }
}
