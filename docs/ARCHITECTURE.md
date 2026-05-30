# System Architecture and Class Reference

This document maps the package organization, class responsibilities, and core method signatures implemented in the MK8 MicroKernel.

---

## 1. Class Organization

All primary microkernel classes reside inside the `kernel` directory, sharing a single package level to maximize execution speed and simplify compilation via JBang:

```
kernel/
├── Kernel.java                 # UDS Server, connection manager, routing maps
├── Event.java                  # Unified envelope, frame protocol, configuration loader
├── BasePlugin.java             # Connection bootstrap, virtual thread dispatcher
├── PluginManager.java          # Plugin discovery, catalog, lifecycle (spawn, kill, track)
├── CapabilityIndex.java        # Live registry, bid request broker, auction scheduler
└── IdempotencyInterceptor.java  # Deduplication cache, collapsing set manager
```

---

## 2. Interface Specifications

Decoupling between UDS infrastructure and extension logic is achieved via three core interfaces.

### `EventInterceptor`
Interceptors hook directly into the event bus pipeline inside `Kernel.route()`.
- `boolean intercept(Event event, String json) throws Exception`
  - **Return `true`**: Consumes the event, halting the interceptor chain and skipping standard routing/broadcast.
  - **Return `false`**: Executes side-effects, allowing subsequent interceptors and standard routing to execute.

### `KernelBus`
Provides a safe interface for interceptors to re-publish events into the bus or push frames directly to specific connections.
- `void sendTo(String pluginId, String json)`: Delivers a payload directly to a client's write queue, bypassing routing.
- `void route(Event event) throws Exception`: Serializes and pushes an event through the full Kernel routing pipeline.
- `void removePendingRoute(String corrId)`: Removes a correlationId from the return-routing table. Used by `IdempotencyInterceptor` after it manually delivers a result, to prevent memory leaks in `pendingRoutes`.

### `PluginRuntime`
The contract between `CapabilityIndex` and `PluginManager`. `CapabilityIndex` depends only on this interface — it never references `PluginManager` directly.

```java
interface PluginRuntime {
    // Catalog
    void awaitReady(long timeoutMs);
    PluginManager.CatalogEntry getByCapName(String capName);
    Collection<PluginManager.CatalogEntry> allEntries();
    void refresh();
    // Lifecycle
    void spawnOnDemand(String capabilityName) throws Exception;
    void trackUsage(String capabilityName);
    String listPlugins() throws Exception;
}
```

---

## 3. Core Infrastructure Classes

### `Kernel.java`
The gateway server managing network sockets and connections.
- **Key Fields**:
  - `exactRoutes`: Maps event type strings to connection arrays.
  - `prefixRoutes`: Tracks wildcard prefix routes (`*` patterns).
  - `byPluginId`: Maps active plugin identifiers to UDS connection handles.
  - `pendingRoutes`: Maps `correlationId` to the source plugin ID for reply routing.
  - `interceptors`: Immutable `List<EventInterceptor>` wired at boot: `[idempotency, capIdx]`.
  - `KERNEL_SOURCE`: Synthetic `Connection` used when the kernel re-enters its own routing pipeline (no writer thread started).
- **Key Methods**:
  - `main(String[] args)`: Boots the kernel, wires `PluginManager` + `CapabilityIndex`, starts the background scan, and runs the socket accept-loop.
  - `start()`: Binds the UDS socket, assembles the interceptor chain, and accepts connections.
  - `route(Event event, String json, Connection source)`: Evaluates destinations based on event types, manages `pendingRoutes`, executes interceptors, and pushes frames.
  - `findProjectRoot()`: Resolves project root by searching parent directories for the `kernel` directory or a `Start.java` anchor.

### `Event.java`
Defines the structure of messages and physical framing.
- **Key Records**:
  - `Event`: Represents the event envelope record.
  - `PluginConfig`: Models configuration properties declared in `plugin.json`.
- **Key Methods**:
  - `readFrame(InputStream in)`: Reads a 4-byte header and parses the specified payload size from a UDS stream.
  - `writeFrame(OutputStream out, String json)`: Encapsulates a JSON payload into a length-prefixed frame.

### `BasePlugin.java`
Removes repetitive client connection and registration boilerplate.
- **Key Methods**:
  - `run(String configPath, String socketPath, EventHandler handler)`: Boots plugin connection, runs standard capacity registrations, and dispatches received events concurrently using virtual threads.
  - `publish(Event event, OutputStream out)`: Stringifies and delivers an event through the connection stream.

---

## 4. Interceptor Reference

The interceptor chain contains exactly two interceptors, executed in order for every `capability.invoke`:

```
[IdempotencyInterceptor] → [CapabilityIndex]
```

`PluginManager` is **not** an interceptor. It is a pure infrastructure service wired directly to `CapabilityIndex` via the `PluginRuntime` interface.

### `IdempotencyInterceptor.java`
Acts as the entry barrier for event deduplication and collapsing.
- **Key Fields**:
  - `cache`: Map storing `correlationId` to resolved `Event` outcomes.
  - `inFlight`: Map tracking `correlationId` to sets of waiting caller plugin IDs.
- **Key Methods**:
  - `intercept(Event event, String json)`: Directs `capability.invoke` to cache lookups or collapsing queues, and intercepts `capability.result` / `capability.error` to cache and manually distribute outputs.

### `CapabilityIndex.java`
Maintains live capability provider state and manages auctions.
- **Key Fields**:
  - `registrations`: Maps capability names to active `Registration` records (live providers only).
  - `pendingInvokes`: Queues `capability.invoke` events while on-demand providers are spawning.
  - `auctions`: Tracks in-flight `AuctionContext` objects by auction UUID.
- **Key Methods**:
  - `intercept(Event event, String json)`: Directs invoke queries, processes `capability.register` / `unregister` side-effects, and schedules auctions.
  - `setRuntime(PluginRuntime r)`: Wires the `PluginRuntime` implementation (called once at boot by `Kernel`).
  - `handleInvoke(Event event)`: Routes to a live provider, falls back to catalog via `PluginRuntime` for persistent/on-demand plugins, or queues pending invokes and calls `runtime.spawnOnDemand()`.
  - `startAuction(String capName, List<Registration> providers, Event invokeEvent)`: Broadcasts `capability.bid.request` and schedules resolution after **500ms**.
  - `resolveAuction(String auctionId)`: Determines the winning provider (highest `score × (1 - load)`) and forwards the queued invocation.

---

## 5. Infrastructure Service

### `PluginManager.java`
Single source of truth for plugin discovery and lifecycle. Merges catalog scanning (previously `PluginCatalog`) and process management (previously `ProcessManager`) into one cohesive class. Implements `PluginRuntime`.

- **Key Records**:
  - `CatalogEntry`: One entry per capability declaration. Fields: `pluginId`, `pluginDir`, `capabilityName`, `triggerEvent`, `onDemand`, `persistent`, `bidWeight`, `idleTimeoutSeconds`, `launchCommand[]`.
  - `ManagedProcess`: Tracks a running child process. Fields: `pluginId`, `pid`, `pluginDir`, `process`.
- **Key Fields**:
  - `byCapName`: Maps capability name → `CatalogEntry`.
  - `byPluginId`: Maps plugin ID → `List<CatalogEntry>`.
  - `managed`: Maps plugin ID → `ManagedProcess` (running processes).
  - `lastUsed`: Tracks last-used timestamp per plugin for idle-kill checks.
  - `spawning`: Set of plugin IDs currently being launched (prevents double-spawn).
- **Key Methods**:
  - `scan()`: Walks directories beneath the resolved project root, ignores the `kernel` path, parses `plugin.json` configurations, and populates index mappings. Called in a virtual thread at boot.
  - `spawnOnDemand(String capName)`: Looks up the `CatalogEntry`, builds `ProcessBuilder` with `redirectOutput(Redirect.appendTo(logFile)).redirectErrorStream(true)`, and binds process-exit callbacks.
  - `trackUsage(String capName)`: Updates `lastUsed` for the owning plugin.
  - `checkIdlePlugins()`: Invoked every 60 seconds to terminate on-demand plugins exceeding their `idleTimeoutSeconds`. Calls `terminatePlugin()` directly — no bus events.
  - `listPlugins()`: Returns a JSON array of all managed processes and their status.
  - `refresh()`: Synchronously re-scans disk on `plugin.installed`.
