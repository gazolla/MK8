# System Architecture and Class Reference

This document maps the package organization, class responsibilities, and core method signatures implemented in the MK8 MicroKernel.

---

## 1. Class Organization

Primary microkernel classes reside inside the `kernel` directory, structured into pluggable subpackages to maintain strict architectural separation, while execution paths remain fully asynchronous:

```
kernel/
├── Kernel.java                        # UDS Server, connection manager, convention instantiator, core interfaces
├── KernelEvent.java                   # Unified envelope, frame protocol, logging infrastructure
└── interceptors/                      # Pluggable interceptor packages
    ├── capability/
    │   └── CapabilityInterceptor.java # Live registry, bid request broker, auction scheduler
    ├── idempotency/
    │   └── IdempotencyInterceptor.java# Deduplication cache, collapsing set manager
    └── plugin/
        ├── PluginBase.java            # Connection bootstrap, virtual thread dispatcher
        ├── PluginConfig.java          # plugin.json mapping (typed record accessors)
        └── PluginManager.java         # Plugin discovery, catalog, lifecycle spawner
```

All runnable verification scenarios and end-user interactive modules reside inside the `projects/` directory:

```
projects/
├── SimpleProject/                     # Direct raw UDS socket producer/consumer loop
├── PluginProject/                     # Persistent plugins controlled via PluginManager
├── InterceptorsProject/               # Performance verification demo with full interceptors stack
└── ChatAI/                            # Conversational console terminal linked to LangChain4j Agent
```

---

## 2. Interface Specifications

Decoupling between UDS infrastructure and extension logic is achieved via core interfaces implemented by interceptors.

### `EventInterceptor`
Interceptors hook directly into the event bus pipeline inside `Kernel.route()`.
- `boolean intercept(KernelEvent event, String json) throws Exception`
  - **Return `true`**: Consumes the event, halting the interceptor chain and skipping standard routing/broadcast.
  - **Return `false`**: Executes side-effects, allowing subsequent interceptors and standard routing to execute.
- `default boolean handles(String eventType)`: Returns `true` if this interceptor handles this event type. Used by the Kernel to pre-filter events.
- `default Set<String> publishes()`: Declares which event types this interceptor produces (used for boot logging).
- `default Set<String> subscribes()`: Declares which event types this interceptor consumes (used for boot logging).

### `KernelBus`
Provides a safe interface for interceptors to re-publish events into the bus or push frames directly to specific connections.
- `void sendTo(String pluginId, String json)`: Delivers a payload directly to a client's write queue, bypassing routing.
- `void route(KernelEvent event) throws Exception`: Serializes and pushes an event through the full Kernel routing pipeline.
- `void addPendingRoute(String corrId, String sourcePluginId)`: Stores a correlation path mapping.
- `String removePendingRoute(String corrId)`: Removes a correlation ID from the return-routing table (used to prevent memory leaks).

### `InterceptorLifecycle`
Used by interceptors requiring a background task (e.g. running a directory scan thread or idle checker scheduler) initiated at boot time.
- `void onStart()`: Triggered by the Kernel immediately after instantiating the interceptor class.

---

## 3. Core Infrastructure Classes

### `Kernel.java`
The gateway server managing network sockets and connections. It is a pure, generic event bus that discovers and runs interceptors dynamically from command line arguments.
- **Convention-Based Instantiation**: Positional CLI arguments map directly to classpath class names (e.g. `IdempotencyInterceptor`, `CapabilityInterceptor`, `PluginManager`). The Kernel instantiates them by trying constructors in order: `(KernelBus, KernelConfig) -> (KernelBus) -> ()`.
- **Key Fields**:
  - `exactRoutes`: Maps event type strings to connection arrays.
  - `prefixRoutes`: Tracks wildcard prefix routes (`*` patterns).
  - `byPluginId`: Maps active plugin identifiers to UDS connection handles.
  - `pendingRoutes`: Maps `correlationId` to the source plugin ID for reply routing.
  - `interceptors`: Pluggable `List<EventInterceptor>` resolved at boot.
- **Key Methods**:
  - `main(String[] args)`: Boots the kernel, parses socket paths, logs directories, scans roots, dynamically loads interceptor classes, and triggers `onStart()` hooks.
  - `start(List<EventInterceptor> interceptorList)`: Binds the UDS socket and starts the accepted client connection loops.
  - `route(KernelEvent event, String json, Connection source)`: Executes the interceptor chain sequentially (pre-filtered via `handles()`) and broadcasts if not consumed.

### `KernelEvent.java`
Defines the structure of messages, UDS length-prefixed framing, and shared logging infrastructure.
- **Key Records**:
  - `KernelEvent`: Unified event envelope record containing routing metadata fields (`correlationId`, `sessionId`, `workflowId`, `replyTo`, `traceId`, `spanId`).
- **Key Methods**:
  - `readFrame(InputStream in)`: Decodes big-endian headers and isolates JSON payloads.
  - `writeFrame(OutputStream out, String json)`: Encapsulates JSON strings into length-prefixed frames.
  - `initLogging()`: Installs `TimestampPrintStream` on `System.out` and `System.err`. Safe to call multiple times — guarded by a system property flag.
- **`TimestampPrintStream`** (inner class): A `PrintStream` decorator that prepends every output line with a `[yyyy-MM-dd HH:mm:ss.SSS]` timestamp. Installed automatically at class init and by `initLogging()`. Plugins should call `KernelEvent.initLogging()` explicitly at the top of `main()` instead of relying on static initializer ordering.
- **ThreadLocals**:
  - `CURRENT_TRACE_ID`: Per-virtual-thread holder for the active `traceId`.
  - `CURRENT_SPAN_ID`: Per-virtual-thread holder for the active `spanId`. Both are set by `PluginBase` when dispatching incoming frames and cleared on thread exit.

---

## 3b. Supporting Infrastructure Types

### `Connection`
Manages a single plugin's socket channel and outbound write queue. One `Connection` is created per accepted UDS client in `Kernel.handleClient()`.
- **Fields**: `pluginId` (String), `channel` (SocketChannel), `writeQueue` (LinkedBlockingQueue<byte[]>).
- **Writer Thread**: The constructor starts a dedicated Virtual Thread (`runWriter`) that blocks on `writeQueue.take()` and writes each byte frame to the socket `OutputStream`. If `channel` is `null` (the synthetic `KERNEL_SOURCE` connection), the writer thread is not started.
- **`POISON` Sentinel**: A static `byte[0]` constant. `shutdown()` puts it onto the `writeQueue`; when `runWriter` dequeues it, the writer thread exits cleanly.
- **`shutdown()`**: Called by `Kernel.unregister()` to signal the writer thread to stop after all queued frames have been delivered.

### `PrefixRoute`
A lightweight record pairing a prefix string with its target `Connection`. Stored in `Kernel.prefixRoutes` (`CopyOnWriteArrayList<PrefixRoute>`). During broadcast, the Kernel iterates this list and enqueues any event whose `type` starts with the stored prefix.

### `KernelConfig`
A record (`Path scanRoot, Path logsOverride`) resolved at boot and passed to interceptor constructors declared as `(KernelBus bus, KernelConfig config)`. `scanRoot` is the project root used by `PluginManager` for `plugin.json` discovery; `logsOverride` is an optional explicit log directory.

### `KernelBusImpl`
The concrete implementation of `KernelBus` backed by a `Kernel` instance. Provides interceptors with `sendTo()` (direct enqueue bypassing routing), `route()` (re-enters the full kernel pipeline with a synthetic `KERNEL_SOURCE` connection), `addPendingRoute()`, and `removePendingRoute()`.

---

## 4. Pluggable Interceptor Reference

Interceptors are executed sequentially inside the Kernel pipeline:

```
[IdempotencyInterceptor] → [CapabilityInterceptor] → [PluginManager]
```

Every interceptor is fully decoupled. In particular, `CapabilityInterceptor` and `PluginManager` have **zero direct code dependency** — they coordinate entirely via event broadcasts on the bus:

### `IdempotencyInterceptor.java`
Acts as the entry barrier for event deduplication and collapsing.
- **Atomic Collapsing**: Registers concurrent callers inside `inFlight.compute()` atomically to prevent race windows. Evicts sliding-window cache results automatically after their 5-minute TTL expires.

### `CapabilityInterceptor.java`
Maintains live capability provider state and coordinates auctions.
- **Event-Driven Catalog**: Instead of reading files directly, it subscribes to `system.catalog.entry` and `system.catalog.ready` events published by the `PluginManager` to compile its own local index.
- **Dynamic Spawn & Usage Requests**: Lacking direct code pointers to the manager, it publishes `system.plugin.spawn` to boot an on-demand plugin and `system.plugin.usage` to update its activity status.
- **Bidding Auctions**: Orchestrates bids from multiple providers via `capability.bid.request` and resolves the winner using:
  $$\text{Effective Score} = \text{score} \times (1.0 - \text{load})$$

### `PluginManager.java`
Single source of truth for plugin discovery, process lifecycle, and log redirection.
- **Pluggable Event Interceptor**: Scans the project root for `plugin.json` configurations at boot, broadcasts `system.catalog.entry` frames, and intercepts:
  - `system.plugin.spawn` to execute background child processes via `ProcessBuilder`.
  - `system.plugin.usage` to track usage timestamps.
  - `system.plugin.list.request` to dynamically reply with running processes, statuses, and PIDs (handling the `system.plugin.list` capability).
- **Idle-Kill & Stream Capture**: Destroys idle on-demand plugins exceeding their declared limits and redirects stdout/stderr streams to `logs/<pluginId>.log`.()`: Synchronously re-scans disk on `plugin.installed`.
