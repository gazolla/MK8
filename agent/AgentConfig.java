// Shared file — included via //SOURCES in Agent.java (no JBang header).

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * AgentConfig — Adapter that re-exposes the flat MK8 {@link PluginConfig} accessors
 * in the nested shape the ported MK7 agent stack expects:
 *
 *   config.agent().maxRoundsOrDefault()    config.llm().modelOrDefault()
 *   config.thinking()                      config.capabilitiesOrEmpty()
 *
 * Lives in the agent layer only — the kernel's PluginConfig is untouched and shared
 * unchanged by tools and system plugins (Open/Closed: extension via adapter, zero
 * modification of the shared class).
 *
 * It does NO parsing of its own: every accessor delegates to PluginConfig, so JSON
 * handling and safe defaults stay in exactly one place (DRY).
 */
public final class AgentConfig {

    private final PluginConfig pc;

    private AgentConfig(PluginConfig pc) { this.pc = pc; }

    public static AgentConfig of(PluginConfig pc)        { return new AgentConfig(pc); }
    public static AgentConfig load(String jsonPath) throws Exception {
        return new AgentConfig(PluginConfig.load(jsonPath));
    }

    // ── Root (delegated) ──────────────────────────────────────────────────────
    public String       id()          { return pc.id(); }
    public String       type()        { return pc.type(); }
    public String       version()     { return pc.version(); }
    public String       description() { return pc.description(); }
    public JsonNode     raw()         { return pc.raw(); }
    public PluginConfig plugin()      { return pc; }   // underlying config for PluginBoot

    // ── Nested views (null when the block is absent, mirroring MK7 semantics) ──
    public AgentView agent()    { return pc.raw().has("agent") ? new AgentView(pc) : null; }
    public LlmView   llm()      { return pc.raw().has("llm")   ? new LlmView(pc)   : null; }
    public JsonNode  thinking() { JsonNode t = pc.raw().path("thinking"); return t.isMissingNode() ? null : t; }

    public List<CapabilityDecl> capabilitiesOrEmpty() {
        List<CapabilityDecl> out = new ArrayList<>();
        for (JsonNode c : pc.capabilities()) out.add(new CapabilityDecl(c));
        return out;
    }

    // ── agent.* view ──────────────────────────────────────────────────────────
    public static final class AgentView {
        private final PluginConfig pc;
        AgentView(PluginConfig pc) { this.pc = pc; }

        public int          maxRoundsOrDefault()       { return pc.agentMaxRounds(); }
        public int          maxToolCallsOrDefault()    { return pc.agentMaxToolCalls(); }
        public int          maxDelegationsOrDefault()  { return pc.agentMaxDelegations(); }
        public int          maxConcurrentOrDefault()   { return pc.agentMaxConcurrent(); }
        public String       contextLoadingOrDefault()  { return pc.agentContextLoading(); }
        public int          negotiatingTimeoutOrDefault()        { return pc.agentNegotiatingTimeout(); }
        public boolean      seeInternalToolsOrDefault()          { return pc.agentSeeInternalTools(); }
        public boolean      requireToolOnRound1OrDefault()       { return pc.agentRequireToolOnRound1(); }
        public boolean      requireDelegationOnRound1OrDefault() { return pc.agentRequireDelegationOnRound1(); }
        public List<String> toolTagsOrDefault()        { return pc.agentToolTags(); }
        /** Relative skills directory; defaults to "." (the agent's own folder). */
        public String       skillsDir()                { return pc.raw().path("agent").path("skillsDir").asText("."); }
    }

    // ── llm.* view ──────────────────────────────────────────────────────────────
    public static final class LlmView {
        private final PluginConfig pc;
        LlmView(PluginConfig pc) { this.pc = pc; }

        public String modelOrDefault()      { return pc.llmModel(); }
        public String baseUrlOrDefault()    { return pc.llmBaseUrl(); }
        public String apiKeyEnvOrDefault()  { return pc.llmApiKeyEnv(); }
        public int    maxTokensOrDefault()  { return pc.llmMaxTokens(); }
        public double temperatureOrDefault(){ return pc.llmTemperature(); }
    }

    // ── capabilities[] entry (raw-node backed) ────────────────────────────────
    public static final class CapabilityDecl {
        private final JsonNode c;
        CapabilityDecl(JsonNode c) { this.c = c; }

        public String   name()        { return c.path("name").asText(); }
        public String   description() { return c.path("description").asText(null); }
        public String   version()     { return c.path("version").asText(null); }
        public String   triggerEvent(){
            String t = c.path("triggerEvent").asText(null);
            return (t != null && t.isBlank()) ? null : t;
        }
        public boolean  exclusiveOrDefault() { return c.path("exclusive").asBoolean(false); }
        public double   bidWeightOrDefault() { return c.path("bidWeight").asDouble(1.0); }
        public boolean  internalOrDefault()  { return c.path("internal").asBoolean(false); }
        /** Tags as a JSON array node (null when absent) — ready to embed in a payload. */
        public JsonNode tags()        { JsonNode t = c.path("tags"); return t.isArray() ? t : null; }
        public JsonNode inputSchema() { return c.path("inputSchema"); }
    }
}
