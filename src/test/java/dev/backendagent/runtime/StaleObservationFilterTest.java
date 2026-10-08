package dev.backendagent.runtime;

import java.util.List;
import java.util.Map;
import dev.backendagent.model.*;
import dev.backendagent.memory.MemoryFact;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StaleObservationFilterTest {
    private ToolExchange entry(String id, String tool, String path, int turn, boolean successful, String content) {
        return new ToolExchange(new ToolCall(id, tool, Map.of("path", path)), new ToolResult(successful, content), null, turn);
    }
    private ToolExchange retrieve(String id, String source, int turn) {
        return new ToolExchange(new ToolCall(id, "read_observation", Map.of("call_id", source)),
                new ToolResult(true, "HISTORICAL_OBSERVATION stale=true\nOLD_BODY"), null, turn);
    }
    private AgentSession session(ToolExchange... history) {
        var session = new AgentSession("inspect");
        for (var entry : history) { session.remember(entry); }
        return session;
    }
    private ModelRequest assemble(AgentSession session) {
        return new ContextAssembler(new ContextBudget(20000)).assemble(session, List.of(), 3);
    }

    @Test
    void sameBatchWriteHidesOldReadsSearchesAndTestsWithoutBreakingPairing() {
        var read = entry("read", "read_file", "./Main.java", 1, true, "OLD_BODY");
        var search = entry("search", "search_code", ".", 1, true, "OLD_SEARCH");
        var tests = entry("tests", "run_tests", ".", 1, false, "OLD_TEST_FAILURE");
        var patch = entry("patch", "apply_patch", "Main.java", 1, true, "write succeeded");
        var fresh = entry("fresh", "read_file", "Main.java", 1, true, "CURRENT_BODY");
        var session = session(read, search, tests, patch, fresh);
        var request = assemble(session);
        assertEquals(5, request.history().size());
        assertEquals(3, request.contextProjection().getFilteredExchanges());
        for (int i = 0; i < 3; i++) {
            var original = session.history().get(i);
            var projected = request.history().get(i);
            assertEquals(original.call(), projected.call());
            assertEquals(original.result().successful(), projected.result().successful());
            assertEquals(1, projected.modelCallNumber());
            assertTrue(projected.result().content().contains("STALE_OBSERVATION_BODY_OMITTED"));
            assertFalse(projected.result().content().contains(original.result().content()));
        }
        assertEquals(patch, request.history().get(3));
        assertEquals(fresh, request.history().get(4));
        assertEquals(List.of(read, search, tests, patch, fresh), session.history());
        request.contextProjection().validateAgainst(session.history());
    }

    @Test
    void failedWritesAndUnrelatedReadsRemainUnchangedAndNewFilesInvalidateSearches() {
        var main = entry("main", "read_file", "Main.java", 1, true, "main body");
        var other = entry("other", "read_file", "Other.java", 1, true, "other body");
        var search = entry("search", "search_code", ".", 1, true, "no match");
        var rejected = entry("rejected", "apply_patch", "Main.java", 2, false, "rejected");
        var failed = session(main, other, search, rejected);
        assertEquals(failed.history(), assemble(failed).history());
        failed.remember(entry("create", "create_file", "New.java", 3, true, "created"));
        var projected = assemble(failed).history();
        assertEquals(main, projected.get(0));
        assertEquals(other, projected.get(1));
        assertTrue(projected.get(2).result().content().contains("STALE_OBSERVATION_BODY_OMITTED"));
        assertEquals(rejected, projected.get(3));
    }

    @Test
    void explicitRetrievalIsVisibleForLatestBatchThenOldAliasesAreSuppressed() {
        var session = session(entry("read", "read_file", "Main.java", 1, true, "OLD_BODY"),
                entry("patch", "apply_patch", "Main.java", 2, true, "patched"), retrieve("copy", "read", 3));
        var current = assemble(session).history().getLast().result().content();
        assertTrue(current.contains("EXPLICIT_HISTORICAL_RETRIEVAL: STALE"));
        assertTrue(current.contains("OLD_BODY"));
        session.remember(retrieve("copy-again", "copy", 4));
        var next = assemble(session).history();
        assertFalse(next.get(2).result().content().contains("OLD_BODY"));
        assertTrue(next.getLast().result().content().contains("OLD_BODY"));
        session.remember(entry("latest", "list_files", ".", 5, true, "Main.java"));
        next = assemble(session).history();
        assertFalse(next.get(3).result().content().contains("OLD_BODY"));
        assertEquals("HISTORICAL_OBSERVATION stale=true\nOLD_BODY", session.history().get(2).result().content());
    }

    @Test
    void filtersSummaryAndMemoryViewsWhileKeepingOriginalState() {
        var session = new AgentSession("inspect");
        var read = entry("read", "read_file", "Main.java", 0, true, "OLD_BODY");
        session.importVerifiedMemory(new MemoryFact("old finding", "original", "read_file", "Main.java", "OLD_BODY"),
                read, java.util.UUID.randomUUID());
        session.remember(entry("other", "read_file", "Other.java", 1, true, "CURRENT_BODY"));
        session.remember(entry("patch", "apply_patch", "Main.java", 2, true, "patched"));
        var staleNote = new SummaryNote(SummaryNote.Kind.PROGRESS, "old conclusion", "read", "OLD_BODY");
        var currentNote = new SummaryNote(SummaryNote.Kind.OPEN_ISSUE, "current issue", "other", "CURRENT_BODY");
        var summary = new ContextSummary(1, 2, List.of(staleNote, currentNote));
        session.installSummary(summary);
        for (int i = 3; i <= 6; i++) session.remember(entry("recent-" + i, "list_files", ".", i, true, "Main.java"));
        var request = assemble(session);
        assertNull(request.contextSummary()); // Current original is retained; do not duplicate its note.
        assertEquals(java.util.Set.of("read"), request.staleSummarySourceIds());
        assertTrue(request.workingMemory().isEmpty());
        assertEquals(2, session.contextSummary().getNotes().size());
        assertEquals(1, session.memoryFacts().size()); // View filtering does not mutate persisted source data.
    }

    @Test
    void filteredLatestBodyFitsBudgetAndOldProjectionVersionRemainsReplayable() {
        var raw = List.of(entry("old", "read_file", "Main.java", 1, true, "x".repeat(12000)),
                entry("patch", "apply_patch", "Main.java", 1, true, "patched"));
        var session = session(raw.toArray(ToolExchange[]::new));
        var projection = new ContextAssembler(new ContextBudget(1000)).assemble(session, List.of(), 2).contextProjection();
        assertEquals(0, projection.getOmittedExchanges());
        assertEquals(4, projection.getPolicyVersion());
        assertTrue(projection.getHistoryCharacters() <= 1000);
        projection.validateAgainst(raw);
        var legacy = new ContextAssembler(new ContextBudget(20000)).project(raw, false);
        assertEquals(1, legacy.getPolicyVersion());
        assertEquals(raw, legacy.getHistory());
        legacy.validateAgainst(raw);
    }

    @Test
    void rejectsForgedPlaceholderAndReplaysSavedPrefixBeforeLaterWrites() throws Exception {
        var session = session(entry("old", "read_file", "Main.java", 1, true, "old bytes"));
        var before = assemble(session).contextProjection();
        session.remember(entry("patch", "apply_patch", "Main.java", 2, true, "patched"));
        before.validateAgainst(session.history());
        var after = assemble(session).contextProjection();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var tree = json.valueToTree(after);
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("history").get(0).path("result"))
                .put("content", "forged placeholder");
        var forged = json.treeToValue(tree, ContextProjection.class);
        assertThrows(IllegalArgumentException.class, () -> forged.validateAgainst(session.history()));
        after.validateAgainst(session.history());
    }

    @Test
    void summaryInputRemovesExpiredPreviousNotesAndRejectsResurrectingTheirQuotes() {
        var session = session(entry("old", "read_file", "Main.java", 1, true, "OLD_BODY"),
                entry("other", "read_file", "Other.java", 2, true, "current other\n" + "x".repeat(3000)),
                entry("patch", "apply_patch", "Main.java", 3, true, "patched"),
                entry("more", "read_file", "Other.java", 4, true, "more\n" + "x".repeat(3000)),
                entry("latest", "list_files", ".", 5, true, "Main.java"));
        for (int i = 6; i <= 9; i++) session.remember(entry("recent-" + i, "list_files", ".", i, true, "Main.java"));
        session.installSummary(new ContextSummary(1, 1, List.of(
                new SummaryNote(SummaryNote.Kind.PROGRESS, "old conclusion", "old", "OLD_BODY"))));
        var compactor = new ContextCompactor(new ContextBudget(4000));
        var request = compactor.plan(session);
        assertNotNull(request);
        assertNull(request.getPreviousSummary());
        assertTrue(request.getObservations().stream().noneMatch(e -> e.result().content().contains("OLD_BODY")));
        assertThrows(IllegalArgumentException.class, () -> compactor.validate(request, List.of(
                new SummaryNote(SummaryNote.Kind.PROGRESS, "old conclusion", "old", "OLD_BODY")), session));
        var valid = compactor.validate(request, List.of(
                new SummaryNote(SummaryNote.Kind.OPEN_ISSUE, "other issue", "other", "current other")), session);
        assertEquals(2, valid.getRevision());
    }
}
