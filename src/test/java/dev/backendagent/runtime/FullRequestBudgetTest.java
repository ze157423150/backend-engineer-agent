package dev.backendagent.runtime;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import dev.backendagent.model.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FullRequestBudgetTest {
    private ToolExchange exchange(String id, int turn) {
        return new ToolExchange(new ToolCall(id, "read_file", Map.of("path", "Main.java")),
                new ToolResult(true, "historical evidence\n" + "x".repeat(3000)), null, turn);
    }
    private AgentSession pressured() {
        var session = new AgentSession("inspect");
        session.remember(exchange("old", 0));
        session.remember(exchange("new", 0));
        session.remember(exchange("latest", 0));
        return session;
    }
    private AgentSession pendingWindow() {
        var s = new AgentSession("inspect");
        for (int i = 0; i < 8; i++) s.remember(exchange(i == 0 ? "old" : "window-" + i, i + 1));
        return s;
    }
    @Test
    void fullBudgetCanReduceHistoryEvenWhenCharacterBudgetFitsEverything() {
        var session = pressured();
        var original = session.history();
        ModelClient model = new ModelClient() {
            public RequestBudgetReport inspectRequest(ModelRequest request) {
                return new RequestBudgetReport(1000 + request.historyCharacters(), 500, 500, 9000);
            }
            public ModelResponse execute(ModelRequest request) {
                assertTrue(inspectRequest(request).isWithinBudget());
                assertEquals(1, request.omittedExchanges());
                assertEquals(original.subList(1, 3), request.history());
                return ModelResponse.finish("done");
            }
        };
        new AgentRuntime(model, List.of(), 2, new ContextBudget(20000)).run(session);
        assertEquals(AgentSession.Status.COMPLETED, session.status());
        assertEquals(original, session.history());
        assertTrue(session.events().stream().anyMatch(e -> e.type() == AgentSession.EventType.REQUEST_BUDGET_CHECKED));
        session.contextProjection().validateAgainst(original);
    }
    @Test
    void protectedLatestBatchDoesNotSplitAndFailsBeforeModelExecution() {
        var calls = new AtomicInteger();
        var session = new AgentSession("inspect");
        session.remember(exchange("a", 1));
        session.remember(exchange("b", 1));
        var original = session.history();
        ModelClient model = new ModelClient() {
            public RequestBudgetReport inspectRequest(ModelRequest request) {
                return new RequestBudgetReport(3000 + request.historyCharacters(), 500, 500, 7000);
            }
            public ModelResponse execute(ModelRequest request) {
                calls.incrementAndGet(); return ModelResponse.finish("bad");
            }
        };
        new AgentRuntime(model, List.of(), 2, new ContextBudget(20000)).run(session);
        assertEquals(AgentSession.Status.FAILED, session.status());
        assertEquals(0, calls.get());
        assertEquals(0, session.modelCalls());
        assertEquals(original, session.history());
    }
    @Test
    void fixedOverheadFailsWithoutModelOrSummaryCall() {
        var session = new AgentSession("huge objective");
        ModelClient model = new ModelClient() {
            public boolean supportsSummarization() { return true; }
            public RequestBudgetReport inspectRequest(ModelRequest request) {
                return new RequestBudgetReport(20000, 500, 500, 7000);
            }
            public List<SummaryNote> summarize(SummaryRequest request) { fail("Cannot summarize fixed overhead"); return List.of(); }
            public ModelResponse execute(ModelRequest request) { fail("No HTTP request"); return ModelResponse.finish("bad"); }
        };
        new AgentRuntime(model, List.of(), 2, new ContextBudget(20000)).run(session);
        assertEquals(AgentSession.Status.FAILED, session.status());
        assertEquals(0, session.modelCalls());
        assertTrue(session.events().getLast().detail().contains("Full request budget"));
    }
    @Test
    void windowSummaryRespectsFullBudgetAndRechecksAfterSummary() {
        var session = pendingWindow();
        var summaries = new AtomicInteger();
        ModelClient model = new ModelClient() {
            public boolean supportsSummarization() { return true; }
            public RequestBudgetReport inspectRequest(ModelRequest request) {
                return new RequestBudgetReport(1000 + request.historyCharacters(), 500, 500, 9000);
            }
            public List<SummaryNote> summarize(SummaryRequest request) {
                summaries.incrementAndGet();
                return List.of(new SummaryNote(SummaryNote.Kind.PROGRESS, "Historical read", "old", "historical evidence"));
            }
            public ModelResponse execute(ModelRequest request) {
                assertNotNull(request.contextSummary());
                assertTrue(inspectRequest(request).isWithinBudget());
                return ModelResponse.finish("done");
            }
        };
        new AgentRuntime(model, List.of(), 3, new ContextBudget(20000)).run(session);
        assertEquals(AgentSession.Status.COMPLETED, session.status());
        assertEquals(1, summaries.get());
        assertEquals(2, session.modelCalls());
    }
    @Test
    void sourceValidSummaryThatCannotFitFullRequestIsRejectedBeforeCommit() {
        var session = pendingWindow();
        ModelClient model = new ModelClient() {
            public boolean supportsSummarization() { return true; }
            public RequestBudgetReport inspectRequest(ModelRequest request) {
                return new RequestBudgetReport(request.contextSummary() == null ? 1000 + request.historyCharacters() : 100000,
                        500, 500, 9000);
            }
            public List<SummaryNote> summarize(SummaryRequest request) {
                return List.of(new SummaryNote(SummaryNote.Kind.PROGRESS, "Historical read", "old", "historical evidence"));
            }
            public ModelResponse execute(ModelRequest request) {
                assertNull(request.contextSummary());
                assertTrue(inspectRequest(request).isWithinBudget());
                return ModelResponse.finish("continued");
            }
        };
        new AgentRuntime(model, List.of(), 3, new ContextBudget(20000)).run(session);
        assertEquals(AgentSession.Status.COMPLETED, session.status());
        assertNull(session.contextSummary());
        assertEquals(2, session.modelCalls());
        var rejection = session.events().stream().filter(e -> e.type() == AgentSession.EventType.COMPACTION_FAILED).findFirst().orElseThrow();
        assertTrue(rejection.detail().contains("stage=REQUEST_BUDGET"));
        assertTrue(rejection.detail().contains("reasonCode=REQUEST_BUDGET_CHECK_FAILED"));
    }

}
