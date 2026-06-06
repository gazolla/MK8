///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../../../kernel/KernelEvent.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginBase.java

import com.fasterxml.jackson.databind.JsonNode;
import java.io.OutputStream;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * DateTimeTool — returns the current date/time for a given IANA timezone.
 *
 * Trigger event: capability.tool.datetime.now
 * Input payload:  {"name": "tool.datetime.now", "input": {"timezone": "America/Sao_Paulo"}}
 * Result payload: {"result": "2024-01-15T10:30:00-03:00[America/Sao_Paulo]"}
 */
public class DateTimeTool {

    public static void main(String[] args) throws Exception {
        System.out.println("[TOOL-DATETIME] Starting...");
        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, DateTimeTool::handle);
    }

    static void handle(String json, OutputStream out) throws Exception {
        KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);

        if (!"capability.tool.datetime.now".equals(event.type())) return;

        try {
            JsonNode payload  = KernelEvent.MAPPER.readTree(event.payload());
            JsonNode input    = payload.has("input") ? payload.get("input") : KernelEvent.MAPPER.createObjectNode();
            String tzId       = input.has("timezone") ? input.get("timezone").asText("UTC") : "UTC";

            ZoneId zone;
            try {
                zone = ZoneId.of(tzId);
            } catch (Exception e) {
                zone = ZoneId.of("UTC");
                log("WARN: Unknown timezone '" + tzId + "', defaulting to UTC", out);
            }

            String now = ZonedDateTime.now(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            String result = KernelEvent.MAPPER.writeValueAsString(Map.of("result", now));

            PluginBase.publish(
                    KernelEvent.withCorrelation("capability.result", result, "tool-datetime",
                            event.correlationId(), event.sessionId()),
                    out);
            log("Responded: " + now, out);

        } catch (Exception e) {
            String errPayload = KernelEvent.MAPPER.writeValueAsString(Map.of("reason", e.getMessage()));
            PluginBase.publish(
                    KernelEvent.withCorrelation("capability.error", errPayload, "tool-datetime",
                            event.correlationId(), event.sessionId()),
                    out);
            log("ERROR: " + e.getMessage(), out);
        }
    }

    static void log(String msg, OutputStream out) {
        System.out.println("[TOOL-DATETIME] " + msg);
    }
}
