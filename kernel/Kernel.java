///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES Event.java
//SOURCES PluginCatalog.java
//SOURCES CapabilityIndex.java
//SOURCES ProcessManager.java
//SOURCES IdempotencyInterceptor.java

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;

/**
 * MK7 Kernel — plugin-agnostic UDS event bus.
 *
 * Routing tables (built at runtime from plugin.register):
 *   exactRoutes   — event.type  → List<Connection>  (O(1) dispatch)
 *   prefixRoutes  — prefix      → List<Connection>  (wildcard, * only at end)
 *   byPluginId    — pluginId    → Connection         (direct message.{id} routing)
 *   pendingRoutes — correlationId → pluginId         (capability.result return routing)
 *
 * Frame protocol: 4 bytes big-endian length + UTF-8 JSON payload.
 * Each Connection has a dedicated writer thread draining its writeQueue — FIFO per destination.
 */
public class Kernel {

    static final Path SOCKET_PATH = Path.of("/tmp/mk7/kernel.sock");

    static final ConcurrentHashMap<String, CopyOnWriteArrayList<Connection>> exactRoutes  = new ConcurrentHashMap<>();
    static final CopyOnWriteArrayList<PrefixRoute>                           prefixRoutes  = new CopyOnWriteArrayList<>();
    static final ConcurrentHashMap<String, Connection>                       byPluginId    = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, String>                           pendingRoutes = new ConcurrentHashMap<>();

    static final ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();

    // ── Option B: Kernel-Extendido fields ─────────────────────────────────────
    // Populated once in main(); immutable after that. null until boot completes.
    static volatile List<EventInterceptor> interceptors;
    // Synthetic source Connection used when bus.route() re-enters the kernel pipeline.
    // channel=null — writer thread is NOT started (guarded in Connection constructor).
    static final Connection KERNEL_SOURCE = new Connection("kernel", null);

    public static void main(String[] args) throws Exception {
        Event.initLogging();
        System.out.println("[KERNEL] Starting MK7...");

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

            // ── Option B boot — wire CapabilityIndex + ProcessManager ──────────
            Path mk7Root = findProjectRoot();
            System.out.println("[KERNEL] Project root: " + mk7Root);

            var catalog = new PluginCatalog(mk7Root);
            Thread.ofVirtual().start(catalog::scan);   // background scan; doesn't block accept

            var bus     = new KernelBusImpl();
            var idempotency = new IdempotencyInterceptor(bus);
            var capIdx  = new CapabilityIndex(catalog, bus);
            var procMgr = new ProcessManager(catalog, bus, mk7Root);
            capIdx.setProcessManager(procMgr);         // P1: wire trackUsage callback
            interceptors = List.of(idempotency, capIdx, procMgr);   // immutable after this point
            // ─────────────────────────────────────────────────────────────────────

            while (!Thread.currentThread().isInterrupted()) {
                SocketChannel client = server.accept();
                readers.submit(() -> handleClient(client));
            }
        }
    }

    // ── Client handler (one virtual thread per plugin) ────────────────────────

    static void handleClient(SocketChannel channel) {
        Connection conn = null;
        try {
            InputStream  in  = Channels.newInputStream(channel);
            String json;
            while ((json = Event.readFrame(in)) != null) {
                Event event = Event.MAPPER.readValue(json, Event.class);
                if ("plugin.register".equals(event.type())) {
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

    static Connection register(Event event, SocketChannel channel) throws Exception {
        Event.PluginConfig config = Event.MAPPER.readValue(event.payload(), Event.PluginConfig.class);
        Connection conn = new Connection(config.id(), channel);

        byPluginId.put(config.id(), conn);

        for (String type : config.subscribesOrEmpty()) {
            exactRoutes.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(conn);
        }
        for (String pattern : config.wildcardSubscribesOrEmpty()) {
            if (pattern.endsWith("*")) {
                String prefix = pattern.substring(0, pattern.length() - 1);
                prefixRoutes.add(new PrefixRoute(prefix, conn));
            } else {
                System.err.println("[KERNEL] Invalid wildcard (must end with *): " + pattern);
            }
        }

        System.out.println("[KERNEL] Registered: " + config.id()
                + " subscribes=" + config.subscribesOrEmpty().size()
                + " wildcards="  + config.wildcardSubscribesOrEmpty().size());
        return conn;
    }

    static void unregister(Connection conn) {
        byPluginId.remove(conn.pluginId);
        exactRoutes.values().forEach(list -> list.remove(conn));
        prefixRoutes.removeIf(pr -> pr.conn() == conn);
        pendingRoutes.entrySet().removeIf(e -> e.getValue().equals(conn.pluginId));
        System.out.println("[KERNEL] Unregistered: " + conn.pluginId);
    }

    // ── Routing ───────────────────────────────────────────────────────────────

    static void route(Event event, String json, Connection source) {
        String type = event.type();

        // Direct: message.{targetId}
        if (type.startsWith("message.")) {
            String targetId = type.substring("message.".length());
            Connection target = byPluginId.get(targetId);
            if (target != null) {
                System.out.println("[KERNEL] route " + type + " from=" + source.pluginId + " → " + targetId);
                enqueue(target, json);
            } else {
                System.err.println("[KERNEL] No plugin for direct message: " + targetId);
            }
            return;
        }

        // Return routing via pendingRoutes.
        // Spec defines this only for capability.result/error. blackboard.read.result and
        // blackboard.query.result are a pragmatic extension: the spec does not specify how
        // blackboard results are routed back, and using the same correlationId mechanism is
        // consistent with the existing pattern. The alternative (message.{requesterId})
        // would require agents to differentiate blackboard results from capability.invokes
        // in their message.{self} handler.
        if ("capability.result".equals(type) || "capability.error".equals(type)
                || "blackboard.read.result".equals(type) || "blackboard.query.result".equals(type)) {
            String corrId = event.correlationId();
            if (corrId != null) {
                // Call interceptors for results before returning (allows IdempotencyInterceptor to cache/distribute)
                var chain = interceptors;
                if (chain != null) {
                    for (var ix : chain) {
                        try {
                            if (ix.intercept(event, json)) return;
                        } catch (Exception e) {
                            System.err.println("[KERNEL] Interceptor error on result: " + e.getMessage());
                        }
                    }
                }
                String targetPluginId = pendingRoutes.remove(corrId);
                if (targetPluginId != null) {
                    Connection target = byPluginId.get(targetPluginId);
                    if (target != null) {
                        System.out.println("[KERNEL] route " + type + " corrId=" + corrId + " → " + targetPluginId);
                        enqueue(target, json);
                        return;
                    }
                }
            }
            System.out.println("[KERNEL] No pending route for " + type + " corrId=" + event.correlationId());
            return;
        }

        // Track senders for return routing (pendingRoutes).
        // capability.invoke is spec-defined. blackboard.read/query are pragmatic extensions
        // (see comment above).
        if ("capability.invoke".equals(type)) {
            String corrId = event.correlationId();
            if (corrId != null && source.pluginId != null) {
                pendingRoutes.put(corrId, source.pluginId);
                System.out.println("[KERNEL] route capability.invoke from=" + source.pluginId + " corrId=" + corrId + " (pending return)");
            }
        }
        if ("blackboard.read".equals(type) || "blackboard.query".equals(type)) {
            String corrId = event.correlationId();
            if (corrId != null && source.pluginId != null) {
                pendingRoutes.put(corrId, source.pluginId);
            }
        }

        // [Option B] Interceptor chain — runs after pendingRoutes tracking, before broadcast.
        // If an interceptor returns true, the event is consumed (no broadcast).
        // If it returns false, it may have had side-effects but broadcast continues.
        var chain = interceptors;
        if (chain != null) {
            for (var ix : chain) {
                try {
                    if (ix.intercept(event, json)) return;
                } catch (Exception e) {
                    System.err.println("[KERNEL] Interceptor error on " + type + ": " + e.getMessage());
                }
            }
        }

        // Broadcast: exact match + wildcard prefix match
        var exact = exactRoutes.get(type);
        if (exact != null) exact.forEach(c -> enqueue(c, json));

        for (PrefixRoute pr : prefixRoutes) {
            if (type.startsWith(pr.prefix())) enqueue(pr.conn(), json);
        }

        boolean hasSubscribers = (exact != null && !exact.isEmpty())
                || prefixRoutes.stream().anyMatch(pr -> type.startsWith(pr.prefix()));
        if (hasSubscribers) {
            // Only trace high-value event types, skip log.* and chat.typing noise
            if (!type.startsWith("log.") && !"chat.typing".equals(type)) {
                int count = (exact != null ? exact.size() : 0)
                        + (int) prefixRoutes.stream().filter(pr -> type.startsWith(pr.prefix())).count();
                System.out.println("[KERNEL] broadcast " + type + " from=" + source.pluginId + " → " + count + " subscriber(s)");
            }
        } else if (!type.startsWith("plugin.")
                && !"capability.register".equals(type)) {
            // capability.register is handled in-process by CapabilityIndex (side-effect, no UDS subscribers
            // after CapabilityRegistry was removed). All other events without subscribers are worth logging.
            System.out.println("[KERNEL] No subscribers for: " + type);
        }
    }

    // ── Writer queue ──────────────────────────────────────────────────────────

    static void enqueue(Connection conn, String json) {
        try {
            byte[] frame = buildFrame(json);
            conn.writeQueue.put(frame);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static byte[] buildFrame(String json) {
        byte[] payload = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int len = payload.length;
        byte[] frame = new byte[4 + len];
        frame[0] = (byte)(len >>> 24); frame[1] = (byte)(len >>> 16);
        frame[2] = (byte)(len >>> 8);  frame[3] = (byte) len;
        System.arraycopy(payload, 0, frame, 4, len);
        return frame;
    }

    // ── Project root discovery (Option B) ─────────────────────────────────────

    /**
     * Walks up from user.dir until a directory that contains a 'kernel/' subdirectory
     * or a 'Start.java' file is found. Same heuristic used by MK7's Boot.java and
     * CapabilityRegistry.findProjectRoot().
     *
     * Falls back to user.dir if nothing is found (e.g., when run from kernel/ directly).
     */
    static Path findProjectRoot() {
        Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (p != null) {
            if (Files.exists(p.resolve("kernel")) || Files.exists(p.resolve("Start.java"))) {
                return p;
            }
            p = p.getParent();
        }
        // Fallback: if kernel/ is the cwd (jbang run from inside kernel/), go one level up
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (cwd.getFileName().toString().equals("kernel")) return cwd.getParent();
        return cwd;
    }
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
        // Guard: do not start writer thread for synthetic connections (e.g., KERNEL_SOURCE)
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
        } catch (Exception ignored) {}
    }

    void shutdown() {
        try { writeQueue.put(POISON); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}

record PrefixRoute(String prefix, Connection conn) {}

// ── Option B interfaces and implementations ───────────────────────────────────

/**
 * EventInterceptor — called by Kernel.route() before the broadcast phase.
 *
 * Return semantics:
 *   true  → event CONSUMED: stop chain, skip broadcast entirely.
 *   false → side-effect only: chain continues; event is broadcast normally afterward.
 *
 * Exceptions thrown by intercept() are caught by route() and logged; they do NOT
 * propagate and do NOT prevent subsequent interceptors or the broadcast from running.
 */
interface EventInterceptor {
    boolean intercept(Event event, String json) throws Exception;
}

/**
 * KernelBus — minimal surface for interceptors to emit events back into the kernel.
 *
 * sendTo:             enqueues JSON directly to a plugin connection (bypasses routing).
 * route:              sends an event through the full Kernel pipeline (pendingRoutes + interceptors + broadcast).
 * removePendingRoute: removes a correlationId from the return-routing table (used by IdempotencyInterceptor
 *                     after it manually delivers a result, to prevent memory leaks).
 */
interface KernelBus {
    void sendTo(String pluginId, String json);
    void route(Event event) throws Exception;
    void removePendingRoute(String corrId);
}

/** Concrete KernelBus backed by the Kernel's static routing tables. */
class KernelBusImpl implements KernelBus {
    @Override
    public void sendTo(String pluginId, String json) {
        Connection c = Kernel.byPluginId.get(pluginId);
        if (c != null) Kernel.enqueue(c, json);
        else System.err.println("[KERNEL-BUS] No connection for direct send: " + pluginId);
    }

    @Override
    public void route(Event event) throws Exception {
        // Re-serialise the event and push through the full routing pipeline.
        // KERNEL_SOURCE is used so pendingRoutes can record "kernel" as the sender.
        String json = Event.MAPPER.writeValueAsString(event);
        Kernel.route(event, json, Kernel.KERNEL_SOURCE);
    }

    @Override
    public void removePendingRoute(String corrId) {
        Kernel.pendingRoutes.remove(corrId);
    }
}
