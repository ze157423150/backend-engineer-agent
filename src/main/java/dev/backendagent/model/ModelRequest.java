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
                           List<dev.backendagent.history.HistoricalEvidence> historicalEvidence) {
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
        history = List.copyOf(history);
        availableTools = List.copyOf(availableTools);
        workingMemory = List.copyOf(workingMemory);
        historicalEvidence = List.copyOf(historicalEvidence);
        staleSummarySourceIds = Set.copyOf(staleSummarySourceIds);
        java.util.Objects.requireNonNull(workspaceState);
    }
}
