package dev.backendagent.runtime;

import dev.backendagent.model.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SummaryDeduplicatorTest {
    private final SummaryDeduplicator dedup = new SummaryDeduplicator();
    private SummaryNote note(String id, String statement, String quote) {
        return new SummaryNote(SummaryNote.Kind.PROGRESS, statement, id, quote);
    }
    private ToolExchange read(String id, String hash, String content, Map<String, String> args) {
        return new ToolExchange(new ToolCall(id, "read_file", args),
                new ToolResult(true, content, hash == null ? null : new FileFingerprint("rate.txt", hash.repeat(64))));
    }
    private SummaryDeduplicator.Result analyze(List<SummaryNote> notes, ToolExchange... observations) {
        return dedup.analyze(notes, id -> List.of(observations).stream().filter(x -> x.call().id().equals(id)).findFirst().orElseThrow());
    }
    @Test void removesExactRepetitionKeepingOriginalEvidenceAndArchives() {
        var a = note("a", "RATE=225", "RATE=225");
        var raw = List.of(read("a", "a", "RATE=225", Map.of("path", "rate.txt")));
        var result = dedup.analyze(List.of(a, note("a", " RATE=225 ", "RATE=225")), id -> raw.getFirst());
        assertEquals(List.of(a), result.getNotes());
        assertEquals(1, result.getRemovedCount());
        assertEquals("RATE=225", raw.getFirst().result().content());
    }
    @Test void mergesAcrossCallsOnlyWithSameHashArgumentsAndFullBody() {
        var args = Map.of("path", "rate.txt");
        var result = analyze(List.of(note("a", "RATE=225", "RATE=225"), note("b", "RATE=225", "RATE=225")),
                read("a", "a", "RATE=225", args), read("b", "a", "RATE=225", args));
        assertEquals(1, result.getRemovedCount());
        assertEquals("a", result.getNotes().getFirst().getSourceCallId());
    }
    @Test void keepsUnknownOrDifferentVersionsAndDifferentReadRanges() {
        var args = Map.of("path", "rate.txt");
        for (String hash : new String[] {null, "b"}) {
            assertEquals(2, analyze(List.of(note("a", "RATE=225", "RATE=225"), note("b", "RATE=225", "RATE=225")),
                    read("a", "a", "RATE=225", args), read("b", hash, "RATE=225", args)).getNotes().size());
        }
        assertEquals(2, analyze(List.of(note("a", "RATE=225", "RATE=225"), note("b", "RATE=225", "RATE=225")),
                read("a", "a", "RATE=225", args), read("b", "a", "RATE=225", Map.of("path", "rate.txt", "startLine", "2"))).getNotes().size());
        assertEquals(2, analyze(List.of(note("a", "RATE=225", "RATE=225"), note("b", "RATE=225", "RATE=225")),
                read("a", "a", "RATE=225\nLIMIT=1000", args), read("b", "a", "RATE=225", args)).getNotes().size());
    }
    @Test void keepsIndependentFactsSharingOneQuoteAndFlagsForReview() {
        var quote = "RATE=225; LIMIT=1000";
        var result = analyze(List.of(note("a", "RATE=225", quote), note("a", "LIMIT=1000", quote)),
                read("a", "a", quote, Map.of("path", "rate.txt")));
        assertEquals(2, result.getNotes().size());
        assertEquals(0, result.getRemovedCount());
        assertEquals(List.of("0:1"), result.getSuspectedPairs());
    }
    @Test void keepsNegationPendingItemsAndBeforeAfterValues() {
        var quote = "未通过；已通过；修改前958；修改后225；TODO零金额";
        var notes = List.of(note("a", "未通过", quote), note("a", "已通过", quote),
                note("a", "修改前958", quote), note("a", "修改后225", quote),
                new SummaryNote(SummaryNote.Kind.OPEN_ISSUE, "TODO零金额", "a", quote));
        assertEquals(notes, analyze(notes, read("a", "a", quote, Map.of("path", "rate.txt"))).getNotes());
    }
    @Test void flagsParaphrasesWithoutDeletingOrChangingLiterals() {
        var result = analyze(List.of(note("a", "RATE=225", "RATE=225"), note("a", "重复读取仍为225", "RATE=225")),
                read("a", "a", "RATE=225", Map.of("path", "rate.txt")));
        assertEquals(2, result.getNotes().size());
        assertEquals(1, result.getSuspectedPairs().size());
        assertEquals(2, analyze(List.of(note("a", "值为A B", "RATE=225"), note("a", "值为A  B", "RATE=225")),
                read("a", "a", "RATE=225", Map.of("path", "rate.txt"))).getNotes().size());
    }
}
