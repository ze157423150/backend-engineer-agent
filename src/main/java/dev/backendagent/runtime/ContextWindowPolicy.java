package dev.backendagent.runtime;

import java.util.List;
import java.util.Set;
import java.util.HashSet;
import dev.backendagent.model.ToolExchange;

/** Fixed defaults shared by live projection, summary planning and checkpoint replay. */
public final class ContextWindowPolicy {
    public static final int RECENT_BATCHES = 4;
    public static final int SUMMARY_BATCHES = 8;
    public static final int SUMMARY_INTERVAL_BATCHES = 4;
    private ContextWindowPolicy() { }

    public static int suffixStart(List<ToolExchange> history, int batches) {
        int start = history.size();
        for (int count = 0; count < batches && start > 0; count++) {
            int turn = history.get(--start).modelCallNumber();
            while (turn > 0 && start > 0 && history.get(start - 1).modelCallNumber() == turn) { start--; }
        }
        return start;
    }
    public static int countBatches(List<ToolExchange> history, int from, int to) {
        int count = 0;
        for (int start = from; start < to; count++) {
            int turn = history.get(start++).modelCallNumber();
            while (turn > 0 && start < to && history.get(start).modelCallNumber() == turn) { start++; }
        }
        return count;
    }
    public static ContextSummary visibleSummary(ContextSummary summary, List<ToolExchange> history, Set<String> stale) {
        if (summary == null) { return null; }
        int recent = suffixStart(history, RECENT_BATCHES);
        int oldest = suffixStart(history, RECENT_BATCHES + SUMMARY_BATCHES);
        var allowed = new HashSet<String>();
        for (int index = oldest; index < recent; index++) { allowed.add(history.get(index).call().id()); }
        // A checkpoint may carry still-valid older facts; only raw candidates use the middle window.
        var sources = history.stream().collect(java.util.stream.Collectors.toMap(e -> e.call().id(), e -> e));
        int pending=SummarySourcePolicy.pendingTestFailure(history,stale);
        String pendingId=pending<0?null:history.get(pending).call().id();
        var notes = summary.getNotes().stream().filter(note -> !stale.contains(note.getSourceCallId())
                && !(sources.containsKey(note.getSourceCallId())
                    && sources.get(note.getSourceCallId()).call().name().equals("run_tests")
                    && !sources.get(note.getSourceCallId()).result().successful()
                    && !note.getSourceCallId().equals(pendingId))
                && sources.containsKey(note.getSourceCallId()) && SummarySourcePolicy.substantive(sources.get(note.getSourceCallId()))
                && !SummarySourcePolicy.resolvedExecutionIssue(note,history)).toList();
        return notes.isEmpty() ? null : new ContextSummary(summary.getRevision(), summary.getCoveredExchanges(), notes);
    }
}
