///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../../kernel/KernelEvent.java
//SOURCES ../../../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../../../kernel/interceptors/plugin/PluginBase.java

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ConsolePlugin — Interactive terminal UI for the MK8 ChatAI project.
 *
 * Reads user input from stdin and publishes chat.prompt events to the bus.
 * Slash commands (/help, /quit, etc.) are sent as chat.command.request.
 * Displays chat.response, chat.thinking, and chat.typing events from the agent.
 *
 * Timestamps are intentionally suppressed (mk8.logging.redirected=true) so the
 * terminal UI remains clean for the end user.
 *
 * Contracts (mirrors plugin.json):
 *   Publishes:  chat.prompt, chat.command.request
 *   Subscribes: chat.response, chat.typing, chat.thinking, chat.command.result, plugin.ready
 */
public class ConsolePlugin {

    // ── Eventos que publica ───────────────────────────────────────────────────
    static final String EVT_CHAT_PROMPT          = "chat.prompt";
    static final String EVT_CHAT_COMMAND_REQUEST = "chat.command.request";

    // ── Eventos que subscreve ─────────────────────────────────────────────────
    static final String EVT_PLUGIN_READY        = "plugin.ready";
    static final String EVT_CHAT_RESPONSE       = "chat.response";
    static final String EVT_CHAT_TYPING         = "chat.typing";
    static final String EVT_CHAT_THINKING       = "chat.thinking";
    static final String EVT_CHAT_COMMAND_RESULT = "chat.command.result";

    static final String SOURCE_ID = "console";

    final AtomicBoolean started = new AtomicBoolean(false);

    public static void main(String[] args) throws Exception {
        // Suppress timestamp logging — stdout is the user-facing terminal
        System.setProperty("mk8.logging.redirected", "true");
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
        }
    }

    void readInput(OutputStream out) {
        try (var reader = new BufferedReader(new InputStreamReader(System.in))) {
            printBanner();
            prompt();

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) { prompt(); continue; }

                if (line.equalsIgnoreCase("/quit") || line.equalsIgnoreCase("/exit")) {
                    printStatus("Goodbye!");
                    System.exit(0);
                }

                if (line.startsWith("/")) {
                    String payload = KernelEvent.MAPPER.writeValueAsString(
                            Map.of("command", line.substring(1).trim()));
                    PluginBase.publish(
                            KernelEvent.of(EVT_CHAT_COMMAND_REQUEST, payload, SOURCE_ID), out);
                } else {
                    String payload = KernelEvent.MAPPER.writeValueAsString(
                            Map.of("text", line));
                    PluginBase.publish(
                            KernelEvent.of(EVT_CHAT_PROMPT, payload, SOURCE_ID), out);
                }
            }
        } catch (Exception e) {
            System.err.println("[CONSOLE] Input error: " + e.getMessage());
        }
    }

    // ── Output helpers (synchronized to prevent interleaving with agent output) ─

    static synchronized void printBanner() {
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║       MK8 Chat AI  —  type to chat       ║");
        System.out.println("║       /quit to exit                      ║");
        System.out.println("╚══════════════════════════════════════════╝");
        System.out.println();
    }

    static synchronized void printResponse(String response) {
        System.out.println("\n🤖  " + response.replace("\n", "\n    ") + "\n");
        prompt();
    }

    static synchronized void printStatus(String msg) {
        System.out.println(msg);
    }

    static synchronized void printRaw(String text) {
        System.out.print(text);
        System.out.flush();
    }

    static synchronized void prompt() {
        System.out.print("> ");
        System.out.flush();
    }
}
