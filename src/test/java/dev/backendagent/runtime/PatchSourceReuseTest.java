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
    private AgentSession created(Workspace w){
        var s=new AgentSession("edit");String content="class Main { int n = 1; }\n";var result=w.createFile("Main.java",content);
        assertTrue(result.successful());assertNotNull(result.fileFingerprint());
        s.remember(new ToolExchange(new ToolCall("c","create_file",Map.of("path","Main.java","content",content)),result));return s;
    }
}
