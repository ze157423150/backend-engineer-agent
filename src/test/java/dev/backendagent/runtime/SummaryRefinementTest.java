package dev.backendagent.runtime;

import dev.backendagent.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SummaryRefinementTest {
    private AgentSession sixCompletedTurns() {
        var session=new AgentSession("规则A，不修改公共接口");
        var runtime=new AgentRuntime(r->ModelResponse.finish("历史结果"+"x".repeat(1000)),List.of(),40);
        runtime.run(session);
        for(int i=2;i<=6;i++)runtime.continueConversation(session,"轮次"+i);
        return session;
    }
    private List<SummaryNote> oversized(String source,String quote) {
        var notes=new ArrayList<SummaryNote>();
        for(int i=0;i<9;i++)notes.add(new SummaryNote(SummaryNote.Kind.PROGRESS,"事实"+i+"x".repeat(490),source,quote,SummarySection.CONSTRAINTS));
        return notes;
    }
    private SummaryNote shortNote(String source,String quote) {
        return new SummaryNote(SummaryNote.Kind.PROGRESS,"保留约束与修改结果",source,quote,SummarySection.CONSTRAINTS);
    }
    private class Model implements ModelClient {
        int refinements;
        List<SummaryNote> generated=oversized("turn-1-user","规则A");
        List<SummaryNote> shortened=List.of(shortNote("turn-1-user","规则A"));
        boolean expectInstalled=true;
        public boolean supportsConversationSummarization(){return true;}
        public boolean supportsSummaryRefinement(){return true;}
        public List<SummaryNote> summarizeConversation(ConversationSummaryRequest request){return generated;}
        public List<SummaryNote> refineSummary(SummaryRefinementRequest request){
            refinements++;
            assertTrue(request.getCurrentCharacters()>request.getMaxCharacters());
            assertEquals(4000,request.getMaxCharacters());
            assertEquals(3600,request.getTargetCharacters());
            assertEquals(1,request.getEvidence().size());
            assertFalse(request.referenceData().has("objective"));
            return shortened;
        }
        public ModelResponse execute(ModelRequest request){
            assertEquals(expectInstalled,request.conversationSummary()!=null);
            return ModelResponse.finish("done");
        }
    }
    @Test void oversizedDialogueGetsOneRefinementAndCountsBothCalls() {
        var s=sixCompletedTurns();var model=new Model();
        new AgentRuntime(model,List.of(),40).continueConversation(s,"当前任务");
        assertEquals(1,model.refinements);assertEquals(9,s.modelCalls());
        assertNotNull(s.conversationSummary());assertEquals(AgentSession.Status.COMPLETED,s.status());
        assertTrue(s.events().stream().anyMatch(e->e.type()==AgentSession.EventType.SUMMARY_REFINEMENT_COMPLETED));
    }
    @Test void secondOversizeFallsBackWithoutAThirdSummaryCall() {
        var s=sixCompletedTurns();var model=new Model();model.shortened=model.generated;model.expectInstalled=false;
        new AgentRuntime(model,List.of(),40).continueConversation(s,"当前任务");
        assertEquals(1,model.refinements);assertNull(s.conversationSummary());assertEquals(9,s.modelCalls());
        assertEquals(AgentSession.Status.COMPLETED,s.status());
        assertTrue(s.events().stream().anyMatch(e->e.type()==AgentSession.EventType.CONVERSATION_COMPACTION_FAILED));
    }
    @Test void refinementCannotSwitchToAnotherSourceEvenIfOriginalInputContainsIt() {
        var s=sixCompletedTurns();var model=new Model();
        model.shortened=List.of(shortNote("turn-2-user","轮次2"));model.expectInstalled=false;
        new AgentRuntime(model,List.of(),40).continueConversation(s,"当前任务");
        assertEquals(1,model.refinements);assertNull(s.conversationSummary());assertEquals(AgentSession.Status.COMPLETED,s.status());
    }
    @Test void invalidOriginalQuoteDoesNotSpendARefinementCall() {
        var s=sixCompletedTurns();var model=new Model();
        model.generated=oversized("turn-1-user","不存在的引句");model.expectInstalled=false;
        new AgentRuntime(model,List.of(),40).continueConversation(s,"当前任务");
        assertEquals(0,model.refinements);assertNull(s.conversationSummary());assertEquals(AgentSession.Status.COMPLETED,s.status());
    }
    @Test void insufficientCallBudgetReservesTheTaskCallAndSkipsRefinement() {
        var s=sixCompletedTurns();var model=new Model();model.expectInstalled=false;
        new AgentRuntime(model,List.of(),8).continueConversation(s,"当前任务");
        assertEquals(0,model.refinements);assertEquals(8,s.modelCalls());assertNull(s.conversationSummary());
        assertEquals(AgentSession.Status.COMPLETED,s.status());
    }
    @Test void toolSummaryUsesTheSameOneShotRefinementAndKeepsItsSources() {
        var s=new AgentSession("inspect");
        for(int i=0;i<8;i++)s.remember(new ToolExchange(new ToolCall("read-"+i,"read_file",Map.of("path","Main.java")),
                new ToolResult(true,"historical evidence\n"+"x".repeat(3000)),null,i+1));
        var count=new java.util.concurrent.atomic.AtomicInteger();
        ModelClient model=new ModelClient(){
            public boolean supportsSummarization(){return true;}
            public boolean supportsSummaryRefinement(){return true;}
            public List<SummaryNote> summarize(SummaryRequest r){return oversized("read-0","historical evidence");}
            public List<SummaryNote> refineSummary(SummaryRefinementRequest r){count.incrementAndGet();return List.of(shortNote("read-0","historical evidence"));}
            public ModelResponse execute(ModelRequest r){assertNotNull(r.contextSummary());return ModelResponse.finish("done");}
        };
        new AgentRuntime(model,List.of(),10,new ContextBudget(20000)).run(s);
        assertEquals(1,count.get());assertEquals(3,s.modelCalls());assertNotNull(s.contextSummary());
    }
}
