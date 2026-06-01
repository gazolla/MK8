///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//SOURCES ../../kernel/helpers/BootHelper.java

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Start — Boot runner for the LogStorm load test project.
 *
 * Spawns Kernel + LogEmitter in the background, then runs the JavaFX Dashboard
 * in the foreground. ProcessorTools are on-demand — PluginManager spawns them
 * automatically when the first log arrives after the user hits Play.
 *
 * Can be invoked from any working directory:
 *   jbang projects/LogStorm/Start.java
 *   jbang https://raw.githubusercontent.com/gazolla/MK8/main/projects/LogStorm/Start.java
 */
public class Start {

    final Path   SOCKET_PATH       = Path.of("/tmp/mk8/kernel.sock");
    final long   SOCKET_TIMEOUT_MS = 8_000;

    final List<Process> bg = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        new Start().boot(args);
    }

    void boot(String[] args) throws Exception {
        Path root    = BootHelper.findOrDownloadRoot(args);
        Path projDir = root.resolve("projects/LogStorm");

        File kernelDir    = root.resolve("kernel").toFile();
        File emitterDir   = projDir.resolve("log-emitter").toFile();
        File dashboardDir = projDir.resolve("dashboard").toFile();
        File logsDir      = projDir.resolve("logs").toFile();

        Files.createDirectories(logsDir.toPath());
        Runtime.getRuntime().addShutdownHook(new Thread(this::cleanup));

        try {
            Files.deleteIfExists(SOCKET_PATH);

            System.out.println("[BOOT] Starting Kernel...");
            background(kernelDir, new File(logsDir, "kernel.log"),
                    "jbang", "Kernel.java",
                    "--logs=" + logsDir.getAbsolutePath(),
                    "--scan=" + projDir.toFile().getAbsolutePath(),
                    "IdempotencyInterceptor", "CapabilityInterceptor", "PluginManager");

            waitForSocket();

            System.out.println("[BOOT] Starting LogEmitter...");
            background(emitterDir, new File(logsDir, "log-emitter.log"),
                    "jbang", "LogEmitter.java");

            System.out.println("[BOOT] Opening Dashboard...\n");
            Process dashboard = new ProcessBuilder("jbang", "Dashboard.java")
                    .directory(dashboardDir)
                    .inheritIO()
                    .start();
            bg.add(dashboard);
            dashboard.waitFor();

        } catch (Exception e) {
            System.err.println("[BOOT] Boot failed: " + e.getMessage());
        } finally {
            cleanup();
        }
    }

    void waitForSocket() throws Exception {
        System.out.print("[BOOT] Waiting for Kernel socket...");
        long start = System.currentTimeMillis();
        while (!Files.exists(SOCKET_PATH)) {
            if (System.currentTimeMillis() - start > SOCKET_TIMEOUT_MS)
                throw new RuntimeException("Kernel socket not ready — check logs/kernel.log");
            System.out.print(".");
            Thread.sleep(300);
        }
        System.out.println(" ready.\n");
    }

    void background(File dir, File log, String... cmd) throws IOException {
        bg.add(new ProcessBuilder(cmd)
                .directory(dir)
                .redirectOutput(ProcessBuilder.Redirect.to(log))
                .redirectErrorStream(true)
                .start());
    }

    synchronized void cleanup() {
        if (bg.isEmpty()) return;
        System.out.println("[BOOT] Shutting down...");
        for (Process p : bg) {
            if (p.isAlive()) {
                try {
                    p.destroy();
                    if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly();
                } catch (Exception ignored) {}
            }
        }
        bg.clear();
        try { Files.deleteIfExists(SOCKET_PATH); } catch (Exception ignored) {}
        System.out.println("[BOOT] Done.");
    }
}
