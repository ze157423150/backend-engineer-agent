package dev.backendagent.runtime;

/** Identifies a rejected note without retaining its arbitrary text. */
public final class SummaryValidationFailure extends IllegalArgumentException {
    private final int noteIndex;
    public SummaryValidationFailure(int noteIndex) {
        super("Summary quotation has no original observation");
        this.noteIndex = noteIndex;
    }
    public int getNoteIndex() { return noteIndex; }
}
