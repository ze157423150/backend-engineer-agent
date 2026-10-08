package dev.backendagent.tools;

import dev.backendagent.history.ConversationHistory;
import dev.backendagent.model.ToolResult;
import dev.backendagent.runtime.AgentSession;
import java.util.*;

public final class SearchTurnsTool implements Tool {
    private final ConversationHistory history;
    public SearchTurnsTool(AgentSession session) { history=new ConversationHistory(session); }
    public String name(){return "search_turns";}
    public ToolDefinition definition(){return new ToolDefinition(name(),
            "检索本会话原始用户要求与最终回答，返回有数量限制的消息ID和历史属性。旧要求可能被后续轮次修改，需结合后续消息判断。",
            Map.of("keyword","字面关键词，空字符串表示不筛选","role","user、assistant或空字符串","before_turn","仅检索小于该轮次的记录，0不限","limit","最多返回1..20条"));}
    public ToolResult execute(Map<String,String> args){
        if(!args.keySet().equals(definition().getParameters().keySet()))return new ToolResult(false,"search_turns requires keyword, role, before_turn and limit");
        try {
            int before=Integer.parseInt(args.get("before_turn")),limit=Integer.parseInt(args.get("limit"));
            String role=args.get("role"),keyword=args.get("keyword");
            if(before<0||limit<1||limit>20||!(role.isEmpty()||role.equals("user")||role.equals("assistant")))return new ToolResult(false,"Invalid conversation query");
            var messages=new ArrayList<>(history.messages());Collections.reverse(messages);
            var matches=messages.stream().filter(m->(before==0||m.turnId()<before)&&(role.isEmpty()||role.equals(m.role()))&&m.content().contains(keyword)).toList();
            var rows=new ArrayList<Map<String,Object>>();
            for(var message:matches.stream().limit(limit).toList()) {
                var row=new LinkedHashMap<>(history.metadata(message));
                int hit=keyword.isEmpty()?0:message.content().indexOf(keyword);
                int from=Math.max(0,hit-40),to=Math.min(message.content().length(),hit+120);
                row.put("excerpt",message.content().substring(from,to));rows.add(row);
            }
            return new ToolResult(true,new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("matches",rows,"hasMore",matches.size()>limit,
                    "pagination","before_turn excludes a whole turn; if a page splits user/answer, read the matching turn IDs explicitly")));
        }catch(NumberFormatException invalid){return new ToolResult(false,"Invalid conversation query");}
        catch(java.io.IOException failure){throw new IllegalStateException("Cannot encode conversation search",failure);}
    }
}
