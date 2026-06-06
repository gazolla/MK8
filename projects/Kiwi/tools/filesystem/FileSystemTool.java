///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../../../kernel/KernelEvent.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginBase.java

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * FileSystemTool — file operations for Kiwi agents.
 *
 * Trigger event: capability.tool.filesystem.op
 * Input:  {"name": "tool.filesystem.op", "input": {"op": "read|write|ls|exists", "path": "...", "content": "..."}}
 * Result: {"result": <string or list>}
 *
 * Access model:
 *   - READS span the entire MK8 repository (kernel/ source, docs/, every project, an agent's own
 *     skill files and refs/). This lets agents introspect the real system instead of relying on
 *     hardcoded knowledge — e.g. the coder can read kernel/interceptors/plugin/PluginConfig.java to
 *     learn the actual config API. Reads may use absolute paths (inside the repo) or relative paths
 *     (resolved against the MK8 root, then the Kiwi project root).
 *   - WRITES/deletes remain sandboxed to workspace/ (path traversal blocked).
 */
public class FileSystemTool {

    static final Path WORKSPACE    = findProjectWorkspace();
    static final Path PROJECT_ROOT = findProjectRoot();   // projects/Kiwi — relative-read base #2 + ls "."
    static final Path MK8_ROOT     = findMk8Root();        // repo root — read scope spans all of MK8



    public static void main(String[] args) throws Exception {
        Files.createDirectories(WORKSPACE);
        System.out.println("[TOOL-FILESYSTEM] Starting. Workspace: " + WORKSPACE);
        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, FileSystemTool::handle);
    }

    static void handle(String json, OutputStream out) throws Exception {
        KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);

        if (!"capability.tool.filesystem.op".equals(event.type())) return;

        try {
            JsonNode payload = KernelEvent.MAPPER.readTree(event.payload());
            JsonNode input   = payload.has("input") ? payload.get("input") : KernelEvent.MAPPER.createObjectNode();
            String op        = input.has("op")   ? input.get("op").asText()   : "";
            String pathStr   = input.has("path") ? input.get("path").asText() : "";

            String resultValue = switch (op) {
                case "read"   -> opRead(pathStr);
                case "write"  -> opWrite(pathStr, input.has("content") ? input.get("content").asText() : "");
                case "append" -> opAppend(pathStr, input.has("content") ? input.get("content").asText() : "");
                case "ls"     -> opLs(pathStr);
                case "exists" -> opExists(pathStr);
                case "delete" -> opDelete(pathStr);
                case "touch"  -> opTouch(pathStr);
                default       -> throw new IllegalArgumentException("Unknown op: " + op);
            };

            String result = KernelEvent.MAPPER.writeValueAsString(Map.of("result", resultValue));
            PluginBase.publish(
                    KernelEvent.withCorrelation("capability.result", result, "tool-filesystem",
                            event.correlationId(), event.sessionId()),
                    out);
            log("op=" + op + " path=" + pathStr, out);

        } catch (Exception e) {
            String errPayload = KernelEvent.MAPPER.writeValueAsString(Map.of("reason", e.getMessage()));
            PluginBase.publish(
                    KernelEvent.withCorrelation("capability.error", errPayload, "tool-filesystem",
                            event.correlationId(), event.sessionId()),
                    out);
            log("ERROR: " + e.getMessage(), out);
        }
    }

    // ── Operations ────────────────────────────────────────────────────────────

    static String opRead(String pathStr) throws IOException {
        Path target = resolveReadable(pathStr);
        if (!Files.exists(target)) throw new IOException("File not found: " + pathStr);
        String fname = target.getFileName().toString().toLowerCase();
        if (isBinaryExtension(fname)) {
            long size = Files.size(target);
            return "[Binary File: " + target.getFileName().toString() + ", Size: " + size + " bytes]";
        }
        return Files.readString(target);
    }

    static String opWrite(String pathStr, String content) throws IOException {
        Path target = resolve(pathStr);
        String fname = target.getFileName().toString().toLowerCase();
        if (isBinaryExtension(fname) && isUrl(content)) {
            throw new IllegalArgumentException(
                    "Cannot write a URL string to '" + pathStr + "'. " +
                    "To download binary files (images, PDFs), use tool.browser.fetch with op:download.");
        }
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
        return "written " + content.length() + " chars to " + pathStr;
    }

    static boolean isBinaryExtension(String filename) {
        return filename.endsWith(".jpg") || filename.endsWith(".jpeg") || filename.endsWith(".png")
            || filename.endsWith(".gif") || filename.endsWith(".webp") || filename.endsWith(".pdf")
            || filename.endsWith(".zip") || filename.endsWith(".mp4") || filename.endsWith(".mp3")
            || filename.endsWith(".bin");
    }

    static boolean isUrl(String content) {
        String s = content == null ? "" : content.strip();
        return (s.startsWith("http://") || s.startsWith("https://"))
                && !s.contains("\n") && s.length() < 2048;
    }

    static String opAppend(String pathStr, String content) throws IOException {
        Path target = resolve(pathStr);
        if (!Files.exists(target)) throw new IOException("File not found (use op:write to create): " + pathStr);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, java.nio.file.StandardOpenOption.APPEND);
        return "appended " + content.length() + " chars to " + pathStr;
    }

    static String opLs(String pathStr) throws IOException {
        Path target;
        if (pathStr.isBlank()) {
            target = WORKSPACE;
        } else if (".".equals(pathStr) || "/".equals(pathStr)) {
            target = PROJECT_ROOT;
        } else {
            target = resolveReadable(pathStr);
        }
        if (!Files.isDirectory(target)) throw new IOException("Not a directory: " + pathStr);
        List<String> entries = Files.list(target)
                .map(p -> p.getFileName().toString() + (Files.isDirectory(p) ? "/" : ""))
                .sorted()
                .collect(Collectors.toList());
        return KernelEvent.MAPPER.writeValueAsString(entries);
    }

    static String opExists(String pathStr) throws IOException {
        return String.valueOf(Files.exists(resolveReadable(pathStr)));
    }

    static String opDelete(String pathStr) throws IOException {
        Path target = resolve(pathStr);
        if (!Files.exists(target)) throw new IOException("File not found: " + pathStr);
        if (Files.isDirectory(target)) throw new IOException("Cannot delete directory: " + pathStr);
        Files.delete(target);
        return "deleted " + pathStr;
    }

    /**
     * Touches a workspace file — updates its last-modified time to now.
     * Used to signal the Telegram interface to send the file to the user:
     * TelegramPlugin compares workspace snapshots before/after each request and
     * automatically sends any file whose mtime increased during the request.
     */
    static String opTouch(String pathStr) throws IOException {
        Path target = resolve(pathStr);
        if (!Files.exists(target)) throw new IOException("File not found: " + pathStr);
        Files.setLastModifiedTime(target,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        return "touched " + pathStr;
    }

    // ── Project root discovery ────────────────────────────────────────────────

    static Path findProjectWorkspace() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (java.nio.file.Files.exists(current.resolve("Start.java"))) {
                return current.resolve("workspace").normalize();
            }
            current = current.getParent();
        }
        return Path.of("workspace").toAbsolutePath().normalize();
    }

    static Path findProjectRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (java.nio.file.Files.exists(current.resolve("Start.java"))) {
                return current.normalize();
            }
            current = current.getParent();
        }
        return Path.of("").toAbsolutePath().normalize();
    }

    /** MK8 repository root — identified by kernel/Kernel.java. Defines the read scope. */
    static Path findMk8Root() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (java.nio.file.Files.exists(current.resolve("kernel/Kernel.java"))) {
                return current.normalize();
            }
            current = current.getParent();
        }
        return PROJECT_ROOT;
    }

    // ── Path safety ───────────────────────────────────────────────────────────

    /**
     * Resolves a path for READ-ONLY operations. Read scope is the entire MK8 repository.
     *   - "workspace/…"   → the workspace sandbox.
     *   - absolute path    → allowed iff it stays inside MK8_ROOT.
     *   - relative path    → resolved against MK8_ROOT first (kernel/, docs/, projects/…), then the
     *                        Kiwi project root (agents/, tools/, system/…).
     * Traversal outside MK8_ROOT is strictly blocked.
     */
    static Path resolveReadable(String pathStr) {
        if (pathStr == null || pathStr.isBlank()) throw new IllegalArgumentException("Path is required");

        if (pathStr.startsWith("workspace/") || pathStr.equals("workspace")) {
            return resolve(pathStr);
        }

        Path p = Path.of(pathStr);
        if (p.isAbsolute()) {
            Path n = p.normalize();
            if (n.startsWith(MK8_ROOT)) return n;
            throw new SecurityException("Read blocked outside MK8 root: " + pathStr);
        }

        for (Path base : new Path[]{ MK8_ROOT, PROJECT_ROOT }) {
            Path r = base.resolve(pathStr).normalize();
            if (r.startsWith(MK8_ROOT) && Files.exists(r)) return r;
        }
        // Not found under either root — return an MK8-root resolution so opRead raises a clear error.
        Path r = MK8_ROOT.resolve(pathStr).normalize();
        if (!r.startsWith(MK8_ROOT)) throw new SecurityException("Path traversal blocked: " + pathStr);
        return r;
    }

    /** Resolves a path strictly within WORKSPACE — used for write and delete. */
    static Path resolve(String pathStr) {
        if (pathStr == null || pathStr.isBlank()) throw new IllegalArgumentException("Path is required");
        // Strip leading "workspace/" prefix — LLMs often include it, causing workspace/workspace nesting
        String rel = pathStr;
        if (rel.startsWith("workspace/")) rel = rel.substring("workspace/".length());
        else if (rel.equals("workspace"))  rel = "";
        Path resolved = WORKSPACE.resolve(rel).normalize();
        if (!resolved.startsWith(WORKSPACE))
            throw new SecurityException("Path traversal blocked: " + pathStr);
        return resolved;
    }

    static void log(String msg, OutputStream out) {
        System.out.println("[TOOL-FILESYSTEM] " + msg);
    }
}
