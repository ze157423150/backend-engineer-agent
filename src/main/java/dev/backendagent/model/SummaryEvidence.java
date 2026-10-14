package dev.backendagent.model;

/** Request-local reference to a continuous quotation copied by Java, never by the model. */
public final class SummaryEvidence {
    private final String evidenceId;
    private final String sourceCallId;
    private final String evidenceQuote;

    public SummaryEvidence(String evidenceId, String sourceCallId, String evidenceQuote) {
        this.evidenceId = java.util.Objects.requireNonNull(evidenceId);
        this.sourceCallId = java.util.Objects.requireNonNull(sourceCallId);
        this.evidenceQuote = java.util.Objects.requireNonNull(evidenceQuote);
    }
    public String getEvidenceId() { return evidenceId; }
    public String getSourceCallId() { return sourceCallId; }
    public String getEvidenceQuote() { return evidenceQuote; }
}
