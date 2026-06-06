# Tools that need a secret (API key / password / token)

**Never put a secret value in `plugin.json`** (it is committable and would leak). Instead **declare**
the secret by name and read it at runtime from the **vault** via `SecretClient.get(...)`. The user
provides the value once, out-of-band; the vault stores it encrypted. Tools never see the value until
runtime and it never reaches the LLM.

## 1) `plugin.json` — declare, don't embed

- Non-secret settings go in a top-level `"config"` object (host, port, username, base URL, …).
- Each secret is declared in a top-level `"secrets"` array as `{ "key", "prompt" }` — **no value**.

```json
{
  "id": "email-sender",
  "type": "tool",
  "version": "1.0.0",
  "description": "Sends email via SMTP.",
  "lifecycle": { "mode": "on-demand", "idleTimeoutSeconds": 300 },
  "capabilities": [ {
    "name": "tool.email.send",
    "description": "Send an email.",
    "triggerEvent": "capability.tool.email.send",
    "inputSchema": { "required": ["emaildestino","titulo","msg"],
      "properties": { "emaildestino": {"type":"string"}, "titulo": {"type":"string"},
                      "msg": {"type":"string"}, "anexo": {"type":"string"} } }
  } ],
  "subscribes": [ "capability.tool.email.send" ],
  "config":  { "mail.smtp.host": "smtp.gmail.com", "mail.smtp.port": "587",
               "mail.smtp.username": "user@gmail.com" },
  "secrets": [ { "key": "EMAIL_SMTP_PASSWORD",
                 "prompt": "Senha de app SMTP para user@gmail.com" } ],
  "launch": { "name": "EmailSender", "command": ["jbang","EmailSender.java"], "order": 30 }
}
```

## 2) Java — read non-secret config from the tree, secret from the vault

Add `//SOURCES ../../helpers/SecretClient.java` to the usual kernel sources, and read the secret with one call:

```java
///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../../../kernel/KernelEvent.java
//SOURCES ../../../../kernel/Log.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginBase.java
//SOURCES ../../helpers/SecretClient.java

import com.fasterxml.jackson.databind.JsonNode;
import java.io.OutputStream;
import java.util.Map;

public class EmailSender {
    static final String EVT_TRIGGER = "capability.tool.email.send";
    static final String SOURCE_ID   = "email-sender";
    static PluginConfig config;

    public static void main(String[] args) throws Exception {
        KernelEvent.initLogging();
        config = PluginConfig.load("plugin.json");   // eager — no plugin.ready dependency
        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, EmailSender::handle);
    }

    static void handle(String json, OutputStream out) throws Exception {
        KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);
        if (!EVT_TRIGGER.equals(event.type())) return;

        JsonNode in  = KernelEvent.MAPPER.readTree(event.payload()).path("input");
        JsonNode cfg = config.raw().path("config");                       // NESTED config block

        String host = cfg.path("mail.smtp.host").asText("smtp.gmail.com");
        String user = cfg.path("mail.smtp.username").asText("");
        String pass = SecretClient.get("EMAIL_SMTP_PASSWORD");            // ← secret from the vault

        if (pass == null || pass.isBlank()) {
            PluginBase.publish(KernelEvent.withCorrelation("capability.error",
                KernelEvent.MAPPER.writeValueAsString(Map.of(
                    "reason", "Secret EMAIL_SMTP_PASSWORD not set — ask the user to provide it.")),
                SOURCE_ID, event.correlationId(), event.sessionId()), out);
            return;
        }

        // ... use host/user/pass to send the email, then reply capability.result ...
    }
}
```

## Rules

- `plugin.json` carries the secret **name** (`secrets[].key`), never the value.
- The Java reads it with `SecretClient.get("<KEY>")`. If `null`/blank, reply `capability.error` asking
  the user to set it (the orchestration will elicit and store it in the vault, then a retry succeeds).
- Non-secret config stays in `config.raw().path("config")`. Never invent `config.get(...)`.
- The secret key name should be descriptive and unique, e.g. `<TOOL>_<PURPOSE>` (`EMAIL_SMTP_PASSWORD`).
