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
    private final ContextBudget configuredHistoryBudget;
    private final int minimumSummaryInputCharacters;
    private final java.util.function.Consumer<String> progress;

    public AgentRuntime(ModelClient model, List<Tool> tools, int maxModelCalls) {
        this(model, tools, maxModelCalls, new ContextBudget(ContextBudget.DEFAULT_MAX_HISTORY_CHARACTERS));
    }

    public AgentRuntime(ModelClient model, List<Tool> tools, int maxModelCalls, ContextBudget contextBudget) {
        this(model, tools, maxModelCalls, contextBudget, message -> { });
    }

    public AgentRuntime(ModelClient model, List<Tool> tools, int maxModelCalls, ContextBudget contextBudget,
                        java.util.function.Consumer<String> progress) {
        this(model, tools, maxModelCalls, contextBudget, ContextCompactor.DEFAULT_MIN_INPUT_CHARACTERS, progress);
    }

    public AgentRuntime(ModelClient model, List<Tool> tools, int maxModelCalls, ContextBudget contextBudget,
                        int minimumSummaryInputCharacters, java.util.function.Consumer<String> progress) {
        if (minimumSummaryInputCharacters <= 0) throw new IllegalArgumentException("Minimum summary input must be positive");
        this.minimumSummaryInputCharacters = minimumSummaryInputCharacters;
        this.model = Objects.requireNonNull(model);
        this.progress = Objects.requireNonNull(progress);
        this.configuredHistoryBudget = contextBudget;
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
                var prepared = prepareContext(session);
                int callsBeforeCompaction = session.modelCalls();
                compactIfNeeded(session, prepared.historyBudget());
                if (session.modelCalls() != callsBeforeCompaction) { prepared = prepareContext(session); }
                var context = prepared.request();
                if (prepared.report() != null) {
                    var report = prepared.report();
                    session.append(REQUEST_BUDGET_CHECKED, "estimator=utf8-bytes, estimatedInputTokens="
                            + report.getEstimatedInputTokens() + ", outputTokens=" + report.getOutputTokens()
                            + ", safetyMarginTokens=" + report.getSafetyMarginTokens()
                            + ", contextWindowTokens=" + report.getContextWindowTokens(), report);
                }
                session.append(CONTEXT_ASSEMBLED, "historyCharacters=" + context.historyCharacters()
                        + ", includedExchanges=" + context.history().size()
                        + ", omittedExchanges=" + context.omittedExchanges()
                        + ", memoryFacts=" + context.workingMemory().size()
                        + ", compressedExchanges=" + context.contextProjection().getCompressedExchanges()
                        + ", filteredExchanges=" + context.contextProjection().getFilteredExchanges(),
                        context.contextProjection());
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
                    if (call.name().equals("run_tests")) {
                        result = new ToolResult(result.successful(), "workspaceRevision="
                                + session.workspaceState().getRevision() + "\n" + result.content());
                    }
                    session.remember(new ToolExchange(call, result, response.getAssistantContent(),
                            session.modelCalls()));
                    session.append(result.successful() ? TOOL_EXECUTION_SUCCEEDED : TOOL_EXECUTION_FAILED,
                            "callId=" + call.id() + ", content=" + result.content(), session.history().getLast());
                    var completed = session.history().getLast();
                    session.retainHistoricalEvidence(completed);
                    if (WorkspaceState.isSuccessfulWrite(completed)) {
                        session.append(WORKSPACE_REVISION_ADVANCED, "callId=" + call.id()
                                + ", revision=" + session.workspaceState().getRevision()
                                + ", testStatus=" + session.workspaceState().testStatus(), session.workspaceState());
                    } else if (call.name().equals("run_tests")) {
                        session.append(TEST_RESULT_RECORDED, "callId=" + call.id()
                                + ", revision=" + session.workspaceState().getRevision()
                                + ", testStatus=" + session.workspaceState().testStatus(), session.workspaceState());
                    }
                }
                session.transitionTo(RUNNING);
                session.releaseOldBodies();
                session.checkpoint();
            }
        } catch (PersistenceException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            session.transitionTo(FAILED);
            session.append(SESSION_FAILED, failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    private static final class PreparedContext {
        private final dev.backendagent.model.ModelRequest request;
        private final ContextBudget historyBudget;
        private final dev.backendagent.model.RequestBudgetReport report;
        private PreparedContext(dev.backendagent.model.ModelRequest request, ContextBudget historyBudget,
                                dev.backendagent.model.RequestBudgetReport report) {
            this.request = request;
            this.historyBudget = historyBudget;
            this.report = report;
        }
        private dev.backendagent.model.ModelRequest request() { return request; }
        private ContextBudget historyBudget() { return historyBudget; }
        private dev.backendagent.model.RequestBudgetReport report() { return report; }
    }

    private PreparedContext prepareContext(AgentSession session) {
        return prepareContext(session, session.contextSummary(), true);
    }

    private PreparedContext prepareContext(AgentSession session, ContextSummary summary, boolean install) {
        var definitions = tools.values().stream().map(Tool::definition).toList();
        int remaining = maxModelCalls - session.modelCalls();
        var initial = contextAssembler.preview(session, definitions, remaining, summary);
        var report = model.inspectRequest(initial);
        if (report == null || report.isWithinBudget()) {
            if (install) { session.installProjection(initial.contextProjection()); }
            return new PreparedContext(initial, configuredHistoryBudget, report);
        }
        // Search only the history allowance. Task, tools, memory, summary and newest batch are protected.
        PreparedContext best = null;
        var raw = session.history();
        int latestStart = raw.size();
        if (!raw.isEmpty()) {
            latestStart--;
            int turn = raw.get(latestStart).modelCallNumber();
            while (turn > 0 && latestStart > 0 && raw.get(latestStart - 1).modelCallNumber() == turn) { latestStart--; }
        }
        long minimumCharacters = initial.historyCharacters() - initial.contextProjection().getHistoryCharacters();
        var filtered = StaleObservationFilter.filter(raw);
        for (int i = latestStart; i < raw.size(); i++) { minimumCharacters += configuredHistoryBudget.measure(filtered.get(i)); }
        int minimumLimit = (int) Math.max(1, minimumCharacters);
        var minimumBudget = new ContextBudget(minimumLimit);
        var minimum = new ContextAssembler(minimumBudget).preview(session, definitions, remaining, summary);
        var minimumReport = model.inspectRequest(minimum);
        if (minimumReport.isWithinBudget()) { best = new PreparedContext(minimum, minimumBudget, minimumReport); }
        int low = minimumLimit;
        int high = configuredHistoryBudget.getMaxHistoryCharacters() - 1;
        while (low <= high) {
            int mid = low + (high - low) / 2;
            var candidateBudget = new ContextBudget(mid);
            dev.backendagent.model.ModelRequest candidate;
            try { candidate = new ContextAssembler(candidateBudget).preview(session, definitions, remaining, summary); }
            catch (IllegalStateException protectedContentTooLarge) { low = mid + 1; continue; }
            var candidateReport = model.inspectRequest(candidate);
            if (candidateReport.isWithinBudget()) {
                best = new PreparedContext(candidate, candidateBudget, candidateReport);
                low = mid + 1;
            } else { high = mid - 1; }
        }
        if (best == null) {
            throw new IllegalStateException("Full request budget cannot fit protected task, tools, memory, summary or latest batch; "
                    + "reduce input or adjust model.context-window-tokens, model.max-output-tokens and model.safety-margin-tokens");
        }
        if (install) { session.installProjection(best.request().contextProjection()); }
        return best;
    }

    private void compactIfNeeded(AgentSession session, ContextBudget effectiveHistoryBudget) {
        // Keep one call available for continuing the user's task; fake/legacy clients remain functional.
        if (!model.supportsSummarization() || maxModelCalls - session.modelCalls() < 2) { return; }
        var contextCompactor = new ContextCompactor(effectiveHistoryBudget, minimumSummaryInputCharacters);
        var plan = contextCompactor.decide(session);
        if (plan.isSkipped()) {
            session.append(WINDOW_SUMMARY_SKIPPED, Integer.toString(plan.getProcessedEnd()), plan);
            session.checkpoint();
            progress.accept("本段历史过短或没有有效候选，跳过模型摘要，原文仍可检索。");
            return;
        }
        var request = plan.getRequest();
        if (request == null) { return; }
        session.append(WINDOW_SUMMARY_ATTEMPTED, Integer.toString(request.getToIndex()));
        session.transitionTo(COMPACTING);
        session.countModelCall();
        session.append(COMPACTION_STARTED, "call=" + session.modelCalls() + ", fromIndex="
                + request.getFromIndex() + ", toIndex=" + request.getToIndex());
        progress.accept("正在压缩上下文，请稍候……（摘要调用计入模型调用预算）");
        ContextSummary candidate;
        SummaryDeduplicator.Result deduplication;
        var failureStage = SummaryRejection.Stage.MODEL_SUMMARY;
        try {
            var notes = model.summarize(request);
            failureStage = SummaryRejection.Stage.SUMMARY_VALIDATION;
            candidate = contextCompactor.validate(request, notes, session);
            failureStage = SummaryRejection.Stage.DEDUPLICATION;
            deduplication = new SummaryDeduplicator().analyze(notes, session::originalObservation);
            // Source-valid summaries must also leave room for the next complete outgoing request.
            failureStage = SummaryRejection.Stage.REQUEST_BUDGET;
            prepareContext(session, candidate, false);
        } catch (PersistenceException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            session.transitionTo(RUNNING);
            var rejection = SummaryRejection.from(failureStage, failure, session.modelCalls(),
                    request.getFromIndex(), request.getToIndex());
            session.append(COMPACTION_FAILED, rejection.detail(), rejection);
            progress.accept("摘要未通过或调用失败，保留原有状态并使用规则裁剪继续。");
            return;
        }
        // Commit only after validation. Persistence errors are fatal, never retried as model errors.
        session.installSummary(candidate);
        session.transitionTo(RUNNING);
        session.append(COMPACTION_COMPLETED, "revision=" + candidate.getRevision()
                + ", coveredExchanges=" + candidate.getCoveredExchanges()
                + ", summaryCharacters=" + candidate.characterCount()
                + ", removedDuplicates=" + deduplication.getRemovedCount()
                + ", suspectedDuplicatePairs=" + deduplication.getSuspectedPairs(), candidate);
        session.checkpoint();
        progress.accept("上下文摘要完成，继续处理任务。");
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
