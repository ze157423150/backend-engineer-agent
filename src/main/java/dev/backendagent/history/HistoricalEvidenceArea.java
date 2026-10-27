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
    public static boolean originalVisible(HistoricalEvidence evidence,List<ToolExchange> visible) {
        return visible.stream().anyMatch(e->!e.isProgramChild() && e.call().id().equals(evidence.getSourceCallId())
                && e.result().successful() && e.archivedBody()==null
                && ArchivedBody.hash(e.result().content()).equals(evidence.getSourceBodySha256()));
    }
    public static List<HistoricalEvidence> hiddenOnly(List<HistoricalEvidence> evidence,List<ToolExchange> visible) {
        return evidence.stream().filter(e->!originalVisible(e,visible) && !programDisplays(e,visible)).toList();
    }
    /** Deduplicate only an exact provenance-bearing copy; stale/changed currency must keep its current warning. */
    private static boolean programDisplays(HistoricalEvidence evidence,List<ToolExchange> visible) {
        if(evidence.getCurrentValidity()!=evidence.getValidityAtRetrieval())return false;
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        final com.fasterxml.jackson.databind.JsonNode expected;
        try { expected=json.readTree(json.writeValueAsString(evidence)); }
        catch(java.io.IOException invalid){return false;}
        for(var e:visible) {
            if(e.isProgramChild() || !e.call().name().equals("run_code"))continue;
            try {
                var body=json.readTree(e.result().content());
                if(body.path("outputTruncated").asBoolean())continue;
                if(containsEvidence(body,expected,json,0,new int[]{512}))return true;
            }catch(java.io.IOException invalid){ /* Non-JSON or excerpt cannot prove duplication. */ }
        }
        return false;
    }
    private static boolean containsEvidence(com.fasterxml.jackson.databind.JsonNode node,com.fasterxml.jackson.databind.JsonNode expected,
            com.fasterxml.jackson.databind.ObjectMapper json,int depth,int[] remaining) {
        if(node==null || depth>10 || --remaining[0]<0)return false;
        if(node.equals(expected))return true;
        if(node.isContainerNode())for(var value:node)
            if(containsEvidence(value,expected,json,depth+1,remaining))return true;
        if(node.isTextual()) {
            String text=node.asText().strip();
            if(text.startsWith("{") || text.startsWith("["))try {
                return containsEvidence(json.readTree(text),expected,json,depth+1,remaining);
            }catch(java.io.IOException ignored){ }
        }
        return false;
    }
    public void removeSources(java.util.Set<String> ids){ids.forEach(entries::remove);}
    public void clear(){entries.clear();}
    public List<HistoricalEvidence> snapshot() { return List.copyOf(entries.values()); }
    public int characterCount() { return entries.values().stream().mapToInt(HistoricalEvidence::characterCount).sum(); }
    public static HistoricalEvidenceArea replay(List<ToolExchange> originals) {
        return replay(originals,0);
    }
    public static HistoricalEvidenceArea replay(List<ToolExchange> originals,int retainFromIndex) {
        return replay(originals,retainFromIndex,java.util.Map.of());
    }
    public static HistoricalEvidenceArea replay(List<ToolExchange> originals,int retainFromIndex,
            java.util.Map<Integer,java.util.Set<String>> removals) {
        if(retainFromIndex<0 || retainFromIndex>originals.size())throw new IllegalArgumentException("Invalid evidence clear boundary");
        var area = new HistoricalEvidenceArea();
        int index=0;
        var sources = new HashMap<String, ToolExchange>();
        for (var exchange : originals) {
            area.removeSources(removals.getOrDefault(index,java.util.Set.of()));
            var evidence = exchange.result().historicalEvidence();
            if (evidence != null) { evidence.validateAgainst(sources.get(evidence.getSourceCallId()), exchange); if(index>=retainFromIndex)area.retain(evidence); }
            sources.put(exchange.call().id(), exchange);index++;
        }
        area.removeSources(removals.getOrDefault(index,java.util.Set.of()));
        return area;
    }
}
