# System Architecture

This document describes the architecture of the MK8 MicroKernel — its layers, components, responsibilities, and how they interact.

---

## The Central Idea

MK8 follows a **hub-and-spoke architecture**: the Kernel is the hub, and every plugin is a spoke. Nothing talks to anything else directly — all communication passes through the center.

```
Plugin A ───┐
Plugin B ────── KERNEL ──── Plugin D
Plugin C ───┘
```

The Kernel is intentionally dumb: it does not know what a capability is, what an agent does, or what any plugin contains. All intelligence lives in the interceptors and plugins.

---

## The Three Layers

The system is organized in three stacked layers, each built on top of the previous one:

```
┌──────────────────────────────────────────────────────────┐
│  LAYER 3 — PLUGINS                                       │
│  Independent OS processes with business logic.           │
│  Tools, agents, and UI plugins.                          │
├──────────────────────────────────────────────────────────┤
│  LAYER 2 — INTERCEPTORS                                  │
│  The intelligence pipeline: caching, capability          │
│  routing, bidding auctions, and process lifecycle.       │
├──────────────────────────────────────────────────────────┤
│  LAYER 1 — KERNEL                                        │
│  Pure event bus. Accepts UDS connections, reads          │
│  length-prefixed frames, routes by event type.           │
└──────────────────────────────────────────────────────────┘
```

**Layer 1** does not know what a capability is. It only routes events by type.  
**Layer 2** does not write to disk or manage processes directly. It only decides what to do with each event.  
**Layer 3** does not know how routing works. It only publishes and consumes events.

---

## Layer 1 — The Kernel

The Kernel is a **UDS server with a routing table**. Its logic fits in three steps:

```
1. Accept connection → create a Connection (reader thread + writeQueue + writer thread)
2. Read frame        → run the interceptor chain
3. Nothing consumed? → broadcast to all subscribers of that event type
```

Internally it maintains four routing tables:

```
exactRoutes    →  "data.item"   →  [consumer-1, consumer-2]
prefixRoutes   →  "logs."       →  [logger]
byPluginId     →  "word-count"  →  Connection
pendingRoutes  →  "corrId-abc"  →  "demo-runner"
```

### Key Classes in Layer 1

#### `Kernel`
The UDS server and connection manager. Accepts plugin connections, runs the interceptor chain for every incoming event, and broadcasts unhandled events to subscribers. Loads interceptors dynamically at boot from positional CLI arguments by class name — no registration or configuration required.

**Routing tables:**
- `exactRoutes` — maps event type strings to lists of `Connection` objects.
- `prefixRoutes` — `CopyOnWriteArrayList<PrefixRoute>` for wildcard prefix subscriptions (e.g. `logs.*`).
- `byPluginId` — maps plugin ID strings to their active `Connection`.
- `pendingRoutes` — maps `correlationId` to the source plugin ID for async reply routing.

**Key methods:**
- `start(interceptors)` — binds the UDS socket and enters the accept loop.
- `handleClient(channel)` — one virtual thread per plugin; reads frames and calls `route()`.
- `register(event, channel)` — creates a `Connection`, registers routes from the `plugin.register` payload.
- `unregister(connection)` — removes all routes for a dropped connection and announces `system.plugin.disconnected` through the normal routing path (naming no listener), letting any interceptor react to the loss.
- `route(event, json, source)` — runs the interceptor chain, then broadcasts if not consumed.
- `findProjectRoot()` — walks up from `user.dir` looking for `kernel/Kernel.java` to locate the project root.

#### `Connection`
Manages a single plugin's socket channel and outbound write queue. One `Connection` is created per accepted UDS client.

- `pluginId` — the plugin's declared ID.
- `channel` — the `SocketChannel` to the plugin process.
- `writeQueue` — a `LinkedBlockingQueue<byte[]>` acting as a FIFO outbound buffer.
- **Writer thread** — a dedicated virtual thread that blocks on `writeQueue.take()` and writes each byte frame to the socket. Started in the constructor; not started for the synthetic `KERNEL_SOURCE` connection.
- **`POISON` sentinel** — a static `byte[0]` constant. `shutdown()` enqueues it; when the writer thread dequeues it, it exits cleanly. Called by `unregister()` after all queued frames have been delivered.

#### `PrefixRoute`
A lightweight record pairing a prefix string with its target `Connection`. During broadcast, the Kernel iterates `prefixRoutes` and enqueues events whose `type` starts with the stored prefix.

#### `KernelConfig`
A record `(Path scanRoot, Path logsOverride)` resolved at boot and passed to interceptor constructors. `scanRoot` is the project root for `plugin.json` discovery; `logsOverride` is an optional explicit log directory.

#### `KernelBus` / `KernelBusImpl`
The interface interceptors use to inject events back into the Kernel without accessing it directly:

```java
bus.route(event)                         // re-enters the full pipeline
bus.sendTo(pluginId, json)               // direct delivery, bypasses routing
bus.addPendingRoute(corrId, pluginId)    // register who is waiting for this reply
bus.removePendingRoute(corrId)           // retrieve and clean up
```

#### `KernelEvent`
The universal event envelope — an immutable Java record shared by all components. Defines the frame protocol, factory methods, and logging infrastructure.

- **Frame protocol** — `readFrame()` / `writeFrame()`: 4-byte big-endian length header followed by UTF-8 JSON payload.
- **Factory methods** — `of()`, `withCorrelation()`, `reply()` for constructing common event patterns.
- **`TimestampPrintStream`** — inner class that decorates `System.out` and `System.err` to prepend `[yyyy-MM-dd HH:mm:ss.SSS]` to every output line. Installed via `initLogging()`, guarded by a system property so it is safe to call multiple times.
- **`CURRENT_TRACE_ID` / `CURRENT_SPAN_ID`** — `ThreadLocal<String>` values set by `PluginBase` on every virtual thread dispatching an incoming event, and cleared after the handler returns.

#### `EventInterceptor`
The interface all interceptors implement:

```java
boolean intercept(KernelEvent event, String json) throws Exception;
default boolean handles(String eventType) { return true; }
default Set<String> publishes()  { return Set.of(); }
default Set<String> subscribes() { return Set.of(); }
```

Return `true` to consume the event (chain stops, no broadcast). Return `false` for side-effects only (chain continues, broadcast proceeds).

#### `InterceptorLifecycle`
Optional interface for interceptors that need to start background work after construction:

```java
interface InterceptorLifecycle {
    void onStart(); // called by Kernel immediately after instantiation
}
```

---

## Layer 2 — The Interceptors

Four interceptors execute sequentially for every incoming event. Each has a single responsibility:

```
Incoming event
      │
      ▼
┌──────────────────────────────────────────────────────────────────────┐
│ IdempotencyInterceptor                                               │
│ "Have I seen this correlationId before?"                             │
│   Yes, result in cache     → serve instantly and STOP  (true)       │
│   Yes, currently in-flight → collapse and STOP         (true)       │
│   No                       → register in-flight, CONTINUE (false)   │
└──────────────────────────────────────────────────────────────────────┘
      │
      ▼
┌──────────────────────────────────────────────────────────────────────┐
│ CapabilityInterceptor                                                │
│ "Is anyone registered for this capability?"                          │
│   1 provider    → route directly and STOP              (true)       │
│   N providers   → run auction → route winner and STOP  (true)       │
│   0 providers   → check catalog → spawn or error STOP  (true)       │
│   not an invoke → update registry and CONTINUE         (false)      │
└──────────────────────────────────────────────────────────────────────┘
      │
      ▼
┌──────────────────────────────────────────────────────────────────────┐
│ PluginInterceptor                                                    │
│ "Is this a lifecycle event?"                                         │
│ system.plugin.spawn         → spawn process and STOP    (true)       │
│ system.plugin.usage         → update timestamp CONTINUE  (false)     │
│ system.plugin.list.req      → reply with list and STOP   (true)      │
│ system.catalog.refresh      → re-scan disk and CONTINUE  (false)     │
│ plugin.ready                → bind pid for supervision   (false)     │
│ system.plugin.disconnected  → auto-restart if crashed    (false)     │
│ system.plugin.restart.req   → restart + reply and STOP   (true)      │
└──────────────────────────────────────────────────────────────────────┘
      │
      ▼
┌──────────────────────────────────────────────────────────────────────┐
│ BlackboardInterceptor                                                │
│ "Is this a blackboard.* operation?"                                  │
│ blackboard.read   → reply read.result and STOP    (true)             │
│ blackboard.write  → store + notify updated.* STOP  (true)            │
│ blackboard.query  → reply query.result and STOP    (true)            │
│ blackboard.purge  → purge scope and STOP           (true)            │
└──────────────────────────────────────────────────────────────────────┘
      │
      ▼
Broadcast to all subscribers
```

Their event types are disjoint, so the relative order in the chain does not
matter — each interceptor's `handles()` filters to its own events. The blackboard
sits last only by convention.

### `IdempotencyInterceptor`

Sits at position 0 in the chain. Eliminates redundant processing with two mechanisms:

- **Sliding-window cache** — stores `capability.result` and `capability.error` events keyed by `correlationId`. Results expire after a 5-minute TTL managed by a daemon scheduler.
- **Single-flight collapsing** — if an identical request arrives while the first is still in-flight, the duplicate is added to the in-flight callers list. When the single result arrives, all callers receive it simultaneously.

Uses `inFlight.compute(corrId, ...)` to atomically close the race window between a cache miss and an in-flight registration — no explicit locks.

### `CapabilityInterceptor`

Sits at position 1. Maintains the live capability registry and coordinates routing:

- **`registrations`** — `ConcurrentHashMap<String, CopyOnWriteArrayList<Registration>>`: maps capability names to live provider registrations. A `Registration` holds `pluginId`, `triggerEvent` (null for agents), and `bidWeight`.
- **`builtins`** — `Map<String, BuiltInHandler>`: built-in capability handlers resolved before the live registry. Currently contains `"system.capability.list"` → `buildCapabilityList()`.
- **`pendingInvokes`** — queues `capability.invoke` events while an on-demand plugin is starting up. Drained when the plugin registers.
- **`localCatalog`** — local copy of the `PluginInterceptor` catalog, populated via `system.catalog.entry` events. Zero direct coupling to `PluginInterceptor`.
- **`auctions`** — tracks in-flight bidding auctions. Resolved after a 500ms window or when all candidates have bid.
- **`catalogReady`** — a `CompletableFuture<Void>` completed on `system.catalog.ready`. Used to wait briefly before declaring a capability unavailable.

Routing decision for `capability.invoke`:
1. Check `builtins` — handle internally if matched.
2. Check `registrations` — route directly (1 provider) or start auction (N providers).
3. Check `localCatalog` — queue for on-demand spawn, forward persistent trigger, or publish error.

#### `capability.query`

`handleQuery(event)` handles `capability.query` events. The payload is treated as a raw capability name. Returns a `capability.query.result` event with `{"capability": "<name>", "providers": ["id1", ...]}` — the list of currently registered provider plugin IDs.

### `PluginInterceptor`

Sits at position 2. Single source of truth for plugin discovery and process lifecycle:

- **`byCapName`** / **`byPluginId`** — indexes built during directory scans.
- **`managed`** — `Map<String, ManagedProcess>`: tracks running child processes with their PID and `Process` handle.
- **`lastUsed`** — `Map<String, Long>`: timestamps updated on every `system.plugin.usage` event.
- **`spawning`** — `ConcurrentHashMap.newKeySet()`: guards against concurrent double-spawn of the same plugin. `spawning.add(pluginId)` is atomic; the entry is always removed in a `finally` block.
- **Scheduler** — daemon `ScheduledExecutorService` running `checkIdlePlugins()` every 60 seconds.

On `onStart()`, launches the initial catalog scan in a virtual thread. The scan walks the project tree, skips the `kernel/` directory, parses every `plugin.json`, publishes `system.catalog.entry` for each capability, registers `system.plugin.list` and `system.plugin.restart` as routable capabilities, and publishes `system.catalog.ready` on completion.

**Supervision (folded-in crash recovery).** The PluginInterceptor also owns plugin *resilience*, since it already owns process lifecycle:

- **`launchables`** — recorded during the scan for *every* `plugin.json` with a launch block, independent of capabilities, so even capability-less plugins are revivable.
- **`supervised`** — per-plugin state (pid learned from `plugin.ready`, restart count, last-restart timestamp, an intentional-stop flag).
- **Crash detection** — the Kernel emits `system.plugin.disconnected` whenever a connection drops (see Layer 1). The PluginInterceptor reacts: an intentional stop (idle reap / manual restart) is ignored; an unexpected drop of a `persistent` launchable triggers recovery.
- **Restart policy** — exponential backoff (`1s, 2s, 4s…`), capped at 3 restarts per 60s; exceeding the cap publishes `system.plugin.crashed` and gives up. On-demand plugins are never auto-restarted. A plugin opts out with `"lifecycle": { "restart": "never" }`.
- **Manual control** — the built-in `system.plugin.restart` capability kills and respawns a named plugin on request; `system.plugin.list` reports each plugin's pid, liveness, and restart count.

The Kernel and the PluginInterceptor stay fully decoupled: the Kernel only *announces* `system.plugin.disconnected` through the normal routing path, naming no listener; the PluginInterceptor subscribes like any other consumer.

### `BlackboardInterceptor`

A shared in-memory key-value store for inter-agent context passing — a "whiteboard" that plugins, which otherwise never talk to each other, use to leave facts for one another (conversation context, research caches, workflow flags). Because it lives inside the kernel, reads and writes resolve in-memory with zero extra network hops: the reply goes straight back to the requester via `bus.sendTo`, correlated by the original `correlationId` (preserved by `KernelEvent.reply`).

- **`store`** — `ConcurrentSkipListMap<String, Entry>` keyed by `"<scope>:<scopeId>:<key>"`. An `Entry` carries `value`, `author`, `tags`, `version`, `ttlSeconds`, and `created/updated` timestamps.
- **Scopes** — `session` (scopeId = sessionId), `workflow` (scopeId = workflowId), `global` (scopeId = null) bound the reach of each entry.
- **Optimistic locking** — an optional `expectedVersion` field. The check-and-set + version bump runs inside `store.compute()` so it is atomic under concurrent writers. A mismatch replies `blackboard.write.conflict`; a success (when `expectedVersion >= 0`) replies `blackboard.write.ok`.
- **TTL** — entries with `ttl > 0` expire after `ttl` seconds; a daemon cleaner sweeps every 60s.
- **Reactive notifications** — every write publishes `blackboard.updated.{scope}.{key}` via `bus.route()` for any prefix subscriber (e.g. a dashboard).

Events handled (all consumed — `intercept()` returns `true`): `blackboard.read` → `blackboard.read.result`, `blackboard.query` → `blackboard.query.result`, `blackboard.write` (store + notify), `blackboard.purge` (delete a scope). It needs no `pendingRoutes`: the direct `sendTo` reply makes return-routing unnecessary.

---

## Layer 3 — The Plugins

Every plugin is an independent JVM process. All plugins built with `PluginBase` share the same three-method skeleton:

```java
main()  →  new Plugin().start()  →  PluginBase.run("plugin.json", socket, this::handle)
```

`PluginBase.run()` handles the entire connection lifecycle: opens the UDS socket, sends `plugin.register` (with the current PID injected by `PluginBoot`), sends `plugin.ready`, reads incoming frames, and dispatches each one to the handler in a dedicated virtual thread.

### `PluginBoot`

Package-private companion class defined inside `PluginBase.java`. Handles the low-level boot sequence:

1. Opens a `SocketChannel` to the UDS path.
2. Reads the current PID via `ProcessHandle.current().pid()` and injects it into the config JSON.
3. Writes a `plugin.register` frame carrying the full `plugin.json` content (including `pid`).
4. Writes a `plugin.ready` frame with `{id, pid}`.
5. Delegates to the `PluginLogic` lambda — the event loop from `PluginBase.run()`.

### `PluginBase`

Public static utility class providing the connection framework for all plugins:

- **`run(configPath, socketPath, handler)`** — the single entry point. Loads `PluginConfig`, calls `PluginBoot.connectAndRun()`, registers capabilities, and starts the event loop.
- **`registerCapabilities(config, out)`** — publishes `capability.register` events for each capability declared in `plugin.json`.
- **`handleBidAuto(config, event, out)`** — automatically intercepts `capability.bid.request` events and replies with the configured `bidWeight` — no boilerplate required in plugin logic.
- **`publish(event, out)`** — thread-safe publish: `synchronized(out)` ensures concurrent virtual threads cannot interleave partial frames.
- **`publishSafe(event, out)`** — same as `publish()` but swallows exceptions; used in fire-and-forget telemetry paths.

### `PluginConfig`

Typed accessor for `plugin.json` fields. Loaded once at plugin startup via `PluginConfig.load("plugin.json")`. Provides `id()`, `llmModel()`, `llmBaseUrl()`, `llmApiKeyEnv()`, `llmMaxTokens()`, `llmTemperature()`, `thinkingBackground()`, `capabilities()`, and `raw()`.

### Plugin Profiles

Three distinct plugin profiles exist in the codebase:

| Profile | Delivery mechanism | Responds with |
|---|---|---|
| **Tool** | `triggerEvent` subscription | `capability.result` / `capability.error` |
| **Agent** | `message.<id>` direct frame | `capability.result` / `capability.error` |
| **System** | broadcast subscription | domain-specific events (no capability result) |

---

## How the Layers Communicate

The only currency exchanged between all layers is `KernelEvent`. No method calls cross layer boundaries. No objects are shared between OS processes.

```
Plugin  ──[KernelEvent over UDS]──►  Kernel  ──[KernelEvent via KernelBus]──►  Interceptors
Plugin  ◄──[KernelEvent over UDS]──  Kernel  ◄──[KernelEvent via KernelBus]──  Interceptors
```

The `KernelBus` is the narrow interface that keeps the Kernel and its interceptors decoupled: interceptors receive events and re-inject new events through the bus, never by calling Kernel methods directly.

---

## End-to-End Invocation Flow

Here is the complete path of a `capability.invoke` from `DemoRunner` to `WordCountTool` and back:

```
① DemoRunner publishes:
   capability.invoke { name: "text.analyze", corrId: "abc", source: "demo-runner" }

② Kernel receives → runs interceptor chain:

③ IdempotencyInterceptor:
   corrId "abc" is new → register inFlight["abc"] = ["demo-runner"]
   return false → continue

④ CapabilityInterceptor:
   "text.analyze" is registered → summary-agent
   store pendingRoutes["abc"] = "demo-runner"
   re-publish as message.summary-agent
   return true → stop chain

⑤ SummaryAgent receives message.summary-agent:
   create innerCorrId "xyz"
   store pending["xyz"] = { outerCorrId: "abc", ... }
   publish capability.invoke { name: "text.wordcount", corrId: "xyz" }

⑥ IdempotencyInterceptor: corrId "xyz" is new → pass
   CapabilityInterceptor: "text.wordcount" has no live provider
   check localCatalog → on-demand plugin found
   queue in pendingInvokes["text.wordcount"]
   publish system.plugin.spawn { capabilityName: "text.wordcount" }

⑦ PluginInterceptor receives system.plugin.spawn:
   spawn WordCountTool via ProcessBuilder
   WordCountTool connects, registers, drains pendingInvokes queue

⑧ WordCountTool receives capability.tool.text.wordcount:
   counts words
   publish capability.result { corrId: "xyz", result: {...} }

⑨ IdempotencyInterceptor receives capability.result corrId "xyz":
   cache result, deliver to summary-agent (was in inFlight["xyz"])

⑩ SummaryAgent receives capability.result corrId "xyz":
   look up pending["xyz"] → outerCorrId = "abc"
   build report
   publish capability.result { corrId: "abc", result: "report" }

⑪ IdempotencyInterceptor receives capability.result corrId "abc":
   cache result, deliver to demo-runner (was in inFlight["abc"])

⑫ DemoRunner receives the report.
```

---

## Directory Structure Maps to Architecture

```
MK8/
├── kernel/                          ← Layer 1 + Layer 2
│   ├── Kernel.java                  ← UDS server, routing tables, core interfaces
│   ├── KernelEvent.java             ← universal envelope, frame protocol, logging
│   └── interceptors/
│       ├── capability/
│       │   └── CapabilityInterceptor.java   ← registry, auctions, routing
│       ├── idempotency/
│       │   └── IdempotencyInterceptor.java  ← cache, single-flight collapsing
│       ├── blackboard/
│       │   └── BlackboardInterceptor.java   ← shared KV store, scopes, TTL, versioning
│       └── plugin/
│           ├── PluginBase.java          ← plugin connection framework
│           ├── PluginConfig.java        ← plugin.json typed accessor
│           └── PluginInterceptor.java   ← discovery, catalog, process lifecycle
│
└── projects/                        ← Layer 3
    ├── SimpleProject/               ← raw UDS plugins (no PluginBase)
    ├── PluginProject/               ← PluginBase plugins, simple pub/sub
    ├── InterceptorsProject/         ← tool + agent + transient client demo
    ├── BlackboardProject/           ← writer/reader demo for the shared blackboard
    ├── SupervisorProject/           ← crash-detection + auto-restart demo
    └── ChatAI/                      ← LLM agent + interactive console UI
```

`kernel/` never imports anything from `projects/`. Projects import from `kernel/` via `//SOURCES` JBang directives. The dependency arrow always points downward, never up.

---

## Component Responsibility Summary

| Component | Single Responsibility |
|---|---|
| `Kernel` | Accept UDS connections and route frames by event type |
| `KernelEvent` | Define the universal envelope and the frame protocol |
| `Connection` | Own the write queue and writer thread for one plugin connection |
| `KernelBus` | Give interceptors a safe surface to re-inject events |
| `IdempotencyInterceptor` | Ensure each request is processed exactly once |
| `CapabilityInterceptor` | Know who offers each capability and route to them |
| `PluginInterceptor` | Know what exists on disk, manage process lifecycle, and supervise (crash detection + restart) |
| `BlackboardInterceptor` | Store and serve shared key-value state across plugins |
| `PluginBase` | Abstract the UDS connection so plugins only write business logic |
| `PluginBoot` | Handle the low-level UDS boot handshake for plugins |
| `PluginConfig` | Read `plugin.json` and expose typed configuration |
| Plugins | Implement the business logic of each capability |

Each component does exactly one thing. None knows the internals of any other.
