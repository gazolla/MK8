// Shared file — included via //SOURCES in Agent.java

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.ChatMemory;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.*;

/**
 * BlackboardClient — all blackboard read/write operations and the
 * resolution of pending blackboard futures when results arrive.
 *
 * Talks to the kernel's BlackboardInterceptor over the same event contract as MK7
 * (blackboard.read/write/query → blackboard.read.result, blackboard.write.conflict).
 */
public class BlackboardClient {

    // ── Event types ─────────────────────────────────────────────────────────────
    static final String EVT_BLACKBOARD_READ           = "blackboard.read";
    static final String EVT_BLACKBOARD_WRITE          = "blackboard.write";
    static final String EVT_BLACKBOARD_WRITE_CONFLICT = "blackboard.write.conflict";

    // ── Keys / scopes / tuning ──────────────────────────────────────────────────
    static final String SCOPE_SESSION             = "session";
    static final String BB_KEY_TASK_CONTEXT       = "task.context";
    static final String TAG_RESEARCH              = "research";
    static final int    REPLY_TIMEOUT_SECONDS     = 2;
    static final long   TASK_CONTEXT_TTL_SECONDS  = 600;
    static final int    CONTEXT_LINE_MAX          = 300;   // truncate each captured line
    static final int    CONTEXT_RECENT_LINES      = 6;     // keep only the last N lines

    private final AgentConfig config;
    private final AgentCore core;
    final Map<String, CompletableFuture<KernelEvent>> pendingBlackboardReads = new ConcurrentHashMap<>();

    BlackboardClient(AgentConfig config, AgentCore core) {
        this.config = config;
        this.core   = core;
    }

    // ── Resolve incoming blackboard events ──────────────────────────────

    void resolve(KernelEvent event) {
        String corrId = event.correlationId();
        if (corrId == null) return;
        CompletableFuture<KernelEvent> f = pendingBlackboardReads.get(corrId);
        if (f == null) return;
        f.complete(event);
    }

    // ── Records for optimistic locking results ────────────────────────────────

    public record ReadResult(String value, long version, String author, boolean found) {}
    public record WriteResult(boolean success, long version, String conflictReason) {}

    /**
     * Publishes a correlated blackboard request and blocks for the reply (≤ timeout).
     * Centralizes the corrId → future → publish → await → cleanup boilerplate.
     */
    private KernelEvent requestReply(String type, Object payload, OutputStream out) throws Exception {
        String corrId = UUID.randomUUID().toString();
        CompletableFuture<KernelEvent> future = new CompletableFuture<>();
        pendingBlackboardReads.put(corrId, future);
        try {
            String json = KernelEvent.MAPPER.writeValueAsString(payload);
            PluginBase.publish(KernelEvent.withCorrelation(type, json, config.id(), corrId, null), out);
            return future.get(REPLY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            pendingBlackboardReads.remove(corrId);
        }
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    String read(String key, String scope, String scopeId, OutputStream out) {
        try {
            KernelEvent reply = requestReply(EVT_BLACKBOARD_READ,
                    Map.of("key", key, "scope", scope, "scopeId", scopeId), out);
            JsonNode result = KernelEvent.MAPPER.readTree(reply.payload());
            if (result.has("found") && !result.get("found").asBoolean(true)) return null;
            return result.has("value") ? result.get("value").asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    public ReadResult readWithMetadata(String key, String scope, String scopeId, OutputStream out) {
        try {
            KernelEvent reply = requestReply(EVT_BLACKBOARD_READ,
                    Map.of("key", key, "scope", scope, "scopeId", scopeId), out);
            JsonNode result = KernelEvent.MAPPER.readTree(reply.payload());
            if (result.has("found") && !result.get("found").asBoolean(true)) {
                return new ReadResult(null, 0, null, false);
            }
            return new ReadResult(
                    result.has("value") ? result.get("value").asText() : null,
                    result.path("version").asLong(0),
                    result.path("author").asText("unknown"),
                    true
            );
        } catch (Exception e) {
            return new ReadResult(null, 0, null, false);
        }
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    void write(String key, String scope, String scopeId, String value,
               long ttlSeconds, OutputStream out) {
        try {
            var payload = KernelEvent.MAPPER.writeValueAsString(Map.of(
                    "key", key, "scope", scope, "scopeId", scopeId,
                    "value", value, "ttl", ttlSeconds, "tags", List.of(TAG_RESEARCH)));
            PluginBase.publishSafe(KernelEvent.of(EVT_BLACKBOARD_WRITE, payload, config.id()), out);
        } catch (Exception e) {
            core.logError("blackboard write failed: " + e.getMessage());
        }
    }

    public WriteResult writeConditional(String key, String scope, String scopeId, String value,
                                        long expectedVersion, long ttlSeconds, OutputStream out) {
        try {
            KernelEvent reply = requestReply(EVT_BLACKBOARD_WRITE, Map.of(
                    "key", key, "scope", scope, "scopeId", scopeId,
                    "value", value, "ttl", ttlSeconds, "expectedVersion", expectedVersion,
                    "tags", List.of(TAG_RESEARCH)), out);
            JsonNode result = KernelEvent.MAPPER.readTree(reply.payload());
            if (EVT_BLACKBOARD_WRITE_CONFLICT.equals(reply.type())) {
                return new WriteResult(false, result.path("currentVersion").asLong(0), result.path("reason").asText("conflict"));
            } else {
                return new WriteResult(true, result.path("version").asLong(0), null);
            }
        } catch (Exception e) {
            return new WriteResult(false, 0, e.getMessage());
        }
    }

    // ── Write conversation context snapshot ───────────────────────────────────

    void writeConversationContext(ChatMemory memory, String sessionId, OutputStream out) {
        List<String> lines = new ArrayList<>();
        for (ChatMessage msg : memory.messages()) {
            if (msg instanceof UserMessage um) {
                String content = um.singleText();
                if (content.startsWith("Tool result:")
                        || content.startsWith("Specialist result:")
                        || content.startsWith("Result:")) continue;
                lines.add("user: " + core.truncate(content, CONTEXT_LINE_MAX));
            } else if (msg instanceof AiMessage am) {
                if (am.hasToolExecutionRequests()) continue;
                String content = am.text();
                if (content != null && !content.isBlank()) {
                    lines.add("assistant: " + core.truncate(content, CONTEXT_LINE_MAX));
                }
            }
        }
        if (lines.isEmpty()) return;

        List<String> recent = lines.size() > CONTEXT_RECENT_LINES
                ? lines.subList(lines.size() - CONTEXT_RECENT_LINES, lines.size()) : lines;
        write(BB_KEY_TASK_CONTEXT, SCOPE_SESSION, sessionId,
                String.join("\n", recent), TASK_CONTEXT_TTL_SECONDS, out);
        core.log("→ blackboard context write (" + recent.size() + " lines) for session " + sessionId);
    }
}
