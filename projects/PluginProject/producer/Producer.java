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
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Producer — Persistent plugin that publishes data.item events to the Kernel bus.
 *
 * Uses PluginBase for registration and event-loop management (plugin.json drives
 * the subscription to plugin.ready). On the first plugin.ready signal, waits
 * 1.5 s for the Consumer to connect, then broadcasts ITEM_COUNT items followed
 * by a data.done sentinel. No CapabilityInterceptor needed — the Kernel routes
 * data.* events directly to all registered subscribers.
 */
public class Producer {

    // ── Event type constants ──────────────────────────────────────────────────
    static final String EVT_PLUGIN_READY = "plugin.ready";
    static final String EVT_DATA_ITEM    = "data.item";
    static final String EVT_DATA_DONE    = "data.done";

    // ── Plugin identity ───────────────────────────────────────────────────────
    static final String SOURCE_ID = "producer";

    // ── Stream parameters ─────────────────────────────────────────────────────
    static final int  ITEM_COUNT       = 10;
    static final long DELAY_MS         = 500;
    static final long CONSUMER_WARMUP_MS = 1_500;

    // Guards against re-triggering if multiple plugin.ready events arrive
    final AtomicBoolean started = new AtomicBoolean(false);

    public static void main(String[] args) throws Exception {
        KernelEvent.initLogging();
        Log.rawInfo("[PRODUCER] Starting...");
        new Producer().start();
    }

    void start() throws Exception {
        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, this::handle);
    }

    void handle(String json, OutputStream out) throws Exception {
        Log.configure(SOURCE_ID, out);
        KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);

        if (EVT_PLUGIN_READY.equals(event.type()) && started.compareAndSet(false, true)) {
            // Fire in a virtual thread so we don't block the PluginBase event loop
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(CONSUMER_WARMUP_MS); // give Consumer time to register
                    sendItems(out);
                } catch (Exception e) {
                    Log.rawError("[PRODUCER] Error: " + e.getMessage());
                }
            });
        }
    }

    void sendItems(OutputStream out) throws Exception {
        Log.rawInfo("[PRODUCER] Dispatching " + ITEM_COUNT + " items...\n");

        for (int i = 1; i <= ITEM_COUNT; i++) {
            String payload = KernelEvent.MAPPER.writeValueAsString(
                    Map.of("seq", i, "value", "Item-" + i));
            PluginBase.publish(KernelEvent.of(EVT_DATA_ITEM, payload, SOURCE_ID), out);
            Log.rawInfo("[PRODUCER] → sent item #" + i + "  (value=Item-" + i + ")");
            Thread.sleep(DELAY_MS);
        }

        PluginBase.publish(KernelEvent.of(EVT_DATA_DONE,
                KernelEvent.MAPPER.writeValueAsString(Map.of("total", ITEM_COUNT)),
                SOURCE_ID), out);
        Log.rawInfo("\n[PRODUCER] → sent data.done. All " + ITEM_COUNT + " items dispatched.");

        // publish() writes synchronously, so the data.done frame is already on the
        // socket. Exit cleanly — mirrors Consumer's System.exit(0) on data.done —
        // so the PluginBase event loop stops and Start's foreground wait returns.
        System.exit(0);
    }
}
