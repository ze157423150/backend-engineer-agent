package dev.backendagent.runtime;

import dev.backendagent.model.ToolExchange;
import dev.backendagent.model.ToolResult;

/** Deterministic excerpts, never a model-written summary. Durable original observations remain in the archive. */
public final class ToolResultCompressor {
    public ToolExchange compress(ToolExchange source) {
        String raw = source.result().content();
        if (raw.length() <= 8192) { return source; }
        String marker = "\n[历史结果已节选；call_id=" + source.call().id()
                + "；用 read_observation 读取原始结果行号，非源文件行号]\n";
        String excerpt;
        switch (source.call().name()) {
            case "read_file", "workspace_diff", "read_observation" ->
                excerpt = raw.substring(0, 3200) + marker + raw.substring(raw.length() - 1200);
            case "search_code", "list_files" -> excerpt = raw.substring(0, 4400) + marker;
            case "run_tests" -> {
                var diagnostics = new StringBuilder();
                for (String line : raw.split("\\R")) {
                    String lower = line.toLowerCase(java.util.Locale.ROOT);
                    if (lower.contains("[error]") || lower.contains("caused by:")
                            || lower.contains("failure") || lower.contains("assertion")
                            || lower.contains("expected:")) {
                        if (diagnostics.length() + line.length() + 1 > 2400) { break; }
                        diagnostics.append(line).append('\n');
                    }
                }
                excerpt = raw.substring(0, 1200) + marker + "[错误行节选，可能不完整]\n"
                        + diagnostics + marker + raw.substring(raw.length() - 1200);
            }
            // Write acknowledgements and unknown tools are retained verbatim.
            default -> { return source; }
        }
        if (excerpt.length() >= raw.length()) { return source; }
        return new ToolExchange(source.call(), new ToolResult(source.result().successful(), excerpt, source.result().fileFingerprint()),
                source.assistantContent(), source.modelCallNumber(), source.evidence());
    }
}
