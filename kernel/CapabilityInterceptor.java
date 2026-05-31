// Shared file — included via //SOURCES in Kernel.java. No JBang header.

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
/**
 * CapabilityInterceptor — Live capability registry, routing broker, and auction engine.
 *
 * Occupies position 1 in the Kernel's interceptor chain (executing after idempotency).
 * It registers capabilities dynamically from plugins, maintaining a mapping of live
 * providers. It routes capability invocations using a three-tier fallback scheme:
 * 1. Single live provider: Direct peer-to-peer delivery or tool trigger routing.
 * 2. Multiple live providers: Holds a 500ms auction using bidWeights and loads.
 * 3. No live provider: Falls back to the PluginRuntime catalog to either queue
 *    persistent plugins or spawn on-demand plugins and drain the queue on connect.
 *
 * It also handles built-in capability requests such as "system.capability.list"
 * and "system.plugin.list" locally in-process without routing to external plugins.
 * Cleans up registrations automatically if "system.plugin.died" is received.
 */
class CapabilityInterceptor implements EventInterceptor {

    // ── Built-in capability handler (checked-exception-safe functional interface) ──

    @FunctionalInterface
    interface BuiltInHandler {
        String handle(KernelEvent event) throws Exception;
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    record Registration(String pluginId, String triggerEvent, double bidWeight) {}

    record BidEntry(String agentId, double score, double load) {
        double effective() { return score * (1.0 - load); }
    }

    static class AuctionContext {
        final KernelEvent              originalEvent;
        final String             capabilityName;
        final List<Registration> candidates;
        final List<BidEntry>     bids     = Collections.synchronizedList(new ArrayList<>());
        private final AtomicBoolean resolved = new AtomicBoolean(false);

        AuctionContext(KernelEvent originalEvent, String capabilityName, List<Registration> candidates) {
            this.originalEvent  = originalEvent;
            this.capabilityName = capabilityName;
            this.candidates     = List.copyOf(candidates);
        }

        /** Returns true only on the first call — lock-free single-resolve guard. */
        boolean tryResolve()  { return resolved.compareAndSet(false, true); }
        boolean isResolved()  { return resolved.get(); }

        /** Winner = highest effective score; fallback to first candidate. */
        Optional<Registration> winner() {
            return bids.stream()
                    .max(Comparator.comparingDouble(BidEntry::effective))
                    .map(BidEntry::agentId)
                    .flatMap(id -> candidates.stream()
                            .filter(r -> r.pluginId().equals(id))
                            .findFirst());
        }
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    private final KernelBus                       bus;
    private final Map<String, BuiltInHandler>     builtins;       // registered at construction
    private final Map<String, List<Registration>> registrations  = new ConcurrentHashMap<>();
    private final Map<String, List<KernelEvent>>        pendingInvokes = new ConcurrentHashMap<>();
    private final Map<String, AuctionContext>      auctions       = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(1,
                    Thread.ofVirtual().name("capidx-sched-", 0).factory());

    /** Set once at boot by Kernel — avoids circular dependency at construction. */
    private volatile PluginRuntime runtime;

    // ── Construction ──────────────────────────────────────────────────────────

    CapabilityInterceptor(KernelBus bus) {
        this.bus = bus;
        // Built-in capability names handled in-process. Add new ones here without
        // touching handleInvoke.
        this.builtins = Map.of(
            "system.capability.list", e -> buildCapabilityList(),
            "system.plugin.list",     e -> runtime != null ? runtime.listPlugins() : "[]"
        );
    }

    void setRuntime(PluginRuntime r) { this.runtime = r; }

    // ── EventInterceptor ──────────────────────────────────────────────────────

    @Override
    public boolean intercept(KernelEvent event, String json) throws Exception {
        return switch (event.type()) {
            case "capability.invoke"            -> { handleInvoke(event);       yield true;  }
            case "capability.register"          -> { handleRegister(event);     yield false; }
            case "capability.unregister"        -> { handleUnregister(event);   yield true;  }
            case "capability.bid.response"      -> { handleBidResponse(event);  yield true;  }
            case "capability.query"             -> { handleQuery(event);        yield true;  }
            case "system.plugin.died",
                 "system.plugin.stopped"       -> { handlePluginDied(event);   yield false; }
            case "plugin.installed"             -> { handlePluginInstalled();   yield false; }
            default                             ->                                    false;
        };
    }

    // ── Registration ──────────────────────────────────────────────────────────

    void handleRegister(KernelEvent event) throws Exception {
        JsonNode p        = KernelEvent.MAPPER.readTree(event.payload());
        String   name     = p.path("name").asText(null);
        String   pluginId = p.path("pluginId").asText(null);
        if (name == null || pluginId == null) return;

        String trigger   = p.path("triggerEvent").asText(null);
        if (trigger != null && trigger.isBlank()) trigger = null;
        double bidWeight = p.path("bidWeight").asDouble(1.0);

        registrations.computeIfAbsent(name, k -> new CopyOnWriteArrayList<>())
                .add(new Registration(pluginId, trigger, bidWeight));

        System.out.println("[CAP-IDX] Registered: " + name + " → " + pluginId
                + (trigger != null ? " trigger=" + trigger : " (agent)") + " weight=" + bidWeight);

        // Drain any invokes queued while this capability was starting on-demand
        List<KernelEvent> pending = pendingInvokes.remove(name);
        if (pending != null && !pending.isEmpty()) {
            Registration reg = registrations.get(name).get(0);
            System.out.println("[CAP-IDX] Draining " + pending.size() + " queued invoke(s) for: " + name);
            for (KernelEvent e : pending) {
                try { routeToProvider(reg, e); } catch (Exception ignored) {}
            }
        }
    }

    void handleUnregister(KernelEvent event) throws Exception {
        JsonNode p        = KernelEvent.MAPPER.readTree(event.payload());
        String   name     = p.path("name").asText(null);
        String   pluginId = p.path("pluginId").asText(null);
        if (name == null || pluginId == null) return;
        registrations.getOrDefault(name, List.of()).removeIf(r -> r.pluginId().equals(pluginId));
        System.out.println("[CAP-IDX] Unregistered: " + name + " from " + pluginId);
    }

    // ── Invoke routing ────────────────────────────────────────────────────────

    void handleInvoke(KernelEvent event) throws Exception {
        String capName = parseCapabilityName(event);
        if (capName == null) { publishError(event, "missing capability name"); return; }

        if (runtime != null) runtime.trackUsage(capName);
        if (handleBuiltIn(capName, event)) return;

        List<Registration> providers = registrations.get(capName);
        System.out.println("[CAP-IDX] invoke " + capName + " from=" + event.source()
                + " corrId=" + event.correlationId()
                + " providers=" + (providers == null ? 0 : providers.size()));

        if (providers == null || providers.isEmpty()) {
            handleNoProvider(capName, event);
            return;
        }
        if (providers.size() == 1) routeToProvider(providers.get(0), event);
        else                       startAuction(capName, new ArrayList<>(providers), event);
    }

    private String parseCapabilityName(KernelEvent event) throws Exception {
        String name = KernelEvent.MAPPER.readTree(event.payload()).path("name").asText(null);
        return (name != null && !name.isBlank()) ? name : null;
    }

    private boolean handleBuiltIn(String capName, KernelEvent event) throws Exception {
        BuiltInHandler handler = builtins.get(capName);
        if (handler == null) return false;
        replyResult(event, handler.handle(event));
        return true;
    }

    private void handleNoProvider(String capName, KernelEvent event) throws Exception {
        if (runtime == null) { publishError(event, "no provider for: " + capName); return; }
        runtime.awaitReady(500);
        CatalogEntry entry = runtime.getByCapName(capName);

        if (entry == null) {
            publishError(event, "no provider for: " + capName);
            System.out.println("[CAP-IDX] No provider for: " + capName);
            return;
        }
        if (entry.triggerEvent() != null && entry.persistent()) {
            // Persistent plugin with triggerEvent: re-route. Covers boot-order miss cases.
            bus.route(forwardAs(event, entry.triggerEvent()));
            System.out.println("[CAP-IDX] Catalog route (persistent) → " + entry.triggerEvent());
        } else if (entry.onDemand()) {
            pendingInvokes.computeIfAbsent(capName, k -> new CopyOnWriteArrayList<>()).add(event);
            runtime.spawnOnDemand(capName);
            System.out.println("[CAP-IDX] Queued on-demand invoke for: " + capName);
        } else {
            publishError(event, "no live provider for: " + capName);
        }
    }

    // ── Auction ───────────────────────────────────────────────────────────────

    void startAuction(String capName, List<Registration> providers, KernelEvent invokeEvent) throws Exception {
        String auctionId = UUID.randomUUID().toString();
        auctions.put(auctionId, new AuctionContext(invokeEvent, capName, providers));

        String bidReq = KernelEvent.MAPPER.writeValueAsString(Map.of(
                "capabilityName", capName,
                "correlationId",  auctionId));
        bus.route(KernelEvent.withCorrelation("capability.bid.request", bidReq, "kernel",
                auctionId, invokeEvent.sessionId()));

        System.out.println("[CAP-IDX] Auction started for " + capName
                + " (" + providers.size() + " candidates) id=" + auctionId);
        scheduler.schedule(() -> resolveAuction(auctionId), 500, TimeUnit.MILLISECONDS);
    }

    void handleBidResponse(KernelEvent event) throws Exception {
        JsonNode p     = KernelEvent.MAPPER.readTree(event.payload());
        String   aucId = p.path("correlationId").asText(null);  // echoed by PluginBase.handleBidAuto
        if (aucId == null) return;

        AuctionContext ctx = auctions.get(aucId);
        if (ctx == null || ctx.isResolved()) return;

        String agentId = p.path("agentId").asText(null);
        double score   = p.path("score").asDouble(1.0);
        double load    = p.path("load").asDouble(0.0);
        if (agentId == null) return;
        ctx.bids.add(new BidEntry(agentId, score, load));

        System.out.println("[CAP-IDX] Bid from " + agentId + " score=" + score
                + " load=" + load + " effective=" + String.format("%.3f", score * (1.0 - load)));

        // Early resolve: all candidates have voted
        if (ctx.bids.size() >= ctx.candidates.size()) resolveAuction(aucId);
    }

    void resolveAuction(String auctionId) {
        AuctionContext ctx = auctions.remove(auctionId);
        if (ctx == null || !ctx.tryResolve()) return; // already resolved by early-resolve

        Registration winner = ctx.winner().orElseGet(() -> ctx.candidates.get(0));
        try {
            routeToProvider(winner, ctx.originalEvent);
            System.out.println("[CAP-IDX] Auction resolved → " + winner.pluginId());
        } catch (Exception e) {
            publishError(ctx.originalEvent, "routing failed: " + e.getMessage());
        }
    }

    // ── Routing helpers ───────────────────────────────────────────────────────

    void routeToProvider(Registration reg, KernelEvent invokeEvent) throws Exception {
        // Tools have a triggerEvent; agents are reached via direct message routing.
        String type = reg.triggerEvent() != null ? reg.triggerEvent()
                                                 : "message." + reg.pluginId();
        bus.route(forwardAs(invokeEvent, type));
        System.out.println("[CAP-IDX] Route → " + type);
    }

    /** Creates a new event preserving the full correlation context but with a new type. */
    private KernelEvent forwardAs(KernelEvent origin, String type) {
        return new KernelEvent(UUID.randomUUID().toString(), type,
                origin.payload(), LocalDateTime.now(), "kernel",
                origin.correlationId(), origin.sessionId(),
                origin.workflowId(), null, origin.traceId(), origin.spanId());
    }

    // ── system.capability.list ────────────────────────────────────────────────

    String buildCapabilityList() throws Exception {
        var caps = new ArrayList<Map<String, Object>>();
        var seen = new HashSet<String>();

        // Live registrations first — accurate runtime state
        registrations.forEach((name, providers) ->
            providers.forEach(reg -> {
                var entry = new LinkedHashMap<String, Object>();
                entry.put("capability", name);
                entry.put("provider",   reg.pluginId());
                entry.put("bidWeight",  reg.bidWeight());
                entry.put("live",       true);
                caps.add(entry);
                seen.add(name);
            }));

        // Catalog entries for capabilities not currently live
        Collection<CatalogEntry> catalogEntries = runtime != null ? runtime.allEntries() : List.of();
        for (CatalogEntry e : catalogEntries) {
            if (seen.contains(e.capabilityName())) continue;
            var entry = new LinkedHashMap<String, Object>();
            entry.put("capability", e.capabilityName());
            entry.put("provider",   e.pluginId());
            entry.put("bidWeight",  e.bidWeight());
            entry.put("live",       false);
            if (e.onDemand())             entry.put("onDemand",     true);
            if (e.triggerEvent() != null) entry.put("triggerEvent", e.triggerEvent());
            caps.add(entry);
        }
        return KernelEvent.MAPPER.writeValueAsString(caps);
    }

    // ── Plugin died/stopped ───────────────────────────────────────────────────

    void handlePluginDied(KernelEvent event) throws Exception {
        JsonNode p        = KernelEvent.MAPPER.readTree(event.payload());
        // payload uses "pluginId" for tools/system; fall back to "agentId" for legacy agents
        String   pluginId = p.path("pluginId").asText(p.path("agentId").asText(null));
        if (pluginId == null || pluginId.isBlank()) return;

        int removed = 0;
        for (List<Registration> list : registrations.values()) {
            int before = list.size();
            list.removeIf(r -> r.pluginId().equals(pluginId));
            removed += before - list.size();
        }
        if (removed > 0)
            System.out.println("[CAP-IDX] Cleaned " + removed + " registration(s) for dead plugin: " + pluginId);
    }

    // ── Plugin installed (hot-reload catalog) ─────────────────────────────────

    void handlePluginInstalled() {
        if (runtime != null) runtime.refresh();
        System.out.println("[CAP-IDX] Catalog refreshed after plugin.installed");
    }

    // ── capability.query ──────────────────────────────────────────────────────

    void handleQuery(KernelEvent event) throws Exception {
        String             capName = event.payload();
        List<Registration> ps      = registrations.getOrDefault(capName, List.of());
        List<String>       ids     = ps.stream().map(Registration::pluginId).toList();
        String result = KernelEvent.MAPPER.writeValueAsString(
                Map.of("capability", capName, "providers", ids));
        bus.route(KernelEvent.reply(event, "capability.query.result", result, "kernel"));
    }

    // ── Reply / Error helpers ─────────────────────────────────────────────────

    /** Wraps a JSON result string in {"result":…} and sends capability.result back to caller. */
    private void replyResult(KernelEvent event, String resultJson) throws Exception {
        String payload = KernelEvent.MAPPER.writeValueAsString(Map.of("result", resultJson));
        bus.route(KernelEvent.withCorrelation("capability.result", payload,
                "kernel", event.correlationId(), event.sessionId()));
    }

    void publishError(KernelEvent origin, String reason) {
        try {
            String payload = KernelEvent.MAPPER.writeValueAsString(Map.of("reason", reason));
            bus.route(KernelEvent.withCorrelation("capability.error", payload,
                    "kernel", origin.correlationId(), origin.sessionId()));
        } catch (Exception ignored) {}
    }
}
