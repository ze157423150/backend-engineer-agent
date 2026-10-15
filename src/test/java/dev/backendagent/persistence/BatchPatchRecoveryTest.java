package dev.backendagent.persistence;
import dev.backendagent.model.*;import dev.backendagent.runtime.*;import dev.backendagent.tools.*;
import java.nio.file.*;import java.util.*;import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import static org.junit.jupiter.api.Assertions.*;
class BatchPatchRecoveryTest {
 @TempDir Path root;
 @Test void acknowledgedAtomicGroupRestoresAsOneCompletedWriteWithoutReplay()throws Exception{
  Files.writeString(root.resolve("Main.java"),"first last");var data=root.resolve("sessions");var w=new Workspace(root,root.resolve("agent-local.properties"),data);UUID id;
  try(var store=new FileSessionStore(data)){
   store.bindWorkspace(w);var s=new AgentSession("edit",store);store.create(s);id=s.id();var count=new AtomicInteger();
   new AgentRuntime(r->switch(count.getAndIncrement()){
    case 0->ModelResponse.callTool(new ToolCall("r","read_file",Map.of("path","Main.java","format","raw","start_line","1","end_line","20")));
    case 1->ModelResponse.callTools(List.of(new ToolCall("a","apply_patch",Map.of("path","Main.java","old_text","first","new_text","new")),new ToolCall("b","apply_patch",Map.of("path","Main.java","old_text","last","new_text","end"))),null);
    default->throw new IllegalStateException("forced after acknowledgement");
   },List.of(new ReadFileTool(w),new ApplyPatchTool(w,s)),5).run(s);assertEquals(AgentSession.Status.FAILED,s.status());store.saveSnapshot(s);
  }
  try(var store=new FileSessionStore(data)){
   var s=store.restoreFailed(id,w,6);assertEquals(2,s.history().size());assertEquals("apply_patch_batch",s.history().getLast().call().name());assertEquals(1,s.workspaceState().getRevision());
   new AgentRuntime(r->ModelResponse.finish("recovered"),List.of(),6).resumeFailed(s,false);assertEquals(AgentSession.Status.COMPLETED,s.status());assertEquals("new end",Files.readString(root.resolve("Main.java")));assertEquals(2,s.history().size());
  }
 }
}
