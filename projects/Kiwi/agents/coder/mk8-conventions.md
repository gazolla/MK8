# MK8 Plugin Conventions (authoritative)

This file is the **single source of truth** for the MK8 kernel contract. The full canonical guides
are replicated **inside this skill** (under my Skills directory `<SKILLS>`, see runtime identity) —
read them for anything non-trivial via the filesystem tool:

- `<SKILLS>/guides/CREATE_PLUGIN.md` — tools and system plugins
- `<SKILLS>/guides/CREATE_AGENT.md` — agents (config-only personas)
- `<SKILLS>/guides/CREATE_INTERCEPTOR.md` — kernel cross-cutting concerns

The filesystem tool reads the whole MK8 repo, so the **real kernel source** is also available for
ground-truth on any API: `kernel/KernelEvent.java`, `kernel/interceptors/plugin/PluginConfig.java`,
`kernel/interceptors/plugin/PluginBase.java`. Never invent an API — read the source if unsure.

> The `refs/` files give *domain* patterns (HTTP, Kafka, crypto, LangChain4j, …) and now use the
> **MK8 kernel wiring** shown here (`KernelEvent` / `PluginBase`, the 4-level `//SOURCES`, no manual
> `capability.bid.request` handling). If a ref ever conflicts with this file, **this file wins** —
> it is the authority on the kernel contract; the ref is the authority on the domain library.

---

## Plugin types

| Type | Has LLM | Has capabilities | Lives in (Kiwi) |
|---|---|---|---|
| `tool` | no | yes | `tools/<name>/` |
| `agent` | yes | yes (no `triggerEvent`) | `agents/<name>/` |
| `system` | no | optional | `system/<name>/` |

Dev/maintenance utilities live in `dev/`.

**Agents have NO `.java` file.** An agent is `plugin.json` + `persona.md` (+ optional `.md`),
run against the shared structural runtime `agent/Agent.java`. Never generate a `.java` for an
agent — see `docs/CREATE_AGENT.md`.

---

## Mandatory JBang header (tools and system plugins)

```java
///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../../kernel/KernelEvent.java
//SOURCES ../../../kernel/Log.java
//SOURCES ../../../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../../../kernel/interceptors/plugin/PluginBase.java
```

The `//SOURCES` prefix is the depth from the plugin dir up to the MK8 root:

| Plugin location | prefix |
|---|---|
| `projects/Kiwi/tools/<name>/` | `../../../../kernel/...` (4 levels) |
| `projects/<App>/<name>/` | `../../../kernel/...` (3 levels) |
| `projects/<App>/` | `../../kernel/...` |

(Kiwi plugins sit one level deeper than a flat project, so they use **four** `../`.)

---

## Event API — exact method signatures (`KernelEvent`, NOT `Event`)

**Only these factories exist. Do NOT invent `KernelEvent.create/build/with`.**

```java
// Fire-and-forget broadcast (capability.register, capability.bid.response, plain pub/sub)
KernelEvent.of(String type, String payload, String source)

// Reply preserving correlation + session — use for capability.result and capability.error
KernelEvent.withCorrelation(String type, String payload, String source, String correlationId, String sessionId)

// Session-scoped, no correlation (e.g. chat.* events)
KernelEvent.withSession(String type, String payload, String source, String sessionId)

// Copies correlation + session (+ workflowId, trace) from an origin event
KernelEvent.reply(KernelEvent origin, String type, String payload, String source)
```

JSON is via `KernelEvent.MAPPER`. The socket constant is `KernelEvent.DEFAULT_SOCKET`
(`/tmp/mk8/kernel.sock`). Always reply to a `capability.invoke` with `withCorrelation(...)`,
passing `event.correlationId()` and `event.sessionId()`.

---

## The plugin skeleton (tools / system) — `PluginBase`, NOT `BasePlugin`

```java
public class MyTool {

    static final String EVT_TRIGGER = "capability.tool.my.op";  // = capability.triggerEvent
    static final String SOURCE_ID   = "my-tool";                // MUST equal plugin.json "id"

    public static void main(String[] args) throws Exception {
        KernelEvent.initLogging();
        new MyTool().start();
    }

    void start() throws Exception {
        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, this::handle);
    }

    void handle(String json, OutputStream out) throws Exception {
        KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class); // declare BEFORE any try
        if (!EVT_TRIGGER.equals(event.type())) return;

        JsonNode input = KernelEvent.MAPPER.readTree(event.payload()).path("input"); // NESTED — see below
        // ... do the work ...

        PluginBase.publish(KernelEvent.withCorrelation(
            "capability.result",
            KernelEvent.MAPPER.writeValueAsString(Map.of("result", result)),
            SOURCE_ID, event.correlationId(), event.sessionId()), out);
    }
}
```

What `PluginBase.run` already does for you (do **not** re-implement it):
- opens the UDS, sends `plugin.register` + `plugin.ready`, runs the read loop on virtual threads;
- **auto-answers `capability.bid.request`** for your capabilities — never write manual bid handling;
- loads trace/span context per frame.

Logging: `Log.rawInfo(...)` / `Log.info/warn/error(...)` (configure once with `Log.configure(SOURCE_ID, out)` inside `handle`). Do not invent a `publishLog` helper.

---

## Invocation payload shape — NESTED

The canonical invoke is `{ "name": "<cap>", "input": { ...fields } }`. Tools read their
arguments from `payload.path("input")`. Reply results are wrapped as `{ "result": ... }`.

---

## plugin.json essentials

- Tool: `type:"tool"`, capability has `triggerEvent` (e.g. `capability.tool.my.op`) which MUST
  also appear in `subscribes`; `lifecycle.mode` usually `on-demand`.
- Agent: `type:"agent"`, capability has **no** `triggerEvent`; `subscribes` includes
  `message.<id>`; launch runs the structural runtime. See `docs/CREATE_AGENT.md`.
- System: `type:"system"`, broadcast pub/sub; `lifecycle.mode:"persistent"`.
- `id` must equal `SOURCE_ID`. Never hardcode an LLM API key — use `apiKeyEnv`.
- `launch.order`: logger 10, system 20, tools 30, agents 40, interactive UI 50.
- **Secrets** (password / API key / token): never put the value in `plugin.json`. Declare it in a
  top-level `"secrets":[{ "key", "prompt" }]` array and read it at runtime via `SecretClient.get("KEY")`
  (`//SOURCES ../../helpers/SecretClient.java`). Non-secret settings go in `"config"`. Full pattern: `refs/ref-secrets.md`.

---

## Critical Java rules

- String literals use double quotes `"` — **never** backticks.
- Declare `KernelEvent event = ...readValue(...)` **before** any `try {` so it is visible in `catch`.
- Wrap slow work (HTTP, LLM, DB, sleep) in `Thread.ofVirtual().start(...)` so the event loop stays responsive.
- One-time init on `plugin.ready` must be guarded with `AtomicBoolean.compareAndSet(false, true)`.
