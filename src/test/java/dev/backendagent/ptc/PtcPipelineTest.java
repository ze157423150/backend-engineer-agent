package dev.backendagent.ptc;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.backendagent.model.*;
import dev.backendagent.persistence.*;
import dev.backendagent.runtime.*;
import dev.backendagent.tools.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PtcPipelineTest {
    @TempDir Path root;
    final ObjectMapper json = new ObjectMapper();
    Tool testTool(boolean success) {
        return new Tool() {
            public String name() { return "run_tests"; }
            public ToolDefinition definition() { return new ToolDefinition(name(),"test",Map.of()); }
            public ToolResult execute(Map<String,String> args) { return new ToolResult(success,
                "status="+(success?"PASSED":"FAILED")+"\nexitCode="+(success?0:1)
                +"\nmavenSucceeded="+success+"\ntestExecution=EXECUTED\ntestsExecuted=1\n"
                +(success?"":"[ERROR] example.CaseTest.value:12 expected: <5> but was: <3>\n")); }
        };
    }
    ToolCall program(String id) { return new ToolCall(id,"run_code",Map.of("code","return 1","description","pipeline")); }

    @Test void readsWritesTestsAndCompletedRestoreUseTheNativeStatePipeline() throws Exception {
        var source=Files.createDirectory(root.resolve("repo"));Files.writeString(source.resolve("A.java"),"class A { int n = 1; }\n");
        var workspace=new Workspace(source,root.resolve("config.properties"));var data=root.resolve("sessions");UUID id;
        try(var store=new FileSessionStore(data)) {
            var session=new AgentSession("repair",store);id=session.id();store.create(session);store.bindWorkspace(workspace);
            var step=new AtomicInteger();
            PtcExecutor executor=(code,names,dispatch)->{
                assertTrue(names.containsAll(List.of("read_file","apply_patch","apply_patch_batch","create_file","run_tests")));
                var read=dispatch.call("read_file",Map.of("path","A.java","start_line","1","end_line","20"));
                assertEquals("class A { int n = 1; }\n",read.path("content").asText());
                assertTrue(read.has("call_id"));
                dispatch.call("apply_patch",Map.of("path","A.java","old_text","int n = 1;","new_text","int n = 2;"));
                assertEquals(1,session.workspaceState().getRevision());
                assertTrue(session.staleEvidenceIds().contains(read.path("call_id").asText()));
                dispatch.call("create_file",Map.of("path","B.java","content","class B {}\n"));
                dispatch.call("run_tests",Map.of());
                assertEquals(WorkspaceState.TestStatus.CURRENT_PASSED,session.workspaceState().testStatus());
                return new PtcRunResult(json.createObjectNode().put("done",true),List.of(),null);
            };
            new AgentRuntime(q->{assertEquals(List.of("run_code"),q.availableTools().stream().map(ToolDefinition::getName).toList());
                return step.getAndIncrement()==0?ModelResponse.callTool(program("p")):ModelResponse.finish("done");},
                List.of(new ReadFileTool(workspace),new ApplyPatchTool(workspace,session),new CreateFileTool(workspace,session),testTool(true)),4).withPtc(executor).run(session);
            assertEquals(AgentSession.Status.COMPLETED,session.status());assertEquals(2,session.workspaceState().getRevision());
            assertEquals(4,session.history().stream().filter(ToolExchange::isProgramChild).count());
            assertEquals(0,new ContextBudget(64000).measure(session.history().getFirst()));
            store.saveSnapshot(session);
        }
        try(var store=new FileSessionStore(data)) {
            var restored=store.openCompleted(id,workspace,20);
            assertEquals(2,restored.workspaceState().getRevision());
            assertEquals(WorkspaceState.TestStatus.CURRENT_PASSED,restored.workspaceState().testStatus());
            assertEquals(5,restored.history().size());
        }
    }

    @Test void interruptedChildLedgerCanBeOpenedButCannotReplayWholeProgram() throws Exception {
        var source=Files.createDirectory(root.resolve("repo"));var workspace=new Workspace(source,root.resolve("config"));
        var data=root.resolve("sessions");UUID id;
        try(var store=new FileSessionStore(data)) {
            var session=new AgentSession("calculate",store);id=session.id();store.create(session);store.bindWorkspace(workspace);
            new AgentRuntime(q->ModelResponse.callTool(program("p")),List.of(new IntegerCalculateTool()),5)
                .withPtc((c,n,d)->{d.call("integer_calculate",Map.of("expression","1+1","result_name","sum"));
                    throw new IllegalStateException("worker disconnected");}).run(session);
            assertEquals(AgentSession.Status.FAILED,session.status());
        }
        try(var store=new FileSessionStore(data)) {
            var restored=store.restoreFailed(id,workspace,10,true);
            assertEquals(1,restored.history().size());assertTrue(restored.history().getFirst().isProgramChild());
            assertThrows(IllegalStateException.class,()->new AgentRuntime(q->ModelResponse.finish("unused"),List.of(),10)
                    .resumeFailed(restored,true));
        }
    }

    @Test void directNativeCallIsRejectedWithoutExecutingItsBody() {
        var actual=new AtomicInteger();var step=new AtomicInteger();var session=new AgentSession("inspect");
        Tool tool=new Tool(){public String name(){return "list_files";}public ToolResult execute(Map<String,String>a){actual.incrementAndGet();return new ToolResult(true,"files");}};
        new AgentRuntime(q->step.getAndIncrement()==0?ModelResponse.callTool(new ToolCall("bad","list_files",Map.of())):ModelResponse.finish("done"),
            List.of(tool),3).withPtc((c,n,d)->{throw new AssertionError();}).run(session);
        assertEquals(0,actual.get());assertTrue(session.history().getFirst().result().content().contains("PTC_ONLY"));
    }

    @Test void programFailureKeepsAppliedWritesAndDoesNotReplayThem() throws Exception {
        var source=Files.createDirectory(root.resolve("repo"));var workspace=new Workspace(source,root.resolve("config"));
        var session=new AgentSession("create");var step=new AtomicInteger();var runs=new AtomicInteger();
        new AgentRuntime(q->step.getAndIncrement()==0?ModelResponse.callTool(program("p")):ModelResponse.finish("done"),
            List.of(new CreateFileTool(workspace,session)),3).withPtc((c,n,d)->{
                runs.incrementAndGet();d.call("create_file",Map.of("path","A.java","content","class A {}"));
                return new PtcRunResult(null,List.of(),"Syntax after side effect");
            }).run(session);
        assertEquals(1,runs.get());assertEquals(1,session.workspaceState().getRevision());
        assertFalse(session.history().getLast().result().successful());assertTrue(Files.exists(source.resolve("A.java")));
    }

    @Test void caughtTestFailureCannotBypassCompletionVerification() {
        var session=new AgentSession("test");var step=new AtomicInteger();
        new AgentRuntime(q->step.getAndIncrement()==0?ModelResponse.callTool(program("p")):ModelResponse.finish("claimed passed"),
            List.of(testTool(false)),5).withPtc((c,n,d)->{
                assertThrows(IllegalArgumentException.class,()->d.call("run_tests",Map.of()));
                return new PtcRunResult(json.createObjectNode().put("claimedPassed",true),List.of(),null);
            }).run(session);
        assertTrue(session.history().getLast().result().content().contains("expected: <5> but was: <3>"));
        assertEquals(WorkspaceState.TestStatus.CURRENT_NOT_PASSED,session.workspaceState().testStatus());
        assertEquals(AgentSession.Status.FAILED,session.status());
        assertTrue(session.events().stream().anyMatch(e->e.type()==AgentSession.EventType.FINISH_REJECTED));
    }

    @Test void interruptedProgramIsNotReplayedEvenAfterReadOnlyChild() throws Exception {
        var source=Files.createDirectory(root.resolve("repo"));var workspace=new Workspace(source,root.resolve("config"));
        var session=new AgentSession("calculate");
        var runtime=new AgentRuntime(q->ModelResponse.callTool(program("p")),List.of(new IntegerCalculateTool()),5)
            .withPtc((c,n,d)->{d.call("integer_calculate",Map.of("expression","1+1","result_name","sum"));throw new IllegalStateException("worker disconnected");});
        runtime.run(session);assertEquals(AgentSession.Status.FAILED,session.status());
        assertThrows(IllegalStateException.class,()->runtime.resumeFailed(session,true));
    }
}
