# Creating a Plugin from Scratch

This guide describes how to construct, configure, and register a new plugin for the MK8 MicroKernel. 

As a reference, we will build a **Sentiment Analysis Tool** (`SentimentAnalysisTool`). This tool operates in **on-demand** mode, intercepts a custom trigger event, analyzes the sentiment of a text (Positive, Negative, or Neutral), and returns the results.

---

## 1. Directory Structure

Every plugin must reside in its own dedicated directory. This directory can contain the plugin metadata, source files, and dependencies.

For our tool, construct this directory:
```
tools/sentiment-analysis/
├── plugin.json                 # Declarative configuration and capability schemas
└── SentimentAnalysisTool.java  # Java executable source file
```

---

## 2. Configuration: `plugin.json`

The `plugin.json` file declares the plugin metadata, lifecycle rules, launch parameters, and capability schemas. 

Create `tools/sentiment-analysis/plugin.json` containing:

```json
{
  "id": "sentiment-analysis",
  "type": "tool",
  "version": "1.0.0",
  "description": "Analyzes the sentiment of a given text.",
  "lifecycle": {
    "mode": "on-demand",
    "idleTimeoutSeconds": 60
  },
  "launch": {
    "name": "SentimentAnalysis",
    "command": ["jbang", "SentimentAnalysisTool.java"],
    "order": 25
  },
  "capabilities": [
    {
      "name": "text.sentiment",
      "description": "Calculates the sentiment score of the text.",
      "triggerEvent": "capability.tool.text.sentiment",
      "bidWeight": 1.0
    }
  ],
  "subscribes": [
    "capability.tool.text.sentiment"
  ]
}
```

### Key Fields Explained
- `lifecycle.mode`: Set to `"on-demand"` so the `ProcessManager` only spawns the tool when `text.sentiment` is invoked, and kills it when idle.
- `launch.command`: The command array used by the kernel to start the process.
- `capabilities[].triggerEvent`: For tools, this is the event type the kernel routes to the tool. The tool must subscribe to this exact type in its `subscribes` list.

> [!NOTE]
> For a detailed reference of all configuration properties, boot order tiers, and block structures available in `plugin.json`, consult the [Plugin Configuration Schemas](PLUGIN_SCHEMAS.md) guide.

---

## 3. Implementation: `SentimentAnalysisTool.java`

Plugins use JBang to run without manual classpath configurations. They import the kernel's shared `Event.java` and `BasePlugin.java` files using JBang's `//SOURCES` directives.

Create `tools/sentiment-analysis/SentimentAnalysisTool.java` containing:

```java
///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../kernel/Event.java
//SOURCES ../../kernel/BasePlugin.java

import com.fasterxml.jackson.databind.JsonNode;
import java.io.OutputStream;
import java.util.Map;

/**
 * SentimentAnalysisTool — on-demand sentiment analysis tool.
 *
 * Demonstrates:
 *   - On-demand lifecycle execution
 *   - Custom triggerEvent interception
 *   - Simple rule-based sentiment calculation
 */
public class SentimentAnalysisTool {

    public static void main(String[] args) throws Exception {
        System.out.println("[SENTIMENT] Starting (on-demand)...");

        // BasePlugin.run loads config, connects UDS, registers capability, and runs event loop
        BasePlugin.run("plugin.json", Event.DEFAULT_SOCKET, (json, out) -> {
            Event event = Event.MAPPER.readValue(json, Event.class);
            
            if ("capability.tool.text.sentiment".equals(event.type())) {
                handleSentiment(event, out);
            }
        });
    }

    private static void handleSentiment(Event event, OutputStream out) throws Exception {
        // Parse incoming payload
        JsonNode payload = Event.MAPPER.readTree(event.payload());
        String text = payload.path("text").asText("");
        System.out.println("[SENTIMENT] Analyzing: " + text.length() + " chars");

        // Execute sentiment analysis (rule-based heuristic)
        String sentiment = calculateSentiment(text);

        // Package result
        String resultPayload = Event.MAPPER.writeValueAsString(Map.of(
                "result", sentiment
        ));

        // Reply to the kernel using the origin event's correlationId and reply routing
        BasePlugin.publish(
                Event.reply(event, "capability.result", resultPayload, "sentiment-analysis"),
                out
        );
        System.out.println("[SENTIMENT] Done — sentiment=" + sentiment);
    }

    private static String calculateSentiment(String text) {
        String lower = text.toLowerCase();
        
        // Define simple token sets
        String[] positiveWords = {"happy", "joy", "good", "great", "excellent", "pond", "frog", "splash"};
        String[] negativeWords = {"sad", "bad", "terrible", "poor", "pain", "annoyed", "error"};

        int score = 0;
        for (String w : positiveWords) {
            if (lower.contains(w)) score++;
        }
        for (String w : negativeWords) {
            if (lower.contains(w)) score--;
        }

        if (score > 0) return "POSITIVE (score: +" + score + ")";
        if (score < 0) return "NEGATIVE (score: " + score + ")";
        return "NEUTRAL (score: 0)";
    }
}
```

> [!NOTE]
> To understand the detailed anatomy of event records, tracing scopes, and all standardized event namespaces (e.g., `capability.*`, `system.*`, `chat.*`), check the [Event Taxonomy Reference](EVENTS.md).

---

## 4. Integration into the Pipeline

Integrating a newly developed plugin into an active application pipeline requires connecting its declared capabilities to higher-level orchestrator agents or dynamic routing boundaries. In the MK8 MicroKernel architecture, this integration is fully decoupled, event-driven, and governed by the Kernel's event loop and dynamic process manager.

---

### The Problem It Solves

Traditional software and microservice architectures coordinate dependencies using direct service calls or static request broker setups. This creates several structural drawbacks:

| Architectural Metric | Traditional Coupled Pipelines (REST/gRPC/Imports) | MK8 Decoupled MicroKernel Pipeline |
| :--- | :--- | :--- |
| **Service Discovery** | Requires static registry endpoints, configuration management, or dedicated DNS layers. | **Zero-Configuration Dynamic Discovery:** Kernel scans local folders, reads `plugin.json` manifests, and indexes capabilities dynamically on boot. |
| **Process Lifecycle** | Services must run continuously in memory, consuming background CPU/RAM regardless of use. | **Elastic On-Demand Lifecycles:** The Process Manager launches the plugin process on demand and terminates it after an idle timeout. |
| **Code Coupling** | Compile-time import coupling or heavy runtime dependency wrappers in client code. | **Semantic Messaging Interface:** Clients call capabilities purely by semantic namespace (e.g., `text.sentiment`), remaining agnostic of provider language, PID, or location. |
| **Concurrency Control**| Custom client-side logic required for thread-safe fan-in, timeouts, and tracking correlation IDs. | **Asynchronous Correlated Boundaries:** Built-in transaction tracing using `correlationId` and `sessionId` managed through lightweight, thread-safe asynchronous routing. |
| **Request Optimization**| Duplicate concurrent calls generate redundant network traffic and service load unless external caching is configured. | **Kernel-Level Request Collapsing:** Concurrent duplicate invokes are automatically collapsed into a single flight, with results cached and broadcast back to all callers. |

---

### When to Use Pipeline Integration

Pipeline integration should be selected over monolithic function calls or tight microservices coupling in the following scenarios:

1. **Parallel Tool Orchestration (Fan-Out/Fan-In Pattern):**
   * When an agent needs to invoke multiple separate utilities concurrently (e.g., executing word counting and sentiment analysis simultaneously) and synthesize a combined response once all tasks finish.
2. **Multi-Stage Data Pipelines (Chaining Pattern):**
   * When the output of one utility serves as the input of the next in a linear sequence (e.g., Text Extraction $\rightarrow$ Language Detection $\rightarrow$ Translation $\rightarrow$ Summarization).
3. **Polyglot Extensibility:**
   * When implementing system features in different runtimes (e.g., wrapping a Python machine-learning model alongside a Java parser and a Node.js scraper) without leaving the platform's unified event space.
4. **Elastic Compute Management:**
   * When hosting compute-heavy utilities that are used sporadically and should not persist in system memory.

---

### End-to-End Execution Flow

The sequence diagram below visualizes how the Kernel coordinates an asynchronous orchestration request. In this scenario, `DemoRunner` invokes a high-level `SummaryAgent`, which in turn triggers both `WordCountTool` and `SentimentAnalysisTool` concurrently, fanning their responses back into a unified report.

```
DemoRunner              Kernel Event Bus            SummaryAgent       WordCountTool   SentimentAnalysisTool
    │                           │                        │                  │                    │
    │  capability.invoke        │                        │                  │                    │
    │  (name="text.analyze")    │                        │                  │                    │
    ├──────────────────────────►│                        │                  │                    │
    │                           │ pendingRoutes[corrA]   │                  │                    │
    │                           │   = "demo-runner"      │                  │                    │
    │                           │                        │                  │                    │
    │                           │ message.summary-agent  │                  │                    │
    │                           ├───────────────────────►│                  │                    │
    │                           │                        │                  │                    │
    │                           │                        │──┐ [Spawn sub-tasks]                  │
    │                           │                        │  │ Generate unique corrIds:           │
    │                           │                        │  │ - wordCountCorrId (corrB)          │
    │                           │                        │  │ - sentimentCorrId (corrC)          │
    │                           │                        │◄─┘                                    │
    │                           │                        │                  │                    │
    │                           │  capability.invoke     │                  │                    │
    │                           │  (name="text.wordcount"│                  │                    │
    │                           │   corrId=corrB)        │                  │                    │
    │                           │◄───────────────────────┤                  │                    │
    │                           │ pendingRoutes[corrB]   │                  │                    │
    │                           │   = "summary-agent"    │                  │                    │
    │                           │                        │                  │                    │
    │                           │  capability.invoke     │                  │                    │
    │                           │  (name="text.sentiment"│                  │                    │
    │                           │   corrId=corrC)        │                  │                    │
    │                           │◄───────────────────────┤                  │                    │
    │                           │ pendingRoutes[corrC]   │                  │                    │
    │                           │   = "summary-agent"    │                  │                    │
    │                           │                        │                  │                    │
    │                           │ [ProcessManager boots  │                  │                    │
    │                           │  both tools on-demand] │                  │                    │
    │                           ├────────────────────────┼─────────────────►│                    │
    │                           ├────────────────────────┼──────────────────┼───────────────────►│
    │                           │                        │                  │                    │
    │                           │                        │                  │ [Processes text]   │
    │                           │  capability.result     │                  │                    │
    │                           │  (from "word-count",   │                  │                    │
    │                           │   corrId=corrB)        │                  │                    │
    │                           │◄───────────────────────┼──────────────────┤                    │
    │                           │ pendingRoutes[corrB]   │                  │                    │
    │                           │   -> "summary-agent"   │                  │                    │
    │                           │                        │                  │                    │
    │                           │ message.summary-agent  │                  │                    │
    │                           ├───────────────────────►│                  │                    │
    │                           │                        │──┐ [Store WordCount result]           │
    │                           │                        │  │ Match via corrId corrB             │
    │                           │                        │◄─┘                                    │
    │                           │                        │                  │                    │
    │                           │                        │                  │ [Processes text]   │
    │                           │  capability.result     │                  │                    │
    │                           │  (from "sentiment",    │                  │                    │
    │                           │   corrId=corrC)        │                  │                    │
    │                           │◄───────────────────────┼──────────────────┼────────────────────┤
    │                           │ pendingRoutes[corrC]   │                  │                    │
    │                           │   -> "summary-agent"   │                  │                    │
    │                           │                        │                  │                    │
    │                           │ message.summary-agent  │                  │                    │
    │                           ├───────────────────────►│                  │                    │
    │                           │                        │──┐ [Store Sentiment result]           │
    │                           │                        │  │ Match via corrId corrC             │
    │                           │                        │  │ Fan-in complete: generate report   │
    │                           │                        │◄─┘                                    │
    │                           │                        │                  │                    │
    │                           │  capability.result     │                  │                    │
    │                           │  (aggregated report,   │                  │                    │
    │                           │   corrId=corrA)        │                  │                    │
    │                           │◄───────────────────────┤                  │                    │
    │                           │ pendingRoutes[corrA]   │                  │                    │
    │                           │   -> "demo-runner"     │                  │                    │
    │                           │                        │                  │                    │
    │  capability.result        │                        │                  │                    │
    │  (final report to caller) │                        │                  │                    │
    │◄──────────────────────────┤                        │                  │                    │
```

---

### Step-by-Step Integration Guide

#### 1. Capability Discovery
When the MicroKernel starts up, the `PluginCatalog` searches the directories listed in the workspace (such as `/tools` and `/system`). If it encounters a new plugin directory containing a `plugin.json` manifest:
1. It parses the JSON schema.
2. It indexes all listed capabilities in the `CapabilityIndex`.
3. It associates the dynamic trigger events (e.g. `capability.tool.text.sentiment`) with the process launch instructions.

#### 2. Declaring Dependencies and Scope
To participate in the pipeline, orchestrator agents or callers must register to receive results or event replies. This is achieved by:
* Listing the target namespaces in the plugin's `subscribes` field (e.g., subscribing to `capability.result`).
* Forwarding the parent transaction context by preserving the `sessionId` and routing responses back using correct correlation IDs.
