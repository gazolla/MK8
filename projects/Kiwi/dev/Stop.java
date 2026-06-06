///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;

/**
 * Stop — kills all running Kiwi components and removes the kernel socket.
 *
 * Discovers which Java files to match by reading all plugin.json files in the
 * project that contain a "launch" block — no hardcoded class names.
 *
 * Works from any directory (projects/Kiwi/ or projects/Kiwi/dev/).
 * Usage: jbang Stop.java
 */
public class Stop {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final Path SOCKET = Path.of("/tmp/mk8/kernel.sock");

    public static void main(String[] args) throws Exception {
        System.out.println("[STOP] Stopping Kiwi...");

        Path root = projectRoot();
        Set<String> patterns = discoverPatterns(root);

        long killed = ProcessHandle.allProcesses()
            .filter(p -> {
                String cmd = p.info().commandLine().orElse("");
                if (cmd.isEmpty()) {
                    String c = p.info().command().orElse("");
                    String[] procArgs = p.info().arguments().orElse(new String[0]);
                    cmd = c + " " + String.join(" ", procArgs);
                }
                final String finalCmd = cmd;
                return patterns.stream().anyMatch(finalCmd::contains);
            })
            .peek(p -> {
                String cmd = p.info().commandLine().orElse("");
                if (cmd.isEmpty()) {
                    String c = p.info().command().orElse("");
                    String[] procArgs = p.info().arguments().orElse(new String[0]);
                    cmd = c + " " + String.join(" ", procArgs);
                }
                System.out.printf("[STOP] Killing pid=%-6d  %s%n",
                    p.pid(), shortCmd(cmd, patterns));
            })
            .peek(ProcessHandle::destroyForcibly)
            .count();

        if (Files.deleteIfExists(SOCKET))
            System.out.println("[STOP] Removed " + SOCKET);

        if (killed == 0)
            System.out.println("[STOP] No Kiwi processes found.");
        else
            System.out.println("[STOP] Stopped " + killed + " process(es). Done.");
    }

    /** Reads all plugin.json "launch.command" arrays and collects .java filenames. */
    static Set<String> discoverPatterns(Path root) {
        Set<String> patterns = new LinkedHashSet<>();
        patterns.add("Kernel.java"); // always present — no plugin.json boot entry
        patterns.add("Kernel.jar");
        patterns.add("Agent.java");  // structural agent runtime (shared, launched per persona)
        try {
            Files.walk(root)
                .filter(p -> p.getFileName().toString().equals("plugin.json"))
                .sorted()
                .forEach(pf -> {
                    try {
                        JsonNode launch = MAPPER.readTree(pf.toFile()).path("launch");
                        if (launch.isMissingNode()) return;
                        JsonNode cmd = launch.path("command");
                        if (!cmd.isArray()) return;
                        for (JsonNode arg : cmd) {
                            String s = arg.asText();
                            if (s.endsWith(".java")) {
                                String name = Path.of(s).getFileName().toString();
                                patterns.add(name);
                                patterns.add(name.replace(".java", ".jar"));
                            }
                        }
                    } catch (Exception ignored) {}
                });
        } catch (Exception ignored) {}
        return patterns;
    }

    static String shortCmd(String cmd, Set<String> patterns) {
        return patterns.stream().filter(cmd::contains).findFirst().orElse(
            cmd.length() > 60 ? cmd.substring(0, 60) + "…" : cmd);
    }

    static Path projectRoot() {
        Path dir = Path.of(System.getProperty("user.dir"));
        return dir.getFileName().toString().equals("dev") ? dir.getParent() : dir;
    }
}
