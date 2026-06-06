# Code Generation Guide

## My knowledge base — everything is reachable, nothing is hardcoded

All my references live **inside my own skill folder**. Its absolute path is in my runtime identity
as **Skills directory** (call it `<SKILLS>`). I read them with the filesystem tool (`op: read`):

- **Domain patterns** → `<SKILLS>/refs/<file>.md` (routing table below).
- **Canonical guides** (full) → `<SKILLS>/guides/CREATE_PLUGIN.md`, `CREATE_AGENT.md`, `CREATE_INTERCEPTOR.md`.
- **Kernel-contract summary** → `<SKILLS>/mk8-conventions.md`.

The filesystem tool can read the **entire MK8 repository** (not just the workspace). So I never guess a
kernel API — when unsure about a method or signature, I **read the real source**:

- `kernel/KernelEvent.java` — factories (`of`, `withCorrelation`, `withSession`, `reply`), `MAPPER`, `DEFAULT_SOCKET`.
- `kernel/interceptors/plugin/PluginConfig.java` — the actual config accessors (e.g. `raw()`, `id()`, `llmModel()`…). **There is no generic `config.get(key)`** — confirm the real method before using it.
- `kernel/interceptors/plugin/PluginBase.java` — `run`, `publish`, auto bid handling.

> Rule: **I never invent imports, packages, or method names.** Classes from `//SOURCES` live in the
> default package — there are **no** `import dev.mk8.kernel.*` lines. If I'm unsure, I read the source file.

## Reference file selection (under `<SKILLS>/refs/`)

| Spec mentions | Read |
|---|---|
| any simple tool (default) | `ref-multi-capability.md` |
| POST, Bearer, API key, authentication | `ref-http-advanced.md` |
| LLM, GPT, Claude, NVIDIA NIM, AI model | `ref-llm-api.md` |
| database, SQL, SQLite, H2, persist | `ref-database.md` |
| process, shell, command, subprocess | `ref-process.md` |
| WebSocket, real-time, ws://, wss:// | `ref-websocket.md` |
| encrypt, decrypt, JWT, HMAC, hash, crypto | `ref-crypto.md` |
| async, parallel, concurrent, CompletableFuture | `ref-async.md` |
| gRPC, protobuf | `ref-grpc.md` |
| CSV, Excel, spreadsheet, .xlsx | `ref-csv.md` |
| Kafka, message broker, pub/sub | `ref-kafka.md` |
| MQTT, IoT, sensor, broker | `ref-mqtt.md` |
| LangChain4J, RAG, AI service, embedding | `ref-langchain4j.md` |
| web UI, dashboard, HTML, HTTP server | `ref-web-ui.md` |
| JavaFX, desktop, window, GUI | `ref-javafx.md` |
| Quarkus, REST API (Quarkus) | `ref-quarkus.md` |
| Spring, Spring Boot | `ref-spring.md` |
| API key, password, token, secret, credential, auth | `ref-secrets.md` (declare in `secrets`, read via `SecretClient.get`) |
| email, SMTP, mail | `ref-secrets.md` (the password is a secret) |
| background, periodic, scheduled, polling | `ref-system-plugin.md` |
| socket, TCP, UDP, Unix socket | `ref-sockets.md` |
| MCP, Model Context Protocol | `ref-mcp-client.md` |
| A2A, agent-to-agent, Google A2A | `ref-a2a.md` |

If the spec involves HTTP calls, read both the default ref and `ref-http-advanced.md`.

---

## CRITICAL RULE — read before generating

**I MUST read `<SKILLS>/mk8-conventions.md` AND the matching `<SKILLS>/refs/<file>.md` BEFORE generating
any code.** Generating without reading them is a failure — my training knowledge of MK8 is incomplete.

- The **ref** is my source of truth for the **domain pattern** (the library calls, a working example to adapt).
- **`mk8-conventions.md`** (and, for the full detail, `<SKILLS>/guides/CREATE_PLUGIN.md` / `CREATE_AGENT.md` /
  `CREATE_INTERCEPTOR.md`) is my source of truth for the **kernel contract** (header, `KernelEvent`/`PluginBase`
  API, plugin.json shape). refs + conventions agree; if they ever disagree, **`mk8-conventions.md` wins**.
- For **config** or any API I'm not 100% sure of, I **read the real kernel source** (see above) instead of guessing.

**If `type=agent`: I do NOT write a `.java`.** An agent is `plugin.json` + `persona.md` run against the
structural runtime — I follow `<SKILLS>/guides/CREATE_AGENT.md`.

Never emit `Event.*`, `BasePlugin.*`, `import dev.mk8.*`, a 2-level `//SOURCES ../../kernel/...`, a manual
`capability.bid.request` handler, or a `config.get(...)` call — none of those exist in MK8.

---

## Plugin config (tools that need settings)

When a spec provides settings (SMTP host, API base URL, credentials, …), put them in a top-level
`"config"` object in `plugin.json` and read them at runtime from the parsed config tree:

```java
PluginConfig config = PluginConfig.load("plugin.json");
JsonNode c = config.raw().path("config");
String host = c.path("mail.smtp.host").asText("smtp.gmail.com");
```

Load config eagerly (in `start()` or lazily on first use, guarding `if (config == null)`). Do **not** defer
it to a `plugin.ready` event unless the plugin also `subscribes` to `plugin.ready` — on-demand tools may be
invoked before any `plugin.ready` arrives.

**Secrets are NOT config.** A password / API key / token / credential must **never** have its value in
`plugin.json`. Declare it by name in a top-level `"secrets": [{ "key", "prompt" }]` array (no value) and read
it at runtime with `SecretClient.get("KEY")` (`//SOURCES ../../helpers/SecretClient.java`). Full pattern in `ref-secrets.md`.
If `SecretClient.get` returns null, reply `capability.error` asking the user to provide it.

---

## Generation steps

When the payload has `spec`, `type`, `name`:

1. **Read `<SKILLS>/mk8-conventions.md` AND the matching `<SKILLS>/refs/<file>.md`** (op: read on both) in round 1, before composing anything. Read kernel source too if the spec touches an unfamiliar API.
2. Identify the domain, operations, and data types from `spec`; determine the Java class name (PascalCase of the base name).
3. Compose the full `plugin.json` (with a `"config"` block if settings were given), Java source (for `tool`/`system`), or — if `type=agent` — `persona.md` instead of Java. Take the **domain pattern** from the ref and the **kernel contract** from `mk8-conventions.md`.
4. Output using the delimited section format in `output-format.md` — no extra prose before or after the sections.

**I produce the complete output in a single response. My only output channel is my final response.**
