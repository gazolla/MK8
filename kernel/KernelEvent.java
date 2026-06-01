// Shared file — included via //SOURCES in all plugins and the kernel.
// No JBang header (not an entry point).

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * KernelEvent — Immutable event envelope and transport infra for the MK8 bus.
 *
 * Every message exchanged across the Unix Domain Socket (UDS) in MK8 is structured
 * as a KernelEvent record. It carries standard routing metadata (id, type, source,
 * correlationId, sessionId, traceId, spanId) and an opaque JSON payload string.
 *
 * To guarantee message boundaries over TCP/UDS socket streams, the transport layer
 * prefixes each serialized JSON block with a 4-byte big-endian length integer.
 * Helper methods readFrame and writeFrame execute this framing protocol safely.
 * Static factories simplify building common patterns like direct requests, sessions,
 * trace context transmission, and replies. Also redirects standard stdout and
 * stderr streams to prepend microsecond timestamps for unified kernel logging.
 * The default socket path (/tmp/mk8/kernel.sock) can be configured at boot time.
 */
public record KernelEvent(
        String id,
        String type,
        String payload,
        LocalDateTime timestamp,
        String source,
        String correlationId,
        String sessionId,
        String workflowId,
        String replyTo,
        String traceId,
        String spanId
) {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    /** Override at runtime with -Dmk8.socket=/path/to/kernel.sock */
    public static final String DEFAULT_SOCKET =
            System.getProperty("mk8.socket", "/tmp/mk8/kernel.sock");

    public static final ThreadLocal<String> CURRENT_TRACE_ID = new ThreadLocal<>();
    public static final ThreadLocal<String> CURRENT_SPAN_ID  = new ThreadLocal<>();

    private static final String LOGGING_FLAG = "mk8.logging.redirected";

    static {
        initLogging();
    }

    /**
     * Installs the timestamp-prefixing PrintStream on stdout/stderr.
     * Safe to call multiple times — guarded by a system property flag.
     *
     * Plugins should call this explicitly at the top of main() instead of relying
     * on the static initializer ordering (which varies by JVM class-loading sequence):
     *   {@code KernelEvent.initLogging();}
     */
    public static void initLogging() {
        if (System.getProperty(LOGGING_FLAG) == null) {
            System.setProperty(LOGGING_FLAG, "true");
            System.setOut(new TimestampPrintStream(System.out));
            System.setErr(new TimestampPrintStream(System.err));
        }
    }

    public static class TimestampPrintStream extends PrintStream {
        private final PrintStream       original;
        private final DateTimeFormatter formatter =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
        private boolean atLineStart = true;

        public TimestampPrintStream(PrintStream original) {
            super(original);
            this.original = original;
        }

        @Override
        public void write(int b) {
            if (atLineStart) writePrefix();
            original.write(b);
            if (b == '\n') atLineStart = true;
        }

        @Override
        public void write(byte[] buf, int off, int len) {
            int start = off;
            for (int i = 0; i < len; i++) {
                if (buf[off + i] == '\n') {
                    writeSegment(buf, start, (off + i + 1) - start);
                    atLineStart = true;
                    start = off + i + 1;
                }
            }
            if (start < off + len) writeSegment(buf, start, (off + len) - start);
        }

        private void writeSegment(byte[] buf, int off, int len) {
            if (len <= 0) return;
            if (atLineStart) writePrefix();
            original.write(buf, off, len);
        }

        private void writePrefix() {
            byte[] bytes = ("[" + LocalDateTime.now().format(formatter) + "] ")
                    .getBytes(StandardCharsets.UTF_8);
            try { original.write(bytes); } catch (IOException ignored) {}
            atLineStart = false;
        }
    }

    // ── Factory methods ───────────────────────────────────────────────────────

    public static KernelEvent of(String type, String payload, String source) {
        String[] t = currentTrace();
        return new KernelEvent(uuid(), type, payload, now(), source, null, null, null, null, t[0], t[1]);
    }

    public static KernelEvent withCorrelation(String type, String payload, String source,
                                        String correlationId, String sessionId) {
        String[] t = currentTrace();
        return new KernelEvent(uuid(), type, payload, now(), source, correlationId, sessionId, null, null, t[0], t[1]);
    }

    public static KernelEvent reply(KernelEvent origin, String type, String payload, String source) {
        return new KernelEvent(uuid(), type, payload, now(), source,
                origin.correlationId(), origin.sessionId(), origin.workflowId(),
                null, origin.traceId(), origin.spanId());
    }

    /** Returns [traceId, spanId] from the current thread context. */
    private static String[]      currentTrace() { return new String[]{ CURRENT_TRACE_ID.get(), CURRENT_SPAN_ID.get() }; }
    private static String        uuid()         { return UUID.randomUUID().toString(); }
    private static LocalDateTime now()          { return LocalDateTime.now(); }

    // ── Frame protocol ────────────────────────────────────────────────────────

    /** Reads one length-prefixed frame. Returns null on EOF. */
    public static String readFrame(InputStream in) throws IOException {
        byte[] lenBuf = in.readNBytes(4);
        if (lenBuf.length < 4) return null;
        int len = ((lenBuf[0] & 0xFF) << 24) | ((lenBuf[1] & 0xFF) << 16)
                | ((lenBuf[2] & 0xFF) << 8)  |  (lenBuf[3] & 0xFF);
        byte[] payload = in.readNBytes(len);
        if (payload.length < len) return null;
        return new String(payload, StandardCharsets.UTF_8);
    }

    /** Writes one length-prefixed frame. */
    public static void writeFrame(OutputStream out, String json) throws IOException {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        int len = payload.length;
        out.write(new byte[]{
                (byte)(len >>> 24), (byte)(len >>> 16), (byte)(len >>> 8), (byte) len
        });
        out.write(payload);
        out.flush();
    }

}

/**
 * Events — Kernel-level routing constants.
 *
 * Only the two event types the Kernel itself must recognise to route correctly.
 * Every other event type belongs to the component that produces it — declared
 * locally via private constants and exposed through EventInterceptor.publishes()
 * / subscribes(), or via plugin.json for plugins.
 */
interface Events {
    String PLUGIN_REGISTER = "plugin.register"; // identifies registration frames
    String MESSAGE_PREFIX  = "message.";        // prefix for direct-message routing
}
