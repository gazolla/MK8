///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//SOURCES ../../helpers/BootHelper.java

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Boot — dynamic launcher for the Kiwi distro. The single boot mechanism; both
 * Start.java (interactive) and Dev.java (headless, for tests) delegate to it.
 *
 * Discovers what to launch by scanning the Kiwi plugin.json files that carry a "launch"
 * block (skipping on-demand plugins — the PluginInterceptor spawns those lazily). Only the
 * Kernel is hardcoded: it has no plugin.json and it receives the interceptor stack as args.
 *
 * Usage (from the MK8 root or projects/Kiwi/):
 *   jbang Boot.java               — start with the interactive Console
 *   jbang Boot.java --dev         — headless (no Console); stay up until Ctrl+C
 *   jbang Boot.java --clean       — clear logs before starting
 *   jbang Boot.java --reset-memory — delete session DBs before starting
 * Flags combine: jbang Boot.java --dev --clean
 *
 * Adding a plugin? Just give its plugin.json a "launch" block — no change here.
 */
public class Boot {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final List<Process> children = new ArrayList<>();

    static final Path SOCKET = Path.of("/tmp/mk8/kernel.sock");

    /** The Kiwi interceptor stack, passed to the Kernel as launch args. */
    static final String[] INTERCEPTORS = {
        "IdempotencyInterceptor", "CapabilityInterceptor", "PluginInterceptor",
        "BlackboardInterceptor", "LogInterceptor"
    };

    record Plugin(String name, Path dir, String[] command,
                  int order, int delayAfterMs, boolean interactive, String prebuild) {}

    public static void main(String[] args) throws Exception {
        var argList     = Arrays.asList(args);
        boolean dev         = argList.contains("--dev");
        boolean clean       = argList.contains("--clean");
        boolean resetMemory = argList.contains("--reset-memory");

        Path root       = BootHelper.findOrDownloadRoot(args);
        Path projectDir = root.resolve("projects/Kiwi");
        File kernelDir  = root.resolve("kernel").toFile();
        File logsDir    = projectDir.resolve("logs").toFile();
        logsDir.mkdirs();
        Files.createDirectories(projectDir.resolve("data"));
        Files.createDirectories(projectDir.resolve("workspace")); // agent file-output sandbox

        if (clean) {
            File[] logs = logsDir.listFiles((d, n) -> n.endsWith(".log"));
            if (logs != null) for (File f : logs) f.delete();
            System.out.println("[BOOT] Logs cleared.");
        }
        if (resetMemory) {
            File dataDir = projectDir.resolve("data").toFile();
            File[] dbs = dataDir.listFiles((d, n) -> n.startsWith("sessions-") && n.endsWith(".db"));
            if (dbs != null && dbs.length > 0) {
                for (File db : dbs) db.delete();
                System.out.println("[BOOT] Session memory reset: deleted " + dbs.length + " DB file(s).");
            } else {
                System.out.println("[BOOT] Session memory reset: no DB files found.");
            }
        }

        System.out.printf("[BOOT] Starting Kiwi (%s) from %s%n", dev ? "headless" : "interactive", projectDir);
        System.out.println("[BOOT] Logs → " + logsDir.getAbsolutePath() + "/");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[BOOT] Shutting down...");
            children.forEach(Process::destroyForcibly);
            try { Files.deleteIfExists(SOCKET); } catch (Exception ignored) {}
        }));

        // Guard: a live socket means another instance is up — a second one causes duplicate
        // subscribers and silently wrong responses.
        if (Files.exists(SOCKET)) {
            System.err.println("[BOOT] ERROR: kernel socket already exists at " + SOCKET);
            System.err.println("[BOOT] Another instance appears to be running. Run dev/Stop.java first.");
            System.exit(1);
        }

        // ── Kernel (hardcoded — receives the interceptor stack as args) ──────────
        List<String> kernelCmd = new ArrayList<>(List.of(
                "jbang", "Kernel.java",
                "--logs=" + logsDir.getAbsolutePath(),
                "--scan=" + projectDir.toFile().getAbsolutePath()));
        kernelCmd.addAll(Arrays.asList(INTERCEPTORS));
        spawn("Kernel", kernelDir, logsDir, false, kernelCmd.toArray(new String[0]));
        sleep(1500); // wait for socket

        // ── Everything else — discovered from plugin.json "launch" blocks ────────
        List<Plugin> plugins = discover(projectDir);
        int[] tiers = plugins.stream().mapToInt(Plugin::order).distinct().sorted().toArray();

        for (int tier : tiers) {
            List<Plugin> group = plugins.stream().filter(p -> p.order == tier).toList();

            Set<Path> built = new LinkedHashSet<>();
            for (Plugin p : group) {
                if (p.prebuild == null) continue;
                Path abs = p.dir.resolve(p.prebuild).normalize();
                if (built.add(abs)) prebuild(abs);
            }
            for (Plugin p : group) {
                if (p.interactive) continue;
                spawn(p.name, p.dir.toFile(), logsDir, false, p.command);
                if (p.delayAfterMs > 0) sleep(p.delayAfterMs);
            }
        }

        // Interactive plugins (Console) last — only in interactive mode.
        if (!dev) {
            plugins.stream().filter(Plugin::interactive)
                    .sorted(Comparator.comparingInt(Plugin::order))
                    .forEach(p -> {
                        try { spawn(p.name, p.dir.toFile(), logsDir, true, p.command); }
                        catch (Exception e) { System.err.println("[BOOT] ERROR spawning " + p.name + ": " + e); }
                    });
        }

        System.out.println("[BOOT] All components started."
                + (dev ? " Waiting for Ctrl+C..." : " Ctrl+C to stop."));

        if (dev) {
            Thread.currentThread().join();   // headless: stay alive
        } else {
            while (true) {                   // interactive: exit when a child dies (e.g. Console /quit)
                for (Process p : children)
                    if (!p.isAlive()) { System.out.println("[BOOT] A component exited. Shutting down."); System.exit(0); }
                sleep(1000);
            }
        }
    }

    // ── Plugin discovery ─────────────────────────────────────────────────────

    static List<Plugin> discover(Path projectDir) throws Exception {
        List<Plugin> result = new ArrayList<>();
        List<Path> pluginFiles;
        try (var walk = Files.walk(projectDir)) {
            pluginFiles = walk.filter(p -> p.getFileName().toString().equals("plugin.json"))
                              .sorted().toList();
        }
        for (Path pf : pluginFiles) {
            JsonNode json   = MAPPER.readTree(pf.toFile());
            JsonNode launch = json.path("launch");
            if (launch.isMissingNode()) continue;                                    // not a boot entry
            if ("on-demand".equals(json.path("lifecycle").path("mode").asText())) continue; // lazy-spawned

            result.add(new Plugin(
                    launch.path("name").asText(json.path("id").asText("?")),
                    pf.getParent(),
                    toArray(launch.path("command")),
                    launch.path("order").asInt(99),
                    launch.path("delayAfterMs").asInt(0),
                    launch.path("interactive").asBoolean(false),
                    launch.has("prebuild") ? launch.get("prebuild").asText() : null));
        }
        result.sort(Comparator.comparingInt(Plugin::order));
        return result;
    }

    static String[] toArray(JsonNode arr) {
        if (!arr.isArray()) return new String[0];
        String[] r = new String[arr.size()];
        for (int i = 0; i < arr.size(); i++) r[i] = arr.get(i).asText();
        return r;
    }

    // ── Process management ───────────────────────────────────────────────────

    static void spawn(String name, File workDir, File logsDir, boolean interactive, String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(workDir);
        if (interactive) {
            pb.inheritIO();
        } else {
            File log = new File(logsDir, name + ".log");
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log)).redirectErrorStream(true);
        }
        Process p = pb.start();
        children.add(p);
        System.out.printf("[BOOT] Launched %-14s (pid=%-6d) %s%n",
                name, p.pid(), interactive ? "[interactive]" : "→ logs/" + name + ".log");
    }

    static void prebuild(Path file) throws Exception {
        System.out.println("[BOOT] Pre-building " + file.getFileName() + "...");
        int exit = new ProcessBuilder("jbang", "build", file.getFileName().toString())
                .directory(file.getParent().toFile()).inheritIO().start().waitFor();
        if (exit != 0)
            System.out.println("[BOOT] WARNING: pre-build of " + file.getFileName() + " exited with code " + exit);
    }

    static void sleep(long ms) throws InterruptedException { Thread.sleep(ms); }
}
