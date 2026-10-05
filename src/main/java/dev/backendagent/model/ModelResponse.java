package dev.backendagent.model;

import java.util.Objects;
import java.util.List;

/** A model response contains either a batch of tool calls or a final answer. */
public final class ModelResponse {
    public enum Type { TOOL_CALL, FINISH }

    private final Type type;
    private final List<ToolCall> toolCalls;
    private final String answer;
    private final String assistantContent;

    // Factory methods below ensure the type always matches the populated field.
    private ModelResponse(Type type, List<ToolCall> toolCalls, String answer, String assistantContent) {
        this.type = type;
        this.toolCalls = List.copyOf(toolCalls);
        this.answer = answer;
        this.assistantContent = assistantContent;
    }

    public static ModelResponse callTool(ToolCall toolCall) {
        return callTool(toolCall, null);
    }

    public static ModelResponse callTool(ToolCall toolCall, String assistantContent) {
        Objects.requireNonNull(toolCall, "Tool call must not be null");
        return callTools(List.of(toolCall), assistantContent);
    }

    public static ModelResponse callTools(List<ToolCall> toolCalls, String assistantContent) {
        if (Objects.requireNonNull(toolCalls, "Tool calls must not be null").isEmpty()) {
            throw new IllegalArgumentException("Tool calls must not be empty");
        }
        return new ModelResponse(Type.TOOL_CALL, toolCalls, null, assistantContent);
    }

    public static ModelResponse finish(String answer) {
        if (Objects.requireNonNull(answer, "Final answer must not be null").isBlank()) {
            throw new IllegalArgumentException("Final answer must not be blank");
        }
        return new ModelResponse(Type.FINISH, List.of(), answer, null);
    }

    public Type getType() {
        return type;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    public String getAnswer() {
        return answer;
    }

    /** Optional text accompanying a tool request; preserved when replaying that message. */
    public String getAssistantContent() {
        return assistantContent;
    }

    @Override
    public String toString() {
        return "ModelResponse{type=" + type + ", toolCalls=" + toolCalls
                + ", answer=" + answer + ", assistantContent=" + assistantContent + "}";
    }
}
