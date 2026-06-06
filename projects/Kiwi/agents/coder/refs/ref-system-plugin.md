# System Plugin — Background Threads, Scheduled Tasks (JBang)

System plugins have no capabilities and no bid handlers.
They subscribe to system events and run infrastructure-level logic.
Use `ScheduledExecutorService` for periodic work.

---

## System plugin with periodic background task

```java
///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../../../kernel/KernelEvent.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginBase.java

import com.fasterxml.jackson.databind.JsonNode;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.*;

public class HealthMonitor {

    static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor();
    static volatile OutputStream kernelOut;

    public static void main(String[] args) throws Exception {
        // Start periodic task — runs every 30 seconds after a 5s initial delay
        SCHEDULER.scheduleAtFixedRate(HealthMonitor::checkHealth, 5, 30, TimeUnit.SECONDS);

        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, HealthMonitor::handle);
    }

    static void handle(String json, OutputStream out) throws Exception {
        kernelOut = out; // capture stream so background thread can publish events
        KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);

        // System plugins subscribe to system events, not capability events
        if ("system.shutdown".equals(event.type())) {
            SCHEDULER.shutdown();
        }
    }

    static void checkHealth() {
        try {
            Runtime rt = Runtime.getRuntime();
            long usedMb  = (rt.totalMemory() - rt.freeMemory()) / 1_048_576;
            long totalMb = rt.totalMemory() / 1_048_576;

            System.out.println("[HEALTH] memory used=" + usedMb + "MB / total=" + totalMb + "MB");

            if (kernelOut != null) {
                String payload = KernelEvent.MAPPER.writeValueAsString(Map.of(
                        "usedMb", usedMb, "totalMb", totalMb));
                PluginBase.publish(KernelEvent.of("system.health.report", payload, "health-monitor"), kernelOut);
            }
        } catch (Exception e) {
            System.err.println("[HEALTH] check failed: " + e.getMessage());
        }
    }
}
```

---

## One-shot background thread

```java
Thread worker = new Thread(() -> {
    try {
        // long-running initialisation
        Thread.sleep(2000);
        System.out.println("[PLUGIN] background init done");
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
});
worker.setDaemon(true); // dies when main thread exits
worker.start();
```

---

## ScheduledExecutorService patterns

```java
// Run once after delay
SCHEDULER.schedule(this::doWork, 10, TimeUnit.SECONDS);

// Run repeatedly at fixed rate (next run starts N seconds after previous START)
SCHEDULER.scheduleAtFixedRate(this::doWork, 0, 60, TimeUnit.SECONDS);

// Run repeatedly with fixed delay (next run starts N seconds after previous END)
SCHEDULER.scheduleWithFixedDelay(this::doWork, 0, 60, TimeUnit.SECONDS);

// Shutdown cleanly
SCHEDULER.shutdown();
SCHEDULER.awaitTermination(5, TimeUnit.SECONDS);
```

---

## System plugin.json — no capabilities section

```json
{
  "id": "health-monitor",
  "type": "system",
  "version": "1.0.0",
  "description": "Monitors JVM health and publishes periodic reports.",
  "lifecycle": { "mode": "persistent" },
  "subscribes": ["system.shutdown"],
  "publishes": ["system.health.report"],
  "launch": {
    "name": "HealthMonitor",
    "command": ["jbang", "HealthMonitor.java"],
    "order": 10,
    "delayAfterMs": 0
  }
}
```

System plugins have **no `capabilities` array** and **no bid handlers**.
