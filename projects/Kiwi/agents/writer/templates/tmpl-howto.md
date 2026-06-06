# Template: How-To Guide

Use when the goal is to walk the reader through accomplishing a specific task step by step.

**Signals:** topic contains "how to", "guide", "setup", "configure", "install", "deploy", "create"; findings contain procedural instructions or numbered steps.

---

## Document structure

```markdown
# How to [Accomplish Specific Goal]

## Overview
[1–2 paragraphs: what the reader will accomplish, why it matters, and roughly 
how long it takes or how complex it is. Set expectations clearly.
Do NOT use "In this guide we will..." — start with the outcome instead:
"By the end of this guide, you will have..."]

## Prerequisites

Before you start, ensure you have:

- **[Requirement 1]:** [brief explanation if not obvious]
- **[Requirement 2]:** [version, access level, or configuration needed]
- **[Knowledge requirement]:** [e.g., "Basic familiarity with the command line"]

[Omit this section only if there are genuinely no prerequisites.]

## Steps

### Step 1: [Descriptive Name — not just "Step 1"]

[1–2 sentences explaining WHAT this step does and WHY before explaining HOW.]

[Instructions. Use code blocks for commands, configuration, or file content:]

\`\`\`bash
command --flag value
\`\`\`

**Expected result:** [What the reader should see or have after completing this step. 
This is critical — it lets the reader verify they did it correctly.]

---

### Step 2: [Descriptive Name]

[...]

**Expected result:** [...]

---

### Step 3: [Descriptive Name]

[...]

**Expected result:** [...]

[Add as many steps as needed. Each step should be atomic — one clear action.]

## Troubleshooting

| Symptom | Likely cause | Solution |
|---|---|---|
| [Error message or symptom] | [Why it happens] | [How to fix it] |
| [Error message or symptom] | [Why it happens] | [How to fix it] |

[Include only issues that are realistically likely. Do not invent problems.
Omit this section if the findings contain no troubleshooting information.]

## Next Steps

[1–3 bullet points pointing the reader toward what to do or learn next.
Keep brief — this is a pointer, not a new section of content.]

- [Next action or related guide]
- [Where to learn more]
```

---

## Rules for this template

- **Step names must be descriptive.** "Install dependencies" is better than "Step 1". The reader should understand the step from its title alone.
- **Expected results are mandatory for each step.** They are what transform instructions into a verifiable process.
- **One action per step.** If a step does two unrelated things, split it.
- **Code blocks for everything machine-readable:** commands, config files, code snippets, file paths with precise values.
- **Troubleshooting only for real problems.** Do not pad with hypothetical issues.
- **Prerequisites are honest.** If root access or a paid account is needed, say so upfront.
