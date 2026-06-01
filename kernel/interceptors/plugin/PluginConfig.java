// Shared file — included via //SOURCES in each plugin (no JBang header).

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * PluginConfig — Typed, lazy-accessor wrapper around a plugin's plugin.json manifest.
 *
 * Wraps the raw Jackson JsonNode tree representing the plugin configuration.
 * By avoiding static POJO bindings, it remains lightweight and compatible with
 * JBang single-file compilation while providing structured accessors for all fields.
 * Accessors are organized into section-specific groups covering root identity,
 * capability declarations, wildcard subscriptions, and launch commands.
 *
 * Provides typed getters with safe defaults for LLM configurations (model, baseUrl,
 * apiKeyEnv), agent execution limits (max rounds, tool calls, tool tags), and
 * progressive user-facing thinking indicators. Safe fallback values ensure that
 * missing json fields never throw Exceptions, making it extremely robust.
 * Loader method automatically handles reading from disk and parsing into records.
 */
record PluginConfig(JsonNode raw) {

    // root
    public String id()          { return raw.path("id").asText(""); }
    public String type()        { return raw.path("type").asText(""); }
    public String version()     { return raw.path("version").asText(""); }
    public String description() { return raw.path("description").asText(""); }

    // lifecycle
    public String  lifecycleMode()      { return raw.path("lifecycle").path("mode").asText("persistent"); }
    public int     idleTimeoutSeconds() { return raw.path("lifecycle").path("idleTimeoutSeconds").asInt(300); }
    public boolean onDemand()           { return "on-demand".equals(lifecycleMode()); }
    public boolean persistent()         { return "persistent".equals(lifecycleMode()); }

    // llm
    public String llmModel()       { return raw.path("llm").path("model").asText("meta/llama-3.3-70b-instruct"); }
    public String llmBaseUrl()     { return raw.path("llm").path("baseUrl").asText("https://integrate.api.nvidia.com/v1"); }
    public String llmApiKeyEnv()   { return raw.path("llm").path("apiKeyEnv").asText("NVIDIA_API_KEY"); }
    public int    llmMaxTokens()   { return raw.path("llm").path("maxTokens").asInt(4096); }
    public double llmTemperature() { return raw.path("llm").path("temperature").asDouble(0.2); }

    // agent
    public int     agentMaxRounds()                 { return raw.path("agent").path("maxRounds").asInt(5); }
    public int     agentMaxDelegations()            { return raw.path("agent").path("maxDelegations").asInt(3); }
    public int     agentMaxToolCalls()              { return raw.path("agent").path("maxToolCalls").asInt(20); }
    public int     agentMaxConcurrent()             { return raw.path("agent").path("maxConcurrentMissions").asInt(1); }
    public String  agentContextLoading()            { return raw.path("agent").path("contextLoading").asText("lazy"); }
    public int     agentNegotiatingTimeout()        { return raw.path("agent").path("negotiatingTimeoutSeconds").asInt(120); }
    public boolean agentSeeInternalTools()          { return raw.path("agent").path("seeInternalTools").asBoolean(false); }
    public boolean agentRequireToolOnRound1()       { return raw.path("agent").path("requireToolOnRound1").asBoolean(true); }
    public boolean agentRequireDelegationOnRound1() { return raw.path("agent").path("requireDelegationOnRound1").asBoolean(true); }
    public List<String> agentToolTags()             { return stringList("agent", "toolTags"); }

    // thinking
    public long         thinkingCycleDelayMs()         { return raw.path("thinking").path("cycleDelayMs").asLong(12_000L); }
    public long         thinkingBackgroundThresholdMs(){ return raw.path("thinking").path("backgroundThresholdMs").asLong(120_000L); }
    public String       thinkingBackground()           { return raw.path("thinking").path("background").asText("⏳ Still working on it. I'll notify you here when ready! 🔔"); }
    public List<String> thinkingSteps()                { return stringList("thinking", "steps"); }

    // launch
    public String launchName() { return raw.path("launch").path("name").asText(""); }
    public String[] launchCommand() {
        JsonNode cmd = raw.path("launch").path("command");
        if (!cmd.isArray() || cmd.isEmpty()) return null;
        String[] arr = new String[cmd.size()];
        for (int i = 0; i < cmd.size(); i++) arr[i] = cmd.get(i).asText();
        return arr;
    }

    // capabilities / subscriptions
    public List<JsonNode> capabilities()              { return nodeList("capabilities"); }
    public List<String>   subscribesOrEmpty()         { return stringList("subscribes"); }
    public List<String>   wildcardSubscribesOrEmpty() { return stringList("wildcardSubscribes"); }

    // ── factory ───────────────────────────────────────────────────────────────

    public static PluginConfig load(String jsonPath) throws Exception {
        System.out.println("[EVENT] Loading config: " + jsonPath);
        return new PluginConfig(KernelEvent.MAPPER.readTree(Files.readString(Path.of(jsonPath))));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private List<JsonNode> nodeList(String field) {
        JsonNode arr = raw.path(field);
        if (!arr.isArray()) return List.of();
        List<JsonNode> out = new ArrayList<>();
        arr.forEach(out::add);
        return List.copyOf(out);
    }

    private List<String> stringList(String field) {
        JsonNode arr = raw.path(field);
        if (!arr.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.asText()));
        return List.copyOf(out);
    }

    private List<String> stringList(String section, String field) {
        JsonNode arr = raw.path(section).path(field);
        if (!arr.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.asText()));
        return List.copyOf(out);
    }
}
