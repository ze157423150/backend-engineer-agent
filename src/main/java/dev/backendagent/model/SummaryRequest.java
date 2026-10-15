package dev.backendagent.model;

import java.util.List;
import dev.backendagent.runtime.ContextSummary;

/** Bounded reference-data input to a separate, tool-free model call.
 * fromIndex/toIndex cover complete original batches; observations omit stale sources.
 */
public final class SummaryRequest {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private final String objective;
    private final ContextSummary previousSummary;
    private final int fromIndex;
    private final int toIndex;
    private final int maxOutputCharacters;
    private final List<ToolExchange> observations;
    private final List<SummaryEvidence> evidence;
    public SummaryRequest(String objective, ContextSummary previousSummary, int fromIndex, int toIndex,
            int maxOutputCharacters, List<ToolExchange> observations) {
        this(objective, previousSummary, fromIndex, toIndex, maxOutputCharacters, observations,
                id -> observations.stream().filter(x -> x.call().id().equals(id)).findFirst().orElseThrow());
    }
    public SummaryRequest(String objective, ContextSummary previousSummary, int fromIndex, int toIndex,
            int maxOutputCharacters, List<ToolExchange> observations,
            java.util.function.Function<String, ToolExchange> original) {
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
        this.evidence = SummaryEvidenceCatalog.build(this.observations, previousSummary, original);
    }
    public String getObjective() { return objective; }
    public ContextSummary getPreviousSummary() { return previousSummary; }
    public int getFromIndex() { return fromIndex; }
    public int getToIndex() { return toIndex; }
    public int getMaxOutputCharacters() { return maxOutputCharacters; }
    public List<ToolExchange> getObservations() { return observations; }
    public List<SummaryEvidence> getEvidence() { return evidence; }
    /** The same reference-data encoding is used by planning and transport; no duplicate raw bodies. */
    public com.fasterxml.jackson.databind.node.ObjectNode referenceData() {
        var reference = JSON.createObjectNode();
        reference.put("objective", getObjective()).put("maxOutputCharacters", getMaxOutputCharacters())
                .put("recommendedMaximumItems", Math.max(1, Math.min(32, getMaxOutputCharacters() / 240)));
        reference.put("budgetNotice", "推荐条数仅为预算引导，不是填满要求；总长度包含每条statement、真实sourceCallId和JSON结构，不含内部证据引句。");
        reference.set("evidence", JSON.valueToTree(getEvidence()));
        var observations = reference.putArray("observations");
        for (var exchange : getObservations()) {
            var observation = observations.addObject();
            observation.set("call", JSON.valueToTree(exchange.call()));
            observation.put("successful", exchange.result().successful());
            if (exchange.evidence() != null) observation.set("evidence", JSON.valueToTree(exchange.evidence()));
        }
        var previous = reference.putArray("previousSummary");
        if (getPreviousSummary() != null) {
            for (var note : getPreviousSummary().getNotes()) {
                getEvidence().stream().filter(e -> e.getSourceCallId().equals(note.getSourceCallId())
                        && e.getEvidenceQuote().equals(note.getEvidenceQuote())).findFirst().ifPresent(e ->
                        previous.addObject().put("kind", note.getKind().name()).put("statement", note.getStatement())
                                .put("section", note.getSection() == null ? "FINDINGS" : note.getSection().name())
                                .put("evidenceId", e.getEvidenceId()));
            }
        }
        return reference;
    }
}
