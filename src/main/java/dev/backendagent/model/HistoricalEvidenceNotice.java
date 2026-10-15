package dev.backendagent.model;

import dev.backendagent.history.HistoricalEvidence;
import java.util.List;
import java.io.IOException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Outgoing hints refer only to excerpts actually carried by this model request. */
final class HistoricalEvidenceNotice {
    private HistoricalEvidenceNotice() { }

    static ArrayNode currentRetrievals(ObjectMapper json, List<HistoricalEvidence> evidence) {
        var states = json.createArrayNode();
        for (var excerpt : evidence) {
            states.addObject().put("sourceCallId", excerpt.getSourceCallId())
                    .put("retrievalState", "IN_HISTORICAL_EVIDENCE_AREA")
                    .put("startLine", excerpt.getStartLine()).put("endLine", excerpt.getEndLine())
                    .put("truncated", excerpt.isTruncated()).put("usageScope", "HISTORICAL_ONLY");
        }
        return states;
    }

    static void annotateReference(ObjectNode reference, ObjectMapper json, List<HistoricalEvidence> evidence) {
        if (evidence.isEmpty()) { return; }
        reference.set("currentHistoricalRetrievals", currentRetrievals(json, evidence));
        reference.put("retrievalNotice", "以下来源的原文片段已在本次历史证据区，按sourceCallId及实际行范围查阅。"
                + "摘要、旧索引引句中的省略/待提取描述是当时状态，不代表本次片段不可见；"
                + "未列出的来源或范围仍需提取。旧引句保持原样，仅供历史追溯，不能证明当前文件或测试状态。");
    }

    static String historicalAnswer(String answer, List<HistoricalEvidence> evidence) {
        if (evidence.isEmpty()) { return answer; }
        return "[HISTORICAL_ASSISTANT_ANSWER: UNVERIFIED]\n"
                + "以下为过去的模型回答，不是本次检索或验证结果。其‘未取回/已隐藏/需要提取’等说法可能已过时；"
                + "片段当前是否可见，以本次historical_evidence_reference_data.entries和currentHistoricalRetrievals为准，"
                + "不能沿用旧回答否认已提取原文，也不能把旧回答中的测试当作本轮重新执行。\n"
                + answer;
    }

    static String content(ToolExchange exchange, List<HistoricalEvidence> evidence, ObjectMapper json) throws IOException {
        if (evidence.isEmpty() || !exchange.call().name().equals("search_history")
                || !exchange.result().successful()) { return content(exchange, evidence); }
        // Only decode actual search_history output. Never rewrite JSON quoted inside arbitrary source files.
        com.fasterxml.jackson.databind.JsonNode parsed;
        try { parsed = json.readTree(exchange.result().content()); }
        catch (IOException invalidOrArchivedIndex) { return content(exchange, evidence); }
        if (!(parsed instanceof ObjectNode index) || !index.path("matches").isArray()) {
            return content(exchange, evidence);
        }
        for (var match : index.path("matches")) {
            if (!(match instanceof ObjectNode item)) { continue; }
            evidence.stream().filter(e -> e.getSourceCallId().equals(item.path("callId").asText()))
                    .findFirst().ifPresent(excerpt -> {
                        item.set("currentHistoricalRetrieval", currentRetrievals(json, List.of(excerpt)).get(0));
                        if (item.path("snippet").asText().equals("[过期正文片段已省略；可显式提取历史]")) {
                            item.put("snippet", "[对应历史片段已提取到历史证据区；sourceCallId="
                                    + excerpt.getSourceCallId() + "; 范围=" + excerpt.getStartLine() + ".."
                                    + excerpt.getEndLine() + "；其他范围仍需提取，仅供历史追溯]");
                        }
                    });
        }
        index.put("notice", "仅为历史索引，validity是原证据状态；currentHistoricalRetrieval标明本次证据区实际保留的片段。"
                + "该片段可直接用于历史追溯，不要重复提取；未保留的来源或范围仍需read_observation，不能证明当前状态。");
        return json.writeValueAsString(index);
    }

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
