// Shared dev helper — included via //SOURCES by the Kiwi dev CLIs. No JBang header.

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

/**
 * DevClient — minimal transient kernel client for the Kiwi dev tools.
 *
 * The MK7 dev CLIs connected to the kernel through the monolithic {@code Event.connectAndRun}.
 * MK8 split that into {@code KernelEvent} + {@code PluginConfig} + plugin-side {@code PluginBase};
 * since the dev tools are short-lived clients (not managed plugins) and we must NOT touch
 * {@code kernel/} or {@code agent/}, this Kiwi-local helper reimplements just the bit they need:
 * open the UDS, register as a transient subscriber, and read/write length-prefixed frames.
 *
 * Routing model (see kernel/Kernel.java#register): the FIRST frame a connection sends must be a
 * {@code plugin.register} whose payload is a config carrying {@code id} + {@code subscribes}
 * (+ optional {@code wildcardSubscribes}). The kernel then delivers every subscribed event to
 * this socket. No plugin.json on disk and no lifecycle management are involved.
 *
 * Depends only on {@code KernelEvent} (MAPPER, frame protocol, factories, DEFAULT_SOCKET).
 */
public class DevClient implements AutoCloseable {

    private final SocketChannel channel;
    private final InputStream   in;
    private final OutputStream  out;
    public  final String        clientId;

    private DevClient(SocketChannel channel, String clientId) {
        this.channel  = channel;
        this.in       = Channels.newInputStream(channel);
        this.out      = Channels.newOutputStream(channel);
        this.clientId = clientId;
    }

    /** Open the kernel UDS and register as a transient client routed the given subscribes. */
    public static DevClient connect(String clientId, List<String> subscribes) throws Exception {
        var addr = UnixDomainSocketAddress.of(Path.of(KernelEvent.DEFAULT_SOCKET));
        SocketChannel ch = SocketChannel.open(addr);
        DevClient c = new DevClient(ch, clientId);
        c.register(subscribes);
        return c;
    }

    private void register(List<String> subscribes) throws Exception {
        ObjectNode cfg = KernelEvent.MAPPER.createObjectNode();
        cfg.put("id", clientId);
        cfg.put("type", "system");
        cfg.put("version", "1.0.0");
        ArrayNode subs = cfg.putArray("subscribes");
        if (subscribes != null) subscribes.forEach(subs::add);
        cfg.put("pid", ProcessHandle.current().pid());
        send(KernelEvent.of("plugin.register", KernelEvent.MAPPER.writeValueAsString(cfg), clientId));
    }

    // ── Frame I/O ───────────────────────────────────────────────────────────────

    public void send(KernelEvent ev) throws Exception {
        synchronized (out) {
            KernelEvent.writeFrame(out, KernelEvent.MAPPER.writeValueAsString(ev));
        }
    }

    /** Read the next frame and parse it, or return null on EOF. */
    public KernelEvent receive() throws Exception {
        String json = KernelEvent.readFrame(in);
        return json == null ? null : KernelEvent.MAPPER.readValue(json, KernelEvent.class);
    }

    /**
     * Read frames until {@code match} accepts one or the timeout elapses.
     * Note: like the MK7 dev tools, the deadline is checked between frames — it relies on the
     * kernel's normal event traffic to wake the loop. Returns the matching event, or null.
     */
    public KernelEvent await(long timeoutMs, Predicate<KernelEvent> match) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        KernelEvent ev;
        while ((ev = receive()) != null) {
            if (match.test(ev)) return ev;
            if (System.currentTimeMillis() > deadline) return null;
        }
        return null;
    }

    /** Convenience: send one event, then await a matching reply. */
    public KernelEvent request(KernelEvent ev, long timeoutMs, Predicate<KernelEvent> match) throws Exception {
        send(ev);
        return await(timeoutMs, match);
    }

    @Override public void close() {
        try { channel.close(); } catch (Exception ignored) {}
    }
}
