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
               [Read Event payload]     [Is corrId active in-flight?]
                       │                     ┌──────────┴──────────┐
                       │                     ▼ (Yes)               ▼ (No)
                       │             [Add Caller ID to      [Register corrId in-flight]
                       │              CopyOnWriteArrayList]        │
                       │                     │                     ▼
                       │                     ▼            [Allow normal routing
                       │              [Halt Routing        to downstream tool]
                       │               (Consume Event)]            │
                       │                     │                     ▼
                       │                     │             [Receive result from tool]
                       │                     │                     │
                       │                     │                     ▼
                       │                     │             [Deliver result to all
                       │                     │              waiting callers in list]
                       │                     │                     │
                       │                     │                     ▼
                       │                     │             [Cache outcome & start
                       │                     │              5-minute TTL eviction]
                       ▼                     ▼                     │
               [Deliver Cached Event] ◄──────┴─────────────────────┘
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
* **Description:** Manages cache hit evaluations and registers concurrent callers for Single-Flight collapsing.

#### `private boolean handleResult(Event event, String json) throws Exception`
* **Description:** Manages the arrival of computed results, caches the payloads, starts the 5-minute eviction scheduler, delivers the response to all registered callers, and cleans up from `Kernel.pendingRoutes` to prevent memory leaks.
