package dev.backendagent.runtime;

import dev.backendagent.model.*;
import java.util.*;

/** Recent dialogue stays raw; middle dialogue is incrementally summarized; oldest remains retrievable. */
public final class ConversationCompactor {
    public static final int RECENT_TURNS=2, MIDDLE_TURNS=8, INTERVAL=4;
    private final int minimumInputCharacters;
    public ConversationCompactor(int minimumInputCharacters) { this.minimumInputCharacters=minimumInputCharacters; }
    public static List<ConversationMessage> messages(ConversationTurn turn) {
        return List.of(ConversationMessage.user(turn), ConversationMessage.answer(turn));
    }
    public static ConversationSummary visibleSummary(ConversationSummary summary, List<ConversationTurn> turns) {
        if (summary==null) return null;
        int recent=Math.max(0, turns.size()-1-RECENT_TURNS), oldest=Math.max(0,recent-MIDDLE_TURNS);
        var ids=new HashSet<String>();
        for (var turn : turns.subList(oldest,recent)) for (var message : messages(turn)) ids.add(message.id());
        var notes=summary.getNotes().stream().filter(n->ids.contains(n.getSourceCallId())).toList();
        return notes.isEmpty()?null:new ConversationSummary(summary.getRevision(),summary.getCoveredTurns(),notes);
    }
    public static List<ConversationTurn> visibleTurns(List<ConversationTurn> turns, ConversationSummary summary,
                                                     int toolStart, int toolEnd) {
        int recent=Math.max(0,turns.size()-1-RECENT_TURNS),oldest=Math.max(0,recent-MIDDLE_TURNS);
        int covered=summary==null?0:summary.getCoveredTurns();
        var selected=new ArrayList<ConversationTurn>();
        for (int i=0;i<turns.size();i++) {
            var turn=turns.get(i);
            boolean ownsVisibleTools=turn.getStartHistoryIndex()<toolEnd && turn.getEndHistoryIndex()>toolStart;
            if (i>=recent || (i>=oldest && i>=covered) || ownsVisibleTools) selected.add(turn);
        }
        return List.copyOf(selected);
    }
    public ConversationSummaryRequest plan(AgentSession session) {
        var turns=session.turns();
        int recent=Math.max(0,turns.size()-1-RECENT_TURNS),oldest=Math.max(0,recent-MIDDLE_TURNS);
        int covered=session.conversationSummary()==null?0:session.conversationSummary().getCoveredTurns();
        int attempt=Math.max(covered,session.lastConversationSummaryAttemptEnd());
        if (recent-attempt<INTERVAL) return null;
        int from=Math.max(covered,oldest);
        var input=new ArrayList<ConversationMessage>();
        int to=from;
        for (;to<recent;to++) {
            var batch=new ArrayList<ConversationMessage>();
            for (var raw : messages(turns.get(to))) {
                String content=raw.content();
                if (content.length()>2400) content=content.substring(0,1600)+"\n[消息节选；完整原文可用read_turn提取]\n"+content.substring(content.length()-700);
                batch.add(new ConversationMessage(raw.id(),raw.turnId(),raw.role(),content));
            }
            var proposed=new ArrayList<>(input);proposed.addAll(batch);
            var candidate=new ConversationSummaryRequest(session.currentTask(),visibleSummary(session.conversationSummary(),turns),from,to+1,4000,proposed);
            try { if (new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(candidate).length()>24000) break; }
            catch (java.io.IOException failure) { throw new IllegalStateException("Cannot encode conversation input",failure); }
            input=proposed;
        }
        if (input.isEmpty() || to<=attempt) return null;
        long characters=input.stream().mapToLong(m->m.content().length()).sum();
        if (characters<minimumInputCharacters) {
            session.append(AgentSession.EventType.CONVERSATION_SUMMARY_SKIPPED,Integer.toString(to));
            session.checkpoint();return null;
        }
        return new ConversationSummaryRequest(session.currentTask(),visibleSummary(session.conversationSummary(),turns),from,to,4000,input);
    }
    public ConversationSummary validate(ConversationSummaryRequest request,List<SummaryNote> notes,AgentSession session) {
        int revision=session.conversationSummary()==null?1:session.conversationSummary().getRevision()+1;
        var candidate=new ConversationSummary(revision,request.toIndex(),notes);
        candidate.validateAgainst(session.turns());
        if(candidate.characterCount()>request.maxOutputCharacters()) throw new IllegalArgumentException("Summary exceeds reserved budget");
        int recent=Math.max(0,session.turns().size()-1-RECENT_TURNS),oldest=Math.max(0,recent-MIDDLE_TURNS);
        var allowed=new HashSet<String>();
        for(var turn:session.turns().subList(oldest,recent)) for(var message:messages(turn)) allowed.add(message.id());
        for(var note:notes) {
            if(!allowed.contains(note.getSourceCallId())) throw new IllegalArgumentException("Summary source is outside the middle window");
            boolean visible=request.messages().stream().anyMatch(m->m.id().equals(note.getSourceCallId())&&m.content().contains(note.getEvidenceQuote()));
            if(!visible && request.previousSummary()!=null) visible=request.previousSummary().getNotes().stream().anyMatch(n->n.getSourceCallId().equals(note.getSourceCallId())&&n.getEvidenceQuote().equals(note.getEvidenceQuote()));
            if(!visible) throw new IllegalArgumentException("Summary quote was not in summary input");
        }
        long before=request.messages().stream().mapToLong(m->m.content().length()).sum()+(request.previousSummary()==null?0:request.previousSummary().characterCount());
        if(candidate.characterCount()>=before) throw new IllegalArgumentException("Summary does not reduce replaced context");
        return candidate;
    }
}
