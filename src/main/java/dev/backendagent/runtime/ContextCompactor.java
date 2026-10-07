package dev.backendagent.runtime;

import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.backendagent.model.*;

/** Plans a bounded incremental summary and validates the candidate before replacing session state. */
public final class ContextCompactor {
    public static final int MAX_INPUT_CHARACTERS = 24000;
    public static final int DEFAULT_MIN_INPUT_CHARACTERS = 2048;
    private final int minimumInputCharacters;
    private final ContextBudget budget;
    private final ObjectMapper json = new ObjectMapper();
    public ContextCompactor(ContextBudget budget) { this(budget, DEFAULT_MIN_INPUT_CHARACTERS); }
    public ContextCompactor(ContextBudget budget, int minimumInputCharacters) {
        this.budget = java.util.Objects.requireNonNull(budget);
        if (minimumInputCharacters <= 0) throw new IllegalArgumentException("Minimum summary input must be positive");
        this.minimumInputCharacters = minimumInputCharacters;
    }

    public SummaryRequest plan(AgentSession session) { return decide(session).getRequest(); }

    private SummaryPlan waiting() { return new SummaryPlan(SummaryPlan.Reason.WAITING, null, 0, 0, minimumInputCharacters); }

    public SummaryPlan decide(AgentSession session) {
        var raw = session.history();
        int recent = ContextWindowPolicy.suffixStart(raw, ContextWindowPolicy.RECENT_BATCHES);
        int oldest = ContextWindowPolicy.suffixStart(raw, ContextWindowPolicy.RECENT_BATCHES + ContextWindowPolicy.SUMMARY_BATCHES);
        int covered = session.contextSummary() == null ? 0 : session.contextSummary().getCoveredExchanges();
        int attempt = Math.max(covered, session.lastWindowSummaryAttemptEnd());
        if (attempt >= recent || ContextWindowPolicy.countBatches(raw, Math.max(oldest, attempt), recent)
                < ContextWindowPolicy.SUMMARY_INTERVAL_BATCHES) { return waiting(); }
        int outputLimit = Math.min(4000, budget.getMaxHistoryCharacters() / 4);
        if (outputLimit < 512) { return waiting(); }
        var stale = StaleObservationFilter.staleIds(raw);
        var previous = ContextWindowPolicy.visibleSummary(session.contextSummary(), raw, stale);
        int from = Math.max(Math.max(covered, oldest), session.lastWindowSummarySkippedEnd());
        var input = new ArrayList<ToolExchange>();
        SummaryRequest best = null;
        for (int start = from; start < recent;) {
            int end = start + 1;
            int turn = raw.get(start).modelCallNumber();
            while (turn > 0 && end < recent && raw.get(end).modelCallNumber() == turn) { end++; }
            for (int index = start; index < end; index++) {
                var saved = raw.get(index);
                // Only reload eligible middle-window evidence, never the distant archive or stale bodies.
                if (!stale.contains(saved.call().id())) {
                    input.add(excerpt(session.originalObservation(saved.call().id())));
                }
            }
            if (input.isEmpty()) { start = end; continue; }
            var candidate = new SummaryRequest(session.objective(), previous, from, end, outputLimit, input);
            if (encodedLength(candidate) > MAX_INPUT_CHARACTERS) { break; }
            best = candidate;
            start = end;
        }
        if (input.isEmpty()) {
            return new SummaryPlan(SummaryPlan.Reason.NO_ELIGIBLE_OBSERVATIONS, null, recent, 0, minimumInputCharacters);
        }
        if (best == null || best.getToIndex() <= attempt) return waiting();
        // Count the text actually offered to the summarizer, not IDs, arguments or JSON framing.
        long characters = best.getObservations().stream().mapToLong(x -> x.result().content().length()).sum();
        if (characters < minimumInputCharacters) {
            return new SummaryPlan(SummaryPlan.Reason.TOO_SHORT, null, best.getToIndex(), characters, minimumInputCharacters);
        }
        return new SummaryPlan(SummaryPlan.Reason.READY, best, best.getToIndex(), characters, minimumInputCharacters);
    }

    private ToolExchange excerpt(ToolExchange raw) {
        // Rule compressor retains test diagnostics before this final input cap.
        String pruned = new ToolResultCompressor().compress(raw).result().content();
        if (pruned.length() > 2400) {
            pruned = pruned.substring(0, 1600) + "\n[摘要输入有省略，原文可用 read_observation 取回]\n"
                    + pruned.substring(pruned.length() - 700);
        }
        // Keep argument metadata for interpretation; enormous arguments cause the batch to be deferred.
        return new ToolExchange(raw.call(), new ToolResult(raw.result().successful(), pruned, raw.result().fileFingerprint()),
                null, raw.modelCallNumber(), raw.evidence());
    }
    private int encodedLength(SummaryRequest request) {
        try { return json.writeValueAsString(request).length(); }
        catch (java.io.IOException failure) { throw new IllegalStateException("Cannot encode summary input", failure); }
    }

    public ContextSummary validate(SummaryRequest request, List<SummaryNote> notes, AgentSession session) {
        int revision = session.contextSummary() == null ? 1 : session.contextSummary().getRevision() + 1;
        var candidate = new ContextSummary(revision, request.getToIndex(), notes);
        candidate.validateAgainst(session.history(), session::originalObservation);
        // A valid original quote must ALSO have been visible in this summary call or carried by the prior summary.
        var stale = StaleObservationFilter.staleIds(session.history());
        int oldest = ContextWindowPolicy.suffixStart(session.history(), ContextWindowPolicy.RECENT_BATCHES + ContextWindowPolicy.SUMMARY_BATCHES);
        int recent = ContextWindowPolicy.suffixStart(session.history(), ContextWindowPolicy.RECENT_BATCHES);
        for (var note : candidate.getNotes()) {
            if (note.getKind() == SummaryNote.Kind.NEXT_STEP) {
                throw new IllegalArgumentException("New summaries cannot contain NEXT_STEP");
            }
            if (session.history().subList(oldest, recent).stream().noneMatch(exchange -> exchange.call().id().equals(note.getSourceCallId()))) {
                throw new IllegalArgumentException("Summary source is outside the middle window");
            }
            if (stale.contains(note.getSourceCallId())) {
                throw new IllegalArgumentException("Summary cannot promote stale evidence");
            }
            boolean visible = request.getObservations().stream().anyMatch(exchange ->
                    exchange.call().id().equals(note.getSourceCallId())
                    && exchange.result().content().contains(note.getEvidenceQuote()));
            if (!visible && request.getPreviousSummary() != null) {
                visible = request.getPreviousSummary().getNotes().stream().anyMatch(old ->
                        old.getSourceCallId().equals(note.getSourceCallId())
                        && old.getEvidenceQuote().equals(note.getEvidenceQuote()));
            }
            if (!visible) { throw new IllegalArgumentException("Summary quote was not in summary input"); }
        }
        // Validate every original note first: deduplication must never hide an invalid source.
        var deduplicated = new SummaryDeduplicator().analyze(notes, session::originalObservation);
        candidate = new ContextSummary(revision, request.getToIndex(), deduplicated.getNotes());
        if (candidate.characterCount() > request.getMaxOutputCharacters()) {
            throw new IllegalArgumentException("Summary exceeds reserved budget");
        }
        long replacedCharacters = session.history().subList(request.getFromIndex(), request.getToIndex())
                .stream().mapToLong(budget::measure).sum();
        if (request.getPreviousSummary() != null) { replacedCharacters += request.getPreviousSummary().characterCount(); }
        if (candidate.characterCount() >= replacedCharacters) {
            throw new IllegalArgumentException("Summary does not reduce replaced context");
        }
        return candidate;
    }
}
