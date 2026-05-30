///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../kernel/Event.java
//SOURCES ../../kernel/BasePlugin.java

import com.fasterxml.jackson.databind.JsonNode;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.*;

/**
 * SummaryAgent — persistent agent plugin.
 *
 * Demonstrates:
 *   - Agent registration WITHOUT triggerEvent
 *     → CapabilityIndex routes capability.invoke{name:"text.analyze"} via message.summary-agent
 *   - Agent-to-tool invocation: sends capability.invoke{name:"text.wordcount"} internally
 *   - Pending call tracking: stores correlation state while waiting for tool response
 *   - capability.result fan-in: matches incoming results to pending requests via correlationId
 *
 * Interaction flow:
 *   DemoRunner                   Kernel                  SummaryAgent          WordCountTool
 *      │ capability.invoke         │                          │                     │
 *      │ {name:"text.analyze"}     │                          │                     │
 *      ├──────────────────────────►│                          │                     │
 *      │                          │ pendingRoutes[corrA]=demo │                     │
 *      │                          │ interceptors.route →      │                     │
 *      │                          │ message.summary-agent     │                     │
 *      │                          ├─────────────────────────►│                     │
 *      │                          │                          │ capability.invoke    │
 *      │                          │                          │ {name:"text.wordcount"│
 *      │                          │                          ├────────────────────►│
 *      │                          │ pendingRoutes[corrB]=     │                     │
 *      │                          │   summary-agent          │                     │
 *      │                          │ ProcessManager spawns WordCountTool on-demand   │
 *      │                          │                          │  capability.result  │
 *      │                          │◄────────────────────────────────────────────────┤
 *      │                          │ pendingRoutes[corrB]→    │                     │
 *      │                          │   summary-agent          │                     │
 *      │                          ├─────────────────────────►│                     │
 *      │                          │  capability.result       │                     │
 *      │◄──────────────────────────┤ pendingRoutes[corrA]→   │                     │
 *      │                          │   demo-runner            │                     │
 */
public class SummaryAgent {

    // Tracks in-flight tool calls: inner correlationId → outer correlationId + sessionId + original text
    static final ConcurrentHashMap<String, PendingAnalysis> pending = new ConcurrentHashMap<>();

    record PendingAnalysis(String outerCorrelationId, String sessionId, String originalText) {}

    static volatile OutputStream globalOut;

    public static void main(String[] args) throws Exception {
        Class.forName(Event.class.getName());
        System.out.println("[SUMMARY-AGENT] Starting...");
        BasePlugin.run("plugin.json", Event.DEFAULT_SOCKET, (json, out) -> {
            if (globalOut == null) globalOut = out;
            handle(json, out);
        });
    }

    static void handle(String json, OutputStream out) throws Exception {
        Event event = Event.MAPPER.readValue(json, Event.class);

        switch (event.type()) {
            case "message.summary-agent" -> handleAnalyzeRequest(event, out);
            case "capability.result"     -> handleToolResult(event, out);
            case "capability.error"      -> handleToolError(event, out);
        }
    }

    // ── Step 1: receive analyze request → delegate to WordCountTool ──────────

    static void handleAnalyzeRequest(Event event, OutputStream out) throws Exception {
        JsonNode payload = Event.MAPPER.readTree(event.payload());
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

        String invokePayload = Event.MAPPER.writeValueAsString(Map.of(
                "name", "text.wordcount",
                "text", text));

        // CapabilityIndex intercepts this → routes to WordCountTool (spawning it on-demand if needed)
        // pendingRoutes[innerCorrId] = "summary-agent" so the result comes back to us
        BasePlugin.publish(
                Event.withCorrelation("capability.invoke", invokePayload,
                        "summary-agent", innerCorrId, event.sessionId()),
                out);

        System.out.println("[SUMMARY-AGENT] Delegated to text.wordcount corrId=" + innerCorrId);
    }

    // ── Step 2: receive tool result → build summary → reply to DemoRunner ────

    static void handleToolResult(Event event, OutputStream out) throws Exception {
        String corrId = event.correlationId();
        PendingAnalysis ctx = pending.remove(corrId);

        if (ctx == null) {
            // Not our result (could be from another agent's invocation)
            return;
        }

        System.out.println("[SUMMARY-AGENT] Tool result received corrId=" + corrId);

        // Parse the nested result (WordCountTool wraps it in { "result": "...json..." })
        JsonNode wrapper = Event.MAPPER.readTree(event.payload());
        JsonNode stats   = Event.MAPPER.readTree(wrapper.path("result").asText("{}"));

        int    words     = stats.path("words").asInt(0);
        int    sentences = stats.path("sentences").asInt(0);
        int    unique    = stats.path("unique").asInt(0);
        String avgWps    = stats.path("avgWordsPerSentence").asText("?");

        // Build human-friendly summary
        String richness = unique > words * 0.7 ? "rich" : unique > words * 0.4 ? "moderate" : "repetitive";
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

    static void handleToolError(Event event, OutputStream out) throws Exception {
        String corrId = event.correlationId();
        PendingAnalysis ctx = pending.remove(corrId);
        if (ctx == null) return;

        JsonNode err = Event.MAPPER.readTree(event.payload());
        String reason = err.path("reason").asText("unknown error");
        System.err.println("[SUMMARY-AGENT] Tool error: " + reason);
        publishResult(ctx.outerCorrelationId(), ctx.sessionId(),
                "Analysis failed: " + reason, out);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    static void publishResult(String corrId, String sessionId, String summary, OutputStream out)
            throws Exception {
        String payload = Event.MAPPER.writeValueAsString(
                Map.of("result", summary));
        BasePlugin.publish(
                Event.withCorrelation("capability.result", payload,
                        "summary-agent", corrId, sessionId),
                out);
        System.out.println("[SUMMARY-AGENT] Result sent corrId=" + corrId);
    }

    static String buildTopWordsSummary(JsonNode stats) {
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
