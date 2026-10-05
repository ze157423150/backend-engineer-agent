package dev.backendagent.model;

import java.util.Objects;

/** A call and its result travel together so context cannot accidentally orphan either one. */
public record ToolExchange(ToolCall call, ToolResult result, String assistantContent, int modelCallNumber) {
    public ToolExchange(ToolCall call, ToolResult result) {
        this(call, result, null, 0);
    }

    public ToolExchange(ToolCall call, ToolResult result, String assistantContent) {
        this(call, result, assistantContent, 0);
    }

    public ToolExchange {
        Objects.requireNonNull(call);
        Objects.requireNonNull(result);
        // A positive number groups calls from one assistant message; zero is a standalone exchange.
        if (modelCallNumber < 0) {
            throw new IllegalArgumentException("Model call number must not be negative");
        }
    }
}
