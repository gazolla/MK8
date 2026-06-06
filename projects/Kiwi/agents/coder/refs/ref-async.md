# Async & Concurrency — CompletableFuture, Virtual Threads (JBang)

No extra //DEPS needed. Java 21 virtual threads make blocking I/O as scalable as async.

---

## Virtual threads (Java 21) — preferred for I/O-bound work

```java
///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../../../kernel/KernelEvent.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginBase.java

import java.util.concurrent.*;

// Fire-and-forget background task
Thread.ofVirtual().start(() -> {
    // any blocking I/O here — virtual thread suspends, not an OS thread
    doSomeWork();
});

// Virtual thread executor — one virtual thread per submitted task
ExecutorService vExec = Executors.newVirtualThreadPerTaskExecutor();
vExec.submit(() -> fetchSomeData());
```

---

## Parallel HTTP calls — CompletableFuture

```java
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;

static final HttpClient HTTP = HttpClient.newHttpClient();

// Fire N requests concurrently, collect all results
static List<String> fetchAll(List<String> urls) throws Exception {
    List<CompletableFuture<String>> futures = urls.stream()
            .map(url -> HTTP.sendAsync(
                    HttpRequest.newBuilder().uri(java.net.URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body))
            .toList();

    // Wait for all to complete
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);

    return futures.stream()
            .map(CompletableFuture::join) // safe — all already completed
            .toList();
}
```

---

## CompletableFuture chaining

```java
CompletableFuture<String> result = CompletableFuture
        .supplyAsync(() -> fetchData())              // run on ForkJoinPool
        .thenApply(data -> parse(data))              // transform result
        .thenApply(parsed -> format(parsed))         // chain another transform
        .exceptionally(e -> "error: " + e.getMessage()); // fallback on any error

String value = result.get(10, TimeUnit.SECONDS);
```

---

## Timeout on a CompletableFuture

```java
CompletableFuture<String> future = someAsyncOperation();

// Completes with TimeoutException if not done in 5s
String result = future
        .orTimeout(5, TimeUnit.SECONDS)
        .exceptionally(e -> {
            if (e instanceof TimeoutException) return "timeout";
            return "error: " + e.getMessage();
        })
        .get();
```

---

## Run N tasks, take the first result (race)

```java
List<CompletableFuture<String>> futures = List.of(
        CompletableFuture.supplyAsync(() -> queryPrimaryServer()),
        CompletableFuture.supplyAsync(() -> queryFallbackServer())
);

// Returns whichever completes first
String first = CompletableFuture.anyOf(futures.toArray(new CompletableFuture[0]))
        .thenApply(o -> (String) o)
        .get(10, TimeUnit.SECONDS);
```

---

## ScheduledExecutorService — periodic background work

```java
ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
        Thread.ofVirtual().factory()); // virtual thread scheduler (Java 21)

// Run every 30 seconds
scheduler.scheduleAtFixedRate(() -> {
    try { doPeriodicWork(); }
    catch (Exception e) { System.err.println("periodic error: " + e.getMessage()); }
}, 0, 30, TimeUnit.SECONDS);
```

---

## BlockingQueue — producer/consumer between threads

```java
BlockingQueue<String> queue = new LinkedBlockingQueue<>(100); // bounded queue

// Producer thread
Thread.ofVirtual().start(() -> {
    while (true) {
        String item = fetchNextItem();
        queue.put(item); // blocks if queue is full
    }
});

// Consumer thread
Thread.ofVirtual().start(() -> {
    while (true) {
        String item = queue.take(); // blocks until item available
        process(item);
    }
});
```

---

## Atomic counters and flags

```java
import java.util.concurrent.atomic.*;

// Thread-safe counter
AtomicLong requestCount = new AtomicLong(0);
requestCount.incrementAndGet();

// Thread-safe flag (start/stop)
AtomicBoolean running = new AtomicBoolean(true);
while (running.get()) {
    doWork();
}
// From another thread:
running.set(false);
```

---

## Critical rules

- **Never share mutable state without synchronization.** Use `AtomicXxx`, `ConcurrentHashMap`, `BlockingQueue`, or explicit `synchronized`.
- **Virtual threads should not use `synchronized` blocks for I/O** — they pin the carrier thread. Use `ReentrantLock` instead for critical sections in virtual thread code.
- **`CompletableFuture.get()` is blocking** — call it from a virtual thread or a non-critical path only.
- **Always set a timeout** on `future.get(timeout, unit)` — unbounded waits hide bugs.
