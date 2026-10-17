package dev.backendagent.runtime;
import dev.backendagent.model.*;
import dev.backendagent.tools.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class VerificationAblationTest {
 @TempDir Path root;
 @Test void disablingBasisSkipsMaintenanceButStillRejectsFinishingWithFailedTests() throws Exception {
  var workspace=new Workspace(root,root.resolve("local.properties"));var session=new AgentSession("create code and validate");var step=new AtomicInteger();
  ModelClient model=new ModelClient(){
   public boolean supportsVerificationUpdates(){return true;}
   public java.util.List<dev.backendagent.verification.ActiveVerificationBasis> updateVerificationBasis(VerificationUpdateRequest r){fail("Ablation must not call maintenance");return List.of();}
   public ModelResponse execute(ModelRequest r){
    assertNull(r.verificationContext());assertTrue(r.availableTools().stream().noneMatch(t->t.getName().equals("pin_verification_basis")));
    int n=step.getAndIncrement();
    if(n==0)return ModelResponse.callTool(new ToolCall("create","create_file",Map.of("path","Main.java","content","class Main {}\n")));
    if(n==1)return ModelResponse.callTool(new ToolCall("test","run_tests",Map.of()));
    return ModelResponse.finish("done");
   }
  };
  Tool test=new Tool(){public String name(){return "run_tests";}public ToolDefinition definition(){return new ToolDefinition(name(),"test",Map.of());}public ToolResult execute(Map<String,String> a){return new ToolResult(false,"status=FAILED\ntestExecution=EXECUTED\ntestsExecuted=1\nexitCode=1");}};
  new AgentRuntime(model,List.of(new PinVerificationBasisTool(session,workspace),new CreateFileTool(workspace,session),test),8,new ContextBudget(64000),s->{},false).run(session);
  assertEquals(AgentSession.Status.FAILED,session.status());assertEquals(3,session.completionRejections());
  assertTrue(session.events().stream().noneMatch(e->e.type()==AgentSession.EventType.VERIFICATION_UPDATE_STARTED));
 }
}
