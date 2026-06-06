///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * InspectSchemas — project-wide static inspector for Kiwi plugins.
 *
 * Recursively discovers all `plugin.json` configurations in the codebase,
 * parses their parameters, and generates two comprehensive cross-reference tables:
 *   1. Capability Registry Table (what tools/agents offer, their descriptions, and parameters)
 *   2. Pub/Sub Event Matrix (which components publish an event, and who consumes it)
 *
 * Usage: jbang InspectSchemas.java
 */
public class InspectSchemas {

    static final ObjectMapper MAPPER = new ObjectMapper();

    // ANSI Colors
    static final String RESET = "\u001B[0m";
    static final String BOLD  = "\u001B[1m";
    static final String CYAN  = "\u001B[36m";
    static final String GREEN = "\u001B[32m";
    static final String YELLOW = "\u001B[33m";
    static final String MAGENTA = "\u001B[35m";
    static final String GRAY  = "\u001B[90m";

    record CapabilityInfo(
        String name,
        String description,
        String triggerEvent,
        String providerId,
        String providerType,
        String inputParams
    ) {}

    record PluginMetadata(
        String id,
        String type,
        String description,
        List<CapabilityInfo> capabilities,
        List<String> subscribes,
        List<String> publishes
    ) {}

    public static void main(String[] args) throws Exception {
        Path root = projectRoot();
        System.out.println(BOLD + CYAN + "=== KIWI PLUGIN & SCHEMA SYSTEM CRAWLER ===" + RESET);
        System.out.println(GRAY + "Scanning root directory: " + root.toAbsolutePath() + RESET);

        List<PluginMetadata> plugins = new ArrayList<>();

        Files.walk(root)
            .filter(p -> p.getFileName().toString().equals("plugin.json"))
            .filter(p -> !p.startsWith(root.resolve("kernel"))) // ignore kernel
            .forEach(p -> {
                try {
                    JsonNode json = MAPPER.readTree(p.toFile());
                    String id = json.path("id").asText("?");
                    String type = json.path("type").asText("unknown");
                    String desc = json.path("description").asText("");

                    // Subscribes
                    List<String> subscribes = new ArrayList<>();
                    if (json.has("subscribes") && json.get("subscribes").isArray()) {
                        json.get("subscribes").forEach(s -> subscribes.add(s.asText()));
                    }
                    if (json.has("wildcardSubscribes") && json.get("wildcardSubscribes").isArray()) {
                        json.get("wildcardSubscribes").forEach(s -> subscribes.add(s.asText()));
                    }

                    // Publishes
                    List<String> publishes = new ArrayList<>();
                    if (json.has("publishes") && json.get("publishes").isArray()) {
                        json.get("publishes").forEach(p2 -> publishes.add(p2.asText()));
                    }

                    // Capabilities
                    List<CapabilityInfo> capabilities = new ArrayList<>();
                    if (json.has("capabilities") && json.get("capabilities").isArray()) {
                        for (JsonNode cap : json.get("capabilities")) {
                            String name = cap.path("name").asText("");
                            String cDesc = cap.path("description").asText("");
                            String trig = cap.has("triggerEvent") ? cap.get("triggerEvent").asText() : "Direct (Agent)";
                            
                            // Input Params extract
                            String params = "None";
                            JsonNode schema = cap.path("inputSchema");
                            if (!schema.isMissingNode() && schema.has("properties")) {
                                JsonNode props = schema.get("properties");
                                List<String> propList = new ArrayList<>();
                                Iterator<String> fields = props.fieldNames();
                                while (fields.hasNext()) {
                                    String field = fields.next();
                                    String fType = props.get(field).path("type").asText("string");
                                    propList.add(field + " (" + fType + ")");
                                }
                                if (!propList.isEmpty()) {
                                    params = String.join(", ", propList);
                                }
                            }
                            capabilities.add(new CapabilityInfo(name, cDesc, trig, id, type, params));
                        }
                    }

                    plugins.add(new PluginMetadata(id, type, desc, capabilities, subscribes, publishes));
                } catch (Exception e) {
                    System.err.println(YELLOW + "WARNING: Failed to parse plugin.json at: " + p + " (" + e.getMessage() + ")" + RESET);
                }
            });

        // Sort plugins by ID
        plugins.sort(Comparator.comparing(PluginMetadata::id));

        System.out.println(GREEN + "✓ Found and successfully parsed " + plugins.size() + " active plugins." + RESET + "\n");

        // 1. CAPABILITIES REGISTRY TABLE
        printCapabilitiesTable(plugins);
        System.out.println();

        // 2. EVENT PUB/SUB FLOW MATRIX
        printEventFlowMatrix(plugins);

        System.out.println(BOLD + CYAN + "==========================================" + RESET);
    }

    private static void printCapabilitiesTable(List<PluginMetadata> plugins) {
        System.out.println(BOLD + CYAN + "1. CAPABILITY REGISTRY & SCHEMA TABLE" + RESET);
        System.out.println(BOLD + "------------------------------------------------------------------------------------------------------------------------------------" + RESET);
        System.out.printf(BOLD + "%-30s | %-16s | %-12s | %-45s | %-20s%n" + RESET, 
            "Capability Name", "Provider Plugin", "Type", "Input Parameters / Schema", "Trigger Event");
        System.out.println("------------------------------------------------------------------------------------------------------------------------------------");

        List<CapabilityInfo> allCaps = plugins.stream()
            .flatMap(p -> p.capabilities().stream())
            .sorted(Comparator.comparing(CapabilityInfo::name))
            .collect(Collectors.toList());

        for (CapabilityInfo cap : allCaps) {
            String typeColor = cap.providerType().equals("agent") ? MAGENTA : GREEN;
            System.out.printf("%-30s | %-16s | " + typeColor + "%-12s" + RESET + " | %-45s | " + GRAY + "%-20s" + RESET + "%n",
                cap.name(),
                cap.providerId(),
                cap.providerType().toUpperCase(),
                truncate(cap.inputParams(), 45),
                truncate(cap.triggerEvent(), 20)
            );
        }
        System.out.println("------------------------------------------------------------------------------------------------------------------------------------");
    }

    private static void printEventFlowMatrix(List<PluginMetadata> plugins) {
        System.out.println(BOLD + CYAN + "2. EVENT PUB/SUB CROSS-REFERENCE MATRIX" + RESET);
        System.out.println(BOLD + "----------------------------------------------------------------------------------------------------------------------" + RESET);
        System.out.printf(BOLD + "%-40s | %-32s | %-32s%n" + RESET, 
            "Event Type (UDS Message)", "Publishers (Who Sends It)", "Subscribers (Who Listens To It)");
        System.out.println("----------------------------------------------------------------------------------------------------------------------");

        // Collect all unique event types
        Set<String> allEvents = new TreeSet<>();
        for (PluginMetadata p : plugins) {
            allEvents.addAll(p.subscribes());
            allEvents.addAll(p.publishes());
        }

        for (String event : allEvents) {
            // Find publishers
            List<String> pubs = plugins.stream()
                .filter(p -> p.publishes().contains(event))
                .map(PluginMetadata::id)
                .sorted()
                .collect(Collectors.toList());

            // Find subscribers
            List<String> subs = plugins.stream()
                .filter(p -> p.subscribes().stream().anyMatch(sub -> matchesSubscription(sub, event)))
                .map(PluginMetadata::id)
                .sorted()
                .collect(Collectors.toList());

            String pubStr = pubs.isEmpty() ? GRAY + "[External / Runtime]" + RESET : String.join(", ", pubs);
            String subStr = subs.isEmpty() ? YELLOW + "[None (Dropped)]" + RESET : String.join(", ", subs);

            System.out.printf("%-40s | %-32s | %-32s%n",
                event,
                truncate(pubStr, 32),
                truncate(subStr, 32)
            );
        }
        System.out.println("----------------------------------------------------------------------------------------------------------------------");
    }

    /**
     * Checks if a subscription pattern (supports standard UDS * wildcard at the end) matches an event type.
     */
    private static boolean matchesSubscription(String pattern, String eventType) {
        if (pattern.equals(eventType)) return true;
        if (pattern.endsWith("*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return eventType.startsWith(prefix);
        }
        return false;
    }

    private static String truncate(String text, int length) {
        if (text == null) return "";
        if (text.length() <= length) return text;
        return text.substring(0, length - 3) + "...";
    }

    private static Path projectRoot() {
        Path dir = Path.of(System.getProperty("user.dir"));
        if (dir.getFileName().toString().equals("dev")) return dir.getParent();
        return dir;
    }
}
