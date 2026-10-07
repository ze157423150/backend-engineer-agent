package dev.backendagent.runtime;

import java.util.List;

import dev.backendagent.model.ModelRequest;
import dev.backendagent.tools.ToolDefinition;

/** Selects a suffix of complete model turns without changing the session's full history. */
public final class ContextAssembler {
    private final ContextBudget budget;

    public ContextAssembler() {
        this(new ContextBudget(ContextBudget.DEFAULT_MAX_HISTORY_CHARACTERS));
    }

    public ContextAssembler(ContextBudget budget) {
        this.budget = java.util.Objects.requireNonNull(budget);
    }

    public ModelRequest assemble(AgentSession session, List<ToolDefinition> tools, int remainingCalls) {
        var request = preview(session, tools, remainingCalls, session.contextSummary());
        session.installProjection(request.contextProjection());
        return request;
    }

    /** Build a candidate without changing session state, used before committing a new summary. */
    public ModelRequest preview(AgentSession session, List<ToolDefinition> tools, int remainingCalls,
                                ContextSummary summary) {
        var stale = StaleObservationFilter.staleIds(session.history());
        var visibleSummary = ContextWindowPolicy.visibleSummary(summary, session.history(), stale);
        int summaryCost = (visibleSummary == null ? 0 : visibleSummary.characterCount()) + session.historicalEvidenceCharacters();
        if (summaryCost >= budget.getMaxHistoryCharacters()) {
            throw new IllegalStateException("Summary exceeds history budget; increase --max-history-chars");
        }
        int version = 3;
        var projection = new ContextAssembler(new ContextBudget(budget.getMaxHistoryCharacters() - summaryCost))
                .project(session.history(), version);
        return new ModelRequest(session.objective(), projection.getHistory(), tools, remainingCalls,
                projection.getOmittedExchanges(), projection.getHistoryCharacters() + summaryCost,
                session.memoryFacts().stream().filter(fact -> !stale.contains(fact.getSourceCallId())).toList(), projection,
                visibleSummary, summary == null ? java.util.Set.of() : summary.getNotes().stream()
                    .map(SummaryNote::getSourceCallId).filter(stale::contains)
                    .collect(java.util.stream.Collectors.toSet()), session.workspaceState(), session.historicalEvidence());
    }

    ContextProjection project(List<dev.backendagent.model.ToolExchange> raw) {
        return project(raw, true);
    }

    ContextProjection project(List<dev.backendagent.model.ToolExchange> raw, boolean filterStale) {
        return project(raw, filterStale ? 2 : 1);
    }

    ContextProjection project(List<dev.backendagent.model.ToolExchange> raw, int policyVersion) {
        boolean filterStale = policyVersion >= 2;
        var baseline = filterStale ? StaleObservationFilter.filter(raw) : raw;
        var history = new java.util.ArrayList<>(baseline);
        long total = baseline.stream().mapToLong(budget::measure).sum();
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
        int windowStart = policyVersion == 3 ? ContextWindowPolicy.suffixStart(raw, ContextWindowPolicy.RECENT_BATCHES) : 0;
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
            if (selectedCharacters + batchCharacters > budget.getMaxHistoryCharacters()) {
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
        int referenceStart = policyVersion == 3 ? ContextWindowPolicy.suffixStart(raw,
                ContextWindowPolicy.RECENT_BATCHES + ContextWindowPolicy.SUMMARY_BATCHES) : Math.max(0, selectedStart - 20);
        if (policyVersion == 3) {
            String notice = "近期最多4个完整批次；中期仅摘要，未覆盖内容请检索；远期仅归档，用 search_history 查找后 read_observation 提取。\n";
            if (selectedCharacters + notice.length() <= budget.getMaxHistoryCharacters()) { references.append(notice); }
        }
        for (int i = referenceStart; i < selectedStart; i++) {
            var call = raw.get(i).call();
            String line = "call_id=" + call.id() + ", tool=" + call.name() + "\n";
            if (references.length() + line.length() > 2000
                    || selectedCharacters + references.length() + line.length() > budget.getMaxHistoryCharacters()) {
                continue;
            }
            references.append(line);
        }
        int compressed = 0;
        int filtered = 0;
        for (int i = selectedStart; i < history.size(); i++) {
            if (!history.get(i).equals(baseline.get(i))) { compressed++; }
            if (!baseline.get(i).equals(raw.get(i))) { filtered++; }
        }
        return new ContextProjection(policyVersion, raw.size(), budget.getMaxHistoryCharacters(), selectedStart,
                selectedCharacters + references.length(), compressed, references.toString(),
                history.subList(selectedStart, history.size()), filtered);
    }
}
