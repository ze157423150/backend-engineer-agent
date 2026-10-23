package dev.backendagent.selection;

import java.util.List;
import java.util.Set;
import dev.backendagent.model.ToolExchange;
import dev.backendagent.tools.ToolDefinition;

/** Stable coding capabilities; optional capabilities are discovered by the model. */
public final class CoreToolSelector implements ToolSelector {
    private static final Set<String> CORE = Set.of(
            "list_files", "search_code", "read_file", "apply_patch", "run_tests");

    public List<ToolDefinition> select(String task, List<ToolExchange> history,
            List<ToolDefinition> catalog, int limit) {
        return catalog.stream().filter(t -> CORE.contains(t.getName())).toList();
    }
}
