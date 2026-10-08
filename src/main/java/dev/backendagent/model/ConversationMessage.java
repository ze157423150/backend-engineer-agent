package dev.backendagent.model;

/** Raw dialogue source, independent of tool observations and file validity. */
public record ConversationMessage(String id, int turnId, String role, String content) {
    public static ConversationMessage user(dev.backendagent.runtime.ConversationTurn turn) {
        return new ConversationMessage("turn-" + turn.getTurnId() + "-user", turn.getTurnId(), "user", turn.getUserMessage());
    }
    public static ConversationMessage answer(dev.backendagent.runtime.ConversationTurn turn) {
        return new ConversationMessage("turn-" + turn.getTurnId() + "-answer", turn.getTurnId(), "assistant", turn.getAnswer());
    }
    public ConversationMessage {
        if (turnId < 1 || !(role.equals("user") || role.equals("assistant")) || content == null || content.isBlank()
                || !id.equals("turn-" + turnId + (role.equals("user") ? "-user" : "-answer"))) {
            throw new IllegalArgumentException("Invalid conversation message");
        }
    }
}
