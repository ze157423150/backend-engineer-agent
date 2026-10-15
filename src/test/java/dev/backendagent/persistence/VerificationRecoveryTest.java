package dev.backendagent.persistence;

import dev.backendagent.model.*;
import dev.backendagent.runtime.*;
import dev.backendagent.tools.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class VerificationRecoveryTest {
    @TempDir Path root;
    private Workspace workspace()throws Exception{return new Workspace(root,root.resolve("private.properties"),root.resolve("sessions"));}
    private Map<String,String> pin(){return Map.of("basis_id","TAX","source_call_id","read","start_line","1","end_line","1");}
    @Test void completedRestoreReplaysRegistrationsAndRechecksCurrentFiles()throws Exception{
        Files.writeString(root.resolve("tax.md"),"Tax=(base*rate+5000)/10000\n");UUID id;
        try(var store=new FileSessionStore(root.resolve("sessions"))){
            var w=workspace();store.bindWorkspace(w);var s=new AgentSession("verify tax",store);store.create(s);id=s.id();var step=new AtomicInteger();
            new AgentRuntime(r->switch(step.getAndIncrement()){
                case 0->ModelResponse.callTool(new ToolCall("read","read_file",Map.of("path","tax.md","start_line","1","end_line","1","format","raw")));
                case 1->ModelResponse.callTool(new ToolCall("pin","pin_verification_basis",pin()));
                default->ModelResponse.finish("registered");},List.of(new ReadFileTool(w),new PinVerificationBasisTool(s,w)),8).run(s);
            assertEquals(AgentSession.Status.COMPLETED,s.status());assertEquals("CURRENT",s.verificationState().validity("TAX"));store.saveSnapshot(s);
        }
        try(var store=new FileSessionStore(root.resolve("sessions"))){
            var w=workspace();var s=store.openCompleted(id,w,8);s.bindVerificationWorkspace(w);s.refreshVerificationBasis();
            assertEquals(1,s.verificationState().bases().size());assertEquals("CURRENT",s.verificationState().validity("TAX"));
            assertEquals("CURRENT",s.verificationState().validity("TAX"));
            Files.writeString(root.resolve("tax.md"),"changed after restore\n");s.refreshVerificationBasis();assertEquals("STALE",s.verificationState().validity("TAX"));
        }
    }
    @Test void restartDoesNotResetFinishCorrectionCount()throws Exception{
        UUID id;Tool tests=new Tool(){public String name(){return "run_tests";}public ToolResult execute(Map<String,String>a){return new ToolResult(false,"status=FAILED\ntestExecution=EXECUTED\ntestsExecuted=1\n");}};
        try(var store=new FileSessionStore(root.resolve("sessions"))){
            store.bindWorkspace(workspace());var s=new AgentSession("repair",store);store.create(s);id=s.id();var step=new AtomicInteger();
            new AgentRuntime(r->step.getAndIncrement()==0?ModelResponse.callTool(new ToolCall("test","run_tests",Map.of())):ModelResponse.finish("done"),List.of(tests),3).run(s);
            assertEquals(AgentSession.Status.BUDGET_EXHAUSTED,s.status());assertEquals(2,s.completionRejections());store.saveSnapshot(s);
        }
        try(var store=new FileSessionStore(root.resolve("sessions"))){
            var s=store.restore(id,workspace(),10);assertEquals(2,s.completionRejections());
            new AgentRuntime(r->{assertEquals(2,r.verificationContext().getCompletionRejections());return ModelResponse.finish("done");},List.of(tests),10).resume(s);
            assertEquals(AgentSession.Status.FAILED,s.status());assertEquals(3,s.completionRejections());assertEquals(4,s.modelCalls());
        }
    }
    @Test void restoringAnUntestedWriteRebuildsTheRequirementFromHistory()throws Exception{
        UUID id;
        Tool tests=new Tool(){public String name(){return "run_tests";}public ToolResult execute(Map<String,String>a){return new ToolResult(true,"testExecution=EXECUTED\ntestsExecuted=1\n");}};
        try(var store=new FileSessionStore(root.resolve("sessions"))){
            var w=workspace();store.bindWorkspace(w);var session=new AgentSession("change policy and test",store);store.create(session);id=session.id();var step=new AtomicInteger();
            new AgentRuntime(r->step.getAndIncrement()==0?ModelResponse.callTool(new ToolCall("write","create_file",Map.of("path","Rule.java","content","class Rule {}"))):ModelResponse.finish("done"),List.of(new CreateFileTool(w,session),tests),2).run(session);
            assertEquals(AgentSession.Status.BUDGET_EXHAUSTED,session.status());assertEquals(1,session.completionRejections());store.saveSnapshot(session);
        }
        try(var store=new FileSessionStore(root.resolve("sessions"))){
            var session=store.restore(id,workspace(),8);var step=new AtomicInteger();
            new AgentRuntime(r->{assertNotNull(r.verificationContext());return switch(step.getAndIncrement()){
                case 0->{assertTrue(r.verificationContext().getCompletionIssue().contains("尚未运行测试"));yield ModelResponse.finish("done");}
                case 1->ModelResponse.callTool(new ToolCall("test","run_tests",Map.of()));
                default->ModelResponse.finish("verified");};},List.of(tests),8).resume(session);
            assertEquals(AgentSession.Status.COMPLETED,session.status());assertEquals(2,session.completionRejections());
        }
    }
    @Test void registrationCacheReplaysArchivedBodies()throws Exception{
        Files.writeString(root.resolve("contract.md"),"Rule "+"\"".repeat(1500)+"\n");UUID id;
        try(var store=new FileSessionStore(root.resolve("sessions"))){
            var w=workspace();store.bindWorkspace(w);var s=new AgentSession("inspect",store);store.create(s);id=s.id();var step=new AtomicInteger();
            new AgentRuntime(r->{int i=step.getAndIncrement();return i==0?ModelResponse.callTool(new ToolCall("read","read_file",Map.of("path","contract.md","start_line","1","end_line","1","format","raw"))):i==1?ModelResponse.callTool(new ToolCall("pin","pin_verification_basis",pin())):i<18?ModelResponse.callTool(new ToolCall("list-"+i,"list_files",Map.of("path","."))):ModelResponse.finish("done");},
                    List.of(new ReadFileTool(w),new PinVerificationBasisTool(s,w),new ListFilesTool(w)),22).run(s);
            // Directory reads eventually stop for no progress; either recoverable terminal state retains registrations.
            assertNotEquals(AgentSession.Status.RUNNING,s.status());assertNotNull(s.history().get(1).archivedBody());store.saveSnapshot(s);
        }
        try(var store=new FileSessionStore(root.resolve("sessions"))){
            var report=store.read(id);var w=workspace();var s=report.path("lastRecordedStatus").asText().equals("COMPLETED")?store.openCompleted(id,w,30):store.restoreFailed(id,w,30);
            assertEquals(1,s.verificationState().bases().size());assertTrue(s.verificationState().bases().getFirst().getContent().contains("\"".repeat(1500)));
        }
    }
}
