package dev.backendagent.persistence;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.backendagent.memory.MemoryFact;
import dev.backendagent.model.ToolExchange;
import dev.backendagent.runtime.AgentSession;

/** A complete tool-batch boundary; this first version resumes only budget stops. */
public final class SessionCheckpoint {
    private final int schemaVersion;
    private final UUID sessionId;
    private final String objective;
    private final AgentSession.Status status;
    private final int modelCalls;
    private final long lastEventSequence;
    private final List<ToolExchange> history;
    private final List<MemoryFact> workingMemory;
    private final Set<String> staleEvidenceIds;
    private final String workspacePath;
    private final Map<String, String> workspaceHashes;
    private final Map<String, String> originalContents;
    private final Set<String> createdFiles;

    @JsonCreator
    public SessionCheckpoint(@JsonProperty("schemaVersion") int schemaVersion,
            @JsonProperty("sessionId") UUID sessionId, @JsonProperty("objective") String objective,
            @JsonProperty("status") AgentSession.Status status, @JsonProperty("modelCalls") int modelCalls,
            @JsonProperty("lastEventSequence") long lastEventSequence,
            @JsonProperty("history") List<ToolExchange> history,
            @JsonProperty("workingMemory") List<MemoryFact> workingMemory,
            @JsonProperty("staleEvidenceIds") Set<String> staleEvidenceIds,
            @JsonProperty("workspacePath") String workspacePath,
            @JsonProperty("workspaceHashes") Map<String, String> workspaceHashes,
            @JsonProperty("originalContents") Map<String, String> originalContents,
            @JsonProperty("createdFiles") Set<String> createdFiles) {
        if (schemaVersion != 1 || sessionId == null || objective == null || objective.isBlank()
                || status == null || modelCalls < 0 || lastEventSequence < 0 || workspacePath == null) {
            throw new IllegalArgumentException("Invalid checkpoint metadata");
        }
        this.schemaVersion = schemaVersion;
        this.sessionId = sessionId;
        this.objective = objective;
        this.status = status;
        this.modelCalls = modelCalls;
        this.lastEventSequence = lastEventSequence;
        this.history = List.copyOf(history);
        this.workingMemory = List.copyOf(workingMemory);
        this.staleEvidenceIds = Set.copyOf(staleEvidenceIds);
        this.workspacePath = workspacePath;
        this.workspaceHashes = Map.copyOf(workspaceHashes);
        this.originalContents = Map.copyOf(originalContents);
        this.createdFiles = Set.copyOf(createdFiles);
    }

    public int getSchemaVersion() { return schemaVersion; }
    public UUID getSessionId() { return sessionId; }
    public String getObjective() { return objective; }
    public AgentSession.Status getStatus() { return status; }
    public int getModelCalls() { return modelCalls; }
    public long getLastEventSequence() { return lastEventSequence; }
    public List<ToolExchange> getHistory() { return history; }
    public List<MemoryFact> getWorkingMemory() { return workingMemory; }
    public Set<String> getStaleEvidenceIds() { return staleEvidenceIds; }
    public String getWorkspacePath() { return workspacePath; }
    public Map<String, String> getWorkspaceHashes() { return workspaceHashes; }
    public Map<String, String> getOriginalContents() { return originalContents; }
    public Set<String> getCreatedFiles() { return createdFiles; }
}
