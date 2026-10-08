package dev.backendagent.runtime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.backendagent.model.ToolCall;
import java.util.List;
import java.util.HashSet;
import java.util.Map;

/** Durable cursor: completed calls are never replayed; in-flight calls need an explicit retry. */
public final class PendingToolBatch {
    private final int modelCallNumber;
    private final String assistantContent;
    private final List<ToolCall> calls;
    private final int nextCallIndex;
    private final boolean inFlight;
    private final Map<String, String> preExecutionHashes;

    public PendingToolBatch(int modelCallNumber, String assistantContent, List<ToolCall> calls,
            int nextCallIndex, boolean inFlight) {
        this(modelCallNumber, assistantContent, calls, nextCallIndex, inFlight, null);
    }

    @JsonCreator
    public PendingToolBatch(@JsonProperty("modelCallNumber") int modelCallNumber,
            @JsonProperty("assistantContent") String assistantContent,
            @JsonProperty("calls") List<ToolCall> calls,
            @JsonProperty("nextCallIndex") int nextCallIndex,
            @JsonProperty("inFlight") boolean inFlight,
            @JsonProperty("preExecutionHashes") Map<String, String> preExecutionHashes) {
        if (modelCallNumber < 1 || calls == null || calls.isEmpty() || nextCallIndex < 0
                || nextCallIndex > calls.size() || (inFlight && nextCallIndex == calls.size())) {
            throw new IllegalArgumentException("Invalid pending tool batch");
        }
        var ids = new HashSet<String>();
        for (var call : calls) if (!ids.add(call.id())) throw new IllegalArgumentException("Duplicate pending call ID");
        this.modelCallNumber = modelCallNumber;
        this.assistantContent = assistantContent;
        this.calls = List.copyOf(calls);
        this.nextCallIndex = nextCallIndex;
        this.inFlight = inFlight;
        this.preExecutionHashes = preExecutionHashes == null ? null : Map.copyOf(preExecutionHashes);
    }

    public PendingToolBatch starting() { return new PendingToolBatch(modelCallNumber, assistantContent, calls, nextCallIndex, true); }
    public PendingToolBatch completedOne() { return new PendingToolBatch(modelCallNumber, assistantContent, calls, nextCallIndex + 1, false); }
    public PendingToolBatch retrying() { return new PendingToolBatch(modelCallNumber, assistantContent, calls, nextCallIndex, false); }
    public PendingToolBatch withPreExecutionHashes(Map<String, String> hashes) {
        return new PendingToolBatch(modelCallNumber, assistantContent, calls, nextCallIndex, inFlight, hashes);
    }
    public int getModelCallNumber() { return modelCallNumber; }
    public String getAssistantContent() { return assistantContent; }
    public List<ToolCall> getCalls() { return calls; }
    public int getNextCallIndex() { return nextCallIndex; }
    public boolean isInFlight() { return inFlight; }
    public Map<String, String> getPreExecutionHashes() { return preExecutionHashes; }
}
