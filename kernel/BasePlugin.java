// Shared file — included via //SOURCES in each plugin (no JBang header).
// Eliminates the repeated connect → event-loop boilerplate.

import com.fasterxml.jackson.databind.JsonNode;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.Executors;

public class BasePlugin {

    @FunctionalInterface
    public interface EventHandler {
        void handle(String json, OutputStream out) throws Exception;
    }

    /**
     * Loads plugin.json, connects to the kernel, registers declared capabilities,
     * then dispatches each received frame to handler in a separate virtual thread.
     */
    public static void run(String configPath, String socketPath, EventHandler handler) throws Exception {
        var config = Event.loadConfig(configPath);
        Event.connectAndRun(socketPath, config, (in, out) -> {
            registerCapabilities(config, out);
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            String json;
            while ((json = Event.readFrame(in)) != null) {
                final String j = json;
                executor.submit(() -> {
                    try {
                        Event ev = null;
                        try {
                            ev = Event.MAPPER.readValue(j, Event.class);
                            if (ev.traceId() != null) Event.CURRENT_TRACE_ID.set(ev.traceId());
                            if (ev.spanId()  != null) Event.CURRENT_SPAN_ID.set(ev.spanId());
                        } catch (Exception ignored) {}
                        if (ev != null && "capability.bid.request".equals(ev.type())) {
                            handleBidAuto(config, ev, out);
                            return;
                        }
                        handler.handle(j, out);
                    } catch (Exception e) {
                        System.err.println("[" + config.id().toUpperCase() + "] Handler error: " + e.getMessage());
                    } finally {
                        Event.CURRENT_TRACE_ID.remove();
                        Event.CURRENT_SPAN_ID.remove();
                    }
                });
            }
        });
    }

    /**
     * Same as run() but processes frames one at a time.
     * Use when ordering matters (e.g. LoggerPlugin writing to file).
     */
    public static void runSync(String configPath, String socketPath, EventHandler handler) throws Exception {
        var config = Event.loadConfig(configPath);
        Event.connectAndRun(socketPath, config, (in, out) -> {
            registerCapabilities(config, out);
            String json;
            while ((json = Event.readFrame(in)) != null) {
                try {
                    Event ev = null;
                    try {
                        ev = Event.MAPPER.readValue(json, Event.class);
                        if (ev.traceId() != null) Event.CURRENT_TRACE_ID.set(ev.traceId());
                        if (ev.spanId()  != null) Event.CURRENT_SPAN_ID.set(ev.spanId());
                    } catch (Exception ignored) {}
                    if (ev != null && "capability.bid.request".equals(ev.type())) {
                        handleBidAuto(config, ev, out);
                        continue;
                    }
                    handler.handle(json, out);
                } catch (Exception e) {
                    System.err.println("[" + config.id().toUpperCase() + "] Handler error: " + e.getMessage());
                } finally {
                    Event.CURRENT_TRACE_ID.remove();
                    Event.CURRENT_SPAN_ID.remove();
                }
            }
        });
    }

    /** Sends capability.register for each capability declared in config. */
    public static void registerCapabilities(Event.PluginConfig config, OutputStream out) throws Exception {
        for (Event.CapabilityDecl cap : config.capabilitiesOrEmpty()) {
            var reg = new java.util.LinkedHashMap<String, Object>();
            reg.put("name",      cap.name());
            reg.put("pluginId",  config.id());
            reg.put("version",   cap.version() != null ? cap.version() : "1.0.0");
            reg.put("exclusive", cap.exclusiveOrDefault());
            reg.put("bidWeight", cap.bidWeightOrDefault());
            if (cap.triggerEvent() != null) reg.put("triggerEvent", cap.triggerEvent());
            if (cap.tags() != null)         reg.put("tags", cap.tags());
            Event.writeFrame(out, Event.MAPPER.writeValueAsString(
                    Event.of("capability.register", Event.MAPPER.writeValueAsString(reg), config.id())));
        }
    }

    /** Auto-responds to capability.bid.request using capabilities declared in plugin.json. */
    public static void handleBidAuto(Event.PluginConfig config, Event event, OutputStream out) {
        try {
            JsonNode req   = Event.MAPPER.readTree(event.payload());
            String capName = req.has("capabilityName") ? req.get("capabilityName").asText() : "";
            String corrId  = req.has("correlationId")  ? req.get("correlationId").asText()
                                                        : event.correlationId();
            config.capabilitiesOrEmpty().stream()
                  .filter(c -> c.name().equals(capName))
                  .findFirst()
                  .ifPresent(cap -> {
                      try {
                          String bid = Event.MAPPER.writeValueAsString(Map.of(
                              "agentId",       config.id(),
                              "score",         cap.bidWeightOrDefault(),
                              "load",          0.0,
                              "correlationId", corrId != null ? corrId : ""));
                          publish(Event.of("capability.bid.response", bid, config.id()), out);
                      } catch (Exception ignored) {}
                  });
        } catch (Exception ignored) {}
    }

    // ── Publish helpers ───────────────────────────────────────────────────────

    public static void publish(Event e, OutputStream out) throws Exception {
        synchronized (out) {
            Event.writeFrame(out, Event.MAPPER.writeValueAsString(e));
        }
    }

    public static void publishSafe(Event e, OutputStream out) {
        try { publish(e, out); } catch (Exception ignored) {}
    }

    /**
     * Publishes a log.{level} event to the Logger plugin.
     * Levels: debug, info, warn, error
     */
    public static void publishLog(String level, String message, String source, OutputStream out) {
        try {
            String payload = Event.MAPPER.writeValueAsString(
                    Map.of("level", level, "message", message, "source", source));
            publishSafe(Event.of("log." + level, payload, source), out);
        } catch (Exception ignored) {}
    }
}
