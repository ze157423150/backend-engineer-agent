package dev.backendagent.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import dev.backendagent.model.*;
import dev.backendagent.persistence.FileSessionStore;
import dev.backendagent.tools.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ContextWindowTest {
    @TempDir Path root;
    private Path data() { return root.resolve("sessions"); }
    private Workspace workspace() throws Exception { return new Workspace(root, root.resolve("agent-local.properties"), data()); }
    private Tool tool() {
        return new Tool() {
            public String name() { return "read_file"; }
            public ToolResult execute(Map<String, String> args) {
                return new ToolResult(true, "evidence-" + args.get("path") + "\n" + "x".repeat(2600));
            }
        };
    }
    private ToolCall call(String id) { return new ToolCall(id, "read_file", Map.of("path", id)); }
    private AgentSession fill(FileSessionStore store, int batches, boolean multi) {
        var session = new AgentSession("window test", store);
        store.create(session);
        var turns = new AtomicInteger();
        new AgentRuntime(request -> {
            int turn = turns.getAndIncrement();
            return multi ? ModelResponse.callTools(List.of(call("a" + turn), call("b" + turn)), null)
                    : ModelResponse.callTool(call("r" + turn));
        }, List.of(tool()), batches).run(session);
        assertEquals(AgentSession.Status.BUDGET_EXHAUSTED, session.status());
        return session;
    }
    private SummaryNote note(String id) {
        return new SummaryNote(SummaryNote.Kind.PROGRESS, "observed " + id, id, "evidence-" + id);
    }

    @Test
    void recentBodyMiddleSummaryAndDistantArchiveStaySeparateEvenWithLargeBudget() {
        try (var store = new FileSessionStore(data())) {
            var session = fill(store, 16, false);
            session.installSummary(new ContextSummary(1, 15, List.of(note("r0"), note("r5"), note("r14"))));
            var view = new ContextAssembler(new ContextBudget(100000)).assemble(session, List.of(), 10);
            assertEquals(List.of("r12", "r13", "r14", "r15"), view.history().stream().map(exchange -> exchange.call().id()).toList());
            assertEquals(List.of("r5"), view.contextSummary().getNotes().stream().map(SummaryNote::getSourceCallId).toList());
            assertFalse(view.contextProjection().getRecoveryReferences().contains("call_id=r0,"));
            assertTrue(view.contextProjection().getRecoveryReferences().contains("call_id=r4,"));
            assertEquals(3, view.contextProjection().getPolicyVersion());
            view.contextProjection().validateAgainst(session.history());
            assertEquals(3, session.contextSummary().getNotes().size()); // Persistence is not rewritten by an age-filtered view.
            assertTrue(session.originalObservation("r0").result().content().contains("evidence-r0"));
        }
    }

    @Test
    void windowsCountCompleteBatchesAndBudgetDropsWholeRecentBatchesWithoutExcerpting() {
        try (var store = new FileSessionStore(data())) {
            var session = fill(store, 9, true);
            var view = new ContextAssembler(new ContextBudget(100000)).assemble(session, List.of(), 10);
            assertEquals(8, view.history().size());
            assertEquals("a5", view.history().getFirst().call().id());
            var tight = new ContextAssembler(new ContextBudget(6000)).assemble(session, List.of(), 10);
            assertEquals(2, tight.history().size());
            assertEquals("a8", tight.history().getFirst().call().id());
            assertEquals(0, tight.contextProjection().getCompressedExchanges());
            assertTrue(tight.history().stream().allMatch(exchange -> exchange.result().content().length() > 2600));
            tight.contextProjection().validateAgainst(session.history());
        }
    }

    @Test
    void plansOnlyMiddleWindowAndRehydratesOriginalsForSummaryInput() {
        try (var store = new FileSessionStore(data())) {
            var session = fill(store, 16, false);
            var plan = new ContextCompactor(new ContextBudget(64000)).plan(session);
            assertNotNull(plan);
            assertEquals(4, plan.getFromIndex());
            assertTrue(plan.getToIndex() <= 12);
            assertFalse(plan.getObservations().stream().anyMatch(exchange -> exchange.call().id().equals("r0")
                    || exchange.call().id().equals("r12")));
            assertTrue(session.history().get(4).result().content().contains("ARCHIVED_BODY"));
            assertTrue(plan.getObservations().stream().noneMatch(exchange -> exchange.result().content().contains("ARCHIVED_BODY")));
            var candidate = new ContextCompactor(new ContextBudget(64000)).validate(plan, List.of(note("r4")), session);
            assertEquals(plan.getToIndex(), candidate.getCoveredExchanges());
            assertThrows(IllegalArgumentException.class, () -> new ContextCompactor(new ContextBudget(64000))
                    .validate(plan, List.of(note("r0")), session));
        }
    }

    @Test
    void failedSummaryDoesNotRepeatOnSameWindowAfterCheckpointResume() throws Exception {
        UUID id;
        try (var store = new FileSessionStore(data())) {
            store.bindWorkspace(workspace());
            var session = fill(store, 8, false);
            store.saveSnapshot(session);
            id = session.id();
        }
        var attempts = new AtomicInteger();
        try (var store = new FileSessionStore(data())) {
            var session = store.restore(id, workspace(), 10);
            var model = new ModelClient() {
                public boolean supportsSummarization() { return true; }
                public List<SummaryNote> summarize(SummaryRequest request) {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("simulated failure");
                }
                public ModelResponse execute(ModelRequest request) {
                    assertNull(request.contextSummary());
                    assertEquals(4, request.history().size());
                    return ModelResponse.callTool(call("r8"));
                }
            };
            new AgentRuntime(model, List.of(tool()), 10).resume(session);
            assertEquals(1, attempts.get());
            assertEquals(AgentSession.Status.BUDGET_EXHAUSTED, session.status());
            store.saveSnapshot(session);
        }
        try (var store = new FileSessionStore(data())) {
            var session = store.restore(id, workspace(), 12);
            new AgentRuntime(new ModelClient() {
                public boolean supportsSummarization() { return true; }
                public List<SummaryNote> summarize(SummaryRequest request) { fail("Same failed window must not be retried"); return List.of(); }
                public ModelResponse execute(ModelRequest request) { return ModelResponse.finish("continued via archive"); }
            }, List.of(), 12).resume(session);
            assertEquals(AgentSession.Status.COMPLETED, session.status());
            assertEquals(4, session.lastWindowSummaryAttemptEnd());
        }
    }

    @Test
    void noModelCallUntilFourPendingBatchesAndAgedOrStaleNotesCannotReturn() {
        try (var store = new FileSessionStore(data())) {
            var session = fill(store, 7, false);
            assertNull(new ContextCompactor(new ContextBudget(64000)).plan(session));
            assertEquals(0, session.lastWindowSummaryAttemptEnd());
            var old = new ContextSummary(1, 3, List.of(note("r0"), note("r1")));
            assertNull(ContextWindowPolicy.visibleSummary(old, session.history(), java.util.Set.of("r0", "r1")));
            assertEquals(2, old.getNotes().size());
        }
    }
    @Test
    void memoryAndDurableSessionsProduceTheSamePlansAndViews() throws Exception {
        try (var store = new FileSessionStore(data())) {
            var durable = fill(store, 16, true);
            var memory = new AgentSession(durable.objective());
            for (var saved : durable.history()) memory.remember(durable.originalObservation(saved.call().id()));
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            for (int limit : List.of(64000, 6000)) {
                var compactor = new ContextCompactor(new ContextBudget(limit));
                assertEquals(json.writeValueAsString(compactor.plan(durable)), json.writeValueAsString(compactor.plan(memory)));
                var assembler = new ContextAssembler(new ContextBudget(limit));
                var diskView = assembler.assemble(durable, List.of(), 10);
                var memoryView = assembler.assemble(memory, List.of(), 10);
                assertEquals(json.writeValueAsString(diskView), json.writeValueAsString(memoryView));
                assertEquals(3, memoryView.contextProjection().getPolicyVersion());
            }
            var summary = new ContextSummary(1, 15, List.of(note("a0"), note("a5"), note("a14")));
            durable.installSummary(summary);
            memory.installSummary(summary);
            var assembler = new ContextAssembler(new ContextBudget(64000));
            assertEquals(json.writeValueAsString(assembler.assemble(durable, List.of(), 10)),
                    json.writeValueAsString(assembler.assemble(memory, List.of(), 10)));
        }
    }

}
