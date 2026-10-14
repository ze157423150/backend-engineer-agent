package dev.backendagent.model;

import dev.backendagent.runtime.ContextSummary;
import dev.backendagent.runtime.SummaryNote;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Includes only visible text that is also continuous original text. Omission markers are excluded. */
final class SummaryEvidenceCatalog {
    private SummaryEvidenceCatalog() { }

    static List<SummaryEvidence> build(List<ToolExchange> observations, ContextSummary previous,
                                      Function<String, ToolExchange> original) {
        var entries = new ArrayList<SummaryEvidence>();
        if (previous != null) {
            for (var note : previous.getNotes()) {
                if (note.getKind() != SummaryNote.Kind.NEXT_STEP) {
                    add(entries, note.getSourceCallId(), note.getEvidenceQuote());
                }
            }
        }
        for (var exchange : observations) {
            String source = original.apply(exchange.call().id()).result().content();
            String pending = "";
            // Retain line endings so adjacent lines can still be quoted verbatim.
            for (String line : exchange.result().content().split("(?<=\\n)", -1)) {
                for (int start = 0; start < line.length();) {
                    int end = Math.min(start + 320, line.length());
                    if (end < line.length() && Character.isHighSurrogate(line.charAt(end - 1))) end--;
                    String piece = line.substring(start, end);
                    start = end;
                    if (!source.contains(piece)) {
                        add(entries, exchange.call().id(), pending);
                        pending = "";
                        continue;
                    }
                    String joined = pending + piece;
                    if (joined.length() > 320 || !source.contains(joined)) {
                        add(entries, exchange.call().id(), pending);
                        pending = piece;
                    } else {
                        pending = joined;
                    }
                }
            }
            add(entries, exchange.call().id(), pending);
        }
        return List.copyOf(entries);
    }

    private static void add(List<SummaryEvidence> entries, String source, String quote) {
        if (quote.isBlank() || entries.stream().anyMatch(e -> e.getSourceCallId().equals(source)
                && e.getEvidenceQuote().equals(quote))) return;
        entries.add(new SummaryEvidence("E" + (entries.size() + 1), source, quote));
    }
}
