package dev.backendagent.model;

import java.util.Map;
import java.util.Objects;

public record ToolCall(String id, String name, Map<String, String> arguments) {
    public ToolCall {
        if (Objects.requireNonNull(id).isBlank() || Objects.requireNonNull(name).isBlank()) {
            throw new IllegalArgumentException("Tool call id and name must not be blank");
        }
        arguments = Map.copyOf(arguments);
    }
}
