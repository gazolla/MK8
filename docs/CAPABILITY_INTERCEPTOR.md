# CapabilityInterceptor Reference

The `CapabilityInterceptor.java` class serves as the dynamic service registry and routing broker within the MK8 MicroKernel. It handles capabilities indexing, plugin provider registrations, dynamic bidding auctions, scoring evaluations, and route resolution.

It sits at position 1 in the Kernel's interceptor chain (executing right after the idempotency check).

---

## 1. Core Responsibilities

The `CapabilityInterceptor` manages the following structural tasks:
1. **Dynamic Registration:** Intercepts `capability.register` events emitted by connected plugins and adds their declared capabilities to the active index.
2. **Provider Tracking:** Keeps a thread-safe map of active capabilities and their registered providers, including parameters like `bidWeight` (bidding score) and tags.
3. **Event-Driven Catalog:** Subscribes to `system.catalog.entry` and `system.catalog.ready` events published by `PluginManager` during directory scans to build and load a local `localCatalog` registry.
4. **Dynamic Spawn & Usage Requests:** Publishes `system.plugin.spawn` to boot an on-demand plugin and `system.plugin.usage` to update its activity status.
5. **Bidding Auctions Coordination:** When a client invokes a capability, if the capability has multiple registered providers, the interceptor coordinates an auction, soliciting bids from active candidates.
6. **Scoring & Route Selection:** Evaluates bid responses, selects the best provider based on weight scores and load, and forwards the invocation.

---

## 2. Structural Design and Thread Safety

`CapabilityInterceptor` is designed to be fully thread-safe, supporting concurrent registrations and lookups from high-performance virtual thread tasks:

* **`registrations` Map:** Maps `capabilityName` to a `CopyOnWriteArrayList<Registration>` of live providers. A `Registration` holds `pluginId`, `triggerEvent` (null for agents), and `bidWeight`.
* **`pendingInvokes` Map:** Maps `capabilityName` to a list of queued `capability.invoke` events waiting for an on-demand plugin to start and register.
* **`localCatalog` Map:** Maps capability name to a `CatalogEntry` populated via system catalog events.
* **`catalogReady` Promise:** A `CompletableFuture<Void>` completed upon receiving `system.catalog.ready`.
* **`auctions` Map:** Maps an `auctionId` (UUID) to an `AuctionContext` holding the original event, candidate list, and collected bids. Auction timeout is enforced by a `CompletableFuture.delayedExecutor()` (500ms window).

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
  [Forward Message]                  [Publish "capability.bid.request"
                                      to all potential candidate tools]
                                               │
                                               ▼
                                    [Await delayedExecutor
                                     500ms Timeout Window]
                                               │
                                               ├─► [Bid Received] ──► [Record Score]
                                               │
                                               ▼
                                     [Select Provider with
                                      Highest Weight Score]
                                               │
                                               ▼
                                      [Forward Message]
```

---

## 4. Class API and Method Signatures

### A. EventInterceptor Entry Point

#### `boolean handles(String type)`
* **Description:** Pre-filters events. Returns `true` for capability events (`capability.*`) and system lifecycle events (`system.catalog.entry`, `system.catalog.ready`, `system.plugin.died`, `system.plugin.stopped`).

#### `boolean intercept(KernelEvent event, String json) throws Exception`
* **Description:** Routes to the appropriate handler based on `event.type()`. Returns `true` (consumed) for `capability.invoke`, `capability.unregister`, `capability.bid.response`, and `capability.query`. Returns `false` (side-effect only) for `capability.register`, `system.catalog.entry`, `system.catalog.ready`, `system.plugin.died/stopped`.

---

### B. Registration Management

#### `void handleRegister(KernelEvent event)`
* **Description:** Adds a new `Registration` to `registrations` for the declared capability. After registration, drains any `pendingInvokes` queued while the plugin was starting.

#### `void handleUnregister(KernelEvent event)`
* **Description:** Removes the specific `Registration` for the given `pluginId` from the `registrations` map.

#### `void handlePluginDied(KernelEvent event)`
* **Description:** Sweeps `registrations` and removes all entries whose `pluginId` matches the dead/stopped plugin.

---

### C. Routing and Auction Management

#### `void handleInvoke(KernelEvent event)`
* **Description:** Routes a `capability.invoke` to a live provider. If no live provider is found, consults its `localCatalog`: persistent plugins are forwarded to their `triggerEvent`; on-demand plugins are queued in `pendingInvokes` and `system.plugin.spawn` is published on the event bus to trigger `PluginManager`.

#### `void startAuction(String capName, List<Registration> providers, KernelEvent invokeEvent)`
* **Description:** Creates an `AuctionContext`, broadcasts `capability.bid.request` to all candidates, and schedules `resolveAuction()` after a **500ms** window.

#### `void handleBidResponse(KernelEvent event)`
* **Description:** Adds a `BidEntry` to the in-flight `AuctionContext`. If all candidates have voted, resolves the auction immediately (early exit).

#### `void resolveAuction(String auctionId)`
* **Description:** Selects the winner with the highest effective score (`score × (1 - load)`). Falls back to the first candidate if no bids arrived. Calls `routeToProvider()`.

#### `String buildCapabilityList()`
* **Description:** Returns a JSON array merging live registrations (`"live": true`) with catalog entries for capabilities not currently running (`"live": false`). Handled dynamically when `system.capability.list` is invoked. merging live registrations (`"live": true`) with catalog entries for capabilities not currently running (`"live": false`).
