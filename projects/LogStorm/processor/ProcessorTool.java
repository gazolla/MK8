///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../../kernel/KernelEvent.java
//SOURCES ../../../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../../../kernel/interceptors/plugin/PluginBase.java

import com.fasterxml.jackson.databind.JsonNode;
import java.io.OutputStream;
import java.util.*;

/**
 * ProcessorTool — Shared on-demand tool used by processor-fast and processor-thorough.
 *
 * Both instances use this same file via launch.command: ["jbang", "../processor/ProcessorTool.java"].
 * Each reads its own plugin.json from its working directory (processor-fast/ or processor-thorough/),
 * which configures the id, bidWeight, and processorConfig.delayMs independently.
 *
 * Processes a log entry: validates, simulates work (delayMs), enriches with metadata,
 * then publishes capability.result back to the caller and a storm.metric broadcast
 * for the Dashboard.
 */
public class ProcessorTool {

    final String[] HOSTS    = { "node-01", "node-02", "node-03", "node-04" };
    final String[] REGIONS  = { "us-east", "eu-west", "ap-south" };

    final PluginConfig config;
    final int          delayMs;
    final Random       rng = new Random();

    ProcessorTool() throws Exception {
        config  = PluginConfig.load("plugin.json");
        delayMs = config.raw().path("processorConfig").path("delayMs").asInt(10);
    }

    public static void main(String[] args) throws Exception {
        KernelEvent.initLogging();
        new ProcessorTool().start();
    }

    void start() throws Exception {
        System.out.println("[" + id() + "] Starting (on-demand, delayMs=" + delayMs + ")...");
        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, this::handle);
    }

    // ── Event handling ────────────────────────────────────────────────────────

    void handle(String json, OutputStream out) throws Exception {
        KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);
        if (!"capability.tool.log.process".equals(event.type())) return;

        JsonNode p       = KernelEvent.MAPPER.readTree(event.payload());
        String   service = p.path("service").asText("unknown");
        String   level   = p.path("level").asText("INFO");
        String   message = p.path("message").asText("");

        process(event, service, level, message, out);
    }

    void process(KernelEvent event, String service, String level,
                 String message, OutputStream out) throws Exception {

        // Simulate variable processing time
        Thread.sleep(delayMs + rng.nextInt(Math.max(1, delayMs)));

        String result = KernelEvent.MAPPER.writeValueAsString(Map.of(
                "service",   service,
                "level",     level,
                "message",   message,
                "processor", config.id(),
                "host",      HOSTS[rng.nextInt(HOSTS.length)],
                "region",    REGIONS[rng.nextInt(REGIONS.length)]
        ));

        // Reply to caller via correlationId routing
        PluginBase.publish(KernelEvent.withCorrelation(
                "capability.result",
                KernelEvent.MAPPER.writeValueAsString(Map.of("result", result)),
                config.id(), event.correlationId(), event.sessionId()),
                out);

        // Broadcast metric for Dashboard
        PluginBase.publishSafe(KernelEvent.of(
                "storm.metric",
                KernelEvent.MAPPER.writeValueAsString(Map.of(
                        "level",     level,
                        "processor", config.id(),
                        "service",   service,
                        "message",   message
                )),
                config.id()),
                out);

        System.out.println("[" + id() + "] " + level + " " + service + " — " + message);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    String id() { return config.id().toUpperCase(); }
}
