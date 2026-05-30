# PluginCatalog Reference

The `PluginCatalog.java` class serves as the static, disk-level single source of truth for all plugin configurations within the MK8 MicroKernel ecosystem. It recursively scans the local directory tree, parses `plugin.json` manifests, populates fast-lookup indexing tables, and provides safe, thread-guarded discovery boundaries during the system boot cycle.

---

## 1. Core Responsibilities

The `PluginCatalog` manages several critical metadata and parsing routines:
1. **Recursive Directory Scanning (`scan`):** Walks the physical filesystem directory tree starting from the project's root folder to locate all `plugin.json` configuration manifests.
2. **Directory Filtering:** Explicitly excludes the `/kernel` directory during folder traversals, accelerating scan performance and isolating core system infrastructure.
3. **Dynamic Manifest Parsing:** Reads and deserializes JSON manifests into Java record models using Jackson (`ObjectMapper`), capturing execution properties, lifecycle modes, and complete launch configurations (including multi-argument `launchCommand` arrays).
4. **Fast-Lookup Indexing:** Indexes capabilities, trigger events, and plugin IDs into thread-safe concurrent lookup tables for instant routing and lifecycle evaluations.
5. **Boot Race-Condition Guarding:** Employs a concurrency latch (`CountDownLatch`) that blocks other components (like `CapabilityIndex` resolving routes) from executing lookup queries until the initial directory scan has finished successfully.
6. **Hot Reloading (`refresh`):** Re-scans disk manifests synchronously in response to `plugin.installed` events, enabling real-time capability hot-reloading.

---

## 2. Directory Walk and Indexing Flow

The following lifecycle diagram maps how `PluginCatalog` resolves the repository structure and indexes plugins dynamically:

```
          Kernel Boots / Refresh Triggered
                        │
                        ▼
           [Resolve Project Root Folder]
                        │
                        ▼
          [Files.walk Recursively Traverses]
                        │
         ┌──────────────┴──────────────┐
         ▼                             ▼
   [Path under /kernel]         [Path has plugin.json]
         │                             │
         ▼ (Filtered Out)              ▼
    (Skip Path)                [Load Manifest Content]
                                       │
                                       ▼
                             [Parse JSON via Jackson]
                                       │
                                       ▼
                           [Extract Plugin Metadata]
                         - id, type, lifecycle mode
                         - capabilities & triggerEvents
                         - multi-argument launch.command
                                       │
                                       ▼
                            [Populate Concurrency Maps]
                         - byCapName (O(1) capability lookup)
                         - byTrigger (O(1) triggerEvent lookup)
                         - byPluginId (O(1) plugin lookup)
                                       │
                                       ▼
                           [CountDown Latch Released]
```

---

## 3. Concurrency and Thread Safety Patterns

`PluginCatalog` operates under highly concurrent virtual-thread workloads. To ensure thread safety and avoid race conditions during system initialization:

* **Thread-Safe Index Tables:** Employs `ConcurrentHashMap` for all core lookup maps:
  * `byCapName` maps a unique capability String to its corresponding `CatalogEntry`.
  * `byTrigger` maps a trigger event type to its `CatalogEntry`.
  * `byPluginId` maps a plugin identifier to a thread-safe list (`CopyOnWriteArrayList<CatalogEntry>`) of entries.
* **CountDownLatch Synchronization:** Since scanning is initiated asynchronously in a virtual thread at boot to prevent blocking socket binding, other components could attempt lookups prematurely. `PluginCatalog` uses a `CountDownLatch(1)` (`latch`) inside `awaitReady(timeoutMs)` to block caller threads until the catalog scan is complete:

```java
void awaitReady(long timeoutMs) {
    if (ready) return; // Fast-path bypass
    try {
        latch.await(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
}
```

---

## 4. Class API and Record Definitions

### A. Inner Record Model: `CatalogEntry`

The catalog represents parsed capability and launcher profiles as immutable record structures:

```java
record CatalogEntry(
    String   pluginId,
    String   pluginDir,          // Absolute directory path containing the manifest
    String   capabilityName,
    String   triggerEvent,       // Resolved trigger event namespace (null for agents)
    boolean  onDemand,           // Lifecycle mode == "on-demand"
    boolean  persistent,         // Lifecycle mode == "persistent"
    double   bidWeight,
    int      idleTimeoutSeconds,
    String[] launchCommand       // Full command execution array (e.g., ["jbang", "Tool.java"])
) {}
```

---

### B. Core Executive APIs

#### `public void scan()`
* **Description:** Performs the directory tree walk, parses valid JSON configurations (`plugin.json`), populates internal indexing maps, and releases the boot coordination latch. 
* **Details:**
  * Only indexes plugins of type `tool`, `agent`, or `system`.
  * Ignores paths containing the `kernel/` directory.
  * Correctly parses the full `launch.command` JSON array into a multi-parameter `String[]` command.

#### `public void refresh()`
* **Description:** Empties internal index maps and synchronously triggers `scan()` to rebuild the metadata catalog. Used when a new plugin is dynamically installed.

#### `public void awaitReady(long timeoutMs)`
* **Description:** Blocks the calling thread (up to `timeoutMs`) until the initial scanning process completes.

#### `public CatalogEntry getByCapName(String capName)`
* **Description:** Performs an $O(1)$ fast-path lookup to retrieve the metadata configuration associated with a capability name.

#### `public CatalogEntry getByTrigger(String triggerEvent)`
* **Description:** Performs an $O(1)$ fast-path lookup to retrieve the metadata configuration associated with a trigger event.

#### `public List<CatalogEntry> getByPluginId(String pluginId)`
* **Description:** Returns the complete list of capability entries belonging to a specific plugin identifier.

---

## 5. Integration with Core Infrastructure

`PluginCatalog` functions as the structural foundation for dynamic execution:

1. **Route Resolution (`CapabilityIndex`):** When a client requests execution of a capability that is not currently live, `CapabilityIndex` calls `catalog.getByCapName(capName)` to determine if a dynamic provider exists and inspects its lifecycle mode.
2. **Process Management (`ProcessManager`):** When launching an on-demand plugin or agent, `ProcessManager` retrieves the launcher command array and working directory from the `CatalogEntry` to build the target `ProcessBuilder` execution context safely.
