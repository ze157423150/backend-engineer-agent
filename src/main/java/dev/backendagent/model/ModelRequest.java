package dev.backendagent.model;

import java.util.List;
import java.util.Set;
import dev.backendagent.runtime.ContextProjection;
import dev.backendagent.runtime.ContextSummary;
import dev.backendagent.runtime.WorkspaceState;

import dev.backendagent.tools.ToolDefinition;
import dev.backendagent.memory.MemoryFact;

public record ModelRequest(String objective, List<ToolExchange> history,
                           List<ToolDefinition> availableTools, int remainingModelCalls,
                           int omittedExchanges, long historyCharacters, List<MemoryFact> workingMemory,
                           ContextProjection contextProjection, ContextSummary contextSummary,
                           Set<String> staleSummarySourceIds, WorkspaceState workspaceState,
                           List<dev.backendagent.history.HistoricalEvidence> historicalEvidence,
                           List<dev.backendagent.runtime.ConversationTurn> turns,
                           dev.backendagent.runtime.ConversationSummary conversationSummary, int latestTurnId) {
    public ModelRequest(String objective,List<ToolExchange> history,List<ToolDefinition> tools,int remaining,
            int omitted,long characters,List<MemoryFact> memory,ContextProjection projection,ContextSummary summary,
            Set<String> stale,WorkspaceState state,List<dev.backendagent.history.HistoricalEvidence> evidence,
            List<dev.backendagent.runtime.ConversationTurn> turns) {
        this(objective,history,tools,remaining,omitted,characters,memory,projection,summary,stale,state,evidence,turns,null,
                turns.isEmpty()?0:turns.getLast().getTurnId());
    }
    public ModelRequest(String objective, List<ToolExchange> history, List<ToolDefinition> tools,
            int remainingCalls, int omitted, long characters, List<MemoryFact> memory, ContextProjection projection,
            ContextSummary summary, Set<String> stale, WorkspaceState state,
            List<dev.backendagent.history.HistoricalEvidence> evidence) {
        this(objective, history, tools, remainingCalls, omitted, characters, memory, projection, summary, stale, state, evidence, List.of());
    }
    public ModelRequest(String objective, List<ToolExchange> history, List<ToolDefinition> availableTools,
            int remainingModelCalls, int omittedExchanges, long historyCharacters, List<MemoryFact> workingMemory,
            ContextProjection contextProjection, ContextSummary contextSummary, Set<String> staleSummarySourceIds, WorkspaceState workspaceState) {
        this(objective, history, availableTools, remainingModelCalls, omittedExchanges, historyCharacters, workingMemory,
                contextProjection, contextSummary, staleSummarySourceIds, workspaceState, List.of());
    }
    public ModelRequest(String objective, List<ToolExchange> history,
                        List<ToolDefinition> availableTools, int remainingModelCalls,
                        int omittedExchanges, long historyCharacters, List<MemoryFact> workingMemory,
                        ContextProjection contextProjection, ContextSummary contextSummary,
                        Set<String> staleSummarySourceIds) {
        this(objective, history, availableTools, remainingModelCalls, omittedExchanges, historyCharacters,
                workingMemory, contextProjection, contextSummary, staleSummarySourceIds,
                WorkspaceState.fromHistory(history));
    }
    public ModelRequest(String objective, List<ToolExchange> history,
                        List<ToolDefinition> availableTools, int remainingModelCalls) {
        this(objective, history, availableTools, remainingModelCalls, 0, 0);
    }

    public ModelRequest(String objective, List<ToolExchange> history,
                        List<ToolDefinition> availableTools, int remainingModelCalls,
                        int omittedExchanges, long historyCharacters) {
        this(objective, history, availableTools, remainingModelCalls, omittedExchanges, historyCharacters, List.of());
    }

    public ModelRequest(String objective, List<ToolExchange> history,
                        List<ToolDefinition> availableTools, int remainingModelCalls,
                        int omittedExchanges, long historyCharacters, List<MemoryFact> workingMemory) {
        this(objective, history, availableTools, remainingModelCalls, omittedExchanges,
                historyCharacters, workingMemory, null);
    }

    public ModelRequest(String objective, List<ToolExchange> history,
                        List<ToolDefinition> availableTools, int remainingModelCalls,
                        int omittedExchanges, long historyCharacters, List<MemoryFact> workingMemory,
                        ContextProjection contextProjection) {
        this(objective, history, availableTools, remainingModelCalls, omittedExchanges, historyCharacters,
                workingMemory, contextProjection, null, Set.of());
    }

    public ModelRequest {
        turns = List.copyOf(turns);
        history = List.copyOf(history);
        availableTools = List.copyOf(availableTools);
        workingMemory = List.copyOf(workingMemory);
        historicalEvidence = List.copyOf(historicalEvidence);
        staleSummarySourceIds = Set.copyOf(staleSummarySourceIds);
        java.util.Objects.requireNonNull(workspaceState);
    }
}
