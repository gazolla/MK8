///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../../../kernel/KernelEvent.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginBase.java

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;

/**
 * DashboardPlugin — MK7 real-time monitoring dashboard.
 * Refactored to an instance-based model with asset caching and export endpoint.
 * Open http://localhost:8080 — 5 tabs: Dashboard, Logs, Blackboard, Trace, Analytics.
 */
public class DashboardPlugin {

    private static final String SOCKET   = KernelEvent.DEFAULT_SOCKET;
    private static final int    PORT     = 8080;
    private static final Path   LOGS_DIR = Path.of("logs");
    private static final long   START_TS = System.currentTimeMillis();

    // ── Asset Cache ───────────────────────────────────────────────────────────
    private byte[] htmlBytes;
    private byte[] cssBytes;

    // ── Metrics (Instance Fields) ─────────────────────────────────────────────
    private final AtomicLong totalEvents = new AtomicLong();
    private final AtomicLong errorCount  = new AtomicLong();
    private final AtomicReference<String> lastActivity =
            new AtomicReference<>("Waiting for events...");
    private final ConcurrentLinkedDeque<Long> eventTimes    = new ConcurrentLinkedDeque<>();
    private final List<Long>                  latencySamples = Collections.synchronizedList(new ArrayList<>());
    private final ConcurrentHashMap<String, Long> promptTimes = new ConcurrentHashMap<>();

    // ── Agent & mission state (Instance Fields) ───────────────────────────────
    private final ConcurrentHashMap<String, ObjectNode> agentCards    = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ObjectNode> missionStates = new ConcurrentHashMap<>();

    // ── Workflow DAGs (Instance Fields) ───────────────────────────────────────
    private final ConcurrentHashMap<String, ObjectNode> dagStates = new ConcurrentHashMap<>();

    // ── Capabilities (Instance Fields) ────────────────────────────────────────
    private final ConcurrentHashMap<String, ObjectNode> capStates = new ConcurrentHashMap<>();

    // ── Plugin registry (Instance Fields) ─────────────────────────────────────
    private final ConcurrentHashMap<String, ObjectNode> pluginStates = new ConcurrentHashMap<>();

    // ── Blackboard cache (Instance Fields) ────────────────────────────────────
    private final ConcurrentHashMap<String, ObjectNode> bbCache = new ConcurrentHashMap<>();

    // ── Trace spans (Instance Fields) ─────────────────────────────────────────
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<ObjectNode>> traceSpans =
            new ConcurrentHashMap<>();
    private final Deque<String> recentTraces = new ArrayDeque<>();

    // ── Live logs (Instance Fields) ───────────────────────────────────────────
    private final Deque<ObjectNode> logEntries     = new ArrayDeque<>();
    private final Deque<ObjectNode> liveEventLog   = new ArrayDeque<>();

    // ── Blackboard pending queries (Instance Fields) ──────────────────────────
    private final ConcurrentHashMap<String, CompletableFuture<String>> pendingBbQueries =
            new ConcurrentHashMap<>();

    // ── SSE clients (Instance Fields) ─────────────────────────────────────────
    private final CopyOnWriteArrayList<PrintWriter> sseClients    = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<PrintWriter> logSseClients = new CopyOnWriteArrayList<>();

    // ── Kernel output stream (Instance Field) ─────────────────────────────────
    private volatile OutputStream kernelOut = null;

    // ─────────────────────────────────────────────────────────────────────────
    // Constructor & Resource Loader
    // ─────────────────────────────────────────────────────────────────────────

    public DashboardPlugin() {
        loadResources();
    }

    private void loadResources() {
        try {
            htmlBytes = readResourceFile("dashboard.html");
            cssBytes  = readResourceFile("dashboard.css");
            System.out.println("[DASHBOARD] Resources loaded into memory: html=" + htmlBytes.length + " bytes, css=" + cssBytes.length + " bytes");
        } catch (Exception e) {
            System.err.println("[DASHBOARD] ERROR loading assets: " + e.getMessage());
            e.printStackTrace();
            htmlBytes = ("<html><body><h1>Dashboard Asset Error: " + e.getMessage() + "</h1></body></html>").getBytes(StandardCharsets.UTF_8);
            cssBytes = "".getBytes(StandardCharsets.UTF_8);
        }
    }

    private byte[] readResourceFile(String filename) throws IOException {
        Path file = Path.of(filename);
        if (!Files.exists(file)) {
            // Fallback for execution from project root
            file = Path.of("system/dashboard").resolve(filename);
        }
        if (!Files.exists(file)) {
            throw new FileNotFoundException("Required asset not found: " + filename);
        }
        return Files.readAllBytes(file);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Main Entry Point
    // ─────────────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        new DashboardPlugin().start();
    }

    public void start() throws Exception {
        System.out.println("[DASHBOARD] Starting HTTP server on port " + PORT);
        startHttpServer();
        startMetricsBroadcast();

        // Discover and pre-populate all system plugins and agents
        discoverAndRegisterPlugins();

        // PluginBase.run owns the UDS connection + event loop, registers capabilities, and
        // dispatches each frame to handle() on its own virtual thread (replaces the MK7
        // connectAndRun + manual executor).
        PluginBase.run("plugin.json", SOCKET, this::handle);
    }

    /** Called by PluginBase for every inbound frame (already on a virtual thread). */
    void handle(String json, OutputStream out) throws Exception {
        kernelOut = out; // captured so HTTP handlers can publish (e.g. blackboard.query)
        try { processEvent(KernelEvent.MAPPER.readValue(json, KernelEvent.class)); }
        catch (Exception ignored) {}
    }

    // ─────────────────────────────────────────────────────────────────────────
    // KernelEvent processing
    // ─────────────────────────────────────────────────────────────────────────

    private void processEvent(KernelEvent ev) throws Exception {
        totalEvents.incrementAndGet();
        eventTimes.addLast(System.currentTimeMillis());

        if (ev.traceId() != null) recordSpan(ev);

        String t = ev.type();
        switch (t) {
            case "plugin.register"      -> onPluginRegister(ev);
            case "plugin.ready"         -> onPluginReady(ev);
            case "capability.register"  -> onCapRegister(ev);
            case "capability.invoke"    -> onCapInvoke(ev);
            case "capability.result"    -> onCapResult(ev);
            case "capability.error"     -> onCapError(ev);
            case "agent.spawn"          -> onAgentSpawn(ev);
            case "agent.ready"          -> onAgentReady(ev);
            case "agent.busy"           -> onAgentBusy(ev);
            case "agent.idle"           -> onAgentIdle(ev);
            case "chat.prompt"          -> onChatPrompt(ev);
            case "chat.response"        -> onChatResponse(ev);
            case "workflow.dag"         -> onWorkflowDag(ev);
            case "workflow.dag.result"  -> onWorkflowDone(ev, "DONE");
            case "workflow.dag.error"   -> { onWorkflowDone(ev, "ERROR"); errorCount.incrementAndGet(); }
            case "workflow.dag.task.start" -> onWorkflowTask(ev, "RUNNING");
            case "workflow.dag.task.done"  -> onWorkflowTask(ev, "DONE");
            case "workflow.dag.task.failed"-> { onWorkflowTask(ev, "FAILED"); errorCount.incrementAndGet(); }
            case "blackboard.write"         -> onBbWrite(ev);
            case "blackboard.query.result"  -> onBbQueryResult(ev);
            case "kernel.error"             -> { errorCount.incrementAndGet(); lastActivity.set("Kernel error"); broadcastEvent(ev); }
            default -> {
                if (t.startsWith("blackboard.updated.")) onBbUpdated(ev);
                else if (t.startsWith("log."))           onLogEvent(ev);
            }
        }

        if (!t.startsWith("log.") && !t.startsWith("blackboard.updated.") && !t.startsWith("capability.bid.")) {
            addToLiveLog(ev);
            broadcastParticle(ev.source(), "kernel", eventColor(t), t);
        }
    }

    // ── Plugin ────────────────────────────────────────────────────────────────

    private void onPluginRegister(KernelEvent ev) {
        try {
            JsonNode p  = KernelEvent.MAPPER.readTree(ev.payload());
            String id   = p.has("id")   ? p.get("id").asText()   : (ev.source() != null ? ev.source() : "?");
            String type = p.has("type") ? p.get("type").asText() : "system";
            ObjectNode ps = KernelEvent.MAPPER.createObjectNode();
            ps.put("id", id).put("type", type).put("status", "registered")
              .put("lastTs", System.currentTimeMillis()).put("eventCount", 0L);
            pluginStates.put(id, ps);
            broadcastMsg("pluginUpdate", ps);
            // Auto-create agent card for agent-type plugins (e.g. assistant, researcher)
            if ("agent".equals(type)) {
                ObjectNode card = agentCards.computeIfAbsent(id, this::initAgentCard);
                if (p.has("llm") && p.get("llm").has("model"))
                    card.put("model", p.get("llm").get("model").asText());
                broadcastMsg("agentUpdate", card);
            }
            lastActivity.set("Plugin registered: " + id);
        } catch (Exception ignored) {}
    }

    private void onPluginReady(KernelEvent ev) {
        try {
            String id = ev.source() != null ? ev.source() : "?";
            ObjectNode ps = pluginStates.computeIfAbsent(id, k -> {
                ObjectNode n = KernelEvent.MAPPER.createObjectNode();
                n.put("id", k).put("type", "system").put("eventCount", 0L);
                return n;
            });
            ps.put("status", "ready").put("lastTs", System.currentTimeMillis());
            pluginStates.put(id, ps);
            broadcastMsg("pluginUpdate", ps);
        } catch (Exception ignored) {}
    }

    // ── Capability ────────────────────────────────────────────────────────────

    private void onCapRegister(KernelEvent ev) {
        try {
            JsonNode p    = KernelEvent.MAPPER.readTree(ev.payload());
            String name   = p.path("name").asText();
            String plugin = p.path("pluginId").asText(ev.source() != null ? ev.source() : "?");
            ObjectNode cap = KernelEvent.MAPPER.createObjectNode();
            cap.put("name", name).put("provider", plugin).put("invocations", 0L).put("lastInvokedTs", 0L);
            if (p.has("tags")) cap.set("tags", p.get("tags"));
            capStates.put(name, cap);
            broadcastMsg("capUpdate", cap);
        } catch (Exception ignored) {}
    }

    private void onCapInvoke(KernelEvent ev) {
        try {
            JsonNode p    = KernelEvent.MAPPER.readTree(ev.payload());
            String name   = p.path("capabilityName").asText(p.path("name").asText("?"));
            long ts       = System.currentTimeMillis();
            lastActivity.set("Capability: " + name);

            ObjectNode cap = capStates.computeIfAbsent(name, k -> {
                ObjectNode n = KernelEvent.MAPPER.createObjectNode();
                n.put("name", k).put("provider", ev.source() != null ? ev.source() : "?")
                 .put("invocations", 0L).put("lastInvokedTs", 0L);
                return n;
            });
            cap.put("invocations", cap.path("invocations").asLong() + 1)
               .put("lastInvokedTs", ts)
               .put("invokedBy", ev.source() != null ? ev.source() : "?")
               .put("lastSession", ev.sessionId() != null ? ev.sessionId().substring(0, Math.min(8, ev.sessionId().length())) : "");
            broadcastMsg("capUpdate", cap);

            // Track tool calls on the active mission for this session
            if (ev.sessionId() != null) {
                ObjectNode ms = missionStates.get(ev.sessionId());
                if (ms != null) {
                    ms.put("toolCalls", ms.path("toolCalls").asInt() + 1)
                      .put("lastTool", name).put("lastTs", ts);
                    // Also update the agent card
                    String agentId = ms.path("agentId").asText("");
                    if (!agentId.isEmpty()) {
                        ObjectNode card = agentCards.get(agentId);
                        if (card != null) {
                            card.put("toolCalls", ms.path("toolCalls").asInt()).put("lastTool", name);
                            broadcastMsg("agentUpdate", card);
                        }
                    }
                    broadcastMsg("missionUpdate", ms);
                }
            }
            broadcastParticle(ev.source(), "kernel", "#22d3ee", name);
        } catch (Exception ignored) {}
    }

    private void onCapResult(KernelEvent ev) {
        if (ev.correlationId() != null) {
            var f = pendingBbQueries.remove(ev.correlationId());
            if (f != null) f.complete(ev.payload());
        }
        // Return particle: capability result flows kernel → invoking agent
        if (ev.source() != null) broadcastParticle("kernel", ev.source(), "#10b981", "result");
        trackPlugin(ev);
    }

    private void onCapError(KernelEvent ev) {
        errorCount.incrementAndGet();
        if (ev.correlationId() != null) {
            var f = pendingBbQueries.remove(ev.correlationId());
            if (f != null) f.complete("[]");
        }
    }

    // ── Agent ─────────────────────────────────────────────────────────────────

    private ObjectNode initAgentCard(String id) {
        ObjectNode n = KernelEvent.MAPPER.createObjectNode();
        n.put("id", id).put("status", "IDLE").put("toolCalls", 0).put("spawnedAt", System.currentTimeMillis());
        return n;
    }

    private void onAgentSpawn(KernelEvent ev) {
        try {
            JsonNode p = KernelEvent.MAPPER.readTree(ev.payload());
            String id = p.path("agentId").asText(ev.source() != null ? ev.source() : "agent-?");
            ObjectNode card = initAgentCard(id);
            card.put("status", "SPAWNING");
            agentCards.put(id, card);
            broadcastMsg("agentUpdate", card);
            lastActivity.set("Spawning agent: " + id);
        } catch (Exception ignored) {}
    }

    private void onAgentReady(KernelEvent ev) {
        try {
            JsonNode p = KernelEvent.MAPPER.readTree(ev.payload());
            String id  = p.has("agentId") ? p.get("agentId").asText()
                       : (ev.source() != null ? ev.source() : "?");
            ObjectNode card = agentCards.computeIfAbsent(id, this::initAgentCard);
            card.put("status", "IDLE").put("lastTs", System.currentTimeMillis());
            agentCards.put(id, card);
            broadcastMsg("agentUpdate", card);
            lastActivity.set("Agent ready: " + id);
        } catch (Exception ignored) {}
    }

    private void onAgentBusy(KernelEvent ev) {
        try {
            JsonNode p     = KernelEvent.MAPPER.readTree(ev.payload());
            String agentId = p.path("agentId").asText(ev.source() != null ? ev.source() : "?");
            long ts        = System.currentTimeMillis();

            ObjectNode card = agentCards.computeIfAbsent(agentId, this::initAgentCard);
            card.put("status", "BUSY").put("lastTs", ts).put("toolCalls", 0);
            String sessId = ev.sessionId() != null ? ev.sessionId() : agentId + "-" + ts;
            card.put("currentSession", sessId);
            agentCards.put(agentId, card);
            broadcastMsg("agentUpdate", card);

            // Open a mission record keyed by sessionId
            ObjectNode ms = KernelEvent.MAPPER.createObjectNode();
            ms.put("id", sessId).put("agentId", agentId).put("state", "RUNNING")
              .put("startTs", ts).put("toolCalls", 0).put("lastTs", ts);
            missionStates.put(sessId, ms);
            broadcastMsg("missionUpdate", ms);
            lastActivity.set("Mission started: " + agentId);
        } catch (Exception ignored) {}
    }

    private void onAgentIdle(KernelEvent ev) {
        try {
            JsonNode p     = KernelEvent.MAPPER.readTree(ev.payload());
            String agentId = p.path("agentId").asText(ev.source() != null ? ev.source() : "?");
            long ts        = System.currentTimeMillis();

            ObjectNode card = agentCards.computeIfAbsent(agentId, this::initAgentCard);
            String sessId  = card.path("currentSession").asText("");
            card.put("status", "IDLE").put("lastTs", ts);
            agentCards.put(agentId, card);
            broadcastMsg("agentUpdate", card);

            if (!sessId.isEmpty()) {
                ObjectNode ms = missionStates.remove(sessId);
                if (ms != null) {
                    ms.put("state", "DONE").put("endTs", ts);
                    broadcastMsg("missionUpdate", ms);
                }
            }
            lastActivity.set("Agent idle: " + agentId);
        } catch (Exception ignored) {}
    }

    // ── Chat ──────────────────────────────────────────────────────────────────

    private void onChatPrompt(KernelEvent ev) {
        if (ev.sessionId() != null) promptTimes.put(ev.sessionId(), System.currentTimeMillis());
        lastActivity.set("Prompt: " + trunc(ev.payload(), 70));
        broadcastParticle("console", "kernel", "#6366f1", "chat.prompt");
    }

    private void onChatResponse(KernelEvent ev) {
        if (ev.sessionId() != null) {
            Long t0 = promptTimes.remove(ev.sessionId());
            if (t0 != null) {
                long ms = System.currentTimeMillis() - t0;
                synchronized (latencySamples) {
                    latencySamples.add(ms);
                    if (latencySamples.size() > 10) latencySamples.remove(0);
                }
            }
        }
        lastActivity.set("Response sent");
    }

    // ── Workflow ──────────────────────────────────────────────────────────────

    private void onWorkflowDag(KernelEvent ev) {
        try {
            JsonNode p  = KernelEvent.MAPPER.readTree(ev.payload());
            String wfId = p.path("workflowId").asText(
                          ev.workflowId() != null ? ev.workflowId()
                        : ev.correlationId() != null ? ev.correlationId()
                        : UUID.randomUUID().toString().substring(0, 8));

            ObjectNode dag = KernelEvent.MAPPER.createObjectNode();
            dag.put("workflowId", wfId).put("status", "RUNNING").put("startTs", System.currentTimeMillis());

            // Deep-copy tasks as mutable ObjectNodes with initial state = PENDING
            ArrayNode tasksArr = KernelEvent.MAPPER.createArrayNode();
            if (p.has("tasks")) {
                for (JsonNode t : p.get("tasks")) {
                    ObjectNode task = t.deepCopy();
                    if (!task.has("state")) task.put("state", "PENDING");
                    tasksArr.add(task);
                }
            }
            dag.set("tasks", tasksArr);
            dagStates.put(wfId, dag);
            broadcastMsg("dagUpdate", dag);
            lastActivity.set("Workflow started: " + wfId.substring(0, Math.min(12, wfId.length())));
        } catch (Exception ignored) {}
    }

    private void onWorkflowTask(KernelEvent ev, String taskStatus) {
        try {
            JsonNode p    = KernelEvent.MAPPER.readTree(ev.payload());
            String wfId   = p.path("workflowId").asText(ev.workflowId() != null ? ev.workflowId() : "");
            String taskId = p.path("taskId").asText("");
            String capName = p.path("capability").asText("");

            ObjectNode dag = dagStates.get(wfId);
            if (dag == null) return;

            if (dag.has("tasks")) {
                for (JsonNode task : dag.get("tasks")) {
                    if (task instanceof ObjectNode ot && taskId.equals(ot.path("id").asText())) {
                        ot.put("state", taskStatus);
                        if (!capName.isEmpty()) ot.put("capability", capName);
                    }
                }
            }
            dag.put("lastTs", System.currentTimeMillis());
            broadcastMsg("dagUpdate", dag);
        } catch (Exception ignored) {}
    }

    private void onWorkflowDone(KernelEvent ev, String finalStatus) {
        try {
            String wfId = ev.workflowId() != null ? ev.workflowId() : "";
            ObjectNode dag = dagStates.get(wfId);
            if (dag != null) {
                dag.put("status", finalStatus).put("endTs", System.currentTimeMillis());
                broadcastMsg("dagUpdate", dag);
                dagStates.remove(wfId);
            }
            lastActivity.set("Workflow " + finalStatus.toLowerCase() + ": " + wfId);
        } catch (Exception ignored) {}
    }

    // ── Blackboard ────────────────────────────────────────────────────────────

    private void onBbWrite(KernelEvent ev) {
        try {
            JsonNode p     = KernelEvent.MAPPER.readTree(ev.payload());
            String key     = p.path("key").asText("?");
            String scope   = p.path("scope").asText("global");
            String scopeId = p.path("scopeId").asText("");
            String ck      = scope + ":" + scopeId + ":" + key;
            ObjectNode entry = KernelEvent.MAPPER.createObjectNode();
            entry.put("compositeKey", ck).put("key", key).put("scope", scope).put("scopeId", scopeId);
            entry.put("value", p.path("value").toString()).put("ttl", p.path("ttl").asLong(0))
                 .put("updatedTs", System.currentTimeMillis());
            long ver = p.path("expectedVersion").asLong(-1);
            entry.put("version", ver >= 0 ? ver + 1 : 1);
            if (p.has("tags")) entry.set("tags", p.get("tags"));
            bbCache.put(ck, entry);
            broadcastMsg("bbUpdate", entry);
        } catch (Exception ignored) {}
    }

    private void onBbUpdated(KernelEvent ev) {
        lastActivity.set("Blackboard: " + ev.type().substring("blackboard.updated.".length()));
    }

    private void onBbQueryResult(KernelEvent ev) {
        String corrId = ev.correlationId();
        if (corrId != null) {
            var f = pendingBbQueries.remove(corrId);
            if (f != null) f.complete(ev.payload());
        }
    }

    // ── Logs ──────────────────────────────────────────────────────────────────

    private void onLogEvent(KernelEvent ev) {
        try {
            String level  = ev.type().startsWith("log.") ? ev.type().substring(4).toUpperCase() : "INFO";
            String source = ev.source() != null ? ev.source() : "?";
            String msg    = ev.payload();
            String text   = msg;
            try {
                JsonNode p = KernelEvent.MAPPER.readTree(msg);
                if (p.has("message")) text = p.get("message").asText();
            } catch (Exception ignored) {}

            ObjectNode entry = KernelEvent.MAPPER.createObjectNode();
            entry.put("level", level).put("source", source).put("message", trunc(text, 200))
                 .put("ts", System.currentTimeMillis());
            synchronized (logEntries) {
                logEntries.addLast(entry);
                if (logEntries.size() > 500) logEntries.removeFirst();
            }
            // Broadcast to log SSE clients
            String line = "data: " + KernelEvent.MAPPER.writeValueAsString(entry) + "\n\n";
            List<PrintWriter> dead = new ArrayList<>();
            for (PrintWriter w : logSseClients) {
                synchronized (w) { w.print(line); w.flush(); if (w.checkError()) dead.add(w); }
            }
            logSseClients.removeAll(dead);
        } catch (Exception ignored) {}
    }

    // ── Trace ─────────────────────────────────────────────────────────────────

    private void recordSpan(KernelEvent ev) {
        try {
            String tid = ev.traceId();
            ConcurrentLinkedDeque<ObjectNode> spans = traceSpans.computeIfAbsent(tid, k -> new ConcurrentLinkedDeque<>());
            ObjectNode span = KernelEvent.MAPPER.createObjectNode();
            span.put("spanId", ev.spanId() != null ? ev.spanId() : "")
                .put("traceId", tid).put("type", ev.type())
                .put("source", ev.source() != null ? ev.source() : "?")
                .put("ts", System.currentTimeMillis());
            spans.addLast(span);
            if (spans.size() > 100) spans.pollFirst();
            synchronized (recentTraces) {
                recentTraces.remove(tid);
                recentTraces.addFirst(tid);
                while (recentTraces.size() > 50) recentTraces.pollLast();
            }
        } catch (Exception ignored) {}
    }

    // ── Plugin activity ───────────────────────────────────────────────────────

    private void trackPlugin(KernelEvent ev) {
        String src = ev.source();
        if (src == null || src.isBlank()) return;
        ObjectNode ps = pluginStates.computeIfAbsent(src, k -> {
            ObjectNode n = KernelEvent.MAPPER.createObjectNode();
            n.put("id", k).put("type", "system").put("status", "active").put("eventCount", 0L);
            return n;
        });
        ps.put("lastTs", System.currentTimeMillis())
          .put("lastEvent", ev.type())
          .put("eventCount", ps.path("eventCount").asLong() + 1);
    }

    // ── Live event log ────────────────────────────────────────────────────────

    private void addToLiveLog(KernelEvent ev) {
        ObjectNode entry = KernelEvent.MAPPER.createObjectNode();
        entry.put("type", ev.type())
             .put("source", ev.source() != null ? ev.source() : "")
             .put("payload", trunc(ev.payload() != null ? ev.payload() : "", 120))
             .put("session", ev.sessionId() != null ? ev.sessionId().substring(0, Math.min(8, ev.sessionId().length())) : "")
             .put("ts", System.currentTimeMillis());
        synchronized (liveEventLog) {
            liveEventLog.addLast(entry);
            if (liveEventLog.size() > 100) liveEventLog.removeFirst();
        }
        broadcastMsg("eventLog", entry);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP Server & Handlers
    // ─────────────────────────────────────────────────────────────────────────

    private void startHttpServer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 32);
        server.createContext("/",              this::serveIndex);
        server.createContext("/events",        this::serveSSE);
        server.createContext("/log-events",    this::serveLogSSE);
        server.createContext("/api/state",     this::serveState);
        server.createContext("/api/blackboard",this::serveBlackboard);
        server.createContext("/api/logs",      this::serveLogs);
        server.createContext("/api/trace",     this::serveTrace);
        server.createContext("/api/export",    this::serveExport);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("[DASHBOARD] HTTP ready → http://localhost:" + PORT);
    }

    private void serveIndex(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) { ex.sendResponseHeaders(405, -1); return; }
        
        String path = ex.getRequestURI().getPath();
        if (path.equals("/dashboard.css")) {
            ex.getResponseHeaders().set("Content-Type", "text/css; charset=utf-8");
            ex.sendResponseHeaders(200, cssBytes.length);
            try (var out = ex.getResponseBody()) { out.write(cssBytes); }
        } else {
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, htmlBytes.length);
            try (var out = ex.getResponseBody()) { out.write(htmlBytes); }
        }
    }

    private void serveSSE(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type",  "text/event-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(200, 0);
        var pw = new PrintWriter(new OutputStreamWriter(ex.getResponseBody(), StandardCharsets.UTF_8));
        sseClients.add(pw);
        try {
            // Send initial state snapshot
            synchronized (pw) {
                pw.print("data: " + KernelEvent.MAPPER.writeValueAsString(buildSnapshot()) + "\n\n");
                pw.flush();
            }
            while (true) {
                Thread.sleep(15_000);
                synchronized (pw) { pw.print(": ping\n\n"); pw.flush(); if (pw.checkError()) break; }
            }
        } catch (InterruptedException ignored) {}
        sseClients.remove(pw);
    }

    private void serveLogSSE(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type",  "text/event-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(200, 0);
        var pw = new PrintWriter(new OutputStreamWriter(ex.getResponseBody(), StandardCharsets.UTF_8));
        logSseClients.add(pw);
        // Send buffered log entries
        synchronized (pw) {
            synchronized (logEntries) {
                for (ObjectNode e : logEntries) {
                    try { pw.print("data: " + KernelEvent.MAPPER.writeValueAsString(e) + "\n\n"); } catch (Exception ignored) {}
                }
            }
            pw.flush();
        }
        try {
            while (true) {
                Thread.sleep(20_000);
                synchronized (pw) { pw.print(": ping\n\n"); pw.flush(); if (pw.checkError()) break; }
            }
        } catch (InterruptedException ignored) {}
        logSseClients.remove(pw);
    }

    private void serveState(HttpExchange ex) throws IOException {
        try { sendJson(ex, KernelEvent.MAPPER.writeValueAsString(buildSnapshot())); }
        catch (Exception e) { sendJson(ex, "{}"); }
    }

    private void serveBlackboard(HttpExchange ex) throws IOException {
        // Try live query if kernel is up
        if (kernelOut != null) {
            try {
                String corrId = UUID.randomUUID().toString();
                var future = new CompletableFuture<String>();
                pendingBbQueries.put(corrId, future);
                String qPayload = "{\"scope\":\"all\"}";
                KernelEvent qEv = KernelEvent.withCorrelation("blackboard.query", qPayload, "dashboard", corrId, null);
                synchronized (kernelOut) { KernelEvent.writeFrame(kernelOut, KernelEvent.MAPPER.writeValueAsString(qEv)); }
                String result = future.get(4, TimeUnit.SECONDS);
                sendJson(ex, result != null ? result : buildBbJson());
                return;
            } catch (Exception ignored) {}
        }
        sendJson(ex, buildBbJson());
    }

    private void serveLogs(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String[] parts = path.split("/");
        if (parts.length >= 4) {
            String pluginName = parts[3];
            serveLogFile(ex, pluginName);
        } else {
            // Return list of available log files
            try {
                ArrayNode list = KernelEvent.MAPPER.createArrayNode();
                if (Files.exists(LOGS_DIR)) {
                    try (var stream = Files.list(LOGS_DIR)) {
                        stream.filter(p -> p.toString().endsWith(".log"))
                              .map(p -> p.getFileName().toString())
                              .sorted()
                              .forEach(list::add);
                    }
                }
                sendJson(ex, KernelEvent.MAPPER.writeValueAsString(list));
            } catch (Exception e) { sendJson(ex, "[]"); }
        }
    }

    private void serveLogFile(HttpExchange ex, String name) throws IOException {
        try {
            Path logPath = LOGS_DIR.resolve(name);
            if (!Files.exists(logPath)) logPath = LOGS_DIR.resolve(name + ".log");
            if (!Files.exists(logPath)) { sendJson(ex, "[]"); return; }
            List<String> lines = Files.readAllLines(logPath, StandardCharsets.UTF_8);
            int start = Math.max(0, lines.size() - 500);
            ArrayNode arr = KernelEvent.MAPPER.createArrayNode();
            for (int i = start; i < lines.size(); i++) arr.add(lines.get(i));
            sendJson(ex, KernelEvent.MAPPER.writeValueAsString(arr));
        } catch (Exception e) { sendJson(ex, "[]"); }
    }

    private void serveTrace(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String[] parts = path.split("/");
        try {
            if (parts.length >= 4) {
                String traceId = parts[3];
                var spans = traceSpans.get(traceId);
                if (spans == null) { sendJson(ex, "[]"); return; }
                ArrayNode arr = KernelEvent.MAPPER.createArrayNode();
                spans.forEach(arr::add);
                sendJson(ex, KernelEvent.MAPPER.writeValueAsString(arr));
            } else {
                // List recent traces
                ArrayNode arr = KernelEvent.MAPPER.createArrayNode();
                synchronized (recentTraces) {
                    for (String tid : recentTraces) {
                        ObjectNode t = KernelEvent.MAPPER.createObjectNode();
                        t.put("traceId", tid);
                        var spans = traceSpans.get(tid);
                        t.put("spanCount", spans != null ? spans.size() : 0);
                        if (spans != null && !spans.isEmpty()) {
                            t.put("firstTs", spans.peekFirst().path("ts").asLong());
                            t.put("lastTs",  spans.peekLast().path("ts").asLong());
                        }
                        arr.add(t);
                    }
                }
                sendJson(ex, KernelEvent.MAPPER.writeValueAsString(arr));
            }
        } catch (Exception e) { sendJson(ex, "[]"); }
    }

    private void serveExport(HttpExchange ex) throws IOException {
        try {
            ObjectNode data = KernelEvent.MAPPER.createObjectNode();
            data.put("timestamp", System.currentTimeMillis());
            data.set("metrics", buildSnapshot());
            
            ArrayNode bbArr = KernelEvent.MAPPER.createArrayNode();
            bbCache.values().forEach(bbArr::add);
            data.set("blackboard", bbArr);

            ArrayNode logArr = KernelEvent.MAPPER.createArrayNode();
            synchronized (logEntries) { logEntries.forEach(logArr::add); }
            data.set("logs", logArr);

            ObjectNode trNode = KernelEvent.MAPPER.createObjectNode();
            traceSpans.forEach((tid, list) -> {
                ArrayNode spansArr = KernelEvent.MAPPER.createArrayNode();
                list.forEach(spansArr::add);
                trNode.set(tid, spansArr);
            });
            data.set("traces", trNode);

            sendJson(ex, KernelEvent.MAPPER.writeValueAsString(data));
        } catch (Exception e) {
            sendJson(ex, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Metrics broadcast
    // ─────────────────────────────────────────────────────────────────────────

    private void startMetricsBroadcast() {
        Thread.ofVirtual().start(() -> {
            while (true) {
                try {
                    Thread.sleep(1_000);
                    pruneEventTimes();
                    ObjectNode stats = KernelEvent.MAPPER.createObjectNode();
                    stats.put("kind", "stats");
                    stats.put("uptime",      System.currentTimeMillis() - START_TS);
                    stats.put("totalEvents", totalEvents.get());
                    stats.put("errorCount",  errorCount.get());
                    stats.put("activeAgents",   agentCards.values().stream().filter(c -> !"IDLE".equals(c.path("status").asText())).count());
                    stats.put("loadedPlugins",  pluginStates.size());
                    stats.put("activeMissions", missionStates.size());
                    stats.put("lastActivity",   lastActivity.get());
                    // Events/sec window
                    long now = System.currentTimeMillis();
                    int[] buckets = new int[60];
                    for (Long ts : eventTimes) { int age = (int)((now - ts) / 1000); if (age < 60) buckets[age]++; }
                    ArrayNode eps = KernelEvent.MAPPER.createArrayNode();
                    for (int i = 59; i >= 0; i--) eps.add(buckets[i]);
                    stats.set("eventsPerSec", eps);
                    // Latency
                    synchronized (latencySamples) {
                        stats.put("avgLatency", (long) latencySamples.stream().mapToLong(Long::longValue).average().orElse(0));
                        ArrayNode latArr = KernelEvent.MAPPER.createArrayNode();
                        latencySamples.forEach(latArr::add);
                        stats.set("latencySamples", latArr);
                    }
                    broadcast(KernelEvent.MAPPER.writeValueAsString(stats));
                } catch (Exception ignored) {}
            }
        });
    }

    private void pruneEventTimes() {
        long cut = System.currentTimeMillis() - 61_000;
        while (!eventTimes.isEmpty() && eventTimes.peekFirst() < cut) eventTimes.pollFirst();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Snapshot & helpers
    // ─────────────────────────────────────────────────────────────────────────

    private ObjectNode buildSnapshot() {
        ObjectNode s = KernelEvent.MAPPER.createObjectNode();
        s.put("kind", "snapshot");
        s.put("uptime",       System.currentTimeMillis() - START_TS);
        s.put("totalEvents",  totalEvents.get());
        s.put("errorCount",   errorCount.get());
        s.put("loadedPlugins",pluginStates.size());
        s.put("activeAgents", agentCards.values().stream().filter(c -> !"IDLE".equals(c.path("status").asText())).count());
        s.put("activeMissions", missionStates.size());
        s.put("lastActivity", lastActivity.get());
        
        synchronized (latencySamples) {
            s.put("avgLatency", (long) latencySamples.stream().mapToLong(Long::longValue).average().orElse(0));
            ArrayNode latArr = KernelEvent.MAPPER.createArrayNode();
            latencySamples.forEach(latArr::add);
            s.set("latencySamples", latArr);
        }

        ArrayNode agents = KernelEvent.MAPPER.createArrayNode(); agentCards.values().forEach(agents::add);
        s.set("agents", agents);
        ArrayNode missions = KernelEvent.MAPPER.createArrayNode(); missionStates.values().forEach(missions::add);
        s.set("missions", missions);
        ArrayNode dags = KernelEvent.MAPPER.createArrayNode(); dagStates.values().forEach(dags::add);
        s.set("dags", dags);
        ArrayNode caps = KernelEvent.MAPPER.createArrayNode(); capStates.values().forEach(caps::add);
        s.set("capabilities", caps);
        ArrayNode plugins = KernelEvent.MAPPER.createArrayNode(); pluginStates.values().forEach(plugins::add);
        s.set("plugins", plugins);
        // Live event log
        ArrayNode evLog = KernelEvent.MAPPER.createArrayNode();
        synchronized (liveEventLog) { liveEventLog.forEach(evLog::add); }
        s.set("eventLog", evLog);
        return s;
    }

    private String buildBbJson() {
        try {
            ArrayNode arr = KernelEvent.MAPPER.createArrayNode();
            bbCache.values().forEach(arr::add);
            return KernelEvent.MAPPER.writeValueAsString(arr);
        } catch (Exception e) { return "[]"; }
    }

    private void broadcastMsg(String kind, ObjectNode data) {
        try {
            ObjectNode msg = KernelEvent.MAPPER.createObjectNode();
            msg.put("kind", kind);
            msg.set("data", data);
            broadcast(KernelEvent.MAPPER.writeValueAsString(msg));
        } catch (Exception ignored) {}
    }

    private void broadcastParticle(String src, String dst, String color, String label) {
        try {
            ObjectNode msg = KernelEvent.MAPPER.createObjectNode();
            msg.put("kind", "particle")
               .put("src",   src   != null ? src   : "?")
               .put("dst",   dst   != null ? dst   : "kernel")
               .put("color", color != null ? color : "#94a3b8")
               .put("label", trunc(label != null ? label : "", 25));
            broadcast(KernelEvent.MAPPER.writeValueAsString(msg));
        } catch (Exception ignored) {}
    }

    private void broadcastEvent(KernelEvent ev) {
        try {
            ObjectNode entry = KernelEvent.MAPPER.createObjectNode();
            entry.put("type",    ev.type())
                 .put("source",  ev.source()  != null ? ev.source()  : "")
                 .put("payload", trunc(ev.payload() != null ? ev.payload() : "", 120))
                 .put("ts",      System.currentTimeMillis());
            broadcastMsg("eventLog", entry);
        } catch (Exception ignored) {}
    }

    private void broadcast(String data) {
        String line = "data: " + data + "\n\n";
        List<PrintWriter> dead = new ArrayList<>();
        for (PrintWriter w : sseClients) {
            synchronized (w) { w.print(line); w.flush(); if (w.checkError()) dead.add(w); }
        }
        sseClients.removeAll(dead);
    }

    private void sendJson(HttpExchange ex, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(200, body.length);
        try (var out = ex.getResponseBody()) { out.write(body); }
    }

    private String eventColor(String type) {
        if (type.startsWith("chat."))        return "#6366f1";
        if (type.startsWith("capability.") || type.startsWith("tool.")) return "#22d3ee";
        if (type.startsWith("agent."))       return "#a78bfa";
        if (type.startsWith("workflow."))    return "#f59e0b";
        if (type.startsWith("blackboard.")) return "#fb923c";
        if (type.contains("error"))          return "#ef4444";
        return "#94a3b8";
    }

    private String trunc(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private void discoverAndRegisterPlugins() {
        Path root = projectRoot();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.getFileName().toString().equals("plugin.json"))
                .forEach(pf -> {
                    try {
                        JsonNode json = KernelEvent.MAPPER.readTree(pf.toFile());
                        String id = json.path("id").asText("");
                        String type = json.path("type").asText("system");
                        if (!id.isEmpty()) {
                            // Pre-populate pluginState
                            ObjectNode ps = KernelEvent.MAPPER.createObjectNode();
                            ps.put("id", id).put("type", type).put("status", "registered")
                              .put("lastTs", System.currentTimeMillis()).put("eventCount", 0L);
                            pluginStates.putIfAbsent(id, ps);

                            // Pre-populate agentCard if it's an agent type
                            if ("agent".equals(type)) {
                                ObjectNode card = agentCards.computeIfAbsent(id, this::initAgentCard);
                                if (json.has("llm") && json.get("llm").has("model")) {
                                    card.put("model", json.get("llm").get("model").asText());
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                });
        } catch (Exception ignored) {}
    }

    private Path projectRoot() {
        Path dir = Path.of(System.getProperty("user.dir"));
        if (dir.getFileName().toString().equals("dashboard") && dir.getParent().getFileName().toString().equals("system")) {
            return dir.getParent().getParent();
        }
        if (dir.getFileName().toString().equals("system")) {
            return dir.getParent();
        }
        return dir;
    }
}
