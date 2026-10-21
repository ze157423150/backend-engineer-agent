package dev.backendagent.selection;

import java.util.List;
import dev.backendagent.tools.ToolDefinition;

/** Scores a capability query, not the whole agent conversation. Does not grant permissions. */
@FunctionalInterface
public interface ToolSearchRanker {
    List<Match> rank(String query, List<ToolDefinition> catalog, boolean includeParameters);
    final class Match {
        private final ToolDefinition tool;
        private final double score;
        public Match(ToolDefinition tool, double score) { this.tool=tool; this.score=score; }
        public ToolDefinition getTool() { return tool; }
        public double getScore() { return score; }
    }
}
