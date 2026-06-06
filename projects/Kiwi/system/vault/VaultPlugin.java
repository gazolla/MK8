///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../../../kernel/KernelEvent.java
//SOURCES ../../../../kernel/Log.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginBase.java

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.OutputStream;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.*;

/**
 * VaultPlugin — secrets store for the Kiwi distro.
 *
 * Holds API keys / passwords that generated tools need, so secrets never live in plugin.json
 * (committable) and never reach the LLM provider. Capabilities (nested invoke {name, input:{…}}):
 *   secret.set  {key, value}  → stores the secret           → {"result":"stored: <key>"}
 *   secret.get  {key}         → returns the value           → {"result":"<value>"}  (or capability.error)
 *   secret.has  {key}         → existence check             → {"result":"true|false"}
 *
 * Storage: ~/.kiwi/secrets.enc — AES-256-GCM encrypted JSON map, file perms 0600. The AES key is
 * read from env KIWI_VAULT_KEY (base64-32B) if set, else auto-generated into ~/.kiwi/vault.key (0600).
 * Values are NEVER logged (only keys/ops). This is local at-rest protection; OS Keychain is a future
 * upgrade behind the same capabilities.
 */
public class VaultPlugin {

    static final String SOURCE_ID = "vault";
    static final String EVT_SET = "capability.system.secret.set";
    static final String EVT_GET = "capability.system.secret.get";
    static final String EVT_HAS = "capability.system.secret.has";
    static final String EVT_REQUEST = "capability.system.secret.request";
    static final String EVT_ELICIT  = "secret.elicit"; // broadcast → gateways capture out-of-band

    static final Path VAULT_DIR    = Path.of(System.getProperty("user.home"), ".kiwi");
    static final Path SECRETS_FILE = VAULT_DIR.resolve("secrets.enc");
    static final Path KEY_FILE     = VAULT_DIR.resolve("vault.key");

    static final int    GCM_IV_BYTES  = 12;
    static final int    GCM_TAG_BITS   = 128;
    static final Object LOCK           = new Object();
    static final SecureRandom RNG      = new SecureRandom();

    public static void main(String[] args) throws Exception {
        KernelEvent.initLogging();
        ensureDir();
        Log.rawInfo("[VAULT] Starting. Store: " + SECRETS_FILE);
        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, VaultPlugin::handle);
    }

    static void handle(String json, OutputStream out) throws Exception {
        Log.configure(SOURCE_ID, out);
        KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);
        switch (event.type()) {
            case EVT_SET     -> handleSet(event, out);
            case EVT_GET     -> handleGet(event, out);
            case EVT_HAS     -> handleHas(event, out);
            case EVT_REQUEST -> handleRequest(event, out);
            default -> { /* ignore (bid auto-handled by PluginBase) */ }
        }
    }

    /**
     * secret.request {key, prompt} — does NOT take a value. Broadcasts a secret.elicit carrying the
     * requester's sessionId so the gateway (console/telegram) can capture the user's next message
     * OUT-OF-BAND (the value goes straight to secret.set, never through the LLM).
     */
    static void handleRequest(KernelEvent event, OutputStream out) throws Exception {
        JsonNode in = input(event);
        String key    = in.path("key").asText("").trim();
        String prompt = in.path("prompt").asText("Please provide the secret value for " + key);
        if (key.isEmpty()) { error(event, "key is required", out); return; }

        String session = event.sessionId() == null ? "" : event.sessionId();
        String elicit = KernelEvent.MAPPER.writeValueAsString(Map.of(
                "sessionId", session, "key", key, "prompt", prompt));
        PluginBase.publish(KernelEvent.withSession(EVT_ELICIT, elicit, SOURCE_ID, event.sessionId()), out);
        Log.rawInfo("[VAULT] elicit requested key=" + key + " session=" + session);
        result(event, "elicitation requested for " + key, out);
    }

    static JsonNode input(KernelEvent e) throws Exception {
        JsonNode p = KernelEvent.MAPPER.readTree(e.payload());
        return p.has("input") ? p.get("input") : p;
    }

    static void handleSet(KernelEvent event, OutputStream out) throws Exception {
        JsonNode in = input(event);
        String key   = in.path("key").asText("").trim();
        String value = in.path("value").asText("");
        if (key.isEmpty())   { error(event, "key is required", out); return; }
        if (value.isEmpty()) { error(event, "value is required", out); return; }

        synchronized (LOCK) {
            Map<String, String> store = load();
            store.put(key, value);
            save(store);
        }
        Log.rawInfo("[VAULT] set key=" + key + " (value redacted)");          // never log the value
        result(event, "stored: " + key, out);
    }

    static void handleGet(KernelEvent event, OutputStream out) throws Exception {
        String key = input(event).path("key").asText("").trim();
        if (key.isEmpty()) { error(event, "key is required", out); return; }

        String value;
        synchronized (LOCK) { value = load().get(key); }
        if (value == null) { Log.rawInfo("[VAULT] get key=" + key + " → MISS"); error(event, "no secret: " + key, out); return; }
        Log.rawInfo("[VAULT] get key=" + key + " → HIT (value redacted)");
        result(event, value, out);
    }

    static void handleHas(KernelEvent event, OutputStream out) throws Exception {
        String key = input(event).path("key").asText("").trim();
        boolean has;
        synchronized (LOCK) { has = key.isEmpty() ? false : load().containsKey(key); }
        Log.rawInfo("[VAULT] has key=" + key + " → " + has);
        result(event, String.valueOf(has), out);
    }

    // ── Replies ─────────────────────────────────────────────────────────────

    static void result(KernelEvent origin, String value, OutputStream out) throws Exception {
        PluginBase.publish(KernelEvent.withCorrelation("capability.result",
                KernelEvent.MAPPER.writeValueAsString(Map.of("result", value)),
                SOURCE_ID, origin.correlationId(), origin.sessionId()), out);
    }

    static void error(KernelEvent origin, String reason, OutputStream out) {
        try {
            PluginBase.publish(KernelEvent.withCorrelation("capability.error",
                    KernelEvent.MAPPER.writeValueAsString(Map.of("reason", reason)),
                    SOURCE_ID, origin.correlationId(), origin.sessionId()), out);
        } catch (Exception ignored) {}
    }

    // ── Encrypted storage ─────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    static Map<String, String> load() {
        try {
            if (!Files.exists(SECRETS_FILE)) return new LinkedHashMap<>();
            byte[] blob = Files.readAllBytes(SECRETS_FILE);
            byte[] plain = decrypt(blob);
            return KENV(plain);
        } catch (Exception e) {
            Log.rawError("[VAULT] load failed: " + e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> KENV(byte[] plain) throws Exception {
        return KernelEvent.MAPPER.readValue(plain, LinkedHashMap.class);
    }

    static void save(Map<String, String> store) throws Exception {
        byte[] plain = KernelEvent.MAPPER.writeValueAsBytes(store);
        byte[] blob  = encrypt(plain);
        Files.write(SECRETS_FILE, blob);
        restrict(SECRETS_FILE);
    }

    static byte[] encrypt(byte[] plain) throws Exception {
        byte[] iv = new byte[GCM_IV_BYTES];
        RNG.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ct = c.doFinal(plain);
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return out;
    }

    static byte[] decrypt(byte[] blob) throws Exception {
        byte[] iv = Arrays.copyOfRange(blob, 0, GCM_IV_BYTES);
        byte[] ct = Arrays.copyOfRange(blob, GCM_IV_BYTES, blob.length);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return c.doFinal(ct);
    }

    /** 32-byte AES key from env KIWI_VAULT_KEY (base64) or an auto-generated ~/.kiwi/vault.key (0600). */
    static byte[] key() throws Exception {
        String env = System.getenv("KIWI_VAULT_KEY");
        if (env != null && !env.isBlank()) return Base64.getDecoder().decode(env.trim());
        if (Files.exists(KEY_FILE)) return Base64.getDecoder().decode(Files.readString(KEY_FILE).trim());
        byte[] k = new byte[32];
        RNG.nextBytes(k);
        Files.writeString(KEY_FILE, Base64.getEncoder().encodeToString(k));
        restrict(KEY_FILE);
        return k;
    }

    // ── Filesystem helpers ────────────────────────────────────────────────────

    static void ensureDir() throws Exception {
        Files.createDirectories(VAULT_DIR);
        restrict(VAULT_DIR);
    }

    /** Best-effort 0600 (files) / 0700 (dir) on POSIX; no-op elsewhere. */
    static void restrict(Path p) {
        try {
            boolean dir = Files.isDirectory(p);
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(dir ? "rwx------" : "rw-------"));
        } catch (Exception ignored) {}
    }
}
