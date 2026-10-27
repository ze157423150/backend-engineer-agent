package dev.backendagent.ptc;

import java.util.List;
import java.util.stream.Collectors;
import dev.backendagent.tools.ToolDefinition;

/** Generated documentation of the actual registered bindings and program value adapters. */
public final class PtcSdk {
    private PtcSdk() { }
    public static String render(List<ToolDefinition> definitions) {
        var text = new StringBuilder("\nPython SDK签名（工具已注入，无需重定义）：await tools.name(参数名=值)，必填参数无默认值，可选参数可省略。兼容单个dict，不与关键字混用；参数支持字符串或整数，patches需JSON字符串。返回附call_id。\n");
        definitions.stream().sorted(java.util.Comparator.comparing(ToolDefinition::getName)).forEach(tool -> {
            text.append(description(tool)).append('\n');
            text.append("async def ").append(tool.getName()).append("(");
            if (!tool.getParameters().isEmpty()) text.append("*, ");
            text.append(tool.getParameters().keySet().stream().sorted().map(k -> k + ": str | int"
                    + (tool.getRequiredParameters().contains(k) ? "" : " = ..."))
                    .collect(Collectors.joining(", "))).append(") -> dict: ...\n");
            tool.getParameters().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(e -> {
                        text.append("  ").append(e.getKey()).append(": ").append(e.getValue());
                        if (tool.getName().equals("apply_patch_batch") && e.getKey().equals("patches"))
                            text.append("；Python列表先用json.dumps(edits, ensure_ascii=False)转字符串，不能直接传list");
                        text.append('\n');
                    });
            text.append("  返回: ").append(switch (tool.getName()) {
                case "integer_calculate" -> "{expression, result_name, value:十进制整数字符串}；不证明业务公式正确";
                case "search_history" -> "{matches:[{callId,sequence,tool,validity,matchedLine,...}],nextBeforeSequence}；nextBeforeSequence=0表示无下一页";
                case "search_turns" -> "{matches:[{messageId,turnId,role,excerpt,...}],hasMore,pagination}";
                case "read_turn" -> "{messageId,turnId,role,content,totalLines,startLine,endLine,truncated,...}；content为无展示行号的正文，原行范围仍由startLine/endLine标注";
                case "read_observation" -> "{sourceCallId,content,evidence}；content带原结果行号，evidence含来源和时效性；过期片段仅作历史参考";
                case "read_file" -> "{successful,content:无展示行号的源码,fileFingerprint,readMetadata,call_id}；参数仍按源文件行号";
                default -> "{successful,content,fileFingerprint?,readMetadata?,call_id}；content为工具原始文本，失败抛ToolCallError";
            }).append('\n');
        });
        return text.toString();
    }

    /** Native response batching is not the program's sequence of individually committed calls. */
    private static String description(ToolDefinition tool) {
        return switch (tool.getName()) {
            case "read_file" -> "按源文件行号读取，每次最多200行；content为无展示行号和file头的源码，不再次剥离；保留源码缩进，fileFingerprint/readMetadata说明来源范围。";
            case "apply_patch" -> "修改已有文本文件，old_text须从可见且有效的读取、创建content或成功补丁片段复制并唯一匹配；受控修改未影响该片段时可复用。局部替换保留其余内容，整文件重写须提供完整旧、新文件，不能用第一行代替全文。"
                    + "同文件多处修改优先apply_patch_batch；多个await apply_patch逐次提交，不自动归并或回滚。缺原文时可在同一程序先read_file，再直接用content构造补丁，不凭摘要还原。支持片段删除，不创建或删除文件，不执行命令。";
            case "list_files" -> tool.getDescription() + "根目录调用：await tools.list_files({\"path\":\".\"})，不省略path。";
            case "remember_fact" -> "保存重要代码结论及原始证据到工作记忆，最多8条、4000字符，满时淘汰最早记录。引用已完成读取或搜索返回的call_id，可在同一程序后续调用；只核验引文来源，不保证结论正确。";
            default -> tool.getDescription();
        };
    }
}
