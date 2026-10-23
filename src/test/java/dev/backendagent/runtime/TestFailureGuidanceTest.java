package dev.backendagent.runtime;

import dev.backendagent.model.*;
import dev.backendagent.tools.Tool;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestFailureGuidanceTest {
    private static final String HINT="请围绕失败断言核对相关输入、算式和期望值";
    private static final String REPEAT="上次修改未解决该断言";
    private ToolResult failed(String method) {
        return new ToolResult(false,"status=FAILED\ntestExecution=EXECUTED\ntestsExecuted=2\n"
                +"[ERROR] ExampleTest."+method+":12 expected: <true> but was: <false>\n");
    }
    private Tool scripted(ToolResult... results) {
        var index=new AtomicInteger();
        return new Tool() {
            public String name(){return "run_tests";}
            public ToolResult execute(Map<String,String> args){return results[index.getAndIncrement()];}
        };
    }
    private ToolCall test(int step){return new ToolCall("test-"+step,"run_tests",Map.of());}
    @Test void failedAssertionsReceiveLocalGuidanceAndOnlyRepeatedMethodsReceiveReminder() {
        var session=new AgentSession("repair");var step=new AtomicInteger();
        var passed=new ToolResult(true,"status=PASSED\ntestExecution=EXECUTED\ntestsExecuted=2\n");
        new AgentRuntime(request->{
            int n=step.getAndIncrement();
            if(n>0){
                var result=request.history().getLast().result();
                assertEquals(n<4,result.content().contains(HINT));
                assertEquals(n==2,result.content().contains(REPEAT));
                if(n<4)assertTrue(result.content().contains("expected: <true> but was: <false>"));
            }
            return n<4?ModelResponse.callTool(test(n)):ModelResponse.finish("done");
        },List.of(scripted(failed("first"),failed("first"),failed("different"),passed)),5).run(session);
        assertEquals(AgentSession.Status.COMPLETED,session.status());
        assertEquals(5,session.modelCalls());
    }
    @Test void compilationAndEnvironmentFailuresDoNotGetAssertionGuidance() {
        for(String diagnostic:List.of("COMPILATION ERROR\nCompilation failure", "status=TIMED_OUT", "status=ERROR")) {
            var session=new AgentSession("repair");var step=new AtomicInteger();
            new AgentRuntime(request->{
                if(step.getAndIncrement()==0)return ModelResponse.callTool(test(0));
                assertFalse(request.history().getLast().result().content().contains(HINT));
                throw new IllegalStateException("Stop after feedback inspection");
            },List.of(scripted(new ToolResult(false,diagnostic))),2).run(session);
            assertEquals(AgentSession.Status.FAILED,session.status());
        }
    }
    @Test void priorTurnFailureDoesNotTriggerRepeatedFailureReminder() {
        var session=new AgentSession("first");var step=new AtomicInteger();
        var passed=new ToolResult(true,"status=PASSED\ntestExecution=EXECUTED\ntestsExecuted=2\n");
        new AgentRuntime(request->{int n=step.getAndIncrement();return n<2?ModelResponse.callTool(test(n)):ModelResponse.finish("done");},
                List.of(scripted(failed("first"),passed)),3).run(session);
        var next=new AtomicInteger();
        new AgentRuntime(request->{
            if(next.getAndIncrement()==0)return ModelResponse.callTool(new ToolCall("new-test","run_tests",Map.of()));
            assertTrue(request.history().getLast().result().content().contains(HINT));
            assertFalse(request.history().getLast().result().content().contains(REPEAT));
            throw new IllegalStateException("Stop after feedback inspection");
        },List.of(scripted(failed("first"))),6).continueConversation(session,"next");
        assertEquals(AgentSession.Status.FAILED,session.status());
    }
}
