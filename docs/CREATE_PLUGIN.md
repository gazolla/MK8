# Creating a Custom Plugin

This guide walks you through building a new plugin for the MK8 MicroKernel. Every plugin in the system — `WordCountTool`, `SummaryAgent`, `ConsolePlugin`, `Agent` — follows the same conventions described here.

---

## What Is a Plugin?

A plugin is an independent OS process that connects to the Kernel over a Unix Domain Socket (UDS), declares what events it produces and consumes, and participates in the event bus. Plugins are completely decoupled from each other and from the Kernel internals — they only exchange `KernelEvent` frames over the socket.

The Kernel discovers plugins automatically by scanning `plugin.json` files under the project directory tree. No registration, no hardcoded lists, no annotations.

---

## The Two Files Every Plugin Needs

A plugin is always exactly two files, nothing more:

```
my-plugin/
├── MyPlugin.java    ← business logic
└── plugin.json      ← identity, subscriptions, capabilities, launch config
```

---

## Part 1 — `plugin.json`

### Required Fields

Every plugin must declare these fields:

```json
{
  "id":          "my-plugin",
  "type":        "tool",
  "version":     "1.0.0",
  "description": "What this plugin does.",

  "lifecycle": {
    "mode": "on-demand"
  },

  "launch": {
    "name":    "MyPlugin",
    "command": ["jbang", "MyPlugin.java"],
    "order":   30
  },

  "subscribes": ["event.i.receive"],
  "publishes":  ["event.i.emit"]
}
```

### Choosing the `type`

| type | When to use |
|---|---|
| `tool` | Executes a pure function and returns a result. No LLM. No long-lived state. |
| `agent` | Orchestrates, decides, and delegates. May use an LLM. Maintains state across requests. |
| `system` | UI, console, or infrastructure. Does not expose capabilities. Uses broadcast pub/sub only. |

### Choosing the `lifecycle.mode`

| mode | When to use | Behavior |
|---|---|---|
| `on-demand` | Tools that are idle most of the time. | Spawned on the first invocation. Killed after `idleTimeoutSeconds` of inactivity. |
| `persistent` | Agents, UIs, infrastructure. | Starts with the system. Never killed automatically. |

```json
"lifecycle": {
  "mode": "on-demand",
  "idleTimeoutSeconds": 120
}
```

`idleTimeoutSeconds` is only meaningful for `on-demand` plugins. The `PluginManager` checks every 60 seconds and terminates processes that have been idle longer than this threshold.

### Declaring Capabilities

Tools and agents declare one or more capabilities. Each capability is a named function that other plugins can invoke through the `CapabilityInterceptor`.

```json
"capabilities": [
  {
    "name":         "my.capability.name",
    "description":  "What it does.",
    "triggerEvent": "capability.tool.my.name",
    "bidWeight":    1.0,
    "exclusive":    false,
    "tags":         ["tag1", "tag2"]
  }
]
```

**`triggerEvent`** is the event type the `CapabilityInterceptor` publishes when a `capability.invoke` for this capability name is received. The plugin must subscribe to this exact event in the `subscribes` array.

**No `triggerEvent`** means the plugin is an agent. The Kernel delivers invocations via a direct `message.<id>` frame instead.

**`bidWeight`** is the base score used during multi-provider auctions. Higher weight = higher priority when multiple providers register the same capability.

**`exclusive: true`** restricts the capability to a single registered provider at a time.

**`tags`** are semantic labels used for tool discovery filtering.

### Capability Fields — Quick Reference

| Field | Type | Required | Description |
|---|---|---|---|
| `name` | string | Yes | Unique capability identifier, e.g. `text.wordcount` |
| `description` | string | No | Human-readable description |
| `triggerEvent` | string | Tools only | Event the plugin subscribes to for invocations |
| `replyEvent` | string | No | Expected response event (usually `capability.result`) |
| `bidWeight` | number | No (1.0) | Base bidding score for multi-provider auctions |
| `exclusive` | boolean | No (false) | Restrict to one provider at a time |
| `tags` | string[] | No | Semantic filter tags |
| `internal` | boolean | No (false) | Hide from default tool discovery |
| `inputSchema` | object | No | JSON Schema for input validation documentation |
| `outputSchema` | object | No | JSON Schema for output structure documentation |

### LLM Configuration (Agents Only)

```json
"llm": {
  "model":       "google/gemini-flash",
  "baseUrl":     "https://openrouter.ai/api/v1",
  "apiKeyEnv":   "OPENROUTER_API_KEY",
  "maxTokens":   4096,
  "temperature": 0.3
}
```

Never put the API key value in the file. Always use the name of an environment variable via `apiKeyEnv`. The plugin reads the actual key at runtime with `System.getenv(config.llmApiKeyEnv())`.

### Agent Configuration (Agents Only)

```json
"agent": {
  "maxRounds":             7,
  "maxDelegations":        3,
  "maxToolCalls":          10,
  "maxConcurrentMissions": 5,
  "negotiatingTimeoutSeconds": 120
}
```

### Thinking Feedback (Optional, Agents Only)

Emits a `chat.thinking` event while the agent is processing, so the UI can show a status message:

```json
"thinking": {
  "background": "⏳ Still working on it. I'll notify you when ready!"
}
```

### The `launch` Block

Tells `PluginManager` how to start this plugin as a child process:

```json
"launch": {
  "name":         "MyPlugin",
  "command":      ["jbang", "MyPlugin.java"],
  "order":        30,
  "delayAfterMs": 0,
  "interactive":  false
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `name` | string | Yes | Short label; used as the log filename (`logs/MyPlugin.log`) |
| `command` | string[] | Yes | Full JBang launch command |
| `order` | integer | Yes | Boot tier — lower numbers start first |
| `delayAfterMs` | integer | No (0) | Milliseconds to wait after this plugin starts |
| `interactive` | boolean | No (false) | `true` maps to `inheritIO` — use for terminal UIs |

**Boot tier convention:**

| order | Tier | Examples |
|---|---|---|
| 10 | Logger / infra | System loggers |
| 20 | System | Critical infrastructure plugins |
| 30 | Tools | Pure utility functions |
| 40 | Agents | LLM agents, orchestrators |
| 50 | Interactive | Console UI, terminal front-ends |

---

## Part 2 — The Java File

### The Mandatory Structure

Every plugin follows the same three-method skeleton:

```java
///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../../kernel/KernelEvent.java
//SOURCES ../../../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../../../kernel/interceptors/plugin/PluginBase.java

import java.io.OutputStream;

public class MyPlugin {

    static final String SOURCE_ID = "my-plugin"; // must match "id" in plugin.json

    public static void main(String[] args) throws Exception {
        KernelEvent.initLogging();
        new MyPlugin().start();
    }

    void start() throws Exception {
        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, this::handle);
    }

    void handle(String json, OutputStream out) throws Exception {
        KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);
        switch (event.type()) {
            case "my.trigger.event" -> handleTrigger(event, out);
        }
    }
}
```

The `//SOURCES` path depth depends on how many levels below `kernel/` your plugin lives. Adjust the `../` prefix accordingly.

`PluginBase.run()` handles everything repetitive: opens the UDS socket, sends `plugin.register` (with the current PID), sends `plugin.ready`, reads incoming frames, and dispatches each one to your `handle` method in a dedicated virtual thread. You only write `handle`.

### Declaring Event Name Constants

Never scatter string literals across the code. Declare all event type names as private constants at the top of the class:

```java
static final String EVT_TRIGGER = "capability.tool.my.name";
static final String EVT_RESULT  = "capability.result";
static final String EVT_ERROR   = "capability.error";
static final String SOURCE_ID   = "my-plugin";
```

### Publishing Events

**Simple event** (no correlation — pub/sub broadcast):
```java
PluginBase.publish(
    KernelEvent.of("my.output.event", payload, SOURCE_ID),
    out);
```

**Reply to a request** (with correlation — required for capability results):
```java
PluginBase.publish(
    KernelEvent.withCorrelation(
        "capability.result", payload,
        SOURCE_ID, event.correlationId(), event.sessionId()),
    out);
```

Always use `withCorrelation` when responding to a `capability.invoke`. The `correlationId` is how the Kernel knows which caller to deliver the result to.

**Publishing a capability error:**
```java
void publishError(KernelEvent origin, String reason, OutputStream out) {
    try {
        String payload = KernelEvent.MAPPER.writeValueAsString(Map.of("reason", reason));
        PluginBase.publish(
            KernelEvent.withCorrelation(
                "capability.error", payload,
                SOURCE_ID, origin.correlationId(), origin.sessionId()),
            out);
    } catch (Exception ignored) {}
}
```

### Never Block the Event Loop

`PluginBase` dispatches each incoming frame in a virtual thread. If your handler does anything slow — HTTP calls, LLM inference, database queries, `Thread.sleep` — launch it in a new virtual thread so the event loop stays responsive:

```java
void handle(String json, OutputStream out) throws Exception {
    KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);
    if ("my.trigger".equals(event.type())) {
        Thread.ofVirtual().start(() -> {
            try {
                String result = slowWork(event.payload());  // takes seconds
                PluginBase.publish(KernelEvent.withCorrelation(
                    "capability.result",
                    KernelEvent.MAPPER.writeValueAsString(Map.of("result", result)),
                    SOURCE_ID, event.correlationId(), event.sessionId()), out);
            } catch (Exception e) {
                publishError(event, e.getMessage(), out);
            }
        });
    }
}
```

### Run Actions Exactly Once (`plugin.ready`)

`plugin.ready` can arrive multiple times — once per plugin that connects. Use `AtomicBoolean` to ensure initialization runs only on the first signal:

```java
final AtomicBoolean started = new AtomicBoolean(false);

// inside handle():
case "plugin.ready" -> {
    if (started.compareAndSet(false, true))
        Thread.ofVirtual().start(() -> initialize(out));
}
```

### Reading Configuration from `PluginConfig`

`PluginConfig.load("plugin.json")` gives you typed accessors for all fields:

```java
PluginConfig config = PluginConfig.load("plugin.json");

config.id();               // "my-plugin"
config.llmModel();         // "google/gemini-flash"
config.llmBaseUrl();       // "https://openrouter.ai/api/v1"
config.llmApiKeyEnv();     // "OPENROUTER_API_KEY"
config.llmMaxTokens();     // 4096
config.llmTemperature();   // 0.3
config.thinkingBackground(); // "⏳ Still working..."
config.capabilities();     // List of JsonNode capability entries
config.raw();              // the full JsonNode tree
```

---

## Part 3 — The Three Templates

### Template A — Tool (on-demand)

A pure function: receives a trigger event, does work, returns a result.

**`WordCountTool.java` structure:**

```java
public class MyTool {

    static final String EVT_TRIGGER = "capability.tool.my.op";
    static final String SOURCE_ID   = "my-tool";

    public static void main(String[] args) throws Exception {
        KernelEvent.initLogging();
        new MyTool().start();
    }

    void start() throws Exception {
        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, this::handle);
    }

    void handle(String json, OutputStream out) throws Exception {
        KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);
        if (!EVT_TRIGGER.equals(event.type())) return;

        JsonNode payload = KernelEvent.MAPPER.readTree(event.payload());
        String   input   = payload.path("input").asText("").trim();

        if (input.isBlank()) {
            publishError(event, "input field is required", out);
            return;
        }

        // synchronous work — tools are stateless and fast
        String result = process(input);

        PluginBase.publish(KernelEvent.withCorrelation(
            "capability.result",
            KernelEvent.MAPPER.writeValueAsString(Map.of("result", result)),
            SOURCE_ID, event.correlationId(), event.sessionId()), out);
    }

    String process(String input) {
        return input.toUpperCase(); // replace with real logic
    }

    void publishError(KernelEvent origin, String reason, OutputStream out) {
        try {
            PluginBase.publish(KernelEvent.withCorrelation(
                "capability.error",
                KernelEvent.MAPPER.writeValueAsString(Map.of("reason", reason)),
                SOURCE_ID, origin.correlationId(), origin.sessionId()), out);
        } catch (Exception ignored) {}
    }
}
```

**`plugin.json`:**

```json
{
  "id": "my-tool",
  "type": "tool",
  "version": "1.0.0",
  "description": "Does my operation.",
  "lifecycle": { "mode": "on-demand", "idleTimeoutSeconds": 120 },
  "launch": {
    "name": "MyTool",
    "command": ["jbang", "MyTool.java"],
    "order": 30
  },
  "capabilities": [
    {
      "name": "my.op",
      "description": "Performs the operation.",
      "triggerEvent": "capability.tool.my.op",
      "bidWeight": 1.0
    }
  ],
  "subscribes": ["capability.tool.my.op"]
}
```

---

### Template B — Agent (orchestrator)

Receives requests, delegates to other capabilities, and assembles the final reply.

**`SummaryAgent.java` structure:**

```java
public class MyAgent {

    static final String EVT_REQUEST = "message.my-agent";    // direct delivery from CapabilityInterceptor
    static final String EVT_RESULT  = "capability.result";
    static final String EVT_ERROR   = "capability.error";
    static final String SOURCE_ID   = "my-agent";

    // maps inner correlationId → outer request context
    final ConcurrentHashMap<String, PendingRequest> pending = new ConcurrentHashMap<>();

    record PendingRequest(String outerCorrId, String sessionId) {}

    public static void main(String[] args) throws Exception {
        KernelEvent.initLogging();
        new MyAgent().start();
    }

    void start() throws Exception {
        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, this::handle);
    }

    void handle(String json, OutputStream out) throws Exception {
        KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);
        switch (event.type()) {
            case EVT_REQUEST -> handleRequest(event, out);
            case EVT_RESULT  -> handleResult(event, out);
            case EVT_ERROR   -> handleError(event, out);
        }
    }

    void handleRequest(KernelEvent event, OutputStream out) throws Exception {
        // Generate a new correlationId for the downstream tool call.
        // Store the outer context so we can reply to the original caller
        // once the tool result arrives.
        String innerCorrId = UUID.randomUUID().toString();
        pending.put(innerCorrId,
            new PendingRequest(event.correlationId(), event.sessionId()));

        String invokePayload = KernelEvent.MAPPER.writeValueAsString(Map.of(
            "name",  "other.capability",
            "input", event.payload()));

        PluginBase.publish(KernelEvent.withCorrelation(
            "capability.invoke", invokePayload,
            SOURCE_ID, innerCorrId, event.sessionId()), out);
    }

    void handleResult(KernelEvent event, OutputStream out) throws Exception {
        PendingRequest ctx = pending.remove(event.correlationId());
        if (ctx == null) return; // result belongs to another agent

        // build final response and reply to the original caller
        String summary = buildSummary(event.payload());

        PluginBase.publish(KernelEvent.withCorrelation(
            "capability.result",
            KernelEvent.MAPPER.writeValueAsString(Map.of("result", summary)),
            SOURCE_ID, ctx.outerCorrId(), ctx.sessionId()), out);
    }

    void handleError(KernelEvent event, OutputStream out) throws Exception {
        PendingRequest ctx = pending.remove(event.correlationId());
        if (ctx == null) return;

        PluginBase.publish(KernelEvent.withCorrelation(
            "capability.error", event.payload(),
            SOURCE_ID, ctx.outerCorrId(), ctx.sessionId()), out);
    }

    String buildSummary(String payload) { return payload; } // replace with real logic
}
```

**`plugin.json`:**

```json
{
  "id": "my-agent",
  "type": "agent",
  "version": "1.0.0",
  "description": "Orchestrates requests and delegates to tools.",
  "lifecycle": { "mode": "persistent" },
  "launch": {
    "name": "MyAgent",
    "command": ["jbang", "MyAgent.java"],
    "order": 40
  },
  "capabilities": [
    {
      "name": "my.agent.op",
      "description": "Executes the main orchestration flow.",
      "bidWeight": 1.0
    }
  ],
  "subscribes": ["message.my-agent", "capability.result", "capability.error"]
}
```

> Agents have **no `triggerEvent`** in their capability. The `CapabilityInterceptor` delivers invocations via `message.<id>` directly. The plugin subscribes to `message.my-agent` to receive them.

---

### Template C — System Plugin (pure pub/sub)

No capabilities. Communicates via broadcast events only.

**`MySystem.java` structure:**

```java
public class MySystem {

    static final String EVT_INPUT  = "some.input.event";
    static final String EVT_OUTPUT = "some.output.event";
    static final String SOURCE_ID  = "my-system";

    final AtomicBoolean started = new AtomicBoolean(false);

    public static void main(String[] args) throws Exception {
        KernelEvent.initLogging();
        new MySystem().start();
    }

    void start() throws Exception {
        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, this::handle);
    }

    void handle(String json, OutputStream out) throws Exception {
        KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);
        switch (event.type()) {
            case "plugin.ready" -> {
                if (started.compareAndSet(false, true))
                    Thread.ofVirtual().start(() -> initialize(out));
            }
            case EVT_INPUT -> handleInput(event, out);
        }
    }

    void initialize(OutputStream out) { /* one-time startup logic */ }

    void handleInput(KernelEvent event, OutputStream out) throws Exception {
        PluginBase.publish(
            KernelEvent.of(EVT_OUTPUT, event.payload(), SOURCE_ID), out);
    }
}
```

**`plugin.json`:**

```json
{
  "id": "my-system",
  "type": "system",
  "version": "1.0.0",
  "description": "Infrastructure or UI plugin.",
  "lifecycle": { "mode": "persistent" },
  "launch": {
    "name": "MySystem",
    "command": ["jbang", "MySystem.java"],
    "order": 20
  },
  "subscribes": ["plugin.ready", "some.input.event"],
  "publishes":  ["some.output.event"]
}
```

---

## Part 4 — How the Routing Works

Understanding routing helps you write the right `subscribes` and `triggerEvent`.

### Tool invocation flow

```
Caller publishes:
  capability.invoke { name: "my.op", ... }

CapabilityInterceptor:
  looks up "my.op" in registry
  re-publishes as "capability.tool.my.op"   ← triggerEvent

MyTool receives:
  "capability.tool.my.op"
  processes it
  publishes capability.result { correlationId: same }

CapabilityInterceptor:
  reads pendingRoutes[correlationId]
  delivers result directly to original caller
```

### Agent invocation flow

```
Caller publishes:
  capability.invoke { name: "my.agent.op", ... }

CapabilityInterceptor:
  no triggerEvent → delivers as "message.my-agent"

MyAgent receives:
  "message.my-agent"
  creates innerCorrId
  publishes capability.invoke { name: "other.cap", corrId: innerCorrId }

OtherTool receives, processes, publishes capability.result { corrId: innerCorrId }

MyAgent receives:
  capability.result { corrId: innerCorrId }
  looks up pending[innerCorrId] → outer context
  publishes capability.result { corrId: outerCorrId }

CapabilityInterceptor delivers to original caller
```

### Broadcast pub/sub flow (system plugins)

```
PluginA publishes "some.event"

Kernel broadcasts to all subscribers of "some.event"

PluginB (subscribed) receives it
```

No `correlationId` needed. No request-reply. Events flow one-way to all registered listeners.

---

## Part 5 — The `//SOURCES` Path

The `//SOURCES` directives in your JBang header must point to the three shared kernel files. Adjust the relative path based on your plugin's location in the project tree:

| Plugin location | `//SOURCES` prefix |
|---|---|
| `projects/MyProject/my-plugin/` | `../../../kernel/...` |
| `projects/MyProject/` | `../../kernel/...` |
| At project root | `kernel/...` |

The three required sources are always:

```java
//SOURCES <prefix>/KernelEvent.java
//SOURCES <prefix>/interceptors/plugin/PluginConfig.java
//SOURCES <prefix>/interceptors/plugin/PluginBase.java
```

---

## Checklist

Before shipping your plugin, verify:

- [ ] `plugin.json` has `id`, `type`, `version`, `description`, `lifecycle`, `launch`, `subscribes`
- [ ] `SOURCE_ID` in the Java file matches `"id"` in `plugin.json`
- [ ] `subscribes` in `plugin.json` includes every event type handled in the `switch`
- [ ] Tool: `capabilities[].triggerEvent` is listed in `subscribes`
- [ ] Agent without `triggerEvent`: `subscribes` includes `"message.<id>"`
- [ ] Long-running work is wrapped in `Thread.ofVirtual().start(...)`
- [ ] Capability replies use `KernelEvent.withCorrelation(...)` with the original `correlationId`
- [ ] Capability errors publish `"capability.error"` with `withCorrelation`
- [ ] One-time initialization uses `AtomicBoolean.compareAndSet(false, true)`
- [ ] LLM API key is read from an environment variable, not hardcoded
- [ ] `//SOURCES` paths point to the correct `kernel/` location
- [ ] `launch.command` in `plugin.json` matches the `.java` filename
- [ ] `launch.order` follows the boot tier convention (tools=30, agents=40, UI=50)
