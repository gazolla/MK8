// Shared file — included via //SOURCES in all plugins and the kernel.
// No JBang header (not an entry point).

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Unified system event and shared infrastructure (frame protocol, plugin config, UDS boot).
 *
 * Frame protocol: 4 bytes big-endian length + UTF-8 JSON payload.
 * All plugins use readFrame/writeFrame — never raw readline.
 */
public record Event(
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

    public static final String DEFAULT_SOCKET = "/tmp/mk7/kernel.sock";

    public static final ThreadLocal<String> CURRENT_TRACE_ID = new ThreadLocal<>();
    public static final ThreadLocal<String> CURRENT_SPAN_ID = new ThreadLocal<>();

    static {
        initLogging();
    }

    /**
     * Installs the timestamp-prefixing PrintStream on stdout/stderr.
     * Safe to call multiple times — guarded by a system property flag.
     *
     * Plugins should call this explicitly at the top of main() instead of relying
     * on the static initializer ordering (which varies by JVM class-loading sequence):
     *   {@code Event.initLogging();}
     */
    public static void initLogging() {
        if (System.getProperty("mk8.logging.redirected") == null) {
            System.setProperty("mk8.logging.redirected", "true");
            System.setOut(new TimestampPrintStream(System.out));
            System.setErr(new TimestampPrintStream(System.err));
        }
    }

    public static class TimestampPrintStream extends java.io.PrintStream {
        private final java.io.PrintStream original;
        private final java.time.format.DateTimeFormatter formatter = 
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
        private boolean atLineStart = true;

        public TimestampPrintStream(java.io.PrintStream original) {
            super(original);
            this.original = original;
        }

        @Override
        public void write(int b) {
            if (atLineStart) {
                String prefix = "[" + java.time.LocalDateTime.now().format(formatter) + "] ";
                byte[] prefixBytes = prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                try {
                    original.write(prefixBytes);
                } catch (java.io.IOException ignored) {}
                atLineStart = false;
            }
            original.write(b);
            if (b == '\n') {
                atLineStart = true;
            }
        }

        @Override
        public void write(byte[] buf, int off, int len) {
            int start = off;
            for (int i = 0; i < len; i++) {
                if (buf[off + i] == '\n') {
                    int segmentLen = (off + i + 1) - start;
                    writeSegment(buf, start, segmentLen);
                    atLineStart = true;
                    start = off + i + 1;
                }
            }
            if (start < off + len) {
                writeSegment(buf, start, (off + len) - start);
            }
        }

        private void writeSegment(byte[] buf, int off, int len) {
            if (len <= 0) return;
            if (atLineStart) {
                String prefix = "[" + java.time.LocalDateTime.now().format(formatter) + "] ";
                byte[] prefixBytes = prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                try {
                    original.write(prefixBytes);
                } catch (java.io.IOException ignored) {}
                atLineStart = false;
            }
            original.write(buf, off, len);
        }
    }

    // ── Factory methods ───────────────────────────────────────────────────────

    public static Event of(String type, String payload, String source) {
        String tid = CURRENT_TRACE_ID.get();
        String sid = CURRENT_SPAN_ID.get();
        return new Event(uuid(), type, payload, now(), source, null, null, null, null, tid, sid);
    }

    public static Event withSession(String type, String payload, String source, String sessionId) {
        String tid = CURRENT_TRACE_ID.get();
        String sid = CURRENT_SPAN_ID.get();
        if (tid == null) {
            tid = uuid();
            sid = tid;
        }
        return new Event(uuid(), type, payload, now(), source, null, sessionId, null, null, tid, sid);
    }

    public static Event withCorrelation(String type, String payload, String source, String correlationId, String sessionId) {
        String tid = CURRENT_TRACE_ID.get();
        String sid = CURRENT_SPAN_ID.get();
        return new Event(uuid(), type, payload, now(), source, correlationId, sessionId, null, null, tid, sid);
    }

    public static Event withTrace(String type, String payload, String source, String correlationId, String sessionId, String traceId, String spanId) {
        return new Event(uuid(), type, payload, now(), source, correlationId, sessionId, null, null, traceId, spanId);
    }

    public static Event reply(Event origin, String type, String payload, String source) {
        return new Event(uuid(), type, payload, now(), source,
                origin.correlationId(), origin.sessionId(), origin.workflowId(), null, origin.traceId(), origin.spanId());
    }

    private static String uuid() { return UUID.randomUUID().toString(); }
    private static LocalDateTime now() { return LocalDateTime.now(); }

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

    // ── Plugin config ─────────────────────────────────────────────────────────

    public record LaunchConfig(
            String name,
            String[] command,
            Integer order,
            Integer delayAfterMs,
            Boolean interactive,
            String prebuild
    ) {}

    public record PluginConfig(
            String id,
            String type,           // "system" | "tool" | "agent"
            String version,
            String description,
            LifecycleConfig lifecycle,
            LlmConfig llm,
            AgentConfig agent,
            ThinkingConfig thinking,
            LaunchConfig launch,
            List<CapabilityDecl> capabilities,
            List<String> subscribes,
            List<String> wildcardSubscribes,
            List<String> publishes
    ) {
        public List<String> subscribesOrEmpty() {
            return subscribes != null ? subscribes : List.of();
        }
        public List<String> wildcardSubscribesOrEmpty() {
            return wildcardSubscribes != null ? wildcardSubscribes : List.of();
        }
        public List<CapabilityDecl> capabilitiesOrEmpty() {
            return capabilities != null ? capabilities : List.of();
        }
    }

    public record LifecycleConfig(String mode, Integer idleTimeoutSeconds) {
        public String modeOrDefault() { return mode != null ? mode : "persistent"; }
        public int idleTimeoutOrDefault() { return idleTimeoutSeconds != null ? idleTimeoutSeconds : 300; }
    }

    public record LlmConfig(
            String model, String baseUrl, String apiKeyEnv,
            Integer maxTokens, Double temperature
    ) {
        public String modelOrDefault()     { return model      != null ? model      : "meta/llama-3.3-70b-instruct"; }
        public String baseUrlOrDefault()   { return baseUrl    != null ? baseUrl    : "https://integrate.api.nvidia.com/v1"; }
        public String apiKeyEnvOrDefault() { return apiKeyEnv  != null ? apiKeyEnv  : "NVIDIA_API_KEY"; }
        public int    maxTokensOrDefault() { return maxTokens  != null ? maxTokens  : 4096; }
        public double temperatureOrDefault(){ return temperature != null ? temperature : 0.2; }
    }

    public record AgentConfig(
            String skillsDir,
            Integer maxRounds,
            Integer maxDelegations,
            Integer maxToolCalls,
            Integer maxConcurrentMissions,
            String contextLoading,
            Integer negotiatingTimeoutSeconds,
            Boolean seeInternalTools,     // true = include internal:true capabilities in the injected capability list
            Boolean requireToolOnRound1,  // false = skip the round-1 "must invoke a tool" guard (for pure generators)
            Boolean requireDelegationOnRound1, // false = skip the round-1 chat safety net requiring delegation
            List<String> toolTags         // empty/null = no filter (see all); ["filesystem","code"] = tag-based filter
    ) {
        public int          maxRoundsOrDefault()            { return maxRounds              != null ? maxRounds              : 5; }
        public int          maxDelegationsOrDefault()       { return maxDelegations         != null ? maxDelegations         : 3; }
        public int          maxToolCallsOrDefault()         { return maxToolCalls           != null ? maxToolCalls           : 20; }
        public int          maxConcurrentOrDefault()        { return maxConcurrentMissions  != null ? maxConcurrentMissions  : 1; }
        public String       contextLoadingOrDefault()       { return contextLoading         != null ? contextLoading         : "lazy"; }
        public int          negotiatingTimeoutOrDefault()   { return negotiatingTimeoutSeconds != null ? negotiatingTimeoutSeconds : 120; }
        public boolean      seeInternalToolsOrDefault()    { return seeInternalTools       != null && seeInternalTools; }
        public boolean      requireToolOnRound1OrDefault() { return requireToolOnRound1    == null || requireToolOnRound1; }
        public boolean      requireDelegationOnRound1OrDefault() { return requireDelegationOnRound1 == null || requireDelegationOnRound1; }
        public List<String> toolTagsOrDefault()            { return toolTags               != null ? toolTags               : List.of(); }
    }

    public record ThinkingConfig(
            List<String> steps,
            Long cycleDelayMs,
            Long backgroundThresholdMs,
            String background
    ) {
        public List<String> stepsOrDefault() { return steps != null ? steps : List.of("⏳ Thinking..."); }
        public long cycleDelayMsOrDefault()  { return cycleDelayMs != null ? cycleDelayMs : 12000L; }
        public long backgroundThresholdMsOrDefault() { return backgroundThresholdMs != null ? backgroundThresholdMs : 120000L; }
        public String backgroundOrDefault()  { return background != null ? background : "⏳ Still working on it. I'll notify you here when ready! 🔔"; }
    }

    public record CapabilityDecl(
            String name, String description, String version,
            String triggerEvent,   // null for agents; event type the tool subscribes to
            Boolean exclusive, Double bidWeight, List<String> tags,
            Boolean internal,      // true = hidden from orchestrators; only visible to agents with seeInternalTools
            String replyEvent,     // event type published as response (e.g. "chat.response")
            JsonNode inputSchema,  // JSON Schema for input parameters
            JsonNode outputSchema  // JSON Schema for output (documentation)
    ) {
        public boolean exclusiveOrDefault()  { return exclusive  != null && exclusive; }
        public double  bidWeightOrDefault()  { return bidWeight  != null ? bidWeight  : 1.0; }
        public boolean internalOrDefault()   { return internal   != null && internal; }
    }

    // ── UDS boot ──────────────────────────────────────────────────────────────

    @FunctionalInterface
    public interface PluginLogic {
        void run(InputStream in, OutputStream out) throws Exception;
    }

    /**
     * Connects to the kernel, sends plugin.register + plugin.ready, then runs the plugin logic.
     */
    public static void connectAndRun(String socketPath, PluginConfig config, PluginLogic logic) {
        var addr = UnixDomainSocketAddress.of(Path.of(socketPath));
        try (var ch = SocketChannel.open(addr)) {
            var out = Channels.newOutputStream(ch);
            var in  = Channels.newInputStream(ch);

            System.out.println("[" + config.id().toUpperCase() + "] Connected to kernel.");

            String payload;
            try {
                var node = MAPPER.valueToTree(config);
                if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode objNode) {
                    objNode.put("pid", ProcessHandle.current().pid());
                    payload = MAPPER.writeValueAsString(objNode);
                } else {
                    payload = MAPPER.writeValueAsString(config);
                }
            } catch (Exception e) {
                payload = MAPPER.writeValueAsString(config);
            }

            writeFrame(out, MAPPER.writeValueAsString(
                    Event.of("plugin.register", payload, config.id())));
            String readyPayload;
            try {
                readyPayload = MAPPER.writeValueAsString(java.util.Map.of("id", config.id(), "pid", ProcessHandle.current().pid()));
            } catch (Exception e) {
                readyPayload = config.id();
            }

            writeFrame(out, MAPPER.writeValueAsString(
                    Event.of("plugin.ready", readyPayload, config.id())));

            logic.run(in, out);

        } catch (Exception e) {
            System.err.println("[" + config.id().toUpperCase() + "] Connection error: " + e.getMessage());
        }
    }

    public static PluginConfig loadConfig(String jsonPath) throws Exception {
        System.out.println("[EVENT] Loading config: " + jsonPath);
        return MAPPER.readValue(Files.readString(Path.of(jsonPath)), PluginConfig.class);
    }
}
