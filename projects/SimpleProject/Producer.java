///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../kernel/KernelEvent.java

import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.Map;

/**
 * Producer — Connects directly to the Kernel UDS and broadcasts data.item events.
 *
 * Demonstrates raw kernel communication without PluginBase or plugin.json.
 * Registers itself, sends ITEM_COUNT numbered items at DELAY_MS intervals,
 * then broadcasts data.done so the Consumer knows when to stop.
 */
public class Producer {

    // ── Event type constants ──────────────────────────────────────────────────
    static final String EVT_DATA_ITEM = "data.item";
    static final String EVT_DATA_DONE = "data.done";

    // ── Plugin identity ───────────────────────────────────────────────────────
    static final String SOURCE_ID = "producer";

    // ── Stream parameters ─────────────────────────────────────────────────────
    static final int  ITEM_COUNT = 10;
    static final long DELAY_MS   = 500;

    public static void main(String[] args) throws Exception {
        KernelEvent.initLogging();
        System.out.println("[PRODUCER] Starting...");

        var addr = UnixDomainSocketAddress.of(Path.of(KernelEvent.DEFAULT_SOCKET));
        try (var ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            ch.connect(addr);
            var out = Channels.newOutputStream(ch);

            // Register with kernel — no subscriptions needed (producer only sends)
            String regPayload = KernelEvent.MAPPER.writeValueAsString(Map.of(
                    "id",         SOURCE_ID,
                    "subscribes", new String[0],
                    "pid",        ProcessHandle.current().pid()));
            KernelEvent.writeFrame(out, KernelEvent.MAPPER.writeValueAsString(
                    KernelEvent.of("plugin.register", regPayload, SOURCE_ID)));

            System.out.println("[PRODUCER] Registered. Sending " + ITEM_COUNT + " items...\n");

            for (int i = 1; i <= ITEM_COUNT; i++) {
                String payload = KernelEvent.MAPPER.writeValueAsString(Map.of(
                        "seq",   i,
                        "value", "Item-" + i));
                KernelEvent.writeFrame(out, KernelEvent.MAPPER.writeValueAsString(
                        KernelEvent.of(EVT_DATA_ITEM, payload, SOURCE_ID)));
                System.out.println("[PRODUCER] → sent item #" + i + " (value=Item-" + i + ")");
                Thread.sleep(DELAY_MS);
            }

            // Signal end-of-stream
            KernelEvent.writeFrame(out, KernelEvent.MAPPER.writeValueAsString(
                    KernelEvent.of(EVT_DATA_DONE,
                            KernelEvent.MAPPER.writeValueAsString(Map.of("total", ITEM_COUNT)),
                            SOURCE_ID)));
            System.out.println("\n[PRODUCER] → sent data.done. All items dispatched.");
        }
    }
}
