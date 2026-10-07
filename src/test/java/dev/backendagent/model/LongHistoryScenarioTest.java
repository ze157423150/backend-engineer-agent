package dev.backendagent.model;

import dev.backendagent.history.HistoricalEvidence;
import dev.backendagent.runtime.ObservationEvidence;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LongHistoryScenarioTest {
    private List<ToolExchange> trajectory(ObservationEvidence.Validity validity, boolean separateRounds) {
        var history = new ArrayList<ToolExchange>();
        for (int i = 1; i <= 32; i++) history.add(new ToolExchange(
                new ToolCall("note" + i, "read_file", Map.of("path", "docs/notes/note-" + i + ".txt")),
                new ToolResult(true, "note"), null, separateRounds ? i : 1));
        history.add(new ToolExchange(new ToolCall("patch", "apply_patch", Map.of("path", "src/main/java/dev/eval/FixTarget.java")), new ToolResult(true, "patched")));
        history.add(new ToolExchange(new ToolCall("search", "search_history", Map.of()), new ToolResult(true, "index")));
        var source = new ToolExchange(new ToolCall("old", "read_file", Map.of("path", "src/main/java/dev/eval/FixTarget.java")),
                new ToolResult(true, "RoundingMode.DOWN", new FileFingerprint("src/main/java/dev/eval/FixTarget.java", "a".repeat(64))));
        var evidence = HistoricalEvidence.excerpt(source, 1, validity, 1, 1);
        history.add(new ToolExchange(new ToolCall("retrieve", "read_observation", Map.of("call_id", "old")),
                new ToolResult(true, "HISTORICAL_OBSERVATION\n" + evidence.getContent(), null, evidence)));
        history.add(new ToolExchange(new ToolCall("fresh", "read_file", Map.of("path", "src/main/java/dev/eval/FixTarget.java")), new ToolResult(true, "HALF_UP")));
        return history;
    }
    @Test void acceptsTypedStaleEvidenceWithoutModelRenderingMarker() {
        assertTrue(RealModelEvaluation.followedLongHistoryScenario(trajectory(ObservationEvidence.Validity.STALE, true)));
    }
    @Test void rejectsCurrentEvidenceForRequiredOldCodeRetrieval() {
        assertFalse(RealModelEvaluation.followedLongHistoryScenario(trajectory(ObservationEvidence.Validity.CURRENT, true)));
    }
    @Test void rejectsBatchedReadsWhenSeparateReviewRoundsAreRequired() {
        assertFalse(RealModelEvaluation.followedLongHistoryScenario(trajectory(ObservationEvidence.Validity.STALE, false)));
    }
}
