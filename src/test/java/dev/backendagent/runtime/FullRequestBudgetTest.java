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
        assertEquals(AgentSession.Status.COMPLETED, session.status(),session.events().getLast().detail());
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
        assertEquals(AgentSession.Status.COMPLETED, session.status(),session.events().getLast().detail());
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
        assertEquals(AgentSession.Status.COMPLETED, session.status(),session.events().getLast().detail());
        assertNull(session.contextSummary());
        assertEquals(2, session.modelCalls());
        var rejection = session.events().stream().filter(e -> e.type() == AgentSession.EventType.COMPACTION_FAILED).findFirst().orElseThrow();
        assertTrue(rejection.detail().contains("stage=REQUEST_BUDGET"));
        assertTrue(rejection.detail().contains("reasonCode=NO_COMPRESSION_GAIN"));
    }

    @Test
    void shrinkingHistoryReservesEvidenceThatWasInitiallyHiddenByItsVisibleOriginal() {
        var session = new AgentSession("inspect retrieved contract");
        var source = new ToolExchange(new ToolCall("contract", "read_file", Map.of("path", "contract.md")),
                new ToolResult(true, "contract rule " + "a".repeat(900) + "\n" + "b".repeat(2100)), null, 1);
        session.remember(source);
        session.remember(exchange("padding-a", 2));
        session.remember(exchange("padding-b", 3));
        var evidence = dev.backendagent.history.HistoricalEvidence.excerpt(source, 1,
                ObservationEvidence.Validity.UNKNOWN, 1, 1);
        var retrieval = new ToolExchange(new ToolCall("retrieve", "read_observation",
                Map.of("call_id", "contract", "start_line", "1", "end_line", "1")),
                new ToolResult(true, evidence.getContent(), null, evidence), null, 4);
        session.remember(retrieval);
        session.retainHistoricalEvidence(retrieval);
        var initial = new ContextAssembler(new ContextBudget(20000)).preview(session, List.of(), 2, null);
        assertTrue(initial.historicalEvidence().isEmpty(), "Original is initially visible");
        assertTrue(initial.history().contains(source));
        var original = session.history();
        var executed = new AtomicInteger();
        ModelClient model = new ModelClient() {
            public RequestBudgetReport inspectRequest(ModelRequest request) {
                return new RequestBudgetReport(1000 + request.historyCharacters(), 500, 500, 9000);
            }
            public ModelResponse execute(ModelRequest request) {
                executed.incrementAndGet();
                assertTrue(inspectRequest(request).isWithinBudget());
                assertFalse(request.history().stream().anyMatch(e -> e.call().id().equals("contract")));
                assertEquals("contract", request.historicalEvidence().getFirst().getSourceCallId());
                assertEquals(evidence.getContent(), request.historicalEvidence().getFirst().getContent());
                assertEquals(retrieval, request.history().getLast(), "Keep the latest tool result intact");
                return ModelResponse.finish("done");
            }
        };
        new AgentRuntime(model, List.of(), 2, new ContextBudget(20000)).run(session);
        assertEquals(AgentSession.Status.COMPLETED, session.status(), session.events().getLast().detail());
        assertEquals(1, executed.get());
        assertEquals(original, session.history());
    }

}
