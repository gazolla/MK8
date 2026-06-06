# MCP Client — Model Context Protocol (JBang)

MCP (Model Context Protocol) connects LLM agents to external tools and resources.
Two transports: **stdio** (subprocess) and **HTTP+SSE** (remote server).
Protocol: JSON-RPC 2.0.

---

## Option A — Raw HTTP+SSE client (no extra //DEPS)

```java
///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../../../kernel/KernelEvent.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginBase.java

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class McpClientTool {

    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final AtomicLong ID   = new AtomicLong(1);
    static final String MCP_BASE = "http://localhost:3000"; // MCP server base URL

    public static void main(String[] args) throws Exception {
        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, McpClientTool::handle);
    }

    static void handle(String json, OutputStream out) throws Exception {
        KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);
        if (!"capability.tool.mcp.call".equals(event.type())) return;

        try {
            JsonNode input = KernelEvent.MAPPER.readTree(event.payload()).path("input");
            String toolName = input.path("tool").asText();
            JsonNode toolArgs = input.path("args");

            // JSON-RPC 2.0 request
            String rpcBody = KernelEvent.MAPPER.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "id",      ID.getAndIncrement(),
                    "method",  "tools/call",
                    "params",  Map.of("name", toolName, "arguments", toolArgs)
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(MCP_BASE + "/message"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(rpcBody))
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("MCP server returned HTTP " + response.statusCode());
            }

            JsonNode rpcResponse = KernelEvent.MAPPER.readTree(response.body());
            JsonNode result = rpcResponse.path("result");

            PluginBase.publish(
                    KernelEvent.withCorrelation("capability.result",
                            KernelEvent.MAPPER.writeValueAsString(Map.of("result", result)),
                            "mcp-client", event.correlationId(), event.sessionId()),
                    out);

        } catch (Exception e) {
            PluginBase.publish(
                    KernelEvent.withCorrelation("capability.error",
                            KernelEvent.MAPPER.writeValueAsString(Map.of("reason", e.getMessage())),
                            "mcp-client", event.correlationId(), event.sessionId()),
                    out);
        }
    }

    // List available tools from MCP server
    static JsonNode listTools() throws Exception {
        String rpcBody = KernelEvent.MAPPER.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id",      ID.getAndIncrement(),
                "method",  "tools/list",
                "params",  Map.of()
        ));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(MCP_BASE + "/message"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(rpcBody))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        return KernelEvent.MAPPER.readTree(resp.body()).path("result").path("tools");
    }
}
```

---

## Option B — Official MCP Java SDK

```java
//DEPS io.modelcontextprotocol.sdk:mcp:0.9.0
//DEPS io.projectreactor:reactor-core:3.6.0

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema.*;

var transport = new HttpClientSseClientTransport("http://localhost:3000");
var client = McpClient.sync(transport).build();

client.initialize();

// List tools
ListToolsResult tools = client.listTools();
tools.tools().forEach(t -> System.out.println(t.name() + ": " + t.description()));

// Call a tool
CallToolResult result = client.callTool(new CallToolRequest("search", Map.of("query", "Java JBang")));
result.content().forEach(c -> System.out.println(c));

client.closeGracefully();
```

---

## Option C — stdio transport (subprocess MCP server)

```java
// Start an MCP server as a child process and communicate via stdin/stdout
ProcessBuilder pb = new ProcessBuilder(
        "npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp/workspace");
pb.redirectErrorStream(false);
Process proc = pb.start();

var writer = new java.io.PrintWriter(proc.getOutputStream(), true);
var reader = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream()));

// Send JSON-RPC initialize
writer.println("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"mk7\",\"version\":\"1.0\"}}}");

String response = reader.readLine();
System.out.println("MCP init response: " + response);
```

---

## MCP JSON-RPC message structure

```json
// Request
{ "jsonrpc": "2.0", "id": 1, "method": "tools/call",
  "params": { "name": "read_file", "arguments": { "path": "/tmp/file.txt" } } }

// Response
{ "jsonrpc": "2.0", "id": 1,
  "result": { "content": [{ "type": "text", "text": "file contents..." }] } }

// Error response
{ "jsonrpc": "2.0", "id": 1,
  "error": { "code": -32601, "message": "Method not found" } }
```
