package dev.backendagent.runtime;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import dev.backendagent.model.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TurnWriteSummaryTest {
    private ToolExchange exchange(String id, String tool, String path, boolean success, String content) {
        return new ToolExchange(new ToolCall(id, tool, Map.of("path", path)), new ToolResult(success, content));
    }

    @Test void countsOnlySuccessfulWritesAndDeduplicatesPaths() {
        var history = List.of(exchange("a", "apply_patch", "A.java", true, "written"),
                exchange("b", "apply_patch_batch", "A.java", true, "reverted"),
                exchange("c", "create_file", "B.java", true, "created"),
                exchange("d", "apply_patch", "C.java", false, "rejected"),
                exchange("e", "read_file", "D.java", true, "content"),
                exchange("f", "workspace_diff", "E.java", true, "diff"));
        assertEquals(List.of("A.java", "B.java"), TurnWriteSummary.fromHistory(history).getPaths());
    }

    @Test void survivesProjectionAndHistorySerializationButResetsAtTurnBoundary() throws Exception {
        var session = new AgentSession("修复实现与测试");
        session.remember(exchange("written", "apply_patch", "A.java", true, "x".repeat(2000)));
        for (int i = 0; i < 10; i++) session.remember(exchange("read" + i, "read_file", "B.java", true, "content"));
        var assembler = new ContextAssembler(new ContextBudget(1000));
        var request = assembler.assemble(session, List.of(), 5);
        assertTrue(request.history().stream().noneMatch(e -> e.call().id().equals("written")));
        assertEquals(List.of("A.java"), request.turnWrites().getPaths());
        assertSame(request.turnWrites(), request.withNoProgressReport(null).withVerificationContext(null).turnWrites());
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var restored = json.readValue(json.writeValueAsString(session.history()), ToolExchange[].class);
        assertEquals(request.turnWrites().getPaths(), TurnWriteSummary.fromHistory(List.of(restored)).getPaths());
        session.transitionTo(AgentSession.Status.RUNNING); session.finish("完成");
        session.transitionTo(AgentSession.Status.COMPLETED); session.continueWith("下一轮");
        assertTrue(assembler.assemble(session, List.of(), 5).turnWrites().getPaths().isEmpty());
        assertTrue(assembler.assemble(new AgentSession("独立会话"), List.of(), 5).turnWrites().getPaths().isEmpty());
    }

    @Test void boundedListDoesNotClaimToBeCompleteWhenPathsAreOmitted() {
        var history = new ArrayList<ToolExchange>();
        for (int i = 0; i < 40; i++) history.add(exchange("write"+i, "create_file", "File"+i+".java", true, "created"));
        var summary = TurnWriteSummary.fromHistory(history);
        assertEquals(32, summary.getPaths().size());
        assertEquals(8, summary.getOmittedPaths());
        var longSummary = TurnWriteSummary.fromHistory(List.of(exchange("long", "create_file", "a".repeat(2049), true, "created")));
        assertTrue(longSummary.getPaths().isEmpty());
        assertEquals(1, longSummary.getOmittedPaths());
    }
}
