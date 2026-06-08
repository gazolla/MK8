// Shared file — included via //SOURCES in Agent.java

import com.fasterxml.jackson.databind.JsonNode;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.*;

/**
 * CapabilityRouter — invokes capabilities and sub-agents over UDS,
 * and resolves the pending futures when results/errors arrive.
 *
 * MK8 note: on-demand loading is no longer the agent's job. The kernel's
 * CapabilityInterceptor auto-spawns an on-demand provider and queues the invoke
 * until it registers (draining on capability.register). So invokeCapability simply
 * publishes capability.invoke and waits — the interceptor handles spawn + queue.
 * This removes the MK7 plugin.load + poll-until-registered dance entirely.
 */
public class CapabilityRouter {

    // ── Event types ─────────────────────────────────────────────────────────────
    static final String EVT_CAPABILITY_INVOKE   = "capability.invoke";

    // ── Tuning ──────────────────────────────────────────────────────────────────
    static final String PREFIX_AGENT_CAPABILITY = "agent.";   // sub-agent capabilities (long missions)
    static final long   TOOL_TIMEOUT_SECONDS    = 90;
    static final long   AGENT_TIMEOUT_SECONDS   = 600;

    private final AgentConfig config;
    private final AgentCore core;
    final Map<String, CompletableFuture<String>> pendingInvocations = new ConcurrentHashMap<>();

    CapabilityRouter(AgentConfig config, AgentCore core) {
        this.config = config;
        this.core   = core;
    }

    // ── Resolve incoming capability.result ────────────────────────────────────

    void resolveInvocation(KernelEvent event) {
        String corrId = event.correlationId();
        if (corrId == null) return;
        CompletableFuture<String> f = pendingInvocations.get(corrId);
        if (f == null) return;
        try {
            JsonNode p = KernelEvent.MAPPER.readTree(event.payload());
            if (p.has("result")) {
                JsonNode resNode = p.get("result");
                f.complete(resNode.isTextual() ? resNode.asText() : resNode.toString());
            } else {
                f.complete(event.payload());
            }
        } catch (Exception e) {
            f.complete(event.payload());
        }
    }

    // ── Resolve incoming capability.error ─────────────────────────────────────

    void resolveInvocationError(KernelEvent event) {
        String corrId = event.correlationId();
        if (corrId == null) return;
        CompletableFuture<String> f = pendingInvocations.get(corrId);
        if (f == null) return;
        try {
            JsonNode p = KernelEvent.MAPPER.readTree(event.payload());
            f.complete("Error: " + (p.has("reason") ? p.get("reason").asText() : event.payload()));
        } catch (Exception e) {
            f.complete("Error: " + event.payload());
        }
    }

    // ── Invoke a capability over UDS ──────────────────────────────────────────

    String invokeCapability(String capabilityName, String argsJson, String sessionId, OutputStream out) {
        argsJson = argsJson
            .replace("\\$0", "$0")
            .replace("\\$@", "$@")
            .replace("\\$?", "$?");

        // Agents may run long missions; tools are quicker. The timeout also absorbs
        // on-demand spawn latency, since the kernel queues the invoke until ready.
        long timeoutSec = capabilityName.startsWith(PREFIX_AGENT_CAPABILITY)
                ? AGENT_TIMEOUT_SECONDS : TOOL_TIMEOUT_SECONDS;
        return trySingleInvoke(capabilityName, argsJson, sessionId, out, timeoutSec);
    }

    private String trySingleInvoke(String capabilityName, String argsJson,
                                   String sessionId, OutputStream out, long timeoutSec) {
        String corrId = UUID.randomUUID().toString();
        CompletableFuture<String> future = new CompletableFuture<>();
        pendingInvocations.put(corrId, future);
        try {
            JsonNode argsNode  = KernelEvent.MAPPER.readTree(argsJson);
            JsonNode inputNode = argsNode.has("input") ? argsNode.get("input") : argsNode;

            String invocationJson = KernelEvent.MAPPER.writeValueAsString(Map.of(
                "name",  capabilityName,
                "input", inputNode
            ));

            PluginBase.publish(
                    KernelEvent.withCorrelation(EVT_CAPABILITY_INVOKE, invocationJson,
                            config.id(), corrId, sessionId),
                    out);
            return future.get(timeoutSec, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return "Error: invocation timed out after " + timeoutSec + " seconds";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        } finally {
            pendingInvocations.remove(corrId);
        }
    }
}
