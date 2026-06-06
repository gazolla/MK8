# Template: API / Plugin Documentation

Use when documenting an MK7 plugin, a REST API, or a software component's interface.

**Signals:** topic mentions "API", "plugin", "capability", "endpoint", "documentation", "reference"; findings contain input/output schemas, capability names, or interface descriptions.

---

## Document structure

```markdown
# [Plugin or API Name]

> [One-sentence description of what it does and what problem it solves.]

## Overview

[2–3 paragraphs:
1. What this plugin/API does at a high level.
2. When to use it and when NOT to use it.
3. Key design decisions or constraints the user should know upfront
   (e.g., "This tool is sandboxed to the workspace/ directory" or
   "Requires an API key in the BRAVE_API_KEY environment variable").]

## Installation

[How to activate or install. For MK7 plugins, this is typically automatic.
If there are manual steps (env vars to set, dependencies to install),
list them explicitly here.]

**Required environment variables:**

| Variable | Description | Required |
|---|---|---|
| `VAR_NAME` | What it contains | Yes / No |

[Omit this section if installation is fully automatic and requires nothing from the user.]

## Capabilities

[One subsection per capability. Order by frequency of use — most common first.]

### `capability.name.here`

[One sentence describing what this capability does.]

**Input:**

| Field | Type | Required | Description |
|---|---|---|---|
| `fieldName` | string | Yes | What this field contains |
| `fieldName` | number | No | Default: `0`. What this field controls |

**Output:** `{ "result": [type and description of the result value] }`

**Example invocation:**
\`\`\`json
{
  "name": "capability.name.here",
  "input": {
    "fieldName": "example value"
  }
}
\`\`\`

**Example response:**
\`\`\`json
{ "result": "example output value" }
\`\`\`

**Notes:** [Edge cases, limitations, or important behaviors not obvious from the schema.
Omit if there are none.]

---

### `capability.name.two` (if multiple capabilities)

[Repeat the subsection structure above for each capability.]

## Configuration

[The plugin.json fields that control this plugin's behavior, if any are user-configurable.
Omit if there is nothing meaningful to configure.]

| Field | Default | Description |
|---|---|---|
| `lifecycle.mode` | `persistent` | `persistent` keeps the plugin running; `on-demand` spawns per request |
| `bidWeight` | `1.0` | Relative priority in capability auctions |

## Error Handling

[How this plugin signals errors. In MK7, errors arrive as `capability.error` events.]

| Reason | When it occurs |
|---|---|
| `"Field X is required"` | Input field X was missing or blank |
| `"API returned HTTP 429"` | Rate limit reached — retry after delay |
| `"File not found: path"` | The specified path does not exist |

[Only list errors that the user can act on. Do not list internal/unexpected errors.]

## Examples

### [Example scenario title]

[Brief description of the scenario. Then show the full invocation and response.]

**Request:**
\`\`\`json
{ "name": "capability.name.here", "input": { ... } }
\`\`\`

**Response:**
\`\`\`json
{ "result": "..." }
\`\`\`

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Plugin not appearing in capability list | Not started or crashed at launch | Check `logs/PluginName.log` |
| Result is always `null` or `0` | API key not set or rate limited | Verify env var, check API quota |

```

---

## Rules for this template

- **Every capability gets its own subsection with input table, output description, and example.** Incomplete documentation is worse than no documentation — it creates false confidence.
- **Input tables must cover every field**, including optional ones with their defaults.
- **Output always uses `result` as the key** (MK7 convention). If the result is an object, show its structure.
- **Examples must be realistic**, not `"string"` or `"value"`. Show a real invocation that a user would actually make.
- **Error table only lists actionable errors.** Internal crashes are not actionable — omit them. Focus on errors caused by bad input or external service failures.
- **Notes section is for surprises.** Only include it if there is something non-obvious that would bite the user.
