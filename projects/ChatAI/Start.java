///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Start — Boot runner for the ChatAI project.
 *
 * Launches the Kernel with the full interceptor stack, starts the Agent in the
 * background, then hands control to the Console with inherited IO so the user
 * gets a clean interactive terminal. Exits when the user types /quit.
 *
 * Can be invoked from any working directory:
 *   jbang projects/ChatAI/Start.java   (from project root)
 *   jbang Start.java                   (from projects/ChatAI/)
 *
 * Logs:
 *   logs/kernel.log    — kernel stdout/stderr
 *   logs/assistant.log — agent stdout/stderr
 */
public class Start {

    private static final Path   SOCKET_PATH       = Path.of("/tmp/mk8/kernel.sock");
    private static final long   SOCKET_TIMEOUT_MS = 5_000;
    private static final long   AGENT_WARMUP_MS   = 2_000;

    private static final List<Process> bg = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        // Locate the project root (dir containing kernel/Kernel.java) by walking up from cwd.
        Path root = findProjectRoot();

        Path chatAiDir   = root.resolve("projects/ChatAI");
        File kernelDir   = root.resolve("kernel").toFile();
        File agentDir    = chatAiDir.resolve("agent").toFile();
        File consoleDir  = chatAiDir.resolve("console").toFile();
        File logsDir     = chatAiDir.resolve("logs").toFile();
        File kernelLog   = new File(logsDir, "kernel.log");
        File assistantLog= new File(logsDir, "assistant.log");

        Files.createDirectories(logsDir.toPath());
        Runtime.getRuntime().addShutdownHook(new Thread(Start::cleanup));

        try {
            Files.deleteIfExists(SOCKET_PATH);

            System.out.println("[BOOT] Starting Kernel...");
            launchBackground(kernelDir, kernelLog,
                    "jbang", "Kernel.java",
                    "--logs="  + logsDir.getAbsolutePath(),
                    "--scan="  + chatAiDir.toFile().getAbsolutePath(),
                    "IdempotencyInterceptor", "CapabilityInterceptor", "PluginManager");

            waitForSocket();

            System.out.println("[BOOT] Starting Assistant in the background...");
            launchBackground(agentDir, assistantLog,
                    "jbang", "Agent.java");
            Thread.sleep(AGENT_WARMUP_MS);

            System.out.println("[BOOT] Starting Console...\n");
            Process console = new ProcessBuilder("jbang", "ConsolePlugin.java")
                    .directory(consoleDir)
                    .inheritIO()
                    .start();
            bg.add(console);
            console.waitFor();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("[BOOT] Boot failed: " + e.getMessage());
        } finally {
            cleanup();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Walks up from user.dir looking for a directory that contains kernel/Kernel.java. */
    private static Path findProjectRoot() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path p = cwd; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("kernel/Kernel.java")))
                return p;
        }
        throw new IllegalStateException("[BOOT] Cannot locate project root from: " + cwd
                + " — run jbang from anywhere inside the MK8 project tree.");
    }

    private static void waitForSocket() throws Exception {
        System.out.print("[BOOT] Waiting for Kernel UDS socket...");
        long start = System.currentTimeMillis();
        while (!Files.exists(SOCKET_PATH)) {
            if (System.currentTimeMillis() - start > SOCKET_TIMEOUT_MS)
                throw new RuntimeException("Kernel socket not ready — check logs/kernel.log");
            System.out.print(".");
            Thread.sleep(250);
        }
        System.out.println(" Connected!\n");
    }

    private static void launchBackground(File dir, File logFile, String... cmd) throws IOException {
        bg.add(new ProcessBuilder(cmd)
                .directory(dir)
                .redirectOutput(ProcessBuilder.Redirect.to(logFile))
                .redirectErrorStream(true)
                .start());
    }

    private static synchronized void cleanup() {
        if (bg.isEmpty()) return;
        System.out.println("\n[BOOT] Cleaning up...");
        for (Process p : bg) {
            if (p.isAlive()) {
                try {
                    long pid = p.pid();
                    p.destroy();
                    if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly();
                    System.out.println("[BOOT] Terminated PID=" + pid);
                } catch (Exception ignored) {}
            }
        }
        bg.clear();
        try { Files.deleteIfExists(SOCKET_PATH); } catch (Exception ignored) {}
        System.out.println("[BOOT] Shutdown complete.");
    }
}
