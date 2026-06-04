// Shared file — included via //SOURCES in Agent.java

import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.io.OutputStream;
import java.util.*;

/**
 * MissionRunner — instantiated once per mission (virtual thread).
 * Owns all per-mission state that was previously scattered as local variables
 * in Agent.runMission().
 */
public class MissionRunner {

    private final AgentCore core;
    private final CapabilityRouter router;
    private final BlackboardClient blackboard;

    // Per-mission state (instance fields, not static)
    private int toolCalls          = 0;
    private int delegations        = 0;
    private boolean hadSuccessfulTool = false;
    private Map<String, Integer> toolCallCounts = new HashMap<>();
    private Map<String, String>  localToolMap   = Map.of();

    MissionRunner(AgentCore core, CapabilityRouter router, BlackboardClient blackboard) {
        this.core      = core;
        this.router    = router;
        this.blackboard = blackboard;
    }

    // ── Main mission loop ─────────────────────────────────────────────────────

    String run(ChatMemory memory, String internalSession,
             String correlationId, String originalSession,
             String originalQuery, OutputStream out) throws Exception {

        boolean chatMode   = correlationId == null;
        int maxRounds      = core.config.agent() != null ? core.config.agent().maxRoundsOrDefault()      : 5;
        int maxToolCalls   = core.config.agent() != null ? core.config.agent().maxToolCallsOrDefault()   : 20;
        int maxDelegations = core.config.agent() != null ? core.config.agent().maxDelegationsOrDefault() : 3;

        // Use cached prompt and tools from SkillLoader instance (refreshed on plugin.installed)
        String staticSystemPrompt = core.skillLoader.systemPrompt();

        SkillLoader.DiscoveredTools dt = core.skillLoader.tools();
        List<ToolSpecification> allTools = dt.specs();
        localToolMap = dt.toolToCapability();

        for (int round = 1; round <= maxRounds; round++) {
            // Fix 2: stop offering tools once tool budget is exhausted — forces text answer.
            // Delegation budget is enforced per-request (++delegations > maxDelegations),
            // not here — agents with maxDelegations=0 still need tool access.
            boolean budgetExhausted = toolCalls >= maxToolCalls;

            List<ToolSpecification> tools = budgetExhausted ? null : allTools;

            // Load dynamic instruction warning and timestamp separately to preserve prompt caching
            String dynamicWarning = SkillLoader.getDynamicInstruction(core.config, round, toolCalls);

            List<ChatMessage> msgs = new ArrayList<>();
            msgs.add(SystemMessage.from(staticSystemPrompt));
            List<ChatMessage> history = new ArrayList<>(memory.messages());
            if (dynamicWarning != null && !dynamicWarning.isEmpty()) {
                if (!history.isEmpty()) {
                    int lastIdx = history.size() - 1;
                    ChatMessage lastMsg = history.get(lastIdx);
                    if (lastMsg instanceof ToolExecutionResultMessage toolMsg) {
                        String newText = toolMsg.text() + "\n\n[SYSTEM INSTRUCTION]\n" + dynamicWarning;
                        history.set(lastIdx, ToolExecutionResultMessage.from(toolMsg.id(), toolMsg.toolName(), newText));
                    } else if (lastMsg instanceof UserMessage userMsg) {
                        String newText = userMsg.singleText() + "\n\n[SYSTEM INSTRUCTION]\n" + dynamicWarning;
                        history.set(lastIdx, UserMessage.from(newText));
                    } else {
                        history.add(UserMessage.from("[SYSTEM INSTRUCTION]\n" + dynamicWarning));
                    }
                } else {
                    history.add(UserMessage.from("[SYSTEM INSTRUCTION]\n" + dynamicWarning));
                }
            }
            msgs.addAll(history);

            core.log("DEBUG: message history sequence for session " + internalSession + ":");
            for (int i = 0; i < msgs.size(); i++) {
                ChatMessage m = msgs.get(i);
                if (m instanceof SystemMessage sm) {
                    core.log("  [" + i + "] SYSTEM: " + core.truncate(sm.text(), 80));
                } else if (m instanceof UserMessage um) {
                    core.log("  [" + i + "] USER: " + core.truncate(um.singleText() != null ? um.singleText() : "", 80));
                } else if (m instanceof AiMessage am) {
                    core.log("  [" + i + "] ASSISTANT: text=" + (am.text() != null ? core.truncate(am.text(), 80) : "null") + ", toolCalls=" + am.hasToolExecutionRequests());
                } else if (m instanceof ToolExecutionResultMessage tm) {
                    core.log("  [" + i + "] TOOL_RESULT: name=" + tm.toolName() + ", id=" + tm.id() + ", text=" + core.truncate(tm.text(), 80));
                } else {
                    core.log("  [" + i + "] UNKNOWN (" + m.getClass().getSimpleName() + ")");
                }
            }

            AiMessage ai;
            try {
                core.log("round=" + round + " calling LLM session=" + internalSession);
                Response<AiMessage> resp = (tools != null && !tools.isEmpty())
                        ? core.llm.generate(msgs, tools)
                        : core.llm.generate(msgs);
                ai = resp.content();
                if (ai.hasToolExecutionRequests()) {
                    List<ToolExecutionRequest> sanitizedRequests = new ArrayList<>();
                    boolean modified = false;
                    for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                        if (req.id() == null || req.id().isBlank()) {
                            req = ToolExecutionRequest.builder()
                                    .id("call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16))
                                    .name(req.name())
                                    .arguments(req.arguments())
                                    .build();
                            modified = true;
                        }
                        sanitizedRequests.add(req);
                    }
                    if (modified) {
                        ai = AiMessage.from(sanitizedRequests);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                String errMsg = "LLM call failed: " + e.getMessage();
                core.logError(errMsg);
                core.publishError(errMsg, chatMode, correlationId, originalSession, out);
                return "Error: " + errMsg;
            }
            memory.add(ai);

            if (!ai.hasToolExecutionRequests()) {
                String text = ai.text() != null ? ai.text() : "";
                // Fix 5: Llama/open-source models sometimes emit function-call JSON as plain text
                // when no tools are available (budgetExhausted). Detect and force another round.
                if (core.looksLikeHallucinatedToolCall(text) && round < maxRounds) {
                    core.log("⚠ Hallucinated function call in text output — injecting error to force convergence");
                    memory.add(SystemMessage.from(
                        "Your last response contained raw JSON resembling a function call, but no tool was invoked. " +
                        "You do not have tools available right now. " +
                        "Please write a plain text answer synthesizing everything you have found so far."));
                    continue;
                }
                if (originalSession != null && originalQuery != null && hadSuccessfulTool
                        && !core.looksLikeHallucinatedToolCall(text)) {
                    String cacheKey = "research.cache." + core.normalizeQueryKey(originalQuery);
                    blackboard.write(cacheKey, "session", originalSession, text, 1800, out);
                }
                core.publishAnswer(text, chatMode, correlationId, originalSession, originalQuery, out);
                return text;
            }

            for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                String name = req.name();

                // Fix 6: dedup check BEFORE budget — dedup'd calls must not consume the tool budget
                String argsKey = req.arguments() != null ? req.arguments() : "";
                if (argsKey.length() > 200) argsKey = argsKey.substring(0, 200);
                int callCount = toolCallCounts.merge(name + ":" + argsKey, 1, Integer::sum);
                if (callCount >= 3) {
                    memory.add(ToolExecutionResultMessage.from(req,
                        "Error: this exact tool call has been made " + callCount +
                        " times with identical arguments and returned the same result. " +
                        "Stop repeating it. Use the information you already have to produce your final answer now."));
                    continue;
                }

                if (name.startsWith("agent_") && ++delegations > maxDelegations) {
                    memory.add(ToolExecutionResultMessage.from(req,
                        "Error: max delegations reached (" + maxDelegations + ")"));
                    continue;
                }
                if (!name.startsWith("agent_") && ++toolCalls > maxToolCalls) {
                    memory.add(ToolExecutionResultMessage.from(req,
                        "Error: max tool calls reached (" + maxToolCalls + ")"));
                    continue;
                }

                if (name.startsWith("agent_") && chatMode && originalSession != null) {
                    blackboard.writeConversationContext(memory, originalSession, out);
                }

                core.log((name.startsWith("agent_") ? "→ delegation" : "→ tool")
                        + " name=" + name + " session=" + internalSession);

                // Fix 3: use local per-mission map instead of global static map
                String originalCap = localToolMap.get(name);
                String result;
                if (originalCap == null) {
                    result = "Error: tool `" + name + "` is not available. Only call tools from the provided list.";
                } else {
                    result = router.invokeCapability(originalCap, req.arguments(), originalSession, out);
                }

                core.log("← " + (name.startsWith("agent_") ? "delegation" : "tool")
                        + " result: " + core.truncate(result, 120));

                if (result == null || !result.startsWith("Error:")) hadSuccessfulTool = true;

                // Truncate overly long tool outputs at 4000 characters (64000 for delegations) to prevent context-window bloat
                String processedResult = result != null ? result : "";
                if (processedResult.isBlank()) {
                    processedResult = "[Empty response]";
                }
                int maxLength = name.startsWith("agent_") ? 64000 : 4000;
                if (processedResult.length() > maxLength) {
                    int originalLength = processedResult.length();
                    processedResult = processedResult.substring(0, maxLength)
                        + "\n... [truncated " + (originalLength - maxLength) + " characters for token efficiency]";
                }

                memory.add(ToolExecutionResultMessage.from(req, processedResult));
            }
        }

        // Fallback: pick the last AiMessage with no tool requests (best effort)
        String last = memory.messages().stream()
            .filter(m -> m instanceof AiMessage am && !am.hasToolExecutionRequests())
            .reduce((a, b) -> b)
            .map(m -> ((AiMessage) m).text())
            .orElse("Max rounds reached without a final answer.");
        core.publishAnswer(last, chatMode, correlationId, originalSession, originalQuery, out);
        return last;
    }
}
