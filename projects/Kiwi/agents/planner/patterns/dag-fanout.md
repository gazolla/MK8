# DAG Pattern: Fan-out (Gate → Multiple Independent Branches)

One gate task runs first; its output triggers multiple independent tasks in parallel.
Unlike diamond, the branches do NOT converge — each produces its own final output.

**When to use:** one research result feeds multiple independent documents, notifications, or saves.

```json
{
  "tasks": [
    {
      "id": "T1",
      "capability": "<research or fetch capability>",
      "input": { "query": "the topic" },
      "onFailure": { "action": "retryTask", "maxAttempts": 2 }
    },
    {
      "id": "T2",
      "capability": "<write capability>",
      "input": { "content": "{{T1.output.result}}", "format": "summary" },
      "dependsOn": ["T1"],
      "onFailure": { "action": "fail" },
      "onDependencyFailure": "fail"
    },
    {
      "id": "T3",
      "capability": "<notify or publish capability>",
      "input": { "message": "{{T1.output.result}}", "channel": "alerts" },
      "dependsOn": ["T1"],
      "onFailure": { "action": "skipTask" },
      "onDependencyFailure": "fail"
    },
    {
      "id": "T4",
      "capability": "<save capability>",
      "input": { "content": "{{T1.output.result}}", "path": "raw-data.md" },
      "dependsOn": ["T1"],
      "onFailure": { "action": "fail" },
      "onDependencyFailure": "fail"
    }
  ]
}
```

**Rules:**
- T2, T3, T4 each depend only on T1 — they do NOT depend on each other.
- They run in parallel after T1 completes.
- Each branch can have its own failure policy: critical branches use `fail`, non-critical use `skipTask`.
- Do NOT add a final merge task unless the branches need to converge — that would make it a diamond pattern.

**Fan-out vs Diamond:**
- Fan-out: branches diverge and stay independent (no merge task).
- Diamond: branches converge into a final merge task.
