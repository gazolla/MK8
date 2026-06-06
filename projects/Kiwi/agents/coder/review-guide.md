# Code Review Guide

When the payload has `code` and `pluginJson`:

1. Read all three fields (`code`, `pluginJson`, `spec`)

2. Check **correctness**: does the code implement what `spec` says?

3. Check **syntax completeness**:
   - Count `{` vs `}` — they must be equal. Unclosed class or method is a fatal error.
   - The Java source must end with `}`. If the last non-whitespace char is not `}`, the code is truncated.
   - JBang header directives (`//JAVA`, `//DEPS`, `//SOURCES`) must have a **space** between the `//KEYWORD` and the value: `//SOURCES ../../kernel/Event.java` not `//SOURCES../../`. Missing space breaks jbang parsing.
   - `Event event` variable scope: must be declared BEFORE any `try {` block in `handle()`. If inside a try block, the catch block can't access it — fatal compilation error.

4. Check **MK8 conventions**:
   - JBang header present and correct (for tools/system)
   - For `type: "tool"` or `type: "agent"`: every capability in `plugin.json` has a corresponding handler AND a `capability.bid.request` handler in the Java code
   - For `type: "system"`: system plugins are always persistent. They MAY have capabilities (with bid handlers) when they provide services to agents — e.g. `system.workflow.submit`, `system.agent.spawn`. If a system plugin has capabilities, it must handle `capability.bid.request` and publish `capability.result`/`capability.error` like any other plugin.
   - All errors caught; `capability.error` published (not thrown uncaught) — applies to ALL types
   - No hardcoded absolute paths; no path traversal
   - `result` field always present in `capability.result` payload

5. Check **imports**: every class used has a corresponding `import` statement. Common omissions: `java.util.Map`, `java.util.List`, `java.util.HashMap`, `java.util.concurrent.atomic.AtomicLong`, `java.time.Instant`.

6. Check **plugin.json**: all required fields; for tools, `triggerEvent` matches what the code subscribes to; `subscribes`/`publishes` complete

7. Output using the delimited section format in `output-format.md`

**I NEVER write files to workspace during review. My only output channel is my final response.**
