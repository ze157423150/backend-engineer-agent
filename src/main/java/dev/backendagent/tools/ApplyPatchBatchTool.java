package dev.backendagent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.backendagent.model.*;
import dev.backendagent.runtime.AgentSession;
import java.util.*;

/** One durable tool call, one file baseline, one atomic commit. */
public final class ApplyPatchBatchTool implements Tool {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final WorkspaceAccess workspace;
    private final AgentSession session;
    public ApplyPatchBatchTool(WorkspaceAccess workspace,AgentSession session){this.workspace=workspace;this.session=session;}
    public String name(){return "apply_patch_batch";}
    public ToolDefinition definition(){return new ToolDefinition(name(),"一次修改同一文件的1..16处不重叠文本。全部补丁按同一当前原文定位，先全量检查再原子写入；任一冲突整组不写。"
            +"patches为JSON数组字符串，每项含id、old_text、new_text；id须唯一。每项只替换匹配片段，保留其余内容；整文件更新须匹配完整旧文件，不用第一行替换整份内容。不要依赖同组前一修改的新文本。重叠时反馈冲突ID及原文字符范围，请合并或去重后重提。",
            Map.of("path","工作区相对文件路径","patches","JSON数组字符串，例如[{\"id\":\"a\",\"old_text\":\"old\",\"new_text\":\"new\"}]"));}
    public static List<PatchEdit> decode(String value)throws java.io.IOException{
        if(value==null||value.length()>96000)throw new IllegalArgumentException("PATCH_BATCH_LIMIT: patches必须是最多96000字符的JSON数组");
        var edits=JSON.readValue(value,new com.fasterxml.jackson.core.type.TypeReference<List<PatchEdit>>(){});
        if(edits==null||edits.isEmpty()||edits.size()>16)throw new IllegalArgumentException("PATCH_BATCH_LIMIT: 每组1..16处修改");
        return edits;
    }
    public static String encode(List<PatchEdit> edits){try{return JSON.writeValueAsString(edits);}catch(java.io.IOException failure){throw new IllegalArgumentException("Cannot encode edits",failure);}}
    public ToolResult execute(Map<String,String> arguments){
        if(session.status()!=AgentSession.Status.WAITING_FOR_TOOL)return new ToolResult(false,"PATCH_BATCH_STATE: 只能在工具阶段修改；本组未写入。");
        try{
            String path=arguments.get("path");var edits=decode(arguments.get("patches"));
            for(var edit:edits)if(edit.getOldText().equals(edit.getNewText()))
                return new ToolResult(false,"PATCH_NO_CHANGE: 补丁 "+edit.getId()+" 新旧文本相同；本组未写入，删除无改动项后重新提交，无需为此补读文件。");
            var baseline=workspace.fileFingerprint(path);
            for(var edit:edits) {
                var source=session.assessCurrentPatchSource(path,edit.getOldText(),workspace);
                if(source!=dev.backendagent.runtime.PatchSourceStatus.CURRENT)
                    return new ToolResult(false,"补丁 "+edit.getId()+"："+source.feedback()+"；本组全部未写入。");
            }
            var result=workspace.applyPatchBatch(path,baseline.getSha256(),edits);
            if(result.successful())session.invalidateFileEvidence(path);
            return result;
        }catch(dev.backendagent.persistence.PersistenceException uncertain){throw uncertain;}
        catch(java.io.IOException|IllegalArgumentException invalid){return new ToolResult(false,"PATCH_BATCH_INVALID: "+invalid.getMessage()+"；本组未写入，请检查JSON、ID及长度。");}
    }
}
