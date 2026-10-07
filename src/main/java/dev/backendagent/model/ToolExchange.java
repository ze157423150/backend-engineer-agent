package dev.backendagent.model;

import java.util.Objects;

/** A call and its result travel together so context cannot accidentally orphan either one. */
public record ToolExchange(ToolCall call, ToolResult result, String assistantContent, int modelCallNumber,
                           dev.backendagent.runtime.ObservationEvidence evidence,
                           dev.backendagent.history.ArchivedBody archivedBody) {
    public ToolExchange(ToolCall call, ToolResult result, String assistantContent, int modelCallNumber,
                        dev.backendagent.runtime.ObservationEvidence evidence) {
        this(call, result, assistantContent, modelCallNumber, evidence, null);
    }
    public ToolExchange(ToolCall call, ToolResult result, String assistantContent, int modelCallNumber) {
        this(call, result, assistantContent, modelCallNumber, null);
    }
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
        if (evidence != null && (!call.name().equals("read_file") || !result.successful()
                || !evidence.getSourceCallId().equals(call.id()) || result.fileFingerprint() == null
                || !evidence.getFile().equals(result.fileFingerprint())
                || call.arguments().get("path") == null
                || !java.nio.file.Path.of(call.arguments().get("path")).normalize().toString()
                    .equals(evidence.getFile().getPath()))) {
            throw new IllegalArgumentException("Observation evidence does not match its tool exchange");
        }
    }
}
