package dev.backendagent.runtime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import dev.backendagent.model.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContextPressureTest {
    private AgentSession fourCompletedTurns() {
        var session=new AgentSession("保持公共接口");
        var runtime=new AgentRuntime(r->ModelResponse.finish("过去结果"+"x".repeat(1300)),List.of(),20);
        runtime.run(session);
        for(int i=2;i<=4;i++)runtime.continueConversation(session,"第"+i+"轮要求");
        return session;
    }
    @Test void pressureSummarizesTwoOlderTurnsBeforeFixedFourTurnInterval() {
        var session=fourCompletedTurns();session.continueWith("第五轮需求");
        var planner=new ConversationCompactor(2048);
        assertNull(planner.plan(session));
        var early=planner.plan(session,true);assertNotNull(early);assertEquals(2,early.toIndex());
        assertTrue(early.messages().stream().allMatch(m->m.turnId()<=2));
    }
    @Test void pressuredDialogueIsSummarizedBeforeTaskAndRecentTurnsRemainRaw() {
        var session=fourCompletedTurns();var summaries=new AtomicInteger();
        ModelClient model=new ModelClient(){
            public boolean supportsConversationSummarization(){return true;}
            public List<SummaryNote> summarizeConversation(ConversationSummaryRequest r){summaries.incrementAndGet();return List.of(new SummaryNote(SummaryNote.Kind.PROGRESS,"保留接口","turn-1-user","保持公共接口",SummarySection.CONSTRAINTS));}
            public RequestBudgetReport inspectRequest(ModelRequest r){return new RequestBudgetReport(r.conversationSummary()==null?10001:9500,500,500,11000);}
            public ModelResponse execute(ModelRequest r){assertNotNull(r.conversationSummary());assertEquals(List.of(3,4,5),r.turns().stream().map(ConversationTurn::getTurnId).toList());return ModelResponse.finish("done");}
        };
        new AgentRuntime(model,List.of(),10).continueConversation(session,"第五轮需求");
        assertEquals(AgentSession.Status.COMPLETED,session.status());assertEquals(1,summaries.get());assertEquals(6,session.modelCalls());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs={8000,8500,9999,10000})
    void withinBudgetDoesNotTriggerEarlyPressureSummary(long input) {
        var session=fourCompletedTurns();
        ModelClient model=new ModelClient(){
            public boolean supportsConversationSummarization(){return true;}
            public List<SummaryNote> summarizeConversation(ConversationSummaryRequest r){fail("within-budget request must not trigger early summarization");return List.of();}
            public RequestBudgetReport inspectRequest(ModelRequest r){return new RequestBudgetReport(input,500,500,11000);}
            public ModelResponse execute(ModelRequest r){assertNull(r.conversationSummary());return ModelResponse.finish("done");}
        };
        new AgentRuntime(model,List.of(),10).continueConversation(session,"第五轮需求");
        assertEquals(AgentSession.Status.COMPLETED,session.status());
        assertEquals(5,session.modelCalls());
        assertTrue(session.events().stream().noneMatch(e->e.type()==AgentSession.EventType.CONTEXT_PRESSURE_STARTED));
    }
    @Test void unchangedFailedPressureSummaryDoesNotRetryEveryToolBatch() {
        var session=fourCompletedTurns();var summaries=new AtomicInteger();var calls=new AtomicInteger();
        ModelClient model=new ModelClient(){
            public boolean supportsConversationSummarization(){return true;}
            public List<SummaryNote> summarizeConversation(ConversationSummaryRequest r){summaries.incrementAndGet();throw new IllegalArgumentException("bad output");}
            public RequestBudgetReport inspectRequest(ModelRequest r){return new RequestBudgetReport(r.contextProjection().getMaxHistoryCharacters()==Integer.MAX_VALUE?10001:9500,500,500,11000);}
            public ModelResponse execute(ModelRequest r){return calls.getAndIncrement()==0?ModelResponse.callTool(new ToolCall("unknown","unknown",Map.of())):ModelResponse.finish("done");}
        };
        new AgentRuntime(model,List.of(),10).continueConversation(session,"第五轮需求");
        assertEquals(1,summaries.get());assertEquals(AgentSession.Status.COMPLETED,session.status());
    }
    @Test void pressureDoesNotSpendLastTaskCall() {
        var session=fourCompletedTurns();
        new AgentRuntime(new ModelClient(){
            public boolean supportsConversationSummarization(){return true;}
            public List<SummaryNote> summarizeConversation(ConversationSummaryRequest r){fail("must reserve last task call");return List.of();}
            public RequestBudgetReport inspectRequest(ModelRequest r){return new RequestBudgetReport(r.contextProjection().getMaxHistoryCharacters()==Integer.MAX_VALUE?10001:9500,500,500,11000);}
            public ModelResponse execute(ModelRequest r){return ModelResponse.finish("done");}
        },List.of(),5).continueConversation(session,"第五轮需求");
        assertEquals(5,session.modelCalls());assertEquals(AgentSession.Status.COMPLETED,session.status());
    }
    @Test void pressureTrimsHistoryWithoutRewritingExistingSummaryAndPersistsIt(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var repo=java.nio.file.Files.createDirectory(root.resolve("repo"));java.nio.file.Files.writeString(repo.resolve("Main.java"),"FACT\n");
        try(var store=new dev.backendagent.persistence.FileSessionStore(root.resolve("sessions"))) {
            var workspace=new dev.backendagent.tools.Workspace(repo,root.resolve("local.properties"));store.bindWorkspace(workspace);
            var session=new AgentSession("inspect",store);store.create(session);
            for(int i=0;i<13;i++) {
                var exchange=new ToolExchange(new ToolCall("read-"+i,"read_file",Map.of("path","Main.java")),workspace.readFile("Main.java","1","1","raw"),null,i+1);
                session.remember(exchange);session.append(AgentSession.EventType.TOOL_EXECUTION_SUCCEEDED,"fixture read",session.history().getLast());
            }
            var old=new ContextSummary(1,1,List.of(new SummaryNote(SummaryNote.Kind.PROGRESS,"说明".repeat(240),"read-0","FACT"),new SummaryNote(SummaryNote.Kind.PROGRESS,"补充".repeat(240),"read-0","FACT")));
            session.installSummary(old);var taskCalls=new AtomicInteger();
            new AgentRuntime(new ModelClient(){
                public boolean supportsSummaryRefinement(){return true;}
                public List<SummaryNote> refineSummary(SummaryRefinementRequest r){fail("Existing summary must not be refined under request pressure");return List.of();}
                public RequestBudgetReport inspectRequest(ModelRequest r){return new RequestBudgetReport(r.contextProjection().getMaxHistoryCharacters()==Integer.MAX_VALUE?10001:9500,500,500,11000);}
                public ModelResponse execute(ModelRequest r){assertNotNull(r.contextSummary());assertEquals(old.contextData(),r.contextSummary().contextData());assertTrue(r.omittedExchanges()>0);taskCalls.incrementAndGet();return ModelResponse.finish("done");}
            },List.of(),5).run(session);
            assertEquals(AgentSession.Status.COMPLETED,session.status(),session.events().getLast().detail());
            assertEquals(1,taskCalls.get());assertEquals(1,session.modelCalls());assertSame(old,session.contextSummary());
            assertTrue(session.events().stream().anyMatch(e->e.type()==AgentSession.EventType.CONTEXT_PRESSURE_STARTED));
            assertTrue(session.events().stream().noneMatch(e->e.type()==AgentSession.EventType.SUMMARY_REFINEMENT_STARTED));
            var json=new com.fasterxml.jackson.databind.ObjectMapper();
            var saved=json.readTree(java.nio.file.Files.readString(store.sessionDirectory(session.id()).resolve("checkpoint.json")));
            assertEquals("COMPLETED",saved.path("status").asText());
            assertEquals(json.readTree(old.referenceJson()),saved.path("contextSummary"));
        }
    }
    @Test void protectedContextTooLargeFailsWithoutRefiningOrSpendingTaskCalls() {
        var session=new AgentSession("inspect");
        session.remember(new ToolExchange(new ToolCall("read-0","read_file",Map.of("path","Main.java")),new ToolResult(true,"FACT"),null,1));
        var old=new ContextSummary(1,1,List.of(new SummaryNote(SummaryNote.Kind.PROGRESS,"说明".repeat(250),"read-0","FACT"),new SummaryNote(SummaryNote.Kind.PROGRESS,"补充".repeat(250),"read-0","FACT")));
        session.installSummary(old);
        new AgentRuntime(new ModelClient(){
            public boolean supportsSummaryRefinement(){return true;}
            public List<SummaryNote> refineSummary(SummaryRefinementRequest r){fail("No rewrite of an installed summary");return List.of();}
            public RequestBudgetReport inspectRequest(ModelRequest r){return new RequestBudgetReport(10001,500,500,11000);}
            public ModelResponse execute(ModelRequest r){fail("Cannot send an oversized request");return null;}
        },List.of(),5).run(session);
        assertEquals(AgentSession.Status.FAILED,session.status());assertSame(old,session.contextSummary());assertEquals(0,session.modelCalls());
        assertTrue(session.events().stream().noneMatch(e->e.type()==AgentSession.EventType.SUMMARY_REFINEMENT_STARTED));
    }
    @Test void laterExecutedTestsRetireOnlyPendingTestIssueNotCodeFacts() {
        var source=new ToolExchange(new ToolCall("write","apply_patch",Map.of("path","A.java")),new ToolResult(true,"changed"),null,1);
        var test=new ToolExchange(new ToolCall("test","run_tests",Map.of()),new ToolResult(false,"testExecution=EXECUTED\ntestsExecuted=2"),null,2);
        var issue=new SummaryNote(SummaryNote.Kind.OPEN_ISSUE,"补丁已写入但尚未运行测试","write","changed",SummarySection.OPEN_ISSUES);
        var fact=new SummaryNote(SummaryNote.Kind.PROGRESS,"补丁已写入但尚未运行测试","write","changed",SummarySection.CHANGES_AND_TESTS);
        assertTrue(SummarySourcePolicy.resolvedExecutionIssue(issue,List.of(source,test)));
        assertFalse(SummarySourcePolicy.resolvedExecutionIssue(fact,List.of(source,test)));
        assertFalse(SummarySourcePolicy.resolvedExecutionIssue(issue,List.of(source)));
    }
}
