///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Start — single-entry bootrunner for the MK8 verification demo.
 *
 * Spawns the Kernel and SummaryAgent in the background, waits for the socket
 * to bind, executes the interactive DemoRunner in the foreground, and safely
 * tears down all child processes upon completion or interruption.
 */
public class Start {

    private static final Path SOCKET_PATH = Path.of("/tmp/mk7/kernel.sock");
    private static final List<Process> backgroundProcesses = new ArrayList<>();

    public static void main(String[] args) {
        System.out.println("=======================================================");
        System.out.println("          MK8 MicroKernel — Boot Runner                ");
        System.out.println("=======================================================");
        System.out.println();

        // Ensure background processes are cleaned up on shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(Start::cleanup));

        try {
            // Delete any existing socket file from previous dirty runs
            Files.deleteIfExists(SOCKET_PATH);

            // Ensure logs directory exists at project root
            Files.createDirectories(Path.of("logs"));

            // 1. Start the Kernel
            System.out.println("[BOOT] Starting Kernel.java in the background...");
            Process kernel = new ProcessBuilder("jbang", "Kernel.java")
                    .directory(new File("kernel"))
                    .redirectOutput(ProcessBuilder.Redirect.to(new File("logs/kernel.log")))
                    .redirectError(ProcessBuilder.Redirect.to(new File("logs/kernel.log")))
                    .start();
            backgroundProcesses.add(kernel);

            // 2. Poll socket availability (timeout 5 seconds)
            System.out.print("[BOOT] Waiting for Kernel UDS socket to bind...");
            long start = System.currentTimeMillis();
            while (!Files.exists(SOCKET_PATH)) {
                if (System.currentTimeMillis() - start > 5000) {
                    throw new RuntimeException("Kernel socket failed to bind within 5 seconds. Check logs/kernel.log");
                }
                System.out.print(".");
                Thread.sleep(250);
            }
            System.out.println(" Connected! (Socket verified)\n");

            // 3. Start the Summary Agent
            System.out.println("[BOOT] Starting SummaryAgent.java in the background...");
            Process summaryAgent = new ProcessBuilder("jbang", "SummaryAgent.java")
                    .directory(new File("system/summary-agent"))
                    .redirectOutput(ProcessBuilder.Redirect.to(new File("logs/summary-agent.log")))
                    .redirectError(ProcessBuilder.Redirect.to(new File("logs/summary-agent.log")))
                    .start();
            backgroundProcesses.add(summaryAgent);

            // Give the persistent agent a brief moment to connect and register
            Thread.sleep(1000);

            // 4. Execute the DemoRunner in the foreground (inherits standard input/output streams)
            System.out.println("[BOOT] Executing DemoRunner.java in the foreground...\n");
            Process demoRunner = new ProcessBuilder("jbang", "DemoRunner.java")
                    .directory(new File("system/demo-runner"))
                    .inheritIO()
                    .start();

            // Wait for the DemoRunner to complete execution
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
                    if (!p.waitFor(3, TimeUnit.SECONDS)) {
                        p.destroyForcibly();
                    }
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
}
