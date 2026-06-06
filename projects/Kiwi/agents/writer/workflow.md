# Writer Workflow

## Template selection

Before composing any document, read the relevant template from `agents/writer/templates/` using the filesystem capability with `op: read`. Each template provides the exact document skeleton, section guidance, and type-specific rules.

| Topic signals | Template file |
|---|---|
| "how to", "guide", "step by step", "configure", "set up", "install", "procedure" | `agents/writer/templates/tmpl-howto.md` |
| "tutorial", "learn", "introduction to", "beginner", "getting started", "understanding X" | `agents/writer/templates/tmpl-tutorial.md` |
| "vs", "versus", "compare", "comparison", "which is better", "alternatives to" | `agents/writer/templates/tmpl-comparative.md` |
| "what is", "how X works", "deep dive", "technical article", "overview of" | `agents/writer/templates/tmpl-technical-article.md` |
| "API", "plugin", "capability", "endpoint", "documentation", "reference" | `agents/writer/templates/tmpl-api-docs.md` |
| "brief", "summary", "decision", "recommendation", "status update", "executive" | `agents/writer/templates/tmpl-executive-brief.md` |
| "research", "findings", "analysis", "report", "investigation", "study" | `agents/writer/templates/tmpl-research-report.md` |

If the topic matches multiple templates, choose the one whose signals are strongest. If no template fits, use `tmpl-technical-article.md` as the default.

Templates are not auto-loaded — they are in a subdirectory and must be explicitly fetched via `op:read`.

---

## CRITICAL RULE — read the template before writing

**My first tool call MUST be `op:read` on the appropriate template file. Writing without reading the template is a failure.**

The template provides:
- The exact section structure the document must follow
- Required sections and their order
- Type-specific rules (word count targets, mandatory subsections, etc.)

**If I write the document without first calling `tool_filesystem_op` with `op: read` on the template, the document will have wrong structure. Round 1 must be a read, not a write.**

---

## How I work

1. **Read the received material** — topic, findings, and any format or language instructions from the invocation payload and Blackboard context.
2. **Select the template** — use the signals table above. Decide which template applies.
3. **Read the template** — call the filesystem capability with `op:read` on the template path in **round 1**. Study the structure before writing a word.
4. **Plan** — list all sections, estimate total word count, decide short vs long strategy, choose the filename (lowercase, hyphens for spaces, `.md` extension).
5. **Create the file** — for short documents: one `op:write` with the full content. For long documents: `op:write` for title + introduction only, then `op:append` per section.
6. **Append sections** — one `op:append` tool call per section until all sections are written (long documents only).
7. **Deliver success response** — only after the last tool call confirms success. Never before.

The sequence is always: read template → plan → write (+ appends) → final response.

---

## Short vs long document strategy

**Short documents (≤ 2 pages / ≤ 1500 words):**
One `op:write` call with the full content.

**Long documents (> 2 pages / > 1500 words):**
Section-by-section strategy:
- `op:write`: title + introduction only (3–4 sentences, not the full article)
- `op:append`: one section at a time, 150–300 words per call
- Never put the entire article in a single tool call — the JSON string would be too large to generate correctly

If the requested length exceeds 2 pages, always use the section-by-section strategy. Decide this in step 4 before the first tool call.
