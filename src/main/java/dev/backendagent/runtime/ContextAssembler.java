package dev.backendagent.runtime;

import java.util.List;

import dev.backendagent.model.ModelRequest;
import dev.backendagent.tools.ToolDefinition;

/** Selects a suffix of complete model turns without changing the session's full history. */
public final class ContextAssembler {
    private final ContextBudget budget;

    public ContextAssembler() {
        this(new ContextBudget(ContextBudget.DEFAULT_MAX_HISTORY_CHARACTERS));
    }

    public ContextAssembler(ContextBudget budget) {
        this.budget = java.util.Objects.requireNonNull(budget);
    }

    public ModelRequest assemble(AgentSession session, List<ToolDefinition> tools, int remainingCalls) {
        var history = session.history();
        int selectedStart = history.size();
        long selectedCharacters = 0;
        while (selectedStart > 0) {
            int batchEnd = selectedStart;
            int batchStart = batchEnd - 1;
            int turn = history.get(batchStart).modelCallNumber();
            while (turn > 0 && batchStart > 0 && history.get(batchStart - 1).modelCallNumber() == turn) {
                batchStart--;
            }
            long batchCharacters = 0;
            for (int index = batchStart; index < batchEnd; index++) {
                batchCharacters += budget.measure(history.get(index));
            }
            if (selectedCharacters + batchCharacters > budget.getMaxHistoryCharacters()) {
                if (selectedStart == history.size()) {
                    throw new IllegalStateException("Latest tool batch exceeds history character budget; "
                            + "increase --max-history-chars or request smaller tool results");
                }
                break;
            }
            selectedCharacters += batchCharacters;
            selectedStart = batchStart;
        }
        return new ModelRequest(session.objective(), history.subList(selectedStart, history.size()),
                tools, remainingCalls, selectedStart, selectedCharacters, session.memoryFacts());
    }
}
