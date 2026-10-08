package dev.backendagent.history;

import dev.backendagent.model.ConversationMessage;
import dev.backendagent.runtime.AgentSession;
import java.util.*;

/** Session-bound originals. Restored turns have been checked against their durable event log. */
public final class ConversationHistory {
    private final AgentSession session;
    public ConversationHistory(AgentSession session) { this.session=Objects.requireNonNull(session); }
    public List<ConversationMessage> messages() {
        var result=new ArrayList<ConversationMessage>();
        for(var turn:session.turns()) {
            result.add(ConversationMessage.user(turn));
            if(turn.getAnswer()!=null) result.add(ConversationMessage.answer(turn));
        }
        return List.copyOf(result);
    }
    public ConversationMessage read(String id) {
        return messages().stream().filter(m->m.id().equals(id)).findFirst().orElse(null);
    }
    public Map<String,Object> metadata(ConversationMessage source) {
        int latest=session.turns().size();
        var later=new ArrayList<String>();
        for(var turn:session.turns()) if(turn.getTurnId()>source.turnId()) later.add("turn-"+turn.getTurnId()+"-user");
        return Map.of("messageId",source.id(),"turnId",source.turnId(),"role",source.role(),"latestTurnId",latest,
                "laterTurnsExist",source.turnId()<latest,"laterUserMessageIds",later,
                "validity","HISTORICAL_NOT_PROVEN_CURRENT","usageScope","HISTORICAL_ONLY",
                "notice","原始历史消息，后续用户消息可能修改其中要求；不能仅凭轮次认定全部约束已撤销。历史回答不证明当前代码或测试状态。");
    }
}
