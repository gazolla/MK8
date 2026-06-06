# LLM APIs — NVIDIA NIM, Anthropic, OpenAI (JBang)

No extra //DEPS needed — java.net.http.HttpClient handles everything.
API keys come from environment variables — never hardcode them.

---

## NVIDIA NIM — OpenAI-compatible API (primary for MK8)

NVIDIA NIM uses the OpenAI chat completions format.
Base URL: `https://integrate.api.nvidia.com/v1`
Env var: `NVIDIA_API_KEY`

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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

static final HttpClient HTTP = HttpClient.newHttpClient();

static String chat(String model, String systemPrompt, String userMessage) throws Exception {
    String apiKey = System.getenv("NVIDIA_API_KEY");
    if (apiKey == null || apiKey.isBlank())
        throw new RuntimeException("NVIDIA_API_KEY environment variable not set");

    String body = KernelEvent.MAPPER.writeValueAsString(Map.of(
            "model",       model,   // e.g. "meta/llama-3.3-70b-instruct"
            "max_tokens",  1024,
            "temperature", 0.7,
            "messages", List.of(
                    Map.of("role", "system",  "content", systemPrompt),
                    Map.of("role", "user",    "content", userMessage)
            )
    ));

    HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://integrate.api.nvidia.com/v1/chat/completions"))
            .header("Authorization",  "Bearer " + apiKey)
            .header("Content-Type",   "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

    HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200)
        throw new RuntimeException("NVIDIA API HTTP " + response.statusCode() + ": " + response.body());

    JsonNode json = KernelEvent.MAPPER.readTree(response.body());
    return json.path("choices").get(0).path("message").path("content").asText();
}
```

### NVIDIA NIM — streaming response (SSE)

```java
static void chatStreaming(String model, String userMessage) throws Exception {
    String apiKey = System.getenv("NVIDIA_API_KEY");

    String body = KernelEvent.MAPPER.writeValueAsString(Map.of(
            "model",    model,
            "stream",   true,
            "messages", List.of(Map.of("role", "user", "content", userMessage))
    ));

    HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://integrate.api.nvidia.com/v1/chat/completions"))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type",  "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

    // ofLines() reads the SSE stream line by line
    HttpResponse<java.util.stream.Stream<String>> response =
            HTTP.send(request, HttpResponse.BodyHandlers.ofLines());

    StringBuilder sb = new StringBuilder();
    response.body().forEach(line -> {
        if (!line.startsWith("data: ") || line.equals("data: [DONE]")) return;
        try {
            JsonNode chunk = KernelEvent.MAPPER.readTree(line.substring(6)); // strip "data: "
            String delta = chunk.path("choices").get(0)
                               .path("delta").path("content").asText("");
            sb.append(delta);
            System.out.print(delta); // live output
        } catch (Exception ignored) {}
    });
    System.out.println(); // newline after stream ends
}
```

### Common NVIDIA NIM model IDs (as of 2025)

| Model | ID |
|---|---|
| Llama 3.3 70B | `meta/llama-3.3-70b-instruct` |
| Llama 3.1 405B | `meta/llama-3.1-405b-instruct` |
| Nemotron 70B | `nvidia/llama-3.1-nemotron-70b-instruct` |
| Mistral 7B | `mistralai/mistral-7b-instruct-v0.3` |
| Mixtral 8x22B | `mistralai/mixtral-8x22b-instruct-v0.1` |

---

## Anthropic Claude API

Base URL: `https://api.anthropic.com/v1/messages`
Env var: `ANTHROPIC_API_KEY`
Required headers: `x-api-key`, `anthropic-version: 2023-06-01`

```java
static String claudeChat(String model, String systemPrompt, String userMessage) throws Exception {
    String apiKey = System.getenv("ANTHROPIC_API_KEY");
    if (apiKey == null || apiKey.isBlank())
        throw new RuntimeException("ANTHROPIC_API_KEY environment variable not set");

    String body = KernelEvent.MAPPER.writeValueAsString(Map.of(
            "model",      model,    // e.g. "claude-sonnet-4-6"
            "max_tokens", 1024,
            "system",     systemPrompt,
            "messages",   List.of(Map.of("role", "user", "content", userMessage))
    ));

    HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.anthropic.com/v1/messages"))
            .header("x-api-key",          apiKey)
            .header("anthropic-version",  "2023-06-01")
            .header("Content-Type",       "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

    HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200)
        throw new RuntimeException("Anthropic API HTTP " + response.statusCode() + ": " + response.body());

    JsonNode json = KernelEvent.MAPPER.readTree(response.body());
    return json.path("content").get(0).path("text").asText();
}
```

---

## OpenAI API

Base URL: `https://api.openai.com/v1/chat/completions`
Env var: `OPENAI_API_KEY`

```java
static String openAiChat(String model, String userMessage) throws Exception {
    String apiKey = System.getenv("OPENAI_API_KEY");

    String body = KernelEvent.MAPPER.writeValueAsString(Map.of(
            "model",    model,   // e.g. "gpt-4o"
            "messages", List.of(Map.of("role", "user", "content", userMessage))
    ));

    HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.openai.com/v1/chat/completions"))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type",  "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

    HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200)
        throw new RuntimeException("OpenAI API HTTP " + response.statusCode() + ": " + response.body());

    JsonNode json = KernelEvent.MAPPER.readTree(response.body());
    return json.path("choices").get(0).path("message").path("content").asText();
}
```

---

## Tool calling / function calling (OpenAI format)

NVIDIA NIM also supports tool calling with the same format.

```java
// Define tools
List<Map<String, Object>> tools = List.of(Map.of(
        "type", "function",
        "function", Map.of(
                "name",        "get_weather",
                "description", "Get weather for a city",
                "parameters",  Map.of(
                        "type",       "object",
                        "required",   List.of("city"),
                        "properties", Map.of(
                                "city", Map.of("type", "string", "description", "City name")
                        )
                )
        )
));

String body = KernelEvent.MAPPER.writeValueAsString(Map.of(
        "model",    "meta/llama-3.3-70b-instruct",
        "messages", List.of(Map.of("role", "user", "content", "What's the weather in Paris?")),
        "tools",    tools
));

// ... send request ...

// Parse tool call from response
JsonNode choice = json.path("choices").get(0);
if ("tool_calls".equals(choice.path("finish_reason").asText())) {
    JsonNode toolCall = choice.path("message").path("tool_calls").get(0);
    String funcName = toolCall.path("function").path("name").asText();
    JsonNode args   = KernelEvent.MAPPER.readTree(toolCall.path("function").path("arguments").asText());
    String city     = args.path("city").asText();
    // call the actual function with city...
}
```

---

## Sentinel: detect empty or refused responses

```java
String content = json.path("choices").get(0).path("message").path("content").asText();
if (content == null || content.isBlank()) {
    throw new RuntimeException("LLM returned empty response — check model ID, API key, or rate limit");
}
```
