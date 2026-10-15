package dev.backendagent.model;

import dev.backendagent.runtime.ContextSummary;
import java.util.List;
import java.util.Map;

/** Conversation quotes use the same request-local evidence IDs as tool summaries. */
final class ConversationEvidenceCatalog {
    private ConversationEvidenceCatalog() { }
    static List<SummaryEvidence> build(ConversationSummaryRequest request) {
        var originals = request.messages().stream().map(m -> new ToolExchange(
                new ToolCall(m.id(), "read_turn", Map.of()), new ToolResult(true, m.content()), null, 0)).toList();
        var visible = originals.stream().map(e -> new ToolExchange(e.call(), new ToolResult(true,
                e.result().content().replace("[消息节选；完整原文可用read_turn提取]", "[OMITTED]")), null, 0)).toList();
        var prior = request.previousSummary() == null ? null : new ContextSummary(request.previousSummary().getRevision(),
                request.previousSummary().getCoveredTurns(), request.previousSummary().getNotes());
        return SummaryEvidenceCatalog.build(visible, prior,
                id -> originals.stream().filter(e -> e.call().id().equals(id)).findFirst().orElseThrow());
    }
}
