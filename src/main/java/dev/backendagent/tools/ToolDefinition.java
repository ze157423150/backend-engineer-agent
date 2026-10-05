package dev.backendagent.tools;

import java.util.Map;
import java.util.Objects;

/** In this increment, all tool parameters are required strings. */
public final class ToolDefinition {
    private final String name;
    private final String description;
    private final Map<String, String> parameters;

    public ToolDefinition(String name, String description, Map<String, String> parameters) {
        if (Objects.requireNonNull(name).isBlank() || Objects.requireNonNull(description).isBlank()) {
            throw new IllegalArgumentException("Tool name and description must not be blank");
        }
        this.name = name;
        this.description = description;
        this.parameters = Map.copyOf(parameters);
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public Map<String, String> getParameters() { return parameters; }
}
