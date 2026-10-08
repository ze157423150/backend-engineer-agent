package dev.backendagent.runtime;

import dev.backendagent.history.ObservationArchive;
import dev.backendagent.model.*;
import dev.backendagent.persistence.SessionEventSink;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StaleSummaryInputTest {
    private final List<ToolExchange> archive = new ArrayList<>();
    private AgentSession durable() {
        return new AgentSession("inspect current files", new SessionEventSink() {
            public void append(AgentSession s, AgentSession.Event e, Object p) { }
            public ObservationArchive observationArchive(AgentSession s) {
                return visitor -> { for (int i = 0; i < archive.size(); i++) visitor.accept(i + 1, archive.get(i)); };
            }
        });
    }
    private void add(AgentSession session, String id, String tool, Map<String, String> args, int batch, String text) {
        session.remember(new ToolExchange(new ToolCall(id, tool, args), new ToolResult(true, text), null, batch));
        archive.add(session.history().getLast());
    }
    private void read(AgentSession s, String id, String path, int batch) {
        add(s, id, "read_file", Map.of("path", path), batch, "evidence-" + id + "\n" + "x".repeat(3000));
    }
    private void patch(AgentSession s, int batch) {
        add(s, "patch", "apply_patch", Map.of("path", "old.txt"), batch, "patched");
    }
    private ModelClient noSummaryModel() {
        return new ModelClient() {
            public boolean supportsSummarization() { return true; }
            public List<SummaryNote> summarize(SummaryRequest r) { fail("Stale-only input must not call the model"); return List.of(); }
            public ModelResponse execute(ModelRequest r) { return ModelResponse.finish("continued"); }
        };
    }

    @Test
    void staleOnlyMiddleWindowSkipsModelAndKeepsArchiveAvailable() throws Exception {
        var s = durable();
        for (int i = 1; i <= 8; i++) read(s, "old" + i, "old.txt", i);
        patch(s, 9);
        for (int i = 10; i <= 12; i++) read(s, "recent" + i, "current.txt", i);
        s.releaseOldBodies();
        assertNull(s.history().getFirst().archivedBody()); // Short history remains inline, stale body still filtered.
        assertNull(new ContextCompactor(new ContextBudget(64000)).plan(s));
        new AgentRuntime(noSummaryModel(), List.of(), 2).run(s);
        assertEquals(1, s.modelCalls());
        assertTrue(s.events().stream().noneMatch(e -> e.type() == AgentSession.EventType.COMPACTION_STARTED));
        assertTrue(s.originalObservation("old1").result().content().contains("evidence-old1"));
        var found = new dev.backendagent.history.HistoryArchiveReader(visitor -> {
            for (int i = 0; i < archive.size(); i++) visitor.accept(i + 1, archive.get(i));
        }, null).read("old1");
        assertNotNull(found);
        assertTrue(found.getExchange().result().content().contains("evidence-old1"));
    }

    @Test
    void mixedBatchKeepsBoundariesButOmitsStaleReadsAndRetrievalAliases() {
        var s = durable();
        read(s, "old1", "old.txt", 1);
        read(s, "valid", "current.txt", 1);
        for (int i = 2; i <= 7; i++) read(s, "old" + i, "old.txt", i);
        patch(s, 8);
        add(s, "alias", "read_observation", Map.of("call_id", "old1"), 8, "historical evidence-old1");
        for (int i = 9; i <= 12; i++) read(s, "recent" + i, "current.txt", i);
        s.releaseOldBodies();
        var c = new ContextCompactor(new ContextBudget(64000));
        var request = c.plan(s);
        assertNotNull(request);
        assertEquals(0, request.getFromIndex());
        assertEquals(10, request.getToIndex());
        assertEquals(List.of("valid", "patch"), request.getObservations().stream().map(x -> x.call().id()).toList());
        assertTrue(request.getObservations().stream().noneMatch(x -> x.result().content().contains("STALE_OBSERVATION_BODY_OMITTED")));
        var summary = c.validate(request, List.of(new SummaryNote(SummaryNote.Kind.PROGRESS,
                "Observed current file", "valid", "evidence-valid")), s);
        assertEquals(10, summary.getCoveredExchanges());
        assertThrows(IllegalArgumentException.class, () -> c.validate(request,
                List.of(new SummaryNote(SummaryNote.Kind.PROGRESS, "Old file", "old1", "evidence-old1")), s));
    }

    @Test
    void inMemoryStaleOnlyWindowAlsoSkipsModel() {
        var s = new AgentSession("inspect");
        for (int i = 0; i < 8; i++) read(s, "old" + i, "old.txt", i + 1);
        patch(s, 9);
        for (int i = 10; i <= 12; i++) read(s, "recent" + i, "current.txt", i);
        assertNull(new ContextCompactor(new ContextBudget(64000)).plan(s));
        new AgentRuntime(noSummaryModel(), List.of(), 2, new ContextBudget(64000)).run(s);
        assertEquals(1, s.modelCalls());
    }

    @Test
    void inMemoryMixedWindowRetainsOnlyValidSources() {
        var s = new AgentSession("inspect");
        read(s, "old", "old.txt", 1);
        read(s, "valid", "current.txt", 2);
        read(s, "valid2", "current.txt", 3);
        read(s, "valid3", "current.txt", 4);
        read(s, "old2", "old.txt", 5);
        patch(s, 6);
        for (int i = 7; i <= 10; i++) read(s, "recent" + i, "current.txt", i);
        var c = new ContextCompactor(new ContextBudget(64000));
        var request = c.plan(s);
        assertNotNull(request);
        assertTrue(request.getObservations().stream().anyMatch(x -> x.call().id().equals("valid")));
        assertTrue(request.getObservations().stream().noneMatch(x -> x.call().id().startsWith("old")));
        assertTrue(request.getObservations().size() < request.getToIndex() - request.getFromIndex());
        assertNotNull(c.validate(request, List.of(new SummaryNote(SummaryNote.Kind.PROGRESS,
                "Observed current file", "valid", "evidence-valid")), s));
    }
}
