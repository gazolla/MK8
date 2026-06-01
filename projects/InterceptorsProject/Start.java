///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//SOURCES ../../kernel/BootHelper.java

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Start — Boot runner for the InterceptorsProject demo.
 *
 * Launches the Kernel with all three interceptors (IdempotencyInterceptor,
 * CapabilityInterceptor, PluginManager), starts SummaryAgent in the background,
 * then runs DemoRunner in the foreground to validate the full pipeline.
 *
 * Can be invoked from any working directory:
 *   jbang projects/InterceptorsProject/Start.java   (from project root)
 *   jbang Start.java                                (from projects/InterceptorsProject/)
 *
 * Logs:
 *   logs/start.log         — this runner + DemoRunner output (tee'd to terminal)
 *   logs/kernel.log        — kernel stdout/stderr
 *   logs/summary-agent.log — summary-agent stdout/stderr
 */
public class Start {

    private static final Path   SOCKET_PATH       = Path.of("/tmp/mk8/kernel.sock");
    private static final long   SOCKET_TIMEOUT_MS = 5_000;
    private static final long   AGENT_WARMUP_MS   = 1_000;

    private static final List<Process> bg = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path root    = BootHelper.findOrDownloadRoot(args);
        Path projDir = root.resolve("projects/InterceptorsProject");

        File kernelDir      = root.resolve("kernel").toFile();
        File summaryAgentDir= projDir.resolve("summary-agent").toFile();
        File demoRunnerDir  = projDir.resolve("demo-runner").toFile();
        File logsDir        = projDir.resolve("logs").toFile();
        File startLog       = new File(logsDir, "start.log");
        File kernelLog      = new File(logsDir, "kernel.log");
        File summaryAgentLog= new File(logsDir, "summary-agent.log");

        Files.createDirectories(logsDir.toPath());
        setupLogging(startLog);
        Runtime.getRuntime().addShutdownHook(new Thread(Start::cleanup));

        try {
            Files.deleteIfExists(SOCKET_PATH);

            System.out.println("=======================================================");
            System.out.println("          MK8 MicroKernel — Boot Runner                ");
            System.out.println("=======================================================\n");

            System.out.println("[BOOT] Starting Kernel (IdempotencyInterceptor CapabilityInterceptor PluginManager)...");
            launchBackground(kernelDir, kernelLog,
                    "jbang", "Kernel.java",
                    "--logs=" + logsDir.getAbsolutePath(),
                    "IdempotencyInterceptor", "CapabilityInterceptor", "PluginManager");

            waitForSocket();

            System.out.println("[BOOT] Starting SummaryAgent in the background...");
            launchBackground(summaryAgentDir, summaryAgentLog, "jbang", "SummaryAgent.java");
            Thread.sleep(AGENT_WARMUP_MS);

            System.out.println("[BOOT] Executing DemoRunner in the foreground...\n");
            int exit = streamForeground(demoRunnerDir, "jbang", "DemoRunner.java");
            System.out.println("\n[BOOT] DemoRunner finished with exit code: " + exit);

        } catch (InterruptedException e) {
            System.out.println("\n[BOOT] Interrupted.");
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("\n[BOOT] Boot failed: " + e.getMessage());
        } finally {
            cleanup();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void setupLogging(File logFile) throws IOException {
        var original  = System.out;
        var logStream = new FileOutputStream(logFile, false);
        System.setOut(new PrintStream(new TeeOutputStream(original,  logStream), true));
        System.setErr(new PrintStream(new TeeOutputStream(System.err, logStream), true));
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

    private static int streamForeground(File dir, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd)
                .directory(dir)
                .redirectErrorStream(true)
                .start();
        try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            reader.lines().forEach(System.out::println);
        }
        return p.waitFor();
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

    private static final class TeeOutputStream extends OutputStream {
        private final OutputStream a, b;

        TeeOutputStream(OutputStream a, OutputStream b) { this.a = a; this.b = b; }

        @Override public void write(int x) throws IOException { a.write(x); b.write(x); }

        @Override public void write(byte[] buf, int off, int len) throws IOException {
            a.write(buf, off, len); b.write(buf, off, len);
        }

        @Override public void flush() throws IOException { a.flush(); b.flush(); }
    }
}
