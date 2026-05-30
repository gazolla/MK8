# Contributing to MK8 MicroKernel

First off, thank you for considering contributing to MK8 MicroKernel! Your contributions help push the boundaries of modular, asynchronous, and event-driven Java microkernel architectures.

As an experimental project utilizing **Java 21+ Virtual Threads**, **Unix Domain Sockets (UDS)**, and **JBang**, we welcome all kinds of contributions—from architectural enhancements and logging interceptors to custom plugin tools and agents.

---

## 🚀 How Can I Contribute?

### 1. Reporting Bugs
- Check the open issues in the repository to ensure the bug has not already been reported.
- If it is new, open a new issue. Clearly describe the problem, including:
  - Your environment (OS, JRE/JDK vendor, and version).
  - Terminal stdout logs from running `jbang Start.java`.
  - Content of target files under `logs/` (e.g., `kernel.log`, `summary-agent.log`, or `word-count.log`).
  - Clear steps to reproduce the issue.

### 2. Implementing Plugins (Tools and Agents)
MK8 thrives on its decoupled, dynamic plugin architecture. You can contribute new plugins by placing them in the `tools/` or `system/` directory:
- **Tools:** Specialized mathematical or operational computational units running in `"on-demand"` lifecycle mode (e.g., Sentiment Analysis, File System tools).
- **Agents:** Intelligent orchestrators utilizing prompt instructions and structured skill specs.
- Ensure your plugin contains a compliant `plugin.json` declaring its type, launcher parameters, subscribes/publishes namespaces, and capability schemas (following the **[Plugin Schemas Guide](docs/PLUGIN_SCHEMAS.md)**).

### 3. Core Infrastructure Enhancements
You can contribute directly to the core kernel logic inside the `kernel/` folder:
- **Interceptors:** Enhancing or writing new implementations of `EventInterceptor` (like security token validation or compression filters).
- **Performance Optimizations:** Tuning UDS length-prefixed frame parsers or improving the concurrency throughput of Virtual Thread writers.
- **Routing Rules:** Optimizing bidding auctions (`CapabilityIndex`), route caching, or idle sweepers (`ProcessManager`).

### 4. Code Submission Flow
1. Fork the repository.
2. Create a branch for your feature (`git checkout -b feature/amazing-plugin`).
3. Format your Java code conforming to standard conventions.
4. Verify your changes compile and pass verification tests by running `jbang Start.java`.
5. Commit your changes (`git commit -m 'feat: add image resizing tool plugin'`).
6. Push to the branch (`git push origin feature/amazing-plugin`).
7. Open a Pull Request.

---

## 🛠 Technical Stack & Requirements

To contribute code, ensure your environment has:
- **JDK 21** or higher (leveraging Virtual Threads and Unix Domain Sockets support).
- **JBang** installed and configured in your terminal PATH.
- **Git** configured locally.

---

## ⚖️ Code of Conduct

By participating in this project, you agree to maintain a respectful, inclusive, and collaborative environment for everyone in the community.

---

## 📝 License

By contributing, you agree that your contributions will be licensed under the **Apache License 2.0**.
