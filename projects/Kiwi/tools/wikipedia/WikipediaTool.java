///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../../../kernel/KernelEvent.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginBase.java

import com.fasterxml.jackson.databind.JsonNode;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * WikipediaTool — fetches the introductory extract of a Wikipedia article.
 *
 * No API key required.
 * Trigger event: capability.tool.wikipedia.lookup
 * Input:  {"name": "tool.wikipedia.lookup", "input": {"query": "...", "lang": "en"}}
 * Result: {"result": "Title\nURL\n\nSummary text..."}
 */
public class WikipediaTool {

    static final String WIKI_API = "https://%s.wikipedia.org/w/api.php"
            + "?action=query&prop=extracts&exintro=true&explaintext=true"
            + "&generator=search&gsrsearch=%s&gsrlimit=1&format=json&redirects=1";
    static final int MAX_EXTRACT_CHARS = 1200;
    static final HttpClient HTTP = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        System.out.println("[TOOL-WIKIPEDIA] Starting...");
        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, WikipediaTool::handle);
    }

    static void handle(String json, OutputStream out) throws Exception {
        KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);

        if (!"capability.tool.wikipedia.lookup".equals(event.type())) return;

        try {
            JsonNode payload = KernelEvent.MAPPER.readTree(event.payload());
            JsonNode input   = payload.has("input") ? payload.get("input") : KernelEvent.MAPPER.createObjectNode();
            // accept "query"/"lang" at top-level as fallback
            String query     = input.has("query") ? input.get("query").asText("")
                             : payload.has("query") ? payload.get("query").asText("") : "";
            String lang      = input.has("lang")  ? input.get("lang").asText("en")
                             : payload.has("lang") ? payload.get("lang").asText("en") : "en";

            if (query.isBlank()) throw new IllegalArgumentException("query must not be empty");

            String url = String.format(WIKI_API, lang, URLEncoder.encode(query, StandardCharsets.UTF_8));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .header("User-Agent", "Kiwi-WikipediaTool/1.0 (microkernel research agent)")
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Wikipedia API returned HTTP " + response.statusCode());
            }

            String formatted = formatResult(KernelEvent.MAPPER.readTree(response.body()), lang);
            String result = KernelEvent.MAPPER.writeValueAsString(Map.of("result", formatted));

            PluginBase.publish(
                    KernelEvent.withCorrelation("capability.result", result, "tool-wikipedia",
                            event.correlationId(), event.sessionId()),
                    out);
            log("Wikipedia: \"" + query + "\" (lang=" + lang + ")", out);

        } catch (Exception e) {
            String errPayload = KernelEvent.MAPPER.writeValueAsString(Map.of("reason", e.getMessage()));
            PluginBase.publish(
                    KernelEvent.withCorrelation("capability.error", errPayload, "tool-wikipedia",
                            event.correlationId(), event.sessionId()),
                    out);
            log("ERROR: " + e.getMessage(), out);
        }
    }

    static String formatResult(JsonNode body, String lang) {
        JsonNode pages = body.path("query").path("pages");
        if (pages.isMissingNode() || !pages.isObject()) return "No Wikipedia article found.";

        JsonNode page = pages.fields().next().getValue();
        String title   = page.path("title").asText("Unknown");
        String extract = page.path("extract").asText("").strip();
        int pageId     = page.path("pageid").asInt(-1);

        if (extract.isEmpty()) return "Article found but no extract available for: " + title;

        if (extract.length() > MAX_EXTRACT_CHARS) {
            int cutoff = extract.lastIndexOf(' ', MAX_EXTRACT_CHARS);
            extract = extract.substring(0, cutoff > 0 ? cutoff : MAX_EXTRACT_CHARS) + "...";
        }

        String articleUrl = "https://" + lang + ".wikipedia.org/wiki/"
                + title.replace(' ', '_');

        return title + "\n" + articleUrl + "\n\n" + extract;
    }

    static void log(String msg, OutputStream out) {
        System.out.println("[TOOL-WIKIPEDIA] " + msg);
    }
}
