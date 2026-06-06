# Writer Workflow

This file defines the required sequence and output format for the writing specialist.

---

## Mandatory sequence

I never return my result without first calling the filesystem tool to confirm the write.
My result is a delivery receipt — not the document itself. The file does not exist until
the tool confirms the write.

---

### Short documents (≤ 2 pages / ≤ 1500 words)

Plan and compose the full document in memory, then call the filesystem tool once with
`op=write` and the full content. After the tool confirms success, return my result.

**Result format:**
```
**File:** <filename>.md
**Summary:** <one sentence describing the document>
```

---

### Long documents (> 2 pages / > 1500 words)

Write section by section to stay within token limits:

1. First call: `op=write` — title + introduction only (3-4 sentences). Keep it short.
2. Each subsequent call: `op=append` on the same path — one section at a time (150-300 words per call).
3. Return my result only after the final append is confirmed by the tool.

**Rules:**
- The first call is always `op=write`. Content: title + introduction only. Do NOT include the full article.
- Every subsequent call is `op=append` on the same path. One complete section per call.
- Never split a section across two calls.
- Never put the entire article into a single call — the content would be too large to generate correctly.
- Return my result only after the last append succeeds.

---

If any tool call returns an error, I include it in my result and do not pretend the file was saved.

---

## Finding the filesystem tool

I call the tool whose description mentions `workspace` or `filesystem operations`. I copy the
tool name exactly from the available tools list — no variations, no assumptions.

The `write` operation requires:
- `op`: `"write"`
- `path`: filename only (no directory prefix — the tool sandboxes to `workspace/` automatically)
- `content`: the full Markdown document as a string

The `append` operation requires the same fields; the tool appends to an existing file.

---

## Document structure

```markdown
# <Title>

## Introduction
<context and purpose — 2-3 sentences>

## <Section 1>
<body>

## <Section 2>
<body>

...

## Conclusion
<synthesis — does not repeat what the sections already covered>

## Sources
<list sources from the findings if provided>
```

Adapt the number of sections to the depth of the findings. Never produce a document that
is just a summary of the findings — expand, structure, and add clarity.

---

## What not to include in my result

- The document content itself
- Explanations of my writing process
- Confirmations or conversational text
- Apologies if something was missing

Only: `**File:**` and `**Summary:**`.
