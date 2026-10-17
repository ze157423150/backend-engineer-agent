package dev.backendagent.runtime;
import dev.backendagent.model.*;
import dev.backendagent.tools.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class PatchSourceReuseTest {
    @TempDir Path root;
    @Test void numberedDefaultRetainsLiteralLinePrefixesAsPatchSource() throws Exception {
        Files.writeString(root.resolve("note.txt"),"1: literal source text\r\n");
        var workspace=new Workspace(root,root.resolve("agent-local.properties"));
        var result=new ReadFileTool(workspace).execute(Map.of("path","note.txt","start_line","1","end_line","1"));
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var restored=json.readValue(json.writeValueAsString(result),ToolResult.class);
        var session=new AgentSession("edit");
        session.remember(new ToolExchange(new ToolCall("r","read_file",Map.of("path","note.txt")),restored));
        assertTrue(session.hasCurrentPatchSource("note.txt","1: literal source text",workspace));
        Files.writeString(root.resolve("note.txt"),"changed\r\n");
        assertFalse(session.hasCurrentPatchSource("note.txt","1: literal source text",workspace));
    }
    @Test void createAndPatchCanShareOneToolBatch() throws Exception {
        var w=new Workspace(root,root.resolve("agent-local.properties"));var s=new AgentSession("create and edit");var count=new AtomicInteger();
        new AgentRuntime(req->count.getAndIncrement()==0?ModelResponse.callTools(List.of(
                new ToolCall("c","create_file",Map.of("path","Main.java","content","class Main { int n = 1; }\n")),
                new ToolCall("p","apply_patch",Map.of("path","Main.java","old_text","n = 1","new_text","n = 2"))),null):ModelResponse.finish("done"),
                List.of(new CreateFileTool(w,s),new ApplyPatchTool(w,s)),2).run(s);
        assertEquals(AgentSession.Status.COMPLETED,s.status());assertTrue(s.history().getLast().result().successful());
        assertTrue(Files.readString(root.resolve("Main.java")).contains("n = 2"));
    }
    @Test void newlyCreatedVisibleSourceCanBePatchedWithoutReading() throws Exception {
        var w=new Workspace(root,root.resolve("agent-local.properties"));var s=new AgentSession("create and edit");var count=new AtomicInteger();
        new AgentRuntime(req->switch(count.getAndIncrement()){
            case 0->ModelResponse.callTool(new ToolCall("c","create_file",Map.of("path","Main.java","content","class Main { int n = 1; }\n")));
            case 1->ModelResponse.callTool(new ToolCall("p","apply_patch",Map.of("path","Main.java","old_text","n = 1","new_text","n = 2")));
            default->ModelResponse.finish("done");
        },List.of(new CreateFileTool(w,s),new ApplyPatchTool(w,s)),3).run(s);
        assertEquals(AgentSession.Status.COMPLETED,s.status());
        assertTrue(s.history().getLast().result().successful());
        assertTrue(Files.readString(root.resolve("Main.java")).contains("n = 2"));
        assertTrue(s.history().stream().noneMatch(e->e.call().name().equals("read_file")));
    }
    @Test void externalEditsInvalidateCreationFingerprint() throws Exception {
        var w=new Workspace(root,root.resolve("agent-local.properties"));var s=created(w);
        assertTrue(s.hasCurrentPatchSource("Main.java","n = 1",w));
        Files.writeString(root.resolve("Main.java"),"class Main { int n = 99; }\n");
        assertFalse(s.hasCurrentPatchSource("Main.java","n = 1",w));
    }
    @Test void archivedOnlyCreationContentCannotAuthorizePatch() throws Exception {
        var w=new Workspace(root,root.resolve("agent-local.properties"));var s=created(w);
        s.installProjection(new ContextProjection(4,1,1000,1,0,0,"",List.of()));
        assertFalse(s.hasCurrentPatchSource("Main.java","n = 1",w));
    }
    @Test void unreadSourceFragmentMustBeReadAndModifiedFilesNeedFreshFingerprint() throws Exception {
        var w=new Workspace(root,root.resolve("agent-local.properties"));Files.writeString(root.resolve("Main.java"),"first\nsecond\n");var s=new AgentSession("edit");
        var read=w.readFile("Main.java","1","1","raw");s.remember(new ToolExchange(new ToolCall("r","read_file",Map.of("path","Main.java","format","raw")),read));
        assertTrue(s.hasCurrentPatchSource("Main.java","first",w));assertFalse(s.hasCurrentPatchSource("Main.java","second",w));
        assertTrue(w.applyPatch("Main.java","first","changed").successful());
        assertFalse(s.hasCurrentPatchSource("Main.java","first",w));
    }
    @Test void successfulPatchCanBeReusedAfterCompletedTurnAndCheckpointRestore() throws Exception {
        var directory=Files.createDirectory(root.resolve("workspace"));
        Files.writeString(directory.resolve("Main.java"),"class Main { int n = 1; int other = 7; }\n");
        var w=new Workspace(directory,root.resolve("agent-local.properties"));
        UUID id;
        try(var store=new dev.backendagent.persistence.FileSessionStore(root.resolve("sessions"))) {
            store.bindWorkspace(w);var s=new AgentSession("first edit",store);store.create(s);id=s.id();
            var step=new AtomicInteger();
            new AgentRuntime(q->switch(step.getAndIncrement()){
                case 0->ModelResponse.callTool(new ToolCall("r","read_file",Map.of("path","Main.java","start_line","1","end_line","10")));
                case 1->ModelResponse.callTool(new ToolCall("p","apply_patch",Map.of("path","Main.java","old_text","n = 1","new_text","n = 2")));
                default->ModelResponse.finish("first done");
            },List.of(new ReadFileTool(w),new ApplyPatchTool(w,s)),3).run(s);
            assertEquals(AgentSession.Status.COMPLETED,s.status());
            assertEquals(w.fileFingerprint("Main.java"),s.history().getLast().result().fileFingerprint());
        }
        try(var store=new dev.backendagent.persistence.FileSessionStore(root.resolve("sessions"))) {
            var s=store.openCompleted(id,w,6);var step=new AtomicInteger();
            new AgentRuntime(q->step.getAndIncrement()==0
                ?ModelResponse.callTool(new ToolCall("p2","apply_patch",Map.of("path","Main.java","old_text","n = 2","new_text","n = 3")))
                :ModelResponse.finish("second done"),List.of(new ApplyPatchTool(w,s)),6).continueConversation(s,"next edit");
            assertEquals(AgentSession.Status.COMPLETED,s.status());
            assertTrue(s.history().getLast().result().successful());
            assertEquals(1,s.history().stream().filter(e->e.call().name().equals("read_file")).count());
            assertTrue(Files.readString(directory.resolve("Main.java")).contains("n = 3"));
        }
    }
    @Test void batchPatchAllowsOnlyIndividualNewFragmentsAtCurrentFingerprint() throws Exception {
        Files.writeString(root.resolve("Main.java"),"one GAP two\n");
        var w=new Workspace(root,root.resolve("agent-local.properties"));var s=new AgentSession("edit");
        var edits=List.of(new PatchEdit("a","one","FIRST"),new PatchEdit("b","two","SECOND"));
        var result=w.applyPatchBatch("Main.java",w.fileFingerprint("Main.java").getSha256(),edits);
        assertTrue(result.successful());assertEquals(w.fileFingerprint("Main.java"),result.fileFingerprint());
        s.remember(new ToolExchange(new ToolCall("p","apply_patch_batch",Map.of("path","Main.java","patches",ApplyPatchBatchTool.encode(edits))),result));
        assertTrue(s.hasCurrentPatchSource("Main.java","FIRST",w));
        assertTrue(s.hasCurrentPatchSource("Main.java","SECOND",w));
        assertFalse(s.hasCurrentPatchSource("Main.java","FIRSTSECOND",w));
        assertFalse(s.hasCurrentPatchSource("Main.java","GAP",w));
        Files.writeString(root.resolve("Main.java"),"FIRST new GAP SECOND\n");
        assertFalse(s.hasCurrentPatchSource("Main.java","FIRST",w));
    }
    @Test void hiddenOrLegacyPatchWithoutFingerprintCannotAuthorizeAnotherWrite() throws Exception {
        Files.writeString(root.resolve("Main.java"),"old\n");
        var w=new Workspace(root,root.resolve("agent-local.properties"));var s=new AgentSession("edit");
        var call=new ToolCall("p","apply_patch",Map.of("path","Main.java","old_text","old","new_text","changed"));
        var result=w.applyPatch("Main.java","old","changed");
        s.remember(new ToolExchange(call,new ToolResult(true,result.content())));
        assertFalse(s.hasCurrentPatchSource("Main.java","changed",w));
        var current=new AgentSession("visible");current.remember(new ToolExchange(call,result));
        assertTrue(current.hasCurrentPatchSource("Main.java","changed",w));
        current.installProjection(new ContextProjection(4,1,1000,1,0,0,"",List.of()));
        assertFalse(current.hasCurrentPatchSource("Main.java","changed",w));
    }
    @Test void laterWriteInvalidatesPreviousPatchFragmentsEvenWhenTextStillExists() throws Exception {
        Files.writeString(root.resolve("Main.java"),"old stable\n");
        var w=new Workspace(root,root.resolve("agent-local.properties"));var s=new AgentSession("edit");
        var first=w.applyPatch("Main.java","old","changed");
        s.remember(new ToolExchange(new ToolCall("p1","apply_patch",Map.of("path","Main.java","old_text","old","new_text","changed")),first));
        var second=w.applyPatch("Main.java","stable","updated");
        s.remember(new ToolExchange(new ToolCall("p2","apply_patch",Map.of("path","Main.java","old_text","stable","new_text","updated")),second));
        assertFalse(s.hasCurrentPatchSource("Main.java","changed",w));
        assertTrue(s.hasCurrentPatchSource("Main.java","updated",w));
    }
    private AgentSession created(Workspace w){
        var s=new AgentSession("edit");String content="class Main { int n = 1; }\n";var result=w.createFile("Main.java",content);
        assertTrue(result.successful());assertNotNull(result.fileFingerprint());
        s.remember(new ToolExchange(new ToolCall("c","create_file",Map.of("path","Main.java","content",content)),result));return s;
    }
}
