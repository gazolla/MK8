# Template: Research Report

Use when the findings come from a Researcher agent and the goal is to present verified information with sources and confidence levels.

**Signals:** findings contain URLs, confidence levels, or source attributions; topic is a factual question ("what is", "who is", "how does", "latest developments in").

---

## Document structure

```markdown
# [Topic — phrase it as a title, not a question]

## Summary
[2–3 sentences that directly answer the research question. This is the TL;DR — 
write it for someone who will read only this section. Do NOT start with 
"This report covers..." or "In this document...". State the answer directly.]

## Background
[Context the reader needs to understand the findings: why this topic matters,
what problem it addresses, or what state it was in before. 1–3 paragraphs.
Omit if the topic is self-explanatory.]

## Findings

### [Subtopic A]
[First major finding or theme from the research material. Use direct statements,
not hedged language unless the source itself expressed uncertainty.]

### [Subtopic B]
[Second major finding. Add as many H3 subsections as the material warrants —
do not force everything into one block of text.]

### [Subtopic C — if needed]
[...]

## Analysis
[Interpretation: what patterns emerge across the findings, what the findings
imply, what is surprising or counter-intuitive. This section is the writer's
synthesis — not a repetition of the findings. 1–3 paragraphs.
Omit if the findings speak for themselves and no deeper interpretation was requested.]

## Conclusion
[Direct answer to the original research question, 1–2 paragraphs. 
Include caveats: what is uncertain, what could change, what was not found.
Do NOT introduce new information here — only synthesise what is in Findings.]

## Sources
[List each source on its own line. Include confidence level if the Researcher provided it.]

- [Source Title](URL) — Confidence: High
- [Source Title](URL) — Confidence: Medium — [reason for lower confidence]
- [Source Title](URL) — Confidence: Low — [reason]

[If no URLs were provided in the findings, list sources as plain text.]
```

---

## Rules for this template

- **Summary ≠ Conclusion.** Summary is the upfront answer (TL;DR). Conclusion is the closing synthesis with caveats.
- **Findings are factual; Analysis is interpretive.** Do not mix them.
- **Preserve uncertainty.** If the Researcher flagged something as uncertain or low-confidence, carry that flag into the document.
- **Do not fabricate sources.** If the findings do not include URLs or source names, write "Source not provided in research material."
- **Length is proportional to findings.** A 200-word findings block does not need a 600-word report. Match depth to material.
- **Language follows the findings language** unless the invocation specifies otherwise.
