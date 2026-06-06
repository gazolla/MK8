# Web UI — java.httpserver + HTML/CSS/JS (JBang)

`com.sun.net.httpserver.HttpServer` is built into the JDK — no //DEPS needed.
Use this for system plugins that expose a web dashboard or REST endpoint.

---

## Minimal HTTP server serving HTML

```java
///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../../../kernel/KernelEvent.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginBase.java

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;

public class DashboardPlugin {

    static final int PORT = 8080;

    public static void main(String[] args) throws Exception {
        startWebServer();
        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, DashboardPlugin::handle);
    }

    static void startWebServer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        server.createContext("/", exchange -> {
            String html = """
                    <!DOCTYPE html>
                    <html>
                    <head><meta charset="utf-8"><title>MK8 Dashboard</title>
                    <style>body{font-family:sans-serif;padding:2rem;}</style>
                    </head>
                    <body><h1>MK8 Dashboard</h1><div id="data">Loading...</div>
                    <script>
                      fetch('/api/status').then(r=>r.json()).then(d=>document.getElementById('data').textContent=JSON.stringify(d));
                    </script>
                    </body></html>
                    """;
            sendHtml(exchange, 200, html);
        });

        server.createContext("/api/status", exchange -> {
            String json = KernelEvent.MAPPER.writeValueAsString(Map.of("status", "running", "port", PORT));
            sendJson(exchange, 200, json);
        });

        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("[WEB] Listening on http://localhost:" + PORT);
    }

    static void sendHtml(HttpExchange ex, int status, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    static void sendJson(HttpExchange ex, int status, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    static void handle(String json, OutputStream out) throws Exception {
        KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);
        // handle MK8 events normally
    }
}
```

---

## Reading POST body from HttpExchange

```java
server.createContext("/api/data", exchange -> {
    if ("POST".equals(exchange.getRequestMethod())) {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode input = KernelEvent.MAPPER.readTree(body);
        // process input...
        sendJson(exchange, 200, KernelEvent.MAPPER.writeValueAsString(Map.of("ok", true)));
    } else {
        exchange.sendResponseHeaders(405, -1);
    }
});
```

---

## Serving static files from classpath / inline

For small UIs embed HTML as a Java text block (""" ... """).
For larger UIs, read from a file alongside the .java:

```java
String html = java.nio.file.Files.readString(
        java.nio.file.Path.of("index.html"));
```
