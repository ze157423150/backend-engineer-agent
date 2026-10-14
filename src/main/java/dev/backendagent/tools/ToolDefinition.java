package dev.backendagent.tools;

import java.util.Map;
import java.util.Objects;

/** Required parameter descriptions. The model adapter normalizes integer arguments to the string map. */
public final class ToolDefinition {
    private final String name;
    private final String description;
    private final Map<String, String> parameters;
    private final java.util.Set<String> requiredParameters;

    public ToolDefinition(String name, String description, Map<String, String> parameters) {
        this(name, description, parameters, parameters.keySet());
    }
    public ToolDefinition(String name, String description, Map<String, String> parameters,
                          java.util.Set<String> requiredParameters) {
        if (Objects.requireNonNull(name).isBlank() || Objects.requireNonNull(description).isBlank()) {
            throw new IllegalArgumentException("Tool name and description must not be blank");
        }
        this.name = name;
        this.description = description;
        this.parameters = Map.copyOf(parameters);
        this.requiredParameters = java.util.Set.copyOf(requiredParameters);
        if (!parameters.keySet().containsAll(requiredParameters)) throw new IllegalArgumentException("Unknown required parameter");
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public Map<String, String> getParameters() { return parameters; }
    public java.util.Set<String> getRequiredParameters() { return requiredParameters; }
}
