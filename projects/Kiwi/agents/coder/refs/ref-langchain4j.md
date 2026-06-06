# LangChain4J — Java AI Framework (JBang)

LangChain4J provides high-level abstractions for LLM applications: chat memory,
AI services, RAG, tools/functions, and more.

**Note:** LangChain4J releases frequently. Verify the latest version at
https://github.com/langchain4j/langchain4j/releases before using.
As of 2025 it may be at `1.0.0-beta` or later — check Maven Central.

---

## Chat with memory — OpenAI

```java
///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//DEPS dev.langchain4j:langchain4j:0.36.2
//DEPS dev.langchain4j:langchain4j-open-ai:0.36.2
//SOURCES ../../../../kernel/KernelEvent.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginBase.java

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.chain.ConversationalChain;
import java.util.Map;

public class ChatAgentTool {

    static final OpenAiChatModel MODEL = OpenAiChatModel.builder()
            .apiKey(System.getenv("OPENAI_API_KEY"))
            .modelName("gpt-4o-mini")
            .temperature(0.7)
            .build();

    static final ConversationalChain CHAIN = ConversationalChain.builder()
            .chatLanguageModel(MODEL)
            .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
            .build();

    public static void main(String[] args) throws Exception {
        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, ChatAgentTool::handle);
    }

    static void handle(String json, OutputStream out) throws Exception {
        KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);
        if (!"capability.tool.chat.send".equals(event.type())) return;

        try {
            String userMessage = KernelEvent.MAPPER.readTree(event.payload())
                    .path("input").path("message").asText();

            String response = CHAIN.execute(userMessage);

            PluginBase.publish(
                    KernelEvent.withCorrelation("capability.result",
                            KernelEvent.MAPPER.writeValueAsString(Map.of("result", response)),
                            "chat-tool", event.correlationId(), event.sessionId()),
                    out);
        } catch (Exception e) {
            PluginBase.publish(
                    KernelEvent.withCorrelation("capability.error",
                            KernelEvent.MAPPER.writeValueAsString(Map.of("reason", e.getMessage())),
                            "chat-tool", event.correlationId(), event.sessionId()),
                    out);
        }
    }
}
```

---

## NVIDIA NIM with LangChain4J (OpenAI-compatible endpoint)

```java
//DEPS dev.langchain4j:langchain4j:0.36.2
//DEPS dev.langchain4j:langchain4j-open-ai:0.36.2

import dev.langchain4j.model.openai.OpenAiChatModel;

OpenAiChatModel model = OpenAiChatModel.builder()
        .baseUrl("https://integrate.api.nvidia.com/v1")
        .apiKey(System.getenv("NVIDIA_API_KEY"))
        .modelName("meta/llama-3.3-70b-instruct")
        .maxTokens(1024)
        .build();

String response = model.generate("Explain virtual threads in Java 21");
```

---

## AI Service — interface-driven LLM calls

```java
import dev.langchain4j.service.*;

// Define the interface
interface TranslatorService {
    @SystemMessage("You are a professional translator. Translate to {{language}}.")
    @UserMessage("Translate this text: {{text}}")
    String translate(@V("language") String language, @V("text") String text);
}

// Build the service
TranslatorService translator = AiServices.builder(TranslatorService.class)
        .chatLanguageModel(MODEL)
        .build();

String result = translator.translate("Portuguese", "Hello, world!");
```

---

## Streaming response

```java
//DEPS dev.langchain4j:langchain4j:0.36.2
//DEPS dev.langchain4j:langchain4j-open-ai:0.36.2

import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.output.Response;

OpenAiStreamingChatModel streamingModel = OpenAiStreamingChatModel.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("gpt-4o-mini")
        .build();

streamingModel.generate("Write a haiku about Java", new StreamingResponseHandler<>() {
    @Override
    public void onNext(String token) {
        System.out.print(token); // token by token
    }

    @Override
    public void onComplete(Response<dev.langchain4j.model.chat.ChatLanguageModel> response) {
        System.out.println("\n[done]");
    }

    @Override
    public void onError(Throwable error) {
        System.err.println("Error: " + error.getMessage());
    }
});
```

---

## Tool / function calling via LangChain4J

```java
import dev.langchain4j.agent.tool.*;

// Annotate methods with @Tool — LangChain4J registers them automatically
static class WeatherTools {
    @Tool("Get the current weather for a city")
    String getWeather(@P("city name") String city) {
        return "Sunny, 22°C in " + city; // replace with real API call
    }
}

// Wire tools into the AI service
interface AssistantWithTools {
    String chat(String userMessage);
}

AssistantWithTools assistant = AiServices.builder(AssistantWithTools.class)
        .chatLanguageModel(MODEL)
        .tools(new WeatherTools())
        .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
        .build();

String reply = assistant.chat("What's the weather in Tokyo?");
// LangChain4J automatically calls getWeather("Tokyo") and includes the result
```

---

## RAG — retrieval-augmented generation

```java
//DEPS dev.langchain4j:langchain4j:0.36.2
//DEPS dev.langchain4j:langchain4j-open-ai:0.36.2

import dev.langchain4j.data.document.*;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;

// Embed documents
OpenAiEmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("text-embedding-3-small")
        .build();

InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
// store.add(embeddingModel.embed(TextSegment.from("document text")).content(), TextSegment.from("..."));

// Build RAG chain
var retriever = EmbeddingStoreContentRetriever.builder()
        .embeddingStore(store)
        .embeddingModel(embeddingModel)
        .maxResults(3)
        .build();

interface RagAssistant {
    String answer(String question);
}

RagAssistant ragAssistant = AiServices.builder(RagAssistant.class)
        .chatLanguageModel(MODEL)
        .contentRetriever(retriever)
        .build();
```

---

## Version compatibility note

LangChain4J API changes across minor versions. If `ConversationalChain` or other
classes are not found, check the migration guide at:
https://github.com/langchain4j/langchain4j/blob/main/CHANGELOG.md
