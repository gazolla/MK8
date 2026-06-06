# Creator Pipeline

## Determining type and name

If `type` was not provided in the invocation, infer it from `spec`:
- Spec describes a function, computation, or external API call → `tool`
- Spec describes reasoning, decision-making, or LLM-based behavior → `agent`
- Spec describes infrastructure or coordination → `system`

If `name` was not provided, derive it from the spec: lowercase, hyphen-separated, max 3 words. Examples: `weather-tool`, `translator`, `rate-limiter`.

---

## Pipeline — mandatory sequence

Execute these steps strictly in order. Wait for each tool/capability call result before proceeding.

**Critical rule — errors stop the pipeline**: If any delegation or tool call returns a result that starts with `"Error:"`, return failure immediately with the reason. Do not search, retry, or compensate. The only exception is Step 2: if the code review returns `approved: no` (a review verdict, not an error), retry code generation once.

**Critical rule — timeouts are fatal**: `"Error: invocation timed out"` is an infrastructure failure, not recoverable by retrying. Report it immediately.

### Step 0 — Research (conditional: only when spec involves an external API or service)

If the spec mentions calling an external API, web service, or third-party integration:
- Call the capability whose description mentions **searching current information from the web**
- Ask specifically: Is this API still publicly available? What is the current endpoint and required parameters? What does a working request and response look like?
- Pass the research result verbatim as the `context` field in the Step 1 invocation

If the spec is self-contained, skip this step entirely. **Step 0 executes at most once — at the very start. If past Step 1, do not search.**

### Step 1 — Generate

Call the capability whose description mentions **generating MK8 plugin code** — producing `plugin.json` and Java source from a spec. Invoke with:
- `spec`: the full spec
- `type`: `tool`, `agent`, or `system`
- `name`: the derived plugin name

Extract sections from the result:
- `-- plugin.json --` → everything between this delimiter and the next `--`
- `-- java --` → everything between this delimiter and the next `--` (may be empty for agents)
- `-- persona.md --` → everything after this delimiter (only for agent type)

### Step 2 — Review

Call the capability whose description mentions **reviewing or validating MK8 plugin code**. Invoke with:
- `code`: the extracted java section
- `pluginJson`: the extracted plugin.json section
- `spec`: the original spec

Check result:
- `approved: yes` → continue to Step 2.5
- `approved: no` → one retry allowed: re-invoke code generation with original `spec`, `type`, `name`, and a `context` that includes prior research findings AND the full issues list. Then re-invoke code review.
  - Re-review `approved: yes` → continue
  - Re-review `approved: no` → report failure with both issues lists

### Step 2.5 — Save Java source to workspace (tool and system types only; skip for agent)

Call the filesystem capability with:
- `op`: `write`
- `path`: `<name>.java`
- `content`: the extracted java section verbatim — copy character-for-character

Wait for confirmation before proceeding.

### Step 3 — Install

Call the capability whose description mentions **installing MK8 plugins**.

**For tool and system types** (after Step 2.5):
- `type`: `tool` or `system`
- `name`: the plugin name (MUST match the original invocation input, NOT the `id` from plugin.json)
- `pluginJson`: the extracted section
- `javaFilePath`: `<name>.java`
- `overwrite`: `true` if the original invocation input included `overwrite: true`

**For agent type** (no Java file):
- `type`: `agent`
- `name`: the plugin name
- `pluginJson`: the extracted section
- `personaMd`: the extracted section
- `overwrite`: `true` if the original invocation input included `overwrite: true`

### Step 4.5 — Smoke test

After a successful install, verify the plugin works at runtime:
- Read `capabilities[0].name` from the generated plugin.json
- Read `capabilities[0].inputSchema.properties` and `required`
- Build a minimal test input: `"test"` for strings, `0` for numbers, first enum value for enums
- Invoke the capability directly with the test input

Semantic validation:
- A result of `0`, `0.0`, or negative for a price/rate capability → smoke test **failed**
- An empty string or `"null"` for a text/name capability → smoke test **failed**
- A plausibly correct result → smoke test **passed**

Do not retry on smoke test failure. Proceed to Step 4 and include the failure detail in the report.

### Step 4 — Confirm

Write final response (see persona.md for format).

---

## Parsing delimited sections

The code generation output uses `-- section-name --` delimiters.

```
-- plugin.json --
{ "id": "weather-tool", ... }
-- java --
///usr/bin/env jbang ...
-- persona.md --
# WeatherAgent
...
```

Extraction rules:
- Copy the exact text between delimiters, character-for-character
- Do NOT paraphrase, summarize, truncate, or reconstruct from memory
- Do NOT add or remove fields from the extracted JSON
- The `-- section-name --` delimiter line itself is NOT part of the content
- For agents, `-- java --` may be empty — pass an empty string as `javaCode`
- If `-- persona.md --` is absent (tool or system type), omit `personaMd` from the install input

---

## Step budget

| Step | Delegation or tool call |
|------|------------------------|
| Pre-pipeline — Read existing code (overwrite=true) | 2–3 tool calls (optional) |
| Step 0 — Research | 1 delegation (optional) |
| Step 1 — Generate | 1 delegation |
| Step 2 — Review | 1 delegation |
| Step 2 retry — Re-generate | 1 delegation (optional) |
| Step 2 retry — Re-review | 1 delegation (optional) |
| Step 2.5 — Save Java | 1 tool call (tool/system only) |
| Step 3 — Install | 1 tool call |
| Step 4.5 — Smoke test | 1 tool call |

**Budget allocation**: maxToolCalls=10 (for tool calls). maxDelegations=5 (for agent delegations). These budgets are tracked separately — tool calls do not consume delegation budget and vice versa.

---

## Dynamic Discovery of System Capabilities

Following the autoconsciência model, do not assume hardcoded capability names. Discover the appropriate capability dynamically from the native `ToolSpecification` list provided by the API:

- For **saving/reading files, checking file existence, or listing files**, find and use the tool whose description includes executing sandboxed filesystem operations.
- For **compiling and deploying plugins**, find and use the tool whose description mentions installing or updating plugins.
- For **generating code from a natural language spec**, find and invoke the specialist whose description mentions code generation or producing Java and json plugins.
- For **reviewing and validating Java/json plugin code**, find and invoke the specialist whose description mentions code review or validating plugins against conventions.
