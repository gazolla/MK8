# DAG Pattern: Diamond (Gate → Parallel → Merge)

One gate task runs first, then multiple tasks run in parallel (all depending on the gate), then one final task merges everything.

**When to use:** fetch raw data once → process it in multiple ways simultaneously → consolidate results.

```json
{
  "tasks": [
    {
      "id": "T1",
      "capability": "<fetch or research capability>",
      "input": { "query": "the topic" },
      "onFailure": { "action": "retryTask", "maxAttempts": 2 }
    },
    {
      "id": "T2",
      "capability": "<summarise capability>",
      "input": { "content": "{{T1.output.result}}" },
      "dependsOn": ["T1"],
      "onFailure": { "action": "skipTask" }
    },
    {
      "id": "T3",
      "capability": "<translate or transform capability>",
      "input": { "content": "{{T1.output.result}}", "language": "Portuguese" },
      "dependsOn": ["T1"],
      "onFailure": { "action": "skipTask" }
    },
    {
      "id": "T4",
      "capability": "<write or publish capability>",
      "input": {
        "summary":     "{{T2.output.result}}",
        "translation": "{{T3.output.result}}"
      },
      "dependsOn": ["T2", "T3"],
      "onFailure": { "action": "fail" },
      "onDependencyFailure": "skipWithNull"
    }
  ]
}
```

**Rules:**
- T2 and T3 both depend on T1 only — they do NOT depend on each other (that would serialize them).
- T4 depends on BOTH T2 and T3 — it starts only after both complete (or are skipped).
- Use `skipTask` on middle tasks when partial failure is tolerable, and `skipWithNull` on the merge task so it still runs even if a middle task was skipped.
- The gate task (T1) should use `retryTask` — if it fails, the entire diamond fails.
