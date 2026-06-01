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
 * Logs:
 *   logs/kernel.log    — kernel stdout/stderr
 *   logs/assistant.log — agent stdout/stderr
 */
public class Start {

    private static final Path   SOCKET_PATH       = Path.of("/tmp/mk8/kernel.sock");
    private static final File   KERNEL_LOG        = new File("logs/kernel.log");
    private static final File   ASSISTANT_LOG     = new File("logs/assistant.log");
    private static final String KERNEL_DIR        = "../../kernel";
    private static final String AGENT_DIR         = "agent";
    private static final String CONSOLE_DIR       = "console";
    private static final long   SOCKET_TIMEOUT_MS = 5_000;
    private static final long   AGENT_WARMUP_MS   = 2_000;

    private static final List<Process> bg = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Files.createDirectories(Path.of("logs"));
        Runtime.getRuntime().addShutdownHook(new Thread(Start::cleanup));

        try {
            Files.deleteIfExists(SOCKET_PATH);

            System.out.println("[BOOT] Starting Kernel...");
            launchBackground(new File(KERNEL_DIR), KERNEL_LOG,
                    "jbang", "Kernel.java",
                    "--logs="  + new File("logs").getAbsolutePath(),
                    "--scan="  + new File(".").getAbsolutePath(),
                    "IdempotencyInterceptor", "CapabilityInterceptor", "PluginManager");

            waitForSocket();

            System.out.println("[BOOT] Starting Assistant in the background...");
            launchBackground(new File(AGENT_DIR), ASSISTANT_LOG,
                    "jbang", "Agent.java");
            Thread.sleep(AGENT_WARMUP_MS);

            System.out.println("[BOOT] Starting Console...\n");
            Process console = new ProcessBuilder("jbang", "ConsolePlugin.java")
                    .directory(new File(CONSOLE_DIR))
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
