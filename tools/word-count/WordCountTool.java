///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../kernel/KernelEvent.java
//SOURCES ../../kernel/PluginConfig.java
//SOURCES ../../kernel/PluginBase.java

import com.fasterxml.jackson.databind.JsonNode;
import java.io.OutputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * WordCountTool — On-demand tool plugin for text statistical analysis.
 *
 * Implements the text.wordcount capability as a leaf worker in the MK8 pipeline.
 * It is designed as an on-demand plugin: it is launched automatically by the
 * PluginManager when a request arrives, and is terminated after 60 seconds of idle time.
 * Operates under the trigger event model, subscribing directly to rewritten
 * capability.tool.text.wordcount events published by the CapabilityInterceptor.
 *
 * Splits the target text on whitespace to calculate word count, extracts sentences,
 * strips punctuation, and builds lowercase term frequency tables. It determines
 * unique word metrics, extracts the top-5 most frequent words, and formats average
 * words per sentence. Serializes metrics into a double-nested JSON result format
 * to allow downstream consumers to parse the output without shared schemas.
 */
public class WordCountTool {

    public static void main(String[] args) throws Exception {
        KernelEvent.initLogging();
        new WordCountTool().start();
    }

    void start() throws Exception {
        System.out.println("[WORD-COUNT] Starting (on-demand)...");
        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, this::handle);
    }

    void handle(String json, OutputStream out) throws Exception {
        KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);

        // Only handle our trigger event
        if (!"capability.tool.text.wordcount".equals(event.type())) return;

        JsonNode payload = KernelEvent.MAPPER.readTree(event.payload());
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

        String resultPayload = KernelEvent.MAPPER.writeValueAsString(
                Map.of("result", KernelEvent.MAPPER.writeValueAsString(result)));

        // Reply via capability.result — Kernel routes back to caller via pendingRoutes (correlationId)
        PluginBase.publish(
                KernelEvent.withCorrelation("capability.result", resultPayload,
                        "word-count", event.correlationId(), event.sessionId()),
                out);

        System.out.println("[WORD-COUNT] Done — words=" + wordCount
                + " sentences=" + sentenceCount + " unique=" + freq.size());
    }

    void publishError(KernelEvent origin, String reason, OutputStream out) {
        try {
            String payload = KernelEvent.MAPPER.writeValueAsString(Map.of("reason", reason));
            PluginBase.publish(
                    KernelEvent.withCorrelation("capability.error", payload,
                            "word-count", origin.correlationId(), origin.sessionId()),
                    out);
        } catch (Exception ignored) {}
    }
}
