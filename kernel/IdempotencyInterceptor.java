// Shared file — included via //SOURCES in Kernel.java. No JBang header.

import java.util.*;
import java.util.concurrent.*;

/**
 * IdempotencyInterceptor — platform-level idempotency and request collapsing.
 *
 * Intercepts capability.invoke and capability.result/error events.
 * Key features:
 *   1. Cache lookups: If correlationId has a cached result, returns it instantly.
 *   2. Request collapsing (Single-Flight): If correlationId is already in-flight,
 *      collapses duplicate calls, queuing the callers.
 *   3. Result distribution: On result arrival, delivers it to all collapsed callers
 *      and caches the result for CACHE_TTL_MINUTES minutes.
 */
class IdempotencyInterceptor implements EventInterceptor {

    private static final long CACHE_TTL_MINUTES = 5;
    private static final int  CORR_ID_LOG_LEN   = 8;

    // correlationId → cached result/error Event
    private final Map<String, Event>        cache    = new ConcurrentHashMap<>();

    // correlationId → list of pluginIds waiting for the result
    private final Map<String, List<String>> inFlight = new ConcurrentHashMap<>();

    private final KernelBus bus;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().name("idempotency-cleaner").daemon(true).factory());

    IdempotencyInterceptor(KernelBus bus) {
        this.bus = bus;
    }

    // ── EventInterceptor ──────────────────────────────────────────────────────

    @Override
    public boolean intercept(Event event, String json) throws Exception {
        return switch (event.type()) {
            case "capability.invoke"                      -> handleInvoke(event, json);
            case "capability.result", "capability.error" -> handleResult(event, json);
            default                                       -> false;
        };
    }

    // ── Invoke handling ───────────────────────────────────────────────────────

    private boolean handleInvoke(Event event, String json) throws Exception {
        String corrId = event.correlationId();
        if (corrId == null) return false; // can't enforce idempotency without correlationId

        // Case 1: Return cached result instantly (checked before acquiring inFlight lock)
        Event cached = cache.get(corrId);
        if (cached != null) {
            System.out.println("[IDEMPOTENCY] Cache hit for corrId=" + shortId(corrId)
                    + " → returning cached result to " + event.source());
            bus.sendTo(event.source(), Event.MAPPER.writeValueAsString(cached));
            return true;
        }

        // Cases 2 & 3 are decided atomically via compute() on the inFlight map.
        // This closes the race window between the cache miss above and the inFlight check:
        // if handleResult() runs between those two points, inFlight will be empty but cache
        // will be populated — the re-check inside compute() catches that and returns the
        // cached result instead of registering a new in-flight entry.
        boolean[] consumed = {false};
        inFlight.compute(corrId, (k, callers) -> {
            // Re-check cache inside the atomic compute block
            Event recheck = cache.get(corrId);
            if (recheck != null) {
                System.out.println("[IDEMPOTENCY] Cache hit (recheck) for corrId=" + shortId(corrId)
                        + " → returning cached result to " + event.source());
                try { bus.sendTo(event.source(), Event.MAPPER.writeValueAsString(recheck)); }
                catch (Exception ignored) {}
                consumed[0] = true;
                return null; // do not create an inFlight entry
            }

            if (callers != null) {
                // Case 2: already in-flight — collapse
                System.out.println("[IDEMPOTENCY] Collapsing duplicate invoke for corrId=" + shortId(corrId)
                        + " from " + event.source());
                callers.add(event.source());
                consumed[0] = true;
                return callers;
            }

            // Case 3: first execution — register in-flight
            List<String> first = new CopyOnWriteArrayList<>();
            first.add(event.source());
            return first;
        });
        return consumed[0];
    }

    // ── Result / Error caching and delivery ──────────────────────────────────

    private boolean handleResult(Event event, String json) throws Exception {
        String corrId = event.correlationId();
        if (corrId == null) return false;

        List<String> callers = inFlight.remove(corrId);
        if (callers == null) return false; // not tracking this corrId

        cache.put(corrId, event);
        System.out.println("[IDEMPOTENCY] Caching result for corrId=" + shortId(corrId)
                + " (" + callers.size() + " caller(s))");

        scheduler.schedule(() -> {
            cache.remove(corrId);
            System.out.println("[IDEMPOTENCY] Expired cache entry for corrId=" + shortId(corrId));
        }, CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        for (String callerId : callers) {
            System.out.println("[IDEMPOTENCY] Delivering result to caller: " + callerId);
            bus.sendTo(callerId, json);
        }

        // Clean up the return-routing table to prevent memory leak.
        bus.removePendingRoute(corrId);
        return true;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String shortId(String corrId) {
        return corrId.length() > CORR_ID_LOG_LEN ? corrId.substring(0, CORR_ID_LOG_LEN) : corrId;
    }
}
