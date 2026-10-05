package dev.backendagent.tools;

import java.util.Map;
import dev.backendagent.model.ToolResult;

public final class WorkspaceDiffTool implements Tool {
    private final Workspace workspace;
    public WorkspaceDiffTool(Workspace workspace) { this.workspace = workspace; }
    public String name() { return "workspace_diff"; }
    public ToolDefinition definition() {
        return new ToolDefinition(name(), "查看本次创建的新文件或指定文件相对首次补丁前的差异。独立于 Git；+ 表示新增，- 表示删除，"
                + "只用于检查，不是可直接应用的补丁。", Map.of("path", "本次通过 create_file 创建或 apply_patch 修改的相对文件路径"));
    }
    public ToolResult execute(Map<String, String> arguments) { return workspace.diff(arguments.get("path")); }
}
