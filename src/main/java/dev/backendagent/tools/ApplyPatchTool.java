package dev.backendagent.tools;

import java.util.Map;
import dev.backendagent.model.ToolResult;
import dev.backendagent.runtime.AgentSession;

public final class ApplyPatchTool implements Tool {
    private final WorkspaceAccess workspace;
    private final AgentSession session;

    public ApplyPatchTool(WorkspaceAccess workspace, AgentSession session) {
        this.workspace = workspace;
        this.session = session;
    }

    public String name() { return "apply_patch"; }
    public ToolDefinition definition() {
        return new ToolDefinition(name(), "精确替换已有文本文件中的一处内容。使用当前上下文中仍有效的读取片段或成功创建参数，无需重复读取；旧文本只允许匹配一次。"
                + "支持片段删除，不创建或删除文件，不执行命令。", Map.of(
                "path", "工作区相对文件路径", "old_text", "从有效raw读取或成功创建参数复制旧文本，逐字匹配并保留缩进换行；缺少该片段时重新read_file(format=raw)",
                "new_text", "替换后的文本，可为空；新旧文本各最多 24000 字符"));
    }

    public ToolResult execute(Map<String, String> arguments) {
        if (session.status() != AgentSession.Status.WAITING_FOR_TOOL
                || !session.hasCurrentPatchSource(arguments.get("path"), arguments.get("old_text"), workspace)) {
            return new ToolResult(false, "补丁未应用：当前可见记录缺少该旧文本或指纹已过期；可复用有效读取/成功创建内容，否则先read_file(format=raw)。修改后的文件仍需取得新内容和指纹。");
        }
        ToolResult result = workspace.applyPatch(arguments.get("path"), arguments.get("old_text"), arguments.get("new_text"));
        if (result.successful()) {
            session.invalidateFileEvidence(arguments.get("path"));
        }
        return result;
    }
}
