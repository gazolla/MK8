# MK8 MicroKernel

A modular, asynchronous, and event-driven MicroKernel implemented in Java 21+. Independent OS processes (plugins) discover and invoke each other's capabilities over Unix Domain Sockets (UDS) using JSON event frames — no compile-time dependencies, no shared memory, no service registry.

The **[System Concepts Guide](docs/CONCEPTS.md)** explains the core design patterns: capability bidding auctions, idempotency caching, Single-Flight request collapsing, on-demand process lifecycles, and distributed tracing. The **[System Architecture Reference](docs/ARCHITECTURE.md)** covers the three-layer structure, every class's responsibility, and the end-to-end event flow from invocation to response.

To extend the system, two step-by-step guides are available. **[Creating a Custom Interceptor](docs/CREATE_INTERCEPTOR.md)** walks through building a new pipeline component that plugs into the Kernel's event chain — covering the `EventInterceptor` contract, constructor conventions, thread-safe state management, and the `InterceptorLifecycle` hook for background work. **[Creating a Custom Plugin](docs/CREATE_PLUGIN.md)** covers the full plugin lifecycle: `plugin.json` schema, the three plugin profiles (tool, agent, system), capability declaration, the `PluginBase.run()` skeleton, request-reply patterns, and the three ready-to-use templates.

---

## The Full Picture

```
                    ┌────────────────────────────────────────┐
                    │                KERNEL                  │
                    │                                        │
Plugin A ──UDS───►  │  ┌──────────────────────────────────┐ │
                    │  │       Interceptor Chain           │ │
Plugin B ──UDS───►  │  │  [IdempotencyInterceptor]         │ │  cache / collapse
                    │  │           ↓                       │ │
Plugin C ──UDS───►  │  │  [CapabilityInterceptor]          │ │  registry / auction
                    │  │           ↓                       │ │
                    │  │  [PluginManager]                  │ │  spawn / kill
                    │  │           ↓                       │ │
                    │  │  [Broadcast / Direct Delivery]    │ │
                    │  └──────────────────────────────────┘ │
                    │       ↓           ↓           ↓       │
                    └───────┼───────────┼───────────┼───────┘
                           UDS         UDS         UDS
                            │           │           │
                        Plugin A    Plugin B    Plugin C
```

Every plugin is an independent OS process. The Kernel is the only meeting point. Events flow in, are filtered, routed, cached, and delivered — and each plugin sees only what it subscribed to.

---

## Quick Start

### Prerequisites

- **JDK 21+**
- **[JBang](https://www.jbang.dev/download/)** on your PATH

### Option 1 — Local clone

```bash
git clone https://github.com/gazolla/MK8.git
cd MK8

# Run any example from anywhere inside the project tree:
jbang projects/SimpleProject/Start.java
jbang projects/PluginProject/Start.java
jbang projects/InterceptorsProject/Start.java
jbang projects/ChatAI/Start.java
```

### Option 2 — Directly from GitHub (no clone required)

JBang fetches the script and its dependencies from GitHub. On the first run, the full repository is downloaded and cached at `~/.jbang/mk8/` so subsequent runs start immediately.

```bash
# SimpleProject — raw UDS producer/consumer
jbang https://raw.githubusercontent.com/gazolla/MK8/main/projects/SimpleProject/Start.java

# PluginProject — persistent plugins with PluginManager
jbang https://raw.githubusercontent.com/gazolla/MK8/main/projects/PluginProject/Start.java

# InterceptorsProject — idempotency + collapsing demo
jbang https://raw.githubusercontent.com/gazolla/MK8/main/projects/InterceptorsProject/Start.java

# ChatAI — interactive LLM terminal chat (requires OPENROUTER_API_KEY)
jbang https://raw.githubusercontent.com/gazolla/MK8/main/projects/ChatAI/Start.java
```

**Force cache refresh** (pull latest changes from GitHub):

```bash
jbang https://raw.githubusercontent.com/gazolla/MK8/main/projects/InterceptorsProject/Start.java --update
```

### What happens on first remote run

```
[BOOT] Downloading MK8 from GitHub...
[BOOT] Source: https://github.com/gazolla/MK8/archive/refs/heads/main.zip
[BOOT] Download complete (1 MB). Extracting...
[BOOT] Extracted to: /Users/you/.jbang/mk8

[BOOT] Starting Kernel (IdempotencyInterceptor CapabilityInterceptor PluginManager)...
[BOOT] Waiting for Kernel UDS socket..... Connected!
...
```

### InterceptorsProject — expected output

```
╔══════════════════════════════════════════════════════╗
║    MK8 Kernel-Extendido — Idempotency & Collapsing    ║
║  3 plugins: DemoRunner → SummaryAgent → WordCount    ║
╚══════════════════════════════════════════════════════╝

[DEMO] → Sent concurrent request #1 corrId=haiku-collapsed-id
[DEMO] → Sent concurrent request #2 (duplicate) corrId=haiku-collapsed-id
[DEMO] Both requests in-flight. Waiting for collapsing...

[DEMO] 🟢 Plugin spawned: word-count pid=32221
┌─ Result received (latch=3) corrId=haiku-collapsed-id ──
│ 📄 Text Analysis Report
│ Words: 13  Sentences: 1  Unique: 12  (rich vocabulary)
│ Top words: pond(×2) the(×1) into(×1) a(×1) silent(×1)
└──────────────────────────────────────────────────────

┌─ Result received (latch=2) corrId=haiku-collapsed-id ──
│ 📄 Text Analysis Report  [collapsed — same result, one execution]
└──────────────────────────────────────────────────────

[DEMO] === RUNNING SEQUENTIAL CACHE HIT TEST ===
[DEMO] → Sent sequential request #3 in 0ms

┌─ Result received (latch=1) corrId=haiku-collapsed-id ──
│ 📄 Text Analysis Report  [served from cache — no plugin invoked]
└──────────────────────────────────────────────────────

✅ All 3 analyses complete! Idempotency & Collapsing successfully validated.
```

---

## Project Structure

```
MK8/
│
├── kernel/                              # Core infrastructure (Layers 1 & 2)
│   ├── Kernel.java                      # UDS server, routing tables, interceptor chain
│   ├── KernelEvent.java                 # Universal event envelope, frame protocol, logging
│   ├── BootHelper.java                  # Project root resolution + GitHub download fallback
│   └── interceptors/
│       ├── capability/
│       │   └── CapabilityInterceptor.java   # Live registry, auctions, capability routing
│       ├── idempotency/
│       │   └── IdempotencyInterceptor.java  # Cache, Single-Flight collapsing
│       └── plugin/
│           ├── PluginBase.java          # Plugin connection framework (UDS + event loop)
│           ├── PluginConfig.java        # Typed plugin.json accessors
│           └── PluginManager.java       # Plugin discovery, catalog, process lifecycle
│
├── projects/                            # Runnable examples (Layer 3)
│   ├── SimpleProject/                   # Raw UDS producer/consumer — no PluginBase
│   │   ├── Start.java
│   │   ├── Producer.java
│   │   └── Consumer.java
│   │
│   ├── PluginProject/                   # Persistent plugins with PluginManager
│   │   ├── Start.java
│   │   ├── producer/
│   │   └── consumer/
│   │
│   ├── InterceptorsProject/             # Full interceptor stack demo
│   │   ├── Start.java
│   │   ├── demo-runner/                 # Transient client — fires requests, validates results
│   │   ├── summary-agent/              # Persistent agent — orchestrates tool calls
│   │   └── word-count/                 # On-demand tool — spawned on first invocation
│   │
│   └── ChatAI/                          # Interactive LLM terminal chat
│       ├── Start.java
│       ├── agent/                       # Persistent LLM agent (reads config from plugin.json)
│       └── console/                     # System plugin — stdin → chat.prompt → display
│
└── docs/                                # Documentation
    ├── CONCEPTS.md                      # Core design patterns and system concepts
    ├── ARCHITECTURE.md                  # Three-layer architecture and class reference
    ├── CREATE_INTERCEPTOR.md            # Guide to building a custom interceptor
    ├── CREATE_PLUGIN.md                 # Guide to building a custom plugin
    └── PLUGIN_SCHEMAS.md               # plugin.json field reference for all plugin types
```

---

## Documentation

| Document | Contents |
|---|---|
| [System Concepts](docs/CONCEPTS.md) | Event envelope, UDS framing, pub/sub, capabilities, auctions, idempotency, on-demand lifecycle, virtual threads |
| [System Architecture](docs/ARCHITECTURE.md) | Three-layer design, all class responsibilities, end-to-end invocation flow |
| [Creating an Interceptor](docs/CREATE_INTERCEPTOR.md) | `EventInterceptor` contract, constructor conventions, thread safety, templates |
| [Creating a Plugin](docs/CREATE_PLUGIN.md) | `plugin.json` schema, Tool/Agent/System templates, routing patterns, checklist |
| [Plugin Schemas](docs/PLUGIN_SCHEMAS.md) | Full `plugin.json` field reference: `tool`, `agent`, `system`, `launch`, `llm`, `capabilities` |
