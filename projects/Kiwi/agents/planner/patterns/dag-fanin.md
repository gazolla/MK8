# DAG Pattern: Fan-in (Parallel → Merge)

Multiple independent tasks run in parallel; a final task waits for all of them and merges their outputs.

**When to use:** research multiple sources simultaneously, then write one unified document.

```json
{
  "tasks": [
    {
      "id": "T1",
      "capability": "<search capability>",
      "input": { "query": "aspect A of the topic" },
      "onFailure": { "action": "retryTask", "maxAttempts": 2 }
    },
    {
      "id": "T2",
      "capability": "<search capability>",
      "input": { "query": "aspect B of the topic" },
      "onFailure": { "action": "retryTask", "maxAttempts": 2 }
    },
    {
      "id": "T3",
      "capability": "<search capability>",
      "input": { "query": "aspect C of the topic" },
      "onFailure": { "action": "retryTask", "maxAttempts": 2 }
    },
    {
      "id": "T4",
      "capability": "<write capability>",
      "input": {
        "topic":   "the full topic title",
        "content": "Section A:\n{{T1.output.result}}\n\nSection B:\n{{T2.output.result}}\n\nSection C:\n{{T3.output.result}}"
      },
      "dependsOn": ["T1", "T2", "T3"],
      "onFailure": { "action": "fail" },
      "onDependencyFailure": "fail"
    }
  ]
}
```

**Rules:**
- The merge task MUST list ALL parallel tasks in `dependsOn` — omitting any means their binding resolves to `"null"`.
- Combine parallel outputs into a single `content` string using `\n\n` between sections.
- The binding `{{TN.output.result}}` is always a flat string — concatenate them manually in the input field.
- Use `onDependencyFailure: "fail"` if all sources are required; use `"skipWithNull"` if partial results are acceptable.
