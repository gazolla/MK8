# Research Output Format

This file defines the expected structure of research findings returned as my final result.

The output format depends on what the caller actually needs — information, a file, or a detailed report. Read the invocation carefully and choose the right format.

---

## File delivery output

Use when the caller needs an actual file — image, PDF, archive, or any binary content.

Retrieve and save the file to workspace/ using available retrieval capabilities. Then confirm:

```
## File Delivered
Saved to workspace: <filename> (<size>)

**Source:** <URL where the file was found>
**Confidence:** High — file retrieved and saved successfully
```

If retrieval fails after trying multiple sources, report what was attempted and why it failed — never return a list of links as a substitute for the file.

**Image sources that work for direct download:** Wikimedia Commons (`commons.wikimedia.org`), Wikipedia article images, open news photo URLs ending in `.jpg`/`.png`. Search specifically for these using terms like `site:commons.wikimedia.org` or `filetype:jpg`.

**Image sources that block scraping (avoid):** Getty Images, AP Images, Reuters, Shutterstock, Unsplash (requires API key), Google Images (returns JavaScript-rendered HTML). If a URL returns 401, 403, or HTML instead of image bytes, abandon it immediately and search for a different source.

---

## Shallow output (default)

Use when the invocation does not specify `depth`, or specifies `depth: "shallow"`.
One or two tool calls, direct summary answer.

```
## Summary
<direct answer, 2-3 sentences covering the most important finding>

**Sources:** [Title](URL)
**Confidence:** High/Medium/Low — <one-sentence reason>
```

---

## Deep output

Use when the invocation specifies `depth: "deep"`, or when the Blackboard `task.context` signals a long document is needed ("article," "5 pages," "detailed," "report," "comprehensive").

Return structured findings with enough content to support a long article. Each section should have at least 3–4 substantive sentences — no bullet-point skeletons.

```
## Summary
<overview paragraph — 3-4 sentences establishing the topic>

## Key Concepts
<explanation of the core ideas, mechanisms, or principles — as much detail as found>

## Current State and Applications
<what exists today, real-world uses, notable examples>

## Challenges and Limitations
<known problems, open questions, constraints>

## Future Directions
<research trends, anticipated developments>

**Sources:** [Title](URL), [Title](URL)
**Confidence:** High/Medium/Low — <one-sentence reason>
```

Adapt sections to the topic — not every topic has "Challenges" or "Future Directions." Omit empty sections; add topic-specific sections if the sources warrant it.

---

## Confidence levels

- **High**: multiple independent sources agree; information is recent and verifiable.
- **Medium**: one reliable source, or information is less recent but from a credible outlet.
- **Low**: single source of uncertain quality, or could not directly verify the claim.

When confidence is Low, always say what could not be verified and why.

---

## What not to include

- Introductions or preambles ("Great question!", "I researched this topic and...")
- Explanations of your process or which tools you used
- Speculation presented as fact
- Assertions about time-sensitive facts without a source
- Any content that was not confirmed by a tool call
