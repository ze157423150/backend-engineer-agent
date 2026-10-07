package dev.backendagent.runtime;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import dev.backendagent.model.*;
import dev.backendagent.persistence.PersistenceException;
import dev.backendagent.persistence.SessionEventSink;
import dev.backendagent.tools.Tool;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContextCompactorTest {
    private ToolExchange exchange(String id, int batch, String content) {
        return new ToolExchange(new ToolCall(id, "read_file", Map.of("path", "Main.java")),
                new ToolResult(true, content), "reading", batch);
    }
    private AgentSession pressured() {
        var session = new AgentSession("inspect Main");
        for (int i = 0; i < 8; i++) { session.remember(exchange("old-" + i, i + 1,
                "evidence " + i + (i < 4 ? "\n" + "x".repeat(3000) : ""))); }
        return session;
    }
    private SummaryNote note(String id, String quote) {
        return new SummaryNote(SummaryNote.Kind.PROGRESS, "Historical file observation", id, quote);
    }
    private ModelClient model(java.util.function.Function<SummaryRequest, List<SummaryNote>> summarize,
                              java.util.function.Function<ModelRequest, ModelResponse> execute) {
        return new ModelClient() {
            public boolean supportsSummarization() { return true; }
            public List<SummaryNote> summarize(SummaryRequest request) { return summarize.apply(request); }
            public ModelResponse execute(ModelRequest request) { return execute.apply(request); }
        };
    }

    @Test
    void summarizesPendingWindowAndCountsBothCallsBeforeContinuing() {
        var session = pressured();
        var raw = session.history();
        var summaries = new AtomicInteger();
        var progress = new java.util.ArrayList<String>();
        new AgentRuntime(model(request -> {
            summaries.incrementAndGet();
            assertEquals(AgentSession.Status.COMPACTING, session.status());
            assertEquals(1, session.modelCalls());
            assertEquals(0, request.getFromIndex());
            assertEquals(4, request.getToIndex());
            return List.of(note("old-0", "evidence 0"));
        }, request -> {
            assertEquals(AgentSession.Status.RUNNING, session.status());
            assertNotNull(request.contextSummary());
            assertEquals(2, request.remainingModelCalls());
            assertTrue(request.historyCharacters() <= 7000);
            assertEquals(raw.getLast(), request.history().getLast());
            return ModelResponse.finish("done");
        }), List.of(), 3, new ContextBudget(7000), progress::add).run(session);
        assertEquals(AgentSession.Status.COMPLETED, session.status());
        assertEquals(2, session.modelCalls());
        assertEquals(1, summaries.get());
        assertEquals(raw, session.history());
        assertEquals(2, progress.size());
        assertTrue(session.events().stream().anyMatch(e -> e.type() == AgentSession.EventType.COMPACTION_COMPLETED));
        assertNotNull(new ContextCompactor(new ContextBudget(64000)).plan(pressured()));
    }

    @Test
    void rulePruningSufficesWithoutSummaryCall() {
        var session = new AgentSession("inspect");
        session.remember(exchange("old", 1, "x".repeat(12000)));
        session.remember(exchange("new", 2, "latest"));
        assertNull(new ContextCompactor(new ContextBudget(6500)).plan(session));
    }

    @Test
    void insufficientCallBudgetKeepsOneTaskCallAndSkipsSummary() {
        var session = pressured();
        new AgentRuntime(model(request -> { fail("No budget for summary"); return List.of(); },
                request -> { assertNull(request.contextSummary()); return ModelResponse.finish("done"); }),
                List.of(), 1, new ContextBudget(7000)).run(session);
        assertEquals(1, session.modelCalls());
        assertEquals(AgentSession.Status.COMPLETED, session.status());
    }

    @Test
    void invalidCandidateFallsBackWithoutChangingPreviousSummaryOrLeakingExceptionText() {
        var session = pressured();
        for (int i = 8; i < 12; i++) session.remember(exchange("old-" + i, i + 1, "evidence " + i));
        var previous = new ContextSummary(1, 1, List.of(note("old-0", "evidence 0")));
        session.installSummary(previous);
        new AgentRuntime(model(request -> List.of(note("invented-id", "invented quote")), request -> {
            assertEquals(previous.getNotes(), request.contextSummary().getNotes());
            return ModelResponse.finish("continued");
        }), List.of(), 3, new ContextBudget(6000)).run(session);
        assertSame(previous, session.contextSummary());
        assertEquals(AgentSession.Status.COMPLETED, session.status());
        assertEquals(2, session.modelCalls());
        assertTrue(session.events().stream().anyMatch(e -> e.type() == AgentSession.EventType.COMPACTION_FAILED));
    }

    @Test
    void transportFailureUsesSameFallback() {
        var session = pressured();
        new AgentRuntime(model(request -> { throw new IllegalStateException("sensitive-response-body"); },
                request -> ModelResponse.finish("continued")), List.of(), 3, new ContextBudget(7000)).run(session);
        assertEquals(AgentSession.Status.COMPLETED, session.status());
        assertNull(session.contextSummary());
        assertFalse(session.events().toString().contains("sensitive-response-body"));
        assertEquals(4, session.lastWindowSummaryAttemptEnd());
        assertNull(new ContextCompactor(new ContextBudget(7000)).plan(session));
    }

    @Test
    void rollingSummaryOnlyProcessesNewPrefixAndCanCarryOriginalQuotes() {
        var session = pressured();
        var compactor = new ContextCompactor(new ContextBudget(7000), 1);
        var first = compactor.plan(session);
        session.installSummary(compactor.validate(first, List.of(note("old-0", "evidence 0")), session));
        assertNull(compactor.plan(session));
        session.remember(exchange("old-8", 9, "evidence 8"));
        assertNull(compactor.plan(session));
        for (int i = 9; i < 12; i++) session.remember(exchange("old-" + i, i + 1, "evidence " + i));
        var next = compactor.plan(session);
        assertNotNull(next);
        assertEquals(4, next.getFromIndex());
        assertEquals(session.contextSummary().getNotes(), next.getPreviousSummary().getNotes());
        var updated = compactor.validate(next, List.of(note("old-0", "evidence 0"), note("old-4", "evidence 4")), session);
        assertEquals(2, updated.getRevision());
        assertTrue(updated.getCoveredExchanges() > 4);
    }

    @Test
    void rejectsQuotesOutsideThisCallsVisibleInputEvenIfTheyExistInRawHistory() {
        var session = new AgentSession("inspect");
        session.remember(exchange("old", 1, "head\n" + "x".repeat(5000) + "HIDDEN_QUOTE" + "y".repeat(5000)));
        for (int i = 2; i <= 8; i++) session.remember(exchange("other-" + i, i, "other evidence"));
        var compactor = new ContextCompactor(new ContextBudget(7000));
        var request = compactor.plan(session);
        assertNotNull(request);
        assertFalse(request.getObservations().getFirst().result().content().contains("HIDDEN_QUOTE"));
        assertThrows(IllegalArgumentException.class,
                () -> compactor.validate(request, List.of(note("old", "HIDDEN_QUOTE")), session));
    }

    @Test
    void inputLimitDoesNotSplitAnOlderBatch() throws Exception {
        var session = pressured();
        // Oversized arguments: even an excerpted observation cannot fit the summary input.
        var huge = new ToolExchange(new ToolCall("huge", "read_file", Map.of("path", "p".repeat(25000))),
                new ToolResult(true, "evidence"), null, 1);
        var raw = new AgentSession("inspect");
        raw.remember(huge);
        for (int i = 2; i <= 8; i++) raw.remember(exchange("other-" + i, i, "current"));
        assertNull(new ContextCompactor(new ContextBudget(7000)).plan(raw));
        var request = new ContextCompactor(new ContextBudget(7000)).plan(session);
        assertTrue(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(request).length()
                <= ContextCompactor.MAX_INPUT_CHARACTERS);
        var split = new ContextSummary(1, 1, List.of(note("first", "evidence")));
        assertThrows(IllegalArgumentException.class, () -> split.validateAgainst(List.of(
                exchange("first", 1, "evidence"), exchange("second", 1, "evidence"))));
    }

    @Test
    void staleSummarySourcesAreMarkedAndDoNotBecomeFreshFileEvidence() {
        var session = pressured();
        session.installSummary(new ContextSummary(1, 1, List.of(note("old-0", "evidence 0"))));
        session.transitionTo(AgentSession.Status.RUNNING);
        session.transitionTo(AgentSession.Status.WAITING_FOR_TOOL);
        session.invalidateFileEvidence("Main.java");
        session.remember(new ToolExchange(new ToolCall("patch", "apply_patch", Map.of("path", "Main.java")),
                new ToolResult(true, "patched"), null, 99));
        var request = new ContextAssembler(new ContextBudget(7000)).assemble(session, List.of(), 2);
        assertTrue(request.staleSummarySourceIds().contains("old-0"));
        assertNull(request.contextSummary());
        assertFalse(session.hasCurrentRead("Main.java"));
    }

    @Test
    void rejectsSummaryThatExceedsReservationOrDoesNotShrink() {
        var session = pressured();
        var compactor = new ContextCompactor(new ContextBudget(7000));
        var request = compactor.plan(session);
        var large = new SummaryNote(SummaryNote.Kind.PROGRESS, "s".repeat(600), "old-0", "x".repeat(400));
        assertThrows(IllegalArgumentException.class,
                () -> compactor.validate(request, List.of(large,
                        new SummaryNote(SummaryNote.Kind.PROGRESS, "t".repeat(600), "old-0", "x".repeat(400))), session));
        var tiny = new AgentSession("inspect");
        tiny.remember(exchange("tiny", 0, "evidence"));
        var tinyRequest = new SummaryRequest("inspect", null, 0, 1, 2000, tiny.history());
        var expanded = new SummaryNote(SummaryNote.Kind.PROGRESS, "s".repeat(600), "tiny", "evidence");
        assertThrows(IllegalArgumentException.class,
                () -> compactor.validate(tinyRequest, List.of(expanded), tiny));
    }

    @Test
    void persistenceFailureAfterValidatedSummaryStopsBeforeTaskCall() {
        SessionEventSink sink = new SessionEventSink() {
            public void append(AgentSession s, AgentSession.Event e, Object payload) { }
            public void checkpoint(AgentSession s) { throw new PersistenceException("disk failure", null); }
        };
        var session = new AgentSession("inspect", sink);
        for (var exchange : pressured().history()) { session.remember(exchange); }
        assertThrows(PersistenceException.class, () -> new AgentRuntime(model(
                request -> List.of(note("old-0", "evidence 0")), request -> {
                    fail("Task call must not occur after checkpoint failure"); return ModelResponse.finish("bad");
                }), List.of(), 3, new ContextBudget(7000)).run(session));
        assertEquals(1, session.modelCalls());
    }
    @Test
    void rejectsNewPlanningNotesEvenWhenTheyReferenceValidInput() {
        var session = pressured();
        var c = new ContextCompactor(new ContextBudget(7000));
        var request = c.plan(session);
        var plan = new SummaryNote(SummaryNote.Kind.NEXT_STEP, "Run tests next", "old-0", "evidence 0");
        assertThrows(IllegalArgumentException.class, () -> c.validate(request, List.of(plan), session));
    }

    @Test
    void validatesAllSourcesBeforeDedupAndInstallsOnlyUniqueNotes() {
        var session = pressured();
        var compactor = new ContextCompactor(new ContextBudget(7000));
        var request = compactor.plan(session);
        var a = note("old-0", "evidence 0");
        assertEquals(1, compactor.validate(request, List.of(a, a), session).getNotes().size());
        assertThrows(IllegalArgumentException.class, () -> compactor.validate(request,
                List.of(a, note("missing", "evidence 0")), session));
        new AgentRuntime(model(r -> List.of(a, a), r -> ModelResponse.finish("done")),
                List.of(), 3, new ContextBudget(7000)).run(session);
        assertEquals(1, session.contextSummary().getNotes().size());
        assertTrue(session.events().stream().anyMatch(e -> e.type() == AgentSession.EventType.COMPACTION_COMPLETED
                && e.detail().contains("removedDuplicates=1")));
        assertEquals(8, session.history().size());
    }

}
