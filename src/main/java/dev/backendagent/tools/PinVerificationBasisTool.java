package dev.backendagent.tools;

import java.util.*;
import dev.backendagent.model.ToolResult;
import dev.backendagent.runtime.AgentSession;
import dev.backendagent.verification.*;

public final class PinVerificationBasisTool implements Tool {
    private final AgentSession session;private final WorkspaceAccess workspace;
    public PinVerificationBasisTool(AgentSession session,WorkspaceAccess workspace){this.session=session;this.workspace=workspace;session.bindVerificationWorkspace(workspace);}
    public String name(){return "pin_verification_basis";}
    public ToolDefinition definition(){return new ToolDefinition(name(),"补充本轮自动依据区尚未集中展示的契约原文，已有有效依据无需重复登记。仅接受本会话成功read_file已展示的完整片段。"
            +"来源与当前文件指纹必须一致。补充登记最多8份、合计6000字符；同ID更新会替换旧依据。新用户轮次清空补充登记并自动重评依据。",
            Map.of("basis_id","本轮依据标识，字母数字下划线横线，最多64字符","source_call_id","本会话成功read_file的调用ID",
                    "start_line","实际源文件起始行，不是工具结果行号","end_line","结束行，最多100行、1600字符"));}
    public ToolResult execute(Map<String,String> args){
        if(session.status()!=AgentSession.Status.WAITING_FOR_TOOL)return new ToolResult(false,"只能在工具阶段登记依据");
        try{
            var source=session.originalObservation(args.get("source_call_id"));var result=source.result();var metadata=result.readMetadata();
            if(!source.call().name().equals("read_file")||!result.successful()||result.fileFingerprint()==null||metadata==null
                    ||!metadata.isBodyComplete()||session.staleEvidenceIds().contains(source.call().id()))
                return new ToolResult(false,"依据必须来自成功且未过期的完整read_file片段；请重新读取契约");
            if(!VerificationSources.isContractDocument(result.fileFingerprint().getPath()))
                return new ToolResult(false,"仅补充需求说明或契约规范原文；源码、构建配置和测试断言不作为正确期望的依据");
            int start=Integer.parseInt(args.get("start_line")),end=Integer.parseInt(args.get("end_line"));
            if(start<metadata.getStartLine()||end>metadata.getEndLine()||start>end||end-start>=100)
                return new ToolResult(false,"所选行必须位于已读取范围内，最多100行");
            if(!result.fileFingerprint().equals(workspace.fileFingerprint(result.fileFingerprint().getPath())))
                return new ToolResult(false,"依据文件指纹已变化，请重新读取");
            var lines=new TreeMap<Integer,String>();
            if("raw".equals(source.call().arguments().get("format"))){
                String[] text=result.content().split("\n",-1);
                for(int i=0;i<text.length;i++)lines.put(metadata.getStartLine()+i,text[i]);
            }else{
                for(String line:result.content().split("\n",-1)){
                    var m=java.util.regex.Pattern.compile("^(\\d+): (.*)$").matcher(line);
                    if(m.matches())lines.put(Integer.parseInt(m.group(1)),m.group(2));
                }
            }
            var excerpt=new StringBuilder();for(int i=start;i<=end;i++){
                if(!lines.containsKey(i))return new ToolResult(false,"原文片段不完整，请重新读取");
                excerpt.append(lines.get(i)).append('\n');
            }
            var basis=new VerificationBasis(args.get("basis_id"),session.turns().size(),source.call().id(),
                    result.fileFingerprint(),start,end,excerpt.toString());
            session.verificationState().checkFits(basis);
            return new ToolResult(true,VerificationState.encode(basis));
        }catch(IllegalArgumentException|java.io.IOException invalid){return new ToolResult(false,"无法登记依据：检查本会话来源、行范围、ID和原文预算；必要时重新读取");}
    }
}
