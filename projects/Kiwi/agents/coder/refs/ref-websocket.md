# WebSocket Client — java.net.http.WebSocket (JBang)

Built into Java 11+ — no //DEPS needed.
Use for real-time integrations: Telegram bot API, Slack, Binance, etc.

---

## Minimal WebSocket client

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
import java.net.http.WebSocket;
import java.util.Map;
import java.util.concurrent.*;

public class WsTool {

    static final HttpClient HTTP = HttpClient.newHttpClient();
    static volatile WebSocket ws;
    static volatile OutputStream kernelOut; // captured from handle()

    public static void main(String[] args) throws Exception {
        connectWebSocket();
        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, WsTool::handle);
    }

    static void connectWebSocket() throws Exception {
        ws = HTTP.newWebSocketBuilder()
                .header("Authorization", "Bearer " + System.getenv("WS_API_KEY"))
                .buildAsync(URI.create("wss://stream.example.com/ws"), new WebSocket.Listener() {

                    private final StringBuilder buffer = new StringBuilder();

                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        buffer.append(data);
                        if (last) {
                            onMessage(buffer.toString());
                            buffer.setLength(0);
                        }
                        ws.request(1); // request next message — MANDATORY
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                        System.out.println("[WS] closed: " + statusCode + " " + reason);
                        reconnect(); // reconnect on close
                        return null;
                    }

                    @Override
                    public void onError(WebSocket ws, Throwable error) {
                        System.err.println("[WS] error: " + error.getMessage());
                        reconnect();
                    }
                }).get(10, TimeUnit.SECONDS);

        System.out.println("[WS] Connected");
    }

    static void onMessage(String text) {
        try {
            JsonNode msg = KernelEvent.MAPPER.readTree(text);
            System.out.println("[WS] received: " + msg);

            // Forward to MK8 kernel as an event if stream is available
            if (kernelOut != null) {
                String payload = KernelEvent.MAPPER.writeValueAsString(Map.of("data", text));
                PluginBase.publish(KernelEvent.of("system.ws.message", payload, "ws-tool"), kernelOut);
            }
        } catch (Exception e) {
            System.err.println("[WS] parse error: " + e.getMessage());
        }
    }

    static void reconnect() {
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(3000);
                connectWebSocket();
            } catch (Exception e) {
                System.err.println("[WS] reconnect failed: " + e.getMessage());
            }
        });
    }

    static void handle(String json, OutputStream out) throws Exception {
        kernelOut = out;
        KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);

        if (!"capability.tool.ws.send".equals(event.type())) return;

        try {
            JsonNode input = KernelEvent.MAPPER.readTree(event.payload()).path("input");
            String message = input.path("message").asText();

            if (ws == null || ws.isOutputClosed())
                throw new RuntimeException("WebSocket not connected");

            ws.sendText(message, true).get(5, TimeUnit.SECONDS);

            String result = KernelEvent.MAPPER.writeValueAsString(Map.of("result", "sent"));
            PluginBase.publish(
                    KernelEvent.withCorrelation("capability.result", result, "ws-tool",
                            event.correlationId(), event.sessionId()), out);
        } catch (Exception e) {
            PluginBase.publish(
                    KernelEvent.withCorrelation("capability.error",
                            KernelEvent.MAPPER.writeValueAsString(Map.of("reason", e.getMessage())),
                            "ws-tool", event.correlationId(), event.sessionId()), out);
        }
    }
}
```

---

## Sending binary data

```java
byte[] data = ...; // binary payload
ws.sendBinary(java.nio.ByteBuffer.wrap(data), true)
  .get(5, TimeUnit.SECONDS);
```

---

## Receiving binary data in listener

```java
@Override
public CompletionStage<?> onBinary(WebSocket ws, java.nio.ByteBuffer data, boolean last) {
    byte[] bytes = new byte[data.remaining()];
    data.get(bytes);
    // process bytes...
    ws.request(1); // MANDATORY
    return null;
}
```

---

## Ping / keepalive

```java
// Send a ping — server should respond with a pong
ws.sendPing(java.nio.ByteBuffer.wrap("ping".getBytes()))
  .get(5, TimeUnit.SECONDS);

// Handle incoming pings (respond automatically by the JDK, but you can intercept)
@Override
public CompletionStage<?> onPing(WebSocket ws, java.nio.ByteBuffer message) {
    ws.request(1);
    return null; // JDK sends pong automatically
}
```

---

## Critical rules

- **`ws.request(1)` is mandatory** after every `onText`, `onBinary`, `onPing`, `onPong` call. Without it, no further messages are delivered.
- **Messages may arrive fragmented** (`last == false`). Always accumulate into a `StringBuilder` until `last == true`.
- **`sendText`/`sendBinary` are async** — call `.get()` or chain `.thenAccept()` to handle completion and errors.
- **WebSocket is not thread-safe** — do not send concurrently without synchronization.
