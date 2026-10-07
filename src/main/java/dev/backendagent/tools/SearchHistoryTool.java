package dev.backendagent.tools;

import java.util.Map;
import java.io.IOException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.backendagent.history.HistoryArchiveReader;
import dev.backendagent.model.ToolResult;
import dev.backendagent.persistence.PersistenceException;

public final class SearchHistoryTool implements Tool {
    private final HistoryArchiveReader archive;
    private final ObjectMapper json = new ObjectMapper();
    public SearchHistoryTool(HistoryArchiveReader archive) { this.archive = java.util.Objects.requireNonNull(archive); }
    public String name() { return "search_history"; }
    public ToolDefinition definition() {
        return new ToolDefinition(name(), "查找本会话归档中的历史工具结果，返回有限的调用ID、结果行号和有效性索引。"
                + "按新到旧分页；过期片段省略，用 read_observation 显式追溯。不会重放操作，也不是搜索当前源码。", Map.of(
                "tool", "工具名称精确匹配；空字符串表示不限，默认排除历史查询工具自身",
                "path", "调用参数中的相对路径精确匹配；空字符串表示不限，不是结果正文中提到的所有文件",
                "keyword", "结果正文中区分大小写的单行字面关键词，最多200字符；空字符串表示不限",
                "before_sequence", "首次填0；下一页填写非零 nextBeforeSequence，返回0表示没有下一页",
                "limit", "本页最多记录数，1到20，例如5"));
    }
    public ToolResult execute(Map<String, String> arguments) {
        if (!arguments.keySet().equals(definition().getParameters().keySet())) {
            return new ToolResult(false, "search_history requires tool, path, keyword, before_sequence and limit");
        }
        try {
            var page = archive.search(arguments.get("tool"), arguments.get("path"), arguments.get("keyword"),
                    Long.parseLong(arguments.get("before_sequence")), Integer.parseInt(arguments.get("limit")));
            String result = json.writeValueAsString(page);
            if (result.length() > 23500) { return new ToolResult(false, "History index exceeds output limit; reduce limit"); }
            return new ToolResult(true, result);
        } catch (IllegalArgumentException invalid) {
            return new ToolResult(false, "Invalid history query; use relative path, nonnegative cursor and limit 1..20");
        } catch (IOException failure) {
            throw new PersistenceException("Cannot read observation archive; execution stopped without memory fallback", failure);
        }
    }
}
