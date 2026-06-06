# DAG Pattern: Resilient Pipeline (Retry Policies)

Standard linear or parallel DAG with explicit retry and failure policies on each task.
Use whenever tasks call external APIs, LLMs, or network resources that may fail transiently.

```json
{
  "tasks": [
    {
      "id": "T1",
      "capability": "<external API or LLM capability>",
      "input": { "query": "the topic" },
      "onFailure": { "action": "retryTask", "maxAttempts": 3 }
    },
    {
      "id": "T2",
      "capability": "<another external capability>",
      "input": { "data": "{{T1.output.result}}" },
      "dependsOn": ["T1"],
      "onFailure": { "action": "retryTask", "maxAttempts": 2 },
      "onDependencyFailure": "fail"
    },
    {
      "id": "T3",
      "capability": "<write or save capability>",
      "input": { "content": "{{T2.output.result}}", "path": "result.md" },
      "dependsOn": ["T2"],
      "onFailure": { "action": "fail" },
      "onDependencyFailure": "fail"
    }
  ]
}
```

**When to apply each policy:**

| Task type | `onFailure` recommendation |
|---|---|
| External API / web search | `retryTask, maxAttempts: 2–3` |
| LLM call (research, write) | `retryTask, maxAttempts: 2` |
| File save (filesystem) | `fail` — no point retrying a disk error |
| Non-critical enrichment | `skipTask` — pipeline continues without it |

**Rules:**
- `maxAttempts` includes the first attempt. `maxAttempts: 2` means 1 retry.
- `retryTask` only retries the task itself — it does not re-run its dependencies.
- `onDependencyFailure` controls what happens to this task when an upstream task fails or is skipped — set to `"fail"` (default) if the output is required, `"skipWithNull"` if the task can run with a null value.
- File write tasks should always use `onFailure: fail` — retrying a write error is rarely useful.
