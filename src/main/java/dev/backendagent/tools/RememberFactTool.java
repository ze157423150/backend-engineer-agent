package dev.backendagent.tools;

import java.util.Map;
import java.util.Objects;
import dev.backendagent.model.ToolResult;
import dev.backendagent.runtime.AgentSession;

/** Stores evidence-backed notes in the current session, without changing repository files. */
public final class RememberFactTool implements Tool {
    private final AgentSession session;

    public RememberFactTool(AgentSession session) { this.session = Objects.requireNonNull(session); }
    public String name() { return "remember_fact"; }

    public ToolDefinition definition() {
        return new ToolDefinition(name(), "保存重要代码结论及原始证据到本次任务的工作记忆。最多 8 条、4000 字符，满时淘汰最早记录。"
                + "先读取或搜索，下一轮再引用其调用 ID。程序核验引文来源，但不保证结论正确。",
                Map.of("statement", "基于证据的简短结论，最多 300 字符，推断应明确标注",
                        "source_call_id", "已有成功 read_file 或 search_code 的工具调用 ID",
                        "evidence_quote", "从该工具结果逐字复制的证据，建议包含路径或行号，最多 400 字符"));
    }

    public ToolResult execute(Map<String, String> arguments) {
        session.saveFact(arguments.get("statement"), arguments.get("source_call_id"), arguments.get("evidence_quote"));
        return new ToolResult(true, "工作记忆已更新；证据来源已核验，结论仍需结合原文判断。当前 "
                + session.memoryFacts().size() + " 条、" + session.memoryCharacters() + " 字符。");
    }
}
