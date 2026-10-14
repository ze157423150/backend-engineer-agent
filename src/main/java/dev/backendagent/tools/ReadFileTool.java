package dev.backendagent.tools;

import java.util.Map;
import dev.backendagent.model.ToolResult;

public final class ReadFileTool implements Tool {
    private final WorkspaceAccess workspace;
    private final java.util.Deque<ReadMark> recentReads=new java.util.ArrayDeque<>();

    public ReadFileTool(WorkspaceAccess workspace) { this.workspace = workspace; }
    public String name() { return "read_file"; }

    public ToolDefinition definition() {
        return new ToolDefinition(name(), "按行读取真实文件，正文与readMetadata定位信息分开，保留完整文件字节的SHA-256证据。每次最多200行；优先一次读取完整方法或测试块。",
                Map.of("path", "根据 list_files 或 search_code 结果选择的工作区相对文件路径",
                        "start_line", "起始行号，从 1 开始，例如 1",
                        "end_line", "结束行号，包含该行，例如 120",
                        "format", "可选，numbered（默认）带行号；raw仅返回原始源码，保留缩进和换行，构造精确补丁必须用raw"),
                java.util.Set.of("path", "start_line", "end_line"));
    }

    public ToolResult execute(Map<String, String> arguments) {
        ToolResult result=workspace.readFile(arguments.get("path"), arguments.get("start_line"), arguments.get("end_line"), arguments.get("format"));
        if(result.successful()&&result.readMetadata()!=null&&result.fileFingerprint()!=null){
            var coverage=new dev.backendagent.model.ReadCoverage();
            for(var read:recentReads)coverage.observe(read.file,read.range);
            boolean repeated=!coverage.observe(result.fileFingerprint(),result.readMetadata());
            result=new ToolResult(true,result.content(),result.fileFingerprint(),result.historicalEvidence(),result.readMetadata().repeated(repeated));
            recentReads.addLast(new ReadMark(result.fileFingerprint(),result.readMetadata()));while(recentReads.size()>12)recentReads.removeFirst();
        }
        return result;
    }
    private static final class ReadMark {
        final dev.backendagent.model.FileFingerprint file;
        final dev.backendagent.model.FileReadMetadata range;
        ReadMark(dev.backendagent.model.FileFingerprint file,dev.backendagent.model.FileReadMetadata range){this.file=file;this.range=range;}
    }
}
