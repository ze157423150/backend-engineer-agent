package dev.backendagent.runtime;

/** Ephemeral patch authorization result; no extra session or checkpoint state. */
public enum PatchSourceStatus {
    CURRENT("当前有效原文"),
    PREVIOUSLY_REPLACED("最近成功补丁已替换该旧片段；不要重放修改前的old_text。按最新成功结果的new_text继续；只有所需片段缺失时定向读取。"),
    SOURCE_VERSION_CHANGED("匹配的旧片段来自已变化的文件版本；文件指纹不再有效。按需读取当前修改范围，再基于同一版本提交相关修改。"),
    CURRENT_TEXT_MISMATCH("已有当前指纹的可见片段，但不包含old_text。检查范围、缩进、换行和JSON转义，去掉展示行号；不要重复转义。仅在所需原文不在有效片段中时补读。"),
    SOURCE_NOT_VISIBLE("当前上下文没有可授权这段old_text的有效原文；按需read_file或提取可核验的历史片段，不要猜测旧文本。"),
    SOURCE_UNAVAILABLE("无法核验当前文件指纹或来源；检查路径及工具错误，未确认前不要重复提交补丁。"),
    INVALID_ARGUMENTS("path和old_text必须非空；请检查参数。");

    private final String guidance;
    PatchSourceStatus(String guidance) { this.guidance = guidance; }
    public String feedback() { return "PATCH_SOURCE_" + name() + ": " + guidance; }
}
