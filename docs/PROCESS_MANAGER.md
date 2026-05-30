# ProcessManager Reference

The `ProcessManager.java` class serves as the elastic execution controller within the MK8 MicroKernel. It handles local child process spawning, JBang orchestration, dynamic lifecycle monitoring, idle process cleanup, and standard streams logging.

---

## 1. Core Responsibilities

The `ProcessManager` encapsulates several runtime management features:
1. **Dynamic On-Demand Spawning:** Spawns child process plugins dynamically via `ProcessBuilder` when their capability is first invoked.
2. **JBang Orchestration:** Formulates command-line parameters (e.g. executing `jbang WordCountTool.java` dynamically) to launch source-only JBang files.
3. **Twelve-Factor Log Redirection:** Intercepts the standard output (`stdout`) and standard error (`stderr`) streams of child processes and redirects them to dedicated files under `/logs/`.
4. **Elastic Lifecycle Sweeping:** Monitors plugin process activity. If a plugin runs in `"on-demand"` mode and remains idle (receives no messages) longer than its pre-configured `idleTimeoutSeconds`, the sweeping engine automatically terminates the process to release system resources.

---

## 2. Structural Design and Execution Lifecycle

The Process Manager utilizes a scheduler thread executor to periodically check for idle child processes. Below is the lifecycle flow of a child process:

```
           Kernel Receives Invoke
                      │
                      ▼
     [Lookup Plugin in Process Catalog]
                      │
                      ▼
        [Is Plugin Active in UDS?]
            ┌─────────┴─────────┐
            ▼ (Yes)             ▼ (No)
     [Route Message]     [Check Lifecycle Mode]
                                │
          ┌─────────────────────┴─────────────────────┐
          ▼ (Persistent)                              ▼ (On-Demand)
  [Log Connection Error]                       [Trigger Spawn Engine]
                                                      │
                                                      ▼
                                            [Build Process Command]
                                           (e.g., jbang WordCountTool.java)
                                                      │
                                                      ▼
                                            [Redirect IO Streams]
                                            (stdout/stderr to logs/)
                                                      │
                                                      ▼
                                            [Establish UDS Connection]
                                                      │
                                                      ▼
                                             [Route Message]
                                                      │
                                                      ▼
                                            [Monitor Message Idle Time]
                                                      │
                                           ┌──────────┴──────────┐
                                           ▼ (Message Received)  ▼ (Idle Timeout Hit)
                                     [Reset Idle Timer]   [Terminate Child Process]
```

---

## 3. Class API and Method Signatures

### A. EventInterceptor Entry Point

#### `boolean intercept(Event event, String json) throws Exception`
Routes to the appropriate handler. Returns `true` (consumed) for `plugin.load`, `agent.spawn`, `agent.stop`, and `capability.system.plugin.list`. Returns `false` (side-effect only) for `agent.ready`, `agent.idle`, and `agent.busy`.

---

### B. Process Spawning

#### `void handlePluginLoad(Event event)`
* **Description:** Triggered by `plugin.load` events emitted by `CapabilityIndex`. Looks up the `CatalogEntry` via `PluginCatalog`, builds a `ProcessBuilder` with `launch.command`, redirects output to `logs/{pluginId}.log`, and registers an `onExit()` callback that publishes `system.plugin.died`.
* **Double-spawn guard:** Uses a `ConcurrentHashMap.newKeySet()` (`spawning`) to prevent multiple concurrent spawns of the same plugin on burst `plugin.load` events.

#### `void handleSpawn(Event event)`
* **Description:** Triggered by `agent.spawn` events (AgentSpawner delegations). Reads `launch.command` from the agent's `plugin.json` directly (fresh from disk), starts the process with `inheritIO()`, and publishes `system.plugin.spawned`.

---

### C. Lifecycle Management

#### `void trackUsage(String capName)`
* **Description:** Updates `lastUsed` for the plugin providing `capName`. Called **directly** by `CapabilityIndex.handleInvoke()` before routing — bypasses the interceptor chain, which stops at `CapabilityIndex` (returns `true`).

#### `void handleStop(Event event)`
* **Description:** Removes the plugin from `managed`, cancels any pending idle timer, calls `process.destroy()` with a 5-second graceful timeout (`destroyForcibly()` if it does not exit), and publishes `system.plugin.stopped`.

#### `void checkIdlePlugins()`
* **Description:** Runs every 60 seconds via `ScheduledExecutorService`. For each entry in `managed`, checks `lastUsed` against the plugin's `idleTimeoutSeconds` from `PluginCatalog`. Publishes `agent.stop` for plugins that have exceeded their idle threshold. Only applies to `on-demand` lifecycle plugins.

---

## 4. Log Redirection

To keep each plugin's output isolated without cluttering the Kernel's terminal, `ProcessManager` uses `ProcessBuilder`'s native stream redirection. Both `stdout` and `stderr` are merged and written to `logs/{pluginId}.log` via OS-level file descriptors — no virtual thread copying involved:

```java
File logFile = new File(logsDir, pluginId + ".log");
Process process = new ProcessBuilder(command)
        .directory(pluginDir.toFile())
        .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        .redirectErrorStream(true)   // merges stderr into stdout
        .start();
```

This approach is zero-copy, does not consume virtual threads for stream draining, and fulfills the Twelve-Factor App logging principles.
