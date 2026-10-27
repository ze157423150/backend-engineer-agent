package dev.backendagent.runtime;

import dev.backendagent.model.ToolExchange;
import dev.backendagent.model.ToolResult;

/** Deterministic excerpts, never a model-written summary. Durable original observations remain in the archive. */
public final class ToolResultCompressor {
    /** Current policy may prune even the latest result; never authorize a partial body as a full read. */
    public ToolExchange prune(ToolExchange source) {
        var compressed = compress(source);
        if (compressed == source) { return source; }
        var original = source.result();
        return new ToolExchange(source.call(), new ToolResult(original.successful(), compressed.result().content(),
                original.fileFingerprint(), original.historicalEvidence(),
                original.readMetadata() == null ? null : original.readMetadata().excerpt()),
                source.assistantContent(), source.modelCallNumber(), source.evidence());
    }
    /** Pressure-only display projection. The durable program output remains intact. */
    public ToolExchange pruneProgramOutput(ToolExchange source,int maxCharacters) {
        if(source.isProgramChild() || !source.call().name().equals("run_code")
                || source.result().content().length()<=maxCharacters)return source;
        try {
            var json=new com.fasterxml.jackson.databind.ObjectMapper();
            var original=json.readTree(source.result().content());
            if(!original.isObject() || !original.path("kind").asText().equals("program_result"))return source;
            var view=((com.fasterxml.jackson.databind.node.ObjectNode)original).deepCopy();
            var payload=json.createObjectNode();payload.set("result",view.remove("result"));payload.set("logs",view.remove("logs"));
            String text=json.writeValueAsString(payload);
            view.put("outputTruncated",true).put("archiveCallId",source.call().id())
                    .put("notice","程序结果已节选，不能视为完整原文；用read_observation按原结果行号提取archiveCallId，修改文件仍需有效文件来源。");
            view.put("outputExcerpt","");
            if(json.writeValueAsString(view).length()>=maxCharacters)return source; // Never drop safety receipts to fit.
            int low=0,high=text.length();String best=null;
            while(low<=high) {
                int amount=low+(high-low)/2,head=(amount*2)/3,tail=amount-head;
                String excerpt=text.substring(0,head)+(amount<text.length()?"\n[中间省略]\n":"")+text.substring(text.length()-tail);
                view.put("outputExcerpt",excerpt);String encoded=json.writeValueAsString(view);
                if(encoded.length()<=maxCharacters){best=encoded;low=amount+1;}else high=amount-1;
            }
            if(best==null || best.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    >=source.result().content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)return source;
            return new ToolExchange(source.call(),new ToolResult(source.result().successful(),best),source.assistantContent(),
                    source.modelCallNumber(),source.evidence(),source.archivedBody(),source.parentCallId());
        }catch(java.io.IOException invalid){return source;}
    }

    public ToolExchange compress(ToolExchange source) {
        if (source.isProgramChild()) return source;
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
