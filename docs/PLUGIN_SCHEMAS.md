# Plugin Configuration Schemas

Every plugin must contain a `plugin.json` file in its root directory. The mandatory fields for all plugin types are:
`id`, `type`, `version`, `description`, `lifecycle`, `subscribes`, `publishes`.

Optionally, configurations can contain `"commands"` and `"launch"` blocks.

---

## The `"commands"` Block — Dynamic Command Registration

System or tool plugins can declare custom slash commands. The `SupervisorPlugin` collects these declarations dynamically during boot and exposes them in an integrated format via `/help` and `/list` commands.

```json
"commands": [
  {
    "name": "/plugins",
    "description": "Display a table of all active plugins, process PIDs, and their statuses."
  },
  {
    "name": "/restart <pluginId>",
    "description": "Safely restart a specific system plugin."
  }
]
```

### Command Fields inside the `"commands"` Block

| Field | Type | Required | Description |
|---|---|---|---|
| `name` | string | Yes | The complete slash command starting with `/` (e.g., `/plugins` or `/trace <sessionId>`) |
| `description` | string | Yes | A short description detailing functionality and usage |

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

Tool plugins contain no `llm` or `agent` blocks. They contain a `capabilities` block defining `inputSchema` and `outputSchema`.

The lifecycle mode is always `on-demand` accompanied by `idleTimeoutSeconds`. **Tools are not started at boot**. Instead, `PluginManager` spawns them on-demand when `CapabilityInterceptor` broadcasts a `system.plugin.spawn` event on the UDS bus during the first invocation. A tool must subscribe directly to its own capability `triggerEvent`.

```json
{
  "id": "filesystem-tool",
  "type": "tool",
  "version": "1.0.0",
  "description": "Sandboxed file system operations inside project root.",

  "lifecycle": {
    "mode": "on-demand",
    "idleTimeoutSeconds": 300
  },

  "capabilities": [
    {
      "name": "tool.filesystem.op",
      "description": "Execute a sandboxed file operation.",
      "version": "1.0.0",
      "triggerEvent": "capability.tool.filesystem.op",
      "replyEvent": "capability.result",
      "inputSchema": {
        "required": ["op", "path"],
        "optional": ["content", "target"],
        "properties": {
          "op":      { "type": "string", "enum": ["read","write","ls","exists","delete"] },
          "path":    { "type": "string" },
          "content": { "type": "string" },
          "target":  { "type": "string" }
        }
      },
      "outputSchema": {
        "type": "string",
        "description": "Operation result as plain text"
      },
      "exclusive": false,
      "bidWeight": 1.0,
      "tags": ["filesystem", "files", "io"]
    }
  ],

  "subscribes": [
    "capability.tool.filesystem.op"
  ],

  "publishes": [
    "capability.result"
  ]
}
```

**Tool Routing Rule**: `CapabilityInterceptor` receives a `capability.invoke { name: "tool.filesystem.op" }` event, resolves the associated `triggerEvent`, and re-publishes it as `capability.tool.filesystem.op`. The target tool subscribes to this `triggerEvent` directly rather than to the generic `capability.invoke`.

### Implemented Operations: `tool.filesystem.op`

| op | Mandatory Parameters | Return |
|---|---|---|
| `read` | `path` | File content as a string |
| `write` | `path`, `content` | `"written N chars to path"` |
| `ls` | `path` (directory) | JSON array containing file names |
| `exists` | `path` | `"true"` or `"false"` |
| `delete` | `path` (file) | `"deleted path"` — fails if it does not exist or is a directory |

### Path Resolution Rules (Sandbox and Global Read)

The `FileSystemTool` operates under two distinct security scopes:
1. **Write and Delete Operations (`write`, `delete`, `append`)**: Rigidly restricted to the `workspace/` subdirectory for isolation and security. Any provided path is resolved relative to the `workspace/` folder.
2. **Read and List Operations (`read`, `ls`, `exists`)**: Permitted global read access across the physical repository root (`PROJECT_ROOT`). If the path is `.` or `/`, it resolves to the repository root.

---

### Example Tool with `internal: true` — BrowserTool

```json
{
  "id": "tool-browser",
  "type": "tool",
  "version": "1.0.0",
  "description": "HTTP fetch and binary download. fetch returns page text; download saves bytes to workspace/.",
  "lifecycle": { "mode": "on-demand", "idleTimeoutSeconds": 300 },

  "capabilities": [
    {
      "name": "tool.browser.fetch",
      "description": "Fetch text content from a URL (op:fetch) or download binary files to workspace/ (op:download).",
      "version": "1.0.0",
      "triggerEvent": "capability.tool.browser.fetch",
      "replyEvent": "capability.result",
      "internal": true,
      "inputSchema": {
        "required": ["op", "url"],
        "properties": {
          "op":       { "type": "string", "enum": ["fetch", "download"] },
          "url":      { "type": "string" },
          "dest":     { "type": "string", "description": "Filename in workspace/ for download (optional)" },
          "maxChars": { "type": "integer", "description": "Max characters for fetch (default 4000)" }
        }
      }
    }
  ]
}
```

**`internal: true`**: The capability only appears in the available list for agents configured with `seeInternalTools: true` in their `plugin.json`. The generic `assistant` cannot see `tool.browser.fetch`; specialized agents such as `researcher` and `writer` can access it.

---

## Type: `"agent"`

Agents contain `llm`, `agent`, and `capabilities` blocks. The lifecycle mode can be `persistent` or `on-demand`. The optional `thinking` block sits at the root level of the JSON alongside `llm` and `agent`.

```json
{
  "id": "researcher",
  "type": "agent",
  "version": "1.0.0",
  "description": "Deep research and synthesis specialist.",

  "lifecycle": {
    "mode": "on-demand",
    "idleTimeoutSeconds": 600
  },

  "llm": {
    "model": "meta/llama-3.3-70b-instruct",
    "baseUrl": "https://integrate.api.nvidia.com/v1",
    "apiKeyEnv": "NVIDIA_API_KEY",
    "maxTokens": 4096,
    "temperature": 0.2
  },

  "agent": {
    "skillsDir": "skills/researcher",
    "maxRounds": 6,
    "maxDelegations": 2,
    "maxToolCalls": 20,
    "maxConcurrentMissions": 1,
    "seeInternalTools": true,
    "toolTags": ["research", "search", "filesystem"]
  },

  "capabilities": [
    {
      "name": "agent.research",
      "description": "Research a topic and return synthesis with sources.",
      "version": "1.0.0",
      "triggerEvent": "capability.invoke",
      "replyEvent": "capability.result",
      "inputSchema": {
        "required": ["query"],
        "optional": ["depth", "sessionId"],
        "properties": {
          "query": { "type": "string" },
          "depth": { "type": "string", "enum": ["shallow", "deep"] },
          "sessionId": { "type": "string" }
        }
      },
      "outputSchema": {
        "properties": {
          "summary":    { "type": "string" },
          "sources":    { "type": "array", "items": { "type": "string" } },
          "confidence": { "type": "number" }
        }
      },
      "exclusive": false,
      "bidWeight": 0.9
    }
  ],

  "subscribes": [
    "message.researcher",
    "capability.bid.request",
    "capability.result",
    "system.error"
  ],

  "publishes": [
    "capability.result",
    "capability.bid.response",
    "capability.invoke",
    "blackboard.write",
    "agent.log"
  ]
}
```

---

## The `"thinking"` Block — Progressive Feedback (Optional, Agents Only)

Appears only in user-facing agents (e.g., `assistant`). Defines the status message loop displayed while the agent processes a request.

```json
"thinking": {
  "cycleDelayMs": 12000,
  "backgroundThresholdMs": 120000,
  "steps": [
    "⏳ Thinking...",
    "⏳ Analyzing...",
    "⏳ Gathering data...",
    "⏳ Processing...",
    "⏳ Almost there...",
    "⏳ Refining...",
    "⏳ Finalizing...",
    "⏳ One more moment..."
  ],
  "background": "⏳ Still working on it. I'll notify you here when ready! 🔔"
}
```

| Field | Type | Default | Description |
|---|---|---|---|
| `steps` | string[] | `["⏳ Thinking..."]` | Loop messages rotated via `EditMessageText` |
| `cycleDelayMs` | long | `12000` | Wait interval between message changes (ms) |
| `backgroundThresholdMs` | long | `120000` | Maximum processing time before entering background execution mode (ms) |
| `background` | string | `"⏳ Still working..."` | Message dispatched when the threshold limit is reached; stops updates |

**Behavior by Channel:**
- **Telegram**: Sends `steps[0]` as a message, updates via `EditMessageText` every `cycleDelayMs`. Upon reaching `backgroundThresholdMs`, sends `background` and halts updates. In the final response: if background execution was not triggered and response ≤ 4096 characters, edits the thinking message in-place; otherwise, sends a new message.
- **Console**: Prints `steps[0]` once upon receiving `chat.thinking`; subsequent steps are ignored.

**Publication Flow**: `AgentCore.handleChatPrompt()` publishes `chat.thinking` (serialized `ThinkingConfig` payload) immediately after `chat.typing` and before acquiring the session execution lock.

---

## Agent Block Fields — Quick Reference

| Field | Type | Default | Description |
|---|---|---|---|
| `skillsDir` | string | `"."` | Directory containing persona `.md` and specification sheets |
| `maxRounds` | integer | `5` | Maximum execution loops the LLM can run during a task |
| `maxDelegations` | integer | `3` | Maximum routing delegations allowed during a task |
| `maxToolCalls` | integer | `20` | Maximum tool execution calls allowed during a task |
| `maxConcurrentMissions` | integer | `1` | Maximum parallel task loops permitted for the agent |
| `seeInternalTools` | boolean | `false` | `true` allows the agent to discover and invoke tools marked `internal: true` |
| `toolTags` | string[] | `null` | Tag filters for tool discovery (e.g., `["research"]`). If omitted, all permitted tools are visible |
| `negotiatingTimeoutSeconds`| integer | `120` | Maximum negotiation seconds allowed (Deprecated) |
| `contextLoading` | string | `"lazy"` | Ignored by runtime; all discovery is eager (Deprecated) |
| `requireToolOnRound1` | boolean | `true` | Ignored; the LLM determines tool usage autonomously (Deprecated) |
| `requireDelegationOnRound1`| boolean | `true` | Ignored; the LLM determines delegation autonomously (Deprecated) |

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
| 50 | Interactive | Standard Console UI or Telegram bot integrations |

---

## Routing Type Mapping Conventions

| Type | `triggerEvent` | Delivery Flow |
|---|---|---|
| tool | `capability.tool.<name>` | `CapabilityInterceptor` intercepts generic invokes, re-publishes to exact triggerEvent type; tool receives direct UDS frames |
| agent | *(None)* | `CapabilityInterceptor` evaluates candidate bids, resolves, and forwards to target agent via `message.{agentId}` |

Agents **do not subscribe to `capability.invoke`**. They receive UDS messages strictly on `message.{self}` paths configured by the Kernel during initialization.
