package dev.backendagent.runtime;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.backendagent.model.ToolExchange;

/** A bounded replacement summary covering a prefix of complete historical batches. */
public final class ContextSummary {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final int revision;
    private final int coveredExchanges;
    private final List<SummaryNote> notes;

    @JsonCreator
    public ContextSummary(@JsonProperty("revision") int revision,
            @JsonProperty("coveredExchanges") int coveredExchanges,
            @JsonProperty("notes") List<SummaryNote> notes) {
        if (revision < 1 || coveredExchanges < 1 || notes == null || notes.isEmpty() || notes.size() > 8) {
            throw new IllegalArgumentException("Invalid context summary");
        }
        this.revision = revision;
        this.coveredExchanges = coveredExchanges;
        this.notes = List.copyOf(notes);
        if (characterCount() > 4000) { throw new IllegalArgumentException("Summary exceeds character limit"); }
    }
    public void validateAgainst(List<ToolExchange> raw) {
        validateAgainst(raw, id -> raw.stream().filter(exchange -> exchange.call().id().equals(id)).findFirst().orElseThrow());
    }
    public void validateAgainst(List<ToolExchange> raw, java.util.function.Function<String, ToolExchange> original) {
        if (coveredExchanges > raw.size()
                || (coveredExchanges < raw.size() && raw.get(coveredExchanges - 1).modelCallNumber() > 0
                    && raw.get(coveredExchanges - 1).modelCallNumber() == raw.get(coveredExchanges).modelCallNumber())) {
            throw new IllegalArgumentException("Summary does not cover complete batches");
        }
        for (var note : notes) {
            if (raw.subList(0, coveredExchanges).stream().noneMatch(exchange ->
                    exchange.call().id().equals(note.getSourceCallId())
                    && original.apply(exchange.call().id()).result().content().contains(note.getEvidenceQuote()))) {
                throw new IllegalArgumentException("Summary quotation has no original observation");
            }
        }
    }
    public String referenceJson() {
        try { return JSON.writeValueAsString(this); }
        catch (java.io.IOException failure) { throw new IllegalStateException("Cannot encode summary", failure); }
    }
    public int characterCount() { return referenceJson().length(); }
    /** A view only; persisted summary and its historical quotations remain intact. */
    public ContextSummary withoutStaleSources(java.util.Set<String> stale) {
        var currentNotes = notes.stream().filter(note -> !stale.contains(note.getSourceCallId())).toList();
        if (currentNotes.size() == notes.size()) { return this; }
        return currentNotes.isEmpty() ? null : new ContextSummary(revision, coveredExchanges, currentNotes);
    }
    public int getRevision() { return revision; }
    public int getCoveredExchanges() { return coveredExchanges; }
    public List<SummaryNote> getNotes() { return notes; }
}
