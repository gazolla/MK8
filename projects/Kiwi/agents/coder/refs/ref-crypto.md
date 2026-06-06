# Cryptography — AES, HMAC, JWT, Hashing (JBang)

All built into the JDK via `javax.crypto` and `java.security` — no //DEPS needed.
For JWT with full library support, JJWT is the standard Java choice.

---

## SHA-256 hash (hex string)

```java
import java.security.MessageDigest;

static String sha256(String input) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    StringBuilder hex = new StringBuilder();
    for (byte b : hash) hex.append(String.format("%02x", b));
    return hex.toString();
}
```

---

## HMAC-SHA256 (message signing, webhook verification)

```java
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

static String hmacSha256(String secret, String message) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(
            secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] result = mac.doFinal(
            message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return Base64.getEncoder().encodeToString(result);
}

// Hex variant (used by GitHub webhooks, Stripe, etc.)
static String hmacSha256Hex(String secret, String message) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(
            secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] result = mac.doFinal(
            message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    StringBuilder hex = new StringBuilder();
    for (byte b : result) hex.append(String.format("%02x", b));
    return hex.toString();
}

// Verify a webhook signature (timing-safe comparison)
static boolean verifyHmac(String secret, String message, String expected) throws Exception {
    String computed = hmacSha256Hex(secret, message);
    return MessageDigest.isEqual(computed.getBytes(), expected.getBytes());
}
```

---

## AES-256-GCM — authenticated encryption

GCM mode provides both encryption and integrity (no separate HMAC needed).

```java
import javax.crypto.*;
import javax.crypto.spec.*;
import java.security.*;
import java.util.Base64;

static final String ALGO = "AES/GCM/NoPadding";
static final int    TAG_LEN = 128; // bits
static final int    IV_LEN  = 12;  // bytes — standard for GCM

// Generate a new 256-bit key (store it securely, e.g. as env var)
static SecretKey generateKey() throws Exception {
    KeyGenerator gen = KeyGenerator.getInstance("AES");
    gen.init(256, new SecureRandom());
    return gen.generateKey();
}

// Encrypt → Base64(iv + ciphertext)
static String encrypt(SecretKey key, String plaintext) throws Exception {
    byte[] iv = new byte[IV_LEN];
    new SecureRandom().nextBytes(iv);

    Cipher cipher = Cipher.getInstance(ALGO);
    cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LEN, iv));
    byte[] encrypted = cipher.doFinal(
            plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

    // Prepend IV to ciphertext so decrypt can read it back
    byte[] combined = new byte[IV_LEN + encrypted.length];
    System.arraycopy(iv, 0, combined, 0, IV_LEN);
    System.arraycopy(encrypted, 0, combined, IV_LEN, encrypted.length);
    return Base64.getEncoder().encodeToString(combined);
}

// Decrypt ← Base64(iv + ciphertext)
static String decrypt(SecretKey key, String encoded) throws Exception {
    byte[] combined = Base64.getDecoder().decode(encoded);
    byte[] iv         = java.util.Arrays.copyOfRange(combined, 0, IV_LEN);
    byte[] ciphertext = java.util.Arrays.copyOfRange(combined, IV_LEN, combined.length);

    Cipher cipher = Cipher.getInstance(ALGO);
    cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LEN, iv));
    return new String(cipher.doFinal(ciphertext), java.nio.charset.StandardCharsets.UTF_8);
}

// Key from env (Base64-encoded 32-byte key)
static SecretKey keyFromEnv(String envVar) {
    String b64 = System.getenv(envVar);
    if (b64 == null) throw new RuntimeException(envVar + " not set");
    byte[] keyBytes = Base64.getDecoder().decode(b64);
    return new SecretKeySpec(keyBytes, "AES");
}
```

---

## JWT — manual creation (no deps, HS256)

```java
import java.util.Base64;

static String createJwt(String secret, Map<String, Object> claims, long expiresInSeconds) throws Exception {
    long now = System.currentTimeMillis() / 1000;
    claims = new java.util.LinkedHashMap<>(claims);
    claims.put("iat", now);
    claims.put("exp", now + expiresInSeconds);

    String header  = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes());
    String payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(KernelEvent.MAPPER.writeValueAsString(claims).getBytes());

    String sigInput = header + "." + payload;
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(
            secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
    String sig = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mac.doFinal(sigInput.getBytes()));

    return sigInput + "." + sig;
}

// Verify and parse JWT (returns claims map, throws if invalid or expired)
static Map<String, Object> verifyJwt(String secret, String token) throws Exception {
    String[] parts = token.split("\\.");
    if (parts.length != 3) throw new IllegalArgumentException("Invalid JWT format");

    String sigInput = parts[0] + "." + parts[1];
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(
            secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
    String expectedSig = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mac.doFinal(sigInput.getBytes()));

    if (!MessageDigest.isEqual(expectedSig.getBytes(), parts[2].getBytes()))
        throw new SecurityException("JWT signature invalid");

    byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
    @SuppressWarnings("unchecked")
    Map<String, Object> claims = KernelEvent.MAPPER.readValue(payloadBytes, Map.class);

    long exp = ((Number) claims.getOrDefault("exp", 0)).longValue();
    if (exp > 0 && System.currentTimeMillis() / 1000 > exp)
        throw new SecurityException("JWT expired");

    return claims;
}
```

---

## JWT with JJWT library (full RS256/ES256 support)

```java
//DEPS io.jsonwebtoken:jjwt-api:0.12.6
//DEPS io.jsonwebtoken:jjwt-impl:0.12.6
//DEPS io.jsonwebtoken:jjwt-jackson:0.12.6

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import javax.crypto.SecretKey;

SecretKey key = Keys.hmacShaKeyFor(secretBytes); // 256+ bit key

// Create
String token = Jwts.builder()
        .subject("user-123")
        .issuedAt(new java.util.Date())
        .expiration(new java.util.Date(System.currentTimeMillis() + 3_600_000))
        .signWith(key)
        .compact();

// Verify
Claims claims = Jwts.parser().verifyWith(key).build()
        .parseSignedClaims(token).getPayload();
String subject = claims.getSubject();
```

---

## Secure random token (API keys, session IDs)

```java
static String randomToken(int bytes) {
    byte[] buf = new byte[bytes];
    new SecureRandom().nextBytes(buf);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
}
// randomToken(32) → 43-char URL-safe string
```
