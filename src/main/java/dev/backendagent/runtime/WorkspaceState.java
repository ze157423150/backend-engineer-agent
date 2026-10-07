package dev.backendagent.runtime;

import java.util.List;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnore;
import dev.backendagent.model.ToolExchange;

/** Host-side evidence bookkeeping. Never executes project code or accesses source files. */
public final class WorkspaceState {
    public enum TestStatus { NOT_RUN, CURRENT_PASSED, CURRENT_NOT_PASSED, STALE }
    private final long revision;
    private final TestRunEvidence latestTest;

    @JsonCreator
    public WorkspaceState(@JsonProperty("revision") long revision,
                          @JsonProperty("latestTest") TestRunEvidence latestTest) {
        if (revision < 0 || (latestTest != null && latestTest.getWorkspaceRevision() > revision)) {
            throw new IllegalArgumentException("Invalid workspace revision");
        }
        this.revision = revision;
        this.latestTest = latestTest;
    }

    public static WorkspaceState initial() { return new WorkspaceState(0, null); }
    public long getRevision() { return revision; }
    public TestRunEvidence getLatestTest() { return latestTest; }

    // Derived, not deserialized: a checkpoint cannot independently declare tests current.
    @JsonIgnore
    public TestStatus testStatus() {
        if (latestTest == null) { return TestStatus.NOT_RUN; }
        if (latestTest.getWorkspaceRevision() != revision) { return TestStatus.STALE; }
        return latestTest.isPassed() ? TestStatus.CURRENT_PASSED : TestStatus.CURRENT_NOT_PASSED;
    }

    public static boolean isSuccessfulWrite(ToolExchange exchange) {
        return exchange.result().successful() && (exchange.call().name().equals("apply_patch")
                || exchange.call().name().equals("create_file"));
    }

    public WorkspaceState after(ToolExchange exchange) {
        if (isSuccessfulWrite(exchange)) { return new WorkspaceState(Math.incrementExact(revision), latestTest); }
        if (exchange.call().name().equals("run_tests")) {
            return new WorkspaceState(revision,
                    new TestRunEvidence(exchange.call().id(), revision, exchange.result().successful()));
        }
        return this;
    }

    /** Rebuild metadata only; never replay writes or tests during recovery. */
    public static WorkspaceState fromHistory(List<ToolExchange> history) {
        var state = initial();
        for (var exchange : history) { state = state.after(exchange); }
        return state;
    }

    @Override public boolean equals(Object other) {
        return other instanceof WorkspaceState state && revision == state.revision
                && Objects.equals(latestTest, state.latestTest);
    }
    @Override public int hashCode() { return Objects.hash(revision, latestTest); }
}
