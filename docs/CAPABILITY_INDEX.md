# CapabilityIndex Reference

The `CapabilityIndex.java` class serves as the dynamic service registry and routing broker within the MK8 MicroKernel. It handles capabilities indexing, plugin provider registrations, dynamic bidding auctions, scoring evaluations, and route resolution.

---

## 1. Core Responsibilities

The `CapabilityIndex` manages the following structural tasks:
1. **Dynamic Registration:** Intercepts `capability.register` events emitted by newly connected plugins and adds their declared capabilities to the active index.
2. **Provider Tracking:** Keeps a thread-safe map of active capabilities and their registered providers, including parameters like `bidWeight` (bidding score) and tags.
3. **Bidding Auctions Coordination:** When a client invokes a capability (e.g. `text.wordcount`), if the capability has multiple registered providers, the `CapabilityIndex` manages a real-time auction, soliciting scores from active candidates.
4. **Scoring & Route Selection:** Evaluates bid responses, selects the best provider based on weight scores and load, and caches the routing path.

---

## 2. Structural Design and Thread Safety

`CapabilityIndex` is designed to be fully thread-safe, supporting concurrent registrations and lookups from high-performance virtual thread tasks:

* **`capabilities` Map:** Maps `capabilityName` to a list of active `CapabilityProvider` records.
* **`activeAuctions` Map:** Tracks in-flight bidding auctions, mapping `correlationId` to the list of arrived `Bid` records.
* **`auctionsLatch` Map:** Maps `correlationId` to a `CountDownLatch` representing the waiting state of the auction. The latch is released when all active providers have submitted their bids or when an auction timeout occurs.

---

## 3. The Bidding and Auction Process

The flow diagram below details how the `CapabilityIndex` coordinates a real-time capability auction:

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
                                   [Await CountDownLatch or
                                    250ms Timeout Threshold]
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

### A. Registry Management

#### `public synchronized void register(String capabilityName, String pluginId, double bidWeight, String triggerEvent)`
* **Description:** Indexes a new capability capability provider. Registers the provider ID, default score, and target trigger event.

#### `public synchronized void deregisterPlugin(String pluginId)`
* **Description:** Sweeps the index and removes all capabilities associated with the given plugin ID when it disconnects.

---

### B. Routing and Auction Management

#### `public RouteInfo resolveRoute(String capabilityName, KernelBus bus) throws Exception`
* **Description:** Returns the optimal routing path (`RouteInfo`) for the capability. If multiple providers exist, it automatically initiates and coordinates a 250ms concurrent bidding auction over the event bus.
* **Arguments:**
  * `capabilityName`: Semantic name of the capability to resolve.
  * `bus`: The active `KernelBus` reference used to publish bid requests.

#### `public void handleBidResponse(Event event)`
* **Description:** Intercepts `capability.bid.response` events, extracts the score, registers the bid inside the `activeAuctions` list, and decrements the auction latch.
* **Arguments:**
  * `event`: The incoming JSON bid event.
