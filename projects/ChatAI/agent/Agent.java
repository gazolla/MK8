///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//DEPS dev.langchain4j:langchain4j-open-ai:0.36.2
//SOURCES ../../../kernel/KernelEvent.java
//SOURCES ../../../kernel/Log.java
//SOURCES ../../../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../../../kernel/interceptors/plugin/PluginBase.java

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.Response;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Agent — Persistent LLM agent for the MK8 ChatAI project.
 *
 * Co-located with plugin.json in the agent/ directory. All LLM settings
 * (model, baseUrl, apiKeyEnv, maxTokens, temperature) are read from plugin.json
 * via PluginConfig — zero hardcoded configuration.
 *
 * Flow:
 *   chat.prompt       → send chat.thinking → call LLM → send chat.response
 *   message.assistant → same as chat.prompt (direct agent-to-agent message)
 *   capability.result → reserved for future tool/delegation handling
 *
 * Contracts (mirrors plugin.json):
 *   Publishes:  chat.response, chat.typing, chat.thinking, capability.invoke
 *   Subscribes: chat.prompt, message.assistant, capability.result, system.error, plugin.installed
 */
public class Agent {

    // ── Eventos que publica ───────────────────────────────────────────────────
    static final String EVT_CHAT_RESPONSE = "chat.response";
    static final String EVT_CHAT_TYPING   = "chat.typing";
    static final String EVT_CHAT_THINKING = "chat.thinking";
    static final String EVT_CAP_INVOKE    = "capability.invoke";

    // ── Eventos que subscreve ─────────────────────────────────────────────────
    static final String EVT_CHAT_PROMPT      = "chat.prompt";
    static final String EVT_CAP_RESULT       = "capability.result";
    static final String EVT_SYSTEM_ERROR     = "system.error";
    static final String EVT_PLUGIN_INSTALLED = "plugin.installed";
    // message.<id> handled via startsWith — prefix is dynamic per agent id

    // ── Parâmetros ────────────────────────────────────────────────────────────
    static final int    MAX_HISTORY   = 40;
    static final String SYSTEM_PROMPT =
            "You are a helpful, concise, and friendly AI assistant. " +
            "Answer clearly and directly. Use markdown when helpful.";

    final PluginConfig    config;
    final OpenAiChatModel model;
    final List<ChatMessage> history = new CopyOnWriteArrayList<>();

    public static void main(String[] args) throws Exception {
        new Agent().start();
    }

    Agent() throws Exception {
        config = PluginConfig.load("plugin.json");

        String apiKey = System.getenv(config.llmApiKeyEnv());
        if (apiKey == null || apiKey.isBlank()) {
            Log.rawError("[" + id() + "] WARNING: env var '"
                    + config.llmApiKeyEnv() + "' is not set — LLM calls will fail.");
            apiKey = "missing-api-key";
        }

        model = OpenAiChatModel.builder()
                .baseUrl(config.llmBaseUrl())
                .apiKey(apiKey)
                .modelName(config.llmModel())
                .maxTokens(config.llmMaxTokens())
                .temperature(config.llmTemperature())
                .build();

        history.add(SystemMessage.from(SYSTEM_PROMPT));

        Log.rawInfo("[" + id() + "] Initialized — model=" + config.llmModel()
                + " url=" + config.llmBaseUrl());
    }

    void start() throws Exception {
        Log.rawInfo("[" + id() + "] Starting...");
        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, this::handle);
    }

    // ── Event dispatch ────────────────────────────────────────────────────────

    void handle(String json, OutputStream out) throws Exception {
        Log.configure(config.id(), out);
        KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);

        if (event.type().equals(EVT_CHAT_PROMPT)
                || event.type().equals("message." + config.id())) {
            handlePrompt(event, out);
        } else if (event.type().equals(EVT_CAP_RESULT)) {
            handleCapabilityResult(event);
        } else if (event.type().equals(EVT_SYSTEM_ERROR)) {
            Log.rawError("[" + id() + "] System error: " + event.payload());
        }
    }

    // ── Prompt → LLM → response ───────────────────────────────────────────────

    void handlePrompt(KernelEvent event, OutputStream out) throws Exception {
        JsonNode p    = KernelEvent.MAPPER.readTree(event.payload());
        String   text = p.path("text").asText(p.path("message").asText("")).trim();
        if (text.isBlank()) return;

        Log.rawInfo("[" + id() + "] Prompt: "
                + text.substring(0, Math.min(text.length(), 80)));

        PluginBase.publish(KernelEvent.of(EVT_CHAT_THINKING,
                KernelEvent.MAPPER.writeValueAsString(
                        Map.of("status", config.thinkingBackground())),
                config.id()), out);

        // LLM call in virtual thread — never blocks the PluginBase event loop
        Thread.ofVirtual().start(() -> {
            try {
                String response = callLlm(text);
                PluginBase.publish(KernelEvent.of(EVT_CHAT_RESPONSE,
                        KernelEvent.MAPPER.writeValueAsString(
                                Map.of("response", response)),
                        config.id()), out);
                Log.rawInfo("[" + id() + "] Response sent ("
                        + response.length() + " chars).");
            } catch (Exception e) {
                Log.rawError("[" + id() + "] LLM error: " + e.getMessage());
                try {
                    PluginBase.publish(KernelEvent.of(EVT_CHAT_RESPONSE,
                            KernelEvent.MAPPER.writeValueAsString(Map.of(
                                    "response", "⚠️ Sorry, I ran into an error: " + e.getMessage())),
                            config.id()), out);
                } catch (Exception ignored) {}
            }
        });
    }

    void handleCapabilityResult(KernelEvent event) {
        // Future: tool use / multi-step delegation
        Log.rawInfo("[" + id() + "] Capability result corrId=" + event.correlationId());
    }

    // ── LLM call (synchronized — one at a time for shared history) ───────────

    synchronized String callLlm(String userText) {
        history.add(UserMessage.from(userText));

        Response<AiMessage> response = model.generate(new ArrayList<>(history));
        AiMessage aiMessage = response.content();
        history.add(aiMessage);

        // Trim oldest non-system messages to stay within MAX_HISTORY
        while (history.size() > MAX_HISTORY && history.size() > 1)
            history.remove(1);

        return aiMessage.text();
    }

    String id() { return config.id().toUpperCase(); }
}
