package dev.backendagent.runtime;

import dev.backendagent.model.ToolExchange;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Removes proven repetitions, while reporting paraphrases without deleting their facts. */
public final class SummaryDeduplicator {
    public static final class Result {
        private final List<SummaryNote> notes;
        private final int removedCount;
        private final List<String> suspectedPairs;
        private Result(List<SummaryNote> notes, int removedCount, List<String> suspectedPairs) {
            this.notes = List.copyOf(notes);
            this.removedCount = removedCount;
            this.suspectedPairs = List.copyOf(suspectedPairs);
        }
        public List<SummaryNote> getNotes() { return notes; }
        public int getRemovedCount() { return removedCount; }
        /** Original note indices: matching evidence is a warning, not semantic equivalence. */
        public List<String> getSuspectedPairs() { return suspectedPairs; }
    }

    public Result analyze(List<SummaryNote> notes, Function<String, ToolExchange> original) {
        var kept = new ArrayList<SummaryNote>();
        var indices = new ArrayList<Integer>();
        var suspected = new ArrayList<String>();
        int removed = 0;
        for (int index = 0; index < notes.size(); index++) {
            var note = notes.get(index);
            boolean duplicate = false;
            for (int k = 0; k < kept.size(); k++) {
                var prior = kept.get(k);
                if (prior.getKind() != note.getKind()
                        || !normalize(prior.getEvidenceQuote()).equals(normalize(note.getEvidenceQuote()))
                        || !sameEvidence(prior, note, original, false)) continue;
                if (normalize(prior.getStatement()).equals(normalize(note.getStatement()))
                        && sameEvidence(prior, note, original, true)) {
                    duplicate = true;
                    break;
                }
                // Two different facts can use the same quotation; never delete on this signal alone.
                suspected.add(indices.get(k) + ":" + index);
            }
            if (duplicate) { removed++; }
            else { kept.add(note); indices.add(index); }
        }
        return new Result(kept, removed, suspected);
    }

    private boolean sameEvidence(SummaryNote first, SummaryNote second,
            Function<String, ToolExchange> original, boolean requireFingerprint) {
        if (first.getSourceCallId().equals(second.getSourceCallId())) return true;
        var a = original.apply(first.getSourceCallId());
        var b = original.apply(second.getSourceCallId());
        // Only immutable file observations support cross-call merging. Execution counts remain distinct.
        if (a == null || b == null) return false;
        return a.call().name().equals("read_file") && b.call().name().equals("read_file")
                && a.result().successful() && b.result().successful()
                && (!requireFingerprint || (a.result().fileFingerprint() != null
                    && a.result().fileFingerprint().equals(b.result().fileFingerprint())))
                && a.call().arguments().equals(b.call().arguments())
                && a.result().content().equals(b.result().content());
    }

    private String normalize(String text) {
        // Keep negation, punctuation, numbers and whitespace inside literals intact.
        return text.strip();
    }
}
