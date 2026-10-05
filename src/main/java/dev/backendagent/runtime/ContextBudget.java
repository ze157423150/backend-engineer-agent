package dev.backendagent.runtime;

import dev.backendagent.model.ToolExchange;

/** Budget for text held in tool history, not a tokenizer or a full HTTP request limit. */
public final class ContextBudget {
    public static final int DEFAULT_MAX_HISTORY_CHARACTERS = 64_000;
    private final int maxHistoryCharacters;

    public ContextBudget(int maxHistoryCharacters) {
        if (maxHistoryCharacters <= 0) {
            throw new IllegalArgumentException("History character budget must be positive");
        }
        this.maxHistoryCharacters = maxHistoryCharacters;
    }

    public int getMaxHistoryCharacters() { return maxHistoryCharacters; }

    // Java String.length(): UTF-16 code units. Includes fields and arguments, excludes JSON framing.
    public long measure(ToolExchange exchange) {
        long size = exchange.call().id().length() + (long) exchange.call().name().length()
                + exchange.result().content().length();
        if (exchange.assistantContent() != null) {
            size += exchange.assistantContent().length();
        }
        for (var argument : exchange.call().arguments().entrySet()) {
            size += argument.getKey().length() + (long) argument.getValue().length();
        }
        return size;
    }
}
