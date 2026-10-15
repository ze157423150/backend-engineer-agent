package dev.backendagent.runtime;

import dev.backendagent.model.*;
import dev.backendagent.tools.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompletionVerifierTest {
    private Tool tests(boolean... outcomes){var i=new AtomicInteger();return new Tool(){
        public String name(){return "run_tests";}
        public ToolResult execute(Map<String,String> args){boolean passed=outcomes[Math.min(i.getAndIncrement(),outcomes.length-1)];
            return new ToolResult(passed,"status="+(passed?"PASSED":"FAILED")+"\ntestExecution=EXECUTED\ntestsExecuted=1\n"
                    +(passed?"":"[ERROR] ExampleTest.amount:12 expected: <180> but was: <1800>\n"));}
    };}
    @Test void rejectsFailedFinishThenAllowsVerifiedCorrection(){
        var s=new AgentSession("repair");var step=new AtomicInteger();var feedback=new ArrayList<ModelRequest>();
        new AgentRuntime(r->{feedback.add(r);return switch(step.getAndIncrement()){
            case 0->ModelResponse.callTool(new ToolCall("t1","run_tests",Map.of()));
            case 1->ModelResponse.finish("all passed");
            case 2->ModelResponse.callTool(new ToolCall("t2","run_tests",Map.of()));
            default->ModelResponse.finish("verified");};},List.of(tests(false,true)),6).run(s);
        assertEquals(AgentSession.Status.COMPLETED,s.status());assertEquals(1,s.completionRejections());
        assertNotNull(feedback.get(2).verificationContext().getCompletionIssue());
        assertEquals("TEST_FAILURE",feedback.get(2).verificationContext().getLatestTest().getKind());
    }
    @Test void stopsAfterTwoCorrectionOpportunities(){
        var s=new AgentSession("repair");var step=new AtomicInteger();
        new AgentRuntime(r->step.getAndIncrement()==0?ModelResponse.callTool(new ToolCall("test","run_tests",Map.of())):
            ModelResponse.finish("done"),List.of(tests(false)),20).run(s);
        assertEquals(AgentSession.Status.FAILED,s.status());assertEquals(3,s.completionRejections());assertEquals(4,s.modelCalls());
    }
    @Test void writesAfterPassingTestRequireAnotherRun(){
        var s=new AgentSession("repair");var step=new AtomicInteger();var snapshots=new ArrayList<ModelRequest>();
        Tool write=new Tool(){public String name(){return "create_file";}public ToolResult execute(Map<String,String>a){return new ToolResult(true,"created");}};
        new AgentRuntime(r->{snapshots.add(r);return switch(step.getAndIncrement()){
            case 0->ModelResponse.callTool(new ToolCall("t1","run_tests",Map.of()));
            case 1->ModelResponse.callTool(new ToolCall("w","create_file",Map.of("path","Main.java","content","class Main {}")));
            case 2->ModelResponse.finish("done");
            case 3->ModelResponse.callTool(new ToolCall("t2","run_tests",Map.of()));
            default->ModelResponse.finish("verified");};},List.of(tests(true),write),8).run(s);
        assertEquals(AgentSession.Status.COMPLETED,s.status());assertEquals(1,s.completionRejections());
        assertTrue(snapshots.get(3).verificationContext().getCompletionIssue().contains("过期"));
    }
    @Test void unknownOrMissingExecutionCannotCountAsVerified(){
        for(String text:List.of("status=PASSED\ntestExecution=UNKNOWN\ntestsExecuted=UNKNOWN","status=NO_TESTS\ntestExecution=NO_TESTS\ntestsExecuted=0","legacy success")){
            var s=new AgentSession("repair");s.remember(new ToolExchange(new ToolCall("t","run_tests",Map.of()),new ToolResult(true,text)));
            assertNotNull(new dev.backendagent.verification.CompletionVerifier().rejection(s));
        }
    }
    @Test void writeWithoutAnyTestCannotFinishAndPendingWorkReachesContext(){
        var session=new AgentSession("更新规则，补充测试并回归，最终回答不超过800字");
        var step=new AtomicInteger();var requests=new ArrayList<ModelRequest>();
        Tool write=new Tool(){public String name(){return "apply_patch";}public ToolResult execute(Map<String,String>a){return new ToolResult(true,"patched");}};
        new AgentRuntime(r->{requests.add(r);return switch(step.getAndIncrement()){
            case 0->ModelResponse.callTool(new ToolCall("w","apply_patch",Map.of("path","Policy.java")));
            case 1->ModelResponse.finish("请确认是否运行测试");
            case 2->ModelResponse.callTool(new ToolCall("t","run_tests",Map.of()));
            default->ModelResponse.finish("verified");};},List.of(write,tests(true)),6).run(session);
        assertEquals(AgentSession.Status.COMPLETED,session.status());assertEquals(1,session.completionRejections());
        assertTrue(requests.get(1).verificationContext().getCompletionIssue().contains("尚未运行测试"));
        assertNull(requests.get(1).verificationContext().getLatestTest());
        assertEquals(1,session.history().stream().filter(e->e.call().name().equals("run_tests")).count());
    }
    @Test void priorTurnPassingTestDoesNotAuthorizeNewTurnWrites(){
        var session=new AgentSession("first");var first=new AtomicInteger();
        new AgentRuntime(r->first.getAndIncrement()==0?ModelResponse.callTool(new ToolCall("old","run_tests",Map.of())):ModelResponse.finish("done"),List.of(tests(true)),3).run(session);
        var second=new AtomicInteger();Tool write=new Tool(){public String name(){return "create_file";}public ToolResult execute(Map<String,String>a){return new ToolResult(true,"created");}};
        new AgentRuntime(r->switch(second.getAndIncrement()){
            case 0->ModelResponse.callTool(new ToolCall("w","create_file",Map.of("path","New.java")));
            case 1->ModelResponse.finish("old tests passed");
            case 2->{assertTrue(r.verificationContext().getCompletionIssue().contains("尚未运行测试"));yield ModelResponse.callTool(new ToolCall("new","run_tests",Map.of()));}
            default->ModelResponse.finish("verified");},List.of(write,tests(true)),8).continueConversation(session,"change policy");
        assertEquals(AgentSession.Status.COMPLETED,session.status());assertEquals(1,session.completionRejections());
    }
    @Test void failedWritesAndInspectionWithTestToolDoNotCreateTestObligation(){
        var session=new AgentSession("inspect");var step=new AtomicInteger();
        Tool write=new Tool(){public String name(){return "create_file";}public ToolResult execute(Map<String,String>a){return new ToolResult(false,"already exists");}};
        new AgentRuntime(r->step.getAndIncrement()==0?ModelResponse.callTool(new ToolCall("w","create_file",Map.of("path","Main.java"))):ModelResponse.finish("no changes"),List.of(write,tests(true)),3).run(session);
        assertEquals(AgentSession.Status.COMPLETED,session.status());assertEquals(0,session.completionRejections());
    }
    @Test void inspectionWithoutTestingCanFinish(){
        var s=new AgentSession("explain code");new AgentRuntime(r->ModelResponse.finish("explanation"),List.of(),1).run(s);
        assertEquals(AgentSession.Status.COMPLETED,s.status());assertEquals(0,s.completionRejections());
    }
    @Test void priorTurnFailureDoesNotRequireTestsForNewInspectionTurn(){
        var s=new AgentSession("inspect");s.remember(new ToolExchange(new ToolCall("t","run_tests",Map.of()),new ToolResult(false,"failure")));
        s.transitionTo(AgentSession.Status.RUNNING);s.finish("prior");s.transitionTo(AgentSession.Status.COMPLETED);s.continueWith("explain the logs only");
        assertNull(new dev.backendagent.verification.CompletionVerifier().rejection(s));assertNull(s.latestVerificationTest());
    }
}
