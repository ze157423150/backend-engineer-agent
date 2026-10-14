package dev.backendagent.model;

import dev.backendagent.runtime.ContextSummary;
import dev.backendagent.runtime.SummaryNote;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SummaryEvidenceCatalogTest {
    private ToolExchange observation(String id, String text) {
        return new ToolExchange(new ToolCall(id, "read_file", Map.of("path", "Main.java")), new ToolResult(true, text));
    }

    @Test void excludesCompressionMarkersAndNeverJoinsAcrossMissingOriginalText() {
        var original = observation("call", "head\n" + "omitted\n".repeat(100) + "tail\n");
        var visible = observation("call", "head\n[summary omission]\ntail\n");
        var request = new SummaryRequest("inspect", null, 0, 1, 4000, List.of(visible), id -> original);
        assertEquals(List.of("head\n", "tail\n"), request.getEvidence().stream()
                .map(SummaryEvidence::getEvidenceQuote).toList());
        for (var evidence : request.getEvidence()) {
            assertTrue(original.result().content().contains(evidence.getEvidenceQuote()));
            assertTrue(visible.result().content().contains(evidence.getEvidenceQuote()));
        }
    }

    @Test void carriesPriorExactQuoteAndGivesDistinctSourcesTheirOwnIds() {
        var previous = new ContextSummary(1, 1, List.of(new SummaryNote(SummaryNote.Kind.PROGRESS,
                "old fact", "old", "original quote")));
        var current = observation("new", "original quote");
        var request = new SummaryRequest("inspect", previous, 1, 2, 4000, List.of(current));
        assertEquals(2, request.getEvidence().size());
        assertEquals("old", request.getEvidence().getFirst().getSourceCallId());
        assertEquals("new", request.getEvidence().getLast().getSourceCallId());
        assertNotEquals(request.getEvidence().getFirst().getEvidenceId(), request.getEvidence().getLast().getEvidenceId());
    }

    @Test void boundsQuotesWithoutBreakingUnicodeSurrogatePairs() {
        String text = "x".repeat(319) + "😀" + "y".repeat(800);
        var request = new SummaryRequest("inspect", null, 0, 1, 4000, List.of(observation("call", text)));
        assertEquals(text, request.getEvidence().stream().map(SummaryEvidence::getEvidenceQuote)
                .collect(java.util.stream.Collectors.joining()));
        assertTrue(request.getEvidence().stream().allMatch(e -> e.getEvidenceQuote().length() <= 400));
        for (var evidence : request.getEvidence()) {
            String quote = evidence.getEvidenceQuote();
            assertFalse(Character.isHighSurrogate(quote.charAt(quote.length() - 1)));
            assertFalse(Character.isLowSurrogate(quote.charAt(0)));
        }
    }
}
