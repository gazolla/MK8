# Idempotency and Request Collapsing Reference

The `IdempotencyInterceptor.java` class acts as the high-performance optimization gateway inside the MK8 MicroKernel. It intercepts capability invocations and results to enforce sliding-window idempotency caching and request collapsing (Single-Flight).

---

## 1. Core Optimizations

The `IdempotencyInterceptor` implements two vital system optimizations:

### A. Request Collapsing (Single-Flight)
When a client sends multiple identical requests concurrently before the first request finishes processing, spawning identical tasks downstream wastes compute resources. 
* **The Optimization:** The interceptor halts duplicate `capability.invoke` calls, records the caller's ID inside an active `inFlight` list, and prevents duplicate calls from reaching downstream tools. When the single computation completes, the interceptor broadcasts the single result to all waiting callers.

### B. Sliding-Window Idempotency Caching
When a client sends duplicate sequential requests within a short timeframe, recalculating identical text statistics is highly inefficient.
* **The Optimization:** The interceptor caches successful `capability.result` payloads in-memory. If an identical `correlationId` invocation arrives, the interceptor immediately fulfills the request in **under 1 millisecond**, bypassing the orchestrators and tools entirely.

---

## 2. Structural Design and Thread-Safe Concurrency

The interceptor operates under intense, concurrent virtual thread execution. To prevent race conditions and memory leaks, it employs several specialized concurrency patterns:

* **`cache` Map:** A thread-safe `ConcurrentHashMap` mapping a `correlationId` to the cached `Event` payload.
* **`inFlight` Map:** A `ConcurrentHashMap` mapping a `correlationId` to a thread-safe `CopyOnWriteArrayList<String>` containing the list of waiting caller plugin IDs.
* **`scheduler` Engine:** A daemon thread `ScheduledExecutorService` that automatically runs cleanups, evicting cached items after their 5-minute sliding-window TTL expires.
* **Atomic Cache+InFlight Decision:** The cache-hit check and the in-flight registration/collapsing decision are made atomically inside a single `ConcurrentHashMap.compute()` call. This closes the race window where `handleResult()` could transition `inFlight → cache` between the initial cache check and the subsequent in-flight lookup, which would otherwise allow a duplicate invoke to bypass both guards and reach the downstream provider.

---

## 3. Invocation Interception Flow

The sequence flowchart below illustrates how the interceptor handles incoming `capability.invoke` requests:

```
                  Incoming "capability.invoke" Event
                                  │
                                  ▼
                    [Extract correlationId String]
                                  │
                      [Is corrId cached in Map?]
                       ┌──────────┴──────────┐
                       ▼ (Yes: Cache Hit)    ▼ (No: Cache Miss)
               [Deliver cached event]   [Enter inFlight.compute() — atomic]
               [Return true — halt]          │
                                      [Re-check cache inside compute()]
                                       ┌─────┴──────┐
                                       ▼ (Hit)       ▼ (Miss)
                               [Deliver cached]  [Is corrId active in-flight?]
                               [Return null —]    ┌──────────┴──────────┐
                               [no inFlight       ▼ (Yes)               ▼ (No)
                                entry created] [Add caller ID to    [Create new inFlight
                                               CopyOnWriteArrayList] entry for corrId]
                                               [Return true — halt]  [Return false —
                                                                       route to provider]
                                                                             │
                                                                             ▼
                                                                  [Receive result from tool]
                                                                             │
                                                                             ▼
                                                                  [Deliver result to all
                                                                   waiting callers in list]
                                                                             │
                                                                             ▼
                                                                  [Cache outcome & start
                                                                   5-minute TTL eviction]
                                                                             │
                                                                             ▼
                                                                  [removePendingRoute(corrId)]
```

---

## 4. Class API and Method Signatures

### A. Core Interceptor Contract

#### `public boolean intercept(Event event, String json) throws Exception`
* **Description:** Implements the `EventInterceptor` interface. Receives all events from the core routing engine right before the broadcast phase.
* **Arguments:**
  * `event`: The parsed `Event` record.
  * `json`: The raw serialized JSON frame.
* **Returns:** `true` if the event was fully resolved and consumed by the interceptor (halting subsequent routing), or `false` to let normal routing proceed.

---

### B. Internal Workload Processing

#### `private boolean handleInvoke(Event event, String json) throws Exception`
* **Description:** Manages cache hit evaluations and registers concurrent callers for Single-Flight collapsing. The cache-miss path and in-flight registration are resolved atomically inside a single `inFlight.compute()` call; a re-check of the cache inside `compute()` closes the race window between the initial cache lookup and the in-flight decision.

#### `private boolean handleResult(Event event, String json) throws Exception`
* **Description:** Manages the arrival of computed results, caches the payloads, starts the 5-minute eviction scheduler, delivers the response to all registered callers, and calls `bus.removePendingRoute(corrId)` to clean up the return-routing table and prevent memory leaks.
