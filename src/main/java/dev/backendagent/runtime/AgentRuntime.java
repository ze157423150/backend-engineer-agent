package dev.backendagent.runtime;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import dev.backendagent.model.ModelClient;
import dev.backendagent.model.ModelResponse;
import dev.backendagent.model.ToolCall;
import dev.backendagent.model.ToolExchange;
import dev.backendagent.model.ToolResult;
import dev.backendagent.tools.Tool;
import dev.backendagent.persistence.PersistenceException;

import static dev.backendagent.runtime.AgentSession.EventType.*;
import static dev.backendagent.runtime.AgentSession.Status.*;

/** Synchronous, single-worker runtime with an optional persistent session event sink. */
public final class AgentRuntime {
    private final ModelClient model;
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final ContextAssembler contextAssembler;
    private final int maxModelCalls;

    public AgentRuntime(ModelClient model, List<Tool> tools, int maxModelCalls) {
        this(model, tools, maxModelCalls, new ContextBudget(ContextBudget.DEFAULT_MAX_HISTORY_CHARACTERS));
    }

    public AgentRuntime(ModelClient model, List<Tool> tools, int maxModelCalls, ContextBudget contextBudget) {
        this.model = Objects.requireNonNull(model);
        this.contextAssembler = new ContextAssembler(contextBudget);
        if (maxModelCalls <= 0) {
            throw new IllegalArgumentException("maxModelCalls must be positive");
        }
        this.maxModelCalls = maxModelCalls;
        for (Tool tool : tools) {
            String name = Objects.requireNonNull(tool.name());
            if (name.isBlank() || this.tools.putIfAbsent(name, tool) != null) {
                throw new IllegalArgumentException("Invalid or duplicate tool name: " + name);
            }
        }
    }

    public void run(AgentSession session) {
        // Outside the catch: attempting to rerun a terminal session is a caller error.
        session.transitionTo(RUNNING);
        session.append(SESSION_STARTED, session.objective());
        executeLoop(session);
    }

    public void resume(AgentSession session) {
        if (maxModelCalls <= session.modelCalls()) {
            throw new IllegalArgumentException("Resume requires --max-model-calls greater than already used calls");
        }
        session.resume();
        executeLoop(session);
    }

    private void executeLoop(AgentSession session) {
        Set<String> seenCallIds = new HashSet<>();
        session.history().forEach(exchange -> seenCallIds.add(exchange.call().id()));
        try {
            while (session.status() == RUNNING) {
                if (session.modelCalls() >= maxModelCalls) {
                    session.transitionTo(AgentSession.Status.BUDGET_EXHAUSTED);
                    session.append(AgentSession.EventType.BUDGET_EXHAUSTED, "Model call limit reached");
                    session.checkpoint();
                    return;
                }
                var context = contextAssembler.assemble(session,
                        tools.values().stream().map(Tool::definition).toList(),
                        maxModelCalls - session.modelCalls());
                session.append(CONTEXT_ASSEMBLED, "historyCharacters=" + context.historyCharacters()
                        + ", includedExchanges=" + context.history().size()
                        + ", omittedExchanges=" + context.omittedExchanges()
                        + ", memoryFacts=" + context.workingMemory().size());
                session.countModelCall();
                session.append(MODEL_CALL_STARTED, "call=" + session.modelCalls());
                ModelResponse response = Objects.requireNonNull(model.execute(context));
                session.append(MODEL_RESPONSE_RECEIVED, response.toString(), response);

                if (response.getType() == ModelResponse.Type.FINISH) {
                    session.finish(response.getAnswer());
                    session.transitionTo(COMPLETED);
                    session.append(SESSION_COMPLETED, response.getAnswer());
                    return;
                }

                Set<String> batchIds = new HashSet<>();
                for (ToolCall call : response.getToolCalls()) {
                    if (seenCallIds.contains(call.id()) || !batchIds.add(call.id())) {
                        throw new IllegalStateException("Duplicate tool call id: " + call.id());
                    }
                }
                seenCallIds.addAll(batchIds);
                session.transitionTo(WAITING_FOR_TOOL);
                for (ToolCall call : response.getToolCalls()) {
                    session.append(TOOL_CALL_REQUESTED, call.toString(), call);
                    ToolResult result = executeTool(call);
                    session.remember(new ToolExchange(call, result, response.getAssistantContent(),
                            session.modelCalls()));
                    session.append(result.successful() ? TOOL_EXECUTION_SUCCEEDED : TOOL_EXECUTION_FAILED,
                            "callId=" + call.id() + ", content=" + result.content(), session.history().getLast());
                }
                session.transitionTo(RUNNING);
                session.checkpoint();
            }
        } catch (PersistenceException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            session.transitionTo(FAILED);
            session.append(SESSION_FAILED, failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    private ToolResult executeTool(ToolCall call) {
        Tool tool = tools.get(call.name());
        if (tool == null) {
            return new ToolResult(false, "Unknown tool: " + call.name());
        }
        try {
            return Objects.requireNonNull(tool.execute(call.arguments()));
        } catch (PersistenceException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            // A tool error becomes context for the next model turn instead of ending the loop.
            return new ToolResult(false, failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }
}
