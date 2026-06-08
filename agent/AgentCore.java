// Shared file — included via //SOURCES in Agent.java

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * AgentCore — the central hub for all MK8 agent instances.
 * Owns shared state (config, llm, sessions) and dispatches incoming events
 * to the appropriate handlers.
 *
 * Ported from MK7. Notable MK8 adaptations:
 *   - Event → KernelEvent, BasePlugin → PluginBase, config via AgentConfig adapter.
 *   - chat.prompt / chat.response use the MK8 console JSON contract
 *     ({"text": ...} in, {"response": ...} / {"status": ...} out).
 *   - Per-capability idempotency removed — the kernel's IdempotencyInterceptor
 *     now provides single-flight dedup at the bus level.
 *   - Background workflow handling (workflow.dag.*) dropped — no workflow engine in MK8.
 *   - Logging goes to stdout/stderr (no Logger plugin in MK8).
 */
public class AgentCore {

    // ── Event types ─────────────────────────────────────────────────────────────
    static final String EVT_CHAT_PROMPT             = "chat.prompt";
    static final String EVT_CHAT_RESPONSE           = "chat.response";
    static final String EVT_CHAT_TYPING             = "chat.typing";
    static final String EVT_CHAT_THINKING           = "chat.thinking";
    static final String EVT_CAPABILITY_RESULT       = "capability.result";
    static final String EVT_CAPABILITY_ERROR        = "capability.error";
    static final String EVT_CAPABILITY_BID_REQUEST  = "capability.bid.request";
    static final String EVT_CAPABILITY_BID_RESPONSE = "capability.bid.response";
    static final String EVT_PLUGIN_INSTALLED        = "plugin.installed";
    static final String EVT_BLACKBOARD_READ_RESULT  = "blackboard.read.result";
    static final String EVT_AGENT_BUSY              = "agent.busy";
    static final String EVT_AGENT_IDLE             = "agent.idle";
    static final String EVT_AGENT_READY            = "agent.ready";
    static final String PREFIX_MESSAGE             = "message.";
    static final String PREFIX_CHAT                = "chat.";
    static final String PREFIX_BLACKBOARD_UPDATED  = "blackboard.updated.";

    // ── Blackboard keys & scopes ────────────────────────────────────────────────
    static final String SCOPE_SESSION                = "session";
    static final String BB_KEY_TASK_CONTEXT          = "task.context";
    static final String BB_KEY_RESEARCH_CACHE_PREFIX = "research.cache.";

    // ── Tuning ──────────────────────────────────────────────────────────────────
    static final int    MAX_HISTORY                = 40;
    static final int    LLM_MAX_RETRIES            = 3;
    static final int    LLM_TIMEOUT_SECONDS        = 120;
    static final int    SESSION_TTL_DAYS           = 90;
    static final int    MAX_SESSIONS               = 1000;
    static final long   RESEARCH_CACHE_TTL_SECONDS = 1800;
    static final int    LOG_PAYLOAD_MAX            = 80;
    static final int    LOG_PAYLOAD_MAX_ERROR      = 1000;

    // ── Misc ────────────────────────────────────────────────────────────────────
    static final String DEFAULT_SESSION   = "default";
    static final String MISSING_API_KEY   = "missing-api-key";
    static final String SPAWN_CORR_ENV    = "MK8_SPAWN_CORRELATION_ID";

    final AgentConfig config;
    final String agentDir;
    final String skillsDir;
    final ChatLanguageModel llm;
    final AtomicInteger activeMissions = new AtomicInteger(0);
    final Map<String, ChatMemory> sessionMemories = new ConcurrentHashMap<>();
    // One lock object per sessionId — serializes concurrent chat.prompt events on the same session.
    // MessageWindowChatMemory is backed by a non-thread-safe LinkedList.
    private final Map<String, Object> sessionLocks = new ConcurrentHashMap<>();

    final CapabilityRouter router;
    final BlackboardClient blackboard;
    final SessionStore store;
    final SkillLoader skillLoader;
    volatile OutputStream globalOut;

    AgentCore(String[] args) throws Exception {
        agentDir  = args.length > 0 ? args[0] : ".";
        config    = AgentConfig.load(agentDir + "/plugin.json");
        Log.configure(config.id().toUpperCase(), null);   // stdout only until connected
        skillsDir = Path.of(agentDir).resolve(
                config.agent() != null ? config.agent().skillsDir() : ".").toString();

        log("Starting from: " + agentDir);

        ChatLanguageModel model = null;
        if (config.llm() != null) {
            AgentConfig.LlmView lc = config.llm();
            // Tolerate a missing API key at boot: the agent still connects and registers,
            // failing gracefully only when an actual LLM call is made (returns a chat error).
            String apiKey = System.getenv(lc.apiKeyEnvOrDefault());
            if (apiKey == null || apiKey.isBlank()) {
                logError("env var '" + lc.apiKeyEnvOrDefault() + "' not set — LLM calls will fail until provided.");
                apiKey = MISSING_API_KEY;
            }
            model = OpenAiChatModel.builder()
                    .apiKey(apiKey)
                    .baseUrl(lc.baseUrlOrDefault())
                    .modelName(lc.modelOrDefault())
                    .temperature(lc.temperatureOrDefault())
                    .maxTokens(lc.maxTokensOrDefault())
                    .maxRetries(LLM_MAX_RETRIES)
                    .timeout(Duration.ofSeconds(LLM_TIMEOUT_SECONDS))
                    .build();
        }
        llm = model;

        router     = new CapabilityRouter(config, this);
        blackboard = new BlackboardClient(config, this);

        Path root = SessionStore.findProjectRoot(Path.of(agentDir).toAbsolutePath());
        store = root != null ? new SessionStore(config.id(), root) : null;
        skillLoader = new SkillLoader(skillsDir, config);
    }

    // ── Startup ───────────────────────────────────────────────────────────────

    void start(OutputStream out) throws Exception {
        globalOut = out;
        Log.configure(config.id().toUpperCase(), out);   // enable the consolidated bus sink
        // DRY: reuse the shared capability registration in PluginBase (identical wire event).
        PluginBase.registerCapabilities(config.plugin(), out);
        log("Capabilities registered: " + config.capabilitiesOrEmpty().size());

        if (store != null) {
            store.open();
            store.prune(SESSION_TTL_DAYS, MAX_SESSIONS);
        }

        String spawnCorrId = System.getenv(SPAWN_CORR_ENV);
        if (spawnCorrId != null && !spawnCorrId.isBlank()) {
            try {
                String readyPayload = KernelEvent.MAPPER.writeValueAsString(
                        Map.of("agentId", config.id(), "correlationId", spawnCorrId));
                KernelEvent.writeFrame(out, KernelEvent.MAPPER.writeValueAsString(
                        KernelEvent.withCorrelation(EVT_AGENT_READY, readyPayload,
                                config.id(), spawnCorrId, null)));
                log("published agent.ready corrId=" + spawnCorrId);
            } catch (Exception e) {
                logError("Failed to publish agent.ready: " + e.getMessage());
            }
        }
    }

    // ── Event dispatch ─────────────────────────────────────────────────────────

    void dispatch(String json, OutputStream out) {
        try {
            KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);
            if (event.traceId() != null) KernelEvent.CURRENT_TRACE_ID.set(event.traceId());
            if (event.spanId()  != null) KernelEvent.CURRENT_SPAN_ID.set(event.spanId());
            String type = event.type();

            // Structured logging for key event types
            if (type.startsWith(PREFIX_CHAT) || type.startsWith(EVT_CAPABILITY_RESULT)
                    || type.startsWith(EVT_CAPABILITY_ERROR) || type.startsWith(PREFIX_MESSAGE)) {
                boolean isError = type.contains("error");
                StringBuilder sb = new StringBuilder("← ").append(type);
                if (event.sessionId() != null && !event.sessionId().isBlank())
                    sb.append(" session=").append(event.sessionId());
                if (event.correlationId() != null && !event.correlationId().isBlank())
                    sb.append(" corrId=").append(event.correlationId());
                if (event.payload() != null)
                    sb.append(" payload=").append(truncate(event.payload(), isError ? LOG_PAYLOAD_MAX_ERROR : LOG_PAYLOAD_MAX));
                if (isError) logError(sb.toString()); else log(sb.toString());
            }

            try {
                if (type.startsWith(PREFIX_MESSAGE + config.id())) {
                    handleCapabilityInvoke(event, out);
                } else switch (type) {
                    case EVT_CHAT_PROMPT            -> handleChatPrompt(event, out);
                    case EVT_CAPABILITY_RESULT      -> router.resolveInvocation(event);
                    case EVT_CAPABILITY_ERROR       -> router.resolveInvocationError(event);
                    case EVT_CAPABILITY_BID_REQUEST -> handleCapabilityBidRequest(event, out);
                    case EVT_PLUGIN_INSTALLED       -> {
                        try { skillLoader.refresh(out); log("SkillLoader refreshed — new plugin available"); }
                        catch (Exception e) { logError("SkillLoader refresh failed: " + e.getMessage()); }
                    }
                    case EVT_BLACKBOARD_READ_RESULT -> blackboard.resolve(event);
                    default -> {
                        if (!type.startsWith(PREFIX_BLACKBOARD_UPDATED)) {
                            log("← unhandled event type: " + type);
                        }
                    }
                }
            } catch (Exception e) {
                logError("Dispatch error for " + type + ": " + e.getMessage());
            }
        } catch (Exception e) {
            logError("Handle error: " + e.getMessage());
        } finally {
            KernelEvent.CURRENT_TRACE_ID.remove();
            KernelEvent.CURRENT_SPAN_ID.remove();
        }
    }

    // ── CHAT mode: chat.prompt → LLM → chat.response ─────────────────────────

    void handleChatPrompt(KernelEvent event, OutputStream out) throws Exception {
        String sessionId = event.sessionId() != null ? event.sessionId() : DEFAULT_SESSION;
        String userText  = promptText(event);
        if (userText == null || userText.isBlank()) return;

        // MK8 console contract: chat.typing reads {"text"}, chat.thinking reads {"status"}.
        PluginBase.publishSafe(KernelEvent.withSession(EVT_CHAT_TYPING,
                KernelEvent.MAPPER.writeValueAsString(Map.of("text", "⏳")), config.id(), sessionId), out);

        if (config.thinking() != null) {
            String status = config.thinking().path("background").asText("⏳ Thinking...");
            PluginBase.publishSafe(KernelEvent.withSession(EVT_CHAT_THINKING,
                    KernelEvent.MAPPER.writeValueAsString(Map.of("status", status)), config.id(), sessionId), out);
        }

        // Serialize all chat.prompt processing for a given session.
        synchronized (sessionLocks.computeIfAbsent(sessionId, k -> new Object())) {
            boolean isNew = !sessionMemories.containsKey(sessionId);
            ChatMemory memory = sessionMemories.computeIfAbsent(sessionId,
                    k -> MessageWindowChatMemory.withMaxMessages(MAX_HISTORY));
            if (isNew && store != null) store.loadInto(sessionId, memory);
            memory.add(UserMessage.from(userText));
            new MissionRunner(this, router, blackboard)
                    .run(memory, sessionId, null, sessionId, null, out);
            if (store != null) store.save(sessionId, memory.messages());
        }
    }

    /** Extracts the user text from a chat.prompt payload ({"text"}/{"message"}, or plain). */
    private static String promptText(KernelEvent event) {
        try {
            JsonNode p = KernelEvent.MAPPER.readTree(event.payload());
            if (p.isObject()) {
                String t = p.path("text").asText(p.path("message").asText(null));
                if (t != null && !t.isBlank()) return t;
            }
        } catch (Exception ignored) {}
        return event.payload();
    }

    // ── CAPABILITY mode: message.{self} (forwarded capability.invoke) ─────────

    void handleCapabilityInvoke(KernelEvent event, OutputStream out) throws Exception {
        // Single-flight dedup is now handled by the kernel's IdempotencyInterceptor.
        // The agent simply runs the mission; busy/idle status feeds load-aware bidding.
        Thread.ofVirtual().start(() -> {
            activeMissions.incrementAndGet();
            publishAgentStatus(EVT_AGENT_BUSY, out);
            try {
                runActualMission(event, out);
            } catch (Exception e) {
                logError("capability mission failed: " + e.getMessage());
                try { publishError(e.getMessage(), false, event.correlationId(), event.sessionId(), out); }
                catch (Exception ignored) {}
            } finally {
                if (activeMissions.decrementAndGet() == 0) publishAgentStatus(EVT_AGENT_IDLE, out);
            }
        });
    }

    private String runActualMission(KernelEvent event, OutputStream out) throws Exception {
        JsonNode p     = KernelEvent.MAPPER.readTree(event.payload());
        String userMsg = buildCapabilityQuery(p);
        String corrId  = event.correlationId();
        String sessionId = event.sessionId();

        if (sessionId != null) {
            String cached = blackboard.read(researchCacheKey(userMsg), SCOPE_SESSION, sessionId, out);
            if (cached != null) {
                log("← blackboard cache hit for: " + truncate(userMsg, 60));
                String resultPayload = KernelEvent.MAPPER.writeValueAsString(Map.of("result", cached));
                PluginBase.publish(KernelEvent.withCorrelation(EVT_CAPABILITY_RESULT, resultPayload,
                        config.id(), corrId, sessionId), out);
                return cached;
            }
        }

        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(MAX_HISTORY);

        String missionQuery = userMsg;
        if (sessionId != null) {
            String context = blackboard.read(BB_KEY_TASK_CONTEXT, SCOPE_SESSION, sessionId, out);
            if (context != null) {
                log("← context loaded for session " + sessionId);
                missionQuery = "Conversation context (recent discussion — use this to resolve pronouns "
                        + "and avoid re-researching facts already known):\n"
                        + context + "\n\nResearch query: " + userMsg;
            }
        }
        memory.add(UserMessage.from(missionQuery));

        String internalSession = corrId != null ? "cap-" + corrId : UUID.randomUUID().toString();
        return new MissionRunner(this, router, blackboard)
                .run(memory, internalSession, corrId, sessionId, userMsg, out);
    }

    // ── Bidding (load-aware — the custom event loop bypasses PluginBase auto-bid) ──

    void handleCapabilityBidRequest(KernelEvent event, OutputStream out) throws Exception {
        JsonNode req   = KernelEvent.MAPPER.readTree(event.payload());
        String capName = req.has("capabilityName") ? req.get("capabilityName").asText() : "";
        String corrId  = req.has("correlationId")  ? req.get("correlationId").asText()  : event.correlationId();

        boolean canHandle = config.capabilitiesOrEmpty().stream()
                .anyMatch(c -> c.name().equals(capName));
        if (!canHandle) return;

        double bidWeight = config.capabilitiesOrEmpty().stream()
                .filter(c -> c.name().equals(capName))
                .findFirst().map(AgentConfig.CapabilityDecl::bidWeightOrDefault).orElse(1.0);

        int    maxConcurrent = config.agent() != null ? config.agent().maxConcurrentOrDefault() : 1;
        double load = maxConcurrent > 0
                ? Math.min(1.0, (double) activeMissions.get() / maxConcurrent)
                : 0.0;

        String bidPayload = KernelEvent.MAPPER.writeValueAsString(Map.of(
                "agentId",       config.id(),
                "score",         bidWeight,
                "load",          load,
                "correlationId", corrId != null ? corrId : ""));
        PluginBase.publish(KernelEvent.of(EVT_CAPABILITY_BID_RESPONSE, bidPayload, config.id()), out);
    }

    // ── Agent busy/idle status ────────────────────────────────────────────────

    void publishAgentStatus(String type, OutputStream out) {
        try {
            String payload = KernelEvent.MAPPER.writeValueAsString(Map.of("agentId", config.id()));
            PluginBase.publishSafe(KernelEvent.of(type, payload, config.id()), out);
        } catch (Exception ignored) {}
    }

    // ── Answer / error publishing (MK8 console JSON contract) ─────────────────

    void publishAnswer(String text, boolean chatMode, String correlationId,
                       String originalSession, String originalQuery, OutputStream out) throws Exception {
        if (chatMode) {
            String payload = KernelEvent.MAPPER.writeValueAsString(Map.of("response", text));
            PluginBase.publish(
                    KernelEvent.withSession(EVT_CHAT_RESPONSE, payload, config.id(), originalSession), out);
        } else {
            String resultPayload = KernelEvent.MAPPER.writeValueAsString(Map.of("result", text));
            PluginBase.publish(
                    KernelEvent.withCorrelation(EVT_CAPABILITY_RESULT, resultPayload,
                            config.id(), correlationId, originalSession), out);
        }
    }

    void publishError(String msg, boolean chatMode, String correlationId,
                      String originalSession, OutputStream out) throws Exception {
        if (chatMode) {
            String payload = KernelEvent.MAPPER.writeValueAsString(Map.of(
                    "response", "The AI service is temporarily unavailable. Please try again."));
            PluginBase.publish(KernelEvent.withSession(EVT_CHAT_RESPONSE, payload,
                    config.id(), originalSession), out);
        } else {
            String ep = KernelEvent.MAPPER.writeValueAsString(Map.of("reason", msg));
            PluginBase.publish(KernelEvent.withCorrelation(EVT_CAPABILITY_ERROR, ep,
                    config.id(), correlationId, originalSession), out);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Blackboard key for a cached research result of the given query (shared by chat + capability paths). */
    String researchCacheKey(String query) {
        return BB_KEY_RESEARCH_CACHE_PREFIX + normalizeQueryKey(query);
    }

    static String buildCapabilityQuery(JsonNode payload) throws Exception {
        if (!payload.has("input")) return KernelEvent.MAPPER.writeValueAsString(payload);
        JsonNode input = payload.get("input");
        if (input.has("query")) return input.get("query").asText();
        if (input.isTextual()) return input.asText();
        return KernelEvent.MAPPER.writeValueAsString(input);
    }

    // Detects when an open-source model (e.g. Llama) emits a function-call JSON object
    // as plain text instead of using the native tool calling API.
    boolean looksLikeHallucinatedToolCall(String text) {
        if (text == null || text.isBlank()) return false;
        String t = text.strip();
        return t.startsWith("{")
            && (t.contains("\"type\"") || t.contains("\"name\""))
            && (t.contains("\"function\"") || t.contains("\"parameters\""));
    }

    String normalizeQueryKey(String query) {
        String norm = query.toLowerCase()
                .replaceAll("[^a-z0-9]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        return norm.length() > 80 ? norm.substring(0, 80) : norm;
    }

    String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "…" : s;
    }

    void log(String msg)      { Log.info(msg);  }
    void logError(String msg) { Log.error(msg); }
    void logDebug(String msg) { Log.debug(msg); }   // suppressed unless Log.level(DEBUG)
}
