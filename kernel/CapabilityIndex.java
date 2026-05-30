// Shared file — included via //SOURCES in Kernel.java. No JBang header.

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * CapabilityIndex — live capability registry and auction engine.
 * Replaces the external CapabilityRegistry plugin (system/capability-registry/).
 *
 * Implements EventInterceptor — intercepts and routes capability.* events before broadcast.
 *
 * Interceptor return values (per spec table):
 *   capability.invoke       → true  (consumed: routed directly, no broadcast)
 *   capability.register     → false (side-effect: save registration; broadcast continues for telemetry)
 *   capability.unregister   → true  (only CapabilityIndex cares)
 *   capability.bid.response → true  (internal auction step)
 *   capability.query        → true  (direct response to requester)
 *   system.plugin.died/stopped → false (clean up registrations; broadcast continues)
 *   plugin.installed        → false (refresh catalog; broadcast continues for SkillLoader agents)
 *
 * PluginRuntime: CapabilityIndex interacts with PluginManager only through this interface,
 * covering both catalog lookups and lifecycle operations (spawn, track, list).
 */
class CapabilityIndex implements EventInterceptor {

    // ── Inner types ───────────────────────────────────────────────────────────

    record Registration(String pluginId, String triggerEvent, double bidWeight) {}

    record BidEntry(String agentId, double score, double load) {
        double effective() { return score * (1.0 - load); }
    }

    static class AuctionContext {
        final Event              originalEvent;
        final String             capabilityName;
        final List<Registration> candidates;
        final List<BidEntry>     bids      = Collections.synchronizedList(new ArrayList<>());
        volatile boolean         resolved  = false;

        AuctionContext(Event originalEvent, String capabilityName, List<Registration> candidates) {
            this.originalEvent  = originalEvent;
            this.capabilityName = capabilityName;
            this.candidates     = List.copyOf(candidates);
        }

        /** Thread-safe single-resolve guard. Returns true only once. */
        synchronized boolean tryResolve() {
            if (resolved) return false;
            resolved = true;
            return true;
        }

        /** Winner = agent with highest effective score; fallback to first candidate. */
        Optional<Registration> winner() {
            return bids.stream()
                    .max(Comparator.comparingDouble(BidEntry::effective))
                    .flatMap(best -> candidates.stream()
                            .filter(r -> r.pluginId().equals(best.agentId()))
                            .findFirst());
        }
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    private final KernelBus                              bus;
    // capName → live providers (plugins currently running and registered)
    private final Map<String, List<Registration>> registrations  = new ConcurrentHashMap<>();
    // capName → invoke events queued while on-demand plugin was starting
    private final Map<String, List<Event>>        pendingInvokes = new ConcurrentHashMap<>();
    // auctionId → in-flight auction context
    private final Map<String, AuctionContext>     auctions       = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(1,
                    Thread.ofVirtual().name("capidx-sched-", 0).factory());

    /**
     * PluginRuntime — set after construction to avoid circular dependency.
     * CapabilityIndex only knows the interface, never the concrete PluginManager.
     */
    private volatile PluginRuntime runtime;

    // ── Construction ──────────────────────────────────────────────────────────

    CapabilityIndex(KernelBus bus) {
        this.bus = bus;
    }

    /** Called from Kernel.start() after PluginManager is created. */
    void setRuntime(PluginRuntime r) {
        this.runtime = r;
    }

    // ── EventInterceptor ──────────────────────────────────────────────────────

    @Override
    public boolean intercept(Event event, String json) throws Exception {
        return switch (event.type()) {
            case "capability.invoke"         -> { handleInvoke(event);        yield true;  }
            case "capability.register"       -> { handleRegister(event);      yield false; }
            case "capability.unregister"     -> { handleUnregister(event);    yield true;  }
            case "capability.bid.response"   -> { handleBidResponse(event);   yield true;  }
            case "capability.query"          -> { handleQuery(event);         yield true;  }
            case "system.plugin.died",
                 "system.plugin.stopped"    -> { handlePluginDied(event);    yield false; }
            case "plugin.installed"          -> { handlePluginInstalled();    yield false; }
            default                          ->                                     false;
        };
    }

    // ── Registration ──────────────────────────────────────────────────────────

    void handleRegister(Event event) throws Exception {
        JsonNode p         = Event.MAPPER.readTree(event.payload());
        String   name      = p.get("name").asText();
        String   pluginId  = p.get("pluginId").asText();
        String   trigger   = p.has("triggerEvent") ? p.get("triggerEvent").asText(null) : null;
        if (trigger != null && trigger.isBlank()) trigger = null;
        double   bidWeight = p.path("bidWeight").asDouble(1.0);

        registrations.computeIfAbsent(name, k -> new CopyOnWriteArrayList<>())
                .add(new Registration(pluginId, trigger, bidWeight));

        System.out.println("[CAP-IDX] Registered: " + name + " → " + pluginId
                + (trigger != null ? " trigger=" + trigger : " (agent)") + " weight=" + bidWeight);

        // Drain any invokes queued while this capability was starting on-demand
        // (P3: pendingInvokes are silently held until provider registers; not published as errors)
        List<Event> pending = pendingInvokes.remove(name);
        if (pending != null && !pending.isEmpty()) {
            Registration reg = registrations.get(name).get(0);
            System.out.println("[CAP-IDX] Draining " + pending.size()
                    + " queued invoke(s) for: " + name);
            for (Event pendingEvent : pending) {
                try { routeToProvider(reg, pendingEvent); } catch (Exception ignored) {}
            }
        }
    }

    void handleUnregister(Event event) throws Exception {
        JsonNode p        = Event.MAPPER.readTree(event.payload());
        String   name     = p.get("name").asText();
        String   pluginId = p.get("pluginId").asText();
        // Remove the registration; pendingInvokes for this capability are NOT cleared —
        // they will be drained when/if another provider registers (P3).
        registrations.getOrDefault(name, List.of()).removeIf(r -> r.pluginId().equals(pluginId));
        System.out.println("[CAP-IDX] Unregistered: " + name + " from " + pluginId);
    }

    // ── Invoke routing ────────────────────────────────────────────────────────

    void handleInvoke(Event event) throws Exception {
        JsonNode p       = Event.MAPPER.readTree(event.payload());
        String   capName = p.has("name") ? p.get("name").asText() : null;

        // Notify runtime to update lastUsed — interceptor chain stops here (returns true).
        if (runtime != null && capName != null) {
            runtime.trackUsage(capName);
        }

        // Short-circuits: built-in capabilities handled in-process by the kernel.
        if ("system.capability.list".equals(capName)) {
            String resultPayload = Event.MAPPER.writeValueAsString(
                    Map.of("result", buildCapabilityList()));
            bus.route(Event.withCorrelation("capability.result", resultPayload,
                    "kernel", event.correlationId(), event.sessionId()));
            return;
        }
        if ("system.plugin.list".equals(capName)) {
            String result = runtime != null ? runtime.listPlugins() : "[]";
            bus.route(Event.withCorrelation("capability.result",
                    Event.MAPPER.writeValueAsString(Map.of("result", result)),
                    "kernel", event.correlationId(), event.sessionId()));
            return;
        }

        List<Registration> providers = capName != null ? registrations.get(capName) : null;

        System.out.println("[CAP-IDX] invoke " + capName + " from=" + event.source()
                + " corrId=" + event.correlationId()
                + " providers=" + (providers == null ? 0 : providers.size()));

        if (providers == null || providers.isEmpty()) {
            // No live provider — consult catalog
            if (runtime != null) runtime.awaitReady(500);
            PluginManager.CatalogEntry entry = (capName != null && runtime != null)
                    ? runtime.getByCapName(capName) : null;

            if (entry != null) {
                if (entry.triggerEvent() != null && entry.persistent()) {
                    // Persistent plugin with a triggerEvent: re-route via triggerEvent.
                    // This handles the boot-order miss case (which is rare in Option B,
                    // but kept for completeness per spec Fluxo D / CapabilityRegistry L265-274).
                    Event routed = new Event(UUID.randomUUID().toString(),
                            entry.triggerEvent(), event.payload(), LocalDateTime.now(),
                            "kernel", event.correlationId(), event.sessionId(),
                            event.workflowId(), null, event.traceId(), event.spanId());
                    bus.route(routed);
                    System.out.println("[CAP-IDX] Catalog route (persistent) → " + entry.triggerEvent());

                } else if (entry.onDemand()) {
                    // On-demand: queue invoke and call runtime directly (no bus event)
                    pendingInvokes.computeIfAbsent(capName, k -> new CopyOnWriteArrayList<>())
                            .add(event);
                    if (runtime != null) runtime.spawnOnDemand(capName);
                    System.out.println("[CAP-IDX] Queued on-demand invoke for: " + capName);

                } else {
                    publishError(event, "no live provider for: " + capName);
                }
            } else {
                publishError(event, "no provider for: " + capName);
                System.out.println("[CAP-IDX] No provider for: " + capName);
            }
            return;
        }

        if (providers.size() == 1) {
            routeToProvider(providers.get(0), event);
        } else {
            startAuction(capName, new ArrayList<>(providers), event);
        }
    }

    // ── Auction ───────────────────────────────────────────────────────────────

    void startAuction(String capName, List<Registration> providers, Event invokeEvent) throws Exception {
        String auctionId = UUID.randomUUID().toString();
        auctions.put(auctionId, new AuctionContext(invokeEvent, capName, providers));

        String bidReq = Event.MAPPER.writeValueAsString(Map.of(
                "capabilityName", capName,
                "correlationId",  auctionId));
        // broadcast bid.request — all registered providers receive it
        bus.route(Event.withCorrelation("capability.bid.request", bidReq, "kernel",
                auctionId, invokeEvent.sessionId()));

        System.out.println("[CAP-IDX] Auction started for " + capName
                + " (" + providers.size() + " candidates) id=" + auctionId);

        // Resolve after 500ms regardless of how many bids arrived
        scheduler.schedule(() -> resolveAuction(auctionId), 500, TimeUnit.MILLISECONDS);
    }

    void handleBidResponse(Event event) throws Exception {
        JsonNode p     = Event.MAPPER.readTree(event.payload());
        String   aucId = p.has("correlationId") ? p.get("correlationId").asText() : null;
        if (aucId == null) return;

        AuctionContext ctx = auctions.get(aucId);
        if (ctx == null || ctx.resolved) return;

        String agentId = p.get("agentId").asText();
        double score   = p.path("score").asDouble(1.0);
        double load    = p.path("load").asDouble(0.0);
        ctx.bids.add(new BidEntry(agentId, score, load));

        System.out.println("[CAP-IDX] Bid from " + agentId + " score=" + score
                + " load=" + load + " effective=" + String.format("%.3f", score * (1.0 - load)));

        // Early resolve: all candidates have voted
        if (ctx.bids.size() >= ctx.candidates.size()) {
            resolveAuction(aucId);
        }
    }

    void resolveAuction(String auctionId) {
        AuctionContext ctx = auctions.remove(auctionId);
        if (ctx == null || !ctx.tryResolve()) return; // already resolved by early-resolve

        Registration winner = ctx.winner()
                .orElseGet(() -> ctx.candidates.get(0)); // fallback: first candidate if no bids
        try {
            routeToProvider(winner, ctx.originalEvent);
            System.out.println("[CAP-IDX] Auction resolved → " + winner.pluginId());
        } catch (Exception e) {
            publishErrorSafe(ctx.originalEvent, "routing failed: " + e.getMessage());
        }
    }

    // ── Routing helpers ───────────────────────────────────────────────────────

    void routeToProvider(Registration reg, Event invokeEvent) throws Exception {
        if (reg.triggerEvent() != null) {
            // Tool: re-publish with the tool's trigger event type; tool subscribes directly to it
            Event routed = new Event(
                    UUID.randomUUID().toString(),
                    reg.triggerEvent(),
                    invokeEvent.payload(),
                    LocalDateTime.now(),
                    "kernel",
                    invokeEvent.correlationId(),
                    invokeEvent.sessionId(),
                    invokeEvent.workflowId(),
                    null,
                    invokeEvent.traceId(),
                    invokeEvent.spanId());
            bus.route(routed);
            System.out.println("[CAP-IDX] Tool route → " + reg.triggerEvent());
        } else {
            // Agent: forward via message.{agentId} (direct routing in kernel)
            Event forwarded = new Event(
                    UUID.randomUUID().toString(),
                    "message." + reg.pluginId(),
                    invokeEvent.payload(),
                    LocalDateTime.now(),
                    "kernel",
                    invokeEvent.correlationId(),
                    invokeEvent.sessionId(),
                    invokeEvent.workflowId(),
                    null,
                    invokeEvent.traceId(),
                    invokeEvent.spanId());
            bus.route(forwarded);
            System.out.println("[CAP-IDX] Agent route → message." + reg.pluginId());
        }
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

        // Catalog entries for capabilities not currently live (on-demand not yet started, etc.)
        Collection<PluginManager.CatalogEntry> catalogEntries = runtime != null ? runtime.allEntries() : List.of();
        for (PluginManager.CatalogEntry e : catalogEntries) {
            if (seen.contains(e.capabilityName())) continue;
            var entry = new LinkedHashMap<String, Object>();
            entry.put("capability", e.capabilityName());
            entry.put("provider",   e.pluginId());
            entry.put("bidWeight",  e.bidWeight());
            entry.put("live",       false);
            if (e.onDemand())           entry.put("onDemand",     true);
            if (e.triggerEvent() != null) entry.put("triggerEvent", e.triggerEvent());
            caps.add(entry);
        }

        return Event.MAPPER.writeValueAsString(caps);
    }

    // ── Plugin died ───────────────────────────────────────────────────────────

    void handlePluginDied(Event event) throws Exception {
        JsonNode p        = Event.MAPPER.readTree(event.payload());
        // payload uses "pluginId" for tools/system, "agentId" for agents
        String   pluginId = p.has("pluginId") ? p.get("pluginId").asText()
                          : p.has("agentId")  ? p.get("agentId").asText() : null;
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

    void handleQuery(Event event) throws Exception {
        // payload is the raw capability name string
        String             capName = event.payload();
        List<Registration> ps      = registrations.getOrDefault(capName, List.of());
        List<String>       ids     = ps.stream().map(Registration::pluginId).toList();
        String result = Event.MAPPER.writeValueAsString(
                Map.of("capability", capName, "providers", ids));
        bus.route(Event.reply(event, "capability.query.result", result, "kernel"));
    }

    // ── Error helpers ─────────────────────────────────────────────────────────

    void publishError(Event origin, String reason) {
        try {
            String payload = Event.MAPPER.writeValueAsString(Map.of("reason", reason));
            bus.route(Event.withCorrelation("capability.error", payload,
                    "kernel", origin.correlationId(), origin.sessionId()));
        } catch (Exception ignored) {}
    }

    void publishErrorSafe(Event origin, String reason) {
        publishError(origin, reason);
    }
}
