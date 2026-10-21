package dev.backendagent.runtime;

import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import dev.backendagent.model.*;
import dev.backendagent.selection.*;
import dev.backendagent.tools.*;

/** Explicit offline query evaluation; no model/API calls. */
public final class ToolSearchQueryEvaluation {
    public static List<ToolDefinition> catalog(Path file) throws Exception {
        var definitions=new ArrayList<ToolDefinition>();
        for(var item:new ObjectMapper().readTree(file.toFile())) {
            var f=item.path("function");var params=new LinkedHashMap<String,String>();
            f.path("parameters").path("properties").fields().forEachRemaining(e->params.put(e.getKey(),e.getValue().path("description").asText()));
            var required=new HashSet<String>();f.path("parameters").path("required").forEach(n->required.add(n.asText()));
            definitions.add(new ToolDefinition(f.path("name").asText(),f.path("description").asText(),params,required));
        }
        return List.copyOf(definitions);
    }
    public static void main(String[] args) throws Exception {
        var json=new ObjectMapper();Path input=Path.of(args[0]);var definitions=catalog(input.resolve("catalog.json"));
        var report=json.createObjectNode().put("scope","Explicit query retrieval, all business tools initially hidden; no API calls");
        var cases=report.putArray("cases");var summaries=report.putArray("summaries");
        var rankers=Map.<String,ToolSearchRanker>of("lexical",new LexicalToolSearchRanker(),"bm25",new Bm25ToolSearchRanker());
        for(String name:List.of("lexical","bm25"))for(String group:List.of("historical","curated")) {
            int positives=0,negatives=0,top1=0,top3=0,policyHits=0,rejected=0,added=0,extras=0;double reciprocal=0;
            for(var sample:json.readTree(input.resolve("queries.json").toFile())) {
                if(!sample.path("group").asText().equals(group))continue;
                String query=sample.path("query").asText();var expected=new HashSet<String>();sample.path("expectedAny").forEach(n->expected.add(n.asText()));
                var ranker=rankers.get(name);var ranked=ranker.rank(query,definitions,false);
                var session=new AgentSession("查询工具");var discovery=new ToolDiscovery(definitions,(t,h,c,k)->List.of(),0,ranker);
                var output=discovery.search(session,Map.of("query",query));var result=json.readTree(output.content());
                var found=new ArrayList<String>();result.path("tools").forEach(t->found.add(t.path("name").asText()));
                var row=cases.addObject().put("strategy",name).put("id",sample.path("id").asText()).put("group",group).put("query",query);
                row.set("expectedAny",sample.path("expectedAny"));row.putPOJO("ranked",ranked.stream().map(m->Map.of("name",m.getTool().getName(),"score",m.getScore())).toList());row.set("searchResult",result);
                if(expected.isEmpty()) { negatives++;if(found.isEmpty())rejected++; }
                else {
                    positives++;int position=0;
                    for(int i=0;i<ranked.size();i++)if(expected.contains(ranked.get(i).getTool().getName())) {position=i+1;break;}
                    if(position==1)top1++;if(position>0&&position<=3)top3++;if(position>0)reciprocal+=1.0/position;
                    if(found.stream().anyMatch(expected::contains))policyHits++;
                    added+=found.size();extras+=found.stream().filter(n->!expected.contains(n)).count();
                }
            }
            summaries.addObject().put("strategy",name).put("group",group).put("positiveQueries",positives).put("negativeQueries",negatives)
                    .put("top1",top1).put("top3",top3).put("policyHits",policyHits).put("correctNoMatch",rejected)
                    .put("meanReciprocalRank",positives==0?0:reciprocal/positives).put("returnedToolsOnPositiveQueries",added).put("extraToolsOnPositiveQueries",extras);
        }
        Files.writeString(input.resolve("report.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(summaries));
    }
}
