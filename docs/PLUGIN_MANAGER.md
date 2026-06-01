# PluginManager Reference

`PluginManager.java` is the single source of truth for plugin discovery and process lifecycle management in MK8. It implements `EventInterceptor` and `InterceptorLifecycle` to participate directly in the Kernel's event pipeline.

The interceptor operates under virtual thread execution and is completely decoupled from other core components: all communications occur purely via event streams on the UDS bus.

---

## 1. Core Responsibilities

1. **Dynamic Catalog Scanning:** Walks the project directory recursively at boot inside a virtual thread, parses all `plugin.json` configurations, and indexes capabilities in its internal registry mappings.
2. **Catalog Sync Broadcasts:** Lacking direct object references, it publishes `system.catalog.entry` events for every capability found, followed by `system.catalog.ready`, notifying `CapabilityInterceptor` to sync its indexes.
3. **On-Demand Process Spawning:** Intercepts `system.plugin.spawn` events from the bus and launches child processes dynamically via `ProcessBuilder`. Redirects stdout/stderr streams to `logs/<pluginId>.log`.
4. **Idle-Kill Cleanup:** A background scheduled executor sweeps active on-demand plugins every 60 seconds, terminating processes that have been idle longer than their declared `idleTimeoutSeconds` threshold.
5. **Usage Tracking:** Intercepts `system.plugin.usage` events from the bus and updates the plugin's last-used timestamp to prevent idle timeouts.
6. **Routable Plugin List:** Registers `system.plugin.list` as a capability, intercepts `system.plugin.list.request` events, and replies with a serialized JSON list of running processes, PIDs, and idle durations.
7. **Hot Catalog Reload:** Intercepts `plugin.installed` and `system.catalog.refresh` events to trigger a synchronous directory re-scan in the background.

---

## 2. Key Records

### `CatalogEntry`
One entry per capability declaration. A plugin with N capabilities produces N entries.

| Field | Type | Description |
|---|---|---|
| `pluginId` | string | Unique plugin identifier from `plugin.json` |
| `pluginDir` | string | Absolute path to the plugin directory |
| `capabilityName` | string | Capability name (e.g. `text.wordcount`) |
| `triggerEvent` | string | Trigger event (null for agents with no `triggerEvent`) |
| `onDemand` | boolean | `lifecycle.mode == "on-demand"` |
| `persistent` | boolean | `lifecycle.mode == "persistent"` |
| `bidWeight` | double | Default bid weight from capability declaration |
| `idleTimeoutSeconds` | int | Idle timeout for on-demand plugins (default 300s) |
| `launchCommand` | `String[]` | Full `launch.command` array (e.g. `["jbang", "Tool.java"]`) |

### `ManagedProcess`
One entry per running child process.

| Field | Type | Description |
|---|---|---|
| `pluginId` | string | Plugin identifier |
| `pid` | long | OS process ID |
| `pluginDir` | Path | Working directory of the spawned process |
| `process` | Process | JVM `Process` handle for lifecycle control |

---

## 3. EventInterceptor and InterceptorLifecycle Contract

`PluginManager` implements both core interfaces to integrate seamlessly with the dynamic Kernel:

### `void onStart()`
* **Description:** Initiated by the Kernel immediately after construction. Launches the initial asynchronous catalog directory `scan()` in a dedicated Virtual Thread.

### `boolean handles(String type)`
* **Description:** Pre-filters events. Returns `true` for lifecycle and catalog requests (`system.plugin.spawn`, `system.plugin.usage`, `system.catalog.refresh`, `plugin.installed`, `system.plugin.list.request`).

### `boolean intercept(KernelEvent event, String json) throws Exception`
* **Description:** Entry point for UDS frame processing. Returns `true` (consumed) for `system.plugin.spawn` and `system.plugin.list.request`, and `false` (side-effects only) for others.

---

## 4. API Reference

### Catalog Management

#### `void scan()`
* **Description:** Recursive directory walker. Excludes the `/kernel` path, parses `plugin.json` structures, populates indexes, publishes `system.catalog.entry` events for every capability, registers `system.plugin.list` capability, and broadcasts `system.catalog.ready` upon completion.

---

### Process Lifecycle Management

#### `void spawnOnDemand(String capName)`
* **Description:** Triggered upon intercepting `system.plugin.spawn`. Launches the on-demand process that provides `capName`.
1. Looks up the `CatalogEntry`; exits if missing or not `onDemand`.
2. If already in `managed`, publishes `system.plugin.spawned` and returns (idempotent draining).
3. Checks the `spawning` set (a `ConcurrentHashMap.newKeySet()`) via atomic `add()`. If the plugin ID is already present, another thread is already launching it — returns immediately to prevent double-spawn. The set entry is always removed in a `finally` block after spawn completes or fails.
4. Builds `ProcessBuilder` with JBang commands, sets execution directory to `pluginDir`, redirects output to `logs/<pluginId>.log`, and starts the process.
5. Saves `ManagedProcess`, registers `process.onExit()` callback to publish `system.plugin.died` in case of sudden crashes, and publishes `system.plugin.spawned`.

#### `void checkIdlePlugins()`
* **Description:** Scheduled check running every 60 seconds. Iterates active `managed` plugins, computes idle elapsed durations against thresholds, and terminates idle plugins.

#### `void terminatePlugin(String pluginId, String reason)`
* **Description:** Graceful shutdown handler. Destroys the JVM process, waits 5 seconds, forces termination if unresolved, updates tracking tables, and broadcasts `system.plugin.stopped` on the bus.

---

## 5. Dynamic Boot Loading (Kernel.java)

Interceptors are resolved dynamically at startup without hardcoded registrations. The Kernel instantiates them by convention and invokes their lifecycle hooks:

```java
// Resolved dynamically from CLI positional arguments:
var cls         = Class.forName(name);
var interceptor = instantiate(cls.asSubclass(EventInterceptor.class), bus, config);

if (interceptor instanceof InterceptorLifecycle lc) {
    lc.onStart(); // Triggers scan() and checkIdlePlugins() scheduler
}
```

---

## 6. Log Redirection

All child process output (stdout + stderr) is captured transparently and redirected to `logs/<pluginId>.log` under the project root via:

```java
new ProcessBuilder(command)
    .directory(pluginDir.toFile())
    .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
    .redirectErrorStream(true)
    .start();
```

Plugin developers write to `System.out` / `System.err` as normal; the OS-level capture is transparent.
