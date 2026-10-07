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
        return new ToolDefinition(name(), "创建一个不存在的文本文件，绝不覆盖已有文件。父目录必须已存在；"
                + "先探索目录和项目约定，创建后检查差异并读取确认。", Map.of(
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
