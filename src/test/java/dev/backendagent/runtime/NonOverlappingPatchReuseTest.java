package dev.backendagent.runtime;

import dev.backendagent.model.*;
import dev.backendagent.tools.*;
import dev.backendagent.persistence.FileSessionStore;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NonOverlappingPatchReuseTest {
    @TempDir Path root;
    private static final String BEFORE="// count old\nassertEquals(4, actual);\n// other old\n";
    private ToolCall read() { return new ToolCall("read","read_file",Map.of("path","Example.java","start_line","1","end_line","10")); }
    private ToolCall patch(String id,String old,String replacement) {
        return new ToolCall(id,"apply_patch",Map.of("path","Example.java","old_text",old,"new_text",replacement));
    }
    @Test void separateCommentAndAssertionCallsReuseNumberedReadWithoutRereading() throws Exception {
        Files.writeString(root.resolve("Example.java"),BEFORE);var w=new Workspace(root,root.resolve("private.properties"));
        var s=new AgentSession("fix count");var step=new AtomicInteger();
        new AgentRuntime(q->switch(step.getAndIncrement()) {
            case 0->ModelResponse.callTool(read());
            case 1->ModelResponse.callTool(patch("comment","// count old","// count corrected"));
            case 2->ModelResponse.callTool(patch("assertion","assertEquals(4, actual);","assertEquals(3, actual);"));
            default->ModelResponse.finish("done");
        },List.of(new ReadFileTool(w),new ApplyPatchTool(w,s)),4).run(s);
        assertEquals(AgentSession.Status.COMPLETED,s.status());
        assertTrue(s.history().stream().allMatch(e->e.result().successful()));
        assertEquals(1,s.history().stream().filter(e->e.call().name().equals("read_file")).count());
        assertEquals(BEFORE.replace("// count old","// count corrected").replace("assertEquals(4, actual);","assertEquals(3, actual);"),Files.readString(root.resolve("Example.java")));
        assertEquals(ObservationEvidence.Validity.STALE,s.assessFileEvidence("read",w));
        assertEquals(s.history().getFirst().result().fileFingerprint(),s.history().get(1).result().patchBaseFingerprint());
    }
    @Test void multipleGuardedBatchesCanBridgeAnOldVisibleCreation() throws Exception {
        var w=new Workspace(root,root.resolve("private.properties"));var s=new AgentSession("edit");var step=new AtomicInteger();
        new AgentRuntime(q->switch(step.getAndIncrement()) {
            case 0->ModelResponse.callTool(new ToolCall("create","create_file",Map.of("path","Example.java","content",BEFORE)));
            case 1->ModelResponse.callTool(new ToolCall("comments","apply_patch_batch",Map.of("path","Example.java","patches",ApplyPatchBatchTool.encode(List.of(
                    new PatchEdit("a","// count old","// count corrected"),new PatchEdit("b","// other old","// other corrected"))))));
            case 2->ModelResponse.callTool(patch("assertion","assertEquals(4, actual);","assertEquals(3, actual);"));
            default->ModelResponse.finish("done");
        },List.of(new CreateFileTool(w,s),new ApplyPatchTool(w,s)),4).run(s);
        assertTrue(s.history().stream().allMatch(e->e.result().successful()));
        assertEquals(0,s.history().stream().filter(e->e.call().name().equals("read_file")).count());
        assertTrue(Files.readString(root.resolve("Example.java")).contains("assertEquals(3, actual)"));
    }
    @Test void externalChangeAfterManagedCommentStillRejectsOldAssertionSource() throws Exception {
        Files.writeString(root.resolve("Example.java"),BEFORE);var w=new Workspace(root,root.resolve("private.properties"));var s=new AgentSession("edit");var step=new AtomicInteger();
        new AgentRuntime(q->switch(step.getAndIncrement()) {
            case 0->ModelResponse.callTool(read());
            case 1->ModelResponse.callTool(patch("comment","// count old","// count corrected"));
            case 2->{
                try {Files.writeString(root.resolve("Example.java"),Files.readString(root.resolve("Example.java"))+"external\n");}
                catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
                yield ModelResponse.callTool(patch("assertion","assertEquals(4, actual);","assertEquals(3, actual);"));
            }
            default->ModelResponse.finish("blocked");
        },List.of(new ReadFileTool(w),new ApplyPatchTool(w,s)),4).run(s);
        assertFalse(s.history().getLast().result().successful());
        assertTrue(Files.readString(root.resolve("Example.java")).contains("assertEquals(4, actual);"));
    }
    @Test void hiddenSourceAndLegacyWritesDoNotAcquireAuthorityFromCurrentTextAlone() throws Exception {
        Files.writeString(root.resolve("Example.java"),BEFORE);var w=new Workspace(root,root.resolve("private.properties"));var s=new AgentSession("edit");
        var source=new ToolExchange(read(),w.readFile("Example.java","1","10"));s.remember(source);
        var result=w.applyPatch("Example.java","// count old","// count corrected");
        s.remember(new ToolExchange(patch("legacy","// count old","// count corrected"),result));
        assertFalse(s.hasCurrentPatchSource("Example.java","assertEquals(4, actual);",w));
        s.installProjection(new ContextProjection(7,s.history().size(),1000,s.history().size(),0,0,"",List.of()));
        assertFalse(s.hasCurrentPatchSource("Example.java","assertEquals(4, actual);",w));
    }
    @Test void overlapProofRejectsContainmentAndCrossBoundaryOverlap() {
        assertTrue(PatchSourceRebaser.possibleOverlap("abcdef","cde"));
        assertTrue(PatchSourceRebaser.possibleOverlap("abcdef","defXYZ"));
        assertTrue(PatchSourceRebaser.possibleOverlap("abcdef","XYZabc"));
        assertFalse(PatchSourceRebaser.possibleOverlap("assertEquals(4, actual);","// count old"));
    }
    @Test void externallyChangedBaselineBeforeManagedWriteCannotBridgeTheGap() throws Exception {
        Files.writeString(root.resolve("Example.java"),BEFORE);var w=new Workspace(root,root.resolve("private.properties"));var s=new AgentSession("edit");
        var read=w.readFile("Example.java","1","10");var source=new ToolExchange(read(),read);s.remember(source);
        Files.writeString(root.resolve("Example.java"),BEFORE+"external\n");
        var external=w.fileFingerprint("Example.java");var result=w.applyPatchBatch("Example.java",external.getSha256(),List.of(new PatchEdit("a","// count old","// count corrected"))).withPatchBase(external);
        s.remember(new ToolExchange(patch("managed","// count old","// count corrected"),result));
        assertFalse(PatchSourceRebaser.canReuse(source,"assertEquals(4, actual);",w.fileFingerprint("Example.java"),s.history()));
    }
    @Test void checkpointRestoreRetainsPatchProofAndAllowsIndependentNextTurnEdit() throws Exception {
        var directory=Files.createDirectory(root.resolve("workspace"));Files.writeString(directory.resolve("Example.java"),BEFORE);
        var w=new Workspace(directory,root.resolve("private.properties"));UUID id;
        try(var store=new FileSessionStore(root.resolve("sessions"))) {
            store.bindWorkspace(w);var s=new AgentSession("comment",store);store.create(s);id=s.id();var step=new AtomicInteger();
            new AgentRuntime(q->switch(step.getAndIncrement()) {case 0->ModelResponse.callTool(read());case 1->ModelResponse.callTool(patch("comment","// count old","// count corrected"));default->ModelResponse.finish("done");},List.of(new ReadFileTool(w),new ApplyPatchTool(w,s)),3).run(s);
            assertEquals(AgentSession.Status.COMPLETED,s.status());
        }
        try(var store=new FileSessionStore(root.resolve("sessions"))) {
            var s=store.openCompleted(id,w,5);assertNotNull(s.history().getLast().result().patchBaseFingerprint());var step=new AtomicInteger();
            new AgentRuntime(q->step.getAndIncrement()==0?ModelResponse.callTool(patch("assertion","assertEquals(4, actual);","assertEquals(3, actual);")):ModelResponse.finish("done"),List.of(new ApplyPatchTool(w,s)),5).continueConversation(s,"fix assertion");
            assertTrue(s.history().getLast().result().successful());assertEquals(1,s.history().stream().filter(e->e.call().name().equals("read_file")).count());
        }
    }
    @Test void patchProofSurvivesJsonAndArchivedResultRoundTrip() throws Exception {
        Files.writeString(root.resolve("Example.java"),BEFORE);var w=new Workspace(root,root.resolve("private.properties"));var base=w.fileFingerprint("Example.java");
        var result=w.applyPatch("Example.java","// count old","// count corrected").withPatchBase(base);
        var longResult=new ToolResult(true,"result "+"x".repeat(3000),result.fileFingerprint(),null,null,result.patchBaseFingerprint());
        var original=new ToolExchange(patch("comment","// count old","// count corrected"),longResult);
        var saved=dev.backendagent.history.ArchivedBody.unload(1,original);var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var restored=json.readValue(json.writeValueAsString(saved),ToolExchange.class);
        assertEquals(base,restored.result().patchBaseFingerprint());restored.archivedBody().verify(1,original,restored);
        assertNull(json.readValue("{\"successful\":true,\"content\":\"legacy\"}",ToolResult.class).patchBaseFingerprint());
    }
}
