package dev.backendagent.model;

import java.util.List;
import dev.backendagent.runtime.ConversationSummary;

/** Indices refer to original user turns, never tool history. */
public record ConversationSummaryRequest(String objective, ConversationSummary previousSummary,
        int fromIndex, int toIndex, int maxOutputCharacters, List<ConversationMessage> messages) {
    public ConversationSummaryRequest {
        messages = List.copyOf(messages);
        if (fromIndex < 0 || toIndex <= fromIndex || messages.isEmpty() || maxOutputCharacters < 1)
            throw new IllegalArgumentException("Invalid conversation summary request");
    }
}
