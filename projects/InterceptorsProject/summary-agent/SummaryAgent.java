///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../../kernel/KernelEvent.java
//SOURCES ../../../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../../../kernel/interceptors/plugin/PluginBase.java

import com.fasterxml.jackson.databind.JsonNode;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.*;

/**
 * SummaryAgent — Persistent orchestrator plugin for text analysis.
 *
 * A persistent system plugin that connects at startup and handles the text.analyze
 * capability. Since it functions as an agent orchestrator, it contains no trigger
 * event declarations. Instead, the CapabilityInterceptor resolves its invocations
 * by sending direct peer-to-peer message frames, which this agent processes.
 *
 * When an analysis is requested, SummaryAgent delegates the heavy calculations to
 * the text.wordcount capability (implemented by WordCountTool) using a freshly generated
 * correlation ID. It maintains mapping state (PendingAnalysis) internally to relate
 * outer client correlations with inner tool requests. Upon tool completion,
 * it computes statistics, evaluates vocabulary richness, compiles a beautiful
 * markdown report, and replies back to the original client correlation context.
 */
public class SummaryAgent {

    // ── Event type constants ──────────────────────────────────────────────────
    static final String EVT_ANALYZE_REQUEST  = "message.summary-agent";
    static final String EVT_CAPABILITY_RESULT = "capability.result";
    static final String EVT_CAPABILITY_ERROR  = "capability.error";

    // ── Plugin / capability identity ──────────────────────────────────────────
    static final String SOURCE_ID            = "summary-agent";
    static final String CAPABILITY_WORDCOUNT = "text.wordcount";

    // ── Vocabulary richness thresholds ────────────────────────────────────────
    static final double RICHNESS_HIGH = 0.7;
    static final double RICHNESS_LOW  = 0.4;

    // Tracks in-flight tool calls: inner correlationId → outer correlationId + sessionId + original text
    final ConcurrentHashMap<String, PendingAnalysis> pending = new ConcurrentHashMap<>();

    record PendingAnalysis(String outerCorrelationId, String sessionId, String originalText) {}

    public static void main(String[] args) throws Exception {
        KernelEvent.initLogging();
        new SummaryAgent().start();
    }

    void start() throws Exception {
        System.out.println("[SUMMARY-AGENT] Starting...");
        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, this::handle);
    }

    void handle(String json, OutputStream out) throws Exception {
        KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);

        switch (event.type()) {
            case EVT_ANALYZE_REQUEST   -> handleAnalyzeRequest(event, out);
            case EVT_CAPABILITY_RESULT -> handleToolResult(event, out);
            case EVT_CAPABILITY_ERROR  -> handleToolError(event, out);
        }
    }

    // ── Step 1: receive analyze request → delegate to WordCountTool ──────────

    void handleAnalyzeRequest(KernelEvent event, OutputStream out) throws Exception {
        JsonNode payload = KernelEvent.MAPPER.readTree(event.payload());
        String   text    = payload.path("text").asText("").trim();

        System.out.println("[SUMMARY-AGENT] Analyze request: \""
                + text.substring(0, Math.min(text.length(), 60)) + "...\"");

        if (text.isBlank()) {
            publishResult(event.correlationId(), event.sessionId(),
                    "Error: no text provided.", out);
            return;
        }

        // Issue a capability.invoke for text.wordcount
        // The correlationId here becomes the KEY to route the result back to US
        String innerCorrId = UUID.randomUUID().toString();

        // Remember outer context so we can reply to DemoRunner when the tool answers
        pending.put(innerCorrId, new PendingAnalysis(
                event.correlationId(), event.sessionId(), text));

        String invokePayload = KernelEvent.MAPPER.writeValueAsString(Map.of(
                "name", CAPABILITY_WORDCOUNT,
                "text", text));

        // CapabilityInterceptor intercepts this → routes to WordCountTool (spawning it on-demand if needed)
        // pendingRoutes[innerCorrId] = SOURCE_ID so the result comes back to us
        PluginBase.publish(
                KernelEvent.withCorrelation("capability.invoke", invokePayload,
                        SOURCE_ID, innerCorrId, event.sessionId()),
                out);

        System.out.println("[SUMMARY-AGENT] Delegated to text.wordcount corrId=" + innerCorrId);
    }

    // ── Step 2: receive tool result → build summary → reply to DemoRunner ────

    void handleToolResult(KernelEvent event, OutputStream out) throws Exception {
        String corrId = event.correlationId();
        PendingAnalysis ctx = pending.remove(corrId);

        if (ctx == null) {
            // Not our result (could be from another agent's invocation)
            return;
        }

        System.out.println("[SUMMARY-AGENT] Tool result received corrId=" + corrId);

        // Parse the nested result (WordCountTool wraps it in { "result": "...json..." })
        JsonNode wrapper = KernelEvent.MAPPER.readTree(event.payload());
        JsonNode stats   = KernelEvent.MAPPER.readTree(wrapper.path("result").asText("{}"));

        int    words     = stats.path("words").asInt(0);
        int    sentences = stats.path("sentences").asInt(0);
        int    unique    = stats.path("unique").asInt(0);
        String avgWps    = stats.path("avgWordsPerSentence").asText("?");

        // Build human-friendly summary
        String richness = unique > words * RICHNESS_HIGH ? "rich" : unique > words * RICHNESS_LOW ? "moderate" : "repetitive";
        String summary  = String.format(
                """
                📄 Text Analysis Report
                ─────────────────────────────
                Words:            %d
                Sentences:        %d
                Unique words:     %d  (%s vocabulary)
                Avg words/sentence: %s
                ─────────────────────────────
                %s
                """,
                words, sentences, unique, richness, avgWps,
                buildTopWordsSummary(stats));

        // Reply to DemoRunner (outer correlationId)
        publishResult(ctx.outerCorrelationId(), ctx.sessionId(), summary, out);
    }

    void handleToolError(KernelEvent event, OutputStream out) throws Exception {
        String corrId = event.correlationId();
        PendingAnalysis ctx = pending.remove(corrId);
        if (ctx == null) return;

        JsonNode err = KernelEvent.MAPPER.readTree(event.payload());
        String reason = err.path("reason").asText("unknown error");
        System.err.println("[SUMMARY-AGENT] Tool error: " + reason);
        publishResult(ctx.outerCorrelationId(), ctx.sessionId(),
                "Analysis failed: " + reason, out);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    void publishResult(String corrId, String sessionId, String summary, OutputStream out)
            throws Exception {
        String payload = KernelEvent.MAPPER.writeValueAsString(
                Map.of("result", summary));
        PluginBase.publish(
                KernelEvent.withCorrelation(EVT_CAPABILITY_RESULT, payload,
                        SOURCE_ID, corrId, sessionId),
                out);
        System.out.println("[SUMMARY-AGENT] Result sent corrId=" + corrId);
    }

    String buildTopWordsSummary(JsonNode stats) {
        JsonNode topWords = stats.path("topWords");
        if (!topWords.isArray() || topWords.isEmpty()) return "";
        var sb = new StringBuilder("Top words: ");
        for (JsonNode tw : topWords) {
            sb.append(tw.path("word").asText())
              .append("(×").append(tw.path("count").asInt()).append(") ");
        }
        return sb.toString().trim();
    }
}
