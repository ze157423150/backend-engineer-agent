package dev.backendagent.runtime;

import dev.backendagent.model.ToolExchange;

/** Retrieval bookkeeping already has dedicated context areas; it is not task state. */
public final class SummarySourcePolicy {
    private SummarySourcePolicy() { }
    public static boolean substantive(ToolExchange exchange) {
        return switch (exchange.call().name()) {
            case "search_history", "read_observation", "search_turns", "read_turn", "pin_verification_basis", "remember_fact" -> false;
            default -> true;
        };
    }
}
