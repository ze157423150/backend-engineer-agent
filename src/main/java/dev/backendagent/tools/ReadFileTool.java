package dev.backendagent.tools;

import java.util.Map;
import dev.backendagent.model.ToolResult;

public final class ReadFileTool implements Tool {
    private final WorkspaceAccess workspace;

    public ReadFileTool(WorkspaceAccess workspace) { this.workspace = workspace; }
    public String name() { return "read_file"; }

    public ToolDefinition definition() {
        return new ToolDefinition(name(), "按行读取工作区内真实文本文件，返回文件路径、总行数、行号及完整文件字节的 SHA-256 证据。每次最多 200 行。",
                Map.of("path", "根据 list_files 或 search_code 结果选择的工作区相对文件路径",
                        "start_line", "起始行号，从 1 开始，例如 1",
                        "end_line", "结束行号，包含该行，例如 120"));
    }

    public ToolResult execute(Map<String, String> arguments) {
        return workspace.readFile(arguments.get("path"), arguments.get("start_line"), arguments.get("end_line"));
    }
}
