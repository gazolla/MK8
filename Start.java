///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Start — single-entry bootrunner for the MK8 verification demo.
 *
 * Spawns Kernel and SummaryAgent in the background, waits for the UDS socket,
 * runs DemoRunner in the foreground, and tears down all child processes on exit.
 *
 * Logging strategy:
 *   logs/kernel.log        — Kernel stdout/stderr (truncated on each run)
 *   logs/summary-agent.log — SummaryAgent stdout/stderr (truncated on each run)
 *   logs/word-count.log    — WordCountTool stdout/stderr (appended by PluginManager)
 *   logs/start.log         — Everything printed by Start itself + DemoRunner output
 *                            (truncated on each run; mirrors the terminal exactly)
 */
public class Start {

    private static final Path   SOCKET_PATH = Path.of("/tmp/mk7/kernel.sock");
    private static final File   START_LOG   = new File("logs/start.log");
    private static final List<Process> backgroundProcesses = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Files.createDirectories(Path.of("logs"));

        // Tee System.out/err → terminal AND logs/start.log (truncated on each run).
        // Every subsequent println — including DemoRunner output streamed below —
        // lands in both places automatically.
        var original  = System.out;
        var logStream = new FileOutputStream(START_LOG, false); // false = truncate
        System.setOut(new PrintStream(new TeeOutputStream(original,  logStream), true));
        System.setErr(new PrintStream(new TeeOutputStream(System.err, logStream), true));

        Runtime.getRuntime().addShutdownHook(new Thread(Start::cleanup));

        try {
            Files.deleteIfExists(SOCKET_PATH);

            System.out.println("=======================================================");
            System.out.println("          MK8 MicroKernel — Boot Runner                ");
            System.out.println("=======================================================");
            System.out.println();

            // 1. Kernel — output → logs/kernel.log
            System.out.println("[BOOT] Starting Kernel.java in the background...");
            Process kernel = new ProcessBuilder("jbang", "Kernel.java")
                    .directory(new File("kernel"))
                    .redirectOutput(ProcessBuilder.Redirect.to(new File("logs/kernel.log")))
                    .redirectErrorStream(true)
                    .start();
            backgroundProcesses.add(kernel);

            // 2. Wait for UDS socket (timeout 5 s)
            System.out.print("[BOOT] Waiting for Kernel UDS socket to bind...");
            long start = System.currentTimeMillis();
            while (!Files.exists(SOCKET_PATH)) {
                if (System.currentTimeMillis() - start > 5_000)
                    throw new RuntimeException("Kernel socket not bound within 5 s — check logs/kernel.log");
                System.out.print(".");
                Thread.sleep(250);
            }
            System.out.println(" Connected! (Socket verified)\n");

            // 3. SummaryAgent — output → logs/summary-agent.log
            System.out.println("[BOOT] Starting SummaryAgent.java in the background...");
            Process summaryAgent = new ProcessBuilder("jbang", "SummaryAgent.java")
                    .directory(new File("system/summary-agent"))
                    .redirectOutput(ProcessBuilder.Redirect.to(new File("logs/summary-agent.log")))
                    .redirectErrorStream(true)
                    .start();
            backgroundProcesses.add(summaryAgent);
            Thread.sleep(1000);

            // 4. DemoRunner — stream output line-by-line through System.out so the
            //    TeeOutputStream captures it in start.log alongside the [BOOT] messages.
            System.out.println("[BOOT] Executing DemoRunner.java in the foreground...\n");
            Process demoRunner = new ProcessBuilder("jbang", "DemoRunner.java")
                    .directory(new File("system/demo-runner"))
                    .redirectErrorStream(true)
                    .start();

            try (var reader = new BufferedReader(new InputStreamReader(demoRunner.getInputStream()))) {
                reader.lines().forEach(System.out::println);
            }
            int exitCode = demoRunner.waitFor();
            System.out.println("\n[BOOT] DemoRunner finished with exit code: " + exitCode);

        } catch (InterruptedException e) {
            System.out.println("\n[BOOT] Interrupted. Initiating shutdown...");
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("\n[BOOT] Boot failed: " + e.getMessage());
        } finally {
            cleanup();
        }
    }

    private static synchronized void cleanup() {
        if (backgroundProcesses.isEmpty()) return;

        System.out.println("\n[BOOT] Cleaning up background processes...");
        for (Process p : backgroundProcesses) {
            if (p.isAlive()) {
                try {
                    long pid = p.pid();
                    p.destroy();
                    if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly();
                    System.out.println("[BOOT] Terminated background process PID=" + pid);
                } catch (Exception ignored) {}
            }
        }
        backgroundProcesses.clear();

        try {
            Files.deleteIfExists(SOCKET_PATH);
            System.out.println("[BOOT] Cleaned up socket file.");
        } catch (Exception ignored) {}

        System.out.println("[BOOT] Shutdown complete.");
    }

    /**
     * Writes every byte to two output streams simultaneously.
     * Used to mirror System.out to both the terminal and logs/start.log.
     */
    private static final class TeeOutputStream extends OutputStream {
        private final OutputStream a, b;

        TeeOutputStream(OutputStream a, OutputStream b) {
            this.a = a;
            this.b = b;
        }

        @Override public void write(int x) throws IOException {
            a.write(x); b.write(x);
        }

        @Override public void write(byte[] buf, int off, int len) throws IOException {
            a.write(buf, off, len); b.write(buf, off, len);
        }

        @Override public void flush() throws IOException {
            a.flush(); b.flush();
        }
        // Intentionally not closing a or b — System.out must stay open.
    }
}
