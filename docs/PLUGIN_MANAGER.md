# PluginManager Reference

`PluginManager.java` is the single source of truth for plugin discovery and lifecycle in MK8. It merges what were previously two separate classes — `PluginCatalog` (disk scan and capability index) and `ProcessManager` (process spawning, idle-kill, usage tracking) — into one cohesive infrastructure service.

`PluginManager` implements `PluginRuntime`, the interface used by `CapabilityIndex` to interact with plugin infrastructure without coupling to this class directly.

---

## 1. Core Responsibilities

1. **Catalog Scan:** Walks the project directory tree at boot (in a virtual thread), reads every `plugin.json`, and populates in-memory lookup maps (`byCapName`, `byPluginId`).
2. **Hot Refresh:** On `plugin.installed`, re-scans the directory tree synchronously (the catalog is small; a full re-scan is fast).
3. **On-Demand Spawning:** Launches child processes via `ProcessBuilder` when `CapabilityIndex` calls `spawnOnDemand()`. Redirects stdout/stderr to `logs/<pluginId>.log`.
4. **Idle-Kill:** A background scheduler checks every 60 seconds for on-demand plugins that have been idle longer than their declared `idleTimeoutSeconds` and terminates them.
5. **Usage Tracking:** `trackUsage(capName)` updates the last-used timestamp so the idle-kill sweep knows a plugin is still active.
6. **Plugin List:** `listPlugins()` returns a JSON array of running processes and their status (used by `CapabilityIndex` to handle `system.capability.list` invocations).

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

## 3. PluginRuntime Interface

`CapabilityIndex` only interacts with `PluginManager` through `PluginRuntime`. This keeps `CapabilityIndex` decoupled from the concrete infrastructure class.

```java
interface PluginRuntime {
    // Catalog
    void awaitReady(long timeoutMs);
    PluginManager.CatalogEntry getByCapName(String capName);
    Collection<PluginManager.CatalogEntry> allEntries();
    void refresh();
    // Lifecycle
    void spawnOnDemand(String capabilityName) throws Exception;
    void trackUsage(String capabilityName);
    String listPlugins() throws Exception;
}
```

---

## 4. API Reference

### Catalog Methods

#### `void awaitReady(long timeoutMs)`
Blocks until the initial `scan()` completes (or the timeout elapses). Called by `spawnOnDemand()` internally to ensure the catalog is populated before a lookup.

#### `CatalogEntry getByCapName(String capName)`
Returns the `CatalogEntry` for the given capability name, or `null` if not found.

#### `Collection<CatalogEntry> allEntries()`
Returns an unmodifiable view of all indexed capabilities.

#### `void refresh()`
Clears and rebuilds both index maps. Triggered by `plugin.installed` events.

---

### Lifecycle Methods

#### `void spawnOnDemand(String capName)`
Spawns the on-demand plugin that provides `capName`.

1. Calls `awaitReady(500)` to ensure the catalog is ready.
2. Looks up the `CatalogEntry`; returns immediately if the entry is missing or not `onDemand`.
3. If the plugin is already in `managed`, publishes `system.plugin.spawned` and returns (idempotent — used to drain `pendingInvokes` in `CapabilityIndex`).
4. Guards against double-spawn using the `spawning` set.
5. Builds `ProcessBuilder` from `entry.launchCommand()`, sets the working directory to `pluginDir`, and redirects both stdout and stderr to `logs/<pluginId>.log`.
6. Stores the `ManagedProcess` in `managed`, updates `lastUsed`, and publishes `system.plugin.spawned`.
7. Registers a `process.onExit()` callback that publishes `system.plugin.died` if the process exits unexpectedly.

#### `void trackUsage(String capName)`
Updates the `lastUsed` timestamp for the plugin that provides `capName`. Called by `CapabilityIndex.handleInvoke()` after routing a request to an already-running plugin.

#### `String listPlugins()`
Returns a JSON array of maps, one per managed process, with fields:
- `pluginId` — plugin identifier
- `pid` — OS process ID
- `alive` — whether the process is still running
- `lastUsed` — time since last use (e.g., `"12s ago"`)

---

### Internal Methods

#### `void checkIdlePlugins()`
Called every 60 seconds by the internal `ScheduledExecutorService`. Iterates over `managed`, computes idle duration against each plugin's `idleTimeoutSeconds`, and calls `terminatePlugin()` for those that have exceeded the limit.

#### `void terminatePlugin(String pluginId, String reason)`
Calls `process.destroy()`, waits up to 5 seconds, then `destroyForcibly()` if needed. Removes the plugin from `managed` and `lastUsed`, then publishes `system.plugin.stopped` on the bus.

---

## 5. Boot Wiring (Kernel.start)

```java
var bus       = new KernelBusImpl(this);
var pluginMgr = new PluginManager(bus, mk8Root);
Thread.ofVirtual().start(pluginMgr::scan);   // non-blocking background scan

var idempotency = new IdempotencyInterceptor(bus);
var capIdx      = new CapabilityIndex(bus);
capIdx.setRuntime(pluginMgr);                // inject PluginRuntime

interceptors = List.of(idempotency, capIdx); // immutable after this point
```

`PluginManager` is **not** part of the interceptor chain. It is a pure infrastructure service — `CapabilityIndex` calls it directly through the `PluginRuntime` interface.

---

## 6. Log Redirection

All child process output (stdout + stderr) is redirected to `logs/<pluginId>.log` under the project root via:

```java
new ProcessBuilder(command)
    .directory(pluginDir.toFile())
    .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
    .redirectErrorStream(true)
    .start();
```

Plugin developers write to `System.out` / `System.err` as normal; the OS-level capture is transparent.
