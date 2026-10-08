package dev.backendagent.tools;

import dev.backendagent.history.ConversationHistory;
import dev.backendagent.model.ToolResult;
import dev.backendagent.runtime.AgentSession;
import java.util.*;

public final class ReadTurnTool implements Tool {
    private final ConversationHistory history;
    public ReadTurnTool(AgentSession session){history=new ConversationHistory(session);}
    public String name(){return "read_turn";}
    public ToolDefinition definition(){return new ToolDefinition(name(),
            "按turn-N-user或turn-N-answer消息ID提取本会话原始历史消息，行号属于消息正文。返回后续轮次标识，不自动判定要求已撤销。",
            Map.of("message_id","原始消息ID","start_line","起始行，从1开始","end_line","结束行，最多200行"));}
    public ToolResult execute(Map<String,String> args){
        if(!args.keySet().equals(definition().getParameters().keySet()))return new ToolResult(false,"read_turn requires message_id, start_line and end_line");
        try{
            int start=Integer.parseInt(args.get("start_line")),end=Integer.parseInt(args.get("end_line"));
            if(start<1||end<start||(long)end-start+1>200)return new ToolResult(false,"Invalid conversation line range");
            var message=history.read(args.get("message_id"));
            if(message==null)return new ToolResult(false,"Unknown message_id in this session");
            String[] lines=message.content().split("\\R",-1);
            if(start>lines.length)return new ToolResult(false,"start_line exceeds message length");
            var content=new StringBuilder();boolean truncated=false;
            for(int i=start-1;i<Math.min(end,lines.length);i++){
                String line=(i+1)+": "+lines[i]+"\n";
                int room=12000-content.length();
                if(line.length()>room){content.append(line,0,room);truncated=true;break;}
                content.append(line);
            }
            var result=new LinkedHashMap<>(history.metadata(message));result.put("startLine",start);result.put("endLine",Math.min(end,lines.length));
            result.put("totalLines",lines.length);result.put("truncated",truncated);result.put("content",content.toString());
            return new ToolResult(true,new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result));
        }catch(NumberFormatException invalid){return new ToolResult(false,"Invalid conversation line range");}
        catch(java.io.IOException failure){throw new IllegalStateException("Cannot encode historical message",failure);}
    }
}
