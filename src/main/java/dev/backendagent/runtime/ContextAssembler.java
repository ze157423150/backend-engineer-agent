package dev.backendagent.runtime;

import java.util.List;

import dev.backendagent.model.ModelRequest;
import dev.backendagent.tools.ToolDefinition;

/** Selects a suffix of complete model turns without changing the session's full history. */
public final class ContextAssembler {
    private final ContextBudget budget;
    private final boolean pressurePreview;

    public ContextAssembler() {
        this(new ContextBudget(ContextBudget.DEFAULT_MAX_HISTORY_CHARACTERS));
    }

    public ContextAssembler(ContextBudget budget) {
        this(budget,false);
    }
    private ContextAssembler(ContextBudget budget,boolean pressurePreview) {
        this.budget=java.util.Objects.requireNonNull(budget);this.pressurePreview=pressurePreview;
    }
    public ModelRequest previewUntrimmed(AgentSession session,List<ToolDefinition> tools,int remainingCalls,
                                         ContextSummary summary,ConversationSummary dialogueSummary) {
        return new ContextAssembler(budget,true).preview(session,tools,remainingCalls,summary,dialogueSummary);
    }

    public ModelRequest assemble(AgentSession session, List<ToolDefinition> tools, int remainingCalls) {
        var request = preview(session, tools, remainingCalls, session.contextSummary());
        session.installProjection(request.contextProjection());
        return request;
    }

    /** Build a candidate without changing session state, used before committing a new summary. */
    public ModelRequest preview(AgentSession session, List<ToolDefinition> tools, int remainingCalls,
                                ContextSummary summary) {
        return preview(session,tools,remainingCalls,summary,session.conversationSummary());
    }
    public ModelRequest preview(AgentSession session,List<ToolDefinition> tools,int remainingCalls,
                                ContextSummary summary,ConversationSummary dialogueSummary) {
        var stale = StaleObservationFilter.staleIds(session.history());
        var visibleSummary = ContextWindowPolicy.visibleSummary(summary, session.history(), stale);
        var visibleDialogue=ConversationCompactor.visibleSummary(dialogueSummary,session.turns());
        var verification = session.verificationContext();
        int fixedCost = (visibleSummary == null ? 0 : visibleSummary.characterCount())+(visibleDialogue==null?0:visibleDialogue.characterCount())
                + (verification == null ? 0 : verification.characterCount());
        var allEvidence=session.historicalEvidenceForContext();
        var hiddenEvidence=allEvidence;
        int summaryCost=fixedCost;
        ContextProjection projection=null;
        // Start without duplicate cache cost, then include only excerpts whose original stays hidden.
        // Smaller allowances cannot expose additional originals, so at most one change per cache entry.
        for(int pass=0;pass<=allEvidence.size()+1;pass++) {
            if(summaryCost>=budget.getMaxHistoryCharacters())
                throw new IllegalStateException("Summary exceeds history budget; increase --max-history-chars");
            projection=new ContextAssembler(new ContextBudget(budget.getMaxHistoryCharacters()-summaryCost),pressurePreview)
                    .project(session.history(),7,summary==null?0:summary.getCoveredExchanges());
            hiddenEvidence=dev.backendagent.history.HistoricalEvidenceArea.hiddenOnly(allEvidence,projection.getHistory());
            int nextCost=fixedCost+hiddenEvidence.stream().mapToInt(dev.backendagent.history.HistoricalEvidence::characterCount).sum();
            if(nextCost==summaryCost)break;
            if(pass==allEvidence.size()+1)throw new IllegalStateException("Historical evidence visibility did not stabilize");
            summaryCost=nextCost;
        }
        // A retained original does not need its summary note repeated in the same request.
        if (visibleSummary != null) {
            var retainedIds = projection.getHistory().stream().map(exchange -> exchange.call().id())
                    .collect(java.util.stream.Collectors.toSet());
            var notes = visibleSummary.getNotes().stream()
                    .filter(note -> !retainedIds.contains(note.getSourceCallId())).toList();
            visibleSummary = notes.isEmpty() ? null : new ContextSummary(visibleSummary.getRevision(),
                    visibleSummary.getCoveredExchanges(), notes);
        }
        var request = new ModelRequest(session.objective(), projection.getHistory(), tools, remainingCalls,
                projection.getOmittedExchanges(), projection.getHistoryCharacters() + summaryCost,
                session.memoryFacts().stream().filter(fact -> !stale.contains(fact.getSourceCallId())).toList(), projection,
                visibleSummary, summary == null ? java.util.Set.of() : summary.getNotes().stream()
                    .map(SummaryNote::getSourceCallId).filter(stale::contains)
                    .collect(java.util.stream.Collectors.toSet()), session.workspaceState(), hiddenEvidence, ConversationCompactor.visibleTurns(session.turns(),dialogueSummary,
                        projection.getOmittedExchanges(),session.history().size()).stream().map(turn ->
                    new ConversationTurn(turn.getTurnId(), turn.getUserMessage(),
                            turn.getStatus() == AgentSession.Status.COMPLETED ? AgentSession.Status.COMPLETED : AgentSession.Status.RUNNING,
                            turn.getAnswer(), turn.getStartHistoryIndex(), turn.getEndHistoryIndex())).toList(),visibleDialogue,session.turns().size())
                .withNoProgressReport(session.noProgressReport()).withVerificationContext(verification);
        return request.withVerificationContext(session.verificationContext(request));
    }

    ContextProjection project(List<dev.backendagent.model.ToolExchange> raw) {
        return project(raw, true);
    }

    ContextProjection project(List<dev.backendagent.model.ToolExchange> raw, boolean filterStale) {
        return project(raw, filterStale ? 2 : 1);
    }

    ContextProjection project(List<dev.backendagent.model.ToolExchange> raw, int policyVersion) {
        return project(raw, policyVersion, 0);
    }

    ContextProjection project(List<dev.backendagent.model.ToolExchange> raw, int policyVersion,
                              int summaryCoveredExchanges) {
        boolean filterStale = policyVersion >= 2;
        var baseline = filterStale ? StaleObservationFilter.filter(raw) : raw;
        var history = new java.util.ArrayList<>(baseline);
        if (!pressurePreview && policyVersion >= 6 && baseline.stream().mapToLong(budget::measure).sum() > budget.getMaxHistoryCharacters()) {
            var pruner = new ToolResultCompressor();
            for (int i = 0; i < history.size(); i++) { history.set(i, pruner.prune(history.get(i))); }
        }
        long total = history.stream().mapToLong(budget::measure).sum();
        int newestStart = raw.size();
        if (!raw.isEmpty()) {
            newestStart--;
            int turn = raw.get(newestStart).modelCallNumber();
            while (turn > 0 && newestStart > 0 && raw.get(newestStart - 1).modelCallNumber() == turn) {
                newestStart--;
            }
        }
        if (policyVersion < 3 && total > budget.getMaxHistoryCharacters()) {
            var compressor = new ToolResultCompressor();
            for (int i = 0; i < newestStart; i++) { history.set(i, compressor.compress(baseline.get(i))); }
        }
        int selectedStart = history.size();
        long selectedCharacters = 0;
        // Short histories stay complete if their filtered text fits. Distant history still
        // follows the archive policy, even when a stored excerpt would make it seem cheap.
        boolean retainShortHistory = policyVersion >= 4
                && ContextWindowPolicy.suffixStart(raw, ContextWindowPolicy.RECENT_BATCHES
                    + ContextWindowPolicy.SUMMARY_BATCHES) == 0
                && raw.stream().noneMatch(exchange -> exchange.archivedBody() != null)
                && total <= budget.getMaxHistoryCharacters();
        int windowStart = policyVersion >= 3 && !retainShortHistory
                ? ContextWindowPolicy.suffixStart(raw, ContextWindowPolicy.RECENT_BATCHES) : 0;
        if (policyVersion >= 5 && !retainShortHistory) {
            int oldest = ContextWindowPolicy.suffixStart(raw,
                    ContextWindowPolicy.RECENT_BATCHES + ContextWindowPolicy.SUMMARY_BATCHES);
            // Waiting, rejected and deliberately short inputs remain raw candidates. Only an
            // accepted summary's coverage frontier may replace them with the recent suffix.
            windowStart = Math.max(oldest, Math.min(summaryCoveredExchanges, windowStart));
        }
        while (selectedStart > windowStart) {
            int batchEnd = selectedStart;
            int batchStart = batchEnd - 1;
            int turn = history.get(batchStart).modelCallNumber();
            while (turn > 0 && batchStart > 0 && history.get(batchStart - 1).modelCallNumber() == turn) {
                batchStart--;
            }
            long batchCharacters = 0;
            for (int index = batchStart; index < batchEnd; index++) {
                batchCharacters += budget.measure(history.get(index));
            }
            if (!pressurePreview && selectedCharacters + batchCharacters > budget.getMaxHistoryCharacters()) {
                if (selectedStart == history.size()) {
                    throw new IllegalStateException("Latest tool batch exceeds history character budget; "
                            + "increase --max-history-chars or request smaller tool results");
                }
                break;
            }
            selectedCharacters += batchCharacters;
            selectedStart = batchStart;
        }
        // A bounded catalogue makes omitted call IDs discoverable without orphan tool messages.
        var references = new StringBuilder();
        if(policyVersion>=7) {
            appendMiddleIndex(raw,summaryCoveredExchanges,Math.min(selectedStart,
                    ContextWindowPolicy.suffixStart(raw,ContextWindowPolicy.RECENT_BATCHES)),references,selectedCharacters);
        } else {
        int referenceStart = policyVersion >= 3 ? ContextWindowPolicy.suffixStart(raw,
                ContextWindowPolicy.RECENT_BATCHES + ContextWindowPolicy.SUMMARY_BATCHES) : Math.max(0, selectedStart - 20);
        if (policyVersion == 3 || (policyVersion >= 4 && selectedStart > 0)) {
            String notice = "近期最多4个完整批次；中期仅摘要，未覆盖内容请检索；远期仅归档，用 search_history 查找后 read_observation 提取。\n";
            if (policyVersion >= 4) {
                notice = "历史正文因预算或分层策略隐藏，不代表文件不存在；请按路径 search_history，"
                        + "再 read_observation；旧证据过期时重新读取当前文件。\n";
            }
            if (selectedCharacters + notice.length() <= budget.getMaxHistoryCharacters()) { references.append(notice); }
        }
        var staleIds = policyVersion >= 4 ? StaleObservationFilter.staleIds(raw) : java.util.Set.<String>of();
        if (policyVersion >= 5) {
            appendUnsummarizedReferences(raw, summaryCoveredExchanges, selectedStart, staleIds,
                    references, selectedCharacters);
        }
        for (int i = referenceStart; i < selectedStart; i++) {
            var call = raw.get(i).call();
            String line = "call_id=" + call.id() + ", tool=" + call.name() + "\n";
            if (policyVersion >= 4) {
                var exchange = raw.get(i);
                String path = call.arguments().get("path");
                String validity = staleIds.contains(call.id()) ? "STALE"
                        : exchange.evidence() != null ? "CURRENT" : "UNKNOWN";
                line = "call_id=" + call.id() + ", tool=" + call.name()
                        + (path == null ? "" : ", path=\"" + cataloguePath(path) + "\"")
                        + ", successful=" + exchange.result().successful() + ", validity=" + validity + "\n";
            }
            if (references.length() + line.length() > 2000
                    || selectedCharacters + references.length() + line.length() > budget.getMaxHistoryCharacters()) {
                continue;
            }
            references.append(line);
        }
        }
        int compressed = 0;
        int filtered = 0;
        for (int i = selectedStart; i < history.size(); i++) {
            if (!history.get(i).equals(baseline.get(i))) { compressed++; }
            if (!baseline.get(i).equals(raw.get(i))) { filtered++; }
        }
        // Pressure previews are never installed/persisted; allow their uncropped measurement to exceed the configured limit.
        return new ContextProjection(policyVersion, raw.size(), pressurePreview?Integer.MAX_VALUE:budget.getMaxHistoryCharacters(), selectedStart,
                selectedCharacters + references.length(), compressed, references.toString(),
                history.subList(selectedStart, history.size()), filtered, summaryCoveredExchanges);
    }

    /** Index only hidden records older than the recent four batches, once per call ID. */
    private void appendMiddleIndex(List<dev.backendagent.model.ToolExchange> raw,int covered,int end,
            StringBuilder out,long selectedCharacters) {
        if(end<=0)return;
        appendReference(out,selectedCharacters,"索引不含近期4批；默认当时成功，时效未重核；失败/过期另标。已取回的同来源范围以当前历史证据区为准。\n");
        var stale=StaleObservationFilter.staleIds(raw);
        long pending=raw.subList(Math.min(covered,end),end).stream()
                .filter(e->!stale.contains(e.call().id())).count();
        if(pending>0)appendReference(out,selectedCharacters,"UNSUMMARIZED_OMITTED count="+pending+"\n");
        var candidates=new java.util.LinkedHashSet<Integer>();
        for(boolean writes:new boolean[]{true,false})
            for(int i=end-1;i>=covered;i--)
                if(WorkspaceState.isSuccessfulWrite(raw.get(i))==writes)candidates.add(i);
        int oldest=ContextWindowPolicy.suffixStart(raw,ContextWindowPolicy.RECENT_BATCHES+ContextWindowPolicy.SUMMARY_BATCHES);
        for(int i=end-1;i>=oldest;i--)candidates.add(i);
        for(int i:candidates) {
            var entry=raw.get(i);var call=entry.call();String path=call.arguments().get("path");
            appendReference(out,selectedCharacters,"call_id="+call.id()+", tool="+call.name()
                    +(path==null?"":", path=\""+cataloguePath(path)+"\"")
                    +(entry.result().successful()?"":", failed")+(stale.contains(call.id())?", STALE":"")+"\n");
        }
    }

    private void appendUnsummarizedReferences(List<dev.backendagent.model.ToolExchange> raw, int covered,
            int selectedStart, java.util.Set<String> stale, StringBuilder references, long selectedCharacters) {
        long count = raw.subList(Math.min(covered, selectedStart), selectedStart).stream()
                .filter(exchange -> !stale.contains(exchange.call().id())).count();
        if (count == 0) { return; }
        appendReference(references, selectedCharacters, "UNSUMMARIZED_OMITTED count=" + count
                + "；这些记录尚未由成功摘要处理，不得推断操作未发生。以下仅程序生成操作索引，非模型摘要；"
                + "缺少原文请search_history/read_observation。\n");
        // Successful writes deserve an entry before routine observations; no source body is
        // invented or re-promoted here. Full arguments/results remain in the durable archive.
        for (boolean writes : new boolean[] {true, false}) {
            for (int i = selectedStart - 1; i >= covered; i--) {
                var exchange = raw.get(i);
                if (stale.contains(exchange.call().id()) || WorkspaceState.isSuccessfulWrite(exchange) != writes) {
                    continue;
                }
                String path = exchange.call().arguments().get("path");
                appendReference(references, selectedCharacters, "summaryState=NOT_SUMMARIZED, call_id="
                        + exchange.call().id() + ", tool=" + exchange.call().name()
                        + (path == null ? "" : ", path=\"" + cataloguePath(path) + "\"")
                        + ", successful=" + exchange.result().successful() + "\n");
            }
        }
    }

    private void appendReference(StringBuilder references, long selectedCharacters, String line) {
        if (references.length() + line.length() <= 2000
                && selectedCharacters + references.length() + line.length() <= budget.getMaxHistoryCharacters()) {
            references.append(line);
        }
    }
    /** Only the path is catalogued; arbitrary tool arguments and result bodies stay out. */
    private static String cataloguePath(String path) {
        String bounded = path.length() > 256 ? path.substring(0, 256) + "…" : path;
        return bounded.replace("\\", "\\\\").replace("\"", "\\\"")
                .replaceAll("[\\p{Cntrl}]", " ");
    }
}
