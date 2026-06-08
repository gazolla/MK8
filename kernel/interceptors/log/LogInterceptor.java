// Shared file — included via //SOURCES in Kernel.java. No JBang header.

import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LogInterceptor — Consolidated, asynchronous log sink for the whole system.
 *
 * Consumes {@code log.{level}} events published by any plugin (via the Log API) and
 * appends them to a single {@code logs/system.log}, giving one chronological view
 * across every process. Logs are NOT broadcast (intercept returns true = consumed).
 *
 * Never blocks the kernel routing thread: intercept() only enqueues a formatted line
 * onto a bounded queue; a daemon writer thread does all file IO. If the queue fills
 * (a runaway log storm), new lines are dropped and counted — logging must never stall
 * the bus. Opt-in: only active when named on the Kernel CLI.
 */
class LogInterceptor implements EventInterceptor, InterceptorLifecycle {

    private static final String PREFIX = "log.";
    private static final int    QUEUE_CAPACITY = 8_192;
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final Path logFile;
    private final BlockingQueue<String> queue   = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong             dropped = new AtomicLong();
    private volatile boolean running = true;

    LogInterceptor(KernelBus bus, KernelConfig config) {
        Path dir = config.logsOverride() != null
                ? config.logsOverride()
                : config.scanRoot().resolve("logs");
        this.logFile = dir.resolve("system.log");
    }

    @Override public void onStart() {
        try { Files.createDirectories(logFile.getParent()); } catch (IOException ignored) {}
        Thread.ofPlatform().name("log-writer").daemon(true).start(this::drain);
        System.out.println("[LOG-INTERCEPTOR] consolidating to " + logFile);
    }

    @Override public Set<String> subscribes() {
        return Set.of("log.debug", "log.info", "log.warn", "log.error");
    }

    @Override public boolean handles(String type) { return type.startsWith(PREFIX); }

    @Override
    public boolean intercept(KernelEvent event, String json) {
        try {
            JsonNode p   = KernelEvent.MAPPER.readTree(event.payload());
            String level = p.path("level").asText("INFO");
            String src   = p.path("source").asText(event.source());
            String msg   = p.path("message").asText("");
            String line  = "[" + LocalDateTime.now().format(TS) + "] [" + level + "] [" + src + "] " + msg;
            if (!queue.offer(line)) dropped.incrementAndGet();
        } catch (Exception ignored) {
            // Malformed log event — ignore rather than disrupt routing.
        }
        return true; // consume — logs are not broadcast to other plugins
    }

    private void drain() {
        try (BufferedWriter w = Files.newBufferedWriter(logFile,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            while (running || !queue.isEmpty()) {
                String line = queue.poll(1, TimeUnit.SECONDS);
                if (line != null) { w.write(line); w.newLine(); w.flush(); }
                long d = dropped.getAndSet(0);
                if (d > 0) { w.write("[LOG] dropped " + d + " line(s) — queue full"); w.newLine(); w.flush(); }
            }
        } catch (Exception e) {
            System.err.println("[LOG-INTERCEPTOR] writer stopped: " + e.getMessage());
        }
    }
}
