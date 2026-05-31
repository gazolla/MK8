// Shared file — included via //SOURCES in each plugin (no JBang header).

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

// ── UDS boot (plugin-side only) ───────────────────────────────────────────────

class PluginBoot {

    @FunctionalInterface
    interface PluginLogic {
        void run(InputStream in, OutputStream out) throws Exception;
    }

    static void connectAndRun(String socketPath, PluginConfig config, PluginLogic logic) {
        var addr = UnixDomainSocketAddress.of(Path.of(socketPath));
        try (var ch = SocketChannel.open(addr)) {
            var out = Channels.newOutputStream(ch);
            var in  = Channels.newInputStream(ch);

            System.out.println("[" + config.id().toUpperCase() + "] Connected to kernel.");

            long pid  = ProcessHandle.current().pid();
            var  node = (ObjectNode) config.raw().deepCopy();
            node.put("pid", pid);

            Event.writeFrame(out, Event.MAPPER.writeValueAsString(
                    Event.of("plugin.register", Event.MAPPER.writeValueAsString(node), config.id())));

            Event.writeFrame(out, Event.MAPPER.writeValueAsString(
                    Event.of("plugin.ready", Event.MAPPER.writeValueAsString(
                            Map.of("id", config.id(), "pid", pid)), config.id())));

            logic.run(in, out);

        } catch (Exception e) {
            System.err.println("[" + config.id().toUpperCase() + "] Connection error: " + e.getMessage());
        }
    }
}

// ── BasePlugin ────────────────────────────────────────────────────────────────

public class BasePlugin {

    @FunctionalInterface
    public interface EventHandler {
        void handle(String json, OutputStream out) throws Exception;
    }

    public static void run(String configPath, String socketPath, EventHandler handler) throws Exception {
        var config = PluginConfig.load(configPath);
        PluginBoot.connectAndRun(socketPath, config, (in, out) -> {
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

    public static void runSync(String configPath, String socketPath, EventHandler handler) throws Exception {
        var config = PluginConfig.load(configPath);
        PluginBoot.connectAndRun(socketPath, config, (in, out) -> {
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

    public static void registerCapabilities(PluginConfig config, OutputStream out) throws Exception {
        for (JsonNode cap : config.capabilities()) {
            var reg = new java.util.LinkedHashMap<String, Object>();
            reg.put("name",      cap.path("name").asText());
            reg.put("pluginId",  config.id());
            reg.put("version",   cap.path("version").asText("1.0.0"));
            reg.put("exclusive", cap.path("exclusive").asBoolean(false));
            reg.put("bidWeight", cap.path("bidWeight").asDouble(1.0));
            String trigger = cap.path("triggerEvent").asText(null);
            if (trigger != null && !trigger.isBlank()) reg.put("triggerEvent", trigger);
            JsonNode tags = cap.path("tags");
            if (tags.isArray() && !tags.isEmpty())     reg.put("tags", tags);
            Event.writeFrame(out, Event.MAPPER.writeValueAsString(
                    Event.of("capability.register", Event.MAPPER.writeValueAsString(reg), config.id())));
        }
    }

    public static void handleBidAuto(PluginConfig config, Event event, OutputStream out) {
        try {
            JsonNode req   = Event.MAPPER.readTree(event.payload());
            String capName = req.path("capabilityName").asText("");
            String corrId  = req.path("correlationId").asText(event.correlationId());
            config.capabilities().stream()
                  .filter(c -> c.path("name").asText("").equals(capName))
                  .findFirst()
                  .ifPresent(cap -> {
                      try {
                          String bid = Event.MAPPER.writeValueAsString(Map.of(
                              "agentId",       config.id(),
                              "score",         cap.path("bidWeight").asDouble(1.0),
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

    public static void publishLog(String level, String message, String source, OutputStream out) {
        try {
            String payload = Event.MAPPER.writeValueAsString(
                    Map.of("level", level, "message", message, "source", source));
            publishSafe(Event.of("log." + level, payload, source), out);
        } catch (Exception ignored) {}
    }
}
