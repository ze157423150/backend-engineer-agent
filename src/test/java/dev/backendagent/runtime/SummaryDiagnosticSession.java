package dev.backendagent.runtime;

import dev.backendagent.model.*;
import java.util.List;

/** Reconstructs the historical validator state without executing tools or changing saved sessions. */
public final class SummaryDiagnosticSession {
    public static AgentSession reconstruct(String objective, List<ToolExchange> history, int beforeCall, ContextSummary previous) {
        var session = new AgentSession(objective);
        for (var exchange : history) {
            if (exchange.modelCallNumber() < beforeCall) session.remember(exchange);
        }
        if (previous != null) session.installSummary(previous);
        return session;
    }
}
