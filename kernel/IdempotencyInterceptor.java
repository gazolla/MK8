// Shared file — included via //SOURCES in Kernel.java. No JBang header.

import com.fasterxml.jackson.databind.JsonNode;
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
 *      and caches the result for 5 minutes.
 */
class IdempotencyInterceptor implements EventInterceptor {

    // correlationId → cached result/error Event
    private final Map<String, Event> cache = new ConcurrentHashMap<>();

    // correlationId → List of pluginIds waiting for the result
    private final Map<String, List<String>> inFlight = new ConcurrentHashMap<>();

    private final KernelBus bus;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "idempotency-cleaner");
        t.setDaemon(true);
        return t;
    });

    IdempotencyInterceptor(KernelBus bus) {
        this.bus = bus;
    }

    @Override
    public boolean intercept(Event event, String json) throws Exception {
        String type = event.type();

        if ("capability.invoke".equals(type)) {
            return handleInvoke(event, json);
        }

        if ("capability.result".equals(type) || "capability.error".equals(type)) {
            return handleResult(event, json);
        }

        return false;
    }

    // ── Invoke handling ───────────────────────────────────────────────────────

    private boolean handleInvoke(Event event, String json) throws Exception {
        String corrId = event.correlationId();
        if (corrId == null) return false; // can't enforce idempotency without correlationId

        // Case 1: Return cached result
        Event cached = cache.get(corrId);
        if (cached != null) {
            System.out.println("[IDEMPOTENCY] Cache hit for corrId=" + corrId.substring(0, 8)
                    + " → returning cached result to " + event.source());
            
            // Build the response event with the caller's target correlationId
            String resJson = Event.MAPPER.writeValueAsString(cached);
            bus.sendTo(event.source(), resJson);
            return true; // consume event
        }

        // Case 2: Request Collapsing (Single-Flight)
        List<String> callers = inFlight.get(corrId);
        if (callers != null) {
            System.out.println("[IDEMPOTENCY] Collapsing duplicate invoke for corrId=" + corrId.substring(0, 8)
                    + " from " + event.source());
            callers.add(event.source());
            return true; // consume duplicate invoke so it's not routed to provider again
        }

        // Case 3: First execution — register in-flight
        callers = new CopyOnWriteArrayList<>();
        callers.add(event.source());
        inFlight.put(corrId, callers);
        return false; // let it route to the provider
    }

    // ── Result / Error caching and routing ────────────────────────────────────

    private boolean handleResult(Event event, String json) throws Exception {
        String corrId = event.correlationId();
        if (corrId == null) return false;

        List<String> callers = inFlight.remove(corrId);
        if (callers == null) {
            // Not a result we are tracking or already resolved — let normal routing continue
            return false;
        }

        // Cache the result/error
        cache.put(corrId, event);
        System.out.println("[IDEMPOTENCY] Caching result for corrId=" + corrId.substring(0, 8)
                + " (" + callers.size() + " caller(s))");

        // Expire from cache after 5 minutes
        scheduler.schedule(() -> {
            cache.remove(corrId);
            System.out.println("[IDEMPOTENCY] Expired cache entry for corrId=" + corrId.substring(0, 8));
        }, 5, TimeUnit.MINUTES);

        // Deliver result manually to all waiting callers
        for (String callerId : callers) {
            System.out.println("[IDEMPOTENCY] Delivering result to caller: " + callerId);
            bus.sendTo(callerId, json);
        }

        // Clean up from Kernel's pendingRoutes to prevent memory leak
        Kernel.pendingRoutes.remove(corrId);

        return true; // consume the result (handled manually)
    }
}
