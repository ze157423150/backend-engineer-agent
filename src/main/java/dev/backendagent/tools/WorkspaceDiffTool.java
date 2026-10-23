package dev.backendagent.tools;

import java.util.Map;
import dev.backendagent.model.ToolResult;

public final class WorkspaceDiffTool implements Tool {
    private final WorkspaceAccess workspace;
    public WorkspaceDiffTool(WorkspaceAccess workspace) { this.workspace = workspace; }
    public String name() { return "workspace_diff"; }
    public void beginTurn(int turnId) { workspace.beginTurn(turnId); }
    public ToolDefinition definition() {
        return new ToolDefinition(name(), "查看文件净差异，默认本轮首次成功写入前到当前的变化；scope=session查看会话累计差异。独立于Git，+新增，-删除；只用于检查，不是补丁原文。",
                Map.of("path", "已修改文件的相对路径", "scope", "turn（默认，本轮）或session（会话累计）"),java.util.Set.of("path"));
    }
    public ToolResult execute(Map<String, String> arguments) { return workspace.diff(arguments.get("path"),arguments.getOrDefault("scope","turn")); }
}
