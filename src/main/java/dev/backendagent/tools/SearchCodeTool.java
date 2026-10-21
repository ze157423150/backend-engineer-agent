package dev.backendagent.tools;

import java.util.Map;
import dev.backendagent.model.ToolResult;

public final class SearchCodeTool implements Tool {
    private final WorkspaceAccess workspace;

    public SearchCodeTool(WorkspaceAccess workspace) { this.workspace = workspace; }
    public String name() { return "search_code"; }

    public ToolDefinition definition() {
        return new ToolDefinition(name(), "递归搜索字面关键词，返回路径、行号和片段，用于定位和理解；不提供补丁来源指纹。修改所需原文若无有效读取或写入记录，先read_file；整文件重写须获取全文。最多50个匹配、1000个文件，深度12。",
                Map.of("path", "工作区相对目录或文件路径，. 表示整个工作区",
                        "query", "根据任务或已有代码选择的非空关键词，不是正则表达式"));
    }

    public ToolResult execute(Map<String, String> arguments) {
        return workspace.searchCode(arguments.get("path"), arguments.get("query"));
    }
}
