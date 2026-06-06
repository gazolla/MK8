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
 * SearchTool — web, image and news search via the Brave Search API.
 *
 * Requires env var: BRAVE_API_KEY
 * Trigger event: capability.tool.search.query
 * Input:  {"name": "tool.search.query", "input": {"query": "...", "type": "web|images|news", "count": 5}}
 *
 * type=web    → numbered list of title, URL, description (default)
 * type=images → numbered list of title, direct image URL, page URL
 * type=news   → numbered list of title, URL, source, age, description
 */
public class SearchTool {

    static final String BRAVE_WEB    = "https://api.search.brave.com/res/v1/web/search";
    static final String BRAVE_IMAGES = "https://api.search.brave.com/res/v1/images/search";
    static final String BRAVE_NEWS   = "https://api.search.brave.com/res/v1/news/search";

    static final HttpClient HTTP = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        System.out.println("[TOOL-SEARCH] Starting...");
        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, SearchTool::handle);
    }

    static void handle(String json, OutputStream out) throws Exception {
        KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);

        if (!"capability.tool.search.query".equals(event.type())) return;

        try {
            String apiKey = System.getenv("BRAVE_API_KEY");
            if (apiKey == null || apiKey.isBlank())
                throw new IllegalStateException("BRAVE_API_KEY environment variable is not set");

            JsonNode payload = KernelEvent.MAPPER.readTree(event.payload());
            JsonNode input   = payload.has("input") ? payload.get("input") : KernelEvent.MAPPER.createObjectNode();

            String query = input.has("query")   ? input.get("query").asText("")
                         : payload.has("query") ? payload.get("query").asText("") : "";
            String type  = input.has("type")    ? input.get("type").asText("web")
                         : payload.has("type")  ? payload.get("type").asText("web") : "web";
            int count    = input.has("count")   ? input.get("count").asInt(5)
                         : payload.has("count") ? payload.get("count").asInt(5) : 5;

            if (query.isBlank()) throw new IllegalArgumentException("query must not be empty");

            String endpoint = switch (type) {
                case "images" -> BRAVE_IMAGES;
                case "news"   -> BRAVE_NEWS;
                default       -> BRAVE_WEB;
            };

            String url = endpoint + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&count=" + count;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .header("X-Subscription-Token", apiKey)
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200)
                throw new RuntimeException("Brave API returned HTTP " + response.statusCode());

            JsonNode body = KernelEvent.MAPPER.readTree(response.body());
            String formatted = switch (type) {
                case "images" -> formatImages(body);
                case "news"   -> formatNews(body);
                default       -> formatWeb(body);
            };

            PluginBase.publish(
                    KernelEvent.withCorrelation("capability.result",
                            KernelEvent.MAPPER.writeValueAsString(Map.of("result", formatted)),
                            "tool-search", event.correlationId(), event.sessionId()),
                    out);
            log("Search [" + type + "]: \"" + query + "\" → " + count + " results", out);

        } catch (Exception e) {
            PluginBase.publish(
                    KernelEvent.withCorrelation("capability.error",
                            KernelEvent.MAPPER.writeValueAsString(Map.of("reason", e.getMessage())),
                            "tool-search", event.correlationId(), event.sessionId()),
                    out);
            log("ERROR: " + e.getMessage(), out);
        }
    }

    // ── Formatters ────────────────────────────────────────────────────────────

    static String formatWeb(JsonNode body) {
        JsonNode results = body.path("web").path("results");
        if (!results.isArray() || results.isEmpty()) return "No results found.";
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (JsonNode item : results) {
            sb.append(i++).append(". ").append(item.path("title").asText("(no title)")).append("\n");
            sb.append("   ").append(item.path("url").asText("")).append("\n");
            String desc = item.path("description").asText("").strip();
            if (!desc.isEmpty()) sb.append("   ").append(desc).append("\n");
            sb.append("\n");
        }
        return sb.toString().strip();
    }

    static String formatImages(JsonNode body) {
        JsonNode results = body.path("results");
        if (!results.isArray() || results.isEmpty()) return "No image results found.";
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (JsonNode item : results) {
            String title    = item.path("title").asText("(no title)");
            String pageUrl  = item.path("url").asText("");
            String imageUrl = item.path("properties").path("url").asText("");
            String thumb    = item.path("thumbnail").path("src").asText("");

            sb.append(i++).append(". ").append(title).append("\n");
            if (!imageUrl.isEmpty()) sb.append("   Direct image URL: ").append(imageUrl).append("\n");
            if (!thumb.isEmpty())    sb.append("   Thumbnail: ").append(thumb).append("\n");
            if (!pageUrl.isEmpty())  sb.append("   Page: ").append(pageUrl).append("\n");
            sb.append("\n");
        }
        return sb.toString().strip();
    }

    static String formatNews(JsonNode body) {
        JsonNode results = body.path("results");
        if (!results.isArray() || results.isEmpty()) return "No news results found.";
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (JsonNode item : results) {
            String title  = item.path("title").asText("(no title)");
            String url    = item.path("url").asText("");
            String source = item.path("source").path("name").asText("");
            String age    = item.path("age").asText("");
            String desc   = item.path("description").asText("").strip();

            sb.append(i++).append(". ").append(title).append("\n");
            sb.append("   ").append(url).append("\n");
            if (!source.isEmpty() || !age.isEmpty())
                sb.append("   ").append(source).append(age.isEmpty() ? "" : " · " + age).append("\n");
            if (!desc.isEmpty()) sb.append("   ").append(desc).append("\n");
            sb.append("\n");
        }
        return sb.toString().strip();
    }

    static void log(String msg, OutputStream out) {
        System.out.println("[TOOL-SEARCH] " + msg);
    }
}
