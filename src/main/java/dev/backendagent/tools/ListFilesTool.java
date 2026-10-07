package dev.backendagent.tools;

import java.util.Map;
import dev.backendagent.model.ToolResult;

public final class ListFilesTool implements Tool {
    private final WorkspaceAccess workspace;

    public ListFilesTool(WorkspaceAccess workspace) { this.workspace = workspace; }
    public String name() { return "list_files"; }

    public ToolDefinition definition() {
        return new ToolDefinition(name(), "列出工作区内某目录的直接子项，目录后缀为 /。最多 100 项。",
                Map.of("path", "工作区相对目录，首次探索可填写 .，之后根据返回结果选择子目录"));
    }

    public ToolResult execute(Map<String, String> arguments) {
        return workspace.listFiles(arguments.get("path"));
    }
}
