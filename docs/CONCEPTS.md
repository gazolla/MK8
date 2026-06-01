# System Concepts

This document describes the fundamental concepts, techniques, and characteristics of the MK8 MicroKernel.

---

## What Is This System?

MK8 is an **event-driven microkernel**. A single central process (the Kernel) sits in the middle of everything, and all other processes (plugins) connect to it. No plugin talks directly to another — all communication passes through the Kernel in the form of events.

Think of it like a public square: the Kernel is the square, the plugins are the people, and events are the notes that circulate between them.

---

## Concept 1 — The Event as the Universal Unit

Everything in the system is a `KernelEvent`. It does not matter whether it is a request, a response, an error, a boot signal, or a process death notification — everything has the same shape:

```json
{
  "id":            "uuid",
  "type":          "capability.invoke",
  "payload":       "{\"name\":\"text.wordcount\",\"text\":\"...\"}",
  "source":        "demo-runner",
  "correlationId": "abc-123",
  "sessionId":     "session-1",
  "workflowId":    "wf-456",
  "replyTo":       null,
  "traceId":       "trace-789",
  "spanId":        "span-001",
  "timestamp":     "2026-05-31T10:00:00.000"
}
```

| Field | Purpose |
|---|---|
| `id` | Unique identifier for this event instance |
| `type` | Routing key — what this event is (e.g. `capability.invoke`) |
| `payload` | The content — a stringified JSON object |
| `source` | Who sent it |
| `correlationId` | Which request this belongs to — used for async reply routing |
| `sessionId` | Which conversation or session context |
| `workflowId` | Which long-running workflow execution path — propagated by `KernelEvent.reply()` |
| `replyTo` | Optional hint for the intended recipient plugin ID |
| `traceId` | Distributed tracing transaction identifier |
| `spanId` | Distributed tracing execution step identifier |

This single envelope means every part of the system can handle any event without knowing where it came from or where it is going.

---

## Concept 2 — Unix Domain Socket Transport

The physical communication between the Kernel and plugins uses a **Unix Domain Socket (UDS)** — a special file on disk (`/tmp/mk8/kernel.sock`) that acts as a high-speed channel between processes on the same machine.

UDS is faster than TCP because it bypasses the network stack entirely. Each plugin is a separate OS process, but they all communicate as if sharing memory.

### Frame Protocol

A raw byte stream has no message boundaries. MK8 solves this with a **length-prefixed framing protocol**:

```
┌─────────────────────────┬───────────────────────────────────────┐
│  Length Header (4 bytes) │  Payload (N bytes)                    │
│  Big-endian 32-bit int   │  UTF-8 encoded JSON KernelEvent       │
└─────────────────────────┴───────────────────────────────────────┘
```

The reader reads exactly 4 bytes to get the payload size N, then reads exactly N bytes to get the complete JSON string. This eliminates all fragmentation and delimiter-parsing overhead.

---

## Concept 3 — Publish/Subscribe Routing

The Kernel maintains a routing table: `event type → list of connections`. When an event arrives, it looks up the type and delivers it to every subscriber.

```
"data.item"     → [consumer-1, consumer-2]
"chat.response" → [console]
"plugin.ready"  → [producer, demo-runner]
```

This is **publish/subscribe**: the publisher does not know who receives the event. The consumer does not know who published it. The Kernel is the only intermediary.

Beyond exact matching, plugins can subscribe using **wildcard prefix patterns**: subscribing to `logs.*` delivers `logs.info`, `logs.error`, `logs.warn`, and any other event whose type starts with `logs.`.

Direct point-to-point delivery is also supported via `message.<pluginId>` — an event of type `message.summary-agent` is delivered only to the `summary-agent` plugin, bypassing broadcast entirely.

---

## Concept 4 — Capabilities and Named Invocation

Above pub/sub sits a more powerful abstraction: **capabilities**. Instead of publishing to a fixed address, a plugin declares *what it knows how to do*:

```
"text.wordcount" → the word-count plugin provides this
"text.analyze"   → the summary-agent provides this
"chat.respond"   → the assistant agent provides this
```

When a caller wants to use a capability, it publishes a `capability.invoke` event with the capability name. The `CapabilityInterceptor` discovers who is registered and routes the request — without the caller needing to know the provider's plugin ID.

This is like calling a taxi service: you request a taxi, not the specific car with plate XYZ-1234.

### Capability Registration Flow

```
Plugin starts
    ↓
PluginBase sends "capability.register" { name, pluginId, triggerEvent, bidWeight }
    ↓
CapabilityInterceptor adds to live registry
    ↓
capability.invoke { name: "text.wordcount" } arrives
    ↓
CapabilityInterceptor looks up registry, routes to registered provider
```

---

## Concept 5 — Async Request-Reply via `correlationId`

The system is fully asynchronous. When a plugin sends a request, it does not block waiting for the response on the same call stack. Instead:

1. The caller includes a unique `correlationId` in the `capability.invoke` event
2. The `CapabilityInterceptor` stores: `corrId → caller's pluginId`
3. The provider processes the request and returns `capability.result` with the same `corrId`
4. The `CapabilityInterceptor` reads the table and delivers the result back to the original caller

```
DemoRunner ──corrId="abc123"──► CapabilityInterceptor ──► SummaryAgent
           ◄─corrId="abc123"─── CapabilityInterceptor ◄── SummaryAgent
```

The `correlationId` is the thread that ties a request to its response in a stateless, asynchronous world. No blocking, no polling, no shared references between processes.

---

## Concept 6 — The Interceptor Pipeline

Before any event reaches its subscribers through broadcast, it passes through a **sequential chain of interceptors**. Each interceptor can:

- **Observe** and let the event pass — return `false`
- **Consume** the event and stop all further routing — return `true`

The current chain:

```
Incoming event
      ↓
[IdempotencyInterceptor]   ← cache hit? collapse duplicate?
      ↓
[CapabilityInterceptor]    ← resolve provider, run auction, spawn if needed
      ↓
[PluginManager]            ← handle lifecycle events
      ↓
Broadcast to subscribers
```

Think of it like an airport: the event is a passenger passing through immigration (idempotency), then check-in (capability routing), then the gate (plugin lifecycle) — only then does it board the plane (broadcast to subscribers).

Interceptors are **completely decoupled**: they never import each other. They coordinate only through events on the bus.

---

## Concept 7 — Live Bidding Auctions

When **multiple plugins** offer the same capability, the Kernel does not choose arbitrarily. It runs a **real-time auction**:

1. Broadcasts `capability.bid.request` to all registered candidates
2. Each candidate replies with its `score` (configured bid weight) and `load` (current busyness)
3. The Kernel computes the **effective score**: `score × (1 − load)`
4. Routes the invocation to the winner

$$\text{Effective Score} = \text{score} \times (1.0 - \text{load})$$

This enables **natural load balancing**: a busier instance automatically bids less, steering traffic toward the most available provider. The auction window is 500ms; if all candidates bid before the timeout, the auction resolves immediately.

---

## Concept 8 — On-Demand Plugin Lifecycle

Plugins do not need to run all the time. The `PluginManager` manages two lifecycle modes:

**Persistent**: starts with the system, never stopped automatically. Used for agents, UIs, and infrastructure.

**On-demand**: stays dead until its capability is invoked. When a `capability.invoke` arrives for a capability with no live provider, the sequence is:

```
invoke arrives → no live provider found
    ↓
invoke queued in pendingInvokes
    ↓
system.plugin.spawn published
    ↓
PluginManager spawns child process via ProcessBuilder
    ↓
plugin registers its capabilities
    ↓
pendingInvokes queue drained → all waiting requests delivered
    ↓
[idle for idleTimeoutSeconds]
    ↓
PluginManager kills the process
```

This saves memory and CPU for tools that are rarely used. The idle check runs every 60 seconds.

---

## Concept 9 — Idempotency and Single-Flight Collapsing

Two classic distributed systems problems, solved at the kernel level:

### Sliding-Window Cache (Idempotency)

If the same request arrives twice with the same `correlationId` — whether due to a retry, a network hiccup, or a bug — the second request is answered instantly from cache. Real processing happens only once.

Cache entries expire after a 5-minute TTL managed by a background scheduler.

### Single-Flight Collapsing

If an identical request arrives *while the first is still being processed* (both in-flight concurrently), the duplicate is collapsed: it is queued alongside the first. When the single result arrives, all waiting callers receive it simultaneously — but the downstream work was executed exactly once.

```
Request #1 ──────────────── processing ───────────────► result delivered
Request #2 (duplicate) ──┘  (collapsed, waits)         ► same result
Request #3 (later)     ──── cache hit ─────────────────► result in < 1ms
```

The collapsing logic uses an atomic `compute()` block on a `ConcurrentHashMap` to close the race window between a cache miss and an in-flight registration — no explicit locks required.

---

## Concept 10 — Convention-Based Discovery

No central configuration file. No annotation scanning. No service registry.

**Plugin discovery**: `PluginManager` walks the project directory tree at boot looking for `plugin.json` files. Every file found is parsed and its capabilities are indexed. Hot reloading is triggered by `plugin.installed` or `system.catalog.refresh` events.

**Interceptor loading**: interceptors are named as positional CLI arguments. The Kernel loads them by class name via reflection, trying constructor signatures in order:

```
1. Interceptor(KernelBus, KernelConfig)
2. Interceptor(KernelBus)
3. Interceptor()
```

**Project root discovery**: `Start.java` walks up from the current working directory looking for `kernel/Kernel.java`. All paths are resolved absolutely from there — you can run from any directory inside the project tree.

---

## Technique 1 — Virtual Threads (Java 21+)

All concurrent I/O uses **Java Virtual Threads** — extremely lightweight threads managed by the JVM, not the OS. Each plugin connection gets its own dedicated reader virtual thread. Each incoming event is dispatched in a fresh virtual thread.

The result: the system handles hundreds of concurrent connections with minimal resource consumption. The code reads as straightforward sequential logic — no callbacks, no futures, no reactive chains — but executes concurrently underneath.

---

## Technique 2 — Producer-Consumer Per Connection

Every connected plugin has three components:

```
[Reader virtual thread] → reads frames from socket
                       → calls Kernel.route()
                       → route() calls enqueue()

[writeQueue: LinkedBlockingQueue<byte[]>] ← receives frames from routing

[Writer virtual thread] → drains the queue
                        → writes frames to socket
```

Reading and writing are fully decoupled. The Kernel never blocks waiting for a slow plugin — it enqueues the outbound frame and moves on immediately. The writer drains the queue at whatever pace the socket allows.

---

## Technique 3 — Lock-Free Concurrency

Shared state across virtual threads is managed with `java.util.concurrent` structures — no explicit `synchronized` blocks on data structures:

| Structure | Used for |
|---|---|
| `ConcurrentHashMap` | All shared maps — thread-safe without global locks |
| `CopyOnWriteArrayList` | Provider lists, caller lists — safe for concurrent reads |
| `map.compute(key, ...)` | Atomic check-and-act — closes race windows between read and write |
| `ConcurrentHashMap.newKeySet()` | Concurrent sets (e.g. spawning guard) |
| `AtomicBoolean` | Single-execution guards (e.g. `plugin.ready` initialization) |

The most important pattern is **atomic compute** — used in `IdempotencyInterceptor` to prevent race conditions between a cache miss and an in-flight registration:

```java
// Without atomic compute, two threads could both miss the cache
// and both register a new in-flight entry — causing duplicate processing.
inFlight.compute(corrId, (k, existing) -> {
    if (cache.get(corrId) != null) { /* serve from cache */ return null; }
    if (existing != null)          { existing.add(caller); return existing; } // collapse
    var first = new CopyOnWriteArrayList<>();
    first.add(caller);
    return first; // first execution — register in-flight
});
```

---

## Technique 4 — Process Isolation with Log Capture

When `PluginManager` spawns an on-demand plugin, it uses `ProcessBuilder` and redirects stdout/stderr directly to a log file:

```java
new ProcessBuilder(command)
    .directory(pluginDir.toFile())
    .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
    .redirectErrorStream(true)
    .start();
```

Each plugin has its own isolated log file under the project's `logs/` directory:

```
logs/kernel.log
logs/word-count.log
logs/summary-agent.log
logs/assistant.log
```

Plugins write to `System.out` as normal. The OS-level capture is fully transparent — no logging framework, no shared log handler, no configuration inside the plugin.

---

## Characteristic — Total Decoupling

No component knows about the existence of any other component. `CapabilityInterceptor` does not import `PluginManager`. `SummaryAgent` does not import `WordCountTool`. `ConsolePlugin` does not import `Agent`.

Components know each other only by the **event type names** they publish and consume. Replacing any piece with a different implementation requires changing no other file in the system.

This means:
- A capability can be served by a Java plugin today and a Python plugin tomorrow.
- Two implementations of the same capability can run simultaneously and the Kernel auctions between them.
- The Kernel itself has no knowledge of what any capability does — it only moves events.

---

## The Full Picture

```
                    ┌────────────────────────────────────────┐
                    │                KERNEL                  │
                    │                                        │
Plugin A ──UDS───►  │  ┌──────────────────────────────────┐ │
                    │  │       Interceptor Chain           │ │
Plugin B ──UDS───►  │  │  [IdempotencyInterceptor]         │ │  cache / collapse
                    │  │           ↓                       │ │
Plugin C ──UDS───►  │  │  [CapabilityInterceptor]          │ │  registry / auction
                    │  │           ↓                       │ │
                    │  │  [PluginManager]                  │ │  spawn / kill
                    │  │           ↓                       │ │
                    │  │  [Broadcast / Direct Delivery]    │ │
                    │  └──────────────────────────────────┘ │
                    │       ↓           ↓           ↓       │
                    └───────┼───────────┼───────────┼───────┘
                           UDS         UDS         UDS
                            │           │           │
                        Plugin A    Plugin B    Plugin C
```

Every plugin is an independent OS process. The Kernel is the only meeting point. Events flow in, are filtered, routed, cached, and delivered — and each plugin sees only what it subscribed to.
