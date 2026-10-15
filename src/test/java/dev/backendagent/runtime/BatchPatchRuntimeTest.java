package dev.backendagent.runtime;
import dev.backendagent.model.*;import dev.backendagent.tools.*;
import java.nio.file.*;import java.util.*;import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import static org.junit.jupiter.api.Assertions.*;
class BatchPatchRuntimeTest {
 @TempDir Path root;
 private ToolCall read(){return new ToolCall("read","read_file",Map.of("path","Main.java","format","raw","start_line","1","end_line","20"));}
 private ToolCall patch(String id,String old,String text){return new ToolCall(id,"apply_patch",Map.of("path","Main.java","old_text",old,"new_text",text));}
 @Test void ordinaryMultipleCallsBecomeOneDurableWriteWithoutReread()throws Exception{
  Files.writeString(root.resolve("Main.java"),"class Main { int a=1; int b=2; }\n");var w=new Workspace(root,root.resolve("agent-local.properties"));var s=new AgentSession("edit");var count=new AtomicInteger();
  new AgentRuntime(r->switch(count.getAndIncrement()){
   case 0->ModelResponse.callTool(read());case 1->ModelResponse.callTools(List.of(patch("a","a=1","a=10"),patch("b","b=2","b=20")),"change two fields");default->ModelResponse.finish("done");
  },List.of(new ReadFileTool(w),new ApplyPatchTool(w,s)),5).run(s);
  assertEquals(AgentSession.Status.COMPLETED,s.status());assertEquals(2,s.history().size());assertEquals("apply_patch_batch",s.history().getLast().call().name());assertEquals(1,s.workspaceState().getRevision());assertTrue(s.history().getLast().result().successful());assertTrue(Files.readString(root.resolve("Main.java")).contains("a=10; int b=20"));assertTrue(s.events().stream().anyMatch(e->e.type()==AgentSession.EventType.MODEL_RESPONSE_NORMALIZED));
 }
 @Test void overlapFeedbackReachesModelAndWholeGroupIsUnchanged()throws Exception{
  Files.writeString(root.resolve("Main.java"),"abcdef");var w=new Workspace(root,root.resolve("agent-local.properties"));var s=new AgentSession("edit");var count=new AtomicInteger();
  new AgentRuntime(r->switch(count.getAndIncrement()){
   case 0->ModelResponse.callTool(read());case 1->ModelResponse.callTools(List.of(patch("left","bcd","x"),patch("right","de","y")),null);
   default->{var message=r.history().getLast().result().content();assertTrue(message.contains("PATCH_OVERLAP"));assertTrue(message.contains("left"));assertTrue(message.contains("right"));assertTrue(message.contains("全部未写入"));yield ModelResponse.finish("conflict reported");}
  },List.of(new ReadFileTool(w),new ApplyPatchTool(w,s)),4).run(s);
  assertEquals("abcdef",Files.readString(root.resolve("Main.java")));assertEquals(0,s.workspaceState().getRevision());
 }
 @Test void normalizationDoesNotReorderOtherToolsOrPaths(){
  var calls=List.of(patch("a","x","y"),new ToolCall("read","read_file",Map.of("path","Main.java")),patch("b","z","q"));var r=ModelResponse.callTools(calls,null);assertSame(r,PatchBatchNormalizer.normalize(r));
 }
 @Test void oversizedGroupIsRejectedAsAWholeInsteadOfApplyingItsFirstSixteen()throws Exception{
  StringBuilder content=new StringBuilder();var calls=new ArrayList<ToolCall>();for(int i=0;i<17;i++){content.append("field_").append(i).append("=old;\n");calls.add(patch("p"+i,"field_"+i+"=old;","field_"+i+"=new;"));}
  Files.writeString(root.resolve("Main.java"),content.toString());var w=new Workspace(root,root.resolve("agent-local.properties"));var session=new AgentSession("edit");var count=new AtomicInteger();
  new AgentRuntime(r->switch(count.getAndIncrement()){case 0->ModelResponse.callTool(read());case 1->ModelResponse.callTools(calls,null);default->ModelResponse.finish("limit reported");},List.of(new ReadFileTool(w),new ApplyPatchTool(w,session)),4).run(session);
  assertEquals(content.toString(),Files.readString(root.resolve("Main.java")));assertEquals(2,session.history().size());assertFalse(session.history().getLast().result().successful());assertEquals(0,session.workspaceState().getRevision());
 }
 @Test void consumedChildIdCannotBeReusedAfterNormalization()throws Exception{
  Files.writeString(root.resolve("Main.java"),"first last");var w=new Workspace(root,root.resolve("agent-local.properties"));var s=new AgentSession("edit");var count=new AtomicInteger();
  new AgentRuntime(r->switch(count.getAndIncrement()){case 0->ModelResponse.callTool(read());case 1->ModelResponse.callTools(List.of(patch("a","first","new"),patch("b","last","end")),null);default->ModelResponse.callTool(new ToolCall("b","list_files",Map.of("path",".")));},List.of(new ReadFileTool(w),new ApplyPatchTool(w,s),new ListFilesTool(w)),5).run(s);
  assertEquals(AgentSession.Status.FAILED,s.status());assertEquals(2,s.history().size());
 }
}
