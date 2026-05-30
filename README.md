# MK8 MicroKernel

A modular, asynchronous, and event-driven MicroKernel system implemented in Java 21+. It enables independent processes (plugins) to discover and call each other's capabilities over Unix Domain Sockets (UDS) using JSON event frames, eliminating compile-time or static network dependencies.

For a conceptual deep dive into the system's core design patterns — such as capability bidding auctions, idempotency caching, Single-Flight request collapsing, lifecycles, and distributed tracing — consult the **[System Concepts Guide](docs/CONCEPTS.md)**. To explore the concrete code layout, class descriptions, and core API interfaces (`EventInterceptor`, `KernelBus`, `PluginRuntime`), review the **[System Architecture Reference](docs/ARCHITECTURE.md)**.

---

## 1. Project Structure

* 📁 **MK8 (Project Root)**
  * 📄 **[Start.java](Start.java)** — Bootrunner: spawns Kernel and SummaryAgent in the background, streams DemoRunner in the foreground, auto-generates `logs/start.log` via `TeeOutputStream`, and tears down all processes on exit.
  * 📄 **[README.md](README.md)** — Main entry point (Setup, Quick Start, and documentation index).
  * 📁 **`logs/`** — Auto-created on first run. Contains `start.log` (full run transcript), `kernel.log`, `summary-agent.log`, and `word-count.log`.
  * 📁 **`kernel/`** *(MicroKernel Core Infrastructure)*
    * 📄 **[Kernel.java](kernel/Kernel.java)** — UDS server, routing tables, interceptor chain wiring, and top-level records (`CatalogEntry`, `PrefixRoute`, `Connection`). Also defines the `EventInterceptor`, `KernelBus`, and `PluginRuntime` interfaces. *(See **[Core Kernel Architecture](docs/KERNEL.md)**)*
    * 📄 **[Event.java](kernel/Event.java)** — JSON event envelope, 4-byte length-prefixed frame protocol, and Jackson factory methods. *(See **[Event Taxonomy Reference](docs/EVENTS.md)**)*
    * 📄 **[BasePlugin.java](kernel/BasePlugin.java)** — Plugin bootstrap: UDS connect, `plugin.register` handshake, virtual-thread dispatcher, and auto-bidding. *(See **[BasePlugin Reference](docs/BASE_PLUGIN.md)**)*
    * 📄 **[PluginManager.java](kernel/PluginManager.java)** — Single source of truth for plugins: directory scan, catalog indexing, on-demand process spawn, idle-kill sweep. Implements `PluginRuntime`. *(See **[PluginManager Guide](docs/PLUGIN_MANAGER.md)**)*
    * 📄 **[CapabilityIndex.java](kernel/CapabilityIndex.java)** — Live capability registry, bidding auction engine, built-in capability dispatcher. Depends only on `PluginRuntime` — zero reference to `PluginManager`. *(See **[CapabilityIndex Guide](docs/CAPABILITY_INDEX.md)**)*
    * 📄 **[IdempotencyInterceptor.java](kernel/IdempotencyInterceptor.java)** — Single-Flight request collapsing and sliding-window result cache. *(See **[Idempotency & Collapsing Guide](docs/IDEMPOTENCY.md)**)*
  * 📁 **`system/`** *(Persistent Orchestrators)*
    * 📁 **`demo-runner/`** — Verification client: fires concurrent and sequential requests, validates collapsing and cache hits. *(See **[Pipeline Verification Demo](docs/VERIFICATION_DEMO.md)**)*
    * 📁 **`summary-agent/`** — Persistent orchestrator that delegates to WordCountTool and returns formatted analysis reports.
  * 📁 **`tools/`** *(On-Demand Workers)*
    * 📁 **`word-count/`** — On-demand tool: word, sentence, and unique-word counting. *(See **[Creating a Plugin from Scratch](docs/CREATE_PLUGIN.md)**)*

---

## 2. Quick Start: Run the Pipeline in One Command

### Prerequisites
* **JDK 21+**
* **JBang** on your PATH

### Run Command
```bash
jbang Start.java
```

### Expected Console Output
```text
=======================================================
          MK8 MicroKernel — Boot Runner                
=======================================================

[BOOT] Starting Kernel.java in the background...
[BOOT] Waiting for Kernel UDS socket to bind..... Connected! (Socket verified)

[BOOT] Starting SummaryAgent.java in the background...
[BOOT] Executing DemoRunner.java in the foreground...

╔══════════════════════════════════════════════════════╗
║    MK8 Kernel-Extendido — Idempotency & Collapsing    ║
║  3 plugins: DemoRunner → SummaryAgent → WordCount    ║
╚══════════════════════════════════════════════════════╝

[DEMO] === STARTING IDEMPOTENCY & COLLAPSING TEST ===

[DEMO] → Sent concurrent request #1 corrId=haiku-collapsed-id
[DEMO] → Sent concurrent request #2 (duplicate) corrId=haiku-collapsed-id
[DEMO] Both requests in-flight. Waiting for collapsing...

[DEMO] 🟢 Plugin spawned: word-count pid=32221
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
[DEMO] → Sent sequential request #3 in 0ms
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
[BOOT] Terminated background process PID=32186
[BOOT] Terminated background process PID=32197
[BOOT] Cleaned up socket file.
[BOOT] Shutdown complete.
```

---

## 3. The Text Analysis Verification Example

For a detailed explanation of the text analysis example (word, sentence, and vocabulary counting), including the component breakdown, class roles, publisher/consumer event chain tables, and sequence flowcharts, please refer to the dedicated **[Pipeline Verification Demo Guide](docs/VERIFICATION_DEMO.md)**.

---

## 4. Manual Sequential Execution (For Process Inspection)

If you prefer to inspect individual process streams and logs in real time, execute the components sequentially in three separate terminal windows:

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

* **[Core Kernel Architecture](docs/KERNEL.md):** UDS channel communications, length-prefixed binary framing, virtual-thread topology, and the interceptor chain pipeline.
* **[System Concepts](docs/CONCEPTS.md):** Capability auctions, idempotency caching, Single-Flight request collapsing, persistent/on-demand lifecycles, and distributed tracing.
* **[System Architecture & Class Reference](docs/ARCHITECTURE.md):** Class layout, interface specs (`EventInterceptor`, `KernelBus`, `PluginRuntime`), and method signatures.
* **[Event Taxonomy Reference](docs/EVENTS.md):** Complete event catalog: `capability.*`, `system.plugin.*`, `message.*`, built-in capability names.
* **[PluginManager Guide](docs/PLUGIN_MANAGER.md):** Catalog scan, `CatalogEntry` record, on-demand process spawn, idle-kill sweep, and the `PluginRuntime` interface.
* **[CapabilityIndex Guide](docs/CAPABILITY_INDEX.md):** Live registry, bidding auction engine, `handleInvoke` flow, and built-in handler dispatch map.
* **[Idempotency & Collapsing Guide](docs/IDEMPOTENCY.md):** Single-Flight collapsing implementation, sliding-window result cache, and memory-leak guards.
* **[BasePlugin Reference](docs/BASE_PLUGIN.md):** Plugin bootstrap, `run()`, `publish()`, auto-bidding (`handleBidAuto`), and virtual-thread dispatch lifecycle.
* **[Pipeline Verification Demo Guide](docs/VERIFICATION_DEMO.md):** Component roles, publisher/consumer event chain table, and sequence flowchart for the text analysis pipeline.
* **[Creating a Plugin from Scratch](docs/CREATE_PLUGIN.md):** Step-by-step guide: `plugin.json`, instance pattern (`new Plugin().start()`), triggerEvent wiring, and pipeline integration.
* **[Plugin Configuration Schemas](docs/PLUGIN_SCHEMAS.md):** Full `plugin.json` field reference for `system`, `tool`, and `agent` types; launch block; boot-order tiers.
