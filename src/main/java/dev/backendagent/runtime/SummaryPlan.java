package dev.backendagent.runtime;

import dev.backendagent.model.SummaryRequest;

/** A planning decision. Skipping advances processing, never claims a summary was generated. */
public final class SummaryPlan {
    public enum Reason { WAITING, READY, TOO_SHORT, NO_ELIGIBLE_OBSERVATIONS }
    private final Reason reason;
    private final SummaryRequest request;
    private final int processedEnd;
    private final long inputCharacters;
    private final int minimumInputCharacters;
    SummaryPlan(Reason reason, SummaryRequest request, int processedEnd, long inputCharacters, int minimumInputCharacters) {
        this.reason = reason;
        this.request = request;
        this.processedEnd = processedEnd;
        this.inputCharacters = inputCharacters;
        this.minimumInputCharacters = minimumInputCharacters;
    }
    public Reason getReason() { return reason; }
    public SummaryRequest getRequest() { return request; }
    public int getProcessedEnd() { return processedEnd; }
    public long getInputCharacters() { return inputCharacters; }
    public int getMinimumInputCharacters() { return minimumInputCharacters; }
    public boolean isSkipped() { return reason == Reason.TOO_SHORT || reason == Reason.NO_ELIGIBLE_OBSERVATIONS; }
}
