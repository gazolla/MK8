///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../../../kernel/KernelEvent.java
//SOURCES ../../../../kernel/Log.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginBase.java

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/**
 * WorkflowEngine — executes multi-task DAGs with parallel layers, data binding, and
 * failure policies. Ported from MK7 (system/workflow-engine + system/workflow-gateway),
 * merged into a single MK8 plugin.
 *
 * Self-contained — depends only on the kernel's shared contracts (capability routing,
 * blackboard, chat.response). It does NOT touch agent/ or kernel/: agents discover
 * system.workflow.submit automatically via SkillLoader (it is a non-internal system
 * capability), the kernel's CapabilityInterceptor routes task invokes/results by
 * correlationId, and the final DAG result is delivered straight to the user as a
 * chat.response (the console already renders it). No shared-runtime change required.
 *
 * Events consumed via subscription: capability.system.workflow.submit/status, workflow.cancel
 * Events received via kernel pendingRoutes: capability.result, capability.error (targeted)
 * Events published: capability.invoke, chat.response, workflow.dag.task.{start,done,failed},
 *                   workflow.dag.{result,partial,error}, blackboard.write, blackboard.purge
 *
 * Concurrency model: each WorkflowState is synchronized independently.
 * Tasks within a workflow are settled inside synchronized(wf) using a fixed-point loop.
 * Tasks across different workflows run concurrently without contention.
 */
public class WorkflowEngine {

    static final String SOURCE_ID = "workflow";

    // ── Domain model ──────────────────────────────────────────────────────────

    enum TaskStatus { PENDING, AWAITING, DONE, FAILED, SKIPPED }

    static class TaskState {
        final String id;
        final String capability;
        final JsonNode inputTemplate;
        final List<String> dependsOn;
        final String onFailureAction;     // "fail" | "retryTask" | "skipTask"
        final int    maxAttempts;         // total dispatch attempts (initial + retries)
        final String onDependencyFailure; // "fail" | "skip" | "skipWithNull"

        volatile TaskStatus status = TaskStatus.PENDING;
        String output;       // raw capability.result payload
        int    retryCount = 0;

        TaskState(String id, String capability, JsonNode inputTemplate,
                  List<String> dependsOn, JsonNode onFailureNode, String onDepFail) {
            this.id            = id;
            this.capability    = capability;
            this.inputTemplate = inputTemplate;
            this.dependsOn     = dependsOn != null ? dependsOn : List.of();

            if (onFailureNode != null && onFailureNode.has("action")) {
                this.onFailureAction = onFailureNode.get("action").asText("fail");
                this.maxAttempts     = onFailureNode.has("maxAttempts")
                        ? onFailureNode.get("maxAttempts").asInt(1) : 1;
            } else {
                this.onFailureAction = "fail";
                this.maxAttempts     = 1;
            }
            this.onDependencyFailure = (onDepFail != null && !onDepFail.isBlank()) ? onDepFail : "fail";
        }
    }

    static class WorkflowState {
        final String workflowId;
        final String correlationId;
        final String sessionId;
        final SequencedMap<String, TaskState> tasks;
        final Map<String, String> corrToTask = new ConcurrentHashMap<>(); // taskCorrId → taskId
        volatile boolean cancelled = false;

        WorkflowState(String workflowId, String correlationId, String sessionId,
                      SequencedMap<String, TaskState> tasks) {
            this.workflowId    = workflowId;
            this.correlationId = correlationId;
            this.sessionId     = sessionId;
            this.tasks         = tasks;
        }

        boolean isTerminal() {
            return tasks.values().stream().allMatch(t ->
                    t.status == TaskStatus.DONE
                    || t.status == TaskStatus.FAILED
                    || t.status == TaskStatus.SKIPPED);
        }
        boolean hasFailures() { return tasks.values().stream().anyMatch(t -> t.status == TaskStatus.FAILED); }
        boolean hasSkipped()  { return tasks.values().stream().anyMatch(t -> t.status == TaskStatus.SKIPPED); }
    }

    // ── Global state ──────────────────────────────────────────────────────────

    static final Map<String, WorkflowState> workflows      = new ConcurrentHashMap<>();
    static final Map<String, String>        corrToWf       = new ConcurrentHashMap<>(); // taskCorrId → workflowId
    static final Map<String, String>        workflowStatus = new ConcurrentHashMap<>(); // workflowId → status
    static final Pattern                    BINDING        = Pattern.compile("\\{\\{(\\w+)\\.output\\.(\\w+)\\}\\}");

    // ── Entry point ───────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        KernelEvent.initLogging();
        log("Starting (persistent)...");
        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, WorkflowEngine::handle);
    }

    static void handle(String json, OutputStream out) throws Exception {
        Log.configure(SOURCE_ID, out);
        KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);
        switch (event.type()) {
            case "capability.system.workflow.submit" -> handleSubmit(event, out);
            case "capability.system.workflow.status" -> handleStatus(event, out);
            case "workflow.cancel"                    -> handleCancel(event, out);
            case "capability.result"                  -> handleTaskResult(event, out, false);
            case "capability.error"                   -> handleTaskResult(event, out, true);
            default -> { /* ignored (incl. capability.bid.request — PluginBase auto-handles) */ }
        }
    }

    // ── system.workflow.submit (absorbed gateway) ─────────────────────────────

    static void handleSubmit(KernelEvent event, OutputStream out) throws Exception {
        try {
            JsonNode payload = KernelEvent.MAPPER.readTree(event.payload());
            // The agent's CapabilityRouter wraps args as {name, input:{dag}}; tolerate both
            // that and a flat {dag}. The LLM may also pass the DAG itself as a JSON string.
            JsonNode input = payload.has("input") ? payload.get("input") : payload;
            JsonNode dag   = input.has("dag")     ? input.get("dag")     : input;
            if (dag.isTextual()) dag = KernelEvent.MAPPER.readTree(dag.asText());

            if (!dag.has("tasks") || !dag.get("tasks").isArray()) {
                replyError(event, "DAG must contain a 'tasks' array", out);
                return;
            }

            String wfId      = UUID.randomUUID().toString();
            String wfCorrId  = UUID.randomUUID().toString();
            String sessionId = event.sessionId();
            int    taskCount = dag.get("tasks").size();

            // Track active_workflow so system.workflow.status (and any UI) can find it.
            if (sessionId != null && !sessionId.isBlank()) {
                String bb = KernelEvent.MAPPER.writeValueAsString(Map.of(
                        "key", "active_workflow", "scope", "session", "scopeId", sessionId,
                        "value", wfId, "ttl", 1800, "tags", List.of("workflow")));
                PluginBase.publishSafe(KernelEvent.of("blackboard.write", bb, SOURCE_ID), out);
            }

            // Immediate confirmation back to the caller (routed by correlationId).
            String confirmation = "Background workflow started (id: " + wfId + ") with "
                    + taskCount + " task(s). I'll report the result here when it finishes.";
            PluginBase.publish(KernelEvent.withCorrelation("capability.result",
                    KernelEvent.MAPPER.writeValueAsString(Map.of("result", confirmation)),
                    SOURCE_ID, event.correlationId(), sessionId), out);

            startWorkflow(dag, wfId, wfCorrId, sessionId, out);

        } catch (Exception e) {
            replyError(event, "workflow submit failed: " + e.getMessage(), out);
        }
    }

    static void startWorkflow(JsonNode dag, String workflowId, String correlationId,
                              String sessionId, OutputStream out) throws Exception {
        JsonNode tasksNode = dag.get("tasks");
        log("dag received workflowId=" + workflowId + " tasks=" + tasksNode.size());

        SequencedMap<String, TaskState> tasks = new LinkedHashMap<>();
        for (var t : tasksNode) {
            var id         = t.get("id").asText();
            var capability = t.get("capability").asText();
            var input      = t.has("input") ? t.get("input") : KernelEvent.MAPPER.createObjectNode();

            var deps = new ArrayList<String>();
            if (t.has("dependsOn")) t.get("dependsOn").forEach(d -> deps.add(d.asText()));

            var onFailure = t.has("onFailure")           ? t.get("onFailure")                  : null;
            var onDepFail = t.has("onDependencyFailure") ? t.get("onDependencyFailure").asText(): "fail";

            tasks.put(id, new TaskState(id, capability, input, deps, onFailure, onDepFail));
        }

        var cycle = detectCycle(tasks);
        if (cycle.isPresent()) {
            String errPayload = KernelEvent.MAPPER.writeValueAsString(Map.of(
                    "workflowId",    workflowId,
                    "correlationId", correlationId != null ? correlationId : "",
                    "reason",        "cycle detected",
                    "cycle",         cycle.get()));
            PluginBase.publish(KernelEvent.withCorrelation(
                    "workflow.dag.error", errPayload, SOURCE_ID, correlationId, sessionId), out);
            workflowStatus.put(workflowId, "ERROR");
            log("cycle detected workflowId=" + workflowId + " nodes=" + cycle.get());
            publishChat(sessionId, "❌ Workflow " + workflowId + " rejected: cycle detected among tasks "
                    + cycle.get(), out);
            return;
        }

        var wf = new WorkflowState(workflowId, correlationId, sessionId, tasks);
        workflows.put(workflowId, wf);
        workflowStatus.put(workflowId, "RUNNING");
        synchronized (wf) { advance(wf, out); }
    }

    static Optional<List<String>> detectCycle(SequencedMap<String, TaskState> tasks) {
        SequencedMap<String, Integer>      inDeg      = new LinkedHashMap<>();
        SequencedMap<String, List<String>> dependents = new LinkedHashMap<>();

        for (var t : tasks.values()) {
            inDeg.putIfAbsent(t.id, 0);
            dependents.putIfAbsent(t.id, new ArrayList<>());
            for (var dep : t.dependsOn) {
                inDeg.merge(t.id, 1, Integer::sum);
                dependents.computeIfAbsent(dep, k -> new ArrayList<>()).add(t.id);
            }
        }

        Queue<String> queue = new LinkedList<>();
        inDeg.forEach((id, deg) -> { if (deg == 0) queue.add(id); });

        var processed = new ArrayList<String>();
        while (!queue.isEmpty()) {
            var id = queue.poll();
            processed.add(id);
            for (var dep : dependents.getOrDefault(id, List.of())) {
                if (inDeg.merge(dep, -1, Integer::sum) == 0) queue.add(dep);
            }
        }

        if (processed.size() == tasks.size()) return Optional.empty();
        var cycle = new ArrayList<>(tasks.keySet());
        cycle.removeAll(processed);
        return Optional.of(cycle);
    }

    // ── workflow.cancel ───────────────────────────────────────────────────────

    static void handleCancel(KernelEvent event, OutputStream out) throws Exception {
        JsonNode p = KernelEvent.MAPPER.readTree(event.payload());
        String workflowId = p.get("workflowId").asText();

        WorkflowState wf = workflows.remove(workflowId);
        if (wf == null) return;

        synchronized (wf) {
            wf.cancelled = true;
            wf.corrToTask.keySet().forEach(corrToWf::remove);
        }
        workflowStatus.put(workflowId, "CANCELLED");
        log("cancelled workflowId=" + workflowId);

        String payload = KernelEvent.MAPPER.writeValueAsString(Map.of(
                "workflowId",    workflowId,
                "correlationId", wf.correlationId != null ? wf.correlationId : "",
                "reason",        "cancelled"));
        PluginBase.publish(KernelEvent.withCorrelation(
                "workflow.dag.error", payload, SOURCE_ID, wf.correlationId, wf.sessionId), out);
        purgeBlackboard(wf, out);
    }

    // ── capability.result / capability.error (task outcomes) ──────────────────

    static void handleTaskResult(KernelEvent event, OutputStream out, boolean isError) throws Exception {
        String corrId = event.correlationId();
        if (corrId == null) return;

        String workflowId = corrToWf.remove(corrId);
        if (workflowId == null) return;

        WorkflowState wf = workflows.get(workflowId);
        if (wf == null || wf.cancelled) return;

        synchronized (wf) {
            String taskId = wf.corrToTask.remove(corrId);
            if (taskId == null) return;

            TaskState task = wf.tasks.get(taskId);
            if (task == null || task.status != TaskStatus.AWAITING) return;

            if (!isError) {
                task.status = TaskStatus.DONE;
                task.output = event.payload();
                log("task " + taskId + " DONE workflowId=" + workflowId);
                PluginBase.publishSafe(KernelEvent.of("workflow.dag.task.done",
                        taskEvent(workflowId, taskId), SOURCE_ID), out);
                writeTaskOutput(wf, taskId, task.output, out);
                advance(wf, out);
            } else {
                if ("retryTask".equals(task.onFailureAction) && task.retryCount < task.maxAttempts - 1) {
                    task.retryCount++;
                    log("task " + taskId + " retry=" + task.retryCount + "/" + (task.maxAttempts - 1)
                            + " workflowId=" + workflowId);
                    dispatchTask(wf, task, out);
                    return; // AWAITING again — do not advance
                }

                if ("skipTask".equals(task.onFailureAction)
                        || ("retryTask".equals(task.onFailureAction) && task.retryCount >= task.maxAttempts - 1)) {
                    task.status = TaskStatus.SKIPPED;
                    log("task " + taskId + " SKIPPED workflowId=" + workflowId);
                } else {
                    task.status = TaskStatus.FAILED;
                    log("task " + taskId + " FAILED workflowId=" + workflowId);
                }
                PluginBase.publishSafe(KernelEvent.of("workflow.dag.task.failed",
                        taskEvent(workflowId, taskId), SOURCE_ID), out);
                advance(wf, out);
            }
        }
    }

    // ── Core advance: settle blocked tasks, dispatch ready, finalize ──────────

    static void advance(WorkflowState wf, OutputStream out) throws Exception {
        if (wf.cancelled) return;

        boolean anyChange;
        do {
            anyChange = false;
            for (TaskState task : wf.tasks.values()) {
                if (task.status != TaskStatus.PENDING) continue;

                boolean waiting   = false;
                boolean hasBadDep = false;
                for (String depId : task.dependsOn) {
                    TaskState dep = wf.tasks.get(depId);
                    if (dep == null) continue;
                    switch (dep.status) {
                        case PENDING, AWAITING -> waiting = true;
                        case FAILED, SKIPPED   -> hasBadDep = true;
                        default -> {} // DONE — ok
                    }
                    if (waiting) break;
                }
                if (waiting) continue;

                anyChange = true;

                if (hasBadDep) {
                    switch (task.onDependencyFailure) {
                        case "skip" -> {
                            task.status = TaskStatus.SKIPPED;
                            log("task " + task.id + " SKIPPED (dep failed, onDepFail=skip)");
                            PluginBase.publishSafe(KernelEvent.of("workflow.dag.task.failed",
                                    taskEvent(wf.workflowId, task.id), SOURCE_ID), out);
                        }
                        case "skipWithNull" -> dispatchTask(wf, task, out);
                        default -> { // "fail"
                            task.status = TaskStatus.FAILED;
                            log("task " + task.id + " FAILED (dep failed, onDepFail=fail)");
                            PluginBase.publishSafe(KernelEvent.of("workflow.dag.task.failed",
                                    taskEvent(wf.workflowId, task.id), SOURCE_ID), out);
                        }
                    }
                } else {
                    dispatchTask(wf, task, out);
                }
            }
        } while (anyChange);

        if (wf.isTerminal()) finalizeWorkflow(wf, out);
    }

    // ── Task dispatch ─────────────────────────────────────────────────────────

    static void dispatchTask(WorkflowState wf, TaskState task, OutputStream out) throws Exception {
        task.status = TaskStatus.AWAITING;

        String taskCorrId = UUID.randomUUID().toString();
        wf.corrToTask.put(taskCorrId, task.id);
        corrToWf.put(taskCorrId, wf.workflowId);

        JsonNode resolvedInput = resolveBindings(wf, task.inputTemplate);

        // Canonical MK8 invoke shape is NESTED: {name, input:{...}} — matches CapabilityRouter
        // (the agent) and the CREATE_PLUGIN.md tool template, which read payload.path("input").
        ObjectNode invoke = KernelEvent.MAPPER.createObjectNode();
        invoke.put("name", task.capability);
        invoke.set("input", resolvedInput != null ? resolvedInput : KernelEvent.MAPPER.createObjectNode());
        invoke.put("workflowId", wf.workflowId);
        if (wf.sessionId != null) invoke.put("sessionId", wf.sessionId);

        log("dispatch task=" + task.id + " cap=" + task.capability + " corrId=" + taskCorrId);

        PluginBase.publish(KernelEvent.withCorrelation("capability.invoke",
                KernelEvent.MAPPER.writeValueAsString(invoke), SOURCE_ID, taskCorrId, wf.sessionId), out);

        PluginBase.publishSafe(KernelEvent.of("workflow.dag.task.start",
                KernelEvent.MAPPER.writeValueAsString(Map.of(
                        "workflowId", wf.workflowId,
                        "taskId",     task.id,
                        "capability", task.capability)), SOURCE_ID), out);
    }

    // ── Data binding — walk JsonNode tree ─────────────────────────────────────

    static JsonNode resolveBindings(WorkflowState wf, JsonNode node) {
        return switch (node) {
            case TextNode tn   -> resolveTextBindings(wf, tn);
            case ObjectNode on -> resolveObjectBindings(wf, on);
            case ArrayNode an  -> resolveArrayBindings(wf, an);
            default            -> node;
        };
    }

    private static JsonNode resolveTextBindings(WorkflowState wf, TextNode tn) {
        var text = tn.asText();
        if (!text.contains("{{")) return tn;
        var m  = BINDING.matcher(text);
        var sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(resolveField(wf, m.group(1), m.group(2))));
        }
        m.appendTail(sb);
        return KernelEvent.MAPPER.getNodeFactory().textNode(sb.toString());
    }

    private static ObjectNode resolveObjectBindings(WorkflowState wf, ObjectNode on) {
        var result = KernelEvent.MAPPER.createObjectNode();
        on.fields().forEachRemaining(e -> result.set(e.getKey(), resolveBindings(wf, e.getValue())));
        return result;
    }

    private static ArrayNode resolveArrayBindings(WorkflowState wf, ArrayNode an) {
        var result = KernelEvent.MAPPER.createArrayNode();
        an.forEach(e -> result.add(resolveBindings(wf, e)));
        return result;
    }

    /**
     * Resolves a {{depId.output.field}} binding to its string value.
     * Capability results are wrapped as { "result": "<text>" }; we try the field directly,
     * then look inside the "result" JSON string, then fall back to the "result" text itself.
     */
    static String resolveField(WorkflowState wf, String depId, String field) {
        var dep = wf.tasks.get(depId);
        if (dep == null || dep.output == null) return "null";
        try {
            var output = KernelEvent.MAPPER.readTree(dep.output);
            var val = output.get(field);
            if (val != null) return nodeToString(val);

            if (output.has("result")) {
                var resultText = output.get("result").asText();
                try {
                    var parsed = KernelEvent.MAPPER.readTree(resultText);
                    val = parsed.get(field);
                    if (val != null) return nodeToString(val);
                } catch (Exception ignored) {}
                if ("result".equals(field)) return resultText;
            }
        } catch (Exception ignored) {}
        return "null";
    }

    static String nodeToString(JsonNode val) { return val.isTextual() ? val.asText() : val.toString(); }

    // ── Finalization ──────────────────────────────────────────────────────────

    static void finalizeWorkflow(WorkflowState wf, OutputStream out) throws Exception {
        workflows.remove(wf.workflowId);

        SequencedMap<String, Object> outputs = new LinkedHashMap<>();
        for (var t : wf.tasks.values()) {
            if (t.status == TaskStatus.DONE && t.output != null) {
                try { outputs.put(t.id, KernelEvent.MAPPER.readTree(t.output)); }
                catch (Exception e) { outputs.put(t.id, t.output); }
            }
        }

        String resultType;
        SequencedMap<String, Object> resultMap = new LinkedHashMap<>();
        resultMap.put("workflowId",    wf.workflowId);
        resultMap.put("sessionId",     wf.sessionId != null ? wf.sessionId : "");
        resultMap.put("correlationId", wf.correlationId != null ? wf.correlationId : "");

        List<String> failed  = wf.tasks.values().stream()
                .filter(t -> t.status == TaskStatus.FAILED).map(t -> t.id).toList();
        List<String> skipped = wf.tasks.values().stream()
                .filter(t -> t.status == TaskStatus.SKIPPED).map(t -> t.id).toList();

        if (wf.hasFailures()) {
            resultType = "workflow.dag.error";
            resultMap.put("failedTasks", failed);
        } else if (wf.hasSkipped()) {
            resultType = "workflow.dag.partial";
            resultMap.put("outputs", outputs);
            resultMap.put("skipped", skipped);
        } else {
            resultType = "workflow.dag.result";
            resultMap.put("outputs", outputs);
        }

        workflowStatus.put(wf.workflowId, wf.hasFailures() ? "ERROR" : "DONE");
        log("workflow " + wf.workflowId + " → " + resultType);

        PluginBase.publish(KernelEvent.withCorrelation(
                resultType, KernelEvent.MAPPER.writeValueAsString(resultMap),
                SOURCE_ID, wf.correlationId, wf.sessionId), out);

        publishChat(wf.sessionId, renderSummary(wf, resultType, outputs, failed, skipped), out);
        purgeBlackboard(wf, out);
    }

    /** Human-readable summary delivered to the chat (console renders chat.response). */
    static String renderSummary(WorkflowState wf, String resultType, Map<String, Object> outputs,
                                List<String> failed, List<String> skipped) {
        StringBuilder s = new StringBuilder();
        switch (resultType) {
            case "workflow.dag.error"   -> s.append("❌ Workflow ").append(wf.workflowId)
                    .append(" failed. Failed tasks: ").append(failed);
            case "workflow.dag.partial" -> s.append("⚠️ Workflow ").append(wf.workflowId)
                    .append(" finished with skipped tasks: ").append(skipped);
            default                     -> s.append("✅ Workflow ").append(wf.workflowId)
                    .append(" completed.");
        }
        if (!outputs.isEmpty()) {
            s.append("\n\nResults:");
            outputs.forEach((taskId, payload) -> s.append("\n• ").append(taskId).append(": ")
                    .append(extractResult(payload)));
        }
        return s.toString();
    }

    /** Best-effort readable value: unwrap {result:"..."} (and nested JSON) to a short string. */
    static String extractResult(Object payload) {
        try {
            JsonNode node = (payload instanceof JsonNode jn) ? jn
                    : KernelEvent.MAPPER.valueToTree(payload);
            if (node.has("result")) {
                String r = node.get("result").asText();
                try { return KernelEvent.MAPPER.readTree(r).toString(); } catch (Exception ignored) { return r; }
            }
            return node.toString();
        } catch (Exception e) {
            return String.valueOf(payload);
        }
    }

    // ── status ────────────────────────────────────────────────────────────────

    static void handleStatus(KernelEvent event, OutputStream out) throws Exception {
        JsonNode input = KernelEvent.MAPPER.readTree(event.payload());
        if (input.has("input")) input = input.get("input");
        String wfId = input.has("workflowId") ? input.get("workflowId").asText(null) : null;
        Object status = wfId != null ? workflowStatus.getOrDefault(wfId, "UNKNOWN") : workflowStatus;
        String result = KernelEvent.MAPPER.writeValueAsString(status);
        PluginBase.publish(KernelEvent.withCorrelation("capability.result",
                KernelEvent.MAPPER.writeValueAsString(Map.of("result", result)),
                SOURCE_ID, event.correlationId(), event.sessionId()), out);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    static void publishChat(String sessionId, String text, OutputStream out) {
        if (sessionId == null || sessionId.isBlank()) return;
        try {
            String payload = KernelEvent.MAPPER.writeValueAsString(Map.of("response", text));
            PluginBase.publish(KernelEvent.withSession("chat.response", payload, SOURCE_ID, sessionId), out);
        } catch (Exception ignored) {}
    }

    static void replyError(KernelEvent origin, String reason, OutputStream out) throws Exception {
        PluginBase.publish(KernelEvent.withCorrelation("capability.error",
                KernelEvent.MAPPER.writeValueAsString(Map.of("reason", reason)),
                SOURCE_ID, origin.correlationId(), origin.sessionId()), out);
    }

    static void writeTaskOutput(WorkflowState wf, String taskId, String output, OutputStream out) {
        try {
            String key     = "workflow." + wf.workflowId + ".output." + taskId;
            String payload = KernelEvent.MAPPER.writeValueAsString(Map.of(
                    "key",     key,
                    "scope",   "workflow",
                    "scopeId", wf.workflowId,
                    "value",   KernelEvent.MAPPER.readTree(output),
                    "ttl",     3600,
                    "tags",    List.of("workflow", wf.workflowId, taskId)));
            PluginBase.publishSafe(KernelEvent.of("blackboard.write", payload, SOURCE_ID), out);
        } catch (Exception ignored) {}
    }

    static void purgeBlackboard(WorkflowState wf, OutputStream out) {
        try {
            String payload = KernelEvent.MAPPER.writeValueAsString(
                    Map.of("scope", "workflow", "scopeId", wf.workflowId));
            PluginBase.publishSafe(KernelEvent.of("blackboard.purge", payload, SOURCE_ID), out);
        } catch (Exception ignored) {}
    }

    static String taskEvent(String workflowId, String taskId) throws Exception {
        return KernelEvent.MAPPER.writeValueAsString(Map.of("workflowId", workflowId, "taskId", taskId));
    }

    static void log(String msg) { Log.rawInfo("[WORKFLOW] " + msg); }
}
