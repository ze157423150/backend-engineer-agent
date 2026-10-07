package dev.backendagent.history;

import java.util.*;
import dev.backendagent.model.ToolExchange;

/** At most four most recently retrieved sources, capped at 8000 serialized characters. */
public final class HistoricalEvidenceArea {
    public static final int MAX_ENTRIES = 4, MAX_CHARACTERS = 8000;
    private final LinkedHashMap<String, HistoricalEvidence> entries = new LinkedHashMap<>();
    public void retain(HistoricalEvidence evidence) {
        entries.remove(evidence.getSourceCallId()); entries.put(evidence.getSourceCallId(), evidence);
        while (entries.size() > MAX_ENTRIES || characterCount() > MAX_CHARACTERS) { entries.remove(entries.keySet().iterator().next()); }
    }
    public List<HistoricalEvidence> snapshot() { return List.copyOf(entries.values()); }
    public int characterCount() { return entries.values().stream().mapToInt(HistoricalEvidence::characterCount).sum(); }
    public static HistoricalEvidenceArea replay(List<ToolExchange> originals) {
        var area = new HistoricalEvidenceArea();
        var sources = new HashMap<String, ToolExchange>();
        for (var exchange : originals) {
            var evidence = exchange.result().historicalEvidence();
            if (evidence != null) { evidence.validateAgainst(sources.get(evidence.getSourceCallId()), exchange); area.retain(evidence); }
            sources.put(exchange.call().id(), exchange);
        }
        return area;
    }
}
