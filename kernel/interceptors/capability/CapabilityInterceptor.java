// Shared file — included via //SOURCES in Kernel.java. No JBang header.

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * CatalogEntry — one entry per capability declaration found in plugin.json.
 */
record CatalogEntry(
        String   pluginId,
        String   pluginDir,
        String   capabilityName,
        String   triggerEvent,       // null for agents (no triggerEvent field)
        boolean  onDemand,           // lifecycle.mode == "on-demand"
        boolean  persistent,         // lifecycle.mode == "persistent"
        double   bidWeight,
        int      idleTimeoutSeconds,
        String[] launchCommand       // full launch.command array (e.g. ["jbang", "Tool.java"])
) {}

/** Checked-exception-safe handler for built-in capability invocations. */
@FunctionalInterface
interface BuiltInHandler {
    String handle(KernelEvent event) throws Exception;
}

/**
 * CapabilityInterceptor — Live capability registry, routing broker, and auction engine.
 *
 * Occupies position 1 in the Kernel's interceptor chain (executing after idempotency).
 * Maintains a local catalog populated via system.catalog.entry events from PluginManager —
 * zero direct dependency on PluginManager. Communicates via events only:
 *   system.catalog.entry/ready → received, builds localCatalog
 *   system.plugin.spawn/usage  → published, PluginManager handles
 */
class CapabilityInterceptor implements EventInterceptor {

    // ── Eventos que publica ───────────────────────────────────────────────────
    private static final String EVT_RESULT          = "capability.result";
    private static final String EVT_ERROR           = "capability.error";
    private static final String EVT_BID_REQUEST     = "capability.bid.request";
    private static final String EVT_PLUGIN_SPAWN    = "system.plugin.spawn";
    private static final String EVT_PLUGIN_USAGE    = "system.plugin.usage";
    private static final String EVT_CATALOG_REFRESH = "system.catalog.refresh";

    // ── Eventos que subscreve ─────────────────────────────────────────────────
    private static final String EVT_INVOKE         = "capability.invoke";
    private static final String EVT_REGISTER       = "capability.register";
    private static final String EVT_UNREGISTER     = "capability.unregister";
    private static final String EVT_BID_RESPONSE   = "capability.bid.response";
    private static final String EVT_QUERY          = "capability.query";
    private static final String EVT_QUERY_RESULT   = "capability.query.result";
    private static final String EVT_CATALOG_ENTRY  = "system.catalog.entry";
    private static final String EVT_CATALOG_READY  = "system.catalog.ready";
    private static final String EVT_PLUGIN_DIED    = "system.plugin.died";
    private static final String EVT_PLUGIN_STOPPED = "system.plugin.stopped";

    // ── Internos de roteamento ────────────────────────────────────────────────
    private static final String CAP_PREFIX = "capability.";
    private static final String MSG_PREFIX = "message.";
    private static final String CAP_LIST   = "system.capability.list";

    // JSON field keys — eventos de capability
    private static final String F_NAME             = "name";
    private static final String F_PLUGIN_ID        = "pluginId";
    private static final String F_AGENT_ID         = "agentId";
    private static final String F_TRIGGER          = "triggerEvent";
    private static final String F_BID_WEIGHT       = "bidWeight";
    private static final String F_CAP_NAME         = "capabilityName";
    private static final String F_CORR_ID          = "correlationId";
    private static final String F_SCORE            = "score";
    private static final String F_LOAD             = "load";

    // JSON field keys — payload de system.catalog.entry
    private static final String FC_PLUGIN_ID       = "pluginId";
    private static final String FC_PLUGIN_DIR      = "pluginDir";
    private static final String FC_CAP_NAME        = "capabilityName";
    private static final String FC_TRIGGER         = "triggerEvent";
    private static final String FC_ON_DEMAND       = "onDemand";
    private static final String FC_PERSISTENT      = "persistent";
    private static final String FC_BID_WEIGHT      = "bidWeight";
    private static final String FC_IDLE_TIMEOUT    = "idleTimeoutSeconds";
    private static final String FC_LAUNCH_CMD      = "launchCommand";


    // Configuração
    private static final long   AUCTION_TIMEOUT_MS = 500;
    private static final long   CATALOG_AWAIT_MS   = 500;
    private static final String SOURCE_KERNEL      = "kernel";

    // ── Inner types ───────────────────────────────────────────────────────────

    record Registration(String pluginId, String triggerEvent, double bidWeight) {}

    record BidEntry(String agentId, double score, double load) {
        double effective() { return score * (1.0 - load); }
    }

    private record RegisterPayload(String name, String pluginId, String trigger, double bidWeight) {}

    static class AuctionContext {
        final KernelEvent        originalEvent;
        final List<Registration> candidates;
        final List<BidEntry>     bids     = Collections.synchronizedList(new ArrayList<>());
        private final AtomicBoolean resolved = new AtomicBoolean(false);

        AuctionContext(KernelEvent originalEvent, List<Registration> candidates) {
            this.originalEvent = originalEvent;
            this.candidates    = List.copyOf(candidates);
        }

        boolean tryResolve() { return resolved.compareAndSet(false, true); }
        boolean isResolved() { return resolved.get(); }

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
    private final Map<String, BuiltInHandler>     builtins;
    private final Map<String, List<Registration>> registrations  = new ConcurrentHashMap<>();
    private final Map<String, List<KernelEvent>>  pendingInvokes = new ConcurrentHashMap<>();
    private final Map<String, AuctionContext>      auctions       = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CatalogEntry> localCatalog = new ConcurrentHashMap<>();
    private final CompletableFuture<Void>          catalogReady   = new CompletableFuture<>();

    // ── Construction ──────────────────────────────────────────────────────────

    CapabilityInterceptor(KernelBus bus) {
        this.bus      = bus;
        this.builtins = Map.of(CAP_LIST, e -> buildCapabilityList());
    }

    @Override public Set<String> publishes() {
        return Set.of(EVT_RESULT, EVT_ERROR, EVT_BID_REQUEST,
                      EVT_PLUGIN_SPAWN, EVT_PLUGIN_USAGE, EVT_CATALOG_REFRESH);
    }

    @Override public Set<String> subscribes() {
        return Set.of(EVT_INVOKE, EVT_REGISTER, EVT_UNREGISTER, EVT_BID_RESPONSE,
                      EVT_QUERY, EVT_QUERY_RESULT, EVT_CATALOG_ENTRY,
                      EVT_CATALOG_READY, EVT_PLUGIN_DIED, EVT_PLUGIN_STOPPED);
    }

    // ── EventInterceptor ──────────────────────────────────────────────────────

    @Override
    public boolean handles(String type) {
        return type.startsWith(CAP_PREFIX)
            || type.equals(EVT_CATALOG_ENTRY)
            || type.equals(EVT_CATALOG_READY)
            || type.equals(EVT_PLUGIN_DIED)
            || type.equals(EVT_PLUGIN_STOPPED);
    }

    @Override
    public boolean intercept(KernelEvent event, String json) throws Exception {
        return switch (event.type()) {
            case EVT_INVOKE                            -> { handleInvoke(event);          yield true;  }
            case EVT_RESULT, EVT_ERROR                 -> { handleResult(event, json);    yield true;  }
            case EVT_REGISTER                          -> { handleRegister(event);        yield false; }
            case EVT_UNREGISTER                        -> { handleUnregister(event);      yield true;  }
            case EVT_BID_RESPONSE                      -> { handleBidResponse(event);     yield true;  }
            case EVT_QUERY                             -> { handleQuery(event);           yield true;  }
            case EVT_CATALOG_ENTRY                     -> { handleCatalogEntry(event);    yield false; }
            case EVT_CATALOG_READY                     -> { catalogReady.complete(null);  yield false; }
            case EVT_PLUGIN_DIED, EVT_PLUGIN_STOPPED   -> { handlePluginDied(event);      yield false; }
            default                                    ->                                       false;
        };
    }

    // ── Catalog local — recebe push do PluginManager ──────────────────────────

    private void handleCatalogEntry(KernelEvent event) throws Exception {
        JsonNode p       = KernelEvent.MAPPER.readTree(event.payload());
        JsonNode cmdNode = p.path(FC_LAUNCH_CMD);
        String[] cmd     = cmdNode.isArray()
                ? StreamSupport.stream(cmdNode.spliterator(), false)
                               .map(JsonNode::asText).toArray(String[]::new)
                : null;
        var entry = new CatalogEntry(
                p.path(FC_PLUGIN_ID).asText(),
                p.path(FC_PLUGIN_DIR).asText(),
                p.path(FC_CAP_NAME).asText(),
                p.path(FC_TRIGGER).asText(null),
                p.path(FC_ON_DEMAND).asBoolean(),
                p.path(FC_PERSISTENT).asBoolean(),
                p.path(FC_BID_WEIGHT).asDouble(1.0),
                p.path(FC_IDLE_TIMEOUT).asInt(300),
                cmd);
        localCatalog.put(entry.capabilityName(), entry);
    }

    // ── Registration ──────────────────────────────────────────────────────────

    void handleRegister(KernelEvent event) throws Exception {
        RegisterPayload r = parseRegister(event);
        if (r == null) return;

        registrations.computeIfAbsent(r.name(), k -> new CopyOnWriteArrayList<>())
                .add(new Registration(r.pluginId(), r.trigger(), r.bidWeight()));

        System.out.println("[CAP-IDX] Registered: " + r.name() + " → " + r.pluginId()
                + (r.trigger() != null ? " trigger=" + r.trigger() : " (agent)") + " weight=" + r.bidWeight());

        List<KernelEvent> pending = pendingInvokes.remove(r.name());
        if (pending != null && !pending.isEmpty()) {
            Registration reg = registrations.get(r.name()).get(0);
            System.out.println("[CAP-IDX] Draining " + pending.size() + " queued invoke(s) for: " + r.name());
            for (KernelEvent e : pending) {
                try { routeToProvider(reg, e); } catch (Exception ignored) {}
            }
        }
    }

    void handleUnregister(KernelEvent event) throws Exception {
        RegisterPayload r = parseRegister(event);
        if (r == null) return;
        registrations.getOrDefault(r.name(), List.of()).removeIf(reg -> reg.pluginId().equals(r.pluginId()));
        System.out.println("[CAP-IDX] Unregistered: " + r.name() + " from " + r.pluginId());
    }

    private RegisterPayload parseRegister(KernelEvent event) throws Exception {
        JsonNode p      = KernelEvent.MAPPER.readTree(event.payload());
        String   name   = p.path(F_NAME).asText(null);
        String   plugin = p.path(F_PLUGIN_ID).asText(null);
        if (name == null || plugin == null) return null;
        String trigger = p.path(F_TRIGGER).asText(null);
        if (trigger != null && trigger.isBlank()) trigger = null;
        return new RegisterPayload(name, plugin, trigger, p.path(F_BID_WEIGHT).asDouble(1.0));
    }

    // ── Invoke routing ────────────────────────────────────────────────────────

    void handleInvoke(KernelEvent event) throws Exception {
        String capName = parseCapabilityName(event);
        if (capName == null) { publishError(event, "missing capability name"); return; }

        String corrId = event.correlationId();
        if (corrId != null && event.source() != null)
            bus.addPendingRoute(corrId, event.source());

        trackUsage(capName);
        if (handleBuiltIn(capName, event)) return;

        List<Registration> providers = registrations.get(capName);
        System.out.println("[CAP-IDX] invoke " + capName + " from=" + event.source()
                + " corrId=" + corrId
                + " providers=" + (providers == null ? 0 : providers.size()));

        if (providers == null || providers.isEmpty()) {
            handleNoProvider(capName, event);
            return;
        }
        if (providers.size() == 1) routeToProvider(providers.get(0), event);
        else                       startAuction(capName, new ArrayList<>(providers), event);
    }

    private String parseCapabilityName(KernelEvent event) throws Exception {
        String name = KernelEvent.MAPPER.readTree(event.payload()).path(F_NAME).asText(null);
        return (name != null && !name.isBlank()) ? name : null;
    }

    private boolean handleBuiltIn(String capName, KernelEvent event) throws Exception {
        BuiltInHandler handler = builtins.get(capName);
        if (handler == null) return false;
        replyResult(event, handler.handle(event));
        return true;
    }

    private void trackUsage(String capName) {
        try {
            String payload = KernelEvent.MAPPER.writeValueAsString(Map.of(F_CAP_NAME, capName));
            bus.route(KernelEvent.of(EVT_PLUGIN_USAGE, payload, SOURCE_KERNEL));
        } catch (Exception ignored) {}
    }

    private void handleNoProvider(String capName, KernelEvent event) throws Exception {
        try { catalogReady.get(CATALOG_AWAIT_MS, TimeUnit.MILLISECONDS); }
        catch (TimeoutException ignored) {}

        CatalogEntry entry = localCatalog.get(capName);
        if (entry == null)                                   { publishError(event, "no provider for: " + capName);
                                                               System.out.println("[CAP-IDX] No provider for: " + capName); return; }
        if (entry.persistent() && entry.triggerEvent() != null) { routePersistent(entry, event); return; }
        if (entry.onDemand())                                { queueOnDemand(capName, event); return; }
        publishError(event, "no live provider for: " + capName);
    }

    private void routePersistent(CatalogEntry entry, KernelEvent event) throws Exception {
        bus.route(forwardAs(event, entry.triggerEvent()));
        System.out.println("[CAP-IDX] Catalog route (persistent) → " + entry.triggerEvent());
    }

    private void queueOnDemand(String capName, KernelEvent event) throws Exception {
        pendingInvokes.computeIfAbsent(capName, k -> new CopyOnWriteArrayList<>()).add(event);
        String payload = KernelEvent.MAPPER.writeValueAsString(Map.of(F_CAP_NAME, capName));
        bus.route(KernelEvent.of(EVT_PLUGIN_SPAWN, payload, SOURCE_KERNEL));
        System.out.println("[CAP-IDX] Queued on-demand invoke for: " + capName);
    }

    // ── Auction ───────────────────────────────────────────────────────────────

    void startAuction(String capName, List<Registration> providers, KernelEvent invokeEvent) throws Exception {
        String auctionId = UUID.randomUUID().toString();
        auctions.put(auctionId, new AuctionContext(invokeEvent, providers));

        String bidReq = KernelEvent.MAPPER.writeValueAsString(Map.of(F_CAP_NAME, capName, F_CORR_ID, auctionId));
        bus.route(KernelEvent.withCorrelation(EVT_BID_REQUEST, bidReq, SOURCE_KERNEL, auctionId, invokeEvent.sessionId()));

        System.out.println("[CAP-IDX] Auction started for " + capName
                + " (" + providers.size() + " candidates) id=" + auctionId);
        CompletableFuture.delayedExecutor(AUCTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .execute(() -> resolveAuction(auctionId));
    }

    void handleBidResponse(KernelEvent event) throws Exception {
        JsonNode p     = KernelEvent.MAPPER.readTree(event.payload());
        String   aucId = p.path(F_CORR_ID).asText(null);
        if (aucId == null) return;

        AuctionContext ctx = auctions.get(aucId);
        if (ctx == null || ctx.isResolved()) return;

        String agentId = p.path(F_AGENT_ID).asText(null);
        double score   = p.path(F_SCORE).asDouble(1.0);
        double load    = p.path(F_LOAD).asDouble(0.0);
        if (agentId == null) return;
        ctx.bids.add(new BidEntry(agentId, score, load));

        System.out.println("[CAP-IDX] Bid from " + agentId + " score=" + score
                + " load=" + load + " effective=" + String.format("%.3f", score * (1.0 - load)));

        if (ctx.bids.size() >= ctx.candidates.size()) resolveAuction(aucId);
    }

    void resolveAuction(String auctionId) {
        AuctionContext ctx = auctions.remove(auctionId);
        if (ctx == null || !ctx.tryResolve()) return;

        Registration winner = ctx.winner().orElseGet(() -> ctx.candidates.get(0));
        try {
            routeToProvider(winner, ctx.originalEvent);
            System.out.println("[CAP-IDX] Auction resolved → " + winner.pluginId());
        } catch (Exception e) {
            publishError(ctx.originalEvent, "routing failed: " + e.getMessage());
        }
    }

    // ── Result / Error return routing ─────────────────────────────────────────

    void handleResult(KernelEvent event, String json) {
        String corrId = event.correlationId();
        if (corrId == null) return;
        String targetPluginId = bus.removePendingRoute(corrId);
        if (targetPluginId != null) {
            System.out.println("[CAP-IDX] route " + event.type() + " corrId=" + corrId + " → " + targetPluginId);
            bus.sendTo(targetPluginId, json);
        } else {
            System.out.println("[CAP-IDX] No pending route for " + event.type() + " corrId=" + corrId);
        }
    }

    // ── Routing helpers ───────────────────────────────────────────────────────

    void routeToProvider(Registration reg, KernelEvent invokeEvent) throws Exception {
        String type = reg.triggerEvent() != null ? reg.triggerEvent() : MSG_PREFIX + reg.pluginId();
        bus.route(forwardAs(invokeEvent, type));
        System.out.println("[CAP-IDX] Route → " + type);
    }

    private KernelEvent forwardAs(KernelEvent origin, String type) {
        return KernelEvent.reply(origin, type, origin.payload(), SOURCE_KERNEL);
    }

    // ── system.capability.list ────────────────────────────────────────────────

    String buildCapabilityList() throws Exception {
        var live = registrations.entrySet().stream()
                .flatMap(e -> e.getValue().stream()
                        .map(r -> capEntryMap(e.getKey(), r.pluginId(), r.bidWeight(), true)));

        var liveNames = registrations.keySet();
        var offline = localCatalog.values().stream()
                .filter(e -> !liveNames.contains(e.capabilityName()))
                .map(e -> {
                    var m = capEntryMap(e.capabilityName(), e.pluginId(), e.bidWeight(), false);
                    if (e.onDemand())             m.put("onDemand",  true);
                    if (e.triggerEvent() != null) m.put(F_TRIGGER,   e.triggerEvent());
                    return m;
                });

        return KernelEvent.MAPPER.writeValueAsString(Stream.concat(live, offline).toList());
    }

    private Map<String, Object> capEntryMap(String capability, String provider, double weight, boolean live) {
        var m = new LinkedHashMap<String, Object>();
        m.put("capability", capability);
        m.put("provider",   provider);
        m.put(F_BID_WEIGHT, weight);
        m.put("live",       live);
        return m;
    }

    // ── Plugin died/stopped ───────────────────────────────────────────────────

    void handlePluginDied(KernelEvent event) throws Exception {
        JsonNode p        = KernelEvent.MAPPER.readTree(event.payload());
        String   pluginId = p.path(F_PLUGIN_ID).asText(p.path(F_AGENT_ID).asText(null));
        if (pluginId == null || pluginId.isBlank()) return;

        boolean removed = registrations.values().stream()
                .mapToInt(list -> { int b = list.size(); list.removeIf(r -> r.pluginId().equals(pluginId)); return b - list.size(); })
                .sum() > 0;
        if (removed)
            System.out.println("[CAP-IDX] Cleaned registrations for dead plugin: " + pluginId);
    }

    // ── capability.query ──────────────────────────────────────────────────────

    void handleQuery(KernelEvent event) throws Exception {
        String             capName = event.payload();
        List<Registration> ps      = registrations.getOrDefault(capName, List.of());
        List<String>       ids     = ps.stream().map(Registration::pluginId).toList();
        String result = KernelEvent.MAPPER.writeValueAsString(Map.of("capability", capName, "providers", ids));
        bus.route(KernelEvent.reply(event, EVT_QUERY_RESULT, result, SOURCE_KERNEL));
    }

    // ── Reply / Error helpers ─────────────────────────────────────────────────

    private void replyResult(KernelEvent event, String resultJson) throws Exception {
        String payload = KernelEvent.MAPPER.writeValueAsString(Map.of("result", resultJson));
        bus.route(KernelEvent.withCorrelation(EVT_RESULT, payload,
                SOURCE_KERNEL, event.correlationId(), event.sessionId()));
    }

    void publishError(KernelEvent origin, String reason) {
        try {
            String payload = KernelEvent.MAPPER.writeValueAsString(Map.of("reason", reason));
            bus.route(KernelEvent.withCorrelation(EVT_ERROR, payload,
                    SOURCE_KERNEL, origin.correlationId(), origin.sessionId()));
        } catch (Exception e) {
            System.err.println("[CAP-IDX] Failed to publish error for corrId="
                    + origin.correlationId() + ": " + e.getMessage());
        }
    }
}
