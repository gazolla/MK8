///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES KernelEvent.java
//SOURCES interceptors/plugin/PluginInterceptor.java
//SOURCES interceptors/plugin/PluginBase.java
//SOURCES interceptors/plugin/PluginConfig.java
//SOURCES interceptors/capability/CapabilityInterceptor.java
//SOURCES interceptors/idempotency/IdempotencyInterceptor.java
//SOURCES interceptors/blackboard/BlackboardInterceptor.java

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
 * maintains a pluggable list of EventInterceptors that run sequentially on inbound
 * events. If any interceptor returns true the event is consumed and routing stops;
 * otherwise standard broadcast takes place.
 *
 * ── CLI parameters ────────────────────────────────────────────────────────────
 *
 *   --socket=<path>
 *       Unix Domain Socket path the Kernel will bind to.
 *       Default: /tmp/mk8/kernel.sock (or -Dmk8.socket system property).
 *       Use distinct paths to run multiple Kernel instances in parallel,
 *       e.g. for isolated integration tests.
 *
 *   --logs=<path>
 *       Absolute directory where PluginInterceptor writes on-demand plugin logs.
 *       Default: <project-root>/logs (resolved via findProjectRoot heuristic).
 *       Pass the project's own logs/ folder so every log lands together.
 *
 *   --scan=<path>
 *       Root directory PluginInterceptor walks to discover plugin.json files.
 *       Default: auto-detected by walking up from cwd until a folder named
 *       "kernel" or a file named "Start.java" is found (findProjectRoot).
 *       Supply an explicit path to eliminate the heuristic entirely.
 *
 *   --log-level=DEBUG|INFO|WARN
 *       Controls verbosity of Kernel stdout messages.
 *       DEBUG — every routing decision (broadcast targets, direct hops, no-subscribers).
 *       INFO  — lifecycle events only: startup, registration, shutdown. (default)
 *       WARN  — silent stdout; errors still appear on stderr.
 *
 *   <InterceptorName> ...
 *       Ordered list of interceptors to install. Any class on the classpath that
 *       implements EventInterceptor can be named here — no registration required.
 *       Built-in interceptors:
 *         IdempotencyInterceptor  — single-flight collapsing + sliding-window cache.
 *         CapabilityInterceptor   — capability registry and bid-based routing.
 *         PluginInterceptor           — catalog scan and on-demand process lifecycle.
 *       Default (no names given): none — Kernel starts as a pure event bus.
 *
 * ── Examples ──────────────────────────────────────────────────────────────────
 *
 *   # Full stack with explicit paths
 *   jbang Kernel.java \
 *     --socket=/tmp/mk8/myapp.sock \
 *     --logs=/projects/myapp/logs  \
 *     --scan=/projects/myapp       \
 *     --log-level=DEBUG            \
 *     IdempotencyInterceptor CapabilityInterceptor PluginInterceptor
 *
 *   # Pure event bus (no args needed)
 *   jbang Kernel.java --socket=/tmp/mk8/test.sock
 */
public class Kernel {

    private static final java.nio.charset.Charset UTF8 = StandardCharsets.UTF_8;

    // ── Log level ─────────────────────────────────────────────────────────────

    enum LogLevel {
        DEBUG, INFO, WARN;

        static LogLevel of(String s) {
            return switch (s.toUpperCase()) {
                case "DEBUG" -> DEBUG;
                case "WARN"  -> WARN;
                default      -> INFO;
            };
        }
    }

    static volatile LogLevel LOG_LEVEL = LogLevel.INFO;

    static void log(LogLevel level, String msg) {
        if (level.ordinal() >= LOG_LEVEL.ordinal()) System.out.println(msg);
    }

    // ── Socket path (instance — set before start()) ───────────────────────────
    Path socketPath = Path.of(KernelEvent.DEFAULT_SOCKET);

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
        var kernel = new Kernel();
        var bus    = new KernelBusImpl(kernel);

        // ── Parse flags; remaining positional args are interceptor names ──────
        Path logsOverride = null;
        Path scanOverride = null;
        var  names        = new ArrayList<String>();

        for (String arg : args) {
            if      (arg.startsWith("--socket="))    kernel.socketPath = Path.of(arg.substring(9));
            else if (arg.startsWith("--logs="))      logsOverride      = Path.of(arg.substring(7));
            else if (arg.startsWith("--scan="))      scanOverride      = Path.of(arg.substring(7));
            else if (arg.startsWith("--log-level=")) LOG_LEVEL         = LogLevel.of(arg.substring(12));
            else                                     names.add(arg);
        }

        var config = new KernelConfig(
                scanOverride != null ? scanOverride : kernel.findProjectRoot(),
                logsOverride);

        // ── Discover and instantiate interceptors by convention ───────────────
        // Convention: CLI name = class name; constructor tried in order:
        //   (KernelBus, KernelConfig) → (KernelBus) → ()
        // If the instance implements InterceptorLifecycle, onStart() is called.
        var interceptorList = new ArrayList<EventInterceptor>();
        for (String name : names) {
            try {
                var cls    = Class.forName(name);
                var iface  = EventInterceptor.class;
                if (!iface.isAssignableFrom(cls)) {
                    System.err.println("[KERNEL] " + name + " does not implement EventInterceptor — skipped.");
                    continue;
                }
                var interceptor = instantiate(cls.asSubclass(iface), bus, config);
                if (interceptor instanceof InterceptorLifecycle lc) lc.onStart();
                interceptorList.add(interceptor);
                log(LogLevel.DEBUG, "[KERNEL] Loaded interceptor: " + name);
            } catch (ClassNotFoundException e) {
                System.err.println("[KERNEL] Interceptor class not found: '" + name + "'");
            } catch (Exception e) {
                System.err.println("[KERNEL] Failed to instantiate '" + name + "': " + e.getMessage());
            }
        }
        kernel.start(interceptorList);
    }

    void start(List<EventInterceptor> interceptorList) throws Exception {
        this.interceptors = List.copyOf(interceptorList);
        log(LogLevel.INFO, "[KERNEL] Starting MK8...");

        Files.createDirectories(socketPath.getParent());
        Files.deleteIfExists(socketPath);

        var address = UnixDomainSocketAddress.of(socketPath);
        try (var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(address);
            log(LogLevel.INFO, "[KERNEL] Listening at:  " + socketPath);
            log(LogLevel.INFO, "[KERNEL] Interceptors:  " + interceptors.stream()
                    .map(i -> i.getClass().getSimpleName()).toList());
            interceptors.forEach(i -> {
                log(LogLevel.DEBUG, "[KERNEL]   " + i.getClass().getSimpleName()
                        + " publishes="  + i.publishes()
                        + " subscribes=" + i.subscribes());
            });

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { Files.deleteIfExists(socketPath); } catch (IOException ignored) {}
                log(LogLevel.INFO, "[KERNEL] Shut down.");
            }));

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
                if (Events.PLUGIN_REGISTER.equals(event.type())) {
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

        log(LogLevel.INFO, "[KERNEL] Registered:   " + pluginId
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
        log(LogLevel.INFO, "[KERNEL] Unregistered: " + conn.pluginId);
        emitDisconnected(conn.pluginId);
    }

    /**
     * Announce a dropped connection as a plain broadcast event. The kernel names no
     * listener — any interceptor or plugin may react (e.g. lifecycle supervision).
     * This keeps the kernel fully decoupled from whatever consumes the signal.
     */
    private void emitDisconnected(String pluginId) {
        try {
            KernelEvent ev = KernelEvent.of(Events.PLUGIN_DISCONNECTED,
                    KernelEvent.MAPPER.writeValueAsString(Map.of("id", pluginId)), "kernel");
            route(ev, KernelEvent.MAPPER.writeValueAsString(ev), KERNEL_SOURCE);
        } catch (Exception ignored) {}
    }

    // ── Routing ───────────────────────────────────────────────────────────────

    void route(KernelEvent event, String json, Connection source) {
        if (event.type().startsWith(Events.MESSAGE_PREFIX)) {
            routeDirectMessage(event, json);
            return;
        }
        if (runInterceptors(event, json)) return;
        broadcast(event, json, source);
    }

    private void routeDirectMessage(KernelEvent event, String json) {
        String targetId = event.type().substring(Events.MESSAGE_PREFIX.length());
        Connection target = findConnection(targetId);
        if (target != null) {
            log(LogLevel.DEBUG, "[KERNEL] direct " + event.type() + " → " + targetId);
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
            log(LogLevel.DEBUG, "[KERNEL] broadcast " + type + " from=" + source.pluginId + " → " + total + " subscriber(s)");
        else
            log(LogLevel.DEBUG, "[KERNEL] No subscribers for: " + type);
    }

    // ── Interceptor chain ─────────────────────────────────────────────────────

    private boolean runInterceptors(KernelEvent event, String json) {
        var chain = interceptors;
        if (chain == null) return false;
        for (var ix : chain) {
            if (!ix.handles(event.type())) continue;
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

    // ── Convention-based instantiation ────────────────────────────────────────

    static EventInterceptor instantiate(Class<? extends EventInterceptor> cls,
                                        KernelBus bus, KernelConfig config) throws Exception {
        try {
            return cls.getDeclaredConstructor(KernelBus.class, KernelConfig.class).newInstance(bus, config);
        } catch (NoSuchMethodException ignored) {}
        try {
            return cls.getDeclaredConstructor(KernelBus.class).newInstance(bus);
        } catch (NoSuchMethodException ignored) {}
        return cls.getDeclaredConstructor().newInstance();
    }

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

// ── Convention-over-configuration types ──────────────────────────────────────

/**
 * Resolved path configuration passed to interceptors that need filesystem context.
 * Interceptors declare this as their second constructor parameter to receive it:
 *   MyInterceptor(KernelBus bus, KernelConfig config) { ... }
 */
record KernelConfig(Path scanRoot, Path logsOverride) {}

/**
 * Optional lifecycle hook for interceptors that need to start background work
 * after construction (e.g. launching a scan thread). The Kernel calls onStart()
 * immediately after instantiation if the interceptor implements this interface.
 */
interface InterceptorLifecycle {
    void onStart();
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
 *
 * handles() declares which event types this interceptor wants to receive.
 * The Kernel filters before calling intercept() — return false in handles()
 * to skip events entirely without entering intercept().
 *
 * publishes() / subscribes() declare the interceptor's contract — the event
 * types it produces and consumes. Used for logging at boot and introspection.
 * Implement both to make the contract explicit and machine-readable.
 */
interface EventInterceptor {
    boolean intercept(KernelEvent event, String json) throws Exception;
    default boolean handles(String eventType) { return true; }
    default Set<String> publishes()  { return Set.of(); }
    default Set<String> subscribes() { return Set.of(); }
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
