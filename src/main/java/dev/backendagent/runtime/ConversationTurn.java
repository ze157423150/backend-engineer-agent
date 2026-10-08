package dev.backendagent.runtime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Immutable user turn snapshot. Tool history ranges are half-open and refer to the full history. */
public final class ConversationTurn {
    private final int turnId;
    private final String userMessage;
    private final AgentSession.Status status;
    private final String answer;
    private final int startHistoryIndex;
    private final int endHistoryIndex;
    @JsonCreator
    public ConversationTurn(@JsonProperty("turnId") int turnId, @JsonProperty("userMessage") String userMessage,
            @JsonProperty("status") AgentSession.Status status, @JsonProperty("answer") String answer,
            @JsonProperty("startHistoryIndex") int startHistoryIndex, @JsonProperty("endHistoryIndex") int endHistoryIndex) {
        if (turnId < 1 || userMessage == null || userMessage.isBlank() || userMessage.length() > 8000
                || status == null || startHistoryIndex < 0 || endHistoryIndex < startHistoryIndex
                || (status == AgentSession.Status.COMPLETED && (answer == null || answer.isBlank()))) {
            throw new IllegalArgumentException("Invalid conversation turn");
        }
        this.turnId = turnId; this.userMessage = userMessage; this.status = status; this.answer = answer;
        this.startHistoryIndex = startHistoryIndex; this.endHistoryIndex = endHistoryIndex;
    }
    public int getTurnId() { return turnId; }
    public String getUserMessage() { return userMessage; }
    public AgentSession.Status getStatus() { return status; }
    public String getAnswer() { return answer; }
    public int getStartHistoryIndex() { return startHistoryIndex; }
    public int getEndHistoryIndex() { return endHistoryIndex; }
}
