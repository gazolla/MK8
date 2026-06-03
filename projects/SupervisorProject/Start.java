///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//SOURCES ../../helpers/BootHelper.java

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Start — Boot runner for the SupervisorProject crash/restart demo.
 *
 * Launches the Kernel with IdempotencyInterceptor, CapabilityInterceptor and
 * PluginInterceptor (which now owns crash detection + auto-restart), starts the idle
 * Heartbeat in the background, then runs Chaos in the foreground. Chaos kills the
 * Heartbeat to simulate a crash and verifies the PluginInterceptor revives it, then
 * exercises the manual system.plugin.restart capability. After Chaos exits, the
 * Heartbeat log is printed so the restarts are visible.
 *
 * Can be invoked from any working directory:
 *   jbang projects/SupervisorProject/Start.java   (from project root)
 *   jbang Start.java                              (from projects/SupervisorProject/)
 *
 * Logs:
 *   logs/start.log     — this runner + Chaos output (tee'd to terminal)
 *   logs/kernel.log    — kernel stdout/stderr
 *   logs/heartbeat.log — heartbeat stdout/stderr (shows each (re)start)
 */
public class Start {

    private static final Path SOCKET_PATH       = Path.of("/tmp/mk8/kernel.sock");
    private static final long SOCKET_TIMEOUT_MS = 5_000;
    private static final long HEARTBEAT_WARMUP_MS = 1_000;
    private static final long DRAIN_MS          = 800;

    private static final List<Process> bg = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path root    = BootHelper.findOrDownloadRoot(args);
        Path projDir = root.resolve("projects/SupervisorProject");

        File kernelDir    = root.resolve("kernel").toFile();
        File heartbeatDir = projDir.resolve("heartbeat").toFile();
        File chaosDir     = projDir.resolve("chaos").toFile();
        File logsDir      = projDir.resolve("logs").toFile();
        File startLog     = new File(logsDir, "start.log");
        File kernelLog    = new File(logsDir, "kernel.log");
        File heartbeatLog = new File(logsDir, "heartbeat.log");

        Files.createDirectories(logsDir.toPath());
        setupLogging(startLog);
        Runtime.getRuntime().addShutdownHook(new Thread(Start::cleanup));

        try {
            Files.deleteIfExists(SOCKET_PATH);

            System.out.println("=======================================================");
            System.out.println("     MK8 SupervisorProject — Crash / Auto-restart       ");
            System.out.println("     Idempotency + Capability + PluginInterceptor           ");
            System.out.println("=======================================================\n");

            System.out.println("[BOOT] Starting Kernel (IdempotencyInterceptor CapabilityInterceptor PluginInterceptor)...");
            launchBackground(kernelDir, kernelLog,
                    "jbang", "Kernel.java",
                    "--logs=" + logsDir.getAbsolutePath(),
                    "--scan=" + projDir.toFile().getAbsolutePath(),
                    "IdempotencyInterceptor", "CapabilityInterceptor", "PluginInterceptor");

            waitForSocket();

            System.out.println("[BOOT] Starting Heartbeat in the background...");
            launchBackground(heartbeatDir, heartbeatLog, "jbang", "Heartbeat.java");
            Thread.sleep(HEARTBEAT_WARMUP_MS);

            System.out.println("[BOOT] Starting Chaos in the foreground...\n");
            int exit = streamForeground(chaosDir, "jbang", "Chaos.java");
            System.out.println("\n[BOOT] Chaos finished (exit=" + exit + ").");

            Thread.sleep(DRAIN_MS);
            printLog(heartbeatLog);

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

    private static void printLog(File logFile) {
        System.out.println("\n[BOOT] " + logFile.getName() + ":");
        System.out.println("──────────────────────────────────────────");
        try { Files.readAllLines(logFile.toPath()).forEach(System.out::println); }
        catch (IOException e) { System.err.println("[BOOT] Could not read log: " + e.getMessage()); }
        System.out.println("──────────────────────────────────────────");
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
