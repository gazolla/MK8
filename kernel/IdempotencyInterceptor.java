// Shared file — included via //SOURCES in Kernel.java. No JBang header.

import java.util.*;
import java.util.concurrent.*;

/**
 * IdempotencyInterceptor — Platform-level request deduplication and Single-Flight collapsing.
 *
 * Occupies position 0 in the Kernel's interceptor chain, running before capability checks.
 * It eliminates redundant computation by implementing two caching and routing strategies:
 * 1. Sliding-Window Cache: Stores results and errors for 5 minutes. Subsequent invokes
 *    with the same correlationId are answered instantly from cache without routing.
 * 2. Single-Flight Collapsing: If identical invokes arrive concurrently, only the first
 *    is forwarded downstream. Duplicate callers are registered in-flight and then
 *    answered simultaneously once the single downstream outcome is received.
 *
 * Employs atomic compute blocks on concurrent maps to prevent race conditions during
 * simultaneous cache checks and result deliveries. Interceptor return values are used
 * to consume events completely and avoid broadcast noise when cache hits occur.
 */
class IdempotencyInterceptor implements EventInterceptor {

    private static final long CACHE_TTL_MINUTES = 5;
    private static final int  CORR_ID_LOG_LEN   = 8;

    // correlationId → cached result/error KernelEvent
    private final Map<String, KernelEvent>        cache    = new ConcurrentHashMap<>();

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
    public boolean intercept(KernelEvent event, String json) throws Exception {
        return switch (event.type()) {
            case "capability.invoke"                      -> handleInvoke(event, json);
            case "capability.result", "capability.error" -> handleResult(event, json);
            default                                       -> false;
        };
    }

    // ── Invoke handling ───────────────────────────────────────────────────────

    private boolean handleInvoke(KernelEvent event, String json) throws Exception {
        String corrId = event.correlationId();
        if (corrId == null) return false; // can't enforce idempotency without correlationId

        // Case 1: Return cached result instantly (checked before acquiring inFlight lock)
        KernelEvent cached = cache.get(corrId);
        if (cached != null) {
            System.out.println("[IDEMPOTENCY] Cache hit for corrId=" + shortId(corrId)
                    + " → returning cached result to " + event.source());
            bus.sendTo(event.source(), KernelEvent.MAPPER.writeValueAsString(cached));
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
            KernelEvent recheck = cache.get(corrId);
            if (recheck != null) {
                System.out.println("[IDEMPOTENCY] Cache hit (recheck) for corrId=" + shortId(corrId)
                        + " → returning cached result to " + event.source());
                try { bus.sendTo(event.source(), KernelEvent.MAPPER.writeValueAsString(recheck)); }
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

    private boolean handleResult(KernelEvent event, String json) throws Exception {
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
