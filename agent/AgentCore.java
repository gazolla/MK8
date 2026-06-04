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
                apiKey = "missing-api-key";
            }
            model = OpenAiChatModel.builder()
                    .apiKey(apiKey)
                    .baseUrl(lc.baseUrlOrDefault())
                    .modelName(lc.modelOrDefault())
                    .temperature(lc.temperatureOrDefault())
                    .maxTokens(lc.maxTokensOrDefault())
                    .maxRetries(3)
                    .timeout(Duration.ofSeconds(120))
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
        registerCapabilities(out);

        if (store != null) {
            store.open();
            store.prune(90, 1000);
        }

        String spawnCorrId = System.getenv("MK8_SPAWN_CORRELATION_ID");
        if (spawnCorrId != null && !spawnCorrId.isBlank()) {
            try {
                String readyPayload = KernelEvent.MAPPER.writeValueAsString(
                        Map.of("agentId", config.id(), "correlationId", spawnCorrId));
                KernelEvent.writeFrame(out, KernelEvent.MAPPER.writeValueAsString(
                        KernelEvent.withCorrelation("agent.ready", readyPayload,
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
            if (type.startsWith("chat.") || type.startsWith("capability.result")
                    || type.startsWith("capability.error") || type.startsWith("message.")) {
                boolean isError = type.contains("error");
                StringBuilder sb = new StringBuilder("← ").append(type);
                if (event.sessionId() != null && !event.sessionId().isBlank())
                    sb.append(" session=").append(event.sessionId());
                if (event.correlationId() != null && !event.correlationId().isBlank())
                    sb.append(" corrId=").append(event.correlationId());
                if (event.payload() != null)
                    sb.append(" payload=").append(truncate(event.payload(), isError ? 1000 : 80));
                if (isError) logError(sb.toString()); else log(sb.toString());
            }

            try {
                if (type.startsWith("message." + config.id())) {
                    handleCapabilityInvoke(event, out);
                } else switch (type) {
                    case "chat.prompt"            -> handleChatPrompt(event, out);
                    case "capability.result"      -> router.resolveInvocation(event);
                    case "capability.error"       -> router.resolveInvocationError(event);
                    case "capability.bid.request" -> handleCapabilityBidRequest(event, out);
                    case "plugin.installed"       -> {
                        try { skillLoader.refresh(out); log("SkillLoader refreshed — new plugin available"); }
                        catch (Exception e) { logError("SkillLoader refresh failed: " + e.getMessage()); }
                    }
                    case "blackboard.read.result" -> blackboard.resolve(event);
                    default -> {
                        if (!type.startsWith("blackboard.updated.")) {
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
        String sessionId = event.sessionId() != null ? event.sessionId() : "default";
        String userText  = promptText(event);
        if (userText == null || userText.isBlank()) return;

        // MK8 console contract: chat.typing reads {"text"}, chat.thinking reads {"status"}.
        PluginBase.publishSafe(KernelEvent.withSession("chat.typing",
                KernelEvent.MAPPER.writeValueAsString(Map.of("text", "⏳")), config.id(), sessionId), out);

        if (config.thinking() != null) {
            String status = config.thinking().path("background").asText("⏳ Thinking...");
            PluginBase.publishSafe(KernelEvent.withSession("chat.thinking",
                    KernelEvent.MAPPER.writeValueAsString(Map.of("status", status)), config.id(), sessionId), out);
        }

        // Serialize all chat.prompt processing for a given session.
        synchronized (sessionLocks.computeIfAbsent(sessionId, k -> new Object())) {
            boolean isNew = !sessionMemories.containsKey(sessionId);
            ChatMemory memory = sessionMemories.computeIfAbsent(sessionId,
                    k -> MessageWindowChatMemory.withMaxMessages(40));
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
            publishAgentStatus("agent.busy", out);
            try {
                runActualMission(event, out);
            } catch (Exception e) {
                logError("capability mission failed: " + e.getMessage());
                try { publishError(e.getMessage(), false, event.correlationId(), event.sessionId(), out); }
                catch (Exception ignored) {}
            } finally {
                if (activeMissions.decrementAndGet() == 0) publishAgentStatus("agent.idle", out);
            }
        });
    }

    private String runActualMission(KernelEvent event, OutputStream out) throws Exception {
        JsonNode p     = KernelEvent.MAPPER.readTree(event.payload());
        String userMsg = buildCapabilityQuery(p);
        String corrId  = event.correlationId();
        String sessionId = event.sessionId();

        if (sessionId != null) {
            String cacheKey = "research.cache." + normalizeQueryKey(userMsg);
            String cached   = blackboard.read(cacheKey, "session", sessionId, out);
            if (cached != null) {
                log("← blackboard cache hit for: " + truncate(userMsg, 60));
                String resultPayload = KernelEvent.MAPPER.writeValueAsString(Map.of("result", cached));
                PluginBase.publish(KernelEvent.withCorrelation("capability.result", resultPayload,
                        config.id(), corrId, sessionId), out);
                return cached;
            }
        }

        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(40);

        String missionQuery = userMsg;
        if (sessionId != null) {
            String context = blackboard.read("task.context", "session", sessionId, out);
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
        PluginBase.publish(KernelEvent.of("capability.bid.response", bidPayload, config.id()), out);
    }

    // ── Capability registration ───────────────────────────────────────────────

    void registerCapabilities(OutputStream out) throws Exception {
        for (AgentConfig.CapabilityDecl cap : config.capabilitiesOrEmpty()) {
            var reg = new LinkedHashMap<String, Object>();
            reg.put("name",      cap.name());
            reg.put("pluginId",  config.id());
            reg.put("version",   cap.version() != null ? cap.version() : "1.0.0");
            reg.put("exclusive", cap.exclusiveOrDefault());
            reg.put("bidWeight", cap.bidWeightOrDefault());
            if (cap.triggerEvent() != null) reg.put("triggerEvent", cap.triggerEvent());
            if (cap.tags() != null)         reg.put("tags", cap.tags());
            KernelEvent.writeFrame(out, KernelEvent.MAPPER.writeValueAsString(
                    KernelEvent.of("capability.register", KernelEvent.MAPPER.writeValueAsString(reg), config.id())));
        }
        log("Capabilities registered: " + config.capabilitiesOrEmpty().size());
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
                    KernelEvent.withSession("chat.response", payload, config.id(), originalSession), out);
        } else {
            String resultPayload = KernelEvent.MAPPER.writeValueAsString(Map.of("result", text));
            PluginBase.publish(
                    KernelEvent.withCorrelation("capability.result", resultPayload,
                            config.id(), correlationId, originalSession), out);
        }
    }

    void publishError(String msg, boolean chatMode, String correlationId,
                      String originalSession, OutputStream out) throws Exception {
        if (chatMode) {
            String payload = KernelEvent.MAPPER.writeValueAsString(Map.of(
                    "response", "The AI service is temporarily unavailable. Please try again."));
            PluginBase.publish(KernelEvent.withSession("chat.response", payload,
                    config.id(), originalSession), out);
        } else {
            String ep = KernelEvent.MAPPER.writeValueAsString(Map.of("reason", msg));
            PluginBase.publish(KernelEvent.withCorrelation("capability.error", ep,
                    config.id(), correlationId, originalSession), out);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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

    void log(String msg) {
        String id = config != null ? config.id().toUpperCase() : "AGENT";
        System.out.println("[" + id + "] " + msg);
    }

    void logError(String msg) {
        String id = config != null ? config.id().toUpperCase() : "AGENT";
        System.err.println("[" + id + "] ERROR: " + msg);
    }
}
