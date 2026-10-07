package dev.backendagent.runtime;

import dev.backendagent.model.*;
import dev.backendagent.persistence.FileSessionStore;
import dev.backendagent.tools.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ShortSummarySkipTest {
    @TempDir Path root;
    private AgentSession history(int length) {
        var s = new AgentSession("inspect");
        for (int i = 0; i < 8; i++) add(s, "r" + i, i + 1, "s".repeat(length));
        return s;
    }
    private void add(AgentSession s, String id, int batch, String text) {
        s.remember(new ToolExchange(new ToolCall(id, "read_file", Map.of("path", "x".repeat(1000))), new ToolResult(true, text), null, batch));
    }
    private ModelClient finishOnly() {
        return new ModelClient() {
            public boolean supportsSummarization() { return true; }
            public List<SummaryNote> summarize(SummaryRequest r) { fail("Short input must not call summary model"); return List.of(); }
            public ModelResponse execute(ModelRequest r) { return ModelResponse.finish("done"); }
        };
    }
    @Test
    void thresholdCountsBodyInsteadOfMetadataAndIncludesEquality() {
        var s = history(10);
        var skip = new ContextCompactor(new ContextBudget(64000), 41).decide(s);
        assertEquals(SummaryPlan.Reason.TOO_SHORT, skip.getReason());
        assertEquals(40, skip.getInputCharacters());
        assertEquals(4, skip.getProcessedEnd());
        assertEquals(SummaryPlan.Reason.READY, new ContextCompactor(new ContextBudget(64000), 40).decide(s).getReason());
    }
    @Test
    void skipsWithoutApiAndPreservesExistingSummary() {
        var s = history(10);
        for (int i = 8; i < 12; i++) add(s, "r" + i, i + 1, "short");
        var previous = new ContextSummary(1, 4, List.of(new SummaryNote(SummaryNote.Kind.PROGRESS, "Read file", "r0", "s")));
        s.installSummary(previous);
        new AgentRuntime(finishOnly(), List.of(), 2).run(s);
        assertSame(previous, s.contextSummary());
        assertEquals(1, s.modelCalls());
        assertEquals(8, s.lastWindowSummarySkippedEnd());
        assertTrue(s.events().stream().noneMatch(e -> e.type() == AgentSession.EventType.COMPACTION_STARTED));
        assertTrue(s.originalObservation("r4").result().content().contains("ssss"));
    }
    @Test
    void waitsForNewBatchesAndDoesNotResendSkippedPrefix() {
        var s = history(10);
        var c = new ContextCompactor(new ContextBudget(64000));
        var skip = c.decide(s);
        s.append(AgentSession.EventType.WINDOW_SUMMARY_SKIPPED, Integer.toString(skip.getProcessedEnd()), skip);
        assertEquals(SummaryPlan.Reason.WAITING, c.decide(s).getReason());
        for (int i = 8; i < 12; i++) add(s, "r" + i, i + 1, "next");
        var next = c.decide(s);
        assertEquals(SummaryPlan.Reason.TOO_SHORT, next.getReason());
        assertEquals(8, next.getProcessedEnd());
        assertEquals(40, next.getInputCharacters());
    }
    @Test
    void longInputStillProducesPlan() {
        assertEquals(SummaryPlan.Reason.READY, new ContextCompactor(new ContextBudget(64000)).decide(history(2000)).getReason());
    }
    @Test
    void skippedBoundarySurvivesCheckpointRestoreAndOriginalIsRetrievable() throws Exception {
        UUID id;
        var turns = new AtomicInteger();
        Tool tool = new Tool() {
            public String name() { return "list_files"; }
            public ToolResult execute(Map<String, String> args) { return new ToolResult(true, "short observation"); }
        };
        ModelClient model = new ModelClient() {
            public boolean supportsSummarization() { return true; }
            public List<SummaryNote> summarize(SummaryRequest r) { fail("No summary for short history"); return List.of(); }
            public ModelResponse execute(ModelRequest r) { return ModelResponse.callTool(new ToolCall("list-" + turns.incrementAndGet(), "list_files", Map.of())); }
        };
        Path data = root.resolve("sessions");
        try (var store = new FileSessionStore(data)) {
            store.bindWorkspace(new Workspace(root, root.resolve("agent-local.properties"), data));
            var s = new AgentSession("inspect", store);
            store.create(s);
            new AgentRuntime(model, List.of(tool), 10).run(s);
            assertEquals(AgentSession.Status.BUDGET_EXHAUSTED, s.status());
            assertEquals(4, s.lastWindowSummarySkippedEnd());
            assertEquals(10, s.modelCalls());
            store.saveSnapshot(s);
            id = s.id();
        }
        try (var store = new FileSessionStore(data)) {
            var s = store.restore(id, new Workspace(root, root.resolve("agent-local.properties"), data), 12);
            assertEquals(4, s.lastWindowSummarySkippedEnd());
            new AgentRuntime(finishOnly(), List.of(), 12).resume(s);
            assertEquals(AgentSession.Status.COMPLETED, s.status());
            assertEquals(1, s.events().stream().filter(e -> e.type() == AgentSession.EventType.WINDOW_SUMMARY_SKIPPED).count());
            assertEquals("short observation", new dev.backendagent.history.HistoryArchiveReader(store.observationArchive(s), null).read("list-1").getExchange().result().content());
        }
    }
}
