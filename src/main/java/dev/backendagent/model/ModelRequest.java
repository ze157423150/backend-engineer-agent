package dev.backendagent.model;

import java.util.List;

import dev.backendagent.tools.ToolDefinition;
import dev.backendagent.memory.MemoryFact;

public record ModelRequest(String objective, List<ToolExchange> history,
                           List<ToolDefinition> availableTools, int remainingModelCalls,
                           int omittedExchanges, long historyCharacters, List<MemoryFact> workingMemory) {
    public ModelRequest(String objective, List<ToolExchange> history,
                        List<ToolDefinition> availableTools, int remainingModelCalls) {
        this(objective, history, availableTools, remainingModelCalls, 0, 0);
    }

    public ModelRequest(String objective, List<ToolExchange> history,
                        List<ToolDefinition> availableTools, int remainingModelCalls,
                        int omittedExchanges, long historyCharacters) {
        this(objective, history, availableTools, remainingModelCalls, omittedExchanges, historyCharacters, List.of());
    }

    public ModelRequest {
        history = List.copyOf(history);
        availableTools = List.copyOf(availableTools);
        workingMemory = List.copyOf(workingMemory);
    }
}
