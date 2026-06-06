# Template: Comparative Analysis

Use when the goal is to evaluate two or more options against each other and produce a recommendation.

**Signals:** topic contains "vs", "versus", "compare", "comparison", "which is better", "alternatives to"; findings contain data about multiple options.

---

## Document structure

```markdown
# [Option A] vs [Option B][vs Option C]: [The Decision Question]

## Overview
[1–2 paragraphs: what is being compared, why this comparison matters, 
and what question it answers. State the context: who is the audience and 
what decision are they trying to make?
Example: "This analysis compares Quarkus, Spring Boot, and Micronaut for 
building MK7 system plugins — focusing on startup time, memory footprint, 
and JBang compatibility."]

## Evaluation Criteria

The comparison uses the following criteria:

| Criterion | Weight | Why it matters |
|---|---|---|
| [Criterion 1] | High / Medium / Low | [Reason] |
| [Criterion 2] | High / Medium / Low | [Reason] |
| [Criterion 3] | High / Medium / Low | [Reason] |

[Explain why these specific criteria were chosen and what was excluded. 
Criteria should reflect the actual decision context, not a generic checklist.]

## Comparison at a Glance

| Criterion | [Option A] | [Option B] | [Option C] |
|---|---|---|---|
| [Criterion 1] | ✅ Excellent | ⚠️ Adequate | ❌ Poor |
| [Criterion 2] | ⚠️ Adequate | ✅ Excellent | ✅ Excellent |
| [Criterion 3] | ✅ Excellent | ⚠️ Adequate | ⚠️ Adequate |
| **Overall** | **Strong** | **Strong** | **Moderate** |

[Use ✅ / ⚠️ / ❌ consistently. "Overall" row is a qualitative summary, not a numeric score.]

## Detailed Analysis

### [Criterion 1: Name]

**[Option A]:** [2–4 sentences with specific evidence. Avoid vague praise — 
cite numbers, features, or concrete behaviors.]

**[Option B]:** [...]

**[Option C]:** [...]

---

### [Criterion 2: Name]

**[Option A]:** [...]

**[Option B]:** [...]

**[Option C]:** [...]

---

[Repeat for each criterion.]

## Verdict

**For [use case / context A]:** Choose **[Option X]** because [specific reason tied to criteria].

**For [use case / context B]:** Choose **[Option Y]** because [specific reason].

[If one option is clearly superior across all use cases, say so directly and explain why.
Do not hedge excessively — a verdict that recommends everything is not a verdict.]

### When NOT to use any of these options
[Optional: if there are scenarios where none of the compared options is appropriate, 
name them and briefly explain what to use instead.]

## Sources

- [Source](URL) — used for [Option A] data
- [Source](URL) — used for [Option B] data
```

---

## Rules for this template

- **The verdict must be a decision, not a hedge.** "It depends on your use case" without specifics is not a verdict — it is an avoidance of judgment. Always give concrete "if X then Y" recommendations.
- **Criteria must be defined before the table.** The reader should understand the evaluation framework before seeing the scores.
- **Evidence over assertion.** "Option A is faster" is weak. "Option A starts in 30ms vs Option B's 300ms under identical conditions" is strong. Use numbers and specifics from the findings.
- **The summary table rows must match the detailed analysis sections.** Every criterion in the table must have a corresponding detailed section.
- **Do not add criteria not supported by the findings.** If the research did not cover security, do not include a security row.
- **Symmetric treatment.** Give each option roughly the same depth of analysis per criterion.
