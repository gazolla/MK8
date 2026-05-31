# Pipeline Verification Demo

This document describes the design, execution flow, and event-driven architecture of the built-in MK8 verification pipeline. The demo exercises dynamic plugin discovery, Unix Domain Socket (UDS) communications, on-demand process management, kernel interceptors (idempotency and request collapsing), and asynchronous event fan-in coordination.

---

## 1. How the Verification Pipeline Works

At its core, this example performs **text analysis (word, sentence, and vocabulary counting)** on a specific text segment:
`"An old silent pond a frog jumps into the pond splash silence again."`

We intentionally use this text-counting task to test the Kernel's **concurrency (Request Collapsing)** and **Idempotency** features. To do this, the system deliberately dispatches duplicate text and identical concurrent invocations under the same correlation ID (`haiku-collapsed-id`), forcing the Kernel's interceptors to execute.

### What Each Class Does in the Context of Text Counting

* **`DemoRunner` (The Client):**
  * Initiates the pipeline by sending the text analysis request. 
  * To test **concurrency / request collapsing (Single-Flight)**, it sends the exact same text and correlation ID concurrently *twice* (Requests #1 and #2) almost at the same time.
  * To test **idempotency / caching**, it waits for the first two requests to finish, and then sends the exact same request a *third* time (Request #3) sequentially.
  * Finally, it receives and prints the formatted reports.

* **`SummaryAgent` (The Orchestrator):**
  * Acts as the controller of the text analysis task.
  * It receives the raw text to analyze, delegates the actual calculations to the downstream tool by emitting a sub-invocation, waits for the result, compiles a formatted report, and replies back to the client.

* **`WordCountTool` (The Computational Worker):**
  * Receives the raw text and performs the actual mathematical parsing:
    * **Word Count (13 words):** Splits the text into whitespace tokens.
    * **Sentence Count (1 sentence):** Isolates the text by punctuation flags (`.!?`).
    * **Unique Words (12 unique words):** Standardizes casing and punctuation, counting unique terms. Since the word `"pond"` is repeated twice, the unique count is 12.
    * **Thematic Frequencies:** Extracts and lists the top 5 most repeated terms (`pond(×2)`, `the(×1)`, etc.).
  * It returns these metrics as a JSON string to the orchestrator.

* **`IdempotencyInterceptor` (The Kernel Guard):**
  * Intercepts the invokes at the Kernel level to optimize performance:
    * When Request #2 (duplicate concurrent) arrives, it **collapses** it, blocking it from reaching the `SummaryAgent` again, and waiting for the single-flight result to return.
    * When Request #3 (duplicate sequential) arrives, it instantly returns the cached result in under 1 millisecond, bypassing the plugins completely.

---

## 2. Component and Plugin Breakdown

The demo orchestrates three main actors alongside the Kernel core. Below is the description of the role, lifecycle, and event boundaries of each component:

### A. `DemoRunner` (Client Initiator)
* **Role:** Acts as the external client or user-facing entry point. It submits the initial analysis request to the event bus. To validate the Kernel's performance features, it issues concurrent identical requests (triggering the **Request Collapsing / Single-Flight** interceptor) followed by a sequential identical request (triggering the **Idempotency Cache** interceptor).
* **Lifecycle:** Transitory command-line utility. Exits gracefully once the final report is received or when a failure threshold is hit.
* **Events Published:**
  * `capability.invoke` (requesting the semantic capability `text.analyze`).
* **Events Consumed:**
  * `capability.result` (containing the final consolidated analysis report).

### B. `SummaryAgent` (Persistent Pipeline Orchestrator)
* **Role:** A high-level system agent that orchestrates the execution flow. It intercepts the generic analysis trigger, creates tracking tokens, generates unique sub-transaction correlation IDs, delegates the raw computations to specialised downstream tools, collects their asynchronous replies, and consolidates the output.
* **Lifecycle:** Persistent background process. Boots during system startup (tier-2 ordering) and remains online to handle incoming messages.
* **Events Published:**
  * `capability.invoke` (requesting the computational capability `text.wordcount`).
  * `capability.result` (transmitting the integrated final report back to the parent caller).
  * `capability.error` (notifying the parent caller of downstream pipeline failures).
* **Events Consumed:**
  * `message.summary-agent` (intercepted routing event containing the raw text payload to analyze).
  * `capability.result` (asynchronous callbacks containing statistical outputs from downstream tools).
  * `capability.error` (handling downstream tool execution aborts).

### C. `WordCountTool` (On-Demand Utility)
* **Role:** Performs granular textual calculations (counting total words, sentences, unique vocabulary, and computing relative averages). 
* **Lifecycle:** Elastic and on-demand. Spawns dynamically via `PluginManager` only when a `text.wordcount` capability invocation occurs, and terminates automatically after a 60-second idle period.
* **Events Published:**
  * `capability.result` (transmitting JSON-formatted string metrics back to the orchestrator).
* **Events Consumed:**
  * `capability.tool.text.wordcount` (trigger event containing the text payload to process).

---

## 3. The Publisher/Consumer KernelEvent Chain

The table below maps the complete step-by-step transaction life cycle as events traverse the Kernel's virtual thread routing engine:

| Step | Publisher (Source) | KernelEvent Type (`type`) | Key Payload Properties | Consumer (Destination) | Tracing Scope (`correlationId` / `sessionId`) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **1** | `DemoRunner` | `capability.invoke` | `name: "text.analyze"`, `text: "..."` | `Kernel` (Bus) | `corrId: "corr-demo-1"`, `sessId: "session-xyz"` |
| **2** | `Kernel` (Bus) | `message.summary-agent` | `text: "..."` | `SummaryAgent` | `corrId: "corr-demo-1"`, `sessId: "session-xyz"` |
| **3** | `SummaryAgent` | `capability.invoke` | `name: "text.wordcount"`, `text: "..."` | `Kernel` (Bus) | `corrId: "corr-sub-word-1"`, `sessId: "session-xyz"` |
| **4** | `Kernel` (Bus) | `capability.tool.text.wordcount` | `text: "..."` | `WordCountTool` | `corrId: "corr-sub-word-1"`, `sessId: "session-xyz"` |
| **5** | `WordCountTool` | `capability.result` | `result: "{\"words\":13,...}"` | `Kernel` (Bus) | `corrId: "corr-sub-word-1"`, `sessId: "session-xyz"` |
| **6** | `Kernel` (Bus) | `capability.result` | `result: "{\"words\":13,...}"` | `SummaryAgent` | `corrId: "corr-sub-word-1"`, `sessId: "session-xyz"` |
| **7** | `SummaryAgent` | `capability.result` | `result: "📄 Integrated Report..."` | `Kernel` (Bus) | `corrId: "corr-demo-1"`, `sessId: "session-xyz"` |
| **8** | `Kernel` (Bus) | `capability.result` | `result: "📄 Integrated Report..."` | `DemoRunner` | `corrId: "corr-demo-1"`, `sessId: "session-xyz"` |

---

## 4. Processing Flow Schema

The diagram below details the interaction model across the asynchronous boundaries of the system. Note how the Kernel intercepts the initial transaction, routes it to the persistent `SummaryAgent`, boots the `WordCountTool` on-demand, and returns the final response using nested correlation structures.

```
+--------------+               +---------------+               +──────────────+               +───────────────+
|  DemoRunner  |               |  Kernel Core  |               | SummaryAgent |               | WordCountTool |
+──────┬───────+               +───────┬───────+               +──────┬───────+               +───────┬───────+
       │                               │                              │                               │
       │  1. capability.invoke         │                              │                               │
       │     (name="text.analyze")     │                              │                               │
       ├──────────────────────────────►│                              │                               │
       │                               │                              │                               │
       │                               │──┐ [Record correlation mapping]                              │
       │                               │  │ pendingRoutes["corr-demo-1"] = "demo-runner"              │
       │                               │◄─┘                           │                               │
       │                               │                              │                               │
       │                               │  2. message.summary-agent    │                               │
       │                               ├─────────────────────────────►│                               │
       │                               │                              │                               │
       │                               │                              │──┐ [Process Text]             │
       │                               │                              │  │ Generate sub-corrId:       │
       │                               │                              │  │ "corr-sub-word-1"          │
       │                               │                              │◄─┘                            │
       │                               │                              │                               │
       │                               │  3. capability.invoke        │                               │
       │                               │     (name="text.wordcount")  │                               │
       │                               │◄─────────────────────────────┤                               │
       │                               │                              │                               │
       │                               │──┐ [Record correlation mapping]                              │
       │                               │  │ pendingRoutes["corr-sub-word-1"] = "summary-agent"        │
       │                               │◄─┘                           │                               │
       │                               │                              │                               │
       │                               │──┐ [PluginManager Checks Tool Status]                        │
       │                               │  │ IF WordCountTool offline:                                 │
       │                               │  │ Exec "jbang WordCountTool.java"                            │
       │                               │◄─┘                           │                               │
       │                               │                                                              │
       │                               │  4. capability.tool.text.wordcount                           │
       │                               ├─────────────────────────────────────────────────────────────►│
       │                               │                                                              │
       │                               │                                                              │──┐ [Execute word counting]
       │                               │                                                              │  │ Parse text tokens
       │                               │                                                              │◄─┘
       │                               │                                                              │
       │                               │  5. capability.result (raw json metrics)                      │
       │                               │◄─────────────────────────────────────────────────────────────┤
       │                               │                                                              │
       │                               │──┐ [Lookup Destination for "corr-sub-word-1"]                │
       │                               │  │ Resolves to "summary-agent"                               │
       │                               │◄─┘                           │                               │
       │                               │                              │                               │
       │                               │  6. capability.result        │                               │
       │                               ├─────────────────────────────►│                               │
       │                               │                              │                               │
       │                               │                              │──┐ [Compile Final Report]     │
       │                               │                              │  │ Match via sub-corrId       │
       │                               │  │ Format report string       │                              │
       │                               │                              │◄─┘                            │
       │                               │                              │                               │
       │                               │  7. capability.result (consolidated report)                  │
       │                               │◄─────────────────────────────┤                               │
       │                               │                              │                               │
       │                               │──┐ [Lookup Destination for "corr-demo-1"]                     │
       │                               │  │ Resolves to "demo-runner"                                 │
       │                               │◄─┘                           │                               │
       │                               │                              │                               │
       │                               │  8. capability.result        │                               │
       │◄──────────────────────────────┤                              │                               │
       │                               │                              │                               │
```

---

## 5. Kernel-Level Concurrency Optimizations Exercised

During the processing loop shown above, the Kernel actively monitors for optimization hooks using interceptors before routing packages to virtual threads:

1. **Request Collapsing (Single-Flight):**
   * `DemoRunner` issues two identical `text.analyze` requests concurrently (Requests #1 and #2, same `corrId=haiku-collapsed-id`). The Kernel's `IdempotencyInterceptor` catches the duplicate correlation key, collapses the second call, routes only a single transaction to the `SummaryAgent`, and delivers the final report to both waiting callers once received.
2. **Sliding-Window Idempotency Caching:**
   * After both collapsed results are delivered, `DemoRunner` issues the same request a third time sequentially (Request #3, same `corrId`). The Kernel fulfills the request in **less than 1 millisecond** using its sliding-window cache, bypassing the `SummaryAgent` and `WordCountTool` entirely.
