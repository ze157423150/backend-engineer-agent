package dev.backendagent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.backendagent.model.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PendingSummaryRetentionTest {
    private AgentSession history(int batches) {
        var session = new AgentSession("inspect");
        for (int i = 0; i < batches; i++) {
            session.remember(new ToolExchange(new ToolCall("r" + i, "read_file",
                    Map.of("path", "File" + i + ".java")), new ToolResult(true, "source-" + i), null, i + 1));
        }
        return session;
    }

    @Test
    void pendingMiddleRemainsVisibleWithoutAcceptedSummary() {
        var session = history(16);
        var request = new ContextAssembler().assemble(session, List.of(), 10);
        assertEquals(12, request.history().size());
        assertEquals("r4", request.history().getFirst().call().id());
        assertEquals("r15", request.history().getLast().call().id());
        assertEquals(5, request.contextProjection().getPolicyVersion());
        assertTrue(request.contextProjection().getRecoveryReferences().contains("UNSUMMARIZED_OMITTED count=4"));
        assertTrue(request.contextProjection().getRecoveryReferences().contains("call_id=r0,"));
        request.contextProjection().validateAgainst(session.history());
    }

    @Test
    void skippedAndRejectedAttemptsDoNotBecomeSummaryCoverage() {
        var session = history(16);
        session.append(AgentSession.EventType.WINDOW_SUMMARY_SKIPPED, "8");
        session.append(AgentSession.EventType.WINDOW_SUMMARY_ATTEMPTED, "12");
        var request = new ContextAssembler().assemble(session, List.of(), 10);
        assertEquals(0, request.contextProjection().getSummaryCoveredExchanges());
        assertEquals("r4", request.history().getFirst().call().id());
    }

    @Test
    void onlyAcceptedCoverageReplacesMiddleOriginals() {
        var session = history(16);
        session.installSummary(new ContextSummary(1, 8, List.of(
                new SummaryNote(SummaryNote.Kind.PROGRESS, "Observed source", "r5", "source-5"))));
        var request = new ContextAssembler().assemble(session, List.of(), 10);
        assertEquals("r8", request.history().getFirst().call().id());
        assertEquals(8, request.history().size());
        assertEquals(8, request.contextProjection().getSummaryCoveredExchanges());
        assertNotNull(request.contextSummary());
        assertFalse(request.contextProjection().getRecoveryReferences().contains("NOT_SUMMARIZED"));
        request.contextProjection().validateAgainst(session.history());
    }

    @Test
    void budgetStillDropsWholeBatchesAndExposesUncoveredWrite() {
        var session = new AgentSession("inspect");
        session.remember(new ToolExchange(new ToolCall("write", "create_file",
                Map.of("path", "Main.java", "content", "class Main {}")),
                new ToolResult(true, "created\n" + "x".repeat(1400)), null, 1));
        session.remember(new ToolExchange(new ToolCall("a", "list_files", Map.of()),
                new ToolResult(true, "a".repeat(100)), null, 2));
        session.remember(new ToolExchange(new ToolCall("b", "list_files", Map.of()),
                new ToolResult(true, "b".repeat(100)), null, 2));
        var request = new ContextAssembler(new ContextBudget(1000)).assemble(session, List.of(), 10);
        assertEquals(List.of("a", "b"), request.history().stream().map(x -> x.call().id()).toList());
        assertTrue(request.contextProjection().getRecoveryReferences().contains("summaryState=NOT_SUMMARIZED, call_id=write"));
        assertTrue(request.historyCharacters() <= 1000);
        request.contextProjection().validateAgainst(session.history());
    }

    @Test
    void newCoverageRoundTripsAndLegacyProjectionReplaysItsOriginalRule() throws Exception {
        var session = history(16);
        var legacy = new ContextAssembler().project(session.history(), 4);
        assertEquals(4, legacy.getHistory().size());
        legacy.validateAgainst(session.history());
        session.installSummary(new ContextSummary(1, 8, List.of(
                new SummaryNote(SummaryNote.Kind.PROGRESS, "Observed source", "r5", "source-5"))));
        var projection = new ContextAssembler().assemble(session, List.of(), 10).contextProjection();
        var json = new ObjectMapper();
        var restored = json.readValue(json.writeValueAsString(projection), ContextProjection.class);
        assertEquals(5, restored.getPolicyVersion());
        assertEquals(8, restored.getSummaryCoveredExchanges());
        restored.validateAgainst(session.history());
        var oldJson = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(legacy);
        oldJson.remove("summaryCoveredExchanges");
        json.treeToValue(oldJson, ContextProjection.class).validateAgainst(session.history());
    }

    @Test
    void recoveryRejectsCoverageThatSplitsAToolBatch() {
        var session = history(1);
        session.remember(new ToolExchange(new ToolCall("other", "list_files", Map.of()),
                new ToolResult(true, "files"), null, 1));
        var projection = new ContextAssembler().project(session.history(), 5, 1);
        assertThrows(IllegalArgumentException.class, () -> projection.validateAgainst(session.history()));
    }
}
