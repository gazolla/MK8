# Multi-Capability Tool (JBang)

A single tool plugin registering and handling more than one capability.
The bid handler and the event dispatcher both route by capability name.

---

## Tool with three capabilities: get, set, delete

```java
///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../../../kernel/KernelEvent.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginBase.java

import com.fasterxml.jackson.databind.JsonNode;
import java.io.OutputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class StoreTool {

    // In-memory store — replace with real persistence as needed
    static final Map<String, String> STORE = new ConcurrentHashMap<>();

    // All capability names this tool handles
    static final Set<String> CAPABILITIES = Set.of(
            "tool.store.get",
            "tool.store.set",
            "tool.store.delete"
    );

    public static void main(String[] args) throws Exception {
        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, StoreTool::handle);
    }

    static void handle(String json, OutputStream out) throws Exception {
        KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);

        // Route by event type — each capability has its own triggerEvent
        switch (event.type()) {
            case "capability.tool.store.get"    -> handleGet(event, out);
            case "capability.tool.store.set"    -> handleSet(event, out);
            case "capability.tool.store.delete" -> handleDelete(event, out);
        }
    }

    static void handleGet(KernelEvent event, OutputStream out) throws Exception {
        try {
            JsonNode input = KernelEvent.MAPPER.readTree(event.payload()).path("input");
            String key   = input.path("key").asText();
            String value = STORE.getOrDefault(key, "");
            reply(event, Map.of("result", value), out);
        } catch (Exception e) { error(event, e, out); }
    }

    static void handleSet(KernelEvent event, OutputStream out) throws Exception {
        try {
            JsonNode input = KernelEvent.MAPPER.readTree(event.payload()).path("input");
            String key   = input.path("key").asText();
            String value = input.path("value").asText();
            STORE.put(key, value);
            reply(event, Map.of("result", "ok"), out);
        } catch (Exception e) { error(event, e, out); }
    }

    static void handleDelete(KernelEvent event, OutputStream out) throws Exception {
        try {
            JsonNode input = KernelEvent.MAPPER.readTree(event.payload()).path("input");
            String key = input.path("key").asText();
            STORE.remove(key);
            reply(event, Map.of("result", "deleted"), out);
        } catch (Exception e) { error(event, e, out); }
    }

    static void reply(KernelEvent event, Map<?, ?> data, OutputStream out) throws Exception {
        PluginBase.publish(
                KernelEvent.withCorrelation("capability.result",
                        KernelEvent.MAPPER.writeValueAsString(data),
                        "tool-store", event.correlationId(), event.sessionId()),
                out);
    }

    static void error(KernelEvent event, Exception e, OutputStream out) throws Exception {
        PluginBase.publish(
                KernelEvent.withCorrelation("capability.error",
                        KernelEvent.MAPPER.writeValueAsString(Map.of("reason", e.getMessage())),
                        "tool-store", event.correlationId(), event.sessionId()),
                out);
    }
}
```

---

## Corresponding plugin.json skeleton

```json
{
  "id": "tool-store",
  "type": "tool",
  "lifecycle": {
    "mode": "on-demand",
    "idleTimeoutSeconds": 300
  },
  "capabilities": [
    {
      "name": "tool.store.get",
      "triggerEvent": "capability.tool.store.get",
      "inputSchema": { "required": ["key"], "properties": { "key": { "type": "string" } } }
    },
    {
      "name": "tool.store.set",
      "triggerEvent": "capability.tool.store.set",
      "inputSchema": { "required": ["key","value"], "properties": { "key": { "type": "string" }, "value": { "type": "string" } } }
    },
    {
      "name": "tool.store.delete",
      "triggerEvent": "capability.tool.store.delete",
      "inputSchema": { "required": ["key"], "properties": { "key": { "type": "string" } } }
    }
  ],
  "subscribes": [
    "capability.tool.store.get",
    "capability.tool.store.set",
    "capability.tool.store.delete",
    "capability.bid.request"
  ]
}
```

---

## Key rules for multi-capability tools

- Each capability gets its own `triggerEvent` (e.g. `capability.tool.store.get`)
- The bid handler responds to ALL capabilities the tool handles — check `CAPABILITIES.contains(capName)`
- Use a `switch` on `event.type()` to route — cleaner than chained `if/else`
- Helper methods `reply()` and `error()` avoid repetition across handlers
