# CapabilityInterceptor Reference

The `CapabilityInterceptor.java` class serves as the dynamic service registry and routing broker within the MK8 MicroKernel. It handles capabilities indexing, plugin provider registrations, dynamic bidding auctions, scoring evaluations, and route resolution.

---

## 1. Core Responsibilities

The `CapabilityInterceptor` manages the following structural tasks:
1. **Dynamic Registration:** Intercepts `capability.register` events emitted by newly connected plugins and adds their declared capabilities to the active index.
2. **Provider Tracking:** Keeps a thread-safe map of active capabilities and their registered providers, including parameters like `bidWeight` (bidding score) and tags.
3. **Bidding Auctions Coordination:** When a client invokes a capability (e.g. `text.wordcount`), if the capability has multiple registered providers, the `CapabilityInterceptor` manages a real-time auction, soliciting scores from active candidates.
4. **Scoring & Route Selection:** Evaluates bid responses, selects the best provider based on weight scores and load, and caches the routing path.

---

## 2. Structural Design and Thread Safety

`CapabilityInterceptor` is designed to be fully thread-safe, supporting concurrent registrations and lookups from high-performance virtual thread tasks:

* **`registrations` Map:** Maps `capabilityName` to a `CopyOnWriteArrayList<Registration>` of live providers. A `Registration` holds `pluginId`, `triggerEvent` (null for agents), and `bidWeight`.
* **`pendingInvokes` Map:** Maps `capabilityName` to a list of queued `capability.invoke` events waiting for an on-demand plugin to start and register.
* **`auctions` Map:** Maps an `auctionId` (UUID) to an `AuctionContext` holding the original event, candidate list, and collected bids. Auction timeout is enforced by a `ScheduledExecutorService` (500ms window), not a latch.

---

## 3. The Bidding and Auction Process

The flow diagram below details how the `CapabilityInterceptor` coordinates a real-time capability auction:

```
        Client Invokes Capability (e.g., "text.wordcount")
                           │
                           ▼
          [Check Active Provider Registry]
                           │
        ┌──────────────────┴──────────────────┐
        ▼ (Single Provider)                   ▼ (Multiple Providers)
  [Direct Route]                     [Initialize Auction Context]
        │                                     │
        ▼                                     ▼
 [Return Target PID]               [Publish "capability.bid.request"
                                    to all potential candidate tools]
                                              │
                                              ▼
                                   [Await ScheduledExecutorService
                                    500ms Timeout Window]
                                              │
                                              ├─► [Bid Received] ──► [Record Score]
                                              │
                                              ▼
                                    [Select Provider with
                                     Highest Weight Score]
                                              │
                                              ▼
                                     [Return Target PID]
```

---

## 4. Class API and Method Signatures

### A. EventInterceptor Entry Point

#### `boolean intercept(KernelEvent event, String json) throws Exception`
Routes to the appropriate handler based on `event.type()`. Returns `true` (consumed) for `capability.invoke`, `capability.unregister`, `capability.bid.response`, and `capability.query`. Returns `false` (side-effect only) for `capability.register`, `system.plugin.died/stopped`, and `plugin.installed`.

---

### B. Registration Management

#### `void handleRegister(KernelEvent event)`
* **Description:** Adds a new `Registration` to `registrations` for the declared capability. After registration, drains any `pendingInvokes` queued while the plugin was starting.

#### `void handleUnregister(KernelEvent event)`
* **Description:** Removes the specific `Registration` for the given `pluginId` from the `registrations` map.

#### `void handlePluginDied(KernelEvent event)`
* **Description:** Sweeps `registrations` and removes all entries whose `pluginId` matches the dead plugin.

---

### C. Routing and Auction Management

#### `void handleInvoke(KernelEvent event)`
* **Description:** Routes a `capability.invoke` to a live provider. If no live provider is found, consults the catalog via `PluginRuntime`: persistent plugins are re-routed via `triggerEvent`; on-demand plugins are queued in `pendingInvokes` and `runtime.spawnOnDemand(capName)` is called directly (no bus event).

#### `void setRuntime(PluginRuntime r)`
* **Description:** Wires the `PluginRuntime` implementation (called once at boot by `Kernel` after both `CapabilityInterceptor` and `PluginManager` are constructed). Until this is called, `runtime` is `null` and catalog fallbacks are skipped.

#### `void startAuction(String capName, List<Registration> providers, KernelEvent invokeEvent)`
* **Description:** Creates an `AuctionContext`, broadcasts `capability.bid.request` to all candidates, and schedules `resolveAuction()` after a **500ms** window.

#### `void handleBidResponse(KernelEvent event)`
* **Description:** Adds a `BidEntry` to the in-flight `AuctionContext`. If all candidates have voted, resolves the auction immediately (early exit).

#### `void resolveAuction(String auctionId)`
* **Description:** Selects the winner with the highest effective score (`score × (1 - load)`). Falls back to the first candidate if no bids arrived. Calls `routeToProvider()`.

#### `String buildCapabilityList()`
* **Description:** Returns a JSON array merging live registrations (`"live": true`) with catalog entries for capabilities not currently running (`"live": false`).
