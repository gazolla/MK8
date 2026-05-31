///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES KernelEvent.java
//SOURCES PluginManager.java
//SOURCES CapabilityInterceptor.java
//SOURCES IdempotencyInterceptor.java

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * Kernel — The central event bus and connection manager of the MK8 MicroKernel.
 *
 * The Kernel runs a Unix Domain Socket (UDS) server to accept plugin connections.
 * Each connection gets dedicated virtual reader and writer threads to decouple
 * routing logic from socket IO, preventing head-of-line blocking across plugins.
 * It maintains concurrent routing maps for exact, prefix, plugin ID, and pending routes.
 *
 * Events are routed via direct message envelopes or a broadcast pipeline. The Kernel
 * maintains a pluggable list of EventInterceptors (Idempotency and Capability)
 * that run sequentially on inbound invokes. If any interceptor returns true,
 * the event is consumed and routing stops, otherwise standard broadcast takes place.
 * The Kernel dynamically discovers the project root and initializes the PluginManager
 * to coordinate background catalog scanning and process lifecycle events.
 */
public class Kernel {

    // Socket path — override at runtime with -Dmk8.socket=/path/to/kernel.sock
    static final Path SOCKET_PATH = Path.of(KernelEvent.DEFAULT_SOCKET);

    private static final java.nio.charset.Charset UTF8 = StandardCharsets.UTF_8;

    // ── Instance-owned routing tables ────────────────────────────────────────
    final ConcurrentHashMap<String, CopyOnWriteArrayList<Connection>> exactRoutes  = new ConcurrentHashMap<>();
    final CopyOnWriteArrayList<PrefixRoute>                           prefixRoutes  = new CopyOnWriteArrayList<>();
    final ConcurrentHashMap<String, Connection>                       byPluginId    = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, String>                           pendingRoutes = new ConcurrentHashMap<>();

    final ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();

    // Populated once in start(); written before accept() loop starts so all reader
    // threads see a fully-initialized list. volatile ensures visibility across threads.
    volatile List<EventInterceptor> interceptors;

    // Synthetic source Connection used when bus.route() re-enters the kernel pipeline.
    // channel=null — writer thread is NOT started (guarded in Connection constructor).
    final Connection KERNEL_SOURCE = new Connection("kernel", null);

    // ── Entry point ───────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        KernelEvent.initLogging();
        new Kernel().start();
    }

    void start() throws Exception {
        System.out.println("[KERNEL] Starting MK8...");

        Files.createDirectories(SOCKET_PATH.getParent());
        Files.deleteIfExists(SOCKET_PATH);

        var address = UnixDomainSocketAddress.of(SOCKET_PATH);
        try (var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(address);
            System.out.println("[KERNEL] Listening at: " + SOCKET_PATH);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { Files.deleteIfExists(SOCKET_PATH); } catch (IOException ignored) {}
                System.out.println("[KERNEL] Shut down.");
            }));

            Path mk8Root = findProjectRoot();
            System.out.println("[KERNEL] Project root: " + mk8Root);

            var bus       = new KernelBusImpl(this);
            var pluginMgr = new PluginManager(bus, mk8Root);
            Thread.ofVirtual().start(pluginMgr::scan);

            var idempotency = new IdempotencyInterceptor(bus);
            var capIdx      = new CapabilityInterceptor(bus);
            capIdx.setRuntime(pluginMgr);
            interceptors = List.of(idempotency, capIdx); // immutable; written before accept loop

            while (!Thread.currentThread().isInterrupted()) {
                SocketChannel client = server.accept();
                readers.submit(() -> handleClient(client));
            }
        }
    }

    // ── Client handler (one virtual thread per plugin) ────────────────────────

    void handleClient(SocketChannel channel) {
        Connection conn = null;
        try {
            InputStream in = Channels.newInputStream(channel);
            String json;
            while ((json = KernelEvent.readFrame(in)) != null) {
                KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);
                if (EventTypes.PLUGIN_REGISTER.equals(event.type())) {
                    conn = register(event, channel);
                } else if (conn != null) {
                    route(event, json, conn);
                }
            }
        } catch (Exception e) {
            if (conn != null)
                System.err.println("[KERNEL] Plugin " + conn.pluginId + " disconnected: " + e.getMessage());
        } finally {
            if (conn != null) unregister(conn);
            try { channel.close(); } catch (IOException ignored) {}
        }
    }

    // ── Registration ──────────────────────────────────────────────────────────

    Connection register(KernelEvent event, SocketChannel channel) throws Exception {
        JsonNode cfg      = KernelEvent.MAPPER.readTree(event.payload());
        String   pluginId = cfg.path("id").asText("");
        Connection conn   = new Connection(pluginId, channel);

        byPluginId.put(pluginId, conn);

        JsonNode subs = cfg.path("subscribes");
        if (subs.isArray()) {
            for (JsonNode s : subs)
                exactRoutes.computeIfAbsent(s.asText(), k -> new CopyOnWriteArrayList<>()).add(conn);
        }

        JsonNode wildcards = cfg.path("wildcardSubscribes");
        if (wildcards.isArray()) {
            for (JsonNode w : wildcards) {
                String pattern = w.asText();
                if (pattern.endsWith("*"))
                    prefixRoutes.add(new PrefixRoute(pattern.substring(0, pattern.length() - 1), conn));
                else
                    System.err.println("[KERNEL] Invalid wildcard (must end with *): " + pattern);
            }
        }

        System.out.println("[KERNEL] Registered: " + pluginId
                + " subscribes=" + (subs.isArray() ? subs.size() : 0)
                + " wildcards="  + (wildcards.isArray() ? wildcards.size() : 0));
        return conn;
    }

    void unregister(Connection conn) {
        byPluginId.remove(conn.pluginId);
        exactRoutes.values().forEach(list -> list.remove(conn));
        prefixRoutes.removeIf(pr -> pr.conn() == conn);
        pendingRoutes.entrySet().removeIf(e -> e.getValue().equals(conn.pluginId));
        conn.shutdown(); // signal writer thread to exit gracefully
        System.out.println("[KERNEL] Unregistered: " + conn.pluginId);
    }

    // ── Routing ───────────────────────────────────────────────────────────────

    void route(KernelEvent event, String json, Connection source) {
        if (event.type().startsWith(EventTypes.MESSAGE_PREFIX)) {
            routeDirectMessage(event, json);
            return;
        }
        if (runInterceptors(event, json)) return;
        broadcast(event, json, source);
    }

    private void routeDirectMessage(KernelEvent event, String json) {
        String targetId = event.type().substring(EventTypes.MESSAGE_PREFIX.length());
        Connection target = findConnection(targetId);
        if (target != null) {
            System.out.println("[KERNEL] direct " + event.type() + " → " + targetId);
            enqueue(target, json);
        } else {
            System.err.println("[KERNEL] No plugin for direct message: " + targetId);
        }
    }

    private void broadcast(KernelEvent event, String json, Connection source) {
        String type  = event.type();
        var    exact = exactRoutes.get(type);
        if (exact != null) exact.forEach(c -> enqueue(c, json));

        int prefixCount = 0;
        for (PrefixRoute pr : prefixRoutes) {
            if (type.startsWith(pr.prefix())) { enqueue(pr.conn(), json); prefixCount++; }
        }

        int total = (exact != null ? exact.size() : 0) + prefixCount;
        if (total > 0)
            System.out.println("[KERNEL] broadcast " + type + " from=" + source.pluginId + " → " + total + " subscriber(s)");
        else
            System.out.println("[KERNEL] No subscribers for: " + type);
    }

    // ── Interceptor chain ─────────────────────────────────────────────────────

    private boolean runInterceptors(KernelEvent event, String json) {
        var chain = interceptors;
        if (chain == null) return false;
        for (var ix : chain) {
            try {
                if (ix.intercept(event, json)) return true;
            } catch (Exception e) {
                System.err.println("[KERNEL] Interceptor error on " + event.type() + ": " + e.getMessage());
            }
        }
        return false;
    }

    // ── Writer queue ──────────────────────────────────────────────────────────

    void enqueue(Connection conn, String json) {
        try {
            conn.writeQueue.put(buildFrame(json));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static byte[] buildFrame(String json) {
        byte[] payload = json.getBytes(UTF8);
        return ByteBuffer.allocate(4 + payload.length)
                .putInt(payload.length)
                .put(payload)
                .array();
    }

    // ── Service accessors (used by KernelBusImpl) ─────────────────────────────

    Connection findConnection(String pluginId) { return byPluginId.get(pluginId); }

    // ── Project root discovery ────────────────────────────────────────────────

    Path findProjectRoot() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path p = cwd; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("kernel")) || Files.exists(p.resolve("Start.java")))
                return p;
        }
        return cwd.getFileName().toString().equals("kernel") ? cwd.getParent() : cwd;
    }
}

// ── Event type constants ──────────────────────────────────────────────────────

interface EventTypes {
    String PLUGIN_REGISTER = "plugin.register";
    String MESSAGE_PREFIX  = "message.";
}

// ── Connection (pluginId + channel + dedicated writer thread) ─────────────────

class Connection {
    final String pluginId;
    final SocketChannel channel;
    final LinkedBlockingQueue<byte[]> writeQueue = new LinkedBlockingQueue<>();

    static final byte[] POISON = new byte[0];

    Connection(String pluginId, SocketChannel channel) {
        this.pluginId = pluginId;
        this.channel  = channel;
        if (channel != null) Thread.ofVirtual().start(this::runWriter);
    }

    private void runWriter() {
        try {
            OutputStream out = Channels.newOutputStream(channel);
            while (true) {
                byte[] frame = writeQueue.take();
                if (frame == POISON) break;
                out.write(frame);
                out.flush();
            }
        } catch (IOException e) {
            // expected on disconnect — channel closed by handleClient
        } catch (Exception e) {
            System.err.println("[KERNEL] Writer error for " + pluginId + ": " + e.getMessage());
        }
    }

    void shutdown() {
        try { writeQueue.put(POISON); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}

record PrefixRoute(String prefix, Connection conn) {}

// ── Kernel extension interfaces ───────────────────────────────────────────────

/**
 * EventInterceptor — called by Kernel.route() before the broadcast phase.
 *
 * Return semantics:
 *   true  → event CONSUMED: stop chain, skip broadcast entirely.
 *   false → side-effect only: chain continues; event is broadcast normally afterward.
 */
interface EventInterceptor {
    boolean intercept(KernelEvent event, String json) throws Exception;
}

/**
 * KernelBus — minimal surface for interceptors to emit events back into the kernel.
 */
interface KernelBus {
    void sendTo(String pluginId, String json);
    void route(KernelEvent event) throws Exception;
    void addPendingRoute(String corrId, String sourcePluginId);
    String removePendingRoute(String corrId);
}

/** Concrete KernelBus backed by a Kernel instance. */
class KernelBusImpl implements KernelBus {
    private final Kernel kernel;

    KernelBusImpl(Kernel kernel) {
        this.kernel = kernel;
    }

    @Override
    public void sendTo(String pluginId, String json) {
        Connection c = kernel.findConnection(pluginId);
        if (c != null) kernel.enqueue(c, json);
        else System.err.println("[KERNEL-BUS] No connection for direct send: " + pluginId);
    }

    @Override
    public void route(KernelEvent event) throws Exception {
        String json = KernelEvent.MAPPER.writeValueAsString(event);
        kernel.route(event, json, kernel.KERNEL_SOURCE);
    }

    @Override
    public void addPendingRoute(String corrId, String sourcePluginId) {
        kernel.pendingRoutes.put(corrId, sourcePluginId);
    }

    @Override
    public String removePendingRoute(String corrId) {
        return kernel.pendingRoutes.remove(corrId);
    }
}
