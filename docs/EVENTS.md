# Event Taxonomy

This document outlines the complete list of system event types and payload conventions used in the MK8 MicroKernel.

All communications pass through the Unix Domain Socket at `/tmp/mk7/kernel.sock`. The transfer format is a length-prefixed frame: a 4-byte big-endian header specifying the size, followed by a UTF-8 JSON event string.

---

## 1. Unified Event Envelope Schema

Every UDS socket frame contains a serialized JSON event matching this exact properties envelope:

```json
{
  "id": "uuid_event_id",
  "type": "event.type.name",
  "payload": "stringified_content_or_json",
  "timestamp": "2026-05-24T19:38:00",
  "source": "plugin_id_of_sender",
  "correlationId": "optional_correlation_uuid",
  "sessionId": "optional_conversation_session_uuid",
  "workflowId": "optional_workflow_execution_uuid",
  "replyTo": "optional_target_plugin_id",
  "traceId": "optional_distributed_trace_uuid",
  "spanId": "optional_distributed_span_uuid"
}
```

### Distributed Tracing Context (`traceId` and `spanId`)
For real-time telemetry, debugging, and execution timeline rendering (via `/trace`), the event envelope carries two tracking fields:
- `traceId` (UUID): A stable ID representing the complete lifecycle of a conversation transaction initiated by a user prompt or slash command.
- `spanId` (UUID): The local ID of an individual step within the transaction trace (e.g., an LLM call by a secondary agent or a tool invocation).

These IDs are propagated by the Kernel and the active Plugins using transparent `ThreadLocal` structures inside concurrent virtual execution threads.

### Subscription Matching Styles
Clients declare their interests during `plugin.register` using two distinct list arrays:
- `subscribes`: Exact event type matching. Evaluated via $O(1)$ fast-path lookup keys.
- `wildcardSubscribes`: Prefix-based matching. The wildcard character `*` is valid **only at the very end** of the namespace string.

*Valid wildcards*: `blackboard.updated.session.*`, `blackboard.updated.*`  
*Invalid wildcards*: `*.research.*`, `blackboard.*.session` (rejected at registration).

---

## 2. Infrastructure Event Types: `system.*`

```
system.boot              → Kernel initialization completed
system.shutdown          → Kernel is actively stopping
system.health.check      → Periodic diagnostic broadcast query
system.health.response   → Client response to the health check query
system.error             → Unrecoverable system execution error
system.plugin.spawned    → Spawn confirmation: { pluginId|agentId, pid, capability|skillsDir }
system.plugin.stopped    → Safe process termination: { agentId, pid, reason }
system.plugin.died       → Unexpected process termination: { pluginId, pid, exitCode }
```

---

## 3. Plugin Lifecycle Event Types: `plugin.*`

```
plugin.register          → Client announces id, subscribes, and publishes properties
plugin.ready             → Client initialization is complete
plugin.terminate         → Request for client to gracefully shut down
plugin.reload            → Request for client configuration/code hot-reload
plugin.error             → Non-fatal execution error within the client
plugin.load              → Router requests dynamic spawn: { "capability": "tool.datetime.now" }
                           → Spawns the child process and publishes system.plugin.spawned
```

---

## 4. Agent Lifecycle and Negotiation: `agent.*`

```
agent.spawn              → Request process manager to start a secondary agent process
agent.ready              → Secondary agent process connected and configured
agent.terminate          → Request to stop agent process
agent.idle               → Agent reports no active processing task
agent.busy               → Agent reports processing task in progress
agent.reload             → Request agent restart
agent.error              → Non-fatal error in agent processing
agent.negotiate          → Agent proposes alternative execution parameters (Deprecated)
agent.negotiate.reply    → Response to agent negotiation proposal (Deprecated)
```

---

## 5. Peer-to-Peer Agent Communications: `message.*`

```
message.{targetId}       → Direct targeted message to a specific agent
message.reply            → Asynchronous reply mapped via correlationId
message.broadcast        → Global message broadcast to all active agents
```
*Note*: A plugin registers a subscription to `message.{self}`. The Kernel expands this mapping directly to `message.<id>` at registration.

---

## 6. Capability Invocation and Discovery: `capability.*`

```
capability.invoke        → Invoke a capability by name (routed via CapabilityIndex)
capability.result        → Response containing the successful output of any tool or agent execution
capability.error         → Response indicating execution failure
capability.register      → Client registers a capability schema during boot
capability.unregister    → Client removes a capability schema during shutdown
capability.query         → Consult which active plugins offer capability X
capability.query.result  → Consultation outcome response listing providers
capability.bid.request   → Indexer triggers bidding auction across candidates
capability.bid.response  → Candidate submits bid: { agentId, score, load }
capability.bid.accept    → Winner is notified of acceptance
capability.bid.reject    → Candidates are notified of rejection
```

### Invocation Timeout Rules
- **Probe Detection**: **3 seconds**. Quickly identifies when no providers exist without waiting for process timers.
- **Tools (`tool.*`)**: **90 seconds** final execution limit (accounting for dynamic spawning delay).
- **Agents (`agent.*`)**: **600 seconds** (10 minutes) final execution limit.
- **On-Demand Spawn Polling**: Polls every 2 seconds, with a maximum limit of 15 seconds. Exits as soon as the spawned client process registers.

---

## 7. Tool Specific Lifecycle: `tool.*`

```
tool.register            → Announces availability of a new standalone tool
tool.unregister          → Announces removal of a standalone tool
```

---

## 8. Shared Knowledge Blackboard: `blackboard.*`

```
blackboard.write              → Writes or updates an entry (supports LWW or expectedVersion comparison)
blackboard.write.conflict     → Expected version mismatch; sent directly to the editor
blackboard.read               → Read a value by key
blackboard.read.result        → Returns the read result along with its version
blackboard.delete             → Deletes an entry by key
blackboard.purge              → Deletes all entries belonging to a specific scope/scopeId
blackboard.query              → Queries entries by tags, scope, or pattern matching
blackboard.query.result       → Returns the query results
blackboard.updated.{scope}.{key} → Reactive update notification (requires wildcard subscription)
```
- **Scopes**: `session` (by sessionId), `workflow` (by workflowId), `global`.
- **Conflicts**: `blackboard.write.conflict` is delivered directly to the editor connection via `message.{solicitanteId}`.
- **Expiry**: Entries exceeding their `expires_at` value are omitted from queries and reads. Expired values are purged by a cleanup thread every 60 seconds.

---

## 9. DAG Workflow Orchestration: `workflow.*`

```
workflow.dag             → Submit a Directed Acyclic Graph (DAG) for parallel execution
workflow.run             → Execute a pre-registered workflow by name
workflow.cancel          → Cancel an active in-flight workflow
workflow.dag.result      → DAG execution completed successfully
workflow.dag.error       → DAG execution failed
workflow.dag.partial     → DAG execution finished with partial results
workflow.dag.task.start  → An individual task in the DAG started
workflow.dag.task.done   → An individual task in the DAG completed
workflow.dag.task.failed → An individual task in the DAG failed
workflow.run.result      → Named workflow execution result
```

---

## 10. Session Context Management: `session.*`

```
session.create           → Initializes a new conversation session
session.close            → Safe session closure and cleanup
session.context.get      → Request session context snapshot history
session.context.result   → Session context snapshot returned
session.handoff          → Transfer conversational session to another agent
session.summary.request  → Request agent summarization of session history
session.summary.result   → Session history summary returned
```

---

## 11. Chat and User Interface: `chat.*`

```
chat.prompt              → Raw user input message
chat.response            → Final agent response output to the user
chat.typing              → Visual typing indicator state (simple "." on Console, typing status on Telegram)
chat.thinking            → Progressive feedback indicator; payload = serialized ThinkingConfig
chat.command.request     → Slash command request initiated by the user
chat.command.result      → Executed slash command outcome response
chat.error               → Execution error within the chat processing pipeline
```

### Payload Structure of `chat.thinking`
```json
{
  "steps": [
    "⏳ Thinking...",
    "⏳ Analyzing...",
    "⏳ Gathering data...",
    "⏳ Processing...",
    "⏳ Almost there...",
    "⏳ Refining...",
    "⏳ Finalizing...",
    "⏳ One more moment..."
  ],
  "cycleDelayMs": 12000,
  "backgroundThresholdMs": 120000,
  "background": "⏳ Still working on it. I'll notify you here when ready! 🔔"
}
```
Assigned via the `"thinking"` block configuration in `plugin.json` for user-facing agents. Published by `AgentCore.handleChatPrompt()` immediately after dispatching `chat.typing` and prior to acquiring the session execution lock.
