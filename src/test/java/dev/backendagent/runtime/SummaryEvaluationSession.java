package dev.backendagent.runtime;

import dev.backendagent.model.*;
import java.util.List;
import java.util.Map;

/** Seeds synthetic complete batches for opt-in summary evaluation, without executing tools. */
public final class SummaryEvaluationSession {
    public static AgentSession seed(String objective, List<ToolExchange> middle) {
        var session = new AgentSession(objective);
        int batch = 0;
        for (var exchange : middle) {
            session.remember(new ToolExchange(exchange.call(), exchange.result(), null, ++batch));
            session.countModelCall();
        }
        for (int i = 0; i < ContextWindowPolicy.RECENT_BATCHES; i++) {
            session.remember(new ToolExchange(new ToolCall("recent-" + i, "list_files", Map.of()),
                    new ToolResult(true, "[空目录]"), null, ++batch));
            session.countModelCall();
        }
        return session;
    }
}
