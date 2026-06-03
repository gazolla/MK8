// Shared file — included via //SOURCES in Kernel.java. No JBang header.

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.concurrent.*;

/**
 * BlackboardInterceptor — Platform-level shared in-memory key-value store for
 * inter-agent context passing, promoted from the MK7 BlackboardPlugin into the
 * kernel interceptor chain.
 *
 * Acts as a shared "whiteboard": plugins that otherwise never talk to each other
 * write facts (conversation context, research caches, workflow flags) under a
 * scope and read them back later. Because it runs inside the kernel, reads and
 * writes resolve in-memory with zero extra network hops — the reply goes straight
 * back to the requester via KernelBus.sendTo, correlated by the original
 * correlationId (KernelEvent.reply preserves it).
 *
 * Scopes: session (scopeId=sessionId), workflow (scopeId=workflowId), global (scopeId=null).
 * Storage key: "<scope>:<scopeId>:<key>".
 *
 * Events handled (all consumed — return true):
 *   blackboard.write  → store entry, publish blackboard.updated.{scope}.{key}
 *   blackboard.read   → reply blackboard.read.result to source
 *   blackboard.query  → reply blackboard.query.result to source
 *   blackboard.purge  → delete all entries in a scope/scopeId
 *
 * Optimistic locking: optional expectedVersion field. The check-and-set runs
 * inside store.compute() so the version bump is atomic under concurrent writers
 * (an improvement over the MK7 get-then-put which had a race window).
 * TTL: entries with ttl>0 expire after ttl seconds (a daemon cleaner runs every 60s).
 *
 * handles() filters to blackboard.* event types, which are disjoint from the
 * capability.* flow — so this interceptor's position in the chain is irrelevant.
 */
class BlackboardInterceptor implements EventInterceptor {

    // ── Event types ───────────────────────────────────────────────────────────
    private static final String EVT_WRITE  = "blackboard.write";
    private static final String EVT_READ   = "blackboard.read";
    private static final String EVT_QUERY  = "blackboard.query";
    private static final String EVT_PURGE  = "blackboard.purge";

    private static final String SOURCE = "blackboard";

    record Entry(
            String key, String scope, String scopeId, String value,
            String author, List<String> tags, long ttlSeconds,
            long version, long createdAt, long updatedAt
    ) {}

    // Storage key "<scope>:<scopeId>:<key>" → Entry. Sorted map keeps query iteration deterministic.
    private final ConcurrentSkipListMap<String, Entry> store = new ConcurrentSkipListMap<>();

    private final KernelBus bus;

    private final ScheduledExecutorService cleaner =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().name("blackboard-ttl").daemon(true).factory());

    BlackboardInterceptor(KernelBus bus) {
        this.bus = bus;
        cleaner.scheduleAtFixedRate(this::cleanExpired, 60, 60, TimeUnit.SECONDS);
    }

    @Override public Set<String> publishes()  {
        return Set.of("blackboard.read.result", "blackboard.query.result",
                      "blackboard.write.ok", "blackboard.write.conflict", "blackboard.updated.*");
    }
    @Override public Set<String> subscribes() {
        return Set.of(EVT_WRITE, EVT_READ, EVT_QUERY, EVT_PURGE);
    }

    @Override
    public boolean handles(String type) {
        return type.equals(EVT_WRITE) || type.equals(EVT_READ)
            || type.equals(EVT_QUERY) || type.equals(EVT_PURGE);
    }

    @Override
    public boolean intercept(KernelEvent event, String json) throws Exception {
        return switch (event.type()) {
            case EVT_WRITE -> handleWrite(event);
            case EVT_READ  -> handleRead(event);
            case EVT_QUERY -> handleQuery(event);
            case EVT_PURGE -> handlePurge(event);
            default        -> false;
        };
    }

    // ── Write ───────────────────────────────────────────────────────────────

    private boolean handleWrite(KernelEvent event) throws Exception {
        JsonNode p   = KernelEvent.MAPPER.readTree(event.payload());
        String key   = p.path("key").asText();
        String scope = p.path("scope").asText("global");
        String scopeId = p.hasNonNull("scopeId") ? p.get("scopeId").asText() : null;
        String value = p.has("value") ? KernelEvent.MAPPER.writeValueAsString(p.get("value")) : "null";
        String author = event.source();
        long ttl = p.path("ttl").asLong(0);
        long expVersion = p.path("expectedVersion").asLong(-1);

        List<String> tags = new ArrayList<>();
        if (p.path("tags").isArray()) p.path("tags").forEach(t -> tags.add(t.asText()));

        String sk  = storeKey(scope, scopeId, key);
        long   now = System.currentTimeMillis();

        // Atomic check-and-set: optimistic-lock check + version bump in one step.
        var outcome = new Object() { boolean conflict; long version; long currentVersion; String reason; };
        store.compute(sk, (k, exist) -> {
            if (expVersion >= 0) {
                if (exist == null && expVersion > 0) {
                    outcome.conflict = true; outcome.currentVersion = 0;
                    outcome.reason = "key does not exist but non-zero version expected";
                    return exist;
                }
                if (exist != null && exist.version() != expVersion) {
                    outcome.conflict = true; outcome.currentVersion = exist.version();
                    outcome.reason = "version mismatch";
                    return exist;
                }
            }
            long version = exist != null ? exist.version() + 1 : 1;
            long created = exist != null ? exist.createdAt() : now;
            outcome.version = version;
            return new Entry(key, scope, scopeId, value, author, tags, ttl, version, created, now);
        });

        if (outcome.conflict) {
            if (expVersion >= 0) {
                String payload = KernelEvent.MAPPER.writeValueAsString(Map.of(
                        "key", key, "currentVersion", outcome.currentVersion,
                        "expectedVersion", expVersion, "reason", outcome.reason));
                bus.sendTo(event.source(), KernelEvent.MAPPER.writeValueAsString(
                        KernelEvent.reply(event, "blackboard.write.conflict", payload, SOURCE)));
            }
            log("write CONFLICT " + sk + " current=" + outcome.currentVersion
                    + " expected=" + expVersion + " (" + outcome.reason + ")");
            return true;
        }

        // Reactive notification to whoever observes blackboard.updated.*
        bus.route(KernelEvent.of("blackboard.updated." + scope + "." + key, value, SOURCE));
        log("write " + sk + " v" + outcome.version + " author=" + author + (ttl > 0 ? " ttl=" + ttl + "s" : ""));

        if (expVersion >= 0) {
            String payload = KernelEvent.MAPPER.writeValueAsString(Map.of(
                    "key", key, "version", outcome.version, "status", "success"));
            bus.sendTo(event.source(), KernelEvent.MAPPER.writeValueAsString(
                    KernelEvent.reply(event, "blackboard.write.ok", payload, SOURCE)));
        }
        return true;
    }

    // ── Read ────────────────────────────────────────────────────────────────

    private boolean handleRead(KernelEvent event) throws Exception {
        JsonNode p   = KernelEvent.MAPPER.readTree(event.payload());
        String key   = p.path("key").asText();
        String scope = p.path("scope").asText("global");
        String scopeId = p.hasNonNull("scopeId") ? p.get("scopeId").asText() : null;

        String sk = storeKey(scope, scopeId, key);
        Entry entry = store.get(sk);
        String result;
        if (entry != null && !isExpired(entry)) {
            result = KernelEvent.MAPPER.writeValueAsString(entryToMap(entry));
            log("read HIT  " + sk + " v" + entry.version());
        } else {
            result = KernelEvent.MAPPER.writeValueAsString(Map.of("found", false, "key", key));
            log("read MISS " + sk);
        }
        bus.sendTo(event.source(), KernelEvent.MAPPER.writeValueAsString(
                KernelEvent.reply(event, "blackboard.read.result", result, SOURCE)));
        return true;
    }

    // ── Query ───────────────────────────────────────────────────────────────

    private boolean handleQuery(KernelEvent event) throws Exception {
        JsonNode p     = KernelEvent.MAPPER.readTree(event.payload());
        String scope   = p.hasNonNull("scope")   ? p.get("scope").asText()   : null;
        String scopeId = p.hasNonNull("scopeId") ? p.get("scopeId").asText() : null;

        Set<String> filterTags = new HashSet<>();
        if (p.path("tags").isArray()) p.path("tags").forEach(t -> filterTags.add(t.asText()));

        List<Map<String, Object>> matches = store.values().stream()
                .filter(e -> !isExpired(e))
                .filter(e -> scope   == null || scope.equals(e.scope()))
                .filter(e -> scopeId == null || scopeId.equals(e.scopeId()))
                .filter(e -> filterTags.isEmpty() || e.tags().stream().anyMatch(filterTags::contains))
                .map(BlackboardInterceptor::entryToMap)
                .toList();

        String result = KernelEvent.MAPPER.writeValueAsString(Map.of("entries", matches));
        log("query scope=" + scope + " scopeId=" + scopeId + " tags=" + filterTags + " → " + matches.size() + " entries");
        bus.sendTo(event.source(), KernelEvent.MAPPER.writeValueAsString(
                KernelEvent.reply(event, "blackboard.query.result", result, SOURCE)));
        return true;
    }

    // ── Purge ───────────────────────────────────────────────────────────────

    private boolean handlePurge(KernelEvent event) throws Exception {
        JsonNode p     = KernelEvent.MAPPER.readTree(event.payload());
        String scope   = p.hasNonNull("scope")   ? p.get("scope").asText()   : null;
        String scopeId = p.hasNonNull("scopeId") ? p.get("scopeId").asText() : null;

        long before = store.size();
        store.entrySet().removeIf(e -> {
            Entry en = e.getValue();
            boolean scopeMatch   = scope   == null || scope.equals(en.scope());
            boolean scopeIdMatch = scopeId == null || scopeId.equals(en.scopeId());
            return scopeMatch && scopeIdMatch;
        });
        log("purge scope=" + scope + " scopeId=" + scopeId + " removed=" + (before - store.size()));
        return true;
    }

    // ── TTL cleanup ───────────────────────────────────────────────────────────

    private void cleanExpired() {
        long before = store.size();
        store.entrySet().removeIf(e -> isExpired(e.getValue()));
        long removed = before - store.size();
        if (removed > 0) System.out.println("[BLACKBOARD] TTL cleaned " + removed + " expired entries");
    }

    private static boolean isExpired(Entry e) {
        return e.ttlSeconds() > 0
                && System.currentTimeMillis() > e.updatedAt() + e.ttlSeconds() * 1000L;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String storeKey(String scope, String scopeId, String key) {
        return scope + ":" + (scopeId != null ? scopeId : "") + ":" + key;
    }

    private static Map<String, Object> entryToMap(Entry e) {
        var m = new LinkedHashMap<String, Object>();
        m.put("key",       e.key());
        m.put("scope",     e.scope());
        if (e.scopeId() != null) m.put("scopeId", e.scopeId());
        m.put("value",     e.value());
        m.put("author",    e.author());
        m.put("tags",      e.tags());
        m.put("version",   e.version());
        m.put("createdAt", e.createdAt());
        m.put("updatedAt", e.updatedAt());
        return m;
    }

    private static void log(String msg) {
        System.out.println("[BLACKBOARD] " + msg);
    }
}
