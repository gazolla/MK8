///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../../kernel/KernelEvent.java
//SOURCES DevClient.java

import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.nio.file.*;
import java.util.*;

/**
 * Health — shows the status of a running Kiwi system.
 *
 * Checks:
 *   1. Environment variables (API keys)
 *   2. Kernel socket (alive / not found, via a transient DevClient connection)
 *   3. Registered capabilities — queried LIVE from the kernel via the built-in
 *      `system.capability.list` capability (MK8), instead of MK7's CapReg.log
 *   4. workspace/ contents
 *
 * Works from any directory (projects/Kiwi/ or projects/Kiwi/dev/).
 * Usage: jbang Health.java
 */
public class Health {

    static final Path SOCKET = Path.of("/tmp/mk8/kernel.sock");
    static final String CLIENT_ID = "health-check";

    // ANSI colours
    static final String G = "[32m", R = "[31m",
                        Y = "[33m", B = "[1m", X = "[0m";

    public static void main(String[] args) throws Exception {
        Path root = projectRoot();
        System.out.println(B + "── Kiwi Health ────────────────────────────────" + X);

        showEnv();
        System.out.println();

        boolean kernelUp = checkKernel();
        System.out.println();

        if (kernelUp) {
            showRegistered(root);
            System.out.println();
        }

        showWorkspace(root);
        System.out.println(B + "────────────────────────────────────────────────" + X);
    }

    // ── Environment ───────────────────────────────────────────────────────────

    static void showEnv() {
        System.out.println(B + "Environment" + X);
        checkEnvVar("OPENROUTER_API_KEY", "LLM agents");
        checkEnvVar("BRAVE_API_KEY",      "search tool");
        checkEnvVarOptional("TELEGRAM_BOT_TOKEN", "telegram plugin");
    }

    static void checkEnvVar(String name, String purpose) {
        String val = System.getenv(name);
        if (val != null && !val.isBlank())
            System.out.printf("  " + G + "✓" + X + "  %-24s set%n", name);
        else
            System.out.printf("  " + Y + "✗" + X + "  %-24s NOT SET — needed by %s%n", name, purpose);
    }

    static void checkEnvVarOptional(String name, String purpose) {
        String val = System.getenv(name);
        if (val != null && !val.isBlank())
            System.out.printf("  " + G + "✓" + X + "  %-24s set%n", name);
        else
            System.out.printf("  " + X + "-" + X + "  %-24s not set  (optional — needed by %s)%n", name, purpose);
    }

    // ── Kernel ────────────────────────────────────────────────────────────────

    static boolean checkKernel() {
        if (!Files.exists(SOCKET)) {
            System.out.println(R + "✗ Kernel" + X + "  socket not found — run: jbang Start.java");
            return false;
        }
        try (DevClient c = DevClient.connect(CLIENT_ID, List.of())) {
            System.out.println(G + "✓ Kernel" + X + "  up  →  " + SOCKET);
            return true;
        } catch (Exception e) {
            System.out.println(R + "✗ Kernel" + X + "  socket exists but refused connection");
            return false;
        }
    }

    // ── Registered capabilities (live query) ──────────────────────────────────

    static void showRegistered(Path root) {
        List<JsonNode> caps = queryLiveCapabilities();
        if (caps == null) {
            System.out.println(Y + "? Capabilities" + X + "  could not query the kernel");
            return;
        }
        if (caps.isEmpty()) {
            System.out.println(Y + "? Capabilities" + X + "  none registered yet");
            return;
        }

        var live = caps.stream().filter(c -> c.path("live").asBoolean(false)).toList();
        var offline = caps.stream().filter(c -> !c.path("live").asBoolean(false)).toList();

        System.out.println(B + "Capabilities  (" + live.size() + " live, " + offline.size() + " on-demand/offline)" + X);
        if (!live.isEmpty()) {
            System.out.println("  Live:");
            live.forEach(c -> System.out.printf("    " + G + "✓" + X + "  %-35s → %s%n",
                    c.path("capability").asText(), c.path("provider").asText()));
        }
        if (!offline.isEmpty()) {
            System.out.println("  On-demand / offline:");
            offline.forEach(c -> System.out.printf("    " + Y + "·" + X + "  %-35s → %s%n",
                    c.path("capability").asText(), c.path("provider").asText()));
        }
    }

    /** Invokes the kernel built-in `system.capability.list` and returns the catalog entries. */
    static List<JsonNode> queryLiveCapabilities() {
        String corrId = UUID.randomUUID().toString();
        String payload = "";
        try {
            payload = KernelEvent.MAPPER.writeValueAsString(Map.of("name", "system.capability.list"));
            try (DevClient c = DevClient.connect(CLIENT_ID, List.of("capability.result", "capability.error"))) {
                KernelEvent req = KernelEvent.withCorrelation("capability.invoke", payload, CLIENT_ID, corrId, null);
                KernelEvent ev = c.request(req, 5000L,
                        e -> corrId.equals(e.correlationId())
                             && ("capability.result".equals(e.type()) || "capability.error".equals(e.type())));
                if (ev == null || "capability.error".equals(ev.type())) return null;
                JsonNode r = KernelEvent.MAPPER.readTree(ev.payload());
                JsonNode arr = r.has("result") ? KernelEvent.MAPPER.readTree(r.get("result").asText()) : r;
                List<JsonNode> out = new ArrayList<>();
                if (arr.isArray()) arr.forEach(out::add);
                return out;
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ── Workspace ─────────────────────────────────────────────────────────────

    static void showWorkspace(Path root) {
        File ws = root.resolve("workspace").toFile();
        if (!ws.exists()) {
            System.out.println(Y + "? workspace/" + X + "  directory not found");
            return;
        }
        File[] files = ws.listFiles(f -> !f.getName().startsWith("."));
        if (files == null || files.length == 0) {
            System.out.println("workspace/  (empty)");
            return;
        }
        System.out.println(B + "workspace/  (" + files.length + " file(s))" + X);
        Arrays.stream(files)
                .sorted(Comparator.comparingLong(File::lastModified).reversed())
                .forEach(f -> System.out.printf("  %-42s  %,6d bytes%n", f.getName(), f.length()));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Project root whether we run from projects/Kiwi/ or projects/Kiwi/dev/. */
    static Path projectRoot() {
        Path dir = Path.of(System.getProperty("user.dir"));
        return dir.getFileName().toString().equals("dev") ? dir.getParent() : dir;
    }
}
