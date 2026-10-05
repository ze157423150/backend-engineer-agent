package dev.backendagent.tools;

import java.util.Map;
import dev.backendagent.model.ToolResult;
import dev.backendagent.runtime.AgentSession;

public final class ApplyPatchTool implements Tool {
    private final Workspace workspace;
    private final AgentSession session;

    public ApplyPatchTool(Workspace workspace, AgentSession session) {
        this.workspace = workspace;
        this.session = session;
    }

    public String name() { return "apply_patch"; }
    public ToolDefinition definition() {
        return new ToolDefinition(name(), "精确替换已有文本文件中的一处内容。必须先读取；旧文本只允许匹配一次。"
                + "支持片段删除，不创建或删除文件，不执行命令。", Map.of(
                "path", "工作区相对文件路径", "old_text", "原文件中逐字匹配的旧文本，不包含读取输出的行号，必须非空",
                "new_text", "替换后的文本，可为空；新旧文本各最多 24000 字符"));
    }

    public ToolResult execute(Map<String, String> arguments) {
        if (session.status() != AgentSession.Status.WAITING_FOR_TOOL
                || !session.hasCurrentRead(arguments.get("path"))) {
            return new ToolResult(false, "补丁未应用：必须先成功读取该文件；每次修改后需要重新读取才能再次修改");
        }
        ToolResult result = workspace.applyPatch(arguments.get("path"), arguments.get("old_text"), arguments.get("new_text"));
        if (result.successful()) {
            session.invalidateFileEvidence(arguments.get("path"));
        }
        return result;
    }
}
