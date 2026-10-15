package dev.backendagent.model;

import dev.backendagent.history.ArchivedBody;
import dev.backendagent.history.HistoricalEvidence;
import dev.backendagent.runtime.ObservationEvidence.Validity;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HistoricalEvidenceNoticeTest {
    private ToolExchange source(String id, String text) {
        return new ToolExchange(new ToolCall(id, "read_file", Map.of("path", "Main.java")),
                new ToolResult(true, text), null, 1);
    }
    private ToolExchange hidden(String id, String tool, String sourceId) {
        return new ToolExchange(new ToolCall(id, tool, Map.of("call_id", sourceId)),
                new ToolResult(true, "[STALE_OBSERVATION_BODY_OMITTED] old body hidden; read_observation"), null, 3);
    }
    private HistoricalEvidence excerpt(String id, int start, int end) {
        return HistoricalEvidence.excerpt(source(id, "old first\nold second\nold third"), 10, Validity.STALE, start, end);
    }

    @Test void pointsBothOriginalAndRetrievalPlaceholderToTheRetainedExcerpt() {
        var evidence = List.of(excerpt("original", 2, 2));
        for (var exchange : List.of(hidden("original", "read_file", "unused"),
                hidden("retrieval", "read_observation", "original"))) {
            String text = HistoricalEvidenceNotice.content(exchange, evidence);
            assertTrue(text.contains("HISTORICAL_EXCERPT_IN_EVIDENCE_AREA"));
            assertTrue(text.contains("source_call_id=original"));
            assertTrue(text.contains("2..2"));
            assertTrue(text.contains("HISTORICAL_ONLY"));
            assertFalse(text.contains("STALE_OBSERVATION_BODY_OMITTED"));
            assertTrue(exchange.result().content().contains("STALE_OBSERVATION_BODY_OMITTED"));
        }
    }

    @Test void absentOrEvictedSourceKeepsTheOriginalExtractionHint() {
        var exchange = hidden("retrieval", "read_observation", "original");
        assertEquals(exchange.result().content(), HistoricalEvidenceNotice.content(exchange, List.of()));
        assertEquals(exchange.result().content(), HistoricalEvidenceNotice.content(exchange, List.of(excerpt("different", 1, 1))));
    }

    @Test void replacementUsesOnlyTheLatestRetainedRange() {
        var exchange = hidden("retrieval", "read_observation", "original");
        String text = HistoricalEvidenceNotice.content(exchange, List.of(excerpt("original", 3, 3)));
        assertTrue(text.contains("3..3"));
        assertTrue(text.contains("不代表整份原文已取回"));
        assertFalse(text.contains("1..3"));
    }

    @Test void archivedMiddlePointsToEvidenceWhileKeepingItsHeadAndTail() {
        var original = source("original", "head\n" + "middle line\n".repeat(300) + "tail");
        var archived = ArchivedBody.unload(10, original);
        var evidence = HistoricalEvidence.excerpt(original, 10, Validity.CURRENT, 100, 102);
        String text = HistoricalEvidenceNotice.content(archived, List.of(evidence));
        assertTrue(text.startsWith("head\n"));
        assertTrue(text.endsWith("tail"));
        assertTrue(text.contains("100..102"));
        assertFalse(text.contains("[ARCHIVED_BODY"));
        assertTrue(archived.result().content().contains("[ARCHIVED_BODY"));
    }

    @Test void fullVisibleOriginalAndFreshRetrievalRemainUnchanged() {
        var original = source("original", "old first\nold second\nold third");
        assertEquals(original.result().content(), HistoricalEvidenceNotice.content(original, List.of(excerpt("original", 1, 2))));
        var retrieval = new ToolExchange(new ToolCall("retrieve", "read_observation", Map.of("call_id", "original")),
                new ToolResult(true, "[EXPLICIT_HISTORICAL_RETRIEVAL: STALE]\nold first"), null, 3);
        assertEquals(retrieval.result().content(), HistoricalEvidenceNotice.content(retrieval, List.of(excerpt("original", 1, 1))));
    }
}
