package dev.backendagent.tools;

import java.util.Map;

import dev.backendagent.model.ToolResult;

public interface Tool {
    String name();

    default ToolDefinition definition() {
        return new ToolDefinition(name(), "Execute " + name(), Map.of());
    }

    ToolResult execute(Map<String, String> arguments);
}
