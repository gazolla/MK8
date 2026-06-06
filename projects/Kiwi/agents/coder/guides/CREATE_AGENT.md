# Creating an Agent

This guide explains how to build an **agent** for the MK8 MicroKernel. It complements [CREATE_PLUGIN.md](CREATE_PLUGIN.md) (tools, system plugins) and [CREATE_INTERCEPTOR.md](CREATE_INTERCEPTOR.md) (kernel cross-cutting concerns).

Agents are special: unlike a tool or a system plugin, **an agent has no `.java` file of its own**. It is pure configuration — a `plugin.json` plus one or more Markdown files — that runs against the shared, structural agent runtime in `agent/` (`Agent.java`, `AgentCore.java`, `MissionRunner.java`, `SkillLoader.java`, …).

---

## What Is an Agent?

A tool executes a pure function. An agent **reasons**: it receives a request, calls an LLM in a multi-round loop, optionally invokes tools and delegates to other agents, and assembles a final answer. The reasoning loop, the LLM wiring, the session memory, the tool routing — all of that lives **once** in the structural runtime and is shared by every agent. What makes one agent different from another is only:

1. its **persona** (the Markdown that becomes the system prompt), and
2. its **`plugin.json`** (identity, model, limits, capability, subscriptions, launch).

So creating an agent means writing those config files — never a new event loop.

```
agents/
└── researcher/
    ├── plugin.json          ← identity, llm, agent limits, capability, launch
    ├── persona.md           ← REQUIRED — loaded first, becomes the system prompt
    ├── research-strategy.md  ← optional supporting knowledge (loaded too)
    └── workspace.md          ← optional supporting knowledge
```

The runtime is launched once per persona with the persona directory as its working directory:

```json
"launch": {
  "command": ["jbang", "../../../../agent/Agent.java", "."]
}
```

`Agent.java` receives `.` as its `skillsDir` argument and loads `./plugin.json` and every `./*.md` from that directory. The `../../../../` prefix is the depth from `projects/<App>/agents/<persona>/` up to the MK8 root — adjust it if your persona lives at a different depth.

---

## Part 1 — The Persona (`persona.md` and friends)

### How the system prompt is assembled

`SkillLoader.loadSystemPrompt()` builds the system prompt like this:

1. Lists every `*.md` file in the persona directory (non-recursive).
2. Sorts them so **`persona.md` is always first**, then the rest alphabetically.
3. Concatenates their content.
4. Appends an **auto-generated identity section** (agent id, type, runtime, the capabilities you expose, your round/tool/delegation limits, the model, the current date/timezone, the project root and workspace paths). You do **not** write this part — the runtime injects it.

Tools are **not** described in the prompt text. They are passed to the model natively as function-calling specifications (see Part 3). Do not paste tool lists into `persona.md`.

### `persona.md` — required

This is the heart of the agent. Write it as a direct, second-person instruction set: who the agent is, what it is responsible for, how it should behave, its output format, and its boundaries. Keep it focused — it is prepended to every LLM call.

```markdown
# Researcher

You are a research specialist. Given a topic, you investigate it using the
search, browser and wikipedia tools and return **structured, sourced findings**.

## How you work
1. Decompose the query into concrete sub-questions.
2. Use tools to gather evidence — never answer changing facts from memory.
3. Cross-check claims across at least two sources before asserting them.

## Output format
Return a short summary, a bullet list of findings, and a `sources` list of URLs.
Always include a confidence level: high / medium / low.

## Boundaries
- Do not fabricate URLs or citations.
- If the evidence is thin, say so explicitly rather than guessing.
```

### Supporting `.md` files — optional

Any other top-level `*.md` (e.g. `research-strategy.md`, `output-format.md`) is concatenated into the prompt after `persona.md`. Use them to keep `persona.md` short while still giving the agent deep guidance.

**Lazy loading.** If `plugin.json` sets `"agent": { "contextLoading": "lazy" }`, only `persona.md` is loaded on the first LLM round; the other `.md` files are added in later rounds. Use this to keep round-1 latency low for agents with large knowledge bases.

**Subfolders are not auto-loaded.** Files inside subdirectories (e.g. `refs/`, `patterns/`, `templates/`) are **not** read into the prompt. Reference them from `persona.md` and let the agent open them on demand through the `filesystem` tool. This is the idiom for large reference libraries (the `coder` persona's `refs/`, the `planner`'s `patterns/`, the `writer`'s `templates/`).

---

## Part 2 — `plugin.json` for an Agent

```json
{
  "id": "researcher",
  "type": "agent",
  "version": "1.0.0",
  "description": "On-demand research agent. Investigates topics and returns structured findings.",

  "lifecycle": { "mode": "on-demand", "idleTimeoutSeconds": 600 },

  "llm": {
    "model":       "google/gemini-2.5-flash",
    "baseUrl":     "https://openrouter.ai/api/v1",
    "apiKeyEnv":   "OPENROUTER_API_KEY",
    "maxTokens":   4096,
    "temperature": 0.2
  },

  "agent": {
    "skillsDir":             ".",
    "maxRounds":             14,
    "maxDelegations":        0,
    "maxToolCalls":          10,
    "maxConcurrentMissions": 3,
    "seeInternalTools":      true,
    "toolTags":              ["search", "browser", "wikipedia"]
  },

  "capabilities": [
    {
      "name":        "agent.research",
      "description": "Research a topic and return structured findings.",
      "replyEvent":  "capability.result",
      "inputSchema": {
        "required":   ["query"],
        "properties": { "query": { "type": "string" } }
      },
      "bidWeight": 1.0,
      "tags":      ["agent", "research"]
    }
  ],

  "subscribes": [
    "message.researcher",
    "capability.bid.request",
    "capability.result",
    "system.error"
  ],

  "publishes": [
    "capability.register",
    "capability.result",
    "capability.error",
    "capability.invoke",
    "capability.bid.response"
  ],

  "launch": {
    "name":         "Researcher",
    "command":      ["jbang", "../../../../agent/Agent.java", "."],
    "order":        40,
    "delayAfterMs": 300
  }
}
```

### Fields specific to agents

**`type` must be `"agent"`.** This is what tells `SkillLoader` (in other agents) and the kernel to treat it as a delegatable reasoner.

**`lifecycle.mode`:**
| mode | Use for | Behaviour |
|---|---|---|
| `persistent` | The conversational front-end (e.g. `assistant`). | Launched at boot, never auto-killed. |
| `on-demand` | Specialists (`researcher`, `coder`, `writer`, …). | Spawned by the `PluginInterceptor` on first invocation, killed after `idleTimeoutSeconds`. |

**`llm`** — model, base URL, token/temperature. Never hardcode the key; reference an env var via `apiKeyEnv` (read at runtime). Use a **valid** model id for your provider.

**`agent`** — the reasoning budget and tool visibility:
| field | meaning |
|---|---|
| `skillsDir` | Where to load `.md` from. Always `"."` (the persona dir is the cwd). |
| `maxRounds` | Max LLM turns per mission. |
| `maxToolCalls` | Hard cap on tool invocations per mission. |
| `maxDelegations` | How many times this agent may delegate to other agents. `0` = leaf specialist. |
| `maxConcurrentMissions` | Parallel missions allowed. |
| `contextLoading` | `lazy` to load only `persona.md` on round 1 (see Part 1). |
| `seeInternalTools` | `true` lets this agent see capabilities marked `"internal": true`. |
| `toolTags` | Whitelist of tool tags this agent may use. Empty or `["*"]` = all tools. Otherwise a capability is offered only if one of its `tags` matches. |

### The Agent's Capability — no `triggerEvent`

An agent's capability has **no `triggerEvent`**. That is the signal that makes the `CapabilityInterceptor` deliver an invocation as a direct **`message.<id>`** frame instead of re-publishing a tool trigger. Therefore:

- The capability name is conventionally prefixed `agent.` (e.g. `agent.research`, `agent.write`). The router gives `agent.*` calls a longer timeout because missions run long.
- **`subscribes` must include `"message.<id>"`** (e.g. `"message.researcher"`) — this is how invocations arrive.
- Mark a capability `"internal": true` to hide it from default tool discovery (only agents with `seeInternalTools` will see it). The `assistant`'s own `chat.respond` is internal.

### Required subscriptions

Every agent should subscribe to at least:

- `message.<id>` — incoming invocations (the agent capability).
- `capability.bid.request` — so `PluginBase`/the runtime can auto-bid when the capability is contended.
- `capability.result` — results of tools/agents this agent itself invoked (routed back by `correlationId`).
- `system.error` — error awareness.

A persistent front-end agent (the `assistant`) additionally subscribes to `chat.prompt` and publishes `chat.response` / `chat.typing` / `chat.thinking`.

---

## Part 3 — How Agents Use Tools and Delegate

### Tool discovery

The runtime calls `SkillLoader.discoverTools()` which walks every `plugin.json` under the **project root** (the directory containing `Start.java`) and offers each non-`internal` capability of type `tool`, `agent`, or `system` to the model as a function — filtered by this agent's `toolTags`. You do not wire tools manually; just place the tool's `plugin.json` in the project and tag it. The capability name `text.wordcount` is exposed to the model as the function `text_wordcount` (dots → underscores).

### Invocation shape (canonical)

When the model picks a tool, the runtime publishes a **nested** invoke — this is the canonical MK8 shape and what tool templates read (`payload.path("input")`):

```json
{ "name": "tool.weather.get", "input": { "city": "São Paulo" } }
```

### Delegation

An agent delegates to another agent exactly like calling a tool: it invokes the other agent's `agent.*` capability. The `assistant` (with `maxDelegations > 0`) is the usual orchestrator; specialists are typically leaves (`maxDelegations: 0`). Cross-agent context can be shared through the blackboard (`blackboard.write` / `blackboard.read`, with `wildcardSubscribes` like `blackboard.updated.session.research.*` for reactive updates).

---

## Part 4 — Boot and Routing Recap

```
Console → chat.prompt ─────────────► assistant (persistent)
                                        │ model decides to delegate
                                        ▼
                            capability.invoke { name: "agent.research" }
                                        │
                              CapabilityInterceptor (no triggerEvent)
                                        ▼
                            message.researcher ─► researcher (spawned on-demand)
                                        │ uses search/browser tools
                                        ▼
                            capability.result { corrId } ─► back to assistant
                                        ▼
                            chat.response ─► Console
```

---

## Checklist

- [ ] `type` is `"agent"`; there is **no `.java`** in the persona directory.
- [ ] `persona.md` exists and is written as a direct instruction set (it loads first).
- [ ] Supporting knowledge is split into extra `*.md` (loaded) or subfolders (read on demand via `filesystem`).
- [ ] The capability has **no `triggerEvent`** and is named `agent.<something>`.
- [ ] `subscribes` includes `message.<id>`, `capability.bid.request`, `capability.result`, `system.error`.
- [ ] `lifecycle.mode` is `persistent` only for the conversational front-end; specialists are `on-demand`.
- [ ] `llm.model` is a valid provider id; the API key is read from `apiKeyEnv`, never hardcoded.
- [ ] `agent.toolTags` matches the tags of the tools this agent should use (or `["*"]`).
- [ ] `launch.command` points to the structural runtime: `["jbang", "../../../../agent/Agent.java", "."]` (adjust depth).
- [ ] `launch.order` is `40` (agent tier).
```
