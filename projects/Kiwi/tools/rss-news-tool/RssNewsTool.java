///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../../../kernel/KernelEvent.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginBase.java

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import java.util.HashMap;

public class RssNewsTool {

    static final Set<String> CAPABILITIES = Set.of("tool.rss.fetch");

    static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    // Realistic browser UA — anti-bot defenses (Cloudflare etc.) reject obvious bot agents.
    static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    // Common RSS/Atom feed paths to try
    static final String[] COMMON_FEED_PATHS = {
            "/rss", "/feed", "/rss.xml", "/feed.xml",
            "/atom.xml", "/feeds/posts/default",
            "/index.xml", "/feed/rss", "/news/rss",
            "/rss/feed", "/?feed=rss2"
    };

    // Pattern to find RSS/Atom link tags in HTML
    static final Pattern LINK_RSS_PATTERN = Pattern.compile(
            "<link[^>]+type\\s*=\\s*[\"']application/(?:rss|atom)\\+xml[\"'][^>]*>",
            Pattern.CASE_INSENSITIVE
    );
    static final Pattern HREF_PATTERN = Pattern.compile(
            "href\\s*=\\s*[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE
    );

    public static void main(String[] args) throws Exception {
        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, RssNewsTool::handle);
    }

    static void handle(String json, OutputStream out) throws Exception {
        KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);

        if ("capability.tool.rss.fetch".equals(event.type())) {
            handleFetch(event, out);
        }
    }

    static void handleFetch(KernelEvent event, OutputStream out) throws Exception {
        try {
            JsonNode input = KernelEvent.MAPPER.readTree(event.payload()).path("input");
            String siteUrl = input.path("url").asText("").trim();

            if (siteUrl.isEmpty()) {
                reply(event, Map.of(
                        "result", "Erro: parâmetro 'url' é obrigatório.",
                        "rssSupported", false
                ), out);
                return;
            }

            // Normalize URL
            if (!siteUrl.startsWith("http://") && !siteUrl.startsWith("https://")) {
                siteUrl = "https://" + siteUrl;
            }
            // Remove trailing slash
            if (siteUrl.endsWith("/")) {
                siteUrl = siteUrl.substring(0, siteUrl.length() - 1);
            }

            // Step 1: Try to discover RSS feed URL
            String feedUrl = discoverFeedUrl(siteUrl);

            if (feedUrl == null) {
                // Step 2: Try common feed paths
                feedUrl = tryCommonPaths(siteUrl);
            }

            if (feedUrl == null) {
                reply(event, Map.of(
                        "result", "O site " + siteUrl + " não parece suportar RSS. Nenhum feed RSS/Atom foi encontrado.",
                        "rssSupported", false,
                        "site", siteUrl
                ), out);
                return;
            }

            // Step 3: Parse the feed
            List<Map<String, String>> items = parseFeed(feedUrl);

            if (items.isEmpty()) {
                reply(event, Map.of(
                        "result", "Feed RSS encontrado em " + feedUrl + " mas nenhuma notícia foi extraída.",
                        "rssSupported", true,
                        "feedUrl", feedUrl,
                        "site", siteUrl,
                        "newsCount", 0
                ), out);
                return;
            }

            // Build result
            ObjectNode resultNode = KernelEvent.MAPPER.createObjectNode();
            resultNode.put("result", "Foram encontradas " + items.size() + " notícias via RSS no site " + siteUrl + ".");
            resultNode.put("rssSupported", true);
            resultNode.put("feedUrl", feedUrl);
            resultNode.put("site", siteUrl);
            resultNode.put("newsCount", items.size());

            ArrayNode newsArray = KernelEvent.MAPPER.createArrayNode();
            for (Map<String, String> item : items) {
                ObjectNode newsItem = KernelEvent.MAPPER.createObjectNode();
                item.forEach(newsItem::put);
                newsArray.add(newsItem);
            }
            resultNode.set("news", newsArray);

            String resultPayload = KernelEvent.MAPPER.writeValueAsString(resultNode);
            PluginBase.publish(
                    KernelEvent.withCorrelation("capability.result", resultPayload,
                            "rss-news-tool", event.correlationId(), event.sessionId()),
                    out);

        } catch (Exception e) {
            error(event, e, out);
        }
    }

    /**
     * Fetches the site's HTML and looks for <link> tags pointing to RSS/Atom feeds.
     */
    static String discoverFeedUrl(String siteUrl) {
        try {
            String body = fetch(siteUrl, "text/html,application/xhtml+xml");
            if (body == null) {
                return null;
            }

            // Check if the response itself is an RSS/Atom feed
            if (isRssContent(body)) {
                return siteUrl;
            }

            // Look for <link type="application/rss+xml"> or <link type="application/atom+xml">
            Matcher linkMatcher = LINK_RSS_PATTERN.matcher(body);
            if (linkMatcher.find()) {
                String linkTag = linkMatcher.group();
                Matcher hrefMatcher = HREF_PATTERN.matcher(linkTag);
                if (hrefMatcher.find()) {
                    String href = hrefMatcher.group(1);
                    return resolveUrl(siteUrl, href);
                }
            }

        } catch (Exception e) {
            // Silently fail — will try common paths next
        }
        return null;
    }

    /**
     * Tries common RSS feed paths on the given site.
     */
    static String tryCommonPaths(String siteUrl) {
        for (String path : COMMON_FEED_PATHS) {
            String candidateUrl = siteUrl + path;
            String body = fetch(candidateUrl, "application/rss+xml, application/atom+xml, application/xml, text/xml");
            if (body != null && isRssContent(body)) {
                return candidateUrl;
            }
        }
        return null;
    }

    /**
     * GETs a URL and returns the body as a UTF-8 string, transparently decoding gzip.
     * Some CDNs (e.g. metropoles.com) return Content-Encoding: gzip even when not requested;
     * the JDK HttpClient does not auto-decompress, so the raw bytes would corrupt the XML prolog.
     * Returns null on non-200 or any failure (callers fall through to the next strategy).
     */
    static String fetch(String url, String accept) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", accept)
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) return null;

            byte[] body = response.body();
            boolean gzipped = response.headers().firstValue("Content-Encoding")
                    .map(e -> e.toLowerCase().contains("gzip")).orElse(false);
            if (gzipped) {
                try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(body))) {
                    body = gz.readAllBytes();
                }
            }
            return new String(body, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Checks if the content looks like an RSS or Atom feed.
     */
    static boolean isRssContent(String content) {
        if (content == null || content.isBlank()) return false;
        String lower = content.toLowerCase();
        return lower.contains("<rss") || lower.contains("<feed") ||
               lower.contains("<channel>") || lower.contains("xmlns:atom");
    }

    /**
     * Detects an anti-bot interstitial (Cloudflare "Just a moment...", JS challenge) that
     * returns HTTP 200 with an HTML body where a feed was expected.
     */
    static boolean looksLikeChallenge(String content) {
        if (content == null || content.isBlank()) return false;
        String lower = content.toLowerCase();
        return lower.contains("just a moment")
            || lower.contains("cf-browser-verification")
            || lower.contains("challenge-platform")
            || lower.contains("enable javascript and cookies");
    }

    /**
     * Parses an RSS or Atom feed and extracts news items.
     */
    static List<Map<String, String>> parseFeed(String feedUrl) throws Exception {
        List<Map<String, String>> items = new ArrayList<>();

        String xml = fetch(feedUrl, "application/rss+xml, application/atom+xml, application/xml, text/xml");
        if (xml == null) {
            return items;
        }

        // Strip BOM / leading whitespace before the XML prolog. A stray byte before
        // <?xml triggers the strict parser's "content not allowed in prolog" fatal error.
        int lt = xml.indexOf('<');
        if (lt > 0) xml = xml.substring(lt);

        // Anti-bot challenge or plain HTML returned instead of a feed → no items (handled gracefully upstream).
        if (looksLikeChallenge(xml) || !isRssContent(xml)) {
            return items;
        }

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        doc.getDocumentElement().normalize();

        String rootTag = doc.getDocumentElement().getTagName().toLowerCase();

        if (rootTag.contains("feed")) {
            // Atom feed
            items = parseAtomFeed(doc);
        } else {
            // RSS feed
            items = parseRssFeed(doc);
        }

        // Limit to 20 items
        if (items.size() > 20) {
            items = items.subList(0, 20);
        }

        return items;
    }

    static List<Map<String, String>> parseRssFeed(Document doc) {
        List<Map<String, String>> items = new ArrayList<>();
        NodeList itemNodes = doc.getElementsByTagName("item");

        for (int i = 0; i < itemNodes.getLength(); i++) {
            Element item = (Element) itemNodes.item(i);
            Map<String, String> newsItem = new LinkedHashMap<>();

            newsItem.put("title", getElementText(item, "title"));
            newsItem.put("link", getElementText(item, "link"));
            newsItem.put("description", cleanHtml(getElementText(item, "description")));
            newsItem.put("pubDate", getElementText(item, "pubDate"));
            newsItem.put("author", getElementText(item, "author"));

            // Remove empty fields
            newsItem.values().removeIf(v -> v == null || v.isBlank());
            if (!newsItem.isEmpty()) {
                items.add(newsItem);
            }
        }
        return items;
    }

    static List<Map<String, String>> parseAtomFeed(Document doc) {
        List<Map<String, String>> items = new ArrayList<>();
        NodeList entryNodes = doc.getElementsByTagNameNS("*", "entry");

        for (int i = 0; i < entryNodes.getLength(); i++) {
            Element entry = (Element) entryNodes.item(i);
            Map<String, String> newsItem = new LinkedHashMap<>();

            newsItem.put("title", getElementText(entry, "title"));
            newsItem.put("link", getAtomLink(entry));
            newsItem.put("description", cleanHtml(getElementText(entry, "summary")));
            newsItem.put("pubDate", getElementText(entry, "updated"));
            newsItem.put("author", getAtomAuthor(entry));

            newsItem.values().removeIf(v -> v == null || v.isBlank());
            if (!newsItem.isEmpty()) {
                items.add(newsItem);
            }
        }
        return items;
    }

    static String getElementText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            // Try with namespace wildcard
            nodes = parent.getElementsByTagNameNS("*", tagName);
        }
        if (nodes.getLength() > 0) {
            return nodes.item(0).getTextContent().trim();
        }
        return "";
    }

    static String getAtomLink(Element entry) {
        NodeList links = entry.getElementsByTagNameNS("*", "link");
        for (int i = 0; i < links.getLength(); i++) {
            Element link = (Element) links.item(i);
            String rel = link.getAttribute("rel");
            if (rel.isEmpty() || "alternate".equals(rel)) {
                return link.getAttribute("href");
            }
        }
        return "";
    }

    static String getAtomAuthor(Element entry) {
        NodeList authors = entry.getElementsByTagNameNS("*", "author");
        if (authors.getLength() > 0) {
            Element author = (Element) authors.item(0);
            return getElementText(author, "name");
        }
        return "";
    }

    static String cleanHtml(String html) {
        if (html == null || html.isBlank()) return "";
        // Simple HTML tag removal
        String cleaned = html.replaceAll("<[^>]+>", "").trim();
        // Decode common HTML entities
        cleaned = cleaned.replace("&amp;", "&")
                         .replace("&lt;", "<")
                         .replace("&gt;", ">")
                         .replace("&quot;", "\"")
                         .replace("&#39;", "'")
                         .replace("&nbsp;", " ");
        // Collapse whitespace
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        // Limit length
        if (cleaned.length() > 500) {
            cleaned = cleaned.substring(0, 497) + "...";
        }
        return cleaned;
    }

    static String resolveUrl(String baseUrl, String href) {
        if (href.startsWith("http://") || href.startsWith("https://")) {
            return href;
        }
        try {
            URI base = URI.create(baseUrl);
            return base.resolve(href).toString();
        } catch (Exception e) {
            return baseUrl + (href.startsWith("/") ? href : "/" + href);
        }
    }

    static void reply(KernelEvent event, Map<?, ?> data, OutputStream out) throws Exception {
        PluginBase.publish(
                KernelEvent.withCorrelation("capability.result",
                        KernelEvent.MAPPER.writeValueAsString(data),
                        "rss-news-tool", event.correlationId(), event.sessionId()),
                out);
    }

    static void error(KernelEvent event, Exception e, OutputStream out) throws Exception {
        PluginBase.publish(
                KernelEvent.withCorrelation("capability.error",
                        KernelEvent.MAPPER.writeValueAsString(Map.of(
                                "error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                                "source", "rss-news-tool")),
                        "rss-news-tool", event.correlationId(), event.sessionId()),
                out);
    }
}