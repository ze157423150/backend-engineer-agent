package dev.backendagent.model;

/** Raw dialogue source, independent of tool observations and file validity. */
public record ConversationMessage(String id, int turnId, String role, String content) {
    /** Derived from the validated message identity; the model does not supply these fields. */
    public static void annotateSource(com.fasterxml.jackson.databind.node.ObjectNode item, String sourceId) {
        var match = java.util.regex.Pattern.compile("turn-([1-9][0-9]*)-(user|answer)").matcher(sourceId);
        if (!match.matches()) throw new IllegalArgumentException("Invalid conversation source");
        item.put("sourceTurnId", Integer.parseInt(match.group(1)))
                .put("sourceRole", match.group(2).equals("user") ? "user" : "assistant");
    }
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
