package dev.backendagent.model;

/** Application-owned diagnostics only; never stores model text or unknown field values. */
public final class SummaryOutputFailure extends IllegalStateException {
    private final String issue;
    private final int noteIndex;
    private final int actualCount;

    public SummaryOutputFailure(String message, String issue, int noteIndex, int actualCount) {
        super(message);
        this.issue = switch (issue) {
            case "JSON_SYNTAX", "ROOT_NOT_OBJECT", "ROOT_FIELDS", "NOTES_TYPE", "NOTE_COUNT",
                    "NOTE_FIELDS", "UNKNOWN_EVIDENCE_ID", "NOTE_VALUES" -> issue;
            default -> "UNCLASSIFIED_OUTPUT_ISSUE";
        };
        this.noteIndex = noteIndex;
        this.actualCount = actualCount;
    }
    public String getIssue() { return issue; }
    public int getNoteIndex() { return noteIndex; }
    public int getActualCount() { return actualCount; }
}
