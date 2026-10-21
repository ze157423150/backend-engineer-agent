package dev.backendagent.selection;

import java.util.List;
import dev.backendagent.model.ToolExchange;
import dev.backendagent.tools.ToolDefinition;

/** Chooses definitions only; execution permissions remain with the registered tools. */
@FunctionalInterface
public interface ToolSelector {
    List<ToolDefinition> select(String task, List<ToolExchange> history, List<ToolDefinition> catalog, int limit);
}
