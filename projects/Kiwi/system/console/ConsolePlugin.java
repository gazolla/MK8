///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../../../kernel/KernelEvent.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginBase.java

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ConsolePlugin — interactive terminal UI for the Kiwi distro (ported from MK7 system/console).
 *
 * Reads stdin and publishes chat.prompt for free-form messages and chat.command.request for
 * slash commands (/help, /quit, …). Displays chat.response, chat.thinking, chat.typing and
 * chat.command.result coming back from the assistant.
 *
 * MK8 idiom: PluginBase.run drives the connection/event loop; the stdin reader starts on the
 * first plugin.ready in its own virtual thread. chat.prompt carries {"text": …} (the AgentCore
 * accepts {text}/{message}/plain). Timestamps are suppressed so the terminal stays clean.
 *
 * Contracts (mirror plugin.json):
 *   Publishes:  chat.prompt, chat.command.request
 *   Subscribes: chat.response, chat.typing, chat.thinking, chat.command.result, plugin.ready
 */
public class ConsolePlugin {

    static final String EVT_CHAT_PROMPT          = "chat.prompt";
    static final String EVT_CHAT_COMMAND_REQUEST = "chat.command.request";

    static final String EVT_PLUGIN_READY        = "plugin.ready";
    static final String EVT_CHAT_RESPONSE       = "chat.response";
    static final String EVT_CHAT_TYPING         = "chat.typing";
    static final String EVT_CHAT_THINKING       = "chat.thinking";
    static final String EVT_CHAT_COMMAND_RESULT = "chat.command.result";
    static final String EVT_SECRET_ELICIT       = "secret.elicit";
    static final String EVT_CAPABILITY_INVOKE   = "capability.invoke";

    static final String SOURCE_ID = "console";

    final AtomicBoolean started = new AtomicBoolean(false);
    // Out-of-band secret capture: when set, the NEXT user line is the secret value for this key.
    volatile String awaitingSecretKey = null;

    public static void main(String[] args) throws Exception {
        System.setProperty("mk8.logging.redirected", "true"); // stdout is the user-facing terminal
        new ConsolePlugin().start();
    }

    void start() throws Exception {
        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, this::handle);
    }

    void handle(String json, OutputStream out) throws Exception {
        KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);
        switch (event.type()) {
            case EVT_PLUGIN_READY -> {
                if (started.compareAndSet(false, true))
                    Thread.ofVirtual().start(() -> readInput(out));
            }
            case EVT_CHAT_THINKING -> {
                JsonNode p = KernelEvent.MAPPER.readTree(event.payload());
                printStatus("\n🤔 " + p.path("status").asText("Thinking..."));
            }
            case EVT_CHAT_TYPING -> {
                JsonNode p = KernelEvent.MAPPER.readTree(event.payload());
                printRaw(p.path("text").asText(""));
            }
            case EVT_CHAT_RESPONSE -> {
                JsonNode p = KernelEvent.MAPPER.readTree(event.payload());
                printResponse(p.path("response").asText(""));
            }
            case EVT_CHAT_COMMAND_RESULT -> {
                JsonNode p = KernelEvent.MAPPER.readTree(event.payload());
                printStatus("\n" + p.path("result").asText("(no result)"));
                prompt();
            }
            case EVT_SECRET_ELICIT -> {
                JsonNode p = KernelEvent.MAPPER.readTree(event.payload());
                awaitingSecretKey = p.path("key").asText(null);
                printStatus("\n🔐 " + p.path("prompt").asText("Provide the secret value:")
                        + "\n   (digite a seguir — não será exibido nem enviado ao modelo)");
                prompt();
            }
        }
    }

    void readInput(OutputStream out) {
        try (var reader = new BufferedReader(new InputStreamReader(System.in))) {
            printBanner();
            prompt();

            String raw;
            while ((raw = reader.readLine()) != null) {
                // Out-of-band secret capture: the next line goes straight to the vault, never the LLM.
                String pendingKey = awaitingSecretKey;
                if (pendingKey != null) {
                    if (raw.isBlank()) { prompt(); continue; }
                    awaitingSecretKey = null;
                    String payload = KernelEvent.MAPPER.writeValueAsString(Map.of(
                            "name", "secret.set", "input", Map.of("key", pendingKey, "value", raw)));
                    PluginBase.publish(KernelEvent.of(EVT_CAPABILITY_INVOKE, payload, SOURCE_ID), out);
                    printStatus("✅ Segredo salvo com segurança (não exibido).");
                    prompt();
                    continue;
                }

                String line = raw.trim();
                if (line.isEmpty()) { prompt(); continue; }

                if (line.equalsIgnoreCase("/quit") || line.equalsIgnoreCase("/exit")) {
                    printStatus("Goodbye!");
                    System.exit(0);
                }

                if (line.startsWith("/")) {
                    String payload = KernelEvent.MAPPER.writeValueAsString(
                            Map.of("command", line.substring(1).trim()));
                    PluginBase.publish(KernelEvent.of(EVT_CHAT_COMMAND_REQUEST, payload, SOURCE_ID), out);
                } else {
                    String payload = KernelEvent.MAPPER.writeValueAsString(Map.of("text", line));
                    PluginBase.publish(KernelEvent.of(EVT_CHAT_PROMPT, payload, SOURCE_ID), out);
                }
            }
        } catch (Exception e) {
            System.err.println("[CONSOLE] Input error: " + e.getMessage());
        }
    }

    // ── Output helpers (synchronized to avoid interleaving with agent output) ──

    static synchronized void printBanner() {
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║        Kiwi  —  type to chat             ║");
        System.out.println("║        /quit to exit                     ║");
        System.out.println("╚══════════════════════════════════════════╝");
        System.out.println();
    }

    static synchronized void printResponse(String response) {
        System.out.println("\n🤖  " + response.replace("\n", "\n    ") + "\n");
        prompt();
    }

    static synchronized void printStatus(String msg) { System.out.println(msg); }

    static synchronized void printRaw(String text) { System.out.print(text); System.out.flush(); }

    static synchronized void prompt() { System.out.print("> "); System.out.flush(); }
}
