# Kiwi Multi-Agent System

Kiwi is a multi-agent system built on the MicroKernel-3 (MK8) event-driven architecture. Components within the system communicate via JSON messages sent over a UNIX domain socket. The project is implemented in Java 21+ and executes using JBang.

## Directory Layout

* `Boot.java`: Orchestrates the system startup process, launching the kernel and configured plugins.
* `Start.java`: Serves as the interactive entry point, starting the system and handing control to the terminal console.
* `agents/`: Contains configuration files and instruction documents for autonomous agents.
* `data/`: Stores database files for sessions.
* `dev/`: Contains utilities for health checks, command-line invocation, and system shutdown.
* `helpers/`: Contains shared helper code, such as the vault client.
* `logs/`: Stores log outputs for each component.
* `system/`: Contains system-level plugins for console, dashboard, telegram, vault, and workflows.
* `tools/`: Contains capability-providing tools for browser, search, weather, filesystem, and other utilities.
* `workspace/`: Represents the sandboxed directory where agents read and write output files.

## Architecture

Kiwi uses a microkernel architecture where all components communicate by publishing and subscribing to events over a UNIX domain socket located at `/tmp/mk8/kernel.sock`.

### Event Loop and Message Format

Messages are JSON frames serialized using the `KernelEvent` class. Each frame contains:
* `type`: The event identifier (e.g., `chat.prompt`, `capability.invoke`).
* `payload`: A JSON-encoded string containing the event data.
* `source`: The identifier of the publishing component.
* `correlationId`: A string to associate responses with requests.
* `sessionId`: A string representing the active chat or user session.
* `traceId`: An identifier for tracking event propagation.

### Core Interceptors

The kernel passes events through five interceptors:
1. `IdempotencyInterceptor`: Tracks message identifiers to prevent duplicate processing of the same request.
2. `CapabilityInterceptor`: Manages capability registration and routes method calls to the registering provider.
3. `PluginInterceptor`: Controls the lifecycles of plugins, spawning on-demand instances when their capabilities are invoked.
4. `BlackboardInterceptor`: Provides a key-value store scoped by session, workflow, or globally.
5. `LogInterceptor`: Records events for diagnostics.

## System Services

System plugins are located in the `system/` directory and execute continuously in persistent mode:

* **Console (`system/console`)**: Provides a terminal user interface. It reads standard input, publishes `chat.prompt` events, and displays `chat.response` messages. It handles slash commands such as `/quit` and `/exit`.
* **Dashboard (`system/dashboard`)**: Starts an HTTP server on port 8080. It serves an HTML interface and uses Server-Sent Events to show status updates, logs, blackboard entries, trace spans, and performance metrics.
* **Plugin Installer (`system/plugin-installer`)**: Provides the `tool.plugin.install` capability. It writes `plugin.json` files, Java files, and persona files to directories, compiles them, and starts their execution.
* **Telegram Bot Gateway (`system/telegram`)**: Connects to the Telegram Bot API using long-polling. It maps messages from Telegram to `chat.prompt` events and returns replies to the chat.
* **Vault (`system/vault`)**: Manages credentials. It stores and retrieves credentials from an AES-256-GCM encrypted file (`~/.kiwi/secrets.enc`). Requesters can invoke `secret.request` to prompt the user for input without exposing credentials to large language models.
* **Workflow Engine (`system/workflow`)**: Parses and executes Directed Acyclic Graphs (DAGs) of tasks. It supports parallel task execution, data binding of outputs to inputs, retries, and task-skipping policies.

## Agents

Agents are configured via `plugin.json` and driven by LLMs. They run using the shared `agent/Agent.java` runtime and are located in the `agents/` directory:

* **Assistant (`agents/assistant`)**: Serves as the default conversational agent. It manages conversations and delegates tasks to other agents.
* **Coder (`agents/coder`)**: Generates and reviews Java plugin code based on specifications.
* **Creator (`agents/creator`)**: Coordinates the generation, review, installation, and execution of new plugins.
* **Planner (`agents/planner`)**: Converts requests into Directed Acyclic Graphs (DAGs) representing sequential and parallel tasks.
* **Researcher (`agents/researcher`)**: Inspects topics by querying search, Wikipedia, and browser tools, returning findings.
* **Writer (`agents/writer`)**: Composes Markdown reports and articles using structural templates.

## Tools

Tools are plugins that run on-demand to provide specific capabilities. They compile and execute when their trigger events are received:

* **Browser (`tools/browser`)**: Performs browser automation to extract page text and data.
* **Crypto Quote (`tools/crypto-quote-tool`)**: Retrieves market pricing for cryptocurrencies.
* **DateTime (`tools/datetime`)**: Returns the ISO-8601 date and time for a given timezone.
* **Email Sender (`tools/email-sender-tool`)**: Sends email notifications via SMTP.
* **Filesystem (`tools/filesystem`)**: Performs file operations (read, write, list, exists, delete) sandboxed inside the `workspace/` directory.
* **RSS News (`tools/rss-news-tool`)**: Parses RSS feeds to extract headlines and content.
* **Search (`tools/search`)**: Queries search engines via API to find web pages.
* **Weather (`tools/weather-tool`)**: Retrieves forecasts from the Open-Meteo API.
* **Wikipedia (`tools/wikipedia`)**: Queries the Wikipedia search API and extracts summaries.

## Operational Guide

### Requirements

* Java 21 or higher
* JBang installation
* OpenRouter API key configured in the environment (`OPENROUTER_API_KEY`)
* Brave API key (`BRAVE_API_KEY`) for web search
* Telegram Token (`TELEGRAM_BOT_TOKEN`) for Telegram bot functionality (optional)

### Booting the System

To start the system with the interactive terminal console:
```bash
jbang Start.java
```

To start the system in headless mode (no console UI, remains active until stopped):
```bash
jbang Boot.java --dev
```

To clear logs before starting:
```bash
jbang Start.java --clean
```

To delete session databases before starting:
```bash
jbang Start.java --reset-memory
```

### Stopping the System

To stop active processes and delete the kernel socket file:
```bash
jbang dev/Stop.java
```

## Diagnostics and Testing

* **Health Status**: To verify environment variables, socket connectivity, live registered capabilities, and workspace contents:
  ```bash
  jbang dev/Health.java
  ```

* **Transient Prompts**: To send a message to the assistant and print the response from the command line:
  ```bash
  jbang dev/Dev.java --prompt "message"
  ```

* **Direct Capability Invocation**: To execute a capability without using an agent:
  ```bash
  jbang dev/Dev.java --invoke <capability_name> '<input_json>'
  ```
  Example:
  ```bash
  jbang dev/Dev.java --invoke tool.weather.get '{"city":"London"}'
  ```

* **Capability Verification**: To test if a capability is registered:
  ```bash
  jbang dev/Dev.java --assert-capability <capability_name>
  ```
