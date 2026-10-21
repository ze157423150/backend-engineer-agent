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
        return new ToolDefinition(name(), "修改已有文本文件，支持局部替换和整文件重写。整文件重写时，old_text填完整旧文件，new_text填完整新文件；不使用create_file覆盖。局部替换只改匹配片段，其余内容保留；不能用第一行匹配代替完整旧文件。可复用带当前指纹的读取、创建content或成功补丁片段；受控修改未影响目标片段时也可复用，无需重复读取；旧文本必须唯一匹配。"
                + "同文件多处修改优先apply_patch_batch；同一响应中连续同文件补丁会整组校验，重叠整组拒绝。支持片段删除，不创建或删除文件，不执行命令。", Map.of(
                "path", "工作区相对文件路径", "old_text", "从可见且指纹有效的读取、创建content或成功补丁new_text复制，保留缩进换行；缺少片段时read_file",
                "new_text", "替换后的文本，可为空；新旧文本各最多 24000 字符"));
    }

    public ToolResult execute(Map<String, String> arguments) {
        if (session.status() == AgentSession.Status.WAITING_FOR_TOOL
                && arguments.get("old_text") != null
                && arguments.get("old_text").equals(arguments.get("new_text"))) {
            return new ToolResult(false, "PATCH_NO_CHANGE: 新旧文本完全相同，未写入；无需为此补读文件，请仅提交实际修改。");
        }
        if (session.status() != AgentSession.Status.WAITING_FOR_TOOL) {
            return new ToolResult(false, "PATCH_STATE: 只能在工具阶段修改；未写入。");
        }
        final dev.backendagent.model.FileFingerprint baseline;
        try { baseline = workspace.fileFingerprint(arguments.get("path")); }
        catch (java.io.IOException | IllegalArgumentException unavailable) {
            return new ToolResult(false, "PATCH_BASELINE_UNAVAILABLE: 无法核验文件指纹；未写入。");
        }
        var source = session.assessCurrentPatchSource(arguments.get("path"), arguments.get("old_text"), workspace);
        if (source != dev.backendagent.runtime.PatchSourceStatus.CURRENT) {
            return new ToolResult(false, source.feedback() + "；未写入。");
        }
        ToolResult result = workspace.applyPatchBatch(arguments.get("path"), baseline.getSha256(),
                java.util.List.of(new PatchEdit("single",arguments.get("old_text"),arguments.get("new_text"))))
                .withPatchBase(baseline);
        if (result.successful()) {
            session.invalidateFileEvidence(arguments.get("path"));
        }
        return result;
    }
}
