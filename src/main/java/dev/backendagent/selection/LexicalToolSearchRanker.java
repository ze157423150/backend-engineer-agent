package dev.backendagent.selection;

import java.util.*;
import dev.backendagent.tools.ToolDefinition;

/** Existing set-overlap baseline, retained as the default. */
public final class LexicalToolSearchRanker implements ToolSearchRanker {
    public List<Match> rank(String query,List<ToolDefinition> catalog,boolean includeParameters) {
        var terms=new HashSet<>(ToolSearchText.terms(query));
        return catalog.stream().map(tool->{
            var words=new HashSet<>(ToolSearchText.terms(ToolSearchText.text(tool,includeParameters)));
            double score=ToolSearchText.exactName(query,tool)?10000:terms.stream().filter(words::contains).count();
            return new Match(tool,score);
        }).filter(m->m.getScore()>0).sorted(Comparator.comparingDouble(Match::getScore).reversed()
                .thenComparing(m->m.getTool().getName())).toList();
    }
}
