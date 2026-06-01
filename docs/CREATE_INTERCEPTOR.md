# Creating a Custom Interceptor

This guide walks you through building a new interceptor for the MK8 MicroKernel. Every interceptor in the system — `IdempotencyInterceptor`, `CapabilityInterceptor`, and `PluginManager` — follows the same set of conventions described here.

---

## What Is an Interceptor?

An interceptor is a class that plugs into the Kernel's event routing pipeline. Before any event is broadcast to subscribed plugins, the Kernel runs it through the interceptor chain sequentially. Each interceptor can:

- **Observe** the event and update internal state (return `false`).
- **Consume** the event and stop all further routing (return `true`).
- **React** by publishing new events back into the bus via `KernelBus`.

Interceptors are loaded at boot from positional CLI arguments — no registration file, no annotation, no configuration:

```bash
jbang Kernel.java IdempotencyInterceptor CapabilityInterceptor PluginManager MyInterceptor
```

Order matters: each interceptor sees the event before the ones listed after it.

---

## Step 1 — Implement `EventInterceptor`

Every interceptor must implement the `EventInterceptor` interface defined in `Kernel.java`:

```java
interface EventInterceptor {
    boolean intercept(KernelEvent event, String json) throws Exception;
    default boolean handles(String eventType) { return true; }
    default Set<String> publishes()  { return Set.of(); }
    default Set<String> subscribes() { return Set.of(); }
}
```

### `intercept()` — the core method

Called by the Kernel for every event that passes the `handles()` filter.

| Return value | Meaning |
|---|---|
| `true` | Event **consumed** — the interceptor chain stops and broadcast is skipped entirely. |
| `false` | Side-effect only — the chain continues and the event is broadcast normally afterward. |

**Return `true`** when you have fully handled the event: you sent a reply, served a cache hit, or spawned a process and do not want the event broadcast further.

**Return `false`** when you only observed the event to update internal state (e.g., tracking usage timestamps, updating a registry, building a local index).

### `handles()` — the pre-filter

Called by the Kernel *before* `intercept()`. If it returns `false`, `intercept()` is never called for that event type. Always implement this — without it the Kernel calls `intercept()` for every single event on the bus.

```java
// Exact match — most efficient
@Override
public boolean handles(String type) {
    return type.equals("my.event.invoke")
        || type.equals("my.event.result");
}

// Prefix match — useful for domain namespaces
@Override
public boolean handles(String type) {
    return type.startsWith("my.domain.");
}
```

### `publishes()` and `subscribes()` — contract declarations

These are not used at runtime for routing. The Kernel reads them once at boot for logging and introspection. Implement both to make the interceptor's contract explicit and machine-readable.

```java
@Override public Set<String> publishes()  { return Set.of("my.event.result", "my.event.error"); }
@Override public Set<String> subscribes() { return Set.of("my.event.invoke", "my.event.notify"); }
```

---

## Step 2 — Choose the Right Constructor

The Kernel instantiates interceptors by convention, trying constructors in this order:

```
1. MyInterceptor(KernelBus bus, KernelConfig config)
2. MyInterceptor(KernelBus bus)
3. MyInterceptor()
```

Declare only what you need:

```java
// Needs to publish events back into the bus
MyInterceptor(KernelBus bus) {
    this.bus = bus;
}

// Needs the bus + project filesystem paths (logs dir, scan root)
MyInterceptor(KernelBus bus, KernelConfig config) {
    this.bus     = bus;
    this.logsDir = config.logsOverride() != null
            ? config.logsOverride()
            : config.scanRoot().resolve("logs");
}

// No external dependencies
MyInterceptor() { }
```

`KernelConfig` provides:
- `config.scanRoot()` — the project root directory used for `plugin.json` discovery.
- `config.logsOverride()` — an optional explicit log directory (may be `null`).

> **Never start background threads in the constructor.** The `KernelBus` is not yet wired to live connections at construction time. Use `InterceptorLifecycle.onStart()` for that (see Step 3).

---

## Step 3 — Add Background Work with `InterceptorLifecycle` (Optional)

If your interceptor needs to start background threads, schedulers, or scans, implement `InterceptorLifecycle` alongside `EventInterceptor`. The Kernel calls `onStart()` immediately after construction, before the accept loop begins.

```java
class MyInterceptor implements EventInterceptor, InterceptorLifecycle {

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "my-interceptor-timer");
                t.setDaemon(true);
                return t;
            });

    @Override
    public void onStart() {
        // Safe to use bus here — connections are about to start being accepted.
        Thread.ofVirtual().start(this::initialScan);
        scheduler.scheduleAtFixedRate(this::periodicCheck, 60, 60, TimeUnit.SECONDS);
    }
}
```

Use a **daemon thread** for schedulers so they do not prevent JVM shutdown.

---

## Step 4 — Route Events via `KernelBus`

Interceptors must never reference each other directly. All communication happens through events on the bus. `KernelBus` provides four methods:

```java
// Injects an event into the full Kernel pipeline (runs through all interceptors + broadcast)
bus.route(KernelEvent.of("my.event.out", payload, "my-interceptor"));

// Delivers a raw JSON frame directly to a specific plugin's write queue (bypasses routing entirely)
bus.sendTo("target-plugin-id", json);

// Records that pluginId is waiting for the reply with this correlationId
bus.addPendingRoute(event.correlationId(), event.source());

// Retrieves and removes the waiting plugin for a given correlationId
String callerId = bus.removePendingRoute(event.correlationId());
```

### Request-Reply Pattern

The standard pattern for routing a request to a plugin and delivering the reply back to the original caller:

```java
// On invoke: record who is waiting
private void handleInvoke(KernelEvent event) throws Exception {
    bus.addPendingRoute(event.correlationId(), event.source());
    bus.route(KernelEvent.reply(event, "target.trigger.event", event.payload(), SOURCE));
}

// On result: deliver back to the original caller
private void handleResult(KernelEvent event, String json) {
    String callerId = bus.removePendingRoute(event.correlationId());
    if (callerId != null) bus.sendTo(callerId, json);
}
```

Always call `bus.removePendingRoute()` when a result arrives to prevent memory leaks.

---

## Step 5 — Use `switch` to Dispatch Event Types

The idiomatic internal structure of `intercept()` is a `switch` expression that dispatches to private handler methods and simultaneously declares the return value:

```java
@Override
public boolean intercept(KernelEvent event, String json) throws Exception {
    return switch (event.type()) {
        case EVT_INVOKE  -> { handleInvoke(event);          yield true;  } // consumed
        case EVT_RESULT  -> { handleResult(event, json);    yield true;  } // consumed
        case EVT_NOTIFY  -> { handleNotify(event);          yield false; } // side-effect only
        case EVT_REGISTER-> { handleRegister(event);        yield false; } // side-effect only
        default          ->                                        false;
    };
}
```

Keep each handler in its own private method — `intercept()` should read like a routing table, not contain business logic.

---

## Step 6 — Thread Safety

Interceptors receive events from many concurrent virtual threads. Never use unsynchronized mutable state.

| Use this | Instead of |
|---|---|
| `ConcurrentHashMap<K,V>` | `HashMap<K,V>` |
| `CopyOnWriteArrayList<E>` | `ArrayList<E>` |
| `map.compute(key, (k, v) -> ...)` | separate get + put |
| `ConcurrentHashMap.newKeySet()` | `HashSet<E>` |
| `AtomicBoolean` / `AtomicReference` | plain `boolean` / reference |

### The Atomic Compute Pattern

When you need to check-and-act atomically (avoiding a race between reading and writing), use `compute()` on a `ConcurrentHashMap`:

```java
// Classic race condition — DO NOT do this:
if (!inFlight.containsKey(corrId)) {    // thread A checks here
    // ... thread B also checks here and passes
    inFlight.put(corrId, callerList);   // both threads register — duplicate execution
}

// Correct — atomic check and act:
inFlight.compute(corrId, (k, existing) -> {
    if (existing != null) {
        existing.add(event.source()); // collapse duplicate
        return existing;
    }
    var first = new CopyOnWriteArrayList<String>();
    first.add(event.source());
    return first; // first execution — register in-flight
});
```

---

## Step 7 — Declare Event Name Constants

Never scatter string literals across the code. Declare all event type names as private constants at the top of the class:

```java
class MyInterceptor implements EventInterceptor {

    // Events this interceptor publishes
    private static final String EVT_RESULT  = "my.event.result";
    private static final String EVT_ERROR   = "my.event.error";

    // Events this interceptor subscribes to
    private static final String EVT_INVOKE  = "my.event.invoke";
    private static final String EVT_NOTIFY  = "my.event.notify";

    // Source identifier for events emitted by this interceptor
    private static final String SOURCE = "my-interceptor";
}
```

This makes `handles()`, `intercept()`, `publishes()`, and `subscribes()` all reference the same constant — rename once, everything stays in sync.

---

## Minimal Template

```java
// No JBang header — this file is included via //SOURCES in Kernel.java

import java.util.*;
import java.util.concurrent.*;

class MyInterceptor implements EventInterceptor {

    // ── Event name constants ──────────────────────────────────────────────────

    private static final String EVT_INVOKE = "my.event.invoke";
    private static final String EVT_RESULT = "my.event.result";
    private static final String EVT_ERROR  = "my.event.error";
    private static final String SOURCE     = "my-interceptor";

    // ── State ─────────────────────────────────────────────────────────────────

    private final KernelBus bus;

    // ── Construction ──────────────────────────────────────────────────────────

    MyInterceptor(KernelBus bus) {
        this.bus = bus;
    }

    // ── Contract declarations ─────────────────────────────────────────────────

    @Override public Set<String> publishes()  { return Set.of(EVT_RESULT, EVT_ERROR); }
    @Override public Set<String> subscribes() { return Set.of(EVT_INVOKE); }

    // ── EventInterceptor ──────────────────────────────────────────────────────

    @Override
    public boolean handles(String type) {
        return type.equals(EVT_INVOKE);
    }

    @Override
    public boolean intercept(KernelEvent event, String json) throws Exception {
        return switch (event.type()) {
            case EVT_INVOKE -> { handleInvoke(event); yield true; }
            default         ->                              false;
        };
    }

    // ── Handlers ──────────────────────────────────────────────────────────────

    private void handleInvoke(KernelEvent event) throws Exception {
        try {
            String result = process(event.payload());
            String payload = KernelEvent.MAPPER.writeValueAsString(Map.of("result", result));
            bus.route(KernelEvent.withCorrelation(
                    EVT_RESULT, payload, SOURCE,
                    event.correlationId(), event.sessionId()));
        } catch (Exception e) {
            String payload = KernelEvent.MAPPER.writeValueAsString(Map.of("reason", e.getMessage()));
            bus.route(KernelEvent.withCorrelation(
                    EVT_ERROR, payload, SOURCE,
                    event.correlationId(), event.sessionId()));
        }
    }

    private String process(String payload) throws Exception {
        // your logic here
        return "processed: " + payload;
    }
}
```

---

## Template with Background Work

```java
class MyInterceptor implements EventInterceptor, InterceptorLifecycle {

    private static final String EVT_INVOKE  = "my.event.invoke";
    private static final String EVT_REFRESH = "my.event.refresh";
    private static final String SOURCE      = "my-interceptor";

    private final KernelBus bus;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "my-interceptor-timer");
                t.setDaemon(true);
                return t;
            });

    MyInterceptor(KernelBus bus) { this.bus = bus; }

    @Override
    public void onStart() {
        Thread.ofVirtual().start(this::initialLoad);
        scheduler.scheduleAtFixedRate(this::periodicCheck, 60, 60, TimeUnit.SECONDS);
    }

    @Override public Set<String> publishes()  { return Set.of(); }
    @Override public Set<String> subscribes() { return Set.of(EVT_INVOKE, EVT_REFRESH); }

    @Override
    public boolean handles(String type) {
        return type.equals(EVT_INVOKE) || type.equals(EVT_REFRESH);
    }

    @Override
    public boolean intercept(KernelEvent event, String json) throws Exception {
        return switch (event.type()) {
            case EVT_INVOKE  -> { handleInvoke(event);   yield true;  }
            case EVT_REFRESH -> { Thread.ofVirtual().start(this::initialLoad); yield false; }
            default          ->                                               false;
        };
    }

    private void initialLoad() { /* scan, fetch, or initialize state */ }
    private void periodicCheck() { /* cleanup, expiry, health check */ }
    private void handleInvoke(KernelEvent event) throws Exception { /* business logic */ }
}
```

---

## Checklist

Before shipping your interceptor, verify:

- [ ] `handles()` is implemented and filters only the event types you care about.
- [ ] `intercept()` returns `true` only when the event is fully handled.
- [ ] `intercept()` returns `false` when only updating internal state.
- [ ] `publishes()` and `subscribes()` list all event types accurately.
- [ ] All mutable state uses `java.util.concurrent` structures.
- [ ] No direct reference to another interceptor class — events only.
- [ ] Background threads (if any) are started in `onStart()`, not the constructor.
- [ ] `bus.removePendingRoute()` is called whenever a result is delivered.
- [ ] The class is added to `//SOURCES` in `Kernel.java` if it lives in a separate file.
- [ ] The class name is added to the CLI launch command in the project's `Start.java`.
