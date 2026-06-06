# DAG Pattern: Linear (Sequential Pipeline)

Each task depends on the previous one. Use when each step needs the output of the prior step before it can start.

**When to use:** research → write, fetch → transform → save, any strict sequence.

```json
{
  "tasks": [
    {
      "id": "T1",
      "capability": "<search-or-fetch capability>",
      "input": { "query": "the topic" },
      "onFailure": { "action": "retryTask", "maxAttempts": 2 }
    },
    {
      "id": "T2",
      "capability": "<write or transform capability>",
      "input": { "content": "{{T1.output.result}}", "format": "markdown" },
      "dependsOn": ["T1"],
      "onFailure": { "action": "fail" }
    },
    {
      "id": "T3",
      "capability": "<save or publish capability>",
      "input": { "content": "{{T2.output.result}}", "path": "output.md" },
      "dependsOn": ["T2"],
      "onFailure": { "action": "fail" }
    }
  ]
}
```

**Rules:**
- Each task lists exactly the previous task in `dependsOn`.
- The binding is always `{{TN.output.result}}` — never `.summary`, `.text`, `.data`, or any other field.
- Apply `retryTask` on the first task when it calls external APIs (network may be flaky).
