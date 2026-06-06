# Template: Technical Article

Use for longer-form technical content that explains a concept, technology, or approach in depth.

**Signals:** topic is a concept, technology, or system to be explained ("what is X", "how X works", "deep dive into X"); findings are rich enough to support 500+ words; no single specific task to accomplish (that would be how-to).

---

## Document structure

```markdown
# [Title — informative and specific, not clickbait]

## Introduction

[Open with the problem or context — why does this topic matter right now?
1–2 paragraphs. Hook the reader with a concrete scenario or question before
explaining what the article covers.

End this section with a brief statement of what the reader will understand
after reading: "This article explains X, covers Y, and shows Z."

Do NOT start with "In this article..." as the first words. Lead with the context.]

## Background

[Foundational concepts the reader needs before the main content.
Define key terms. Explain prerequisites without being condescending.

Omit this section entirely if the audience is clearly expert-level or if the
topic itself is introductory. Do not pad with basic definitions that the
reader obviously already knows.]

## [Main Section 1 — descriptive title]

[First major topic. 2–5 paragraphs. Use H3 for sub-points if needed.
Write for understanding, not just information transfer — explain WHY, not just WHAT.]

### [Subsection if needed]
[...]

## [Main Section 2 — descriptive title]

[Second major topic. Same depth guidance as above.]

### [Subsection if needed]
[...]

## [Main Section 3 — descriptive title]

[Third major topic. Add or remove main sections based on the material —
do not force exactly three if two or four fit better.]

## Practical Examples

[Concrete demonstrations of the concepts explained above.
Use code blocks, diagrams (described in text), or worked examples.
This section bridges theory and practice.

Omit only if the entire article is already hands-on and examples are
embedded in the main sections.]

\`\`\`language
// example code, configuration, or command
\`\`\`

[Brief explanation of what the example demonstrates.]

## Conclusion

[Synthesise the key insights from the article — 1–2 paragraphs.
Answer: what should the reader take away? What are the implications?
What should they do or explore next?

Do NOT summarise each section mechanically ("In section 1 we covered...").
Draw a conclusion from the whole, not a recap of the parts.]

## References

- [Source Title](URL)
- [Source Title](URL)
```

---

## Rules for this template

- **Sections titles must describe content, not label it.** "How the Event Bus Works" is better than "Architecture". "Why Latency Matters More Than Throughput" is better than "Performance".
- **Introduction ends with scope, not a table of contents.** One sentence stating what the article covers is enough — do not list every section.
- **Background is for gaps, not review.** Only include background that the target reader actually needs. Assume a reasonably informed technical audience unless the findings indicate otherwise.
- **Main sections must connect.** Use transitional sentences between sections so the article reads as a coherent argument, not a list of facts.
- **Conclusion introduces no new information.** It synthesises and points forward — nothing that was not in the body.
- **Code examples must be self-contained and runnable where possible.** A snippet that requires invisible context is not an example — it is a puzzle.
- **Length follows the material.** 600–1500 words is typical. Do not pad to reach a target length, and do not truncate to avoid length.
