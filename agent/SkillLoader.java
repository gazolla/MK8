// Shared file — included via //SOURCES in Agent.java

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.*;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Stream;

/**
 * SkillLoader — loads .md files from skillsDir and builds the system prompt.
 * Also discovers tools dynamically by scanning plugin.json files.
 */
public class SkillLoader {

    public record DiscoveredTools(List<ToolSpecification> specs, Map<String, String> toolToCapability) {}

    static final YearMonth TRAINING_CUTOFF        = YearMonth.of(2024, 4);
    static final int       STALE_MONTHS_THRESHOLD = 6;            // emit the "must use a tool" warning beyond this
    static final int       DEFAULT_MAX_TOOL_CALLS = 10;           // fallback when config.agent() is absent
    static final String    PERSONA_FILE           = "persona.md"; // loaded first, and the only file in lazy round 1

    static String loadSystemPrompt(String skillsDir, boolean lazyRound1,
                                   AgentConfig config) throws Exception {
        Path dir = Path.of(skillsDir);
        if (!Files.isDirectory(dir)) {
            return "You are a helpful assistant.";
        }

        List<Path> mdFiles = Files.list(dir)
                .filter(p -> p.toString().endsWith(".md"))
                .sorted(Comparator.<Path, Integer>comparing(
                        p -> p.getFileName().toString().equals(PERSONA_FILE) ? 0 : 1)
                        .thenComparing(Comparator.naturalOrder()))
                .toList();

        if (lazyRound1) {
            mdFiles = mdFiles.stream()
                    .filter(p -> p.getFileName().toString().equals(PERSONA_FILE))
                    .toList();
        }

        StringBuilder sb = new StringBuilder();
        for (Path file : mdFiles) {
            sb.append(Files.readString(file)).append("\n\n");
        }

        sb.append(buildIdentitySection(config, dir.toAbsolutePath().normalize()));

        // Capabilities are passed natively to the model via discoverTools(), so we
        // do NOT append capabilities as raw text in the system prompt.
        return sb.toString().trim();
    }

    // ── Identity section ─────────────────────────────────────────────────────

    static String buildIdentitySection(AgentConfig config, Path skillsDirPath) {
        if (config == null) return "";

        StringBuilder s = new StringBuilder();
        s.append("## Your runtime identity\n\n");
        s.append("- **Agent ID**: `").append(config.id()).append("`\n");
        s.append("- **Type**: ").append(config.type() != null ? config.type() : "agent").append("\n");
        if (config.description() != null)
            s.append("- **Description**: ").append(config.description()).append("\n");
        s.append("- **Runtime**: Java 21 + JBang, MK8 microkernel (LangChain4j low-level function calling)\n");
        s.append("- **Communication**: Unix Domain Socket `/tmp/mk8/kernel.sock`, length-prefixed JSON frames\n");

        if (skillsDirPath != null) {
            Path absSkills = skillsDirPath.toAbsolutePath().normalize();
            s.append("- **Skills directory**: `").append(absSkills).append("`\n");
            Path root = findProjectRoot(absSkills);
            if (root != null) {
                s.append("- **Project root**: `").append(root).append("`\n");
                s.append("- **Workspace**: `").append(root.resolve("workspace")).append("`\n");
                s.append("- **Restart command**: `jbang Start.java` (run from `").append(root).append("`)\n");
                s.append("- **Logs**: `").append(root.resolve("logs")).append("/`\n");
            }
        }

        var caps = config.capabilitiesOrEmpty();
        if (!caps.isEmpty()) {
            s.append("- **Capabilities you expose**:\n");
            for (var cap : caps) {
                s.append("  - `").append(cap.name()).append("`");
                if (cap.description() != null) s.append(" — ").append(cap.description());
                s.append("\n");
            }
        }

        if (config.agent() != null) {
            var a = config.agent();
            s.append("- **Max rounds**: ").append(a.maxRoundsOrDefault()).append("\n");
            s.append("- **Max tool calls**: ").append(a.maxToolCallsOrDefault()).append("\n");
            s.append("- **Max delegations**: ").append(a.maxDelegationsOrDefault()).append("\n");
        }

        if (config.llm() != null)
            s.append("- **LLM model**: `").append(config.llm().modelOrDefault()).append("`\n");

        LocalDate today = LocalDate.now();
        String tz = TimeZone.getDefault().getID();
        YearMonth nowYM = YearMonth.from(today);
        long monthsStale = ChronoUnit.MONTHS.between(TRAINING_CUTOFF, nowYM);

        s.append("\n## Current date, time and location\n\n");
        s.append("- **Timezone**: ").append(tz).append("\n");
        s.append("- **Training cutoff**: approx. ").append(TRAINING_CUTOFF)
         .append(" (").append(monthsStale).append(" months ago)\n");

        return s.toString();
    }

    static String getDynamicInstruction(AgentConfig config, int round, int toolCallsSoFar) {
        StringBuilder s = new StringBuilder();
        s.append("## Dynamic Context & Warnings\n");
        s.append("- **Current Time**: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append("\n");

        if (config == null) return s.toString();

        int maxTC = config.agent() != null ? config.agent().maxToolCallsOrDefault() : DEFAULT_MAX_TOOL_CALLS;
        LocalDate today = LocalDate.now();
        YearMonth nowYM = YearMonth.from(today);
        long monthsStale = ChronoUnit.MONTHS.between(TRAINING_CUTOFF, nowYM);

        if (monthsStale > STALE_MONTHS_THRESHOLD) {
            if (round == 1 && toolCallsSoFar == 0) {
                s.append("\n**⚠ MANDATORY**: Training data is ").append(monthsStale)
                 .append(" months old. For ANY query about current events, world leaders, recent news,")
                 .append(" or anything that could have changed since ").append(TRAINING_CUTOFF)
                 .append(", you MUST invoke the appropriate tool/capability BEFORE answering.")
                 .append(" Do NOT answer from memory. Failing to call a tool for changing facts is an error.\n");
            } else if (toolCallsSoFar > 0 && toolCallsSoFar >= maxTC - 2) {
                s.append("\n**→ Tool budget nearly exhausted** (").append(toolCallsSoFar)
                 .append("/").append(maxTC).append(" calls used).")
                 .append(" Do NOT call any more tools. Synthesize the information you have")
                 .append(" and produce your final answer now.\n");
            }
        }

        return s.toString().trim();
    }

    // ── Dynamic Tool Discovery ────────────────────────────────────────────────

    public static DiscoveredTools discoverTools(AgentConfig selfConfig, OutputStream out) {
        List<ToolSpecification> list = new ArrayList<>();
        Map<String, String> localMap = new HashMap<>();
        Path root = findProjectRoot(Path.of(System.getProperty("user.dir")));
        if (root == null) return new DiscoveredTools(list, localMap);

        try {
            List<Path> pluginFiles = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.getFileName().toString().equals("plugin.json"))
                    .sorted()
                    .forEach(pluginFiles::add);
            }

            boolean canSeeInternal = selfConfig != null
                    && selfConfig.agent() != null
                    && selfConfig.agent().seeInternalToolsOrDefault();

            for (Path pf : pluginFiles) {
                JsonNode json = KernelEvent.MAPPER.readTree(pf.toFile());
                String id   = json.path("id").asText("");
                String type = json.path("type").asText("");

                if (selfConfig != null && id.equals(selfConfig.id())) continue;
                if (!"tool".equals(type) && !"agent".equals(type) && !"system".equals(type)) continue;

                JsonNode caps = json.path("capabilities");
                if (caps.isArray()) {
                    List<String> agentTags = (selfConfig != null && selfConfig.agent() != null)
                            ? selfConfig.agent().toolTagsOrDefault() : List.of();
                    boolean noTagFilter = agentTags.isEmpty() || agentTags.contains("*");

                    for (JsonNode cap : caps) {
                        boolean isInternal = cap.path("internal").asBoolean(false);
                        if (isInternal && !canSeeInternal) continue;

                        String capName = cap.path("name").asText();

                        if (!noTagFilter) {
                            List<String> capTags = new ArrayList<>();
                            cap.path("tags").forEach(t -> capTags.add(t.asText()));
                            boolean matches = capTags.stream().anyMatch(agentTags::contains);
                            if (!matches) continue;
                        }

                        String capDesc = cap.path("description").asText("").strip();

                        // Translate dot to underscore for standard LLM tool schema requirements
                        String toolName = capName.replace(".", "_");
                        localMap.put(toolName, capName);

                        JsonObjectSchema paramSchema = parseInputSchema(cap.path("inputSchema"));

                        ToolSpecification spec = ToolSpecification.builder()
                                .name(toolName)
                                .description(capDesc)
                                .parameters(paramSchema)
                                .build();
                        list.add(spec);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[SKILLLOADER] Error discovering tools: " + e.getMessage());
        }

        return new DiscoveredTools(list, localMap);
    }

    private static JsonObjectSchema parseInputSchema(JsonNode inputSchema) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
        if (inputSchema.isMissingNode() || !inputSchema.isObject()) {
            return builder.build();
        }

        JsonNode props = inputSchema.path("properties");
        if (props.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = props.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String name = field.getKey();
                JsonNode prop = field.getValue();
                String type = prop.path("type").asText("string");
                String desc = prop.path("description").asText("").strip();

                JsonSchemaElement elem;
                if ("integer".equals(type) || "int".equals(type)) {
                    elem = JsonIntegerSchema.builder().description(desc).build();
                } else if ("number".equals(type) || "double".equals(type) || "float".equals(type)) {
                    elem = JsonNumberSchema.builder().description(desc).build();
                } else if ("boolean".equals(type)) {
                    elem = JsonBooleanSchema.builder().description(desc).build();
                } else if (prop.has("enum")) {
                    List<String> enumValues = new ArrayList<>();
                    JsonNode enums = prop.get("enum");
                    if (enums.isArray()) {
                        for (JsonNode ev : enums) enumValues.add(ev.asText());
                    }
                    elem = JsonEnumSchema.builder()
                            .description(desc)
                            .enumValues(enumValues)
                            .build();
                } else if ("array".equals(type)) {
                    elem = JsonArraySchema.builder()
                            .description(desc)
                            .build();
                } else {
                    elem = JsonStringSchema.builder().description(desc).build();
                }
                builder.addProperty(name, elem);
            }
        }

        JsonNode req = inputSchema.path("required");
        if (req.isArray()) {
            List<String> requiredFields = new ArrayList<>();
            for (JsonNode r : req) {
                requiredFields.add(r.asText());
            }
            builder.required(requiredFields);
        }

        return builder.build();
    }

    private static Path findProjectRoot(Path start) {
        Path current = start;
        while (current != null) {
            if (Files.exists(current.resolve("PLAN.md")) || Files.exists(current.resolve("Start.java"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    // ── Instance wrapper with cache ───────────────────────────────────────────

    private final String instanceSkillsDir;
    private final AgentConfig instanceConfig;
    private volatile String cachedPrompt;
    private volatile DiscoveredTools cachedTools;

    SkillLoader(String skillsDir, AgentConfig config) throws Exception {
        this.instanceSkillsDir = skillsDir;
        this.instanceConfig    = config;
        refresh(null);
    }

    void refresh(OutputStream out) throws Exception {
        this.cachedPrompt = loadSystemPrompt(instanceSkillsDir, false, instanceConfig);
        this.cachedTools  = discoverTools(instanceConfig, out);
    }

    String systemPrompt()      { return cachedPrompt; }
    DiscoveredTools tools()    { return cachedTools;  }
}
