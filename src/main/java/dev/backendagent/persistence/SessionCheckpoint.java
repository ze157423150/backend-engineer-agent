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
import dev.backendagent.runtime.ContextProjection;
import dev.backendagent.runtime.ContextSummary;
import dev.backendagent.runtime.WorkspaceState;

/** Complete execution boundary: budget stops resume; finished turns accept a new user message. */
public final class SessionCheckpoint {
    private final dev.backendagent.runtime.SessionFailure failure;
    private final dev.backendagent.runtime.PendingToolBatch pendingToolBatch;
    private final List<dev.backendagent.runtime.ConversationTurn> turns;
    private final dev.backendagent.runtime.ConversationSummary conversationSummary;
    private final ContextProjection contextProjection;
    private final ContextSummary contextSummary;
    private final WorkspaceState workspaceState;
    private final int schemaVersion;
    private final UUID sessionId;
    private final String objective;
    private final AgentSession.Status status;
    private final int modelCalls;
    private final long lastEventSequence;
    private final List<ToolExchange> history;
    private final List<MemoryFact> workingMemory;
    private final List<dev.backendagent.history.HistoricalEvidence> historicalEvidence;
    private final Set<String> staleEvidenceIds;
    private final String workspacePath;
    private final Map<String, String> workspaceHashes;
    private final Map<String, String> originalContents;
    private final Set<String> createdFiles;

    public SessionCheckpoint(int schemaVersion, UUID sessionId, String objective,
            AgentSession.Status status, int modelCalls, long lastEventSequence,
            List<ToolExchange> history, List<MemoryFact> workingMemory, Set<String> staleEvidenceIds,
            String workspacePath, Map<String, String> workspaceHashes, Map<String, String> originalContents,
            Set<String> createdFiles, ContextProjection contextProjection, ContextSummary contextSummary) {
        this(schemaVersion, sessionId, objective, status, modelCalls, lastEventSequence, history, workingMemory,
                staleEvidenceIds, workspacePath, workspaceHashes, originalContents, createdFiles,
                contextProjection, contextSummary, WorkspaceState.fromHistory(history));
    }

    public SessionCheckpoint(int schemaVersion, UUID sessionId, String objective, AgentSession.Status status,
            int modelCalls, long lastEventSequence, List<ToolExchange> history, List<MemoryFact> workingMemory,
            Set<String> staleEvidenceIds, String workspacePath, Map<String,String> workspaceHashes,
            Map<String,String> originalContents, Set<String> createdFiles, ContextProjection contextProjection,
            ContextSummary contextSummary, WorkspaceState workspaceState) {
        this(schemaVersion, sessionId, objective, status, modelCalls, lastEventSequence, history, workingMemory,
                staleEvidenceIds, workspacePath, workspaceHashes, originalContents, createdFiles,
                contextProjection, contextSummary, workspaceState, List.of());
    }

    public SessionCheckpoint(int schemaVersion, UUID sessionId, String objective, AgentSession.Status status,
            int modelCalls, long lastEventSequence, List<ToolExchange> history, List<MemoryFact> workingMemory,
            Set<String> staleEvidenceIds, String workspacePath, Map<String,String> workspaceHashes,
            Map<String,String> originalContents, Set<String> createdFiles, ContextProjection contextProjection,
            ContextSummary contextSummary, WorkspaceState workspaceState,
            List<dev.backendagent.history.HistoricalEvidence> historicalEvidence) {
        this(schemaVersion, sessionId, objective, status, modelCalls, lastEventSequence, history, workingMemory,
                staleEvidenceIds, workspacePath, workspaceHashes, originalContents, createdFiles, contextProjection,
                contextSummary, workspaceState, historicalEvidence, List.of());
    }

    public SessionCheckpoint(int schemaVersion, UUID sessionId, String objective, AgentSession.Status status,
            int modelCalls, long lastEventSequence, List<ToolExchange> history, List<MemoryFact> workingMemory,
            Set<String> staleEvidenceIds, String workspacePath, Map<String,String> workspaceHashes,
            Map<String,String> originalContents, Set<String> createdFiles, ContextProjection contextProjection,
            ContextSummary contextSummary, WorkspaceState workspaceState,
            List<dev.backendagent.history.HistoricalEvidence> historicalEvidence,
            List<dev.backendagent.runtime.ConversationTurn> turns) {
        this(schemaVersion,sessionId,objective,status,modelCalls,lastEventSequence,history,workingMemory,staleEvidenceIds,
                workspacePath,workspaceHashes,originalContents,createdFiles,contextProjection,contextSummary,workspaceState,
                historicalEvidence,turns,null);
    }

    public SessionCheckpoint(int schemaVersion, UUID sessionId, String objective, AgentSession.Status status,
            int modelCalls, long lastEventSequence, List<ToolExchange> history, List<MemoryFact> workingMemory,
            Set<String> staleEvidenceIds, String workspacePath, Map<String,String> workspaceHashes,
            Map<String,String> originalContents, Set<String> createdFiles, ContextProjection contextProjection,
            ContextSummary contextSummary, WorkspaceState workspaceState,
            List<dev.backendagent.history.HistoricalEvidence> historicalEvidence,
            List<dev.backendagent.runtime.ConversationTurn> turns,
            dev.backendagent.runtime.ConversationSummary conversationSummary) {
        this(schemaVersion,sessionId,objective,status,modelCalls,lastEventSequence,history,workingMemory,
                staleEvidenceIds,workspacePath,workspaceHashes,originalContents,createdFiles,contextProjection,
                contextSummary,workspaceState,historicalEvidence,turns,conversationSummary,null);
    }

    public SessionCheckpoint(int schemaVersion, UUID sessionId, String objective, AgentSession.Status status,
            int modelCalls, long lastEventSequence, List<ToolExchange> history, List<MemoryFact> workingMemory,
            Set<String> staleEvidenceIds, String workspacePath, Map<String,String> workspaceHashes,
            Map<String,String> originalContents, Set<String> createdFiles, ContextProjection contextProjection,
            ContextSummary contextSummary, WorkspaceState workspaceState,
            List<dev.backendagent.history.HistoricalEvidence> historicalEvidence,
            List<dev.backendagent.runtime.ConversationTurn> turns,
            dev.backendagent.runtime.ConversationSummary conversationSummary,
            dev.backendagent.runtime.SessionFailure failure) {
        this(schemaVersion,sessionId,objective,status,modelCalls,lastEventSequence,history,workingMemory,
                staleEvidenceIds,workspacePath,workspaceHashes,originalContents,createdFiles,contextProjection,
                contextSummary,workspaceState,historicalEvidence,turns,conversationSummary,failure,null);
    }

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
            @JsonProperty("createdFiles") Set<String> createdFiles,
            @JsonProperty("contextProjection") ContextProjection contextProjection,
            @JsonProperty("contextSummary") ContextSummary contextSummary,
            @JsonProperty("workspaceState") WorkspaceState workspaceState,
            @JsonProperty("historicalEvidence") List<dev.backendagent.history.HistoricalEvidence> historicalEvidence,
            @JsonProperty("turns") List<dev.backendagent.runtime.ConversationTurn> turns,
            @JsonProperty("conversationSummary") dev.backendagent.runtime.ConversationSummary conversationSummary,
            @JsonProperty("failure") dev.backendagent.runtime.SessionFailure failure,
            @JsonProperty("pendingToolBatch") dev.backendagent.runtime.PendingToolBatch pendingToolBatch) {
        if ((schemaVersion != 1 && schemaVersion != 2 && schemaVersion != 3) || sessionId == null || objective == null || objective.isBlank()
                || status == null || modelCalls < 0 || lastEventSequence < 0 || workspacePath == null) {
            throw new IllegalArgumentException("Invalid checkpoint metadata");
        }
        this.conversationSummary=conversationSummary;
        this.failure=failure;
        this.pendingToolBatch=pendingToolBatch;
        this.turns = turns == null ? List.of() : List.copyOf(turns);
        this.contextProjection = contextProjection;
        this.contextSummary = contextSummary;
        this.workspaceState = workspaceState;
        this.schemaVersion = schemaVersion;
        this.sessionId = sessionId;
        this.objective = objective;
        this.status = status;
        this.modelCalls = modelCalls;
        this.lastEventSequence = lastEventSequence;
        this.history = List.copyOf(history);
        this.workingMemory = List.copyOf(workingMemory);
        this.historicalEvidence = historicalEvidence == null ? List.of() : List.copyOf(historicalEvidence);
        this.staleEvidenceIds = Set.copyOf(staleEvidenceIds);
        this.workspacePath = workspacePath;
        this.workspaceHashes = Map.copyOf(workspaceHashes);
        this.originalContents = Map.copyOf(originalContents);
        this.createdFiles = Set.copyOf(createdFiles);
    }

    public dev.backendagent.runtime.SessionFailure getFailure() { return failure; }
    public dev.backendagent.runtime.PendingToolBatch getPendingToolBatch() { return pendingToolBatch; }
    public dev.backendagent.runtime.ConversationSummary getConversationSummary() { return conversationSummary; }
    public List<dev.backendagent.runtime.ConversationTurn> getTurns() { return turns; }
    public List<dev.backendagent.history.HistoricalEvidence> getHistoricalEvidence() { return historicalEvidence; }
    public ContextSummary getContextSummary() { return contextSummary; }
    public WorkspaceState getWorkspaceState() { return workspaceState; }
    public ContextProjection getContextProjection() { return contextProjection; }
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
