# System Concepts

This document outlines the architectural patterns and communication protocols implemented in the MK8 MicroKernel.

---

## 1. Event Bus and UDS Protocol

The system runs on an asynchronous event bus communicating via Unix Domain Sockets (UDS) located at `/tmp/mk7/kernel.sock`. 

### Length-Prefixed Frame Protocol
To guarantee message integrity without parsing streams line-by-line, the system uses a binary length-prefixed framing protocol:
- **Header**: 4-byte big-endian integer specifying the payload size in bytes.
- **Payload**: UTF-8 encoded JSON string matching the unified event envelope schema.

### Unified Event Envelope Schema
All transmissions must conform to the unified event record structure containing:
- `id`: Unique event identifier (UUID).
- `type`: Routing classifier (e.g., `capability.invoke`).
- `payload`: Internal event data stringified.
- `timestamp`: Date and time of generation.
- `source`: Plugin ID of origin.
- `correlationId`: Matching key for asynchronous request-reply tracking.
- `sessionId`: Session context identifier.
- `workflowId`: Execution path trace.
- `replyTo`: Future response destination.
- `traceId`: Context trace tracking.
- `spanId`: Step scope trace.

---

## 2. Capabilities and Live Auctions

Capabilities are declarative functions exposed by plugins. When multiple active plugins register identical capabilities, the kernel resolves incoming invocations using an automated real-time bidding auction.

### The Auction Engine Sequence
1. **Invocation**: A client requests execution via `capability.invoke` for a specific capability name.
2. **Identification**: If multiple active providers exist, the Kernel triggers an auction and broadcasts `capability.bid.request`.
3. **Response**: Providers reply within 500ms using `capability.bid.response` supplying:
   - `score`: Absolute qualification metric.
   - `load`: Current utilization factor (0.0 to 1.0).
4. **Resolution**: The winner is determined based on the highest effective score calculation:
   $$\text{Effective Score} = \text{score} \times (1.0 - \text{load})$$
   If no bids are returned, the kernel falls back to routing to the first registered candidate.

---

## 3. Idempotency and Request Collapsing

The kernel intercepts invocations at the gateway layer to eliminate duplicate processing and optimize external resource consumption.

### Cache Deduplication
When execution results (`capability.result` or `capability.error`) return, the Kernel caches them under their unique `correlationId` using a sliding-window cache. Subsequent identical invocations within 5 minutes retrieve the cached result instantly in **1ms**, bypassing the execution pipeline.

### Request Collapsing (Single-Flight)
If a duplicate invocation arrives while the original request is actively in-flight (e.g., while the target plugin is launching), the Kernel blocks routing the duplicate. Instead, it collapses the request by attaching the duplicate caller's connection to the execution. Once the single result returns, the Kernel duplicates and delivers it to all collapsed callers.

---

## 4. Plugin Lifecycle Modes

Plugins operate under two primary execution cycles declared in their `plugin.json` configurations:

### Persistent Mode
- Loaded on start or manually spawned.
- Remain active indefinitely.
- Do not shut down automatically on idle periods.

### On-Demand Mode
- Loaded dynamically by the `ProcessManager` only when their specific capability is invoked.
- Shut down automatically via a scheduled background task if they remain idle longer than their declared `idleTimeoutSeconds` threshold.

---

## 5. Asynchronous Distributed Tracing

To trace requests passing across process and socket boundaries, the Kernel propagates tracing metadata automatically.

- **Trace ID**: A global identifier unique to a specific transaction path.
- **Span ID**: A local identifier unique to an individual execution unit.

When `BasePlugin` receives a frame, it deserializes the tracing context, loads the IDs into a `ThreadLocal` structure within the virtual execution thread, and propagates them on any outbound events. The context is safely cleared upon thread termination.

---

## 6. Hybrid Messaging Model

The Kernel routing system combines two distinct routing topologies:

### Broadcast Pub/Sub (One-to-Many)
- Matches exact event types or suffix wildcards (e.g., `log.*`).
- Routes to all connected clients registered to listen to the pattern.
- Used for telemetry, logging, and infrastructure updates.

### Point-to-Point Reply Routing (One-to-One)
- Directs replies (`capability.result`) exclusively to the socket connection of the original caller.
- Resolves destinations using `pendingRoutes` maps keyed by `correlationId`.
- Eliminates broadcast noise and protects data isolation.

---

## 7. Twelve-Factor Log Redirection

The MicroKernel decouples logs generation from storage mechanics:
- **No Disk Writing inside Plugins**: Plugin developers simply print statements to standard output (`System.out`) or standard error (`System.err`).
- **OS-Level Capture**: The Kernel `ProcessManager` captures stdout/stderr of spawned processes at launch, multiplexes them, and redirects the output streams directly into dedicated `.log` files inside the project's root `logs/` directory.
