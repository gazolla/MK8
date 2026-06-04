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

    // ── Read ─────────────────────────────────────────────────────────────────

    String read(String key, String scope, String scopeId, OutputStream out) {
        String corrId = UUID.randomUUID().toString();
        CompletableFuture<KernelEvent> future = new CompletableFuture<>();
        pendingBlackboardReads.put(corrId, future);
        try {
            var payload = KernelEvent.MAPPER.writeValueAsString(
                    Map.of("key", key, "scope", scope, "scopeId", scopeId));
            PluginBase.publish(
                    KernelEvent.withCorrelation("blackboard.read", payload, config.id(), corrId, null), out);
            KernelEvent reply = future.get(2, TimeUnit.SECONDS);
            JsonNode result = KernelEvent.MAPPER.readTree(reply.payload());
            if (result.has("found") && !result.get("found").asBoolean(true)) return null;
            return result.has("value") ? result.get("value").asText() : null;
        } catch (Exception e) {
            return null;
        } finally {
            pendingBlackboardReads.remove(corrId);
        }
    }

    public ReadResult readWithMetadata(String key, String scope, String scopeId, OutputStream out) {
        String corrId = UUID.randomUUID().toString();
        CompletableFuture<KernelEvent> future = new CompletableFuture<>();
        pendingBlackboardReads.put(corrId, future);
        try {
            var payload = KernelEvent.MAPPER.writeValueAsString(
                    Map.of("key", key, "scope", scope, "scopeId", scopeId));
            PluginBase.publish(
                    KernelEvent.withCorrelation("blackboard.read", payload, config.id(), corrId, null), out);
            KernelEvent reply = future.get(2, TimeUnit.SECONDS);
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
        } finally {
            pendingBlackboardReads.remove(corrId);
        }
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    void write(String key, String scope, String scopeId, String value,
               long ttlSeconds, OutputStream out) {
        try {
            var payload = KernelEvent.MAPPER.writeValueAsString(Map.of(
                    "key", key, "scope", scope, "scopeId", scopeId,
                    "value", value, "ttl", ttlSeconds, "tags", List.of("research")));
            PluginBase.publishSafe(KernelEvent.of("blackboard.write", payload, config.id()), out);
        } catch (Exception e) {
            core.logError("blackboard write failed: " + e.getMessage());
        }
    }

    public WriteResult writeConditional(String key, String scope, String scopeId, String value,
                                        long expectedVersion, long ttlSeconds, OutputStream out) {
        String corrId = UUID.randomUUID().toString();
        CompletableFuture<KernelEvent> future = new CompletableFuture<>();
        pendingBlackboardReads.put(corrId, future);
        try {
            var payload = KernelEvent.MAPPER.writeValueAsString(Map.of(
                    "key", key, "scope", scope, "scopeId", scopeId,
                    "value", value, "ttl", ttlSeconds, "expectedVersion", expectedVersion,
                    "tags", List.of("research")));
            PluginBase.publish(
                    KernelEvent.withCorrelation("blackboard.write", payload, config.id(), corrId, null), out);

            KernelEvent reply = future.get(2, TimeUnit.SECONDS);
            JsonNode result = KernelEvent.MAPPER.readTree(reply.payload());

            if ("blackboard.write.conflict".equals(reply.type())) {
                return new WriteResult(false, result.path("currentVersion").asLong(0), result.path("reason").asText("conflict"));
            } else {
                return new WriteResult(true, result.path("version").asLong(0), null);
            }
        } catch (Exception e) {
            return new WriteResult(false, 0, e.getMessage());
        } finally {
            pendingBlackboardReads.remove(corrId);
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
                lines.add("user: " + core.truncate(content, 300));
            } else if (msg instanceof AiMessage am) {
                if (am.hasToolExecutionRequests()) continue;
                String content = am.text();
                if (content != null && !content.isBlank()) {
                    lines.add("assistant: " + core.truncate(content, 300));
                }
            }
        }
        if (lines.isEmpty()) return;

        List<String> recent = lines.size() > 6
                ? lines.subList(lines.size() - 6, lines.size()) : lines;
        write("task.context", "session", sessionId,
                String.join("\n", recent), 600, out);
        core.log("→ blackboard context write (" + recent.size() + " lines) for session " + sessionId);
    }
}
