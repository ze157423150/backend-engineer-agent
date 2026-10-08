package dev.backendagent.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.backendagent.model.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShortHistoryRetentionTest {
    private ToolExchange read(String id, int batch, String body) {
        var file = new FileFingerprint("test.java", "a".repeat(64));
        return new ToolExchange(new ToolCall(id, "read_file", Map.of("path", "test.java")),
                new ToolResult(true, body, file), null, batch,
                new ObservationEvidence(ObservationEvidence.Type.FILE_CONTENT, id, file, 0));
    }
    private List<ToolExchange> trace(String body) {
        var history = new ArrayList<ToolExchange>();
        history.add(read("target", 1, body));
        for (int batch = 2; batch <= 6; batch++) {
            history.add(new ToolExchange(new ToolCall("list" + batch, "list_files", Map.of("path", "src")),
                    new ToolResult(true, "Calculator.java"), null, batch));
        }
        return history;
    }
    @Test
    void targetReadSurvivesFiveExplorationBatchesWhenShortHistoryFits() {
        var raw = trace("ans[i-k+1] = deque.peekFirst();");
        var projection = new ContextAssembler(new ContextBudget(64000)).project(raw, 4);
        assertEquals(raw, projection.getHistory());
        assertEquals(0, projection.getOmittedExchanges());
        assertEquals("", projection.getRecoveryReferences());
        projection.validateAgainst(raw);
        // Prior checkpoints must continue to replay with their original fixed-window rule.
        var legacy = new ContextAssembler(new ContextBudget(64000)).project(raw, 3);
        assertEquals(4, legacy.getHistory().size());
        assertEquals(2, legacy.getOmittedExchanges());
        legacy.validateAgainst(raw);
    }
    @Test
    void budgetPressureCataloguesTargetWithPathSuccessAndCurrentEvidence() {
        var raw = trace("source".repeat(1000));
        var projection = new ContextAssembler(new ContextBudget(1000)).project(raw, 4);
        assertEquals(4, projection.getHistory().size());
        assertTrue(projection.getRecoveryReferences().contains(
                "call_id=target, tool=read_file, path=\"test.java\", successful=true, validity=CURRENT"));
        assertTrue(projection.getHistoryCharacters() <= 1000);
        projection.validateAgainst(raw);
    }
    @Test
    void completeShortHistoryStillFiltersInvalidatedFileBody() {
        var raw = new ArrayList<>(trace("OLD_SECRET_SOURCE"));
        raw.add(new ToolExchange(new ToolCall("write", "apply_patch", Map.of("path", "test.java")),
                new ToolResult(true, "updated"), null, 7));
        var projection = new ContextAssembler(new ContextBudget(64000)).project(raw, 4);
        assertEquals(7, projection.getHistory().size());
        assertEquals(1, projection.getFilteredExchanges());
        assertFalse(projection.getHistory().getFirst().result().content().contains("OLD_SECRET_SOURCE"));
        assertTrue(projection.getHistory().getFirst().result().content().contains("STALE_OBSERVATION_BODY_OMITTED"));
        var noise = raw.get(1);
        raw.set(1, new ToolExchange(noise.call(), new ToolResult(true, "x".repeat(2000)), null, 2));
        var tight = new ContextAssembler(new ContextBudget(1000)).project(raw, 4);
        assertTrue(tight.getRecoveryReferences().contains("validity=STALE"));
        assertTrue(tight.getHistoryCharacters() <= 1000);
        tight.validateAgainst(raw);
    }
    @Test
    void originalRetainedInRequestDoesNotAlsoRepeatSummaryNote() {
        var session = new AgentSession("fix test.java");
        trace("bug evidence").forEach(session::remember);
        session.installSummary(new ContextSummary(1, 2, List.of(new SummaryNote(
                SummaryNote.Kind.PROGRESS, "observed target", "target", "bug evidence"))));
        var request = new ContextAssembler().assemble(session, List.of(), 10);
        assertEquals(6, request.history().size());
        assertNull(request.contextSummary());
        assertNotNull(session.contextSummary());
    }
    @Test
    void newAndOldProjectionsRoundTripWithVersionSpecificValidation() throws Exception {
        var raw = trace("source");
        var json = new ObjectMapper();
        for (int version : List.of(3, 4)) {
            var projection = new ContextAssembler().project(raw, version);
            var restored = json.readValue(json.writeValueAsString(projection), ContextProjection.class);
            restored.validateAgainst(raw);
            assertEquals(version, restored.getPolicyVersion());
        }
    }
}
