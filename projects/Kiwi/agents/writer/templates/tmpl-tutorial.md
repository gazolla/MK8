# Template: Tutorial

Use when the goal is to teach — the reader builds understanding and skill progressively, not just follow steps to an end result.

**Signals:** topic contains "tutorial", "learn", "introduction to", "beginner", "getting started", "understanding X"; findings are educational in nature; the document should leave the reader capable of doing something new.

**Tutorial vs How-To:** A how-to solves one specific problem ("How to configure X"). A tutorial builds competence ("Understanding and using X"). In a tutorial, explanation and context are as important as the instructions.

---

## Document structure

```markdown
# [Topic] — A Practical Introduction
[Alternative title forms: "Understanding [Topic]", "Getting Started with [Topic]",
"[Topic] from First Principles"]

## What You'll Learn

By the end of this tutorial, you will be able to:

- [Concrete skill or knowledge 1 — use an action verb: "explain", "build", "configure", "debug"]
- [Concrete skill or knowledge 2]
- [Concrete skill or knowledge 3]

[2–4 objectives. They must be specific enough that the reader can evaluate
whether they were achieved. "Understand X" alone is too vague — "Explain how
X handles Y" is measurable.]

## Prerequisites

**Knowledge:**
- [What the reader must already know. Be honest — an unprepared reader will fail.]

**Tools:**
- [Software, accounts, or access needed before starting]
- [Versions if relevant]

## Introduction

[2–3 paragraphs: 
1. The problem or motivation — why does this topic exist and why does it matter?
2. The core concept or mental model — the one idea the reader must hold to understand everything else.
3. What this tutorial will build or demonstrate.

This section does more work than any other. A reader who understands the mental model
in the Introduction will absorb the rest quickly. A reader who skips it will struggle.

Do NOT summarize the tutorial structure here. Teach the concept, then move forward.]

## [Section 1: First Concept or Foundation]

[Introduce the first concept with explanation before examples. 
The ratio of explanation to example should be roughly 50/50 — 
neither all theory nor all code dumps.

Then demonstrate with a minimal, clear example:]

\`\`\`language
// Minimal example that demonstrates ONLY this concept.
// No extra complexity. No "we'll explain this later."
\`\`\`

[Explain what the example shows. What should the reader observe or notice?
What would change if they modified part X?]

[End each section by connecting to the next: "Now that we understand X, 
we can look at how Y builds on it."]

## [Section 2: Next Concept, Building on Section 1]

[Each section should build on the previous. The tutorial has a narrative arc:
each concept prepares the reader for the next.

Introduce concept → Show minimal example → Explain → Show extended example → Connect forward.]

\`\`\`language
// Extended example — more realistic than the minimal one, but still focused.
\`\`\`

## [Section 3: Putting It Together]

[A more complete example that combines the concepts from previous sections.
This is where theory meets practice at realistic scale.]

\`\`\`language
// Complete, working example — the payoff for having followed the tutorial.
\`\`\`

[Walk through the example explaining the key parts. Do not explain every line —
focus on the parts that use the concepts taught.]

## Summary

[1–2 paragraphs: what the reader learned and why it matters.
Connect the skills back to real problems they can now solve.
Do NOT list the sections — synthesise the learning.]

**Key concepts covered:**
- [Concept 1 — one phrase]
- [Concept 2 — one phrase]
- [Concept 3 — one phrase]

## Exercises

[2–4 exercises that require the reader to apply what they learned.
Good exercises have clear goals but do not give the answer.
Vary difficulty: one straightforward exercise, one that requires combining concepts,
one that extends beyond what was shown.]

1. **[Exercise name]:** [Description. What to build or modify. What the result should look like.]

2. **[Exercise name]:** [...]

3. **[Exercise name — stretch goal]:** [A harder task that goes beyond the tutorial content.]

## Further Reading

- [Resource 1](URL) — [What it covers and why it is valuable]
- [Resource 2](URL) — [...]

[Only include resources that directly extend what was taught. 
Do not list generic documentation links as filler.]
```

---

## Rules for this template

- **Teach the mental model first.** The reader who understands WHY will figure out the HOW. The reverse is rarely true.
- **Each section must build on the previous.** If sections can be read in any order, they are reference documentation, not a tutorial.
- **Minimal examples before complete examples.** Show the simplest possible demonstration of each concept before combining concepts.
- **Exercises require application, not recall.** "What is X?" is a quiz. "Build a version of X that does Y differently" is an exercise.
- **Learning objectives must be measurable.** If the reader cannot tell whether they achieved an objective, rewrite it.
- **Explain examples, do not just show them.** A code block without explanation is a how-to step, not a tutorial element.
- **Connect sections explicitly.** The last sentence of each section should point toward the next.
