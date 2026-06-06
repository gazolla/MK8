# DAG Pattern: Optional Step (Skip on Failure)

A middle task is optional — if it fails, the pipeline continues with a null value for that step.
Use when a task enriches the result but is not strictly required.

**When to use:** optional translation, optional formatting, optional enrichment from a secondary source.

```json
{
  "tasks": [
    {
      "id": "T1",
      "capability": "<primary research capability>",
      "input": { "query": "the topic" },
      "onFailure": { "action": "retryTask", "maxAttempts": 2 }
    },
    {
      "id": "T2",
      "capability": "<optional enrichment capability>",
      "input": { "content": "{{T1.output.result}}" },
      "dependsOn": ["T1"],
      "onFailure": { "action": "skipTask" },
      "onDependencyFailure": "skip"
    },
    {
      "id": "T3",
      "capability": "<write capability>",
      "input": {
        "content":     "{{T1.output.result}}",
        "enrichment":  "{{T2.output.result}}"
      },
      "dependsOn": ["T1", "T2"],
      "onFailure": { "action": "fail" },
      "onDependencyFailure": "skipWithNull"
    }
  ]
}
```

**How it works:**
- If T2 fails → it is marked SKIPPED → `{{T2.output.result}}` resolves to `"null"`.
- T3 uses `onDependencyFailure: "skipWithNull"` → it still runs, receiving `"null"` for T2's binding.
- T3 must be written to handle `"null"` gracefully (e.g., omit that section if null).

**Rules:**
- `skipTask` on the optional task makes it SKIPPED, not FAILED — the workflow does not abort.
- The downstream task that consumes the optional output must use `onDependencyFailure: "skipWithNull"`, not `"fail"` — otherwise a skipped task will still abort the downstream.
- Always include the optional task in `dependsOn` of the consumer — even if it might be skipped. Omitting it means the binding is never resolved.
- The required upstream (T1) should NOT use `skipTask` — use `retryTask` or `fail`.
