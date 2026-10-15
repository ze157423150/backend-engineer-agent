package dev.backendagent.model;

import dev.backendagent.history.HistoricalEvidence;
import java.util.List;

/** Outgoing hints refer only to excerpts actually carried by this model request. */
final class HistoricalEvidenceNotice {
    private HistoricalEvidenceNotice() { }

    static String content(ToolExchange exchange, List<HistoricalEvidence> evidence) {
        String content = exchange.result().content();
        boolean stalePlaceholder = content.startsWith("[STALE_OBSERVATION_BODY_OMITTED]");
        if (!stalePlaceholder && exchange.archivedBody() == null) { return content; }
        String sourceId = exchange.call().name().equals("read_observation")
                ? exchange.call().arguments().get("call_id") : exchange.call().id();
        var retained = evidence.stream().filter(e -> e.getSourceCallId().equals(sourceId)).findFirst();
        if (retained.isEmpty()) { return content; }
        var excerpt = retained.get();
        String notice = "[HISTORICAL_EXCERPT_IN_EVIDENCE_AREA] call_id=" + exchange.call().id()
                + " source_call_id=" + excerpt.getSourceCallId()
                + "\n对应历史原文片段已提取到本次上下文的历史证据区 historical_evidence_reference_data.entries；"
                + "请按 sourceCallId 查阅，无需为同一片段重复提取。"
                + "缓存范围为原工具结果第 " + excerpt.getStartLine() + ".." + excerpt.getEndLine()
                + " 行，truncated=" + excerpt.isTruncated()
                + "；仅此范围可见，不代表整份原文已取回，其他范围仍需 read_observation。"
                + "usageScope=HISTORICAL_ONLY，仅供追溯，不能证明当前状态；当前代码或测试需重新读取或运行。";
        if (stalePlaceholder) { return notice; }
        String marker = "[ARCHIVED_BODY call_id=" + exchange.call().id() + ";";
        int from = content.indexOf(marker);
        int to = from < 0 ? -1 : content.indexOf(']', from);
        return to < 0 ? content : content.substring(0, from) + notice + content.substring(to + 1);
    }
}
