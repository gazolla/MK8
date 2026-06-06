///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../../../kernel/KernelEvent.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginBase.java

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * PluginInstallerTool — installs generated Kiwi plugins.
 *
 * Capability: tool.plugin.install
 *   1. Validates plugin.json (required fields + launch block)
 *   2. Validates javaCode (JBang header for non-agent types)
 *   3. Resolves destination: tools/{name}/, agents/{name}/, system/{name}/
 *   4. Writes files (plugin.json, .java, persona.md)
 *   5. Pre-compiles Java source with `jbang build`
 *   6. Publishes plugin.installed → the kernel re-scans; the plugin spawns on-demand on first use
 *
 * (MK8: no explicit spawn — the PluginInterceptor owns lifecycle; the redundant MK7 spawn is gone.)
 *
 * NOT sandboxed to workspace/ — has full access to the Kiwi project tree.
 * Marked internal:true — only visible to agents with seeInternalTools:true.
 */
public class PluginInstallerTool {

    static final Path KIWI_ROOT = findKiwiRoot();

    public static void main(String[] args) throws Exception {
        log("Starting. Kiwi root: " + KIWI_ROOT, null);
        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, PluginInstallerTool::handle);
    }

    static void handle(String json, OutputStream out) throws Exception {
        KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);

        if (!"capability.tool.plugin.install".equals(event.type())) return;

        try {
            JsonNode payload = KernelEvent.MAPPER.readTree(event.payload());
            JsonNode input   = payload.has("input") ? payload.get("input") : KernelEvent.MAPPER.createObjectNode();

            String type         = input.path("type").asText("").trim();
            String name         = input.path("name").asText("").trim();
            String pluginJson   = input.path("pluginJson").asText("").trim();
            String javaCode     = input.path("javaCode").asText("").trim();
            String javaFilePath = input.path("javaFilePath").asText("").trim();
            String personaMd    = input.path("personaMd").asText("").trim();
            boolean overwrite   = input.path("overwrite").asBoolean(false);

            if (type.isBlank())       throw new IllegalArgumentException("'type' is required");
            if (name.isBlank())       throw new IllegalArgumentException("'name' is required");
            if (pluginJson.isBlank()) throw new IllegalArgumentException("'pluginJson' is required");

            // If javaFilePath provided, read code from workspace file (preserves newlines)
            if (!javaFilePath.isBlank()) {
                Path srcPath = KIWI_ROOT.resolve("workspace").resolve(javaFilePath);
                if (!Files.exists(srcPath))
                    throw new IllegalArgumentException("javaFilePath not found: workspace/" + javaFilePath);
                javaCode = Files.readString(srcPath);
                Files.deleteIfExists(srcPath);
                log("read " + javaFilePath + " from workspace (" + javaCode.length() + " chars)", out);
            }

            String result = install(type, name, pluginJson, javaCode, personaMd, overwrite, out);

            PluginBase.publish(
                KernelEvent.withCorrelation("capability.result",
                    KernelEvent.MAPPER.writeValueAsString(Map.of("result", result)),
                    "plugin-installer", event.correlationId(), event.sessionId()),
                out);
            PluginBase.publish(
                KernelEvent.of("plugin.installed",
                    KernelEvent.MAPPER.writeValueAsString(Map.of("name", name, "type", type)),
                    "plugin-installer"),
                out);
            log("installed: " + result, out);

        } catch (Exception e) {
            PluginBase.publish(
                KernelEvent.withCorrelation("capability.error",
                    KernelEvent.MAPPER.writeValueAsString(Map.of("reason", e.getMessage())),
                    "plugin-installer", event.correlationId(), event.sessionId()),
                out);
            log("ERROR: " + e.getMessage(), out);
        }
    }

    // ── Install pipeline ──────────────────────────────────────────────────────

    static String install(String type, String name, String pluginJson,
                          String javaCode, String personaMd, boolean overwrite,
                          OutputStream out) throws Exception {

        // 1 — Validate and parse plugin.json
        JsonNode pjNode = parseAndValidatePluginJson(pluginJson);

        // 2 — Validate javaCode (required for tool and system; empty is OK for agent)
        String javaFile = null;
        if (!"agent".equals(type)) {
            if (javaCode.isBlank())
                throw new IllegalArgumentException("javaCode is required for type=" + type);
            javaCode = restoreNewlines(javaCode);
            javaCode = restoreQuotes(javaCode);
            javaCode = fixUnbalancedBraces(javaCode);
            javaCode = fixMissingImports(javaCode);
            javaCode = fixNonStaticFields(javaCode);
            javaCode = fixEventScoping(javaCode);
            if (!javaCode.startsWith("///usr/bin/env jbang"))
                throw new IllegalArgumentException("javaCode must start with JBang header (///usr/bin/env jbang)");
            javaFile = extractClassName(javaCode) + ".java";
        }

        // 3 — Resolve destination directory
        Path destDir = resolveDestination(type, name);
        if (Files.exists(destDir)) {
            if (!overwrite)
                throw new IllegalStateException("Directory already exists: " + projectRelative(destDir)
                    + " — pass overwrite:true to reinstall");
            killPlugin(destDir, out);
            deleteDirectory(destDir);
            log("overwrite: removed " + projectRelative(destDir), out);
        }
        Files.createDirectories(destDir);

        // 4 — Write files (use pjNode so any injected defaults are persisted)
        int fileCount = 0;
        Files.writeString(destDir.resolve("plugin.json"),
            KernelEvent.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(pjNode));
        fileCount++;

        if (javaFile != null && !javaCode.isBlank()) {
            Files.writeString(destDir.resolve(javaFile), javaCode);
            fileCount++;
        }
        if (!personaMd.isBlank()) {
            Files.writeString(destDir.resolve("persona.md"), personaMd);
            fileCount++;
        }
        log("wrote " + fileCount + " file(s) to " + projectRelative(destDir), out);

        // 5 — Pre-compile (skip for agent — uses shared Agent.java)
        if (javaFile != null) {
            prebuild(destDir, javaFile, out);
        }

        // No explicit spawn — the plugin.installed event (published by the caller) triggers a
        // kernel catalog re-scan; on-demand plugins then spawn automatically on first invocation
        // (e.g. the creator's smoke test). This removes the MK7 redundant spawn step.
        return "installed " + projectRelative(destDir) + " (" + fileCount
                + " file(s)) — available after catalog refresh";
    }

    // ── Validation ────────────────────────────────────────────────────────────

    static JsonNode parseAndValidatePluginJson(String pluginJson) throws Exception {
        JsonNode node;
        try {
            node = KernelEvent.MAPPER.readTree(pluginJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("plugin.json is not valid JSON: " + e.getMessage());
        }
        for (String req : List.of("id", "type", "version")) {
            if (!node.has(req) || node.get(req).asText().isBlank())
                throw new IllegalArgumentException("plugin.json missing required field: " + req);
        }
        // lifecycle is optional — inject default if absent
        if (!node.has("lifecycle") || node.get("lifecycle").isNull()) {
            ((ObjectNode) node).set("lifecycle",
                KernelEvent.MAPPER.createObjectNode().put("mode", "persistent"));
        }
        if (!node.has("launch") || !node.get("launch").has("command"))
            throw new IllegalArgumentException("plugin.json missing 'launch.command'");
        return node;
    }

    static String extractClassName(String javaCode) {
        Matcher m = Pattern.compile("public\\s+class\\s+(\\w+)").matcher(javaCode);
        if (m.find()) return m.group(1);
        throw new IllegalArgumentException("Cannot find 'public class <Name>' in javaCode");
    }

    // ── Directory routing ─────────────────────────────────────────────────────

    static Path resolveDestination(String type, String name) {
        return switch (type) {
            case "tool"   -> KIWI_ROOT.resolve("tools").resolve(name);
            case "agent"  -> KIWI_ROOT.resolve("agents").resolve(name);
            case "system" -> KIWI_ROOT.resolve("system").resolve(name);
            default       -> throw new IllegalArgumentException("Unknown plugin type: " + type);
        };
    }

    // ── Pre-build ─────────────────────────────────────────────────────────────

    static void prebuild(Path dir, String javaFile, OutputStream out) throws Exception {
        log("pre-building " + javaFile + "...", out);
        Process p = new ProcessBuilder("jbang", "--fresh", "build", javaFile)
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .start();
        String buildOut = new String(p.getInputStream().readAllBytes());
        int exit = p.waitFor();
        if (exit != 0)
            throw new RuntimeException("jbang build failed (exit=" + exit + "): " + buildOut.trim());
        log("build OK", out);
    }

    // ── Code formatting ───────────────────────────────────────────────────────

    /**
     * LLMs collapse newlines to spaces when embedding Java code in JSON strings.
     * This restores line breaks for JBang header directives (//DEPS, //SOURCES, etc.)
     * so that jbang can parse them. Java code inside the class compiles fine on one line.
     */
    /**
     * LLMs sometimes replace Java double-quote string delimiters with backticks
     * to avoid JSON escaping complexity when embedding code in a JSON string.
     * Backtick is not a valid Java string delimiter — replace any backtick-delimited
     * strings (and lone trailing backticks that replaced closing quotes) with ".
     */
    static String restoreQuotes(String code) {
        if (code == null || !code.contains("`")) return code;
        // Replace backtick-delimited template-literal-style strings: `content` → "content"
        code = code.replaceAll("`([^`\n]*)`", "\"$1\"");
        // Replace any remaining lone backticks (e.g., trailing backtick replacing closing ")
        return code.replace("`", "\"");
    }

    static String restoreNewlines(String code) {
        if (code == null) return code;
        // Always fix: JBang directives must have a space after the // prefix.
        // LLMs sometimes omit the space: //SOURCES../../ instead of //SOURCES ../../
        code = code.replaceAll("//(JAVA|DEPS|SOURCES|CP|GAV|FILES)(?![ \n])", "//$1 ");

        if (code.contains("\n")) return code; // already formatted — keep as-is

        // LLMs collapse newlines to spaces when embedding code in JSON strings.
        // Only fix the JBang header — that's the part jbang parses line-by-line.
        return code
            // Each JBang directive must be on its own line.
            // Negative lookbehind for '/' excludes the shebang triple-slash.
            .replaceAll("(?<![/]) *//(?=JAVA |DEPS |SOURCES |CP |GAV |FILES )", "\n//")
            // After the last //SOURCES .java filename, the Java source starts.
            .replaceAll("(?<=\\.java) *(?=import )", "\n");
    }

    /** Counts unbalanced braces outside string literals and appends missing closing braces. */
    static String fixUnbalancedBraces(String code) {
        if (code == null) return code;
        int depth = 0;
        boolean inString = false;
        boolean escape   = false;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (escape)          { escape = false; continue; }
            if (c == '\\' && inString) { escape = true; continue; }
            if (c == '"')        { inString = !inString; continue; }
            if (!inString) {
                if (c == '{') depth++;
                else if (c == '}') depth--;
            }
        }
        if (depth > 0) {
            StringBuilder sb = new StringBuilder(code.trim());
            for (int i = 0; i < depth; i++) sb.append("\n}");
            return sb.toString();
        }
        return code;
    }

    /** Injects missing imports for common Java types that LLMs frequently forget. */
    static String fixMissingImports(String code) {
        if (code == null) return code;
        record Rule(String trigger, String importLine) {}
        var rules = List.of(
            new Rule("Map.",              "import java.util.Map;"),
            new Rule("List.",             "import java.util.List;"),
            new Rule("ArrayList",         "import java.util.ArrayList;"),
            new Rule("HashMap",           "import java.util.HashMap;"),
            new Rule("AtomicLong",        "import java.util.concurrent.atomic.AtomicLong;"),
            new Rule("AtomicInteger",     "import java.util.concurrent.atomic.AtomicInteger;"),
            new Rule("Instant.",          "import java.time.Instant;"),
            new Rule("LocalDateTime",     "import java.time.LocalDateTime;"),
            new Rule("Duration.",         "import java.time.Duration;"),
            new Rule("Collections.",      "import java.util.Collections;"),
            new Rule("Optional",          "import java.util.Optional;")
        );
        for (var rule : rules) {
            if (code.contains(rule.trigger()) && !code.contains(rule.importLine())) {
                code = injectImport(code, rule.importLine());
            }
        }
        return code;
    }

    /** Kiwi plugins use only static methods — class-level fields must be static.
     *  Converts: private Type field = ... → private static final Type field = ... */
    static String fixNonStaticFields(String code) {
        if (code == null) return code;
        return code.replaceAll(
            "private (?!static )([A-Z][\\w<>, \\[\\]]*) ([a-z]\\w+) =",
            "private static final $1 $2 =");
    }

    /** LLMs sometimes declare `KernelEvent event = ...` inside the try block, making it
     *  out of scope in the catch block. Move it before the try if it's the first statement. */
    static String fixEventScoping(String code) {
        if (code == null || !code.contains("KernelEvent event = KernelEvent.MAPPER.readValue")) return code;
        return code.replaceAll(
            "([ \\t]*)try \\{(\\r?\\n)([ \\t]+)(KernelEvent event = KernelEvent\\.MAPPER\\.readValue\\(json, KernelEvent\\.class\\);)",
            "$1$4$2$1try {");
    }

    static String injectImport(String code, String importLine) {
        // Insert after the last existing import statement
        int lastImport = code.lastIndexOf("\nimport ");
        if (lastImport >= 0) {
            int end = code.indexOf('\n', lastImport + 1);
            if (end < 0) end = code.length();
            return code.substring(0, end) + "\n" + importLine + code.substring(end);
        }
        // No existing imports — insert before 'public class'
        int classIdx = code.indexOf("public class ");
        if (classIdx >= 0) return code.substring(0, classIdx) + importLine + "\n" + code.substring(classIdx);
        return importLine + "\n" + code;
    }

    // ── Overwrite helpers ─────────────────────────────────────────────────────

    static void killPlugin(Path destDir, OutputStream out) throws Exception {
        Path pjPath = destDir.resolve("plugin.json");
        if (!Files.exists(pjPath)) return;
        JsonNode pj = KernelEvent.MAPPER.readTree(pjPath.toFile());
        String[] cmd = toArray(pj.path("launch").path("command"));
        String javaFile = Arrays.stream(cmd)
            .filter(s -> s.endsWith(".java"))
            .findFirst().orElse(null);
        if (javaFile == null) return;
        new ProcessBuilder("pkill", "-f", javaFile).start().waitFor();
        Thread.sleep(1500);
        log("killed process matching " + javaFile, out);
    }

    static void deleteDirectory(Path dir) throws Exception {
        Files.walk(dir)
            .sorted(Comparator.reverseOrder())
            .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    static Path findKiwiRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("Start.java"))) return current;
            current = current.getParent();
        }
        throw new RuntimeException("Cannot find Kiwi root (no Start.java found in ancestor dirs)");
    }

    static String projectRelative(Path p) {
        try { return KIWI_ROOT.relativize(p).toString(); }
        catch (Exception e) { return p.toString(); }
    }

    static String[] toArray(JsonNode arr) {
        if (arr == null || !arr.isArray()) return new String[0];
        String[] r = new String[arr.size()];
        for (int i = 0; i < arr.size(); i++) r[i] = arr.get(i).asText();
        return r;
    }

    static void log(String msg, OutputStream out) {
        System.out.println("[PLUGIN-INSTALLER] " + msg);
    }
}
