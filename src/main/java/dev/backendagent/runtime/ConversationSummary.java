package dev.backendagent.runtime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.backendagent.model.ConversationMessage;
import java.util.List;

/** Bounded historical dialogue summary; quotes verify provenance, not current requirements. */
public final class ConversationSummary {
    private final int revision;
    private final int coveredTurns;
    private final List<SummaryNote> notes;
    @JsonCreator
    public ConversationSummary(@JsonProperty("revision") int revision, @JsonProperty("coveredTurns") int coveredTurns,
            @JsonProperty("notes") List<SummaryNote> notes) {
        if (revision < 1 || coveredTurns < 1 || notes == null || notes.isEmpty() || notes.size() > 8)
            throw new IllegalArgumentException("Invalid context summary");
        this.revision=revision; this.coveredTurns=coveredTurns; this.notes=List.copyOf(notes);
        if (characterCount()>4000) throw new IllegalArgumentException("Summary exceeds character limit");
    }
    public void validateAgainst(List<ConversationTurn> turns) {
        if (coveredTurns > turns.size()) throw new IllegalArgumentException("Invalid context summary");
        for (var note : notes) {
            boolean found=false;
            for (var turn : turns.subList(0, coveredTurns)) {
                if (turn.getStatus()!=AgentSession.Status.COMPLETED) throw new IllegalArgumentException("Invalid context summary");
                if (matches(note, ConversationMessage.user(turn)) || matches(note, ConversationMessage.answer(turn))) found=true;
            }
            if (!found) throw new IllegalArgumentException("Summary quotation has no original conversation message");
            if (note.getKind()==SummaryNote.Kind.NEXT_STEP) throw new IllegalArgumentException("New summaries cannot contain NEXT_STEP");
        }
    }
    private boolean matches(SummaryNote note, ConversationMessage source) {
        return note.getSourceCallId().equals(source.id()) && source.content().contains(note.getEvidenceQuote());
    }
    public String referenceJson() {
        try { return new ObjectMapper().writeValueAsString(this); }
        catch (java.io.IOException failure) { throw new IllegalStateException("Cannot encode conversation summary", failure); }
    }
    public int characterCount() { return referenceJson().length(); }
    public int getRevision(){ return revision; }
    public int getCoveredTurns(){ return coveredTurns; }
    public List<SummaryNote> getNotes(){ return notes; }
}
