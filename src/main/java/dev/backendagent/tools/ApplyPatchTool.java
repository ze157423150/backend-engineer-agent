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

    public ApplyPatchBatchTool batchTool() { return new ApplyPatchBatchTool(workspace, session); }

    public String name() { return "apply_patch"; }
    public ToolDefinition definition() {
        return new ToolDefinition(name(), "精确替换已有文本文件中的一处内容。可复用带当前文件指纹的读取、成功创建或成功补丁片段，无需重复读取；旧文本只允许匹配一次。"
                + "同文件多处修改优先apply_patch_batch；同一响应中连续同文件补丁会整组校验，重叠整组拒绝。支持片段删除，不创建或删除文件，不执行命令。", Map.of(
                "path", "工作区相对文件路径", "old_text", "从可见且指纹有效的读取、创建content或成功补丁new_text复制，保留缩进换行；缺少片段时read_file",
                "new_text", "替换后的文本，可为空；新旧文本各最多 24000 字符"));
    }

    public ToolResult execute(Map<String, String> arguments) {
        if (session.status() != AgentSession.Status.WAITING_FOR_TOOL
                || !session.hasCurrentPatchSource(arguments.get("path"), arguments.get("old_text"), workspace)) {
            return new ToolResult(false, "补丁未应用：缺少带当前指纹的可见旧文本；可复用有效读取、创建content或成功补丁new_text。旧补丁没有指纹、文件已变化或缺少片段时，先read_file。");
        }
        ToolResult result = workspace.applyPatch(arguments.get("path"), arguments.get("old_text"), arguments.get("new_text"));
        if (result.successful()) {
            session.invalidateFileEvidence(arguments.get("path"));
        }
        return result;
    }
}
