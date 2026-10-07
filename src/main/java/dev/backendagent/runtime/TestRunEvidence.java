package dev.backendagent.runtime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** A completed run_tests observation, bound to the session's source revision. */
public final class TestRunEvidence {
    private final String callId;
    private final long workspaceRevision;
    private final boolean passed;

    @JsonCreator
    public TestRunEvidence(@JsonProperty("callId") String callId,
                           @JsonProperty("workspaceRevision") long workspaceRevision,
                           @JsonProperty("passed") boolean passed) {
        if (callId == null || callId.isBlank() || workspaceRevision < 0) {
            throw new IllegalArgumentException("Invalid test evidence");
        }
        this.callId = callId;
        this.workspaceRevision = workspaceRevision;
        this.passed = passed;
    }

    public String getCallId() { return callId; }
    public long getWorkspaceRevision() { return workspaceRevision; }
    public boolean isPassed() { return passed; }

    @Override public boolean equals(Object other) {
        return other instanceof TestRunEvidence evidence && callId.equals(evidence.callId)
                && workspaceRevision == evidence.workspaceRevision && passed == evidence.passed;
    }
    @Override public int hashCode() { return java.util.Objects.hash(callId, workspaceRevision, passed); }
}
