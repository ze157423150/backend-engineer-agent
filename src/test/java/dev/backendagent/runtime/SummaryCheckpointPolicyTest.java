package dev.backendagent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.backendagent.model.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SummaryCheckpointPolicyTest {
    @Test void retrievalBookkeepingIsNotPromotedFromAnOldCheckpoint() {
        var session=new AgentSession("audit history");
        session.remember(new ToolExchange(new ToolCall("index","search_history",Map.of("keyword","rule")),
                new ToolResult(true,"old index says body omitted"),null,1));
        var summary=new ContextSummary(1,1,List.of(new SummaryNote(SummaryNote.Kind.OPEN_ISSUE,
                "body must be extracted","index","body omitted")));
        session.installSummary(summary);
        assertNull(ContextWindowPolicy.visibleSummary(summary,session.history(),java.util.Set.of()));
        assertEquals(1,session.contextSummary().getNotes().size());
    }
    private ToolExchange read(String id, int batch, String path, String body) {
        return new ToolExchange(new ToolCall(id, "read_file", Map.of("path", path)), new ToolResult(true, body), null, batch);
    }

    @Test void auditQuotesRemainDurableButDoNotConsumeTheModelSummaryBudget() throws Exception {
        var notes = java.util.stream.IntStream.range(0, 12).mapToObj(i -> new SummaryNote(SummaryNote.Kind.PROGRESS,
                "fact " + i, "r" + i, "quoted evidence ".repeat(24), SummarySection.FINDINGS)).toList();
        var summary = new ContextSummary(1, 12, notes);
        assertTrue(summary.referenceJson().length() > 4000);
        assertTrue(summary.characterCount() < 1500);
        assertFalse(summary.contextData().toString().contains("evidenceQuote"));
        assertEquals(1, summary.contextData().path("sections").size());
        var restored = new ObjectMapper().readValue(summary.referenceJson(), ContextSummary.class);
        assertEquals(12, restored.getNotes().size());
        assertEquals(notes.getFirst().getEvidenceQuote(), restored.getNotes().getFirst().getEvidenceQuote());
        assertEquals(SummarySection.FINDINGS, restored.getNotes().getFirst().getSection());
    }

    @Test void carriesAnOlderValidatedFactButCannotResurrectItAfterAWrite() {
        var session = new AgentSession("inspect current rules");
        session.remember(read("old", 1, "rules.md", "keep this rule\n"));
        session.installSummary(new ContextSummary(1, 1, List.of(new SummaryNote(SummaryNote.Kind.PROGRESS,
                "rule still applies", "old", "keep this rule", SummarySection.CONSTRAINTS))));
        for (int i = 2; i <= 18; i++) session.remember(read("r"+i, i, "other.md", "fresh detail " + i + "\n" + "body\n".repeat(100)));
        var compactor = new ContextCompactor(new ContextBudget(64000), 1);
        var request = compactor.plan(session);
        assertNotNull(request);
        assertEquals("old", request.getPreviousSummary().getNotes().getFirst().getSourceCallId());
        var updated = compactor.validate(request, request.getPreviousSummary().getNotes(), session);
        session.installSummary(updated);
        assertSame(updated, session.contextSummary());
        session.remember(new ToolExchange(new ToolCall("write", "apply_patch", Map.of("path", "rules.md")),
                new ToolResult(true, "changed"), null, 19));
        assertNull(ContextWindowPolicy.visibleSummary(updated, session.history(), StaleObservationFilter.staleIds(session.history())));
        assertThrows(IllegalArgumentException.class, () -> compactor.validate(request, updated.getNotes(), session));
    }

    @Test void pressurePruningIncludesLatestResultsAndKeepsReadMetadataHonest() {
        var raw = new ToolExchange(new ToolCall("read", "read_file", Map.of("path", "Main.java")),
                new ToolResult(true, "head\n" + "middle\n".repeat(3000) + "tail", new FileFingerprint("Main.java", "a".repeat(64)),
                        null, new FileReadMetadata(3002, 1, 3002, true, true, false)), null, 1);
        var session = new AgentSession("inspect");session.remember(raw);
        var view = new ContextAssembler(new ContextBudget(6000)).assemble(session, List.of(), 2);
        assertEquals(1, view.contextProjection().getCompressedExchanges());
        assertFalse(view.history().getFirst().result().readMetadata().isBodyComplete());
        assertTrue(session.history().getFirst().result().readMetadata().isBodyComplete());
        view.contextProjection().validateAgainst(session.history());
        // A saved older projection retains the historical behavior and remains replayable.
        var legacy = new ContextAssembler(new ContextBudget(30000)).project(session.history(), 5);
        assertEquals(session.history().getFirst(), legacy.getHistory().getFirst());legacy.validateAgainst(session.history());
    }
}
