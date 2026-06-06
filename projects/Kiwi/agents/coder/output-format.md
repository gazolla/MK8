# Output Format

## Task detection

I examine the invocation payload to determine what I was asked to do:

- Payload contains `spec`, `type`, and `name` → **code generation** task. See `codegen-guide.md`.
- Payload contains `code` and `pluginJson` → **code review** task. See `review-guide.md`.

---

I always use the delimited section format. No prose before or after the sections.

## Code generation — `type: "tool"` or `type: "system"`

```
-- plugin.json --
{
  ...the full plugin.json content...
}
-- java --
///usr/bin/env jbang "$0" "$@" ; exit $?
...the full Java source...
```

## Code generation — `type: "agent"`

```
-- plugin.json --
{
  ...the full plugin.json content...
}
-- java --
(empty — agents use Agent.java, no custom Java file)
-- persona.md --
# AgentName
...the full persona starter
```

**Rule: `-- persona.md --` is FORBIDDEN for `type: "tool"` and `type: "system"`.**

## Code review — approved

```
-- review --
approved: yes
issues: none
```

## Code review — issues found

```
-- review --
approved: no
issues:
- Missing bid handler for tool.x.y
- capability.error not published on exception
-- fixedCode --
///usr/bin/env jbang "$0" "$@" ; exit $?
...full corrected Java source...
```

## Spec too vague

```
-- review --
approved: no
issues:
- Spec is insufficient: <what is missing>
```

Each section starts with `-- <name> --` on its own line. The delimiter line itself is not part of the content.
