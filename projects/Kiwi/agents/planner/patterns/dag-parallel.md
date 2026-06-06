# DAG Pattern: Parallel (Independent Tasks)

All tasks run simultaneously — no dependencies between them. Use when tasks do not need each other's output.

**When to use:** fetch weather + fetch news + fetch prices at the same time, write multiple independent documents.

```json
{
  "tasks": [
    {
      "id": "T1",
      "capability": "<capability-A>",
      "input": { "query": "topic A" },
      "onFailure": { "action": "retryTask", "maxAttempts": 2 }
    },
    {
      "id": "T2",
      "capability": "<capability-B>",
      "input": { "query": "topic B" },
      "onFailure": { "action": "retryTask", "maxAttempts": 2 }
    },
    {
      "id": "T3",
      "capability": "<capability-C>",
      "input": { "query": "topic C" },
      "onFailure": { "action": "retryTask", "maxAttempts": 2 }
    }
  ]
}
```

**Rules:**
- No `dependsOn` field (or empty array) means the task starts immediately.
- Tasks with no mutual dependency always run in parallel — do not add artificial `dependsOn` to serialize them.
- Each task fails or retries independently.
- If any task must complete before others start, use the Fan-out pattern instead.
