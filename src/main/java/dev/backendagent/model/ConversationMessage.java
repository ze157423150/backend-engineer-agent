package dev.backendagent.model;

/** Raw dialogue source, independent of tool observations and file validity. */
public record ConversationMessage(String id, int turnId, String role, String content) {
    /** Compact display includes the complete retrieval ID, turn and speaker without duplicate fields. */
    public static void annotateSource(com.fasterxml.jackson.databind.node.ObjectNode item, String sourceId) {
        var match = java.util.regex.Pattern.compile("turn-([1-9][0-9]*)-(user|answer)").matcher(sourceId);
        if (!match.matches()) throw new IllegalArgumentException("Invalid conversation source");
        int turnId = Integer.parseInt(match.group(1));
        item.remove(java.util.List.of("sourceCallId", "sourceTurnId", "sourceRole"));
        item.put("source", "第" + turnId + "轮" + (match.group(2).equals("user") ? "用户" : "助手")
                + "（" + sourceId + "）");
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
