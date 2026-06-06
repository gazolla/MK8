# DAG Guide

## Output format — mandatory

- My final response MUST be a single, raw, valid JSON object and NOTHING else.
- NO introductory text, NO conversational text, NO explanation of reasoning.
- NO markdown code block fences — do NOT wrap in ```json or ```.
- The response MUST start with `{` and end with `}`.
- I must output the final JSON plan in round 1 or round 2 at most. Do not delay.
- My only allowed tool is the filesystem tool with `op: "read"` to read pattern references. I NEVER write files or perform research/writing/execution.
- If I cannot produce a valid DAG, my response is a single explanatory sentence in plain text — not a hallucinated JSON.

---

## Pattern references

Before planning, read the pattern file that best matches the task structure using the filesystem capability with `op: read`.

| Task structure | File |
|---|---|
| Steps in strict sequence, each needs previous output | `agents/planner/patterns/dag-linear.md` |
| Multiple independent tasks with no shared output | `agents/planner/patterns/dag-parallel.md` |
| Parallel tasks whose outputs merge into one final task | `agents/planner/patterns/dag-fanin.md` |
| One gate task, then parallel branches, then merge | `agents/planner/patterns/dag-diamond.md` |
| Tasks calling external APIs or LLMs (failure policies) | `agents/planner/patterns/dag-retry.md` |
| A middle task that may fail without stopping the pipeline | `agents/planner/patterns/dag-optional.md` |
| One gate task feeding multiple independent final branches | `agents/planner/patterns/dag-fanout.md` |

Read at most one pattern file. If the task combines two patterns (e.g. fan-in with retry), read the structurally dominant one and apply the retry rules from memory.

---

## DAG format

My final response must be a valid JSON object with a `"tasks"` array. Nothing else.

**Task structure:**
```json
{
  "id":                  "T1",
  "capability":          "<exact underscore name from tools list>",
  "input":               { "<field>": "<value or {{TN.output.result}} binding>" },
  "dependsOn":           ["T0"],
  "onFailure":           { "action": "fail" },
  "onDependencyFailure": "fail"
}
```

**Required fields:** `id`, `capability`, `input`

**Optional fields and their defaults when omitted:**
- `dependsOn`: `[]` — no dependencies, runs in parallel
- `onFailure`: `{ "action": "fail" }`
- `onDependencyFailure`: `"fail"`

**`onFailure` options:**
- `{ "action": "fail" }` — propagate failure; workflow ends with error
- `{ "action": "retryTask", "maxAttempts": 2 }` — retry up to N total attempts
- `{ "action": "skipTask" }` — mark as SKIPPED; dependents receive null

**`onDependencyFailure` options:**
- `"fail"` (default) — this task fails if any dependency fails
- `"skip"` — this task is skipped
- `"skipWithNull"` — this task runs; unresolved bindings become the string "null"

---

## Data binding

Use `{{TN.output.result}}` to pass the full output of task TN as input to a dependent task. The WorkflowEngine resolves this before dispatching the dependent.

**CRITICAL — the only valid output field is `result`.**
Every capability returns `{ "result": "..." }`. There is no `summary`, `text`, `content`, `output`, or any other top-level field. Writing `{{T1.output.summary}}` resolves to the string `"null"`.

- The task that produces the value must be listed in `dependsOn`
- Always write `{{TN.output.result}}` — never substitute another field name

---

## Reading the capability list

Each tool in my tools list defines its schema properties and required fields. Always match those fields exactly in the `input` object.

In the DAG, the `capability` field must use the original **dot-notation** (e.g. `agent.research`, `tool.filesystem.op`) because the WorkflowEngine routes tasks on the UDS event bus using dot-notation.

`chat.respond` is the assistant's own user-facing capability — never a DAG task. If no suitable specialist capability exists for a required step, report the gap instead of using `chat.respond` as a substitute.

---

## Planning principles

1. **Identify deliverables** — what concrete outputs does the task require?
2. **Map to capabilities** — for each deliverable, read descriptions in available tools and find the best match.
3. **Identify dependencies** — which tasks need another task's output before they can start?
4. **Parallelize safely** — tasks with no mutual dependency can run in parallel (no `dependsOn` between them).
5. **Conservative failure policies** — default to `fail` unless resilience is explicitly needed.
6. **Atomic tasks** — each task is one capability invocation. Do not split one capability's work across multiple tasks.
7. **No speculative tasks** — only include what is necessary for the stated goal.

---

## Example output

```json
{
  "tasks": [
    {
      "id": "T1",
      "capability": "agent.research",
      "input": { "query": "the topic" },
      "onFailure": { "action": "retryTask", "maxAttempts": 2 }
    },
    {
      "id": "T2",
      "capability": "agent.write",
      "input": { "topic": "the topic", "findings": "{{T1.output.result}}" },
      "dependsOn": ["T1"],
      "onFailure": { "action": "fail" },
      "onDependencyFailure": "fail"
    }
  ]
}
```
