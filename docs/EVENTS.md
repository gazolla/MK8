# KernelEvent Taxonomy

This document lists the event types that the MK8 MicroKernel actually implements and routes. Events are the only communication mechanism between the Kernel and plugins — all messages travel as length-prefixed JSON frames over a Unix Domain Socket at `/tmp/mk8/kernel.sock`.

The bus is intentionally extensible: any plugin can publish and subscribe to custom event types. Only the types listed here carry built-in Kernel or interceptor behavior.

---

## 1. Unified KernelEvent Envelope Schema

Every UDS frame carries a serialized JSON event:

```json
{
  "id": "7ca62fee-cf82-4ad3-9ef4-d36cb07f18b3",
  "type": "capability.invoke",
  "payload": "{\"name\":\"text.analyze\",\"text\":\"An old silent pond...\"}",
  "timestamp": "2026-05-30T13:45:00.123Z",
  "source": "demo-runner",
  "correlationId": "haiku-collapsed-id",
  "sessionId": "demo-session",
  "workflowId": null,
  "replyTo": null,
  "traceId": null,
  "spanId": null
}
```

### Subscription Matching Styles

Plugins declare their interests during `plugin.register` using two distinct arrays:

- `subscribes`: Exact event type matching — $O(1)$ lookup.
- `wildcardSubscribes`: Prefix-based matching — `*` is only valid at the end of the string.

*Valid*: `capability.tool.*`, `system.plugin.*`
*Invalid*: `*.invoke`, `capability.*.result` (rejected at registration).

---

## 2. Plugin Lifecycle: `plugin.*`

| KernelEvent | Direction | Description |
| :--- | :--- | :--- |
| `plugin.register` | Plugin → Kernel | Announces plugin id, subscribes, wildcardSubscribes, and capabilities. First frame on every connection. |
| `plugin.ready` | Kernel → Plugin | Kernel confirms the plugin is registered and active. |
| `plugin.installed` | External → Kernel | Signals that a new plugin directory was dropped on disk. Re-scans the catalog on receipt. |

---

## 3. Capability Invocation and Discovery: `capability.*`

The core protocol for service discovery, routing, and result delivery.

| KernelEvent | Direction | Description |
| :--- | :--- | :--- |
| `capability.register` | Plugin → Kernel | Plugin registers a capability it provides. Consumed by `CapabilityInterceptor`; drains any `pendingInvokes` queued while the plugin was starting. |
| `capability.unregister` | Plugin → Kernel | Plugin removes a previously registered capability. |
| `capability.invoke` | Any → Kernel | Invoke a capability by name. `CapabilityInterceptor` resolves the live provider; `IdempotencyInterceptor` collapses concurrent duplicates. The `correlationId` is stored in `pendingRoutes` to route the result back to the caller. |
| `capability.result` | Plugin → Kernel | Successful result. `IdempotencyInterceptor` caches the payload and routes to the original caller via `pendingRoutes`. |
| `capability.error` | Plugin → Kernel | Failure result. Same routing path as `capability.result`. |
| `capability.query` | Any → Kernel | Requests the list of active capability providers. `CapabilityInterceptor` replies with `capability.query.result`. |
| `capability.query.result` | Kernel → Caller | Response to `capability.query`. Payload is a JSON array merging live (`"live": true`) and catalog (`"live": false`) entries. |
| `capability.bid.request` | Kernel → Candidates | Published by `CapabilityInterceptor` when multiple providers exist for a capability. Each candidate is expected to reply with `capability.bid.response` within a 500 ms window. |
| `capability.bid.response` | Plugin → Kernel | Candidate submits its bid: `{ "score": 0.9, "load": 0.1 }`. `CapabilityInterceptor` selects the winner using `score × (1 − load)`. |
| `capability.tool.{name}` | Kernel → Tool | Trigger event published by `CapabilityInterceptor` when it resolves a `capability.invoke` to a tool plugin that declared a `triggerEvent`. Example: `capability.tool.text.wordcount`. |

### Built-in and System Capability Names

These names are recognized inside `capability.invoke` payload and handled:

| Name | Handler | Description |
| :--- | :--- | :--- |
| `system.capability.list` | `CapabilityInterceptor` | Returns a JSON array of all registered capabilities (live + catalog). |
| `system.plugin.list` | `PluginManager` | Dynamic capability. Returns a `capability.result` payload listing all managed plugins and their status (PID, alive, lastUsed). Routed via trigger event `system.plugin.list.request`. |

---

## 4. Direct Peer-to-Peer Routing: `message.*`

| KernelEvent | Direction | Description |
| :--- | :--- | :--- |
| `message.{pluginId}` | Any → Kernel | Delivers a frame directly to the named plugin connection, bypassing the subscription tables. The Kernel resolves `byPluginId[targetId]` and enqueues the frame. |

*Example*: `CapabilityInterceptor` routes a `capability.invoke` for an agent (no `triggerEvent`) by publishing `message.summary-agent` with the original payload.

---

## 5. System Notifications: `system.*`

Published by `PluginManager` and `CapabilityInterceptor` to orchestrate dynamic discovery and process lifecycle management asynchronously.

### A. Lifecycle Notifications: `system.plugin.*`

| KernelEvent | Publisher | Description |
| :--- | :--- | :--- |
| `system.plugin.spawn` | CapabilityInterceptor | Requests launching an on-demand plugin. Consumed by `PluginManager`. |
| `system.plugin.usage` | CapabilityInterceptor | Reports capability activity to update idle timers. Consumed by `PluginManager`. |
| `system.plugin.spawned` | PluginManager | Child process started successfully. Payload: `{ "pluginId": "word-count", "pid": 12345 }`. |
| `system.plugin.stopped` | PluginManager | Child process terminated gracefully (idle-timeout). |
| `system.plugin.died` | PluginManager | Child process exited unexpectedly (crash or non-zero exit code). |

### B. Catalog Sync Notifications: `system.catalog.*`

| KernelEvent | Publisher | Description |
| :--- | :--- | :--- |
| `system.catalog.entry` | PluginManager | Broadcasts catalog config details during directory scans. Consumed by `CapabilityInterceptor` to build its local registry mappings. |
| `system.catalog.ready` | PluginManager | Signals directory scanning is fully complete. Consumed by `CapabilityInterceptor` to complete its startup readiness promise. |
| `system.catalog.refresh`| Any → PluginManager| Requests directory re-scan. Consumed by `PluginManager`. |

