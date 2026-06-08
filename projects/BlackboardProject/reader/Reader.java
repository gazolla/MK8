///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../../kernel/KernelEvent.java
//SOURCES ../../../kernel/Log.java
//SOURCES ../../../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../../../kernel/interceptors/plugin/PluginBase.java

import com.fasterxml.jackson.databind.JsonNode;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reader — Transient client that reads facts written by the Writer process.
 *
 * After a short warm-up (giving the Writer time to populate the blackboard) it
 * exercises the BlackboardInterceptor end-to-end across process boundaries:
 *   1. read HIT  — a key the Writer stored (proves cross-process shared memory)
 *   2. read MISS — a key that does not exist
 *   3. query     — entries filtered by tag
 *   4. reactive  — catches the Writer's live blackboard.updated.session.* event
 * Then it prints a summary and exits.
 *
 * read/query are request-reply: each carries a fresh correlationId and a future
 * that is completed when the matching .result frame arrives (KernelEvent.reply
 * preserves the correlationId, so the BlackboardInterceptor's direct reply lands
 * on the right caller).
 */
public class Reader {

    // ── Event type constants ──────────────────────────────────────────────────
    static final String EVT_PLUGIN_READY  = "plugin.ready";
    static final String EVT_READ          = "blackboard.read";
    static final String EVT_QUERY         = "blackboard.query";
    static final String EVT_READ_RESULT   = "blackboard.read.result";
    static final String EVT_QUERY_RESULT  = "blackboard.query.result";
    static final String EVT_UPDATED_PREFIX = "blackboard.updated.";

    // ── Identity / scope ──────────────────────────────────────────────────────
    static final String SOURCE_ID = "reader";
    static final String SCOPE     = "session";
    static final String SCOPE_ID  = "demo";

    // ── Timing ────────────────────────────────────────────────────────────────
    static final long WARMUP_MS         = 1_200;
    static final long REPLY_TIMEOUT_S   = 2;
    static final long UPDATED_TIMEOUT_S = 10;

    // correlationId → future awaiting the matching .result frame
    final ConcurrentHashMap<String, CompletableFuture<KernelEvent>> pending = new ConcurrentHashMap<>();
    final CountDownLatch updatedLatch = new CountDownLatch(1);
    final AtomicBoolean started = new AtomicBoolean(false);

    public static void main(String[] args) throws Exception {
        KernelEvent.initLogging();
        Log.rawInfo("[READER] Starting...");
        new Reader().start();
    }

    void start() throws Exception {
        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, this::handle);
    }

    void handle(String json, OutputStream out) throws Exception {
        Log.configure(SOURCE_ID, out);
        KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);
        String type = event.type();

        if (EVT_PLUGIN_READY.equals(type)) {
            if (started.compareAndSet(false, true)) onReady(out);
        } else if (EVT_READ_RESULT.equals(type) || EVT_QUERY_RESULT.equals(type)) {
            resolve(event);
        } else if (type.startsWith(EVT_UPDATED_PREFIX)) {
            Log.rawInfo("[READER] 🔔 reactive notification: " + type + " → " + event.payload());
            updatedLatch.countDown();
        }
    }

    void resolve(KernelEvent event) {
        String corrId = event.correlationId();
        CompletableFuture<KernelEvent> f = corrId == null ? null : pending.remove(corrId);
        if (f != null) f.complete(event);
    }

    void onReady(OutputStream out) {
        // Fire in a virtual thread so we never block the PluginBase event loop
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(WARMUP_MS); // give the Writer time to populate the blackboard

                Log.rawInfo("\n[READER] === BLACKBOARD ROUND-TRIP TEST ===\n");

                // 1. Cross-process read HIT
                printRead("read HIT  greeting", read("greeting", out));

                // 2. read MISS
                printRead("read MISS missing ", read("missing", out));

                // 3. query by tag
                JsonNode entries = KernelEvent.MAPPER
                        .readTree(query(List.of("doc"), out).payload()).path("entries");
                Log.rawInfo("[READER] query tag=[doc] → " + entries.size()
                        + " entr" + (entries.size() == 1 ? "y" : "ies") + ": " + entries);

                // 4. reactive notification from the Writer's live write
                Log.rawInfo("\n[READER] waiting for live update notification...");
                boolean got = updatedLatch.await(UPDATED_TIMEOUT_S, TimeUnit.SECONDS);
                Log.rawInfo("[READER] " + (got ? "✅ notification received"
                                                      : "⚠️  no notification (timeout)"));

                Log.rawInfo("\n[READER] ✅ Blackboard test complete.");
                System.exit(0);
            } catch (Exception e) {
                Log.rawError("[READER] Error: " + e.getMessage());
                System.exit(1);
            }
        });
    }

    KernelEvent read(String key, OutputStream out) throws Exception {
        return request(EVT_READ, Map.of("key", key, "scope", SCOPE, "scopeId", SCOPE_ID), out);
    }

    KernelEvent query(List<String> tags, OutputStream out) throws Exception {
        return request(EVT_QUERY, Map.of("scope", SCOPE, "scopeId", SCOPE_ID, "tags", tags), out);
    }

    /** Publishes a correlated request and blocks until the matching .result arrives. */
    KernelEvent request(String type, Map<String, Object> payloadMap, OutputStream out) throws Exception {
        String corrId = UUID.randomUUID().toString();
        CompletableFuture<KernelEvent> future = new CompletableFuture<>();
        pending.put(corrId, future);
        try {
            String payload = KernelEvent.MAPPER.writeValueAsString(payloadMap);
            PluginBase.publish(KernelEvent.withCorrelation(type, payload, SOURCE_ID, corrId, null), out);
            return future.get(REPLY_TIMEOUT_S, TimeUnit.SECONDS);
        } finally {
            pending.remove(corrId);
        }
    }

    void printRead(String label, KernelEvent ev) throws Exception {
        JsonNode r = KernelEvent.MAPPER.readTree(ev.payload());
        if (!r.path("found").asBoolean(true)) {
            Log.rawInfo("[READER] " + label + " → (not found)");
        } else {
            // Stored values are JSON-encoded; decode the value node for display
            String value = KernelEvent.MAPPER.readTree(r.path("value").asText()).asText();
            Log.rawInfo("[READER] " + label + " → value=\"" + value + "\" v" + r.path("version").asLong());
        }
    }
}
