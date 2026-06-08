///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../../kernel/KernelEvent.java
//SOURCES ../../../kernel/Log.java
//SOURCES ../../../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../../../kernel/interceptors/plugin/PluginBase.java

import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Writer — Persistent plugin that writes facts to the shared blackboard.
 *
 * On the first plugin.ready signal it performs an immediate batch of writes
 * (a plain entry, a tagged entry, and an optimistic-locking sequence) so the
 * Reader — a separate OS process — can read them back, proving the blackboard
 * is genuine cross-process shared memory. After a short delay it writes one more
 * "live" entry, which fires blackboard.updated.session.live for any reactive
 * subscriber (the Reader) that is already connected.
 *
 * Subscribes to blackboard.write.ok / blackboard.write.conflict to log the
 * outcome of its conditional (versioned) writes.
 */
public class Writer {

    // ── Event type constants ──────────────────────────────────────────────────
    static final String EVT_PLUGIN_READY   = "plugin.ready";
    static final String EVT_WRITE          = "blackboard.write";
    static final String EVT_WRITE_OK       = "blackboard.write.ok";
    static final String EVT_WRITE_CONFLICT = "blackboard.write.conflict";

    // ── Identity / scope ──────────────────────────────────────────────────────
    static final String SOURCE_ID = "writer";
    static final String SCOPE     = "session";
    static final String SCOPE_ID  = "demo";

    // Delay before the live write so the Reader has time to subscribe to updated.*
    static final long LIVE_DELAY_MS = 3_000;

    final AtomicBoolean started = new AtomicBoolean(false);

    public static void main(String[] args) throws Exception {
        KernelEvent.initLogging();
        Log.rawInfo("[WRITER] Starting...");
        new Writer().start();
    }

    void start() throws Exception {
        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, this::handle);
    }

    void handle(String json, OutputStream out) throws Exception {
        Log.configure(SOURCE_ID, out);
        KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);
        switch (event.type()) {
            case EVT_PLUGIN_READY   -> { if (started.compareAndSet(false, true)) onReady(out); }
            case EVT_WRITE_OK       -> log("✅ write.ok       " + event.payload());
            case EVT_WRITE_CONFLICT -> log("⛔ write.conflict " + event.payload());
        }
    }

    void onReady(OutputStream out) {
        // Fire in a virtual thread so we never block the PluginBase event loop
        Thread.ofVirtual().start(() -> {
            try {
                // Plain entries for the Reader to read back / query
                write("greeting", "hello from writer", List.of(), out);
                write("topic",    "kernel internals",  List.of("doc"), out);

                // Optimistic locking: create v1, update to v2, then a stale write conflicts
                writeConditional("counter", "1",     0, out); // create → v1
                writeConditional("counter", "2",     1, out); // update → v2
                writeConditional("counter", "stale", 1, out); // expects v1, but it's v2 → conflict

                // Live write after the Reader has had time to subscribe to updated.*
                Thread.sleep(LIVE_DELAY_MS);
                write("live", "late update", List.of(), out);
                log("→ live write published (fires blackboard.updated." + SCOPE + ".live)");
            } catch (Exception e) {
                Log.rawError("[WRITER] Error: " + e.getMessage());
            }
        });
    }

    void write(String key, String value, List<String> tags, OutputStream out) throws Exception {
        String payload = KernelEvent.MAPPER.writeValueAsString(Map.of(
                "key", key, "scope", SCOPE, "scopeId", SCOPE_ID, "value", value, "tags", tags));
        PluginBase.publish(KernelEvent.of(EVT_WRITE, payload, SOURCE_ID), out);
        log("→ write " + key + "=\"" + value + "\"" + (tags.isEmpty() ? "" : " tags=" + tags));
    }

    void writeConditional(String key, String value, long expectedVersion, OutputStream out) throws Exception {
        String payload = KernelEvent.MAPPER.writeValueAsString(Map.of(
                "key", key, "scope", SCOPE, "scopeId", SCOPE_ID, "value", value,
                "expectedVersion", expectedVersion));
        PluginBase.publish(
                KernelEvent.withCorrelation(EVT_WRITE, payload, SOURCE_ID,
                        UUID.randomUUID().toString(), SCOPE_ID), out);
        log("→ write " + key + "=\"" + value + "\" expectedVersion=" + expectedVersion);
    }

    static void log(String msg) { Log.rawInfo("[WRITER] " + msg); }
}
