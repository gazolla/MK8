///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../kernel/Event.java
//SOURCES ../../kernel/BasePlugin.java

import com.fasterxml.jackson.databind.JsonNode;
import java.io.OutputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * WordCountTool — on-demand tool plugin.
 *
 * Demonstrates:
 *   - Tool registration with triggerEvent (capability.tool.text.wordcount)
 *   - CapabilityIndex routes capability.invoke{name:"text.wordcount"} here
 *     by re-publishing as the triggerEvent type
 *   - Plugin is started on-demand by ProcessManager when first invoked
 *   - After idleTimeoutSeconds (60s) without traffic, ProcessManager kills it
 *
 * Input payload:  { "text": "...", "correlationId": "..." }
 * Output event:   capability.result { "result": { "words": N, "sentences": N, "unique": N, "topWords": [...] } }
 */
public class WordCountTool {

    public static void main(String[] args) throws Exception {
        Class.forName(Event.class.getName());
        System.out.println("[WORD-COUNT] Starting (on-demand)...");
        BasePlugin.run("plugin.json", Event.DEFAULT_SOCKET, WordCountTool::handle);
    }

    static void handle(String json, OutputStream out) throws Exception {
        Event event = Event.MAPPER.readValue(json, Event.class);

        // Only handle our trigger event
        if (!"capability.tool.text.wordcount".equals(event.type())) return;

        JsonNode payload = Event.MAPPER.readTree(event.payload());
        String   text    = payload.path("text").asText("").trim();

        if (text.isBlank()) {
            publishError(event, "text field is missing or empty", out);
            return;
        }

        System.out.println("[WORD-COUNT] Counting: " + text.length() + " chars");

        // ── Analysis ──────────────────────────────────────────────────────────
        String[] tokens     = text.split("\\s+");
        int      wordCount  = tokens.length;

        // Sentence count: split on . ! ? (allowing for abbreviations)
        int sentenceCount = (int) Arrays.stream(text.split("[.!?]+"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .count();
        if (sentenceCount == 0) sentenceCount = 1;

        // Unique words (case-insensitive, stripped of punctuation)
        Map<String, Long> freq = Arrays.stream(tokens)
                .map(t -> t.toLowerCase().replaceAll("[^a-z0-9]", ""))
                .filter(t -> !t.isBlank())
                .collect(Collectors.groupingBy(t -> t, Collectors.counting()));

        List<Map<String, Object>> topWords = freq.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5)
                .map(e -> Map.<String, Object>of("word", e.getKey(), "count", e.getValue()))
                .toList();

        // ── Build result ──────────────────────────────────────────────────────
        var result = new LinkedHashMap<String, Object>();
        result.put("words",     wordCount);
        result.put("sentences", sentenceCount);
        result.put("unique",    freq.size());
        result.put("topWords",  topWords);
        result.put("avgWordsPerSentence", String.format("%.1f", (double) wordCount / sentenceCount));

        String resultPayload = Event.MAPPER.writeValueAsString(
                Map.of("result", Event.MAPPER.writeValueAsString(result)));

        // Reply via capability.result — Kernel routes back to caller via pendingRoutes (correlationId)
        BasePlugin.publish(
                Event.withCorrelation("capability.result", resultPayload,
                        "word-count", event.correlationId(), event.sessionId()),
                out);

        System.out.println("[WORD-COUNT] Done — words=" + wordCount
                + " sentences=" + sentenceCount + " unique=" + freq.size());
    }

    static void publishError(Event origin, String reason, OutputStream out) {
        try {
            String payload = Event.MAPPER.writeValueAsString(Map.of("reason", reason));
            BasePlugin.publish(
                    Event.withCorrelation("capability.error", payload,
                            "word-count", origin.correlationId(), origin.sessionId()),
                    out);
        } catch (Exception ignored) {}
    }
}
