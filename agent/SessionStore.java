// Shared file — included via //SOURCES in Agent.java

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.ChatMemory;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/**
 * SessionStore — persists chat session histories to a per-agent SQLite DB.
 *
 * Schema: sessions(session_id TEXT PK, agent_id TEXT, messages TEXT JSON, last_active INTEGER)
 *
 * Lifecycle:
 *   open()   — called once at agent startup; creates the DB/table if absent
 *   loadInto — called the first time a sessionId is seen; hydrates ChatMemory from DB
 *   save     — called after each MissionRunner.run(); persists current memory state
 *   prune    — called at startup; removes sessions idle > ttlDays and caps total count
 *   clear    — wipes all rows for this agent (triggered by --reset-memory at boot)
 *   close    — called on shutdown
 */
public class SessionStore {

    // ── Tuning ──────────────────────────────────────────────────────────────────
    static final int    MAX_TOOL_TEXT  = 800;                // truncate persisted tool outputs
    static final String EMPTY_RESPONSE = "[Empty response]";

    private final String agentId;
    private final Path   dbPath;
    private Connection   conn;

    SessionStore(String agentId, Path projectRoot) {
        this.agentId = agentId;
        this.dbPath  = projectRoot.resolve("data/sessions-" + agentId + ".db");
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    void open() throws Exception {
        Files.createDirectories(dbPath.getParent());
        conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS sessions (
                    session_id  TEXT    PRIMARY KEY,
                    agent_id    TEXT    NOT NULL,
                    messages    TEXT    NOT NULL,
                    last_active INTEGER NOT NULL
                )
                """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_last_active ON sessions(last_active)");
        }
        System.out.println("[SESSION-STORE] opened " + dbPath.getFileName() + " for agent=" + agentId);
    }

    void close() {
        try { if (conn != null && !conn.isClosed()) conn.close(); }
        catch (Exception ignored) {}
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    void loadInto(String sessionId, ChatMemory memory) {
        if (conn == null) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT messages FROM sessions WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return;
                List<ChatMessage> loaded = new ArrayList<>();
                for (JsonNode node : KernelEvent.MAPPER.readTree(rs.getString(1))) {
                    ChatMessage m = deserialize(node);
                    if (m != null) loaded.add(m);
                }
                sanitize(loaded);
                for (ChatMessage m : loaded) memory.add(m);
                System.out.println("[SESSION-STORE] loaded " + loaded.size()
                        + " messages for session=" + sessionId);
            }
        } catch (Exception e) {
            System.err.println("[SESSION-STORE] load error session=" + sessionId + ": " + e.getMessage());
        }
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    void save(String sessionId, List<ChatMessage> messages) {
        if (conn == null) return;
        try {
            ArrayNode arr = KernelEvent.MAPPER.createArrayNode();
            for (ChatMessage m : messages) {
                ObjectNode node = serialize(m);
                if (node != null) arr.add(node);
            }
            long now = System.currentTimeMillis() / 1000;
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO sessions(session_id, agent_id, messages, last_active)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT(session_id) DO UPDATE
                        SET messages    = excluded.messages,
                            last_active = excluded.last_active
                    """)) {
                ps.setString(1, sessionId);
                ps.setString(2, agentId);
                ps.setString(3, KernelEvent.MAPPER.writeValueAsString(arr));
                ps.setLong(4, now);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            System.err.println("[SESSION-STORE] save error session=" + sessionId + ": " + e.getMessage());
        }
    }

    // ── Prune ─────────────────────────────────────────────────────────────────

    void prune(int ttlDays, int maxSessions) {
        if (conn == null) return;
        try {
            long cutoff = System.currentTimeMillis() / 1000 - (long) ttlDays * 86_400;

            int deleted;
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM sessions WHERE agent_id = ? AND last_active < ?")) {
                ps.setString(1, agentId);
                ps.setLong(2, cutoff);
                deleted = ps.executeUpdate();
            }

            // Cap: keep only the most-recent maxSessions rows for this agent
            int capped;
            try (PreparedStatement ps = conn.prepareStatement("""
                    DELETE FROM sessions WHERE agent_id = ?
                    AND session_id NOT IN (
                        SELECT session_id FROM sessions WHERE agent_id = ?
                        ORDER BY last_active DESC LIMIT ?
                    )
                    """)) {
                ps.setString(1, agentId);
                ps.setString(2, agentId);
                ps.setInt(3, maxSessions);
                capped = ps.executeUpdate();
            }

            if (deleted + capped > 0)
                System.out.println("[SESSION-STORE] pruned " + (deleted + capped)
                        + " session(s) for agent=" + agentId);
        } catch (Exception e) {
            System.err.println("[SESSION-STORE] prune error: " + e.getMessage());
        }
    }

    // ── Clear (--reset-memory) ────────────────────────────────────────────────

    void clear() {
        if (conn == null) return;
        try (Statement st = conn.createStatement()) {
            int n = st.executeUpdate("DELETE FROM sessions WHERE agent_id = '" + agentId + "'");
            System.out.println("[SESSION-STORE] cleared " + n + " session(s) for agent=" + agentId);
        } catch (Exception e) {
            System.err.println("[SESSION-STORE] clear error: " + e.getMessage());
        }
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    private ObjectNode serialize(ChatMessage m) {
        ObjectNode n = KernelEvent.MAPPER.createObjectNode();
        if (m instanceof UserMessage um) {
            n.put("role", "user");
            n.put("text", um.singleText());
        } else if (m instanceof AiMessage am) {
            n.put("role", "assistant");
            if (am.hasToolExecutionRequests()) {
                ArrayNode calls = n.putArray("toolCalls");
                for (ToolExecutionRequest r : am.toolExecutionRequests()) {
                    ObjectNode c = KernelEvent.MAPPER.createObjectNode();
                    c.put("id",   r.id() != null ? r.id() : "");
                    c.put("name", r.name());
                    c.put("args", r.arguments() != null ? r.arguments() : "{}");
                    calls.add(c);
                }
            } else {
                n.put("text", am.text() != null ? am.text() : "");
            }
        } else if (m instanceof ToolExecutionResultMessage tr) {
            n.put("role",     "tool");
            n.put("id",       tr.id());
            n.put("toolName", tr.toolName());
            // Truncate large tool outputs — rarely meaningful after the round ends
            String text = tr.text() != null ? tr.text() : "";
            n.put("text", text.length() > MAX_TOOL_TEXT ? text.substring(0, MAX_TOOL_TEXT) + "…" : text);
        } else {
            return null; // skip SystemMessage — regenerated dynamically
        }
        return n;
    }

    private ChatMessage deserialize(JsonNode n) {
        String role = n.path("role").asText("");
        String text = n.path("text").asText("");
        if (text.isBlank()) {
            text = EMPTY_RESPONSE;
        }
        final String finalText = text;
        return switch (role) {
            case "user" -> UserMessage.from(finalText);
            case "assistant" -> {
                if (n.has("toolCalls")) {
                    List<ToolExecutionRequest> reqs = new ArrayList<>();
                    for (JsonNode c : n.get("toolCalls")) {
                        reqs.add(ToolExecutionRequest.builder()
                                .id(c.path("id").asText("call_" + UUID.randomUUID()))
                                .name(c.path("name").asText())
                                .arguments(c.path("args").asText("{}"))
                                .build());
                    }
                    yield AiMessage.from(reqs);
                }
                yield AiMessage.from(finalText);
            }
            case "tool" -> ToolExecutionResultMessage.from(
                    ToolExecutionRequest.builder()
                            .id(n.path("id").asText(""))
                            .name(n.path("toolName").asText())
                            .arguments("{}")
                            .build(),
                    finalText);
            default -> null;
        };
    }

    // Remove trailing AiMessages with unresolved tool calls (incomplete rounds)
    private void sanitize(List<ChatMessage> messages) {
        while (!messages.isEmpty()) {
            ChatMessage last = messages.getLast();
            if (last instanceof AiMessage am && am.hasToolExecutionRequests()) {
                messages.removeLast();
            } else {
                break;
            }
        }
    }

    // ── Project root resolution ───────────────────────────────────────────────

    static Path findProjectRoot(Path start) {
        Path current = start.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current.resolve("Start.java"))
                    || Files.exists(current.resolve("PLAN.md"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }
}
