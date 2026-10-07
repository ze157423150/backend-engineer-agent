package dev.backendagent.history;

import dev.backendagent.runtime.ObservationEvidence.Validity;

/** A bounded discovery result, not a fresh read or test authorization. */
public final class HistoryReference {
    private final long eventSequence;
    private final String callId;
    private final String tool;
    private final String path;
    private final boolean successful;
    private final Validity validity;
    private final int matchedLine;
    private final int totalLines;
    private final String snippet;
    private final Long observedRevision;

    public HistoryReference(long eventSequence, String callId, String tool, String path, boolean successful,
                            Validity validity, int matchedLine, int totalLines, String snippet, Long observedRevision) {
        this.eventSequence = eventSequence;
        this.callId = callId;
        this.tool = tool;
        this.path = path;
        this.successful = successful;
        this.validity = validity;
        this.matchedLine = matchedLine;
        this.totalLines = totalLines;
        this.snippet = snippet;
        this.observedRevision = observedRevision;
    }
    public long getEventSequence() { return eventSequence; }
    public String getCallId() { return callId; }
    public String getTool() { return tool; }
    public String getPath() { return path; }
    public boolean isSuccessful() { return successful; }
    public Validity getValidity() { return validity; }
    public int getMatchedLine() { return matchedLine; }
    public int getTotalLines() { return totalLines; }
    public String getSnippet() { return snippet; }
    public Long getObservedRevision() { return observedRevision; }
}
