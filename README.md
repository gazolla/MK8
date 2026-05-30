# MK8 MicroKernel

A modular, asynchronous, and event-driven MicroKernel system implemented in Java 21+. It enables independent processes (plugins) to discover and call each other's capabilities over Unix Domain Sockets (UDS) using JSON event frames, eliminating compile-time or static network dependencies.

For a conceptual deep dive into the system's core design patterns—such as capability bidding auctions, idempotency caching, Single-Flight request collapsing, lifecycles, and distributed tracing—consult the **[System Concepts Guide](docs/CONCEPTS.md)**. To explore the concrete code layout, package structure, class descriptions, and core API interfaces (such as `EventInterceptor` and `KernelBus`), review the **[System Architecture Reference](docs/ARCHITECTURE.md)**.

---

## 1. Project Structuring

Below is the layout of folders and files in the repository. Click the links to explore the source files and their respective documentation guides:

* 📁 **MK8 (Project Root)**
  * 📄 **[Start.java](Start.java)** — Automated foreground/background bootrunner and process cleanup utility.
  * 📄 **[README.md](README.md)** — Main entry point (Setup, Quick Start, and conceptual mappings).
  * 📁 **`logs/`** — Automatically spawned directory containing stdout/stderr redirects of all plugin processes.
  * 📁 **`kernel/`** *(MicroKernel Core Infrastructure)*
    * 📄 **[Kernel.java](kernel/Kernel.java)** — UDS socket server, virtual threads router, and interceptor loop. *(See **[Core Kernel Guide](docs/KERNEL.md)**)*
    * 📄 **[Event.java](kernel/Event.java)** — JSON event schema, Jackson parsing models, and UDS frame reader. *(See **[Event Taxonomy Reference](docs/EVENTS.md)**)*
    * 📄 **[BasePlugin.java](kernel/BasePlugin.java)** — Shared bootstrap connection and lifecycle boilerplate for plugins. *(See **[BasePlugin Guide](docs/BASE_PLUGIN.md)**)*
    * 📄 **[PluginCatalog.java](kernel/PluginCatalog.java)** — Local directory scanner that reads metadata manifests. *(See **[PluginCatalog Guide](docs/PLUGIN_CATALOG.md)**)*
    * 📄 **[CapabilityIndex.java](kernel/CapabilityIndex.java)** — Dynamic capability provider auction and route indexing rules. *(See **[CapabilityIndex Guide](docs/CAPABILITY_INDEX.md)**)*
    * 📄 **[ProcessManager.java](kernel/ProcessManager.java)** — On-demand subprocess spawner and idle-time process cleaner. *(See **[ProcessManager Guide](docs/PROCESS_MANAGER.md)**)*
    * 📄 **[IdempotencyInterceptor.java](kernel/IdempotencyInterceptor.java)** — Request collapsing tracking and sliding-window caching interceptor. *(See **[Idempotency & Collapsing Guide](docs/IDEMPOTENCY.md)**)*
  * 📁 **`system/`** *(Persistent Orchestrators and Agents)*
    * 📁 **`demo-runner/`** — Verification client orchestrator and performance stats validator. *(See **[Pipeline Verification Demo Guide](docs/VERIFICATION_DEMO.md)**)*
    * 📁 **`summary-agent/`** — Persistent agent that aggregates statistical metrics into structured reports.
  * 📁 **`tools/`** *(Utility Tools and Workers)*
    * 📁 **`word-count/`** — On-demand computational worker that parses and counts text metrics. *(See **[Plugin Creation & Integration Guide](docs/CREATE_PLUGIN.md)**)*
---

## 2. Quick Start: Run the Pipeline in 6 Seconds (Recommended)

You can run the entire pipeline, execute the verification tests, inspect the results, and automatically clean up background processes using JBang and a single command.

### Prerequisites
* **Java Development Kit (JDK) 21** or higher.
* **JBang** installed and configured in your system terminal PATH.

### Run Command
Execute the unified bootrunner from the project root directory:

```bash
jbang Start.java
```

### Expected Console Output
```text
=======================================================
          MK8 MicroKernel — Boot Runner                
=======================================================

[BOOT] Starting Kernel.java in the background...
[BOOT] Waiting for Kernel UDS socket to bind....... Connected! (Socket verified)

[BOOT] Starting SummaryAgent.java in the background...
[BOOT] Executing DemoRunner.java in the foreground...

╔══════════════════════════════════════════════════════╗
║    MK8 Kernel-Extendido — Idempotency & Collapsing    ║
║  3 plugins: DemoRunner → SummaryAgent → WordCount    ║
╚══════════════════════════════════════════════════════╝

[EVENT] Loading config: plugin.json
[DEMO-RUNNER] Connected to kernel.
[DEMO] === STARTING IDEMPOTENCY & COLLAPSING TEST ===

[DEMO] → Sent concurrent request #1 corrId=haiku-collapsed-id
[DEMO] → Sent concurrent request #2 (duplicate) corrId=haiku-collapsed-id
[DEMO] Both requests in-flight. Waiting for collapsing...

[DEMO] 🟢 Plugin spawned: word-count pid=23284
┌─ Result received (latch=3) corrId=haiku-collapsed-id ──
│ 📄 Text Analysis Report
│ ─────────────────────────────
│ Words:            13
│ Sentences:        1
│ Unique words:     12  (rich vocabulary)
│ Avg words/sentence: 13,0
│ ─────────────────────────────
│ Top words: pond(×2) the(×1) into(×1) a(×1) silent(×1)
└──────────────────────────────────────────────────────

┌─ Result received (latch=2) corrId=haiku-collapsed-id ──
│ 📄 Text Analysis Report
│ ─────────────────────────────
│ Words:            13
│ Sentences:        1
│ Unique words:     12  (rich vocabulary)
│ Avg words/sentence: 13,0
│ ─────────────────────────────
│ Top words: pond(×2) the(×1) into(×1) a(×1) silent(×1)
└──────────────────────────────────────────────────────

[DEMO] === RUNNING SEQUENTIAL CACHE HIT TEST ===
[DEMO] Sending duplicate request #3 sequentially (corrId=haiku-collapsed-id)...
[DEMO] → Sent sequential request #3 in 1ms
┌─ Result received (latch=1) corrId=haiku-collapsed-id ──
│ 📄 Text Analysis Report
│ ─────────────────────────────
│ Words:            13
│ Sentences:        1
│ Unique words:     12  (rich vocabulary)
│ Avg words/sentence: 13,0
│ ─────────────────────────────
│ Top words: pond(×2) the(×1) into(×1) a(×1) silent(×1)
└──────────────────────────────────────────────────────


════════════════════════════════════════════════════════════════════════════════
                   🔬 PIPELINE PERFORMANCE & METRICS SUMMARY
════════════════════════════════════════════════════════════════════════════════

📊 Text Computational Metrics Breakdown:
   • Input text: "An old silent pond a frog jumps into the pond splash silence again."
   • Total words: 13 (tokens separated by spaces)
   • Sentences:   1 (single trailing punctuation block)
   • Unique words: 12 (the word "pond" is repeated twice, demonstrating duplicate deduplication)

🚀 Kernel-Level Concurrency Optimizations Verified:
   • Request Collapsing (Single-Flight):
     Request #1 and Request #2 were dispatched concurrently.
     The Kernel's IdempotencyInterceptor intercepted the duplicate in-flight
     correlation ID and collapsed them, routing only ONE invocation to the SummaryAgent.
     Both callers received their outcomes, but downstream work was executed only ONCE.
     (Confirmed: only one analysis request is logged in 'summary-agent.log').

   • Sliding-Window Idempotency Caching:
     Request #3 was dispatched sequentially.
     The Kernel bypassed the active plugins completely, serving the response
     directly from its high-performance memory cache in under 1 millisecond.
     (Confirmed: zero requests were dispatched to SummaryAgent / WordCountTool).

════════════════════════════════════════════════════════════════════════════════
✅ All 3 analyses complete! Idempotency & Collapsing successfully validated.
════════════════════════════════════════════════════════════════════════════════


[BOOT] DemoRunner finished with exit code: 0

[BOOT] Cleaning up background processes...
[BOOT] Terminated background process PID=23251
[BOOT] Terminated background process PID=23261
[BOOT] Cleaned up socket file.
[BOOT] Shutdown complete.
```

---

## 3. The Text Analysis Verification Example

For a detailed explanation of the text analysis example (word, sentence, and vocabulary counting), including the component breakdown, class roles, publisher/consumer event chain tables, and sequence flowcharts, please refer to the dedicated **[Pipeline Verification Demo Guide](docs/VERIFICATION_DEMO.md)**.

---

## 4. Manual Sequential Execution (For Process Inspection)

If you prefer to inspect individual process streams and logs in real-time, execute the components sequentially in three separate terminal windows:

### Step 1: Start the Core Kernel UDS Server
```bash
cd kernel
jbang Kernel.java
```

### Step 2: Start the Summary Agent
```bash
cd system/summary-agent
jbang SummaryAgent.java
```

### Step 3: Execute the Demo Runner
```bash
cd system/demo-runner
jbang DemoRunner.java
```

---

## 5. Documentation Index

Detailed architectural specs and developer guidelines are available in the `docs` folder:

* **[Core Kernel Architecture](docs/KERNEL.md):** Architectural spec of UDS channel communications, length-prefixed binary socket framing, blocking queue buffers, and virtual thread pools.
* **[System Concepts](docs/CONCEPTS.md):** Theoretical breakdowns of distributed tracing correlation, bidding auctions, idempotency caches, request collapsing, and persistent/on-demand lifecycles.
* **[System Architecture & Class Reference](docs/ARCHITECTURE.md):** Concrete references detailing classes, method signatures, package designs, and implementation interfaces.
* **[BasePlugin Reference Guide](docs/BASE_PLUGIN.md):** Developer guide for the reusable plugin connection loops, automatic registries, and virtual thread execution helpers.
* **[CapabilityIndex Guide](docs/CAPABILITY_INDEX.md):** Code guide for dynamic bidding auctions, provider scoring weight tables, and active capability mappings.
* **[ProcessManager Guide](docs/PROCESS_MANAGER.md):** Technical reference for JBang subprocess spawning, Eleven-Factor stream logs logging, and idle timeout sweeping.
* **[Idempotency & Collapsing Guide](docs/IDEMPOTENCY.md):** Implementation details for Single-Flight request collapsing maps, caching eviction schedulers, and memory leak cleanups.
* **[Pipeline Verification Demo Guide](docs/VERIFICATION_DEMO.md):** Concrete blueprint mapping the verification example (text analysis), including events mapping per class, pub/sub chain tables, and sequence flowcharts.
* **[Creating a Plugin from Scratch](docs/CREATE_PLUGIN.md):** Step-by-step developer guide showing how to implement, package, and integrate a new plugin (illustrated via `SentimentAnalysisTool`).
* **[Plugin Configuration Schemas](docs/PLUGIN_SCHEMAS.md):** Structural guide defining all `plugin.json` schema parameters, system/tool declarations, and boot-ordering tiers.
* **[PluginCatalog Guide](docs/PLUGIN_CATALOG.md):** Developer guide for the local directory scanner, folder walking, metadata manifest parsing, indexing collections, and dynamic reload routines.
* **[Event Taxonomy Reference](docs/EVENTS.md):** Complete event library catalog detailing namespaces, payloads, and transaction propagation requirements.
