package dev.backendagent.tools;

import java.util.Map;
import dev.backendagent.model.ToolResult;
import dev.backendagent.runtime.AgentSession;

public final class CreateFileTool implements Tool {
    private final WorkspaceAccess workspace;
    private final AgentSession session;

    public CreateFileTool(WorkspaceAccess workspace, AgentSession session) {
        this.workspace = workspace;
        this.session = session;
    }
    public String name() { return "create_file"; }
    public ToolDefinition definition() {
        return new ToolDefinition(name(), "创建一个不存在的文本文件，绝不覆盖已有文件。安全创建工作区内缺失的父目录；"
                + "仅在目标目录或约定未知时探索；成功返回创建指纹，完整创建参数仍可见且指纹有效时可直接apply_patch，不必再读取。", Map.of(
                "path", "工作区相对新文件路径，使用允许的文本扩展名，例如 java、xml、md",
                "content", "完整文件文本，最多 24000 字符，可为空"));
    }
    public ToolResult execute(Map<String, String> arguments) {
        if (session.status() != AgentSession.Status.WAITING_FOR_TOOL) {
            return new ToolResult(false, "只能在工具执行阶段创建文件");
        }
        ToolResult result = workspace.createFile(arguments.get("path"), arguments.get("content"));
        if (result.successful()) { session.invalidateFileEvidence(arguments.get("path")); }
        return result;
    }
}
