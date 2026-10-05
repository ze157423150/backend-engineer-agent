package dev.backendagent.model;

import java.util.Objects;

public record ToolResult(boolean successful, String content) {
    public ToolResult {
        Objects.requireNonNull(content);
    }
}
