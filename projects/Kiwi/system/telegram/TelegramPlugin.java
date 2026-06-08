///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//DEPS com.github.pengrad:java-telegram-bot-api:8.3.0
//SOURCES ../../../../kernel/KernelEvent.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginBase.java

import com.fasterxml.jackson.databind.JsonNode;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.Chat;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.request.ChatAction;
import com.pengrad.telegrambot.model.request.ParseMode;
import com.pengrad.telegrambot.request.*;
import com.pengrad.telegrambot.response.GetUpdatesResponse;

import java.io.OutputStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TelegramPlugin — Telegram Bot gateway for the Kiwi distro (ported from MK7, pengrad 8.3.0).
 *
 * Long-polling (no webhook). Each Telegram chatId maps to a stable sessionId ("tg-{chatId}").
 * /reset starts a fresh sessionId. After each chat.response, workspace files created during the
 * request are sent automatically.
 *
 * MK8 idiom: PluginBase.run drives the kernel connection + event loop (and auto-registers
 * capabilities and auto-answers capability.bid.request). The Telegram long-poll loop starts on
 * the first plugin.ready in a virtual thread. MK8 chat.* payloads are JSON: chat.thinking={status},
 * chat.response={response}, chat.command.result={result}; chat.prompt is published as {text}.
 *
 * Required env: TELEGRAM_BOT_TOKEN
 * Optional env: TELEGRAM_ALLOWED_USERS — comma-separated Telegram user IDs
 */
public class TelegramPlugin {

    static final String BOT_TOKEN   = System.getenv("TELEGRAM_BOT_TOKEN");
    static final String ALLOWED_ENV = System.getenv("TELEGRAM_ALLOWED_USERS");

    // Plugin runs from system/telegram/, so ../../workspace = projects/Kiwi/workspace
    static final Path WORKSPACE = Path.of(System.getProperty("user.dir"))
            .resolve("../../workspace").normalize();

    static final Map<Long, String>            chatToSession = new ConcurrentHashMap<>();
    static final Map<String, Long>            sessionToChat = new ConcurrentHashMap<>();
    static final Map<Long, TypingLoop>        typingLoops   = new ConcurrentHashMap<>();
    static final Map<Long, Integer>           thinkingMsg   = new ConcurrentHashMap<>(); // chatId → status messageId
    static final Map<Long, Map<String, Long>> wsSnapshots   = new ConcurrentHashMap<>();
    static final Map<Long, String>            awaitingSecret = new ConcurrentHashMap<>(); // chatId → secret key (out-of-band capture)

    static final Set<Long> allowedUsers = parseAllowedUsers();

    static volatile TelegramBot  bot;
    static volatile String       pluginId = "telegram";
    static volatile OutputStream globalOut;
    static final AtomicBoolean   polling = new AtomicBoolean(false);

    record TypingLoop(Thread thread, AtomicBoolean cancelled) {
        void stop() { cancelled.set(true); thread.interrupt(); }
    }

    // ── Main ──────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        pluginId = PluginConfig.load("plugin.json").id();

        if (BOT_TOKEN == null || BOT_TOKEN.isBlank()) {
            // Stay connected but idle so the boot watch doesn't tear the system down when the
            // token is absent — Telegram is opt-in via TELEGRAM_BOT_TOKEN.
            System.err.println("[TELEGRAM] TELEGRAM_BOT_TOKEN not set — running idle (no polling).");
            PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, (json, out) -> { /* idle */ });
            return;
        }

        // OkHttp read timeout must exceed the long-poll timeout (30 s)
        var okHttp = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(35, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
        bot = new TelegramBot.Builder(BOT_TOKEN).okHttpClient(okHttp).build();

        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, TelegramPlugin::handle);
    }

    // ── Kernel → handler (PluginBase auto-handles capability.bid.request) ──────

    static void handle(String json, OutputStream out) throws Exception {
        globalOut = out;
        KernelEvent ev = KernelEvent.MAPPER.readValue(json, KernelEvent.class);

        switch (ev.type()) {
            case "plugin.ready" -> {
                if (polling.compareAndSet(false, true)) {
                    log("Connected. workspace=" + WORKSPACE);
                    Thread.ofVirtual().start(() -> pollLoop(out));
                }
            }
            case "capability.system.telegram.send" -> handleTelegramSend(ev, out);
            case "secret.elicit" -> handleElicit(ev);
            default -> routeToTelegram(ev);
        }
    }

    /** Vault asked for a secret in some session → ask that chat and arm out-of-band capture. */
    static void handleElicit(KernelEvent ev) {
        try {
            JsonNode p = KernelEvent.MAPPER.readTree(ev.payload());
            Long chatId = sessionToChat.get(p.path("sessionId").asText(""));
            if (chatId == null) return; // not one of our sessions
            awaitingSecret.put(chatId, p.path("key").asText());
            bot.execute(new SendMessage(chatId, "🔐 " + p.path("prompt").asText("Envie o segredo:")
                    + "\n(será apagada após salvar e não será enviada ao modelo)"));
            log("elicit chatId=" + chatId + " key=" + p.path("key").asText());
        } catch (Exception e) {
            log("elicit error: " + e.getMessage());
        }
    }

    static void routeToTelegram(KernelEvent ev) {
        String sid = ev.sessionId();
        if (sid == null) return;
        Long chatId = sessionToChat.get(sid);
        if (chatId == null) return; // not our session

        switch (ev.type()) {
            case "chat.thinking" -> {
                stopTypingLoop(chatId);
                String status = readField(ev.payload(), "status", "⏳ Thinking...");
                var resp = bot.execute(new SendMessage(chatId, status));
                if (resp.isOk()) thinkingMsg.put(chatId, resp.message().messageId());
            }
            case "chat.response" -> {
                stopTypingLoop(chatId);
                String response = readField(ev.payload(), "response", ev.payload());
                Integer msgId = thinkingMsg.remove(chatId);
                if (msgId != null && response != null && response.length() <= 4096
                        && bot.execute(new EditMessageText(chatId, msgId, response)).isOk()) {
                    log("← chat.response (edited) chatId=" + chatId);
                } else {
                    log("← chat.response chatId=" + chatId);
                    sendMessage(chatId, response);
                }
                sendNewWorkspaceFiles(chatId, response);
            }
            case "chat.command.result" -> {
                stopTypingLoop(chatId);
                thinkingMsg.remove(chatId);
                log("← chat.command.result chatId=" + chatId);
                sendMessage(chatId, readField(ev.payload(), "result", ev.payload()));
            }
            case "chat.typing" -> { if (!thinkingMsg.containsKey(chatId)) startTypingLoop(chatId); }
        }
    }

    // ── Long-poll loop (Telegram → kernel) ────────────────────────────────────

    static void pollLoop(OutputStream out) {
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        int[] offset = {0};
        while (!Thread.currentThread().isInterrupted()) {
            try {
                GetUpdatesResponse resp = bot.execute(
                        new GetUpdates().limit(100).offset(offset[0]).timeout(30));
                if (resp.isOk() && resp.updates() != null) {
                    for (Update u : resp.updates()) {
                        offset[0] = u.updateId() + 1;
                        final Update upd = u;
                        executor.submit(() -> handleUpdate(upd, out));
                    }
                }
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) break;
                log("Poll error: " + e.getMessage());
                try { Thread.sleep(3_000); } catch (InterruptedException ie) { break; }
            }
        }
    }

    static void handleUpdate(Update update, OutputStream out) {
        try {
            Message msg = update.message();
            if (msg == null || msg.text() == null) return;

            Chat   chat   = msg.chat();
            long   chatId = chat.id();
            long   userId = msg.from() != null ? msg.from().id() : chatId;
            String text   = msg.text().trim();

            if (!isAllowed(userId)) { bot.execute(new SendMessage(chatId, "Access denied.")); return; }

            // Out-of-band secret capture: this message is the secret value, not a chat prompt.
            // It goes straight to the vault and is deleted — it never reaches the LLM.
            String pendingKey = awaitingSecret.remove(chatId);
            if (pendingKey != null) {
                if (text.equalsIgnoreCase("/cancel")) {
                    bot.execute(new SendMessage(chatId, "Captura de segredo cancelada."));
                    return;
                }
                String payload = KernelEvent.MAPPER.writeValueAsString(Map.of(
                        "name", "secret.set", "input", Map.of("key", pendingKey, "value", text)));
                PluginBase.publishSafe(KernelEvent.of("capability.invoke", payload, pluginId), out);
                try { bot.execute(new DeleteMessage(chatId, msg.messageId())); } catch (Exception ignored) {}
                bot.execute(new SendMessage(chatId, "✅ Segredo salvo com segurança."));
                log("secret captured (redacted) chatId=" + chatId);
                // Nudge the assistant (in-conversation) to retry the action that needed the secret.
                String sid = chatToSession.computeIfAbsent(chatId, id -> "tg-" + id);
                sessionToChat.putIfAbsent(sid, chatId);
                String nudge = KernelEvent.MAPPER.writeValueAsString(Map.of("text",
                        "O segredo solicitado foi configurado com sucesso. Execute novamente a ação que o solicitou."));
                PluginBase.publishSafe(KernelEvent.withSession("chat.prompt", nudge, pluginId, sid), out);
                return;
            }

            String sessionId = chatToSession.computeIfAbsent(chatId, id -> "tg-" + id);
            sessionToChat.putIfAbsent(sessionId, chatId);

            if (text.startsWith("/")) {
                handleCommand(text, chatId, sessionId, out);
            } else {
                wsSnapshots.put(chatId, snapshotWorkspace());
                startTypingLoop(chatId);
                String payload = KernelEvent.MAPPER.writeValueAsString(Map.of("text", text));
                PluginBase.publishSafe(
                        KernelEvent.withSession("chat.prompt", payload, pluginId, sessionId), out);
                log("→ chat.prompt chatId=" + chatId + " session=" + sessionId);
            }
        } catch (Exception e) {
            log("handleUpdate error: " + e.getMessage());
        }
    }

    // ── Slash commands ────────────────────────────────────────────────────────

    static void handleCommand(String text, long chatId, String sessionId, OutputStream out) {
        String[] parts = text.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();

        switch (cmd) {
            case "/start" -> bot.execute(new SendMessage(chatId,
                "Kiwi Bot\n\n" +
                "Send me any message and I'll connect you to the AI assistant.\n" +
                "Files saved to the workspace during a request will be sent back automatically.\n\n" +
                "/reset — start a new conversation\n" +
                "/help  — show all available dynamic system commands"));

            case "/reset" -> {
                String newSession = "tg-" + chatId + "-" + UUID.randomUUID().toString().substring(0, 8);
                String oldSession = chatToSession.put(chatId, newSession);
                if (oldSession != null) sessionToChat.remove(oldSession);
                sessionToChat.put(newSession, chatId);
                wsSnapshots.remove(chatId);
                stopTypingLoop(chatId);
                bot.execute(new SendMessage(chatId, "New session started."));
                log("reset chatId=" + chatId + " newSession=" + newSession);
            }

            default -> {
                // Unknown slash command — route dynamically as a system command request.
                try {
                    String[] cmdParts = text.split("\\s+", 2);
                    String command = cmdParts[0].toLowerCase();
                    String cmdArgs = cmdParts.length > 1 ? cmdParts[1].trim() : "";
                    String payload = KernelEvent.MAPPER.writeValueAsString(Map.of("command", command, "args", cmdArgs));
                    PluginBase.publishSafe(
                            KernelEvent.withSession("chat.command.request", payload, pluginId, sessionId), out);
                    log("→ chat.command.request: " + command + " session=" + sessionId);
                } catch (Exception e) {
                    log("Failed to publish command: " + e.getMessage());
                }
            }
        }
    }

    // ── system.telegram.send capability ───────────────────────────────────────

    static void handleTelegramSend(KernelEvent event, OutputStream out) {
        try {
            JsonNode input = KernelEvent.MAPPER.readTree(event.payload());
            if (input.has("input")) input = input.get("input");
            String message   = input.has("message")   ? input.get("message").asText("")    : "";
            String sessionId = input.has("sessionId") ? input.get("sessionId").asText(null) : null;

            if (message.isBlank()) {
                PluginBase.publishSafe(KernelEvent.withCorrelation("capability.error",
                    KernelEvent.MAPPER.writeValueAsString(Map.of("reason", "message is blank")),
                    "telegram", event.correlationId(), event.sessionId()), out);
                return;
            }

            if (sessionId != null) {
                Long chatId = sessionToChat.get(sessionId);
                if (chatId != null) sendMessage(chatId, message);
            } else {
                sessionToChat.values().forEach(chatId -> sendMessage(chatId, message));
            }

            PluginBase.publish(KernelEvent.withCorrelation("capability.result",
                KernelEvent.MAPPER.writeValueAsString(Map.of("result", "sent")),
                "telegram", event.correlationId(), event.sessionId()), out);
        } catch (Exception e) {
            log("telegram.send error: " + e.getMessage());
        }
    }

    // ── Typing indicator ──────────────────────────────────────────────────────

    static void startTypingLoop(long chatId) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Thread t = Thread.ofVirtual().unstarted(() -> {
            while (!cancelled.get()) {
                try {
                    bot.execute(new SendChatAction(chatId, ChatAction.typing));
                    Thread.sleep(4_000);
                } catch (InterruptedException e) {
                    break;
                } catch (RuntimeException e) {
                    if (cancelled.get()) break; // OkHttp may wrap interrupt as RuntimeException
                }
            }
        });
        TypingLoop old = typingLoops.put(chatId, new TypingLoop(t, cancelled));
        if (old != null) old.stop();
        t.start();
    }

    static void stopTypingLoop(long chatId) {
        TypingLoop loop = typingLoops.remove(chatId);
        if (loop != null) loop.stop();
    }

    // ── Workspace file detection ──────────────────────────────────────────────

    static Map<String, Long> snapshotWorkspace() {
        Map<String, Long> snap = new HashMap<>();
        try {
            if (Files.exists(WORKSPACE))
                Files.list(WORKSPACE).forEach(p -> {
                    try { snap.put(p.getFileName().toString(), Files.getLastModifiedTime(p).toMillis()); }
                    catch (Exception ignored) {}
                });
        } catch (Exception ignored) {}
        return snap;
    }

    static void sendNewWorkspaceFiles(long chatId, String text) {
        if (text != null && text.contains("segundo plano")) return;
        Map<String, Long> before = wsSnapshots.remove(chatId);
        if (before == null) return;
        try {
            if (!Files.exists(WORKSPACE)) return;
            Files.list(WORKSPACE).sorted().forEach(p -> {
                try {
                    String name = p.getFileName().toString();
                    if (name.startsWith(".")) return;
                    long  nowMod  = Files.getLastModifiedTime(p).toMillis();
                    Long  prevMod = before.get(name);
                    if (prevMod == null || nowMod > prevMod) {
                        log("→ sendFile " + name + " chatId=" + chatId);
                        sendFile(chatId, p);
                    }
                } catch (Exception e) { log("sendFile error: " + e.getMessage()); }
            });
        } catch (Exception e) { log("sendNewWorkspaceFiles error: " + e.getMessage()); }
    }

    static void sendFile(long chatId, Path file) {
        String name = file.getFileName().toString().toLowerCase();
        boolean tryPhoto = name.endsWith(".jpg") || name.endsWith(".jpeg")
                        || name.endsWith(".png") || name.endsWith(".gif") || name.endsWith(".webp");
        java.io.File f = file.toFile();
        if (tryPhoto) {
            var resp = bot.execute(new SendPhoto(chatId, f));
            if (resp.isOk()) { log("sendFile OK (photo): " + file.getFileName()); return; }
            log("sendFile photo failed (" + resp.errorCode() + "), falling back to document");
        }
        var resp = bot.execute(new SendDocument(chatId, f));
        if (resp.isOk()) log("sendFile OK (document): " + file.getFileName());
        else log("sendFile FAILED: " + resp.errorCode() + " — " + resp.description());
    }

    // ── Send text message ─────────────────────────────────────────────────────

    static void sendMessage(long chatId, String text) {
        if (text == null || text.isBlank()) return;
        for (String chunk : splitMessage(text, 4000)) {
            var resp = bot.execute(new SendMessage(chatId, chunk).parseMode(ParseMode.Markdown));
            if (!resp.isOk()) bot.execute(new SendMessage(chatId, chunk)); // Markdown rejected → plain
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Reads a string field from a JSON payload, falling back to the given default. */
    static String readField(String payload, String field, String fallback) {
        try {
            JsonNode p = KernelEvent.MAPPER.readTree(payload);
            return p.has(field) ? p.get(field).asText(fallback) : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    static List<String> splitMessage(String text, int maxLen) {
        if (text.length() <= maxLen) return List.of(text);
        List<String> chunks = new ArrayList<>();
        while (text.length() > maxLen) {
            int cut = text.lastIndexOf('\n', maxLen);
            if (cut < maxLen / 2) cut = maxLen;
            chunks.add(text.substring(0, cut));
            text = text.substring(cut).stripLeading();
        }
        if (!text.isBlank()) chunks.add(text);
        return chunks;
    }

    static boolean isAllowed(long userId) {
        return allowedUsers.isEmpty() || allowedUsers.contains(userId);
    }

    static Set<Long> parseAllowedUsers() {
        if (ALLOWED_ENV == null || ALLOWED_ENV.isBlank()) return Set.of();
        Set<Long> ids = new HashSet<>();
        for (String s : ALLOWED_ENV.split(",")) {
            try { ids.add(Long.parseLong(s.trim())); } catch (NumberFormatException ignored) {}
        }
        return Collections.unmodifiableSet(ids);
    }

    static void log(String msg) {
        System.out.println("[" + pluginId.toUpperCase() + "] " + msg);
    }
}
