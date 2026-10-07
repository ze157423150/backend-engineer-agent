package dev.backendagent.runtime;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.backendagent.model.*;
import dev.backendagent.tools.ReadObservationTool;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContextProjectionTest {
    private ToolExchange exchange(String id, String tool, int batch, String content) {
        return new ToolExchange(new ToolCall(id, tool, Map.of("path", "Main.java")),
                new ToolResult(true, content), "inspect", batch);
    }
    private AgentSession session(ToolExchange... entries) {
        var session = new AgentSession("inspect");
        for (var entry : entries) { session.remember(entry); }
        return session;
    }
    private ContextProjection project(AgentSession session, int budget) {
        return new ContextAssembler(new ContextBudget(budget)).assemble(session, List.of(), 3).contextProjection();
    }

    @Test
    void dropsWholeBatchUnderPressureAndKeepsRawMiddleRetrievable() {
        String raw = "HEADER\n" + "first\n".repeat(1800) + "MIDDLE_EVIDENCE\n" + "last\n".repeat(1800);
        var old = exchange("old", "read_file", 1, raw);
        var latest = exchange("latest", "read_file", 2, "current file");
        var session = session(old, latest);
        var projection = project(session, 6500);
        assertEquals(1, projection.getOmittedExchanges());
        assertEquals(0, projection.getCompressedExchanges());
        assertFalse(projection.getHistory().getFirst().result().content().contains("MIDDLE_EVIDENCE"));
        assertEquals(latest, projection.getHistory().getLast());
        assertEquals(raw, session.history().getFirst().result().content());
        var recovered = new ReadObservationTool(session).execute(Map.of("call_id", "old",
                "start_line", "1802", "end_line", "1802"));
        assertTrue(recovered.successful());
        assertTrue(recovered.content().contains("1802: MIDDLE_EVIDENCE"));
        projection.validateAgainst(session.history());
    }

    @Test
    void noCompressionWithoutBudgetPressure() {
        var original = exchange("old", "read_file", 1, "x".repeat(12000));
        var session = session(original, exchange("new", "read_file", 2, "latest"));
        var projection = project(session, 20000);
        assertEquals(original, projection.getHistory().getFirst());
        assertEquals(0, projection.getCompressedExchanges());
    }

    @Test
    void protectsEveryResultInLatestMultiToolBatch() {
        var session = session(exchange("old", "read_file", 1, "x".repeat(12000)),
                exchange("a", "read_file", 2, "a".repeat(10000)),
                exchange("b", "search_code", 2, "b".repeat(10000)));
        var projection = project(session, 26000);
        assertEquals(session.history().subList(1, 3), projection.getHistory().subList(0, 2));
        assertThrows(IllegalStateException.class, () -> project(session, 15000));
    }

    @Test
    void preservesTestStatusAndDiagnosticInTheMiddleOfBuildNoise() {
        String raw = "status=FAILED\nexitCode=1\ncommand=mvn -o -B -ntp test\n"
                + "[INFO] build noise\n".repeat(600) + "[ERROR] MainTest expected: 42 but was: 0\n"
                + "[INFO] more noise\n".repeat(600);
        var original = exchange("tests", "run_tests", 1, raw);
        var compressed = new ToolResultCompressor().compress(original);
        assertTrue(compressed.result().content().contains("status=FAILED\nexitCode=1"));
        assertTrue(compressed.result().content().contains("[ERROR] MainTest expected: 42 but was: 0"));
        assertTrue(compressed.result().content().length() < raw.length());
        assertEquals(original.call(), compressed.call());
        assertEquals(original.result().successful(), compressed.result().successful());
    }

    @Test
    void retainsWriteAcknowledgementsAndUnknownTools() {
        var compressor = new ToolResultCompressor();
        for (String tool : List.of("apply_patch", "create_file", "unknown")) {
            var original = exchange("a", tool, 1, "x".repeat(12000));
            assertEquals(original, compressor.compress(original));
        }
    }

    @Test
    void catalogueFitsBudgetAndContainsNoOrphanToolMessages() {
        var original = exchange("old", "read_file", 1, "x".repeat(12000));
        var latest = exchange("new", "read_file", 2, "latest");
        var projection = project(session(original, latest), 600);
        assertEquals(List.of(latest), projection.getHistory());
        assertEquals(1, projection.getOmittedExchanges());
        assertTrue(projection.getRecoveryReferences().contains("call_id=old"));
        assertTrue(projection.getHistoryCharacters() <= 600);
        assertEquals(new ContextBudget(600).measure(latest) + projection.getRecoveryReferences().length(),
                projection.getHistoryCharacters());
    }

    @Test
    void jsonRoundTripValidatesAgainstRawHistoryAndRejectsForgedView() throws Exception {
        var session = session(exchange("old", "read_file", 1, "x".repeat(12000)),
                exchange("new", "read_file", 2, "latest"));
        var original = project(session, 6500);
        var json = new ObjectMapper();
        var restored = json.readValue(json.writeValueAsString(original), ContextProjection.class);
        restored.validateAgainst(session.history());
        var tree = json.valueToTree(original);
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("history").get(0).path("result"))
                .put("content", "forged evidence");
        var forged = json.treeToValue(tree, ContextProjection.class);
        assertThrows(IllegalArgumentException.class, () -> forged.validateAgainst(session.history()));
        // A later completed batch does not invalidate the saved last-sent view.
        session.remember(exchange("later", "read_file", 3, "later"));
        restored.validateAgainst(session.history());
    }

    @Test
    void historicalRetrievalIsBoundedAndCannotEstablishCurrentReadEvidence() {
        var session = session(exchange("old", "read_file", 1, "class Main {}\n"));
        session.transitionTo(AgentSession.Status.RUNNING);
        session.transitionTo(AgentSession.Status.WAITING_FOR_TOOL);
        session.invalidateFileEvidence("Main.java");
        var tool = new ReadObservationTool(session);
        var result = tool.execute(Map.of("call_id", "old", "start_line", "1", "end_line", "1"));
        assertTrue(result.successful());
        assertTrue(result.content().contains("stale=true"));
        session.remember(new ToolExchange(new ToolCall("recovered", tool.name(), Map.of()), result));
        assertFalse(session.hasCurrentRead("Main.java"));
        assertFalse(tool.execute(Map.of("call_id", "other-session", "start_line", "1", "end_line", "1")).successful());
        assertFalse(tool.execute(Map.of("call_id", "old", "start_line", "1", "end_line", "201")).successful());
        assertFalse(tool.execute(Map.of("call_id", "old", "start_line", "abc", "end_line", "1")).successful());
        assertFalse(tool.execute(Map.of("call_id", "old", "start_line", "9", "end_line", "9")).successful());
    }

    @Test
    void veryLongSingleLineReportsThatItCannotBeRetrievedCompletely() {
        var tool = new ReadObservationTool(session(exchange("long", "read_file", 1, "x".repeat(25000))));
        var result = tool.execute(Map.of("call_id", "long", "start_line", "1", "end_line", "1"));
        assertTrue(result.content().length() < 24000);
        assertTrue(result.content().contains("超长单行无法完整取回"));
    }
}
