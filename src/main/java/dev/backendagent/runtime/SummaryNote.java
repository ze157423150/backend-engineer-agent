package dev.backendagent.runtime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Model-written interpretation with a verifiable historical quotation, not a proven fact. */
public final class SummaryNote {
    public enum Kind { PROGRESS, OPEN_ISSUE, NEXT_STEP }
    private final Kind kind;
    private final String statement;
    private final String sourceCallId;
    private final String evidenceQuote;
    private final SummarySection section;

    public SummaryNote(Kind kind, String statement, String sourceCallId, String evidenceQuote) {
        this(kind, statement, sourceCallId, evidenceQuote, null);
    }

    @JsonCreator
    public SummaryNote(@JsonProperty("kind") Kind kind, @JsonProperty("statement") String statement,
            @JsonProperty("sourceCallId") String sourceCallId, @JsonProperty("evidenceQuote") String evidenceQuote,
            @JsonProperty("section") SummarySection section) {
        if (kind == null || statement == null || statement.isBlank() || statement.length() > 600
                || sourceCallId == null || sourceCallId.isBlank() || sourceCallId.length() > 256
                || evidenceQuote == null || evidenceQuote.isBlank() || evidenceQuote.length() > 400) {
            throw new IllegalArgumentException("Invalid summary note");
        }
        this.kind = kind;
        this.statement = statement;
        this.sourceCallId = sourceCallId;
        this.evidenceQuote = evidenceQuote;
        this.section = section;
    }
    public Kind getKind() { return kind; }
    public String getStatement() { return statement; }
    public String getSourceCallId() { return sourceCallId; }
    public String getEvidenceQuote() { return evidenceQuote; }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public SummarySection getSection() { return section; }
}
