package dev.backendagent.selection;

import java.util.*;
import dev.backendagent.tools.ToolDefinition;

/** BM25 (k1=1.2, b=0.75), no training or model calls. Exact registered names retain priority. */
public final class Bm25ToolSearchRanker implements ToolSearchRanker {
    private static final double K1=1.2, B=0.75;
    public List<Match> rank(String query,List<ToolDefinition> catalog,boolean includeParameters) {
        if(catalog.isEmpty())return List.of();
        var documents=new ArrayList<Map<String,Integer>>();var df=new HashMap<String,Integer>();
        int totalLength=0;
        for(var tool:catalog) {
            var counts=counts(ToolSearchText.terms(ToolSearchText.text(tool,includeParameters)));
            documents.add(counts);totalLength+=counts.values().stream().mapToInt(Integer::intValue).sum();
            counts.keySet().forEach(term->df.merge(term,1,Integer::sum));
        }
        double average=Math.max(1.0,(double)totalLength/catalog.size());
        var queryCounts=counts(ToolSearchText.terms(query));var result=new ArrayList<Match>();
        for(int i=0;i<catalog.size();i++) {
            var tool=catalog.get(i);var doc=documents.get(i);int length=doc.values().stream().mapToInt(Integer::intValue).sum();
            double score=0;
            for(var term:queryCounts.entrySet()) {
                int tf=doc.getOrDefault(term.getKey(),0);if(tf==0)continue;
                int frequency=df.get(term.getKey());
                double idf=Math.log(1+(catalog.size()-frequency+0.5)/(frequency+0.5));
                score+=Math.min(term.getValue(),3)*idf*tf*(K1+1)/(tf+K1*(1-B+B*length/average));
            }
            if(ToolSearchText.exactName(query,tool))score+=10000;
            if(score>0)result.add(new Match(tool,score));
        }
        return result.stream().sorted(Comparator.comparingDouble(Match::getScore).reversed()
                .thenComparing(m->m.getTool().getName())).toList();
    }
    private static Map<String,Integer> counts(List<String> words) {
        var result=new HashMap<String,Integer>();words.forEach(w->result.merge(w,1,Integer::sum));return result;
    }
}
