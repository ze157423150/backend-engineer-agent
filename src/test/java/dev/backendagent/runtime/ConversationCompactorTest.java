package dev.backendagent.runtime;

import dev.backendagent.model.*;
import dev.backendagent.tools.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConversationCompactorTest {
    @Test void denseDialogueCanExceedFourThousandInputCharacters() throws Exception {
        var session=sixTurns(2400);
        var compactor=new ConversationCompactor(2048);
        var request=compactor.plan(session);
        assertNotNull(request);
        assertEquals(4,request.toIndex());
        assertTrue(request.messages().stream().mapToInt(m->m.content().length()).sum()>4000);
        assertEquals(request.toIndex()*2,request.messages().size());
        assertTrue(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(request.referenceData()).length()
                <=ConversationCompactor.MAX_INPUT_CHARACTERS);
        var note=new SummaryNote(SummaryNote.Kind.PROGRESS,"first user rule","turn-1-user","满100九折",SummarySection.CONSTRAINTS);
        var accepted=compactor.validate(request,List.of(note),session);
        session.installConversationSummary(accepted);
        var view=new ContextAssembler().assemble(session,List.of(),10);
        assertTrue(view.turns().stream().anyMatch(t->t.getTurnId()==5));
        assertTrue(view.turns().stream().anyMatch(t->t.getTurnId()==7));
    }
    @Test void denseDialogueStillHonorsEncodedCapAndKeepsWholePairs() throws Exception {
        String details=java.util.stream.IntStream.range(0,800).mapToObj(i->"字段"+i+";")
                .collect(java.util.stream.Collectors.joining());
        var session=new AgentSession("目标"+details);
        for(int i=1;i<=10;i++) {
            var runtime=new AgentRuntime(r->ModelResponse.finish("结果"+details),List.of(),30);
            if(i==1)runtime.run(session);else runtime.continueConversation(session,"需求"+i+details);
        }
        session.continueWith("当前任务");
        var request=new ConversationCompactor(2048).plan(session);
        assertNotNull(request);
        assertTrue(request.toIndex()<8);
        assertEquals(request.toIndex()*2,request.messages().size());
        assertTrue(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(request.referenceData()).length()
                <=ConversationCompactor.MAX_INPUT_CHARACTERS);
        assertEquals(11,new ContextAssembler().assemble(session,List.of(),10).turns().size());
    }
    @Test void expandedInputCanAdvancePastPreviouslyRejectedSmallPrefix() {
        var session=sixTurns(2400);
        session.append(AgentSession.EventType.CONVERSATION_SUMMARY_ATTEMPTED,"2");
        for(int i=0;i<2;i++) {
            session.finish("next");session.transitionTo(AgentSession.Status.COMPLETED);
            session.continueWith("继续");
        }
        var request=new ConversationCompactor(2048).plan(session);
        assertNotNull(request);
        assertTrue(request.toIndex()>2);
        assertNull(session.conversationSummary());
    }
    @Test void conversationSummaryIsValidatedBeforeFinalToolHistoryTrimming() {
        var session=sixTurns(1000);
        for(int i=0;i<3;i++) session.remember(new ToolExchange(new ToolCall("read-"+i,"read_file",Map.of("path","Main.java")),
                new ToolResult(true,"x".repeat(3000)),null,20+i));
        var original=session.history();
        var shrunkCandidate=new java.util.concurrent.atomic.AtomicBoolean();
        ModelClient model=new ModelClient() {
            public boolean supportsConversationSummarization(){return true;}
            public List<SummaryNote> summarizeConversation(ConversationSummaryRequest request) {
                return List.of(new SummaryNote(SummaryNote.Kind.PROGRESS,"第1轮要求满100九折","turn-1-user","满100九折"));
            }
            public RequestBudgetReport inspectRequest(ModelRequest request) {
                if(request.conversationSummary()!=null && session.conversationSummary()==null && request.history().size()==3)
                    shrunkCandidate.set(true);
                return new RequestBudgetReport(1000+request.historyCharacters()+request.turns().stream().mapToInt(t->t.getUserMessage().length()+(t.getAnswer()==null?0:t.getAnswer().length())).sum(),500,500,9000);
            }
            public ModelResponse execute(ModelRequest request) {
                assertNotNull(request.conversationSummary());
                assertTrue(inspectRequest(request).isWithinBudget());
                assertTrue(request.history().size()<3);
                return ModelResponse.finish("done");
            }
        };
        // sixTurns leaves an active turn ready for the loop.
        session.transitionTo(AgentSession.Status.BUDGET_EXHAUSTED);
        new AgentRuntime(model,List.of(),30,new ContextBudget(20000)).resume(session);
        assertEquals(AgentSession.Status.COMPLETED,session.status());
        assertNotNull(session.conversationSummary());assertTrue(shrunkCandidate.get());
        assertEquals(original,session.history());
    }
    @Test void conversationSummaryThatCannotFitEvenAfterShrinkingIsNotInstalled() {
        var session=sixTurns(1000);
        ModelClient model=new ModelClient() {
            public boolean supportsConversationSummarization(){return true;}
            public List<SummaryNote> summarizeConversation(ConversationSummaryRequest request) {
                return List.of(new SummaryNote(SummaryNote.Kind.PROGRESS,"第1轮要求满100九折","turn-1-user","满100九折"));
            }
            public RequestBudgetReport inspectRequest(ModelRequest request) {
                return new RequestBudgetReport(request.conversationSummary()==null?1000:100000,500,500,9000);
            }
            public ModelResponse execute(ModelRequest request) {
                assertNull(request.conversationSummary());assertEquals(7,request.turns().size());
                return ModelResponse.finish("continued");
            }
        };
        session.transitionTo(AgentSession.Status.BUDGET_EXHAUSTED);
        new AgentRuntime(model,List.of(),30,new ContextBudget(20000)).resume(session);
        assertEquals(AgentSession.Status.COMPLETED,session.status());assertNull(session.conversationSummary());
        assertTrue(session.events().stream().anyMatch(e->e.type()==AgentSession.EventType.CONVERSATION_COMPACTION_FAILED
                &&e.detail().contains("REQUEST_BUDGET_CHECK_FAILED")));
    }
    private AgentSession sixTurns(int answerSize) {
        var session=new AgentSession("满100九折，保留输入校验");
        new AgentRuntime(request->ModelResponse.finish("第一轮说明"+"x".repeat(answerSize)),List.of(),20).run(session);
        for(int i=2;i<=6;i++) {
            final int turn=i;
            new AgentRuntime(request->ModelResponse.finish("第"+turn+"轮说明"+"x".repeat(answerSize)),List.of(),20)
                    .continueConversation(session,i==4?"改成满200八折，保留输入校验":"第"+i+"轮继续说明");
        }
        session.continueWith("回顾最初规则，当前仍为满200八折");
        return session;
    }
    @Test void summarizesOnlyCompletedMiddleTurnsAndKeepsLatestUserRaw() {
        var session=sixTurns(900);var compactor=new ConversationCompactor(2048);var request=compactor.plan(session);
        assertEquals(0,request.fromIndex());assertEquals(4,request.toIndex());assertEquals(8,request.messages().size());
        var notes=List.of(new SummaryNote(SummaryNote.Kind.PROGRESS,"第1轮用户提出满100九折","turn-1-user","满100九折"),
                new SummaryNote(SummaryNote.Kind.PROGRESS,"第4轮用户改为满200八折，并保留输入校验","turn-4-user","改成满200八折，保留输入校验"));
        var summary=compactor.validate(request,notes,session);session.installConversationSummary(summary);
        var view=new ContextAssembler().preview(session,List.of(),10,session.contextSummary());
        assertEquals(List.of(5,6,7),view.turns().stream().map(ConversationTurn::getTurnId).toList());
        assertEquals(2,view.conversationSummary().getNotes().size());
        assertEquals("回顾最初规则，当前仍为满200八折",view.turns().getLast().getUserMessage());
        assertEquals(7,session.turns().size());assertNull(compactor.plan(session));
    }
    @Test void rejectsEditedQuoteAnswerAttributedToUserAndCurrentTurnSource() {
        var session=sixTurns(900);var compactor=new ConversationCompactor(2048);var request=compactor.plan(session);
        for(var note:List.of(new SummaryNote(SummaryNote.Kind.PROGRESS,"bad","turn-1-user","满100...九折"),
                new SummaryNote(SummaryNote.Kind.PROGRESS,"bad","turn-1-user","第一轮说明"),
                new SummaryNote(SummaryNote.Kind.PROGRESS,"bad","turn-7-user","回顾最初规则"))) {
            assertThrows(IllegalArgumentException.class,()->compactor.validate(request,List.of(note),session));
        }
    }
    @Test void shortDialogueSkipsModelAndStillRemainsRawInMiddleWindow() {
        var session=sixTurns(0);assertNull(new ConversationCompactor(2048).plan(session));
        assertTrue(session.events().stream().anyMatch(e->e.type()==AgentSession.EventType.CONVERSATION_SUMMARY_SKIPPED));
        assertEquals(7,new ContextAssembler().assemble(session,List.of(),10).turns().size());
        int events=session.events().size();assertNull(new ConversationCompactor(2048).plan(session));assertEquals(events,session.events().size());
    }
    @Test void historyRetrievalShowsLaterMessagesWithoutDeclaringRequirementsInvalid() throws Exception {
        var session=sixTurns(900);
        var search=new SearchTurnsTool(session).execute(Map.of("keyword","满100","role","user","before_turn","0","limit","5"));
        assertTrue(search.successful());assertTrue(search.content().contains("turn-1-user"));
        var result=new ReadTurnTool(session).execute(Map.of("message_id","turn-1-user","start_line","1","end_line","5"));
        assertTrue(result.successful());
        var data=new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.content());
        assertTrue(data.path("laterTurnsExist").asBoolean());assertEquals(7,data.path("latestTurnId").asInt());
        assertEquals("HISTORICAL_ONLY",data.path("usageScope").asText());
        assertTrue(data.path("content").asText().contains("保留输入校验"));
        assertTrue(data.path("laterUserMessageIds").toString().contains("turn-4-user"));
        assertFalse(new ReadTurnTool(session).execute(Map.of("message_id","another-session","start_line","1","end_line","5")).successful());
    }
    @Test void runtimeRecordsFailureAndUsesRawFallbackInsteadOfInstallingBadSummary() {
        ModelClient model=new ModelClient(){
            public boolean supportsConversationSummarization(){return true;}
            public List<SummaryNote> summarizeConversation(ConversationSummaryRequest request){return List.of(new SummaryNote(SummaryNote.Kind.PROGRESS,"bad","turn-1-user","wrong quote"));}
            public ModelResponse execute(ModelRequest request){assertNull(request.conversationSummary());assertEquals(7,request.turns().size());return ModelResponse.finish("done");}
        };
        var active=new AgentSession("满100九折");new AgentRuntime(r->ModelResponse.finish("x".repeat(1000)),List.of(),30).run(active);
        for(int i=2;i<=6;i++)new AgentRuntime(r->ModelResponse.finish("x".repeat(1000)),List.of(),30).continueConversation(active,"轮"+i);
        new AgentRuntime(model,List.of(),30).continueConversation(active,"current");
        assertNull(active.conversationSummary());assertEquals(AgentSession.Status.COMPLETED,active.status());
        assertTrue(active.events().stream().anyMatch(e->e.type()==AgentSession.EventType.CONVERSATION_COMPACTION_FAILED&&e.detail().contains("QUOTE_NOT_IN_MESSAGE")));
    }
}
