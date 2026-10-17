package dev.backendagent.tools;

import java.util.Map;
import dev.backendagent.model.ToolResult;

public final class ReadFileTool implements Tool {
    private final WorkspaceAccess workspace;
    private final java.util.Deque<ReadMark> recentReads=new java.util.ArrayDeque<>();

    public ReadFileTool(WorkspaceAccess workspace) { this.workspace = workspace; }
    public String name() { return "read_file"; }

    public ToolDefinition definition() {
        return new ToolDefinition(name(), "按行读取文件，返回file头和源码行号；每次最多200行，附实际范围及文件指纹。补丁文本需去掉展示行号，保留源码缩进；已有有效片段直接复用。",
                Map.of("path", "工作区相对文件路径",
                        "start_line", "起始行，1起",
                        "end_line", "结束行，含该行"));
    }

    public ToolResult execute(Map<String, String> arguments) {
        ToolResult result=workspace.readFile(arguments.get("path"), arguments.get("start_line"), arguments.get("end_line"));
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
