# A2A Protocol — Agent-to-Agent (JBang)

A2A (Agent-to-Agent) is Google's open protocol for agent interoperability.
Agents expose an **Agent Card** and communicate via JSON-RPC 2.0 over HTTP.
No extra //DEPS needed — uses java.net.http.HttpClient + java.httpserver.

Spec: https://google.github.io/A2A/

---

## A2A Agent Server (exposes capabilities to other agents)

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
import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;

public class A2AAgentServer {

    static final int PORT    = 8090;
    static final String NAME = "mk7-agent";
    static final String URL  = "http://localhost:" + PORT;

    public static void main(String[] args) throws Exception {
        startA2AServer();
        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, A2AAgentServer::handle);
    }

    static void startA2AServer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        // Agent Card — discovered by other agents
        server.createContext("/.well-known/agent.json", ex -> {
            String card = KernelEvent.MAPPER.writeValueAsString(Map.of(
                    "name",        NAME,
                    "description", "MK8 agent exposing capabilities via A2A",
                    "url",         URL,
                    "version",     "1.0.0",
                    "capabilities", Map.of(
                            "streaming",              false,
                            "pushNotifications",      false,
                            "stateTransitionHistory", false
                    ),
                    "skills", List.of(Map.of(
                            "id",          "summarize",
                            "name",        "Summarize text",
                            "description", "Returns a summary of the provided text",
                            "inputModes",  List.of("text"),
                            "outputModes", List.of("text")
                    ))
            ));
            sendJson(ex, 200, card);
        });

        // Task endpoint — receives A2A tasks from other agents
        server.createContext("/", ex -> {
            if (!"POST".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String response = handleA2ARequest(body);
            sendJson(ex, 200, response);
        });

        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("[A2A] Server running at " + URL);
    }

    static String handleA2ARequest(String body) throws Exception {
        JsonNode rpc = KernelEvent.MAPPER.readTree(body);
        String method = rpc.path("method").asText();
        Object rpcId  = rpc.path("id").isNumber()
                ? rpc.path("id").asLong()
                : rpc.path("id").asText();

        return switch (method) {
            case "tasks/send" -> {
                JsonNode params  = rpc.path("params");
                String taskId    = params.path("id").asText();
                String userText  = params.path("message").path("parts").get(0).path("text").asText();

                // Process the task — replace with real logic
                String answer = "Summary of: " + userText.substring(0, Math.min(50, userText.length())) + "...";

                yield KernelEvent.MAPPER.writeValueAsString(Map.of(
                        "jsonrpc", "2.0",
                        "id",      rpcId,
                        "result",  Map.of(
                                "id",     taskId,
                                "status", Map.of("state", "completed"),
                                "artifacts", List.of(Map.of(
                                        "parts", List.of(Map.of("type", "text", "text", answer))
                                ))
                        )
                ));
            }
            case "tasks/get" -> {
                // Return task status
                yield KernelEvent.MAPPER.writeValueAsString(Map.of(
                        "jsonrpc", "2.0",
                        "id",      rpcId,
                        "result",  Map.of("id", rpc.path("params").path("id").asText(),
                                          "status", Map.of("state", "completed"))
                ));
            }
            default -> KernelEvent.MAPPER.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "id",      rpcId,
                    "error",   Map.of("code", -32601, "message", "Method not found: " + method)
            ));
        };
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

## A2A Client (calls another agent's capability)

```java
import java.net.URI;
import java.net.http.*;
import java.util.*;

static final HttpClient HTTP = HttpClient.newHttpClient();

// Step 1 — Discover agent card
static JsonNode discoverAgent(String agentBaseUrl) throws Exception {
    HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(agentBaseUrl + "/.well-known/agent.json"))
            .GET().build();
    HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    return KernelEvent.MAPPER.readTree(resp.body());
}

// Step 2 — Send a task
static JsonNode sendTask(String agentUrl, String taskId, String userMessage) throws Exception {
    String rpcBody = KernelEvent.MAPPER.writeValueAsString(Map.of(
            "jsonrpc", "2.0",
            "id",      1,
            "method",  "tasks/send",
            "params",  Map.of(
                    "id",      taskId,
                    "message", Map.of(
                            "role",  "user",
                            "parts", List.of(Map.of("type", "text", "text", userMessage))
                    )
            )
    ));

    HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(agentUrl))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(rpcBody))
            .build();

    HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    return KernelEvent.MAPPER.readTree(resp.body()).path("result");
}
```

---

## A2A Task lifecycle states

| State | Meaning |
|---|---|
| `submitted` | Task received, not yet started |
| `working` | Agent is processing |
| `input-required` | Agent needs more info from caller |
| `completed` | Done, artifacts available |
| `failed` | Terminal error |
| `canceled` | Canceled by caller |

---

## Key A2A concepts

- **Agent Card** at `/.well-known/agent.json` — mandatory for discovery
- **Task** — unit of work, has `id`, `message`, `status`, `artifacts`
- **Artifact** — output of a task (text, file, data)
- **Skill** — declared capability in the Agent Card (what the agent can do)
- All communication is JSON-RPC 2.0 over HTTP POST
