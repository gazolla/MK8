///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../../kernel/KernelEvent.java
//SOURCES DevClient.java
//SOURCES ../Boot.java

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;

/**
 * Dev — headless boot + test client for the Kiwi distro.
 *
 * Boots the system through Boot when the kernel socket is absent, then drives chat/capability
 * flows as a transient client (via DevClient). If Dev booted the system, its JVM exit tears it
 * down (Boot's shutdown hook) — ideal for one-shot scripted tests. If the system is already up
 * (started via Start.java), Dev just connects and leaves it running.
 *
 * Usage (from projects/Kiwi/ or projects/Kiwi/dev/):
 *
 *   Start headless and stay up (no action):
 *     jbang Dev.java
 *     jbang Dev.java --clean              clear logs first
 *
 *   Wait until the kernel socket is up:
 *     jbang Dev.java --wait-ready [--timeout 30]
 *
 *   Chat (full assistant flow) — boots if needed, runs, tears down:
 *     jbang Dev.java --prompt "message"
 *
 *   Direct capability call (bypass assistant):
 *     jbang Dev.java --invoke tool.weather.get '{"city":"São Paulo"}'
 *
 *   Assert a capability is registered (bid-probe):
 *     jbang Dev.java --assert-capability tool.weather.get
 *
 *   Combine:
 *     jbang Dev.java --clean --invoke tool.weather.get '{"city":"SP"}' --timeout 30
 *
 *   Global flags:
 *     --clean       clear logs before booting (only when Dev boots the system)
 *     --timeout N   override timeout in seconds (default: 600 for prompt/invoke, 60 for wait/assert)
 */
public class Dev {

    static final Path SOCKET                 = Path.of("/tmp/mk8/kernel.sock");
    static final int  DEFAULT_TIMEOUT_PROMPT = 600;
    static final int  DEFAULT_TIMEOUT_WAIT   = 60;
    static final String CLIENT_ID            = "dev-client";

    public static void main(String[] args) throws Exception {
        Args a = Args.parse(args);

        // Boot the system via Boot if it isn't already up. With actions, boot headless in the
        // background and then drive it as a client; without actions, just boot headless and block.
        // (When Dev boots the system, its JVM exit triggers Boot's shutdown hook → the system is
        //  torn down after the test. When the system is already up, Dev only connects to it.)
        if (!Files.exists(SOCKET)) {
            if (a.hasAction()) {
                startBackground(a.clean);
                waitReady(a.timeout(DEFAULT_TIMEOUT_WAIT));
            } else {
                Boot.main(a.clean ? new String[]{"--dev", "--clean"} : new String[]{"--dev"});
                return; // headless boot blocks until Ctrl+C
            }
        }

        if (a.waitReady) {
            waitReady(a.timeout(DEFAULT_TIMEOUT_WAIT));
            System.out.println("[DEV] System ready.");
        }
        if (a.prompt != null)    sendPrompt(a.prompt, a.timeout(DEFAULT_TIMEOUT_PROMPT));
        if (a.invokeCap != null) invokeCap(a.invokeCap, a.invokeInput, a.timeout(DEFAULT_TIMEOUT_PROMPT));
        if (a.assertCap != null) assertCapability(a.assertCap, a.timeout(DEFAULT_TIMEOUT_WAIT));
    }

    /** Boot the full system headless in a background virtual thread (delegates to Boot). */
    static void startBackground(boolean clean) {
        String[] bootArgs = clean ? new String[]{"--dev", "--clean"} : new String[]{"--dev"};
        Thread.ofVirtual().start(() -> {
            try { Boot.main(bootArgs); } catch (Exception e) { e.printStackTrace(); }
        });
    }

    // ── Wait until kernel socket is up ───────────────────────────────────────

    static void waitReady(int timeoutSec) throws Exception {
        System.out.println("[DEV] Waiting for kernel socket (up to " + timeoutSec + "s)...");
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        while (!Files.exists(SOCKET)) {
            if (System.currentTimeMillis() > deadline)
                throw new RuntimeException("Kernel socket did not appear after " + timeoutSec + "s");
            Thread.sleep(500);
        }
        Thread.sleep(2000); // grace for plugins to register
        System.out.println("[DEV] Kernel up.");
    }

    // ── Send chat.prompt, wait for chat.response ─────────────────────────────

    static void sendPrompt(String message, int timeoutSec) throws Exception {
        String sessionId = "dev-" + UUID.randomUUID().toString().substring(0, 8);
        System.out.println("[DEV] Prompt: " + message);
        System.out.println("[DEV] Session: " + sessionId + "  Timeout: " + timeoutSec + "s");

        try (DevClient c = DevClient.connect(CLIENT_ID, List.of("chat.response"))) {
            KernelEvent req = KernelEvent.withSession("chat.prompt",
                    KernelEvent.MAPPER.writeValueAsString(Map.of("message", message)), CLIENT_ID, sessionId);
            KernelEvent resp = c.request(req, timeoutSec * 1000L,
                    ev -> "chat.response".equals(ev.type()) && sessionId.equals(ev.sessionId()));
            if (resp == null) { System.out.println("[DEV] Timeout."); return; }
            printResponse(resp.payload());
        }
    }

    // ── Invoke a capability directly, wait for capability.result ─────────────

    static void invokeCap(String capName, String inputJson, int timeoutSec) throws Exception {
        String corrId    = UUID.randomUUID().toString();
        String sessionId = "dev-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode input   = inputJson != null ? KernelEvent.MAPPER.readTree(inputJson)
                                             : KernelEvent.MAPPER.createObjectNode();
        // Canonical nested invoke shape: {name, input:{...}}
        String payload = KernelEvent.MAPPER.writeValueAsString(Map.of("name", capName, "input", input));

        System.out.println("[DEV] Invoke: " + capName + "  Input: " + inputJson + "  Timeout: " + timeoutSec + "s");

        try (DevClient c = DevClient.connect(CLIENT_ID, List.of("capability.result", "capability.error"))) {
            KernelEvent req = KernelEvent.withCorrelation("capability.invoke", payload, CLIENT_ID, corrId, sessionId);
            KernelEvent ev = c.request(req, timeoutSec * 1000L,
                    e -> corrId.equals(e.correlationId())
                         && ("capability.result".equals(e.type()) || "capability.error".equals(e.type())));
            if (ev == null) { System.out.println("[DEV] Timeout."); return; }
            JsonNode r = KernelEvent.MAPPER.readTree(ev.payload());
            if ("capability.error".equals(ev.type())) {
                System.out.println("\n[ERROR] " + r.path("reason").asText(ev.payload()));
                System.exit(1);
            }
            System.out.println("\n[RESULT]\n" + (r.has("result") ? r.get("result").asText() : ev.payload()));
        }
    }

    // ── Assert a capability is registered (bid-probe) ────────────────────────

    static void assertCapability(String capName, int timeoutSec) throws Exception {
        String corrId    = UUID.randomUUID().toString();
        String sessionId = "dev-" + UUID.randomUUID().toString().substring(0, 8);
        String payload = KernelEvent.MAPPER.writeValueAsString(
                Map.of("capabilityName", capName, "correlationId", corrId));

        System.out.println("[DEV] Asserting capability: " + capName);

        try (DevClient c = DevClient.connect(CLIENT_ID, List.of("capability.bid.response"))) {
            KernelEvent req = KernelEvent.withCorrelation("capability.bid.request", payload, CLIENT_ID, corrId, sessionId);
            KernelEvent ev = c.request(req, timeoutSec * 1000L, e -> {
                if (!"capability.bid.response".equals(e.type())) return false;
                try { return corrId.equals(KernelEvent.MAPPER.readTree(e.payload()).path("correlationId").asText()); }
                catch (Exception ex) { return false; }
            });
            if (ev != null) {
                String agentId = KernelEvent.MAPPER.readTree(ev.payload()).path("agentId").asText();
                System.out.println("[DEV] ✓ capability registered: " + capName + " → " + agentId);
            } else {
                System.out.println("[DEV] ✗ capability NOT found: " + capName);
                System.exit(1);
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    static void printResponse(String payload) {
        try {
            JsonNode p = KernelEvent.MAPPER.readTree(payload);
            System.out.println("\n[RESPONSE]\n" + (p.has("response") ? p.get("response").asText() : payload));
        } catch (Exception e) {
            System.out.println("\n[RESPONSE]\n" + payload);
        }
    }

    // ── Argument parser ────────────────────────────────────────────────────────

    static class Args {
        boolean clean     = false;
        boolean waitReady = false;
        String  prompt    = null;
        String  invokeCap = null;
        String  invokeInput = null;
        String  assertCap = null;
        Integer timeoutOverride = null;

        boolean hasAction() { return waitReady || prompt != null || invokeCap != null || assertCap != null; }
        int timeout(int defaultVal) { return timeoutOverride != null ? timeoutOverride : defaultVal; }

        static Args parse(String[] args) {
            Args a = new Args();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--clean"             -> a.clean = true;
                    case "--wait-ready"        -> a.waitReady = true;
                    case "--prompt"            -> a.prompt = args[++i];
                    case "--assert-capability" -> a.assertCap = args[++i];
                    case "--timeout"           -> a.timeoutOverride = Integer.parseInt(args[++i]);
                    case "--invoke" -> {
                        a.invokeCap = args[++i];
                        if (i + 1 < args.length && !args[i + 1].startsWith("--")) a.invokeInput = args[++i];
                    }
                }
            }
            return a;
        }
    }
}
