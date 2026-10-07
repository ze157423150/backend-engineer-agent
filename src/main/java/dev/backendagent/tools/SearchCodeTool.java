package dev.backendagent.tools;

import java.util.Map;
import dev.backendagent.model.ToolResult;

public final class SearchCodeTool implements Tool {
    private final WorkspaceAccess workspace;

    public SearchCodeTool(WorkspaceAccess workspace) { this.workspace = workspace; }
    public String name() { return "search_code"; }

    public ToolDefinition definition() {
        return new ToolDefinition(name(), "递归搜索文本中的字面关键词，返回文件路径、行号和片段。最多 50 个匹配、1000 个文件，深度 12。",
                Map.of("path", "工作区相对目录或文件路径，. 表示整个工作区",
                        "query", "根据任务或已有代码选择的非空关键词，不是正则表达式"));
    }

    public ToolResult execute(Map<String, String> arguments) {
        return workspace.searchCode(arguments.get("path"), arguments.get("query"));
    }
}
