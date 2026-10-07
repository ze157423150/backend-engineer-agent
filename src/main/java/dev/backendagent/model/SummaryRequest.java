package dev.backendagent.model;

import java.util.List;
import dev.backendagent.runtime.ContextSummary;

/** Bounded reference-data input to a separate, tool-free model call.
 * fromIndex/toIndex cover complete original batches; observations omit stale sources.
 */
public final class SummaryRequest {
    private final String objective;
    private final ContextSummary previousSummary;
    private final int fromIndex;
    private final int toIndex;
    private final int maxOutputCharacters;
    private final List<ToolExchange> observations;
    public SummaryRequest(String objective, ContextSummary previousSummary, int fromIndex, int toIndex,
            int maxOutputCharacters, List<ToolExchange> observations) {
        if (objective == null || objective.isBlank() || fromIndex < 0 || toIndex <= fromIndex
                || maxOutputCharacters < 1 || observations == null || observations.isEmpty() || observations.size() > toIndex - fromIndex) {
            throw new IllegalArgumentException("Invalid summary request");
        }
        this.objective = objective;
        this.previousSummary = previousSummary;
        this.fromIndex = fromIndex;
        this.toIndex = toIndex;
        this.maxOutputCharacters = maxOutputCharacters;
        this.observations = List.copyOf(observations);
    }
    public String getObjective() { return objective; }
    public ContextSummary getPreviousSummary() { return previousSummary; }
    public int getFromIndex() { return fromIndex; }
    public int getToIndex() { return toIndex; }
    public int getMaxOutputCharacters() { return maxOutputCharacters; }
    public List<ToolExchange> getObservations() { return observations; }
}
