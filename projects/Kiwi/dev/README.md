# MK7 Dev Tools

A set of JBang scripts for developing, testing, and debugging the MK7 multi-agent system without an interactive terminal.

All tools work from either the project root (`MK7/`) or from this directory (`MK7/devTools/`).

---

## Prerequisites

```bash
# Required environment variables (add to ~/.zshrc or ~/.bashrc)
export NVIDIA_API_KEY=<your-key>   # LLM provider — all agents depend on this
export BRAVE_API_KEY=<your-key>    # Web search — SearchTool only
```

---

## Tools

### `Dev.java` — Headless system start + test utilities

Starts all MK7 components without launching the interactive console. Also the primary tool for integration testing.

```bash
# Start
jbang Dev.java                                                     # start headless
jbang Dev.java --clean                                             # clear logs then start

# Wait for readiness
jbang Dev.java --wait-ready                                        # block until kernel is up and agents registered
jbang Dev.java --wait-ready --timeout 30                           # custom wait timeout (seconds)

# Chat — full assistant flow (integration test)
jbang Dev.java --prompt "message"                                  # send chat.prompt, print response
jbang Dev.java --clean --prompt "message"                          # start clean then send prompt
jbang Dev.java --prompt "message" --timeout 300                    # custom timeout

# Direct capability call — bypass assistant (unit test)
jbang Dev.java --invoke tool.datetime.now '{}'
jbang Dev.java --invoke tool.weather.get '{"city":"São Paulo"}'
jbang Dev.java --invoke agent.create '{"spec":"...","type":"tool","name":"x"}'

# Assert capability is registered
jbang Dev.java --assert-capability tool.weather.get

# Combine
jbang Dev.java --prompt "crie uma ferramenta de clima..." --assert-capability tool.weather.get
```

**What it starts (in order):**
1. Kernel
2. Logger, CapabilityRegistry, Blackboard
3. DateTimeTool, FileSystemTool, SearchTool, WikipediaTool
4. Researcher, Writer, Assistant agents

**Stop with:** `jbang Stop.java` or `Ctrl+C`

---

### `Stop.java` — Kill all MK7 processes

Finds every running MK7 component by matching known `.java` filenames in process command lines, kills them forcibly, and removes the kernel socket.

```bash
jbang Stop.java
```

Safe to run even when nothing is running — reports "No MK7 processes found" instead of erroring.

**Processes it stops:**
`Kernel.java`, `Agent.java`, `LoggerPlugin.java`, `CapabilityRegistry.java`, `BlackboardPlugin.java`, `DateTimeTool.java`, `FileSystemTool.java`, `SearchTool.java`, `WikipediaTool.java`, `ConsolePlugin.java`

---

### `Health.java` — System status check

Connects to the kernel socket, reads capability registration logs, checks environment variables, and lists workspace contents.

```bash
jbang Health.java
```

**Sample output:**
```
── MK7 Health ─────────────────────────────────
Environment
  ✓  NVIDIA_API_KEY       set
  ✓  BRAVE_API_KEY        set

✓ Kernel  up  →  /tmp/mk7/kernel.sock

Capabilities  (7 registered)
  Tools:
    ✓  tool.filesystem.op       → tool-filesystem
    ✓  tool.datetime.now        → tool-datetime
    ✓  tool.wikipedia.lookup    → tool-wikipedia
    ✓  tool.search.query        → tool-search
  Agents:
    ✓  agent.research           → researcher
    ✓  agent.write              → writer
    ✓  chat.respond             → assistant

workspace/  (2 file(s))
  generative-ai-2026.md                    3,412 bytes
  machine-learning-in-2026.md              1,569 bytes
────────────────────────────────────────────────
```

If any of the 7 expected capabilities are missing, they appear under a `Missing:` section — useful for diagnosing agents that failed to start.

---

### `TestClient.java` — Send a prompt and print the response

Connects to the kernel as a lightweight plugin, sends a `chat.prompt` event, and waits up to 120 seconds for the `chat.response`.

```bash
jbang TestClient.java "your prompt here"
```

**Examples:**
```bash
jbang TestClient.java "what is the capital of Japan?"
jbang TestClient.java "write an article about generative AI"
jbang TestClient.java "who is the current CEO of OpenAI?"
```

The client exits as soon as the response arrives or after the 120s timeout.

---

## Typical workflows

### Start a dev session

```bash
# 1. Start the system (wipe previous logs)
jbang Dev.java --clean

# 2. Verify everything registered correctly
jbang Health.java

# 3. Send a test prompt
jbang TestClient.java "write a short article about quantum computing"

# 4. Check the output file
ls ../workspace/
```

### Restart after changing a persona or Agent.java

```bash
jbang Stop.java
jbang Dev.java --clean
jbang Health.java   # confirm all 7 capabilities registered before testing
jbang TestClient.java "your test prompt"
```

### Debug a broken pipeline

```bash
# 1. Check what's registered vs what's missing
jbang Health.java

# 2. Check logs for the failing component
cat ../logs/Writer.log
cat ../logs/Researcher.log
cat ../logs/Assistant.log

# 3. Check the kernel routing log
cat ../logs/Kernel.log | tail -20
```

### Verify workspace output after a write task

```bash
jbang TestClient.java "write an article about multi-agent architectures in AI"

# After response arrives:
jbang Health.java             # shows workspace/ file list with sizes
cat ../workspace/<filename>   # read the generated article
```

### Clean up everything between test runs

```bash
jbang Stop.java               # kill all processes
rm -f ../workspace/*.md       # clear workspace (optional)
jbang Dev.java --clean        # restart with empty logs
```

---

## Notes

- **`Dev.java` vs `Start.java`**: Use `Dev.java` for development and testing. Use `Start.java` when you need the interactive chat console (e.g., for a real user session).
- **Timing**: After `Dev.java` starts, wait ~2 seconds before running `Health.java` to allow all agents to complete registration. `Health.java` reads the CapReg log — it reflects the last known state, not a live query.
- **Environment variables**: If `NVIDIA_API_KEY` is missing, agents will start successfully but fail on the first LLM call, returning a generic error. `Health.java` and `Dev.java` both warn about missing keys at startup.
- **Socket location**: The kernel socket is always at `/tmp/mk7/kernel.sock`. `Stop.java` removes it automatically.
