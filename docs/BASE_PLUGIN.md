# BasePlugin Reference

The `BasePlugin.java` class serves as the standardized, reusable connector framework for all plugins in the MK8 MicroKernel ecosystem. It abstracts away the low-level Unix Domain Socket (UDS) connection logic, Jackson JSON parsing, event-loop execution, dynamic registration, and bidding auto-responses.

---

## 1. Core Responsibilities

`BasePlugin` encapsulates several vital integration functions:
1. **Dynamic Connection Bootstrap (`connectAndRun`):** Establishes the connection to `/tmp/mk8/kernel.sock`, loads the local `plugin.json` schema, and starts the event loop.
2. **Dynamic Capability Registration:** Parses the capabilities declared in `plugin.json` and automatically publishes `capability.register` events to the Kernel immediately upon connection.
3. **Automated Bidding Brokerage (`handleBidAuto`):** Automatically intercepts `capability.bid.request` events and replies with a calculated `capability.bid.response` using the `bidWeight` declared in the plugin manifest.
4. **Thread-Safe Event Dispatching:** Dispatches each incoming JSON event frame to a dedicated callback executor using Java 21 Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`).

---

## 2. API Reference and Method Signatures

### A. Execution Entry Points

#### `public static void run(String configPath, String socketPath, EventHandler handler)`
* **Description:** Connects to the UDS, registers capabilities, and processes incoming event frames asynchronously. Each received event is executed in a separate, isolated Virtual Thread.
* **Arguments:**
  * `configPath`: Relative path to `plugin.json`.
  * `socketPath`: Path to the Unix Domain Socket (defaults to `/tmp/mk8/kernel.sock`; override with `-Dmk8.socket=…`).
  * `handler`: The custom functional callback interface `EventHandler` mapping events to plugin logic.

#### `public static void runSync(String configPath, String socketPath, EventHandler handler)`
* **Description:** Identical to `run()`, but processes incoming event frames sequentially in a single thread. Used when message ordering is critical (such as the Logger plugin writing sequential outputs to a file).

---

### B. Messaging and Publishing

#### `public static void publish(Event e, OutputStream out)`
* **Description:** Thread-safe method that synchronizes on the output stream of the socket and transmits the serialized JSON event frame to the Kernel.
* **Arguments:**
  * `e`: The serialized `Event` record.
  * `out`: The active `OutputStream` of the UDS socket.

#### `public static void publishSafe(Event e, OutputStream out)`
* **Description:** Identical to `publish()`, but catches and suppresses any IOExceptions. Used in telemetry or logs dispatching where a failure must not interrupt the plugin's main execution loop.

#### `public static void publishLog(String level, String message, String source, OutputStream out)`
* **Description:** Helper method to format and publish a unified log event (`log.info`, `log.error`, etc.) to the central Logger plugin.

---

## 3. Asynchronous Execution Lifecycle Flow

The flow diagram below details how the `BasePlugin` handles connection bootstrap and incoming frames using Virtual Threads:

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
    │             └─► [Valid Event Frame]
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
    └─────────────────────────┘
```

---

## 4. Bidding Auto-Response Mechanism

When the Kernel holds a dynamic bidding auction to route an invoke, it publishes a `capability.bid.request`. `BasePlugin` intercepts this event automatically, reads the requested capability name, matches it against the capability registry indexed from the local `plugin.json` schema, and replies to the Kernel with the pre-configured `bidWeight` (the bidding score):

```java
config.capabilities().stream()
      .filter(c -> c.path("name").asText("").equals(capName))
      .findFirst()
      .ifPresent(cap -> {
          String bid = Event.MAPPER.writeValueAsString(Map.of(
              "agentId",       config.id(),
              "score",         cap.path("bidWeight").asDouble(1.0),
              "load",          0.0,
              "correlationId", corrId
          ));
          publish(Event.of("capability.bid.response", bid, config.id()), out);
      });
```
This ensures that plugins participate in dynamic capability auctions without requiring custom boilerplate code in their business logic.
