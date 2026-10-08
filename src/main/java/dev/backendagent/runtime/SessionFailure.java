package dev.backendagent.runtime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Recovery describes an execution boundary, not whether retrying will solve the original error. */
public final class SessionFailure {
    public enum Boundary { COMPLETE_TOOL_BATCH, BETWEEN_TOOLS, TOOL_IN_FLIGHT, INCOMPLETE_TOOL_BATCH }

    private final Boundary boundary;
    private final int modelCallNumber;
    private final int completedExchanges;
    private final String exceptionType;

    @JsonCreator
    public SessionFailure(@JsonProperty("boundary") Boundary boundary,
            @JsonProperty("modelCallNumber") int modelCallNumber,
            @JsonProperty("completedExchanges") int completedExchanges,
            @JsonProperty("exceptionType") String exceptionType) {
        if (boundary == null || modelCallNumber < 0 || completedExchanges < 0
                || exceptionType == null || exceptionType.isBlank() || exceptionType.length() > 256) {
            throw new IllegalArgumentException("Invalid session failure metadata");
        }
        this.boundary = boundary;
        this.modelCallNumber = modelCallNumber;
        this.completedExchanges = completedExchanges;
        this.exceptionType = exceptionType;
    }

    public boolean canResume() { return boundary == Boundary.COMPLETE_TOOL_BATCH || boundary == Boundary.BETWEEN_TOOLS; }
    public Boundary getBoundary() { return boundary; }
    public int getModelCallNumber() { return modelCallNumber; }
    public int getCompletedExchanges() { return completedExchanges; }
    public String getExceptionType() { return exceptionType; }
}
