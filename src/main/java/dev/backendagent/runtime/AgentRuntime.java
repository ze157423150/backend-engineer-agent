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
    private dev.backendagent.selection.ToolDiscovery toolDiscovery;
    private final ModelClient model;
    private final boolean verificationBasisEnabled;
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final ContextAssembler contextAssembler;
    private final int maxModelCalls;
    private final Set<String> pressureAttempts=new HashSet<>();
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
        this(model,tools,maxModelCalls,contextBudget,minimumSummaryInputCharacters,progress,true);
    }
    public AgentRuntime(ModelClient model,List<Tool> tools,int maxModelCalls,ContextBudget contextBudget,
            java.util.function.Consumer<String> progress,boolean verificationBasisEnabled) {
        this(model,tools,maxModelCalls,contextBudget,ContextCompactor.DEFAULT_MIN_INPUT_CHARACTERS,progress,verificationBasisEnabled);
    }
    public AgentRuntime(ModelClient model,List<Tool> tools,int maxModelCalls,ContextBudget contextBudget,
            int minimumSummaryInputCharacters,java.util.function.Consumer<String> progress,boolean verificationBasisEnabled) {
        this.verificationBasisEnabled=verificationBasisEnabled;
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
            if(!verificationBasisEnabled && name.equals("pin_verification_basis"))continue;
            if (name.isBlank() || this.tools.putIfAbsent(name, tool) != null) {
                throw new IllegalArgumentException("Invalid or duplicate tool name: " + name);
            }
        }
        if (this.tools.get("apply_patch") instanceof dev.backendagent.tools.ApplyPatchTool patch) {
            this.tools.putIfAbsent("apply_patch_batch", patch.batchTool());
        }
    }

    /** Opt-in experiment; the default remains the complete catalog. */
    public AgentRuntime withToolSelection(boolean enabled) {
        toolDiscovery = enabled ? new dev.backendagent.selection.ToolDiscovery(
                tools.values().stream().map(Tool::definition).toList()) : null;
        return this;
    }

    public AgentRuntime withToolSelection(String mode) {
        return switch (mode) {
            case "all" -> withToolSelection(false);
            case "catalog" -> withToolSelection(true);
            case "rule" -> withToolSelection(new dev.backendagent.selection.RuleToolSelector(), 8);
            default -> throw new IllegalArgumentException("Unknown tool selection mode");
        };
    }

    public AgentRuntime withToolSelection(dev.backendagent.selection.ToolSelector selector, int initialLimit) {
        toolDiscovery = new dev.backendagent.selection.ToolDiscovery(
                tools.values().stream().map(Tool::definition).toList(), selector, initialLimit);
        return this;
    }

    public AgentRuntime withToolSelection(dev.backendagent.selection.ToolSelector selector, int initialLimit,
            dev.backendagent.selection.ToolSearchRanker searchRanker) {
        toolDiscovery = new dev.backendagent.selection.ToolDiscovery(
                tools.values().stream().map(Tool::definition).toList(), selector, initialLimit, searchRanker);
        return this;
    }

    private List<dev.backendagent.tools.ToolDefinition> definitions(AgentSession session) {
        return toolDiscovery == null ? tools.values().stream().map(Tool::definition).toList()
                : toolDiscovery.definitions(session);
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

    public void resumeFailed(AgentSession session, boolean retryInFlight) {
        if (session.status() != FAILED || maxModelCalls <= session.modelCalls()) {
            throw new IllegalArgumentException("Failed resume requires a failed session and additional model-call budget");
        }
        session.resume(retryInFlight);
        executeLoop(session);
    }

    public void continueConversation(AgentSession session, String message) {
        if (session.modelCalls() >= maxModelCalls) throw new IllegalArgumentException("New turn requires additional model-call budget");
        session.continueWith(message);
        executeLoop(session);
    }

    private void executeLoop(AgentSession session) {
        session.setVerificationBasisEnabled(verificationBasisEnabled);
        session.setTestToolAvailable(tools.containsKey("run_tests"));
        Set<String> seenCallIds = new HashSet<>();
        session.history().forEach(exchange -> seenCallIds.addAll(PatchBatchNormalizer.ids(exchange.call())));
        boolean completeToolBatch = true;
        try {
            if (tools.get("workspace_diff") instanceof dev.backendagent.tools.WorkspaceDiffTool diff)
                diff.beginTurn(session.turns().size());
            var noProgress = NoProgressDetector.start(session);
            session.setNoProgressReport(null);
            while (session.status() == RUNNING) {
                if (session.pendingToolBatch() != null) {
                    completeToolBatch = false;
                    session.pendingToolBatch().getCalls().forEach(call -> seenCallIds.addAll(PatchBatchNormalizer.ids(call)));
                    executePendingBatch(session);
                    completeToolBatch = true;
                    var report = noProgress.observeCompletedHistory(session);
                    session.setNoProgressReport(report.getWarningLevel() == 0 ? null : report);
                    if (report.getConsecutiveBatches() == 4 || report.getConsecutiveBatches() == 8) {
                        session.append(NO_PROGRESS_WARNING, "consecutiveBatches=" + report.getConsecutiveBatches()
                                + ", warningLevel=" + report.getWarningLevel() + ", revision=" + report.getWorkspaceRevision(), report);
                        progress.accept("连续" + report.getConsecutiveBatches() + "个工具批次没有新增信息，提醒模型重新决策。");
                        session.checkpoint();
                    }
                    if (report.shouldStop()) {
                        session.append(NO_PROGRESS_STOPPED, "consecutiveBatches=" + report.getConsecutiveBatches()
                                + ", revision=" + report.getWorkspaceRevision() + ", patchFailures=" + report.getPatchFailures(), report);
                        progress.accept("重复探索或同一文件补丁连续失败，已停止；已完成改动保留，可检查原因后显式恢复。");
                        throw new NoProgressException();
                    }
                    continue;
                }
                if (session.modelCalls() >= maxModelCalls) {
                    session.transitionTo(AgentSession.Status.BUDGET_EXHAUSTED);
                    session.append(AgentSession.EventType.BUDGET_EXHAUSTED, "Model call limit reached");
                    session.checkpoint();
                    return;
                }
                maintainContextBeforeTrimming(session);
                var prepared = prepareContext(session);
                if (updateVerificationBasisIfNeeded(session,prepared.request())) {
                    maintainContextBeforeTrimming(session);
                    prepared=prepareContext(session);
                }
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
                ModelResponse response = executeModelWithRetry(session, context);
                if (response.getType() == ModelResponse.Type.TOOL_CALL) {
                    var originalIds = new HashSet<String>();
                    for (var call : response.getToolCalls()) {
                        for (String id : PatchBatchNormalizer.ids(call)) {
                            if (seenCallIds.contains(id) || !originalIds.add(id)) throw new IllegalStateException("Duplicate tool call id: " + id);
                        }
                    }
                    if (tools.get("apply_patch_batch") instanceof dev.backendagent.tools.ApplyPatchBatchTool) {
                        var normalized=PatchBatchNormalizer.normalize(response);
                        if(normalized!=response)session.append(MODEL_RESPONSE_NORMALIZED,"Combined adjacent same-file patches into one durable atomic operation",response);
                        response=normalized;
                    }
                }
                session.append(MODEL_RESPONSE_RECEIVED, response.toString(), response);

                if (response.getType() == ModelResponse.Type.FINISH) {
                    String rejection = new dev.backendagent.verification.CompletionVerifier().rejection(session);
                    if (rejection != null) {
                        session.append(AgentSession.EventType.FINISH_REJECTED,
                                "turn=" + session.turns().size() + "; " + rejection);
                        progress.accept("结束请求被拒绝：" + rejection);
                        session.checkpoint();
                        if (session.completionRejections() > 2) {
                            throw new dev.backendagent.verification.CompletionVerifier.IncompleteVerificationException();
                        }
                        continue;
                    }
                    session.finish(response.getAnswer());
                    session.transitionTo(COMPLETED);
                    session.append(SESSION_COMPLETED, response.getAnswer());
                    session.checkpoint();
                    return;
                }

                Set<String> batchIds = new HashSet<>();
                for (ToolCall call : response.getToolCalls()) {
                    if (seenCallIds.contains(call.id()) || !batchIds.add(call.id())) {
                        throw new IllegalStateException("Duplicate tool call id: " + call.id());
                    }
                }
                seenCallIds.addAll(batchIds);
                completeToolBatch = false;
                session.setPendingToolBatch(new PendingToolBatch(session.modelCalls(),
                        response.getAssistantContent(), response.getToolCalls(), 0, false));
                session.append(TOOL_BATCH_STARTED, "call=" + session.modelCalls(), session.pendingToolBatch());
                session.checkpoint();
            }
        } catch (PersistenceException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            session.fail(failure, completeToolBatch);
        }
    }

    /** One automatic refresh per changed relevant input; failures never erase the original task evidence. */
    private boolean updateVerificationBasisIfNeeded(AgentSession session,dev.backendagent.model.ModelRequest context) {
        if(!verificationBasisEnabled || !model.supportsVerificationUpdates() || !tools.containsKey("pin_verification_basis"))return false;
        var visible=session.verificationSources(context);
        var previous=context.verificationContext()==null?List.<dev.backendagent.verification.ActiveVerificationBasis>of():
                context.verificationContext().activeBases().stream().filter(session::isAutomaticVerificationBasis).toList();
        var selected=dev.backendagent.verification.VerificationSources.updateSources(visible,session.turns().size(),
                session.turns().getLast().getUserMessage(),previous);
        var request=new dev.backendagent.model.VerificationUpdateRequest(session.turns().size(),selected,previous);
        var ledger=session.verificationLedger();
        var attemptedFiles=request.fileVersions(ledger!=null&&ledger.turnId()==request.turnId()
                ?ledger.attemptedFileVersions():Map.of());
        var triggerDigest=request.digest(attemptedFiles);
        if(ledger!=null && ledger.turnId()==request.turnId() && ledger.attemptedDigest().equals(triggerDigest))return false;
        if(maxModelCalls-session.modelCalls()<2) {
            session.append(VERIFICATION_UPDATE_SKIPPED,"Insufficient call budget; reserve the task call");
            return false;
        }
        // Save the attempt key first so a malformed response cannot cause an unbounded retry loop.
        session.installVerificationLedger(new dev.backendagent.verification.VerificationLedger(request.turnId(),triggerDigest,previous,attemptedFiles));
        session.countModelCall();
        session.append(VERIFICATION_UPDATE_STARTED,"turn="+request.turnId()+", sources="+selected.size());
        session.checkpoint();
        boolean installBudgetExceeded=false;
        boolean compactionNeeded=false;
        try {
            var updated=request.validateCandidate(model.updateVerificationBasis(request));
            if(dev.backendagent.verification.VerificationLedger.displayCharacters(updated)>dev.backendagent.verification.VerificationLedger.MAX_CHARACTERS) {
                compactionNeeded=true;
                if(!model.supportsVerificationCompaction() || maxModelCalls-session.modelCalls()<2)
                    throw new IllegalArgumentException("Cannot compact basis while reserving task call");
                var compact=new dev.backendagent.model.VerificationCompactionRequest(request,updated,visible);
                session.countModelCall();
                session.append(VERIFICATION_COMPACTION_STARTED,"characters="+dev.backendagent.verification.VerificationLedger.displayCharacters(updated));
                session.checkpoint();
                updated=compact.validate(model.compactVerificationBasis(compact));
                session.append(VERIFICATION_COMPACTION_COMPLETED,"characters="+dev.backendagent.verification.VerificationLedger.displayCharacters(updated));
            }
            updated=request.validate(updated);
            var candidate=new dev.backendagent.verification.VerificationContext(request.turnId(),session.verificationState(),
                    session.completionRejections(),context.verificationContext()==null?null:context.verificationContext().getCompletionIssue(),
                    session.latestVerificationTest(),updated);
            long protectedCharacters=candidate.characterCount()+session.historicalEvidenceCharacters()
                    +(context.contextSummary()==null?0:context.contextSummary().characterCount())
                    +(context.conversationSummary()==null?0:context.conversationSummary().characterCount());
            var report=model.inspectRequest(context.withVerificationContext(candidate));
            if(protectedCharacters>=configuredHistoryBudget.getMaxHistoryCharacters() || report!=null&&!report.isWithinBudget()) {
                installBudgetExceeded=true;
                throw new IllegalArgumentException("Verification basis does not fit the task request");
            }
            session.installVerificationLedger(new dev.backendagent.verification.VerificationLedger(request.turnId(),triggerDigest,updated,attemptedFiles));
        } catch(PersistenceException failure) { throw failure; }
        catch(RuntimeException invalid) {
            if(Thread.currentThread().isInterrupted())throw invalid;
            // Do not persist provider responses, secrets or fabricated quotations in failure diagnostics.
            // Failure to fit a new view does not invalidate the previously verified sources.
            // Their file freshness is still checked by the normal session projection.
            // A failed refresh does not invalidate evidence; preserve the bounded prior state.
            session.installVerificationLedger(new dev.backendagent.verification.VerificationLedger(request.turnId(),triggerDigest,previous,attemptedFiles));
            session.append(VERIFICATION_UPDATE_FAILED,"Automatic basis refresh rejected; reasonCode="
                    +(installBudgetExceeded?"INSTALL_BUDGET":compactionNeeded?"COMPACTION_REJECTED":"UPDATE_VALIDATION")+"; original context remains authoritative");
            progress.accept("本轮依据更新未通过校验，继续使用原始需求与工具证据。");
        }
        session.checkpoint();
        return true;
    }

    /** Transport retries preserve context; undeclared tools trigger bounded definition recovery, never execution. */
    private ModelResponse executeModelWithRetry(AgentSession session, dev.backendagent.model.ModelRequest context) {
        for (int attempt = 1; ; attempt++) {
            session.countModelCall();
            session.append(MODEL_CALL_STARTED, "call=" + session.modelCalls());
            try { return Objects.requireNonNull(model.execute(context)); }
            catch (dev.backendagent.model.ModelCallFailure failure) {
                var diagnostic = failure.getDiagnostic();
                session.append(MODEL_CALL_FAILED, diagnostic.detail() + ", attempt=" + attempt, diagnostic);
                if (!diagnostic.isRetryable() || attempt >= 3 || session.modelCalls() >= maxModelCalls) throw failure;
                if (failure instanceof dev.backendagent.model.UndeclaredToolCallFailure unavailable && toolDiscovery != null) {
                    session.append(MODEL_RETRY_SCHEDULED, toolDiscovery.recoveryEvent(session, unavailable.getProposedNames()));
                    session.checkpoint();
                    context = prepareContext(session).request();
                    progress.accept("工具定义不完整，已补充可用定义；上次批次未执行，重新请求模型。");
                    continue;
                }
                long delayMillis = 250L * attempt;
                session.append(MODEL_RETRY_SCHEDULED, "nextAttempt=" + (attempt + 1) + ", delayMillis=" + delayMillis);
                progress.accept("模型请求失败（" + diagnostic.getCode() + "），准备第" + (attempt + 1) + "次尝试");
                session.checkpoint();
                try { Thread.sleep(delayMillis); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new dev.backendagent.model.ModelCallFailure("Model retry interrupted",
                            new dev.backendagent.model.ModelFailureDiagnostic(
                                dev.backendagent.model.ModelFailureDiagnostic.Code.INTERRUPTED,
                                dev.backendagent.model.ModelFailureDiagnostic.FinishReason.MISSING, null, false));
                }
            }
        }
    }

    private void executePendingBatch(AgentSession session) {
        session.transitionTo(WAITING_FOR_TOOL);
        while (session.pendingToolBatch().getNextCallIndex() < session.pendingToolBatch().getCalls().size()) {
            var batch = session.pendingToolBatch();
            if (batch.isInFlight()) throw new IllegalStateException("In-flight tool has not been authorized for retry");
            ToolCall call = batch.getCalls().get(batch.getNextCallIndex());
            session.append(TOOL_CALL_REQUESTED, call.toString(), call);
            session.setPendingToolBatch(batch.starting());
            // Save the pre-execution workspace hash; ambiguous changes cannot be silently replayed.
            session.checkpoint();
            session.append(TOOL_EXECUTION_STARTED, "callId=" + call.id(), session.pendingToolBatch());
            ToolResult result = executeTool(call, session);
            if (call.name().equals("run_tests")) {
                result = withTestFailureGuidance(session, call, result);
                result = new ToolResult(result.successful(), "workspaceRevision="
                        + session.workspaceState().getRevision() + "\n" + result.content());
            }
            session.remember(new ToolExchange(call, result, batch.getAssistantContent(), batch.getModelCallNumber()));
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
            session.setPendingToolBatch(batch.completedOne());
            session.append(TOOL_BATCH_PROGRESS, "nextCallIndex=" + session.pendingToolBatch().getNextCallIndex(),
                    session.pendingToolBatch());
            session.checkpoint();
        }
        session.setPendingToolBatch(null);
        session.transitionTo(RUNNING);
        session.append(TOOL_BATCH_COMPLETED, "call=" + session.modelCalls());
        session.releaseOldBodies();
        session.checkpoint();
    }

    private static ToolResult withTestFailureGuidance(AgentSession session, ToolCall call, ToolResult result) {
        var current = dev.backendagent.verification.TestFailureReport.from(call.id(),
                session.workspaceState().getRevision(), result.successful(), result.content());
        if (!current.getKind().equals("TEST_FAILURE")) return result;
        String hint = "请围绕失败断言核对相关输入、算式和期望值；按契约核对字段含义与单位，将已核实算式的结果落实到断言和注释。无须重查无关部分。";
        var previous = session.latestVerificationTest();
        if (previous != null && !previous.isSuccessful() && current.getFailures().stream()
                .anyMatch(f -> previous.getFailures().stream().anyMatch(p -> p.getMethod().equals(f.getMethod())))) {
            hint += "上次修改未解决该断言，请检查是否遗漏了尚未解释的差异。";
        }
        return new ToolResult(false, result.content() + "\n" + hint);
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

    /** Measure the policy window without byte-budget pruning; maintenance precedes final trimming. */
    private dev.backendagent.model.ModelRequest pressureView(AgentSession session,ContextSummary toolsSummary,ConversationSummary dialogue) {
        session.refreshVerificationBasis();
        return new ContextAssembler(configuredHistoryBudget).previewUntrimmed(session,
                definitions(session),maxModelCalls-session.modelCalls(),toolsSummary,dialogue);
    }
    private boolean exceedsRequestBudget(dev.backendagent.model.RequestBudgetReport report) {
        return report != null && !report.isWithinBudget();
    }
    private void requirePressureGain(AgentSession session,ContextSummary toolsSummary,ConversationSummary dialogue) {
        var before=model.inspectRequest(pressureView(session,session.contextSummary(),session.conversationSummary()));
        var after=model.inspectRequest(pressureView(session,toolsSummary,dialogue));
        if(before!=null&&after!=null&&after.getRequestBytes()>=before.getRequestBytes())
            throw new IllegalArgumentException("Summary does not reduce outgoing request");
    }
    private boolean stillUnderPressure(AgentSession session) {
        return exceedsRequestBudget(model.inspectRequest(pressureView(session,session.contextSummary(),session.conversationSummary())));
    }
    private void maintainContextBeforeTrimming(AgentSession session) {
        var report=model.inspectRequest(pressureView(session,session.contextSummary(),session.conversationSummary()));
        if(!exceedsRequestBudget(report)) {
            compactConversationIfNeeded(session);
            compactIfNeeded(session,configuredHistoryBudget);
            return;
        }
        session.append(CONTEXT_PRESSURE_STARTED,"estimatedInput="+report.getEstimatedInputTokens()
                +", availableInput="+((long)report.getContextWindowTokens()-report.getOutputTokens()-report.getSafetyMarginTokens())
                +", reason=REQUEST_BUDGET_EXCEEDED");
        compactConversationIfNeeded(session,true);
        if(!stillUnderPressure(session))return;
        compactIfNeeded(session,configuredHistoryBudget,true);
        if(!stillUnderPressure(session))return;
        compactVerificationUnderPressure(session);
        // Final prepareContext applies oversized-result excerpts and whole-batch suffix trimming.
    }
    private boolean pressureAttempted(AgentSession session,String key) {
        return pressureAttempts.contains(key)||session.events().stream()
                .anyMatch(e->e.type()==CONTEXT_PRESSURE_ACTION&&e.detail().equals(key));
    }
    private boolean reservePressureAttempt(AgentSession session,String key) {
        if(pressureAttempted(session,key))return false;
        pressureAttempts.add(key);session.append(CONTEXT_PRESSURE_ACTION,key);return true;
    }
    private void compactVerificationUnderPressure(AgentSession session) {
        var ledger=session.verificationLedger();
        if(!verificationBasisEnabled||ledger==null||!model.supportsVerificationCompaction()||maxModelCalls-session.modelCalls()<2)return;
        var view=pressureView(session,session.contextSummary(),session.conversationSummary());
        var previous=view.verificationContext()==null?List.<dev.backendagent.verification.ActiveVerificationBasis>of():view.verificationContext().activeBases().stream().filter(session::isAutomaticVerificationBasis).toList();
        int size=dev.backendagent.verification.VerificationLedger.displayCharacters(previous);
        if(size<1024||!reservePressureAttempt(session,"verification:"+session.turns().size()+":"+ledger.attemptedDigest()))return;
        var visible=session.verificationSources(view);
        var selected=dev.backendagent.verification.VerificationSources.updateSources(visible,session.turns().size(),session.currentTask(),previous);
        var original=new dev.backendagent.model.VerificationUpdateRequest(session.turns().size(),selected,previous);
        var input=new dev.backendagent.model.VerificationCompactionRequest(original,previous,visible,Math.min(3999,size*3/4));
        session.countModelCall();session.append(VERIFICATION_COMPACTION_STARTED,"reason=REQUEST_PRESSURE, characters="+size);session.checkpoint();
        try {
            var result=input.validate(model.compactVerificationBasis(input));
            var context=new dev.backendagent.verification.VerificationContext(session.turns().size(),session.verificationState(),session.completionRejections(),view.verificationContext().getCompletionIssue(),session.latestVerificationTest(),result);
            var before=model.inspectRequest(view);var after=model.inspectRequest(view.withVerificationContext(context));
            if(before!=null&&after!=null&&after.getRequestBytes()>=before.getRequestBytes())throw new IllegalArgumentException("No request gain");
            session.installVerificationLedger(new dev.backendagent.verification.VerificationLedger(ledger.turnId(),ledger.attemptedDigest(),result,ledger.attemptedFileVersions()));
            session.append(VERIFICATION_COMPACTION_COMPLETED,"reason=REQUEST_PRESSURE, characters="+dev.backendagent.verification.VerificationLedger.displayCharacters(result));
        }catch(PersistenceException fatal){throw fatal;}
        catch(RuntimeException invalid){if(Thread.currentThread().isInterrupted())throw invalid;session.append(CONTEXT_PRESSURE_FAILED,"stage=verification-refinement; retaining prior state");}
        session.checkpoint();
    }

    private PreparedContext prepareContext(AgentSession session) {
        return prepareContext(session, session.contextSummary(), true);
    }

    private PreparedContext prepareContext(AgentSession session, ContextSummary summary, boolean install) {
        return prepareContext(session, summary, session.conversationSummary(), install);
    }

    private PreparedContext prepareContext(AgentSession session, ContextSummary summary,
                                           ConversationSummary dialogueSummary, boolean install) {
        session.refreshVerificationBasis();
        var definitions = definitions(session);
        int remaining = maxModelCalls - session.modelCalls();
        var initial = contextAssembler.preview(session, definitions, remaining, summary, dialogueSummary);
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
        var filtered = StaleObservationFilter.filter(raw).stream()
                .map(new ToolResultCompressor()::prune).toList();
        for (int i = latestStart; i < raw.size(); i++) { minimumCharacters += configuredHistoryBudget.measure(filtered.get(i)); }
        // Shrinking the suffix may hide a retrieved source and make its evidence cache visible.
        // Reserve all current evidence, subtracting the cache already included in the initial view.
        minimumCharacters += session.historicalEvidenceForContext().stream()
                .mapToLong(dev.backendagent.history.HistoricalEvidence::characterCount).sum()
                - initial.historicalEvidence().stream()
                    .mapToLong(dev.backendagent.history.HistoricalEvidence::characterCount).sum();
        int minimumLimit = (int) Math.max(1, minimumCharacters);
        var minimumBudget = new ContextBudget(minimumLimit);
        var minimum = previewBudgetCandidate(session, definitions, remaining, summary, dialogueSummary, minimumBudget);
        if (minimum != null) {
            var minimumReport = model.inspectRequest(minimum);
            if (minimumReport.isWithinBudget()) { best = new PreparedContext(minimum, minimumBudget, minimumReport); }
        }
        int low = minimumLimit;
        int high = configuredHistoryBudget.getMaxHistoryCharacters() - 1;
        while (low <= high) {
            int mid = low + (high - low) / 2;
            var candidateBudget = new ContextBudget(mid);
            var candidate = previewBudgetCandidate(session, definitions, remaining, summary, dialogueSummary, candidateBudget);
            if (candidate == null) { low = mid + 1; continue; }
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

    /** A capacity miss rejects one candidate, not the session; other state failures remain fatal. */
    private dev.backendagent.model.ModelRequest previewBudgetCandidate(AgentSession session,
            List<dev.backendagent.tools.ToolDefinition> definitions, int remaining, ContextSummary summary,
            ConversationSummary dialogueSummary, ContextBudget allowance) {
        try {
            return new ContextAssembler(allowance).preview(session, definitions, remaining, summary, dialogueSummary);
        } catch (IllegalStateException failure) {
            String message = failure.getMessage();
            if (message != null && (message.startsWith("Latest tool batch exceeds history character budget;")
                    || message.startsWith("Summary exceeds history budget;"))) { return null; }
            throw failure;
        }
    }

    private void compactConversationIfNeeded(AgentSession session) { compactConversationIfNeeded(session,false); }
    private void compactConversationIfNeeded(AgentSession session,boolean pressure) {
        if(!model.supportsConversationSummarization() || maxModelCalls-session.modelCalls()<2) return;
        var compactor=new ConversationCompactor(minimumSummaryInputCharacters);
        var request=compactor.plan(session,pressure);if(request==null)return;
        session.append(CONVERSATION_SUMMARY_ATTEMPTED,Integer.toString(request.toIndex()));
        session.transitionTo(COMPACTING);session.countModelCall();
        session.append(CONVERSATION_COMPACTION_STARTED,"call="+session.modelCalls()+", fromTurnIndex="+request.fromIndex()+", toTurnIndex="+request.toIndex());
        progress.accept("正在压缩旧轮次对话，请稍候……");
        var stage=SummaryRejection.Stage.MODEL_SUMMARY;
        ConversationSummary candidate;
        try {
            var notes=model.summarizeConversation(request);stage=SummaryRejection.Stage.SUMMARY_VALIDATION;
            candidate=validateWithRefinement(session, notes, request.evidence(), request.maxOutputCharacters(),
                    true, request.toIndex(), values -> compactor.validate(request,values,session)).candidate();
            stage=SummaryRejection.Stage.REQUEST_BUDGET;
            // Use the same adaptive history allowance as the outgoing request before committing.
            if(pressure) requirePressureGain(session,session.contextSummary(),candidate);
            else prepareContext(session, session.contextSummary(), candidate, false);
        } catch(PersistenceException failure){throw failure;}
        catch(RuntimeException failure){
            session.transitionTo(RUNNING);
            var rejection=SummaryRejection.from(stage,failure,session.modelCalls(),request.fromIndex(),request.toIndex());
            session.append(CONVERSATION_COMPACTION_FAILED,rejection.detail(),rejection);
            progress.accept("跨轮摘要未通过，保留旧摘要和可见原文。");return;
        }
        session.installConversationSummary(candidate);session.transitionTo(RUNNING);
        session.append(CONVERSATION_COMPACTION_COMPLETED,"revision="+candidate.getRevision()+", coveredTurns="+candidate.getCoveredTurns(),candidate);
        session.checkpoint();progress.accept("跨轮摘要完成，继续当前要求。");
    }

    private void compactIfNeeded(AgentSession session, ContextBudget effectiveHistoryBudget) { compactIfNeeded(session,effectiveHistoryBudget,false); }
    private void compactIfNeeded(AgentSession session,ContextBudget effectiveHistoryBudget,boolean pressure) {
        // Keep one call available for continuing the user's task; fake/legacy clients remain functional.
        if (!model.supportsSummarization() || maxModelCalls - session.modelCalls() < 2) { return; }
        var contextCompactor = new ContextCompactor(effectiveHistoryBudget, minimumSummaryInputCharacters);
        // One interval override per user turn; persistent pressure must not summarize every new batch.
        String earlyKey="tool-early:"+session.turns().size();
        boolean early=pressure&&!pressureAttempted(session,earlyKey)
                &&contextCompactor.decide(session,true).getRequest()!=null;
        if(early)reservePressureAttempt(session,earlyKey);
        var plan = contextCompactor.decide(session,early);
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
            var validated = validateWithRefinement(session, notes, request.getEvidence(), request.getMaxOutputCharacters(),
                    false, request.getToIndex(), values -> contextCompactor.validate(request,values,session));
            candidate = validated.candidate();
            failureStage = SummaryRejection.Stage.DEDUPLICATION;
            deduplication = new SummaryDeduplicator().analyze(validated.notes(), session::originalObservation);
            // Source-valid summaries must also leave room for the next complete outgoing request.
            failureStage = SummaryRejection.Stage.REQUEST_BUDGET;
            if(pressure) requirePressureGain(session,candidate,session.conversationSummary());
            else prepareContext(session, candidate, false);
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

    private record ValidatedSummary<T>(T candidate, List<SummaryNote> notes) { }

    private <T> ValidatedSummary<T> validateWithRefinement(AgentSession session, List<SummaryNote> notes,
            List<dev.backendagent.model.SummaryEvidence> evidence, int maxCharacters, boolean conversation,
            int coverage, java.util.function.Function<List<SummaryNote>, T> validate) {
        try { return new ValidatedSummary<>(validate.apply(notes), notes); }
        catch (IllegalArgumentException failure) {
            // Only length failures get one extra call; never retry source errors, JSON errors or full-request failures.
            if (!("Summary exceeds character limit".equals(failure.getMessage())
                    || "Summary exceeds reserved budget".equals(failure.getMessage()))
                    || !model.supportsSummaryRefinement() || maxModelCalls-session.modelCalls()<2) throw failure;
            int current = summaryDisplayCharacters(session, notes, conversation, coverage);
            if (current <= maxCharacters) throw failure;
            var request = new dev.backendagent.model.SummaryRefinementRequest(notes,evidence,current,maxCharacters);
            session.countModelCall();
            session.append(SUMMARY_REFINEMENT_STARTED, "kind="+(conversation?"conversation":"tools")
                    +", call="+session.modelCalls()+", currentCharacters="+current+", maxCharacters="+maxCharacters
                    +", targetCharacters="+request.getTargetCharacters());
            progress.accept("摘要超长，进行一次精简重试……");
            var refined = model.refineSummary(request);
            request.validateSources(refined);
            // Outside the first validation try: another length failure cannot recursively retry.
            T candidate = validate.apply(refined);
            session.append(SUMMARY_REFINEMENT_COMPLETED, "call="+session.modelCalls()+", beforeCharacters="+current
                    +", afterCharacters="+summaryDisplayCharacters(session,refined,conversation,coverage));
            return new ValidatedSummary<>(candidate, refined);
        }
    }

    private int summaryDisplayCharacters(AgentSession session, List<SummaryNote> notes, boolean conversation, int coverage) {
        var view=SummaryContextView.data(notes);
        if(conversation) for(var section:view.path("sections")) for(var item:section)
            dev.backendagent.model.ConversationMessage.annotateSource(
                    (com.fasterxml.jackson.databind.node.ObjectNode)item,item.path("sourceCallId").asText());
        return view.toString().length();
    }

    private ToolResult executeTool(ToolCall call, AgentSession session) {
        if (toolDiscovery != null && call.name().equals(dev.backendagent.selection.ToolDiscovery.NAME))
            return toolDiscovery.search(session, call.arguments());
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
