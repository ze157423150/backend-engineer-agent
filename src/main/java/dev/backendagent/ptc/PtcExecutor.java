package dev.backendagent.ptc;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;

/** Executes a program; all project capabilities remain host-side bindings. */
public interface PtcExecutor {
    PtcRunResult run(String code, List<String> bindings, Dispatcher dispatcher);

    default PtcRunResult runWithDefinitions(String code, List<dev.backendagent.tools.ToolDefinition> definitions, Dispatcher dispatcher) {
        return run(code, definitions.stream().map(dev.backendagent.tools.ToolDefinition::getName).toList(), dispatcher);
    }

    @FunctionalInterface
    interface Dispatcher {
        JsonNode call(String name, Map<String, String> arguments);
    }
}
