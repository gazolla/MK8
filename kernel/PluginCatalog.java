// Shared file — included via //SOURCES in Kernel.java. No JBang header.

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

/**
 * PluginCatalog — single source of truth for all plugin.json files on disk.
 *
 * Scanned once at boot in a virtual thread (non-blocking).
 * Refreshed synchronously on plugin.installed (catalog is small, re-scan is fast).
 *
 * Lookup by capability name, trigger event, or plugin id.
 * Excludes kernel/ directory from scan (no plugin.json there).
 */
class PluginCatalog {

    /**
     * One entry per capability declaration (a plugin with N capabilities → N entries).
     * launchCommand is the full array from launch.command, not just the first element.
     */
    record CatalogEntry(
            String   pluginId,
            String   pluginDir,          // absolute path of the plugin directory
            String   capabilityName,
            String   triggerEvent,       // null for agents (no triggerEvent field)
            boolean  onDemand,           // lifecycle.mode == "on-demand"
            boolean  persistent,         // lifecycle.mode == "persistent"
            double   bidWeight,
            int      idleTimeoutSeconds,
            String[] launchCommand       // full launch.command array (e.g. ["jbang","Tool.java"])
    ) {}

    private final Path root;
    private final ConcurrentHashMap<String, CatalogEntry>       byCapName  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CatalogEntry>       byTrigger  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<CatalogEntry>> byPluginId = new ConcurrentHashMap<>();
    private volatile boolean    ready = false;
    private final CountDownLatch latch = new CountDownLatch(1);

    /** Initializes structures. Does NOT perform I/O. */
    PluginCatalog(Path root) {
        this.root = root;
    }

    // ── Scan ──────────────────────────────────────────────────────────────────

    /**
     * Walks the project root, reads every plugin.json, populates lookup maps.
     * Should be called in a virtual thread (catalog::scan).
     * Can be called again for refresh — clears maps first.
     */
    void scan() {
        byCapName.clear();
        byTrigger.clear();
        byPluginId.clear();

        int pluginCount = 0;
        int capCount    = 0;

        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> pluginFiles = walk
                    .filter(p -> p.getFileName().toString().equals("plugin.json"))
                    .filter(p -> !p.startsWith(root.resolve("kernel"))) // exclude kernel/ dir
                    .sorted()
                    .toList();

            for (Path pf : pluginFiles) {
                try {
                    JsonNode json    = Event.MAPPER.readTree(pf.toFile());
                    String pluginId  = json.path("id").asText("");
                    String type      = json.path("type").asText("");
                    if (pluginId.isBlank()) continue;
                    // Only index tool/agent/system types
                    if (!"tool".equals(type) && !"agent".equals(type) && !"system".equals(type)) continue;

                    JsonNode caps = json.path("capabilities");
                    if (!caps.isArray() || caps.isEmpty()) continue;

                    String  lifecycleMode = json.path("lifecycle").path("mode").asText("persistent");
                    boolean onDemand      = "on-demand".equals(lifecycleMode);
                    boolean persistent    = "persistent".equals(lifecycleMode);
                    int     idleTimeout   = json.path("lifecycle").path("idleTimeoutSeconds").asInt(300);

                    // Read the full launch.command array (P5 correction: NOT just first element)
                    String[] launchCommand = null;
                    JsonNode cmdNode = json.path("launch").path("command");
                    if (cmdNode.isArray() && !cmdNode.isEmpty()) {
                        launchCommand = new String[cmdNode.size()];
                        for (int i = 0; i < cmdNode.size(); i++) launchCommand[i] = cmdNode.get(i).asText();
                    }

                    String pluginDir = pf.getParent().toAbsolutePath().toString();
                    pluginCount++;

                    for (JsonNode cap : caps) {
                        String capName = cap.path("name").asText("");
                        if (capName.isBlank()) continue;

                        // triggerEvent is null for agents (field absent in JSON → asText(null) returns null)
                        String triggerEvent = cap.path("triggerEvent").asText(null);
                        if (triggerEvent != null && triggerEvent.isBlank()) triggerEvent = null;

                        double bidWeight = cap.path("bidWeight").asDouble(1.0);

                        CatalogEntry entry = new CatalogEntry(
                                pluginId, pluginDir, capName, triggerEvent,
                                onDemand, persistent, bidWeight, idleTimeout,
                                launchCommand
                        );

                        byCapName.put(capName, entry);
                        if (triggerEvent != null) byTrigger.put(triggerEvent, entry);
                        byPluginId.computeIfAbsent(pluginId, k -> new CopyOnWriteArrayList<>()).add(entry);
                        capCount++;
                    }
                } catch (Exception e) {
                    System.err.println("[CATALOG] Error scanning " + pf + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("[CATALOG] Walk error: " + e.getMessage());
        }

        ready = true;
        latch.countDown(); // no-op if already 0 (subsequent refresh calls)
        System.out.println("[CATALOG] Scan complete: " + capCount + " capabilities across "
                + pluginCount + " plugins.");
    }

    /**
     * Synchronous refresh — clears maps and re-scans inline.
     * Used on plugin.installed. Catalog is small so re-scan is fast.
     */
    void refresh() {
        System.out.println("[CATALOG] Refreshing...");
        scan(); // clears maps at start of scan(); latch already at 0
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /** Blocks until the initial scan completes or timeout elapses. */
    void awaitReady(long timeoutMs) {
        if (ready) return; // fast path — no locking
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    CatalogEntry getByCapName(String capName) {
        return byCapName.get(capName);
    }

    CatalogEntry getByTrigger(String triggerEvent) {
        return byTrigger.get(triggerEvent);
    }

    List<CatalogEntry> getByPluginId(String pluginId) {
        return byPluginId.getOrDefault(pluginId, List.of());
    }

    Collection<CatalogEntry> allEntries() {
        return Collections.unmodifiableCollection(byCapName.values());
    }

    boolean isReady() {
        return ready;
    }
}
