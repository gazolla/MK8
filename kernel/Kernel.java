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
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
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

    // ── Instance-owned routing tables ────────────────────────────────────────
    final ConcurrentHashMap<String, CopyOnWriteArrayList<Connection>> exactRoutes  = new ConcurrentHashMap<>();
    final CopyOnWriteArrayList<PrefixRoute>                           prefixRoutes  = new CopyOnWriteArrayList<>();
    final ConcurrentHashMap<String, Connection>                       byPluginId    = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, String>                           pendingRoutes = new ConcurrentHashMap<>();

    final ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();

    // ── Option B: Kernel-Extendido fields ─────────────────────────────────────
    // Populated once in start(); immutable after that. null until boot completes.
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

            // ── Option B boot — wire PluginManager + CapabilityInterceptor ──────────
            Path mk8Root = findProjectRoot();
            System.out.println("[KERNEL] Project root: " + mk8Root);

            var bus         = new KernelBusImpl(this);
            var pluginMgr   = new PluginManager(bus, mk8Root);
            Thread.ofVirtual().start(pluginMgr::scan); // background scan; doesn't block accept

            var idempotency = new IdempotencyInterceptor(bus);
            var capIdx      = new CapabilityInterceptor(bus);
            capIdx.setRuntime(pluginMgr);              // wire PluginRuntime
            interceptors = List.of(idempotency, capIdx);             // immutable after this point
            // ─────────────────────────────────────────────────────────────────────

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
            InputStream  in  = Channels.newInputStream(channel);
            String json;
            while ((json = KernelEvent.readFrame(in)) != null) {
                KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);
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
        System.out.println("[KERNEL] Unregistered: " + conn.pluginId);
    }

    // ── Routing ───────────────────────────────────────────────────────────────

    void route(KernelEvent event, String json, Connection source) {
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

        // Return routing via pendingRoutes (capability.result/error only — spec-defined).
        if ("capability.result".equals(type) || "capability.error".equals(type)) {
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

        // Track senders for return routing (pendingRoutes — capability.invoke only).
        if ("capability.invoke".equals(type)) {
            String corrId = event.correlationId();
            if (corrId != null && source.pluginId != null) {
                pendingRoutes.put(corrId, source.pluginId);
                System.out.println("[KERNEL] route capability.invoke from=" + source.pluginId + " corrId=" + corrId + " (pending return)");
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
            // capability.register is handled in-process by CapabilityInterceptor (side-effect, no UDS subscribers
            // after CapabilityRegistry was removed). All other events without subscribers are worth logging.
            System.out.println("[KERNEL] No subscribers for: " + type);
        }
    }

    // ── Writer queue ──────────────────────────────────────────────────────────

    void enqueue(Connection conn, String json) {
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
    Path findProjectRoot() {
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

/**
 * CatalogEntry — one entry per capability declaration found in plugin.json.
 * Defined here (not inside PluginManager) so CapabilityInterceptor can reference it
 * without coupling to PluginManager at all.
 */
record CatalogEntry(
        String   pluginId,
        String   pluginDir,
        String   capabilityName,
        String   triggerEvent,       // null for agents (no triggerEvent field)
        boolean  onDemand,           // lifecycle.mode == "on-demand"
        boolean  persistent,         // lifecycle.mode == "persistent"
        double   bidWeight,
        int      idleTimeoutSeconds,
        String[] launchCommand       // full launch.command array (e.g. ["jbang", "Tool.java"])
) {}

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
    boolean intercept(KernelEvent event, String json) throws Exception;
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
    void route(KernelEvent event) throws Exception;
    void removePendingRoute(String corrId);
}

/**
 * PluginRuntime — contract between CapabilityInterceptor and PluginManager.
 *
 * Exposes catalog lookups (what exists on disk) and lifecycle operations
 * (spawn, track, list) through a single interface. CapabilityInterceptor depends
 * only on this contract; PluginManager is never referenced directly.
 */
interface PluginRuntime {
    // Catalog
    void awaitReady(long timeoutMs);
    CatalogEntry getByCapName(String capName);
    Collection<CatalogEntry> allEntries();
    void refresh();
    // Lifecycle
    void spawnOnDemand(String capabilityName) throws Exception;
    void trackUsage(String capabilityName);
    String listPlugins() throws Exception;
}

/** Concrete KernelBus backed by a Kernel instance. */
class KernelBusImpl implements KernelBus {
    private final Kernel kernel;

    KernelBusImpl(Kernel kernel) {
        this.kernel = kernel;
    }

    @Override
    public void sendTo(String pluginId, String json) {
        Connection c = kernel.byPluginId.get(pluginId);
        if (c != null) kernel.enqueue(c, json);
        else System.err.println("[KERNEL-BUS] No connection for direct send: " + pluginId);
    }

    @Override
    public void route(KernelEvent event) throws Exception {
        // Re-serialise the event and push through the full routing pipeline.
        // KERNEL_SOURCE is used so pendingRoutes can record "kernel" as the sender.
        String json = KernelEvent.MAPPER.writeValueAsString(event);
        kernel.route(event, json, kernel.KERNEL_SOURCE);
    }

    @Override
    public void removePendingRoute(String corrId) {
        kernel.pendingRoutes.remove(corrId);
    }
}
