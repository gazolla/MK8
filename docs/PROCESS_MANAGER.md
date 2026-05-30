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

### A. Process Spawning

#### `public synchronized boolean spawn(String pluginId, Event.PluginConfig config)`
* **Description:** Spawns a child process plugin using `ProcessBuilder` and redirects standard outputs to logs.
* **Arguments:**
  * `pluginId`: Unique identifier of the target plugin.
  * `config`: The launcher metadata and launch commands parsed from the manifest.

---

### B. Lifecycle and Sweeping Management

#### `public synchronized void registerMessage(String pluginId)`
* **Description:** Resets the idle timer of the target plugin. Should be invoked every time the Kernel routes a message to the plugin.

#### `public synchronized void kill(String pluginId)`
* **Description:** Gracefully terminates the child process and closes its active input/output streams.

#### `public synchronized void shutdownAll()`
* **Description:** Invoked when the Kernel shuts down. Gracefully terminates all active child processes, asserts their PID terminations, and cleans up system socket files.

---

## 4. Multi-Stream Log Redirection

To ensure that developers can inspect background plugins in real-time without cluttering the Kernel's main terminal output, `ProcessManager` forks stream redirection into dedicated asynchronous tasks. 

For every spawned subprocess, the manager starts background virtual threads to copy streams into `/logs/{pluginId}.log`:

```java
Process process = processBuilder.start();
long pid = process.toHandle().pid();

// Fork virtual threads to copy stdout and stderr asynchronously
Thread.ofVirtual().start(() -> redirectStream(process.getInputStream(), stdoutLogFile));
Thread.ofVirtual().start(() -> redirectStream(process.getErrorStream(), stderrLogFile));
```
This isolates each plugin's operational output, fulfilling the Twelve-Factor App logging principles.
