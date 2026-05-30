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
├── PluginCatalog.java          # File-tree JSON plugin registry and scanner
├── CapabilityIndex.java        # Live registry, bid request broker, auction scheduler
├── ProcessManager.java         # Lifecycle controller, process launcher, stream logger
└── IdempotencyInterceptor.java  # Deduplication cache, collapsing set manager
```

---

## 2. Interface Specifications

Decoupling between UDS infrastructure and extension logic is achieved via two core interfaces.

### `EventInterceptor`
Interceptors hook directly into the event bus pipeline inside `Kernel.route()`.
- `boolean intercept(Event event, String json) throws Exception`
  - **Return `true`**: Consumes the event, halting the interceptor chain and skipping standard routing/broadcast.
  - **Return `false`**: Executes side-effects, allowing subsequent interceptors and standard routing to execute.

### `KernelBus`
Provides a safe interface for interceptors to re-publish events into the bus or push frames directly to specific connections.
- `void sendTo(String pluginId, String json)`: Delivers a payload directly to a client's write queue, bypassing routing.
- `void route(Event event) throws Exception`: Serializes and pushes an event through the full Kernel routing pipeline.

---

## 3. Core Infrastructure Classes

### `Kernel.java`
The gateway server managing network sockets and connections.
- **Key Fields**:
  - `exactRoutes`: Maps event type strings to connection arrays.
  - `prefixRoutes`: Tracks wildcard prefix routes (`*` patterns).
  - `byPluginId`: Maps active plugin identifiers to UDS connection handles.
  - `pendingRoutes`: Maps `correlationId` to the source plugin ID for reply routing.
- **Key Methods**:
  - `main(String[] args)`: Binds UDS socket, starts the `PluginCatalog` scanning thread, constructs the interceptor chain, and runs the socket accept-loop.
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
  - `connectAndRun(String socketPath, PluginConfig config, PluginLogic logic)`: Connects UDS socket, handshakes `plugin.register` and `plugin.ready`, and runs client handler loops.

### `BasePlugin.java`
Removes repetitive client connection and registration boilerplate.
- **Key Methods**:
  - `run(String configPath, String socketPath, EventHandler handler)`: Boots plugin connection, runs standard capacity registrations, and dispatches received events concurrently using virtual threads.
  - `publish(Event event, OutputStream out)`: Stringifies and delivers an event through the connection stream.

---

## 4. Interceptor Reference (Option B Core)

### `IdempotencyInterceptor.java`
Acts as the entry barrier for event deduplication and collapsing.
- **Key Fields**:
  - `cache`: Map storing `correlationId` to resolved `Event` outcomes.
  - `inFlight`: Map tracking `correlationId` to sets of waiting caller plugin IDs.
- **Key Methods**:
  - `intercept(Event event, String json)`: Directs `capability.invoke` to cache lookups or collapsing queues, and intercepts `capability.result` / `capability.error` to cache and manually distribute outputs.

### `CapabilityIndex.java`
Maintains live capability provider state and manages leilões.
- **Key Fields**:
  - `registrations`: Maps capability names to active providers.
  - `pendingInvokes`: Queues invocations while on-demand providers are spawning.
- **Key Methods**:
  - `intercept(Event event, String json)`: Directs invoke queries, processes `capability.register` / `unregister` side-effects, and schedules auctions.
  - `startAuction(String capName, List<Registration> providers, Event invokeEvent)`: Broadcasts `capability.bid.request` and schedules resolution after 500ms.
  - `resolveAuction(String auctionId)`: Determines the winning provider and forwards the queued invocation.

### `ProcessManager.java`
Coordinates child process spawning and monitoring.
- **Key Fields**:
  - `managed`: Map of active `ManagedProcess` records tracking PID and execution handles.
  - `lastUsed`: Tracks invocation timestamps for idle-kill checks.
- **Key Methods**:
  - `intercept(Event event, String json)`: Intercepts `plugin.load` and `agent.spawn` lifecycle commands.
  - `handlePluginLoad(Event event)`: Launches on-demand tools, redirects streams to `logs/{pluginId}.log`, and binds process-exit callbacks.
  - `checkIdlePlugins()`: Invoked periodically to terminate on-demand tools exceeding their idle thresholds.

### `PluginCatalog.java`
Walks and indexes static plugin capabilities on disk.
- **Key Methods**:
  - `scan()`: Walks directories beneath the resolved project root, ignores the `kernel` path, parses `plugin.json` configurations, and populates index mappings.
  - `refresh()`: Synchronously re-scans disk structures upon new plugin installations.
