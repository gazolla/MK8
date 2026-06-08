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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Consumer — Persistent plugin that receives and processes data.item events.
 *
 * Uses PluginBase for registration and event-loop management (plugin.json declares
 * subscriptions to data.item and data.done). Counts items as they arrive and
 * prints a summary when the data.done sentinel is received, then exits cleanly.
 * Routing is handled entirely by the Kernel's broadcast — no interceptors required.
 */
public class Consumer {

    // ── Event type constants ──────────────────────────────────────────────────
    static final String EVT_DATA_ITEM = "data.item";
    static final String EVT_DATA_DONE = "data.done";

    final AtomicInteger received = new AtomicInteger(0);

    public static void main(String[] args) throws Exception {
        KernelEvent.initLogging();
        Log.rawInfo("[CONSUMER] Starting...");
        new Consumer().start();
    }

    void start() throws Exception {
        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, this::handle);
    }

    void handle(String json, OutputStream out) throws Exception {
        Log.configure("consumer", out);
        KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);

        switch (event.type()) {
            case EVT_DATA_ITEM -> {
                JsonNode p   = KernelEvent.MAPPER.readTree(event.payload());
                int    seq   = p.path("seq").asInt();
                String value = p.path("value").asText();
                System.out.printf("[CONSUMER] ← [%2d] %s%n", seq, value);
                received.incrementAndGet();
            }
            case EVT_DATA_DONE -> {
                JsonNode p = KernelEvent.MAPPER.readTree(event.payload());
                int total  = p.path("total").asInt();
                Log.rawInfo("");
                Log.rawInfo("[CONSUMER] ✅ Stream complete — received "
                        + received.get() + "/" + total + " items.");
                System.exit(0);
            }
        }
    }
}
