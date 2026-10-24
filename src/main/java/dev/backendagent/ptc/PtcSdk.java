package dev.backendagent.ptc;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import dev.backendagent.tools.ToolDefinition;

/** Deterministic documentation of the exact binding allowlist and existing input schemas. */
public final class PtcSdk {
    private PtcSdk() { }
    public static final Set<String> READ_ONLY = Set.of(
            "integer_calculate", "search_history", "read_observation", "search_turns", "read_turn");

    public static String render(List<ToolDefinition> definitions) {
        var text = new StringBuilder("\n程序可调用的Python SDK（参数传一个dict，await；数字参数也可用字符串）：\n");
        definitions.stream().sorted(java.util.Comparator.comparing(ToolDefinition::getName)).forEach(tool -> {
            text.append("await tools.").append(tool.getName()).append("({");
            text.append(tool.getParameters().keySet().stream().sorted().map(k -> "\"" + k + "\": str"
                    + (tool.getRequiredParameters().contains(k) ? "" : " (可选)"))
                    .collect(Collectors.joining(", "))).append("})\n");
            tool.getParameters().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(e -> text.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append('\n'));
            text.append("  返回: ").append(switch (tool.getName()) {
                case "integer_calculate" -> "{expression, result_name, value:十进制整数字符串}；不证明业务公式正确";
                case "search_history" -> "{matches:[{callId,sequence,tool,validity,matchedLine,...}],nextBeforeSequence}；nextBeforeSequence=0表示无下一页";
                case "search_turns" -> "{matches:[{messageId,turnId,role,excerpt,...}],hasMore,pagination}";
                case "read_turn" -> "{messageId,turnId,role,content,totalLines,startLine,endLine,truncated,...}；content为无展示行号的正文，原行范围仍由startLine/endLine标注";
                case "read_observation" -> "{sourceCallId,content,evidence}；content带原结果行号，evidence含来源和时效性；过期片段仅作历史参考";
                default -> throw new IllegalArgumentException("Unsupported PTC binding");
            }).append('\n');
        });
        return text.toString();
    }
}
