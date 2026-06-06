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
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;

/**
 * BrowserTool — HTTP fetch, JS-aware page reading, and binary download.
 *
 * Capability: tool.browser.fetch
 *   op:read     → Fetches URL via Jina AI Reader (renders JS, returns clean Markdown). Best for modern sites.
 *   op:fetch    → Raw GET, returns HTML/JSON/plain text. Fast but no JS rendering.
 *   op:download → GET URL, save bytes to workspace/. Rejects HTML responses.
 *
 * Marked internal:true — visible to researcher/writer (seeInternalTools), not to assistant.
 */
public class BrowserTool {

    static final int     DEFAULT_MAX_CHARS = 8000;
    static final String  JINA_PREFIX       = "https://r.jina.ai/";
    static final String  USER_AGENT        =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/125.0.0.0 Safari/537.36";

    static final Path    WORKSPACE = findWorkspace();
    static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public static void main(String[] args) throws Exception {
        Files.createDirectories(WORKSPACE);
        System.out.println("[TOOL-BROWSER] Starting. Workspace: " + WORKSPACE);
        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, BrowserTool::handle);
    }

    static void handle(String json, OutputStream out) throws Exception {
        KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);

        if (!"capability.tool.browser.fetch".equals(event.type())) return;

        try {
            JsonNode payload = KernelEvent.MAPPER.readTree(event.payload());
            JsonNode input   = payload.has("input") ? payload.get("input") : KernelEvent.MAPPER.createObjectNode();
            String op  = input.path("op").asText("");
            String url = input.path("url").asText("").trim();

            if (url.isBlank()) throw new IllegalArgumentException("'url' is required");
            if (!url.startsWith("http://") && !url.startsWith("https://"))
                throw new IllegalArgumentException("Only http/https URLs are supported");

            String result = switch (op) {
                case "read"     -> opRead(url, input.path("maxChars").asInt(DEFAULT_MAX_CHARS));
                case "fetch"    -> opFetch(url, input.path("maxChars").asInt(DEFAULT_MAX_CHARS));
                case "download" -> opDownload(url, input.path("dest").asText(""));
                default         -> throw new IllegalArgumentException(
                        "Unknown op: '" + op + "'. Use 'read' (JS-rendered Markdown), " +
                        "'fetch' (raw HTML), or 'download' (binary to workspace/)");
            };

            PluginBase.publish(
                    KernelEvent.withCorrelation("capability.result",
                            KernelEvent.MAPPER.writeValueAsString(Map.of("result", result)),
                            "tool-browser", event.correlationId(), event.sessionId()),
                    out);
            log("op=" + op + " url=" + truncate(url, 80), out);

        } catch (Exception e) {
            PluginBase.publish(
                    KernelEvent.withCorrelation("capability.error",
                            KernelEvent.MAPPER.writeValueAsString(Map.of("reason", e.getMessage())),
                            "tool-browser", event.correlationId(), event.sessionId()),
                    out);
            log("ERROR: " + e.getMessage(), out);
        }
    }

    // ── op: read (Jina AI — JS-aware, returns clean Markdown) ────────────────

    static String opRead(String url, int maxChars) throws Exception {
        String jinaUrl = JINA_PREFIX + url;
        var req  = request(jinaUrl, 45);
        var resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assertOk(resp.statusCode(), jinaUrl);
        String body = resp.body();
        if (body.length() > maxChars)
            return body.substring(0, maxChars) + "\n...[truncated at " + maxChars + " of " + body.length() + " chars]";
        return body;
    }

    // ── op: fetch (raw HTTP, no JS) ───────────────────────────────────────────

    static String opFetch(String url, int maxChars) throws Exception {
        var req  = request(url, 30);
        var resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assertOk(resp.statusCode(), url);

        String ct = contentType(resp);
        if (isBinary(ct))
            throw new IllegalStateException(
                    "URL returns binary content (" + ct + "). Use op:download to save it to workspace/.");

        String body = resp.body();
        if (body.length() > maxChars)
            return body.substring(0, maxChars) + "\n...[truncated at " + maxChars + " of " + body.length() + " chars]";
        return body;
    }

    // ── op: download ──────────────────────────────────────────────────────────

    static String opDownload(String url, String dest) throws Exception {
        String filename = dest.isBlank() ? filenameFromUrl(url) : stripWorkspacePrefix(dest);
        Path target = WORKSPACE.resolve(filename).normalize();
        if (!target.startsWith(WORKSPACE))
            throw new SecurityException("Path traversal blocked: " + dest);
        Files.createDirectories(target.getParent());

        Path temp = Files.createTempFile(WORKSPACE, ".dl-", ".tmp");
        try {
            var req  = request(url, 120);
            var resp = HTTP.send(req, HttpResponse.BodyHandlers.ofFile(temp));
            assertOk(resp.statusCode(), url);

            if (dest.isBlank()) {
                String better = resolveFilename(url, resp);
                if (!better.equals(filename)) {
                    Path candidate = WORKSPACE.resolve(better).normalize();
                    if (candidate.startsWith(WORKSPACE)) {
                        target   = candidate;
                        filename = better;
                    }
                }
            }

            String ct = contentType(resp);
            if (ct.contains("text/html") || ct.contains("text/xhtml")) {
                Files.deleteIfExists(temp);
                throw new IllegalStateException(
                        "URL returned HTML, not a binary file. " +
                        "Use op:read or op:fetch to retrieve the page and find the direct file URL, " +
                        "then call op:download with that direct URL.");
            }

            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            Files.deleteIfExists(temp);
            throw e;
        }

        long size = Files.size(target);
        log("download → workspace/" + filename + " (" + size + " bytes)", null);
        return "saved " + filename + " (" + humanSize(size) + ") → workspace/" + filename;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    static HttpRequest request(String url, int timeoutSecs) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                .timeout(Duration.ofSeconds(timeoutSecs))
                .GET().build();
    }

    static void assertOk(int status, String url) {
        if (status < 200 || status >= 300)
            throw new RuntimeException("HTTP " + status + " for " + url);
    }

    static String contentType(HttpResponse<?> resp) {
        return resp.headers().firstValue("Content-Type").orElse("").toLowerCase();
    }

    static boolean isBinary(String ct) {
        return ct.startsWith("image/") || ct.startsWith("video/") || ct.startsWith("audio/")
                || ct.contains("octet-stream") || ct.contains("pdf");
    }

    static String filenameFromUrl(String url) {
        String path = URI.create(url).getPath();
        String last = path.substring(path.lastIndexOf('/') + 1);
        return last.isBlank() ? "download.bin" : last;
    }

    static String stripWorkspacePrefix(String dest) {
        if (dest.startsWith("workspace/")) return dest.substring("workspace/".length());
        if (dest.equals("workspace"))      return "";
        return dest;
    }

    static String humanSize(long bytes) {
        if (bytes < 1024)           return bytes + " B";
        if (bytes < 1024 * 1024)    return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    static String resolveFilename(String url, HttpResponse<?> resp) {
        String cd = resp.headers().firstValue("Content-Disposition").orElse("");
        if (cd.contains("filename=")) {
            String fn = cd.replaceAll(".*filename=[\"']?([^\"';]+)[\"']?.*", "$1").trim();
            if (!fn.isBlank() && !fn.equals(cd)) return fn;
        }
        String path = URI.create(url).getPath();
        String last = path.substring(path.lastIndexOf('/') + 1);
        if (!last.isBlank()) return last;
        String ct  = contentType(resp);
        String ext = ct.contains("jpeg") || ct.contains("jpg") ? ".jpg"
                   : ct.contains("png")  ? ".png"
                   : ct.contains("gif")  ? ".gif"
                   : ct.contains("webp") ? ".webp"
                   : ct.contains("pdf")  ? ".pdf"
                   : ".bin";
        return "download" + ext;
    }

    static Path findWorkspace() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("Boot.java")) ||
                Files.exists(current.resolve("Start.java")))
                return current.resolve("workspace").normalize();
            current = current.getParent();
        }
        return Path.of("workspace").toAbsolutePath().normalize();
    }

    static void log(String msg, OutputStream out) {
        System.out.println("[TOOL-BROWSER] " + msg);
    }

    static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
