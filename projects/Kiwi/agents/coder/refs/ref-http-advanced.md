# HTTP Advanced — POST, Bearer, API Key (JBang)

No extra //DEPS needed — java.net.http.HttpClient covers all cases.

---

## POST with JSON body

```java
///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../../../kernel/KernelEvent.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginBase.java

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

static final HttpClient HTTP = HttpClient.newHttpClient();

// Build JSON body string from a Map
String body = KernelEvent.MAPPER.writeValueAsString(Map.of(
        "model",  "gpt-4",
        "prompt", userInput
));

HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("https://api.example.com/v1/generate"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();

HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
if (response.statusCode() != 200) {
    throw new RuntimeException("API returned HTTP " + response.statusCode() + ": " + response.body());
}
JsonNode data = KernelEvent.MAPPER.readTree(response.body());
```

---

## Bearer token authentication

```java
HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("https://api.example.com/protected"))
        .header("Authorization", "Bearer " + apiKey)
        .header("Content-Type", "application/json")
        .GET()
        .build();
```

---

## API key in header (X-API-Key)

```java
HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("https://api.example.com/data"))
        .header("X-API-Key", apiKey)
        .GET()
        .build();
```

---

## API key in query parameter

```java
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
String url = "https://api.example.com/search?api_key=" + apiKey + "&q=" + encoded;

HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .GET()
        .build();
```

---

## Reading API key from environment variable

```java
// API keys come from environment — never hardcode them
String apiKey = System.getenv("MY_API_KEY");
if (apiKey == null || apiKey.isBlank()) {
    throw new RuntimeException("MY_API_KEY environment variable not set");
}
```

---

## POST with form-encoded body (application/x-www-form-urlencoded)

```java
String formBody = "grant_type=client_credentials"
        + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
        + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);

HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("https://auth.example.com/token"))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(formBody))
        .build();
```

---

## Sentinel pattern — detect missing numeric data

```java
double value = data.path("price").asDouble(-1);
if (value < 0) {
    throw new RuntimeException("API returned no data — check credentials or rate limits");
}
```
