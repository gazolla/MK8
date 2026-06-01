# Plugin Configuration Schemas

Every plugin must contain a `plugin.json` file in its root directory. The mandatory fields for all plugin types are:
`id`, `type`, `version`, `description`, `lifecycle`, `subscribes`, `publishes`.

Optionally, configurations can contain `"commands"` and `"launch"` blocks.

---

---

## Type: `"system"`

System plugins contain no `llm`, `agent`, or `capabilities` blocks.

```json
{
  "id": "console",
  "type": "system",
  "version": "1.0.0",
  "description": "Terminal UI — reads user input, routes slash commands, displays responses.",

  "lifecycle": {
    "mode": "persistent"
  },

  "subscribes": [
    "chat.response",
    "chat.prompt",
    "chat.command.result"
  ],

  "publishes": [
    "chat.prompt",
    "chat.command.request"
  ]
}
```

**Lifecycle Modes for System Plugins**: `persistent` is the only valid mode. It does not support `idleTimeoutSeconds`.

---

## Type: `"tool"`

Tool plugins contain no `llm` or `agent` blocks. They declare capabilities in the `capabilities` block.

The lifecycle mode is always `on-demand` accompanied by `idleTimeoutSeconds`. **Tools are not started at boot**. Instead, `PluginManager` spawns them on-demand when `CapabilityInterceptor` broadcasts a `system.plugin.spawn` event on the UDS bus during the first invocation. A tool must subscribe directly to its own capability `triggerEvent`.

```json
{
  "id": "word-count",
  "type": "tool",
  "version": "1.0.0",
  "description": "Counts words, sentences and unique words in a text.",

  "lifecycle": {
    "mode": "on-demand",
    "idleTimeoutSeconds": 60
  },

  "launch": {
    "name": "WordCount",
    "command": ["jbang", "WordCountTool.java"],
    "order": 20
  },

  "capabilities": [
    {
      "name": "text.wordcount",
      "description": "Counts words in the given text.",
      "triggerEvent": "capability.tool.text.wordcount",
      "bidWeight": 1.0
    }
  ],

  "subscribes": [
    "capability.tool.text.wordcount"
  ]
}
```

**Tool Routing Rule**: `CapabilityInterceptor` receives a `capability.invoke { name: "text.wordcount" }` event, resolves the associated `triggerEvent`, and re-publishes it as `capability.tool.text.wordcount`. The target tool subscribes to this `triggerEvent` directly rather than to the generic `capability.invoke`.

---

## Type: `"agent"`

Agents contain `llm`, `agent`, and `capabilities` blocks. The lifecycle mode can be `persistent` or `on-demand`. The optional `thinking` block sits at the root level of the JSON alongside `llm` and `agent`.

```json
{
  "id": "assistant",
  "type": "agent",
  "version": "1.0.0",
  "description": "Persistent conversational assistant. Handles chat, delegates to specialists.",

  "lifecycle": {
    "mode": "persistent"
  },

  "llm": {
    "model": "google/gemini-3.5-flash",
    "baseUrl": "https://openrouter.ai/api/v1",
    "apiKeyEnv": "OPENROUTER_API_KEY",
    "maxTokens": 8192,
    "temperature": 0.3
  },

  "agent": {
    "maxRounds": 7,
    "maxDelegations": 3,
    "maxToolCalls": 10,
    "maxConcurrentMissions": 5,
    "negotiatingTimeoutSeconds": 120
  },

  "thinking": {
    "background": "⏳ Still working on it. I'll notify you here when ready! 🔔"
  },

  "capabilities": [
    {
      "name": "chat.respond",
      "description": "Respond to a user chat message.",
      "version": "1.0.0",
      "replyEvent": "chat.response",
      "inputSchema": {
        "required": ["message"],
        "properties": {
          "message": { "type": "string" }
        }
      },
      "outputSchema": {
        "properties": {
          "response": { "type": "string" }
        }
      },
      "exclusive": false,
      "bidWeight": 1.0,
      "tags": ["chat", "assistant"],
      "internal": true
    }
  ],

  "subscribes": [
    "chat.prompt",
    "message.assistant",
    "capability.result",
    "system.error",
    "plugin.installed"
  ],

  "publishes": [
    "chat.response",
    "chat.typing",
    "chat.thinking",
    "capability.register",
    "capability.invoke",
    "capability.result"
  ],

  "launch": {
    "name": "Assistant",
    "command": ["jbang", "Agent.java"],
    "order": 40,
    "delayAfterMs": 300
  }
}
```

---

## The `"thinking"` Block — Progressive Feedback (Optional, Agents Only)

Appears in user-facing agents (e.g., `assistant`). Signals that the agent is working by publishing a `chat.thinking` event. The `ConsolePlugin` listens for this event and displays the message to the user.

```json
"thinking": {
  "background": "⏳ Still working on it. I'll notify you here when ready! 🔔"
}
```

| Field | Type | Default | Description |
|---|---|---|---|
| `background` | string | `"⏳ Still working..."` | Message published via `chat.thinking` to inform the user the agent is processing |

---

## Agent Block Fields — Quick Reference

| Field | Type | Default | Description |
|---|---|---|---|
| `maxRounds` | integer | `5` | Maximum execution loops the LLM can run during a task |
| `maxDelegations` | integer | `3` | Maximum routing delegations allowed during a task |
| `maxToolCalls` | integer | `20` | Maximum tool execution calls allowed during a task |
| `maxConcurrentMissions` | integer | `1` | Maximum parallel task loops permitted for the agent |
| `negotiatingTimeoutSeconds` | integer | `120` | Maximum seconds allowed for capability negotiation |

---

## Capability Fields — Quick Reference

| Field | Type | Description |
|---|---|---|
| `name` | string | Unique capability identifier (e.g., `agent.research`) |
| `triggerEvent` | string | Trigger event name routing directly to the provider |
| `replyEvent` | string | Outcome response event type (always `capability.result`) |
| `inputSchema` | object | JSON Schema defining inputs (`required[]`, `optional[]`, `properties{}`) |
| `outputSchema` | object | JSON Schema defining output structures |
| `exclusive` | boolean | `true` restricts the capability to a single provider |
| `bidWeight` | number | Base bidding score factor (0.0 to 1.0) |
| `tags` | string[] | Meta tags for semantic search routing in `CapabilityInterceptor` |

---

## The `"launch"` Block — Dynamic Boot Configuration

Every active plugin (excluding the Kernel) must contain a `"launch"` configuration block inside its `plugin.json`. The launcher scanner parses this block to determine how and when to initialize each child process without hardcoded execution configurations.

```json
"launch": {
  "name":         "DateTime",
  "command":      ["jbang", "DateTimeTool.java"],
  "order":        30,
  "delayAfterMs": 0,
  "interactive":  false,
  "prebuild":     null
}
```

For agents sharing `Agent.java`:

```json
"launch": {
  "name":         "Researcher",
  "command":      ["jbang", "../../Agent.java", "."],
  "order":        40,
  "delayAfterMs": 300,
  "prebuild":     "../../Agent.java"
}
```

### Launch Block Fields

| Field | Type | Required | Description |
|---|---|---|---|
| `name` | string | Yes | Short identifier; determines log filename (`logs/Name.log`) and startup tags |
| `command` | string[] | Yes | Absolute or relative JBang launch execution array |
| `order` | integer | Yes | Boot orchestration tier level |
| `delayAfterMs` | integer | No (0) | Milliseconds of wait time allocated *after* initialization completes |
| `interactive` | boolean | No (false) | `true` maps to `inheritIO` stream processing; bypassed in `--dev` execution mode |
| `prebuild` | string | No (null) | Path to a source file pre-compiled prior to initializing the tier level |

### Boot Tier Order Convention (`launch.order`)

| order | Tier | Description / Guidelines |
|---|---|---|
| 10 | Logger | Active before publishing any event streams |
| 15 | PluginManager | Runtime orchestration and dynamic spawner controller |
| 20 | System | Critical infrastructure (`CapReg`, `Blackboard`, `WorkflowEngine`) + last item delay |
| 30 | Tools | Pure utility functions (`DateTime`, `FileSystem`, `Search`) + last item delay |
| 40 | Agents | Interactive LLM agents; staggered delays and shared prebuild files |
| 50 | Interactive | Standard Console UI and other interactive front-ends |

---

## Routing Type Mapping Conventions

| Type | `triggerEvent` | Delivery Flow |
|---|---|---|
| tool | `capability.tool.<name>` | `CapabilityInterceptor` intercepts generic invokes, re-publishes to exact triggerEvent type; tool receives direct UDS frames |
| agent | *(None)* | `CapabilityInterceptor` evaluates candidate bids, resolves, and forwards to target agent via `message.{agentId}` |

Agents **do not subscribe to `capability.invoke`**. They receive UDS messages strictly on `message.{self}` paths configured by the Kernel during initialization.
