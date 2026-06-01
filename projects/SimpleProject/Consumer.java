///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../kernel/KernelEvent.java

import com.fasterxml.jackson.databind.JsonNode;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.Map;

/**
 * Consumer — Connects directly to the Kernel UDS and processes data.item events.
 *
 * Demonstrates raw kernel communication without PluginBase or plugin.json.
 * Subscribes to data.item and data.done, processes each item as it arrives,
 * and exits cleanly when the producer signals data.done.
 */
public class Consumer {

    // ── Event type constants ──────────────────────────────────────────────────
    static final String EVT_DATA_ITEM = "data.item";
    static final String EVT_DATA_DONE = "data.done";

    // ── Plugin identity ───────────────────────────────────────────────────────
    static final String SOURCE_ID = "consumer";

    public static void main(String[] args) throws Exception {
        KernelEvent.initLogging();
        System.out.println("[CONSUMER] Starting...");

        var addr = UnixDomainSocketAddress.of(Path.of(KernelEvent.DEFAULT_SOCKET));
        try (var ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            ch.connect(addr);
            var out = Channels.newOutputStream(ch);
            var in  = Channels.newInputStream(ch);

            // Register — subscribe to both event types we care about
            String regPayload = KernelEvent.MAPPER.writeValueAsString(Map.of(
                    "id",         SOURCE_ID,
                    "subscribes", new String[]{EVT_DATA_ITEM, EVT_DATA_DONE},
                    "pid",        ProcessHandle.current().pid()));
            KernelEvent.writeFrame(out, KernelEvent.MAPPER.writeValueAsString(
                    KernelEvent.of("plugin.register", regPayload, SOURCE_ID)));

            System.out.println("[CONSUMER] Registered. Waiting for items...\n");

            int received = 0;
            String json;
            while ((json = KernelEvent.readFrame(in)) != null) {
                KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);

                switch (event.type()) {
                    case EVT_DATA_ITEM -> {
                        JsonNode p     = KernelEvent.MAPPER.readTree(event.payload());
                        int    seq     = p.path("seq").asInt();
                        String value   = p.path("value").asText();
                        received++;
                        System.out.printf("[CONSUMER] ← [%2d] %s%n", seq, value);
                    }
                    case EVT_DATA_DONE -> {
                        JsonNode p = KernelEvent.MAPPER.readTree(event.payload());
                        int total  = p.path("total").asInt();
                        System.out.println();
                        System.out.println("[CONSUMER] ✅ Stream complete — received "
                                + received + "/" + total + " items.");
                        return;
                    }
                }
            }
        }
    }
}
