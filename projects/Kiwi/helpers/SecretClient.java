// Shared helper — included via //SOURCES by tools that read secrets from the vault.
// No JBang header (not an entry point). Depends only on KernelEvent (provided by the
// including tool's //SOURCES).
//
// Usage in a generated tool:
//     //SOURCES ../SecretClient.java
//     String password = SecretClient.get("EMAIL_SMTP_PASSWORD");
//
// It opens a short-lived UDS connection to the kernel, invokes the vault's secret.get
// capability, returns the value (cached after first fetch). Returns null if the secret
// is absent — the tool should then publish a capability.error telling the user to provide it.

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SecretClient {

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
    private static final long TIMEOUT_MS = 10_000;

    private SecretClient() {}

    /** Fetch a secret by key from the vault (capability secret.get). Cached after first hit. */
    public static String get(String key) {
        String cached = CACHE.get(key);
        if (cached != null) return cached;
        try {
            String v = fetch(key);
            if (v != null) CACHE.put(key, v);
            return v;
        } catch (Exception e) {
            System.err.println("[SECRET-CLIENT] fetch failed for " + key + ": " + e.getMessage());
            return null;
        }
    }

    private static String fetch(String key) throws Exception {
        var addr = UnixDomainSocketAddress.of(Path.of(KernelEvent.DEFAULT_SOCKET));
        try (SocketChannel ch = SocketChannel.open(addr)) {
            OutputStream out = Channels.newOutputStream(ch);
            InputStream  in  = Channels.newInputStream(ch);
            String id = "secret-client-" + ProcessHandle.current().pid();

            // First frame must be a registration (kernel routes the targeted result back to us).
            ObjectNode cfg = KernelEvent.MAPPER.createObjectNode();
            cfg.put("id", id);
            cfg.putArray("subscribes"); // none needed — capability.result returns targeted by corrId
            KernelEvent.writeFrame(out, KernelEvent.MAPPER.writeValueAsString(
                    KernelEvent.of("plugin.register", KernelEvent.MAPPER.writeValueAsString(cfg), id)));

            String corrId  = UUID.randomUUID().toString();
            String payload = KernelEvent.MAPPER.writeValueAsString(
                    Map.of("name", "secret.get", "input", Map.of("key", key)));
            KernelEvent.writeFrame(out, KernelEvent.MAPPER.writeValueAsString(
                    KernelEvent.withCorrelation("capability.invoke", payload, id, corrId, null)));

            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            String json;
            while ((json = KernelEvent.readFrame(in)) != null) {
                KernelEvent ev = KernelEvent.MAPPER.readValue(json, KernelEvent.class);
                if (corrId.equals(ev.correlationId())) {
                    if ("capability.result".equals(ev.type())) {
                        JsonNode p = KernelEvent.MAPPER.readTree(ev.payload());
                        return p.has("result") ? p.get("result").asText() : null;
                    }
                    if ("capability.error".equals(ev.type())) return null;
                }
                if (System.currentTimeMillis() > deadline) return null;
            }
            return null;
        }
    }
}
