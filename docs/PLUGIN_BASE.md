# PluginBase Reference

The `PluginBase.java` class serves as the standardized, reusable connector framework for all plugins in the MK8 MicroKernel ecosystem. It abstracts away the low-level Unix Domain Socket (UDS) connection logic, Jackson JSON parsing, event-loop execution, dynamic registration, and bidding auto-responses.

---

## 1. Core Responsibilities

`PluginBase` encapsulates several vital integration functions:
1. **Dynamic Connection Bootstrap (`connectAndRun`):** Establishes the connection to `/tmp/mk8/kernel.sock`, loads the local `plugin.json` schema, and starts the event loop.
2. **Dynamic Capability Registration:** Parses the capabilities declared in `plugin.json` and automatically publishes `capability.register` events to the Kernel immediately upon connection.
3. **Automated Bidding Brokerage (`handleBidAuto`):** Automatically intercepts `capability.bid.request` events and replies with a calculated `capability.bid.response` using the `bidWeight` declared in the plugin manifest.
4. **Thread-Safe KernelEvent Dispatching:** Dispatches each incoming JSON event frame to a dedicated callback executor using Java 21 Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`).

---

## 2. API Reference and Method Signatures

### A. Execution Entry Points

#### `public static void run(String configPath, String socketPath, EventHandler handler)`
* **Description:** Connects to the UDS, registers capabilities, and processes incoming event frames asynchronously. Each received event is executed in a separate, isolated Virtual Thread.
* **Arguments:**
  * `configPath`: Relative path to `plugin.json`.
  * `socketPath`: Path to the Unix Domain Socket (defaults to `/tmp/mk8/kernel.sock`; override with `-Dmk8.socket=…`).
  * `handler`: The custom functional callback interface `EventHandler` mapping events to plugin logic.

---

### B. Messaging and Publishing

#### `public static void publish(KernelEvent e, OutputStream out)`
* **Description:** Thread-safe method that synchronizes on the output stream of the socket and transmits the serialized JSON event frame to the Kernel.
* **Arguments:**
  * `e`: The serialized `KernelEvent` record.
  * `out`: The active `OutputStream` of the UDS socket.

#### `public static void publishSafe(KernelEvent e, OutputStream out)`
* **Description:** Identical to `publish()`, but catches and suppresses any IOExceptions. Used in telemetry or logs dispatching where a failure must not interrupt the plugin's main execution loop.

---

## 3. Asynchronous Execution Lifecycle Flow

The flow diagram below details how the `PluginBase` handles connection bootstrap and incoming UDS frames using Virtual Threads:

```
        Plugin Startup
              │
              ▼
    [Load plugin.json Schema]
              │
              ▼
 [Establish Socket Connection to UDS]
              │
              ▼
  [Register Capabilities on Event Bus]
              │
              ▼
    ┌───► [Read UDS Stream Frame]
    │             │
    │             ├─► [EOF / Null] ─────► [Close Streams & Exit]
    │             │
    │             └─► [Valid KernelEvent Frame]
    │                         │
    │                         ▼
    │           [Spawn Virtual Thread Task]
    │             ├───► IF "capability.bid.request" ─────► [handleBidAuto()]
    │             │                                           │
    │             │                                           ▼
    │             │                                       [Reply capability.bid.response]
    │             │
    │             └───► ELSE ─────────────────────────────► [Execute custom EventHandler]
    │                                                         │
    │                                                         ▼
    │                                                     [Run custom plugin logic]
    │ └─────────────────────────┘
```

---

## 4. PluginBoot — UDS Connection Bootstrap

`PluginBoot` is a package-private companion class defined in `PluginBase.java`. It handles the low-level sequence of opening the UDS socket and performing the two mandatory registration frames before handing control to plugin logic.

### `static void connectAndRun(String socketPath, PluginConfig config, PluginLogic logic)`
Opens a `SocketChannel` to the given UDS path, then:
1. Reads the current process PID via `ProcessHandle.current().pid()` and injects it as the `"pid"` field into the raw config JSON node.
2. Writes a `plugin.register` frame carrying the full `plugin.json` content (including `pid`) so the Kernel can register subscription routes and the `byPluginId` index.
3. Writes a `plugin.ready` frame with `{id, pid}` so other plugins waiting on `plugin.ready` events know the plugin is live.
4. Delegates to `logic.run(in, out)` — the event loop provided by `PluginBase.run()`.

`PluginBoot.PluginLogic` is a `@FunctionalInterface` with signature `void run(InputStream in, OutputStream out) throws Exception`.

---

## 5. Trace Context Propagation

`PluginBase.run()` sets two `ThreadLocal` values from `KernelEvent` on every virtual thread before calling the plugin's `EventHandler`:

```java
KernelEvent.CURRENT_TRACE_ID.set(ev.traceId());   // from incoming frame
KernelEvent.CURRENT_SPAN_ID.set(ev.spanId());      // from incoming frame
```

Both are cleared in the `finally` block after the handler returns, preventing context leakage across virtual thread reuse. Downstream publish calls in the same thread automatically pick up these values via `KernelEvent.of()` / `KernelEvent.withCorrelation()`.

The `publish()` method synchronizes on the shared `OutputStream` to ensure concurrent virtual threads cannot interleave partial frames:
```java
synchronized (out) { KernelEvent.writeFrame(out, ...); }
```

---

## 6. Bidding Auto-Response Mechanism

When the Kernel holds a dynamic bidding auction to route an invoke, it publishes a `capability.bid.request`. `PluginBase` intercepts this event automatically, reads the requested capability name, matches it against the capability registry indexed from the local `plugin.json` schema, and replies to the Kernel with the pre-configured `bidWeight` (the bidding score):

```java
config.capabilities().stream()
      .filter(c -> c.path("name").asText("").equals(capName))
      .findFirst()
      .ifPresent(cap -> {
          String bid = KernelEvent.MAPPER.writeValueAsString(Map.of(
              "agentId",       config.id(),
              "score",         cap.path("bidWeight").asDouble(1.0),
              "load",          0.0,
              "correlationId", corrId
          ));
          publish(KernelEvent.of("capability.bid.response", bid, config.id()), out);
      });
```
This ensures that plugins participate in dynamic capability auctions without requiring custom boilerplate code in their business logic.
