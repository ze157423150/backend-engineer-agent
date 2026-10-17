package dev.backendagent.runtime;

import dev.backendagent.model.*;
import dev.backendagent.tools.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PatchSourceFeedbackTest {
    @TempDir Path root;
    private Workspace workspace() throws java.io.IOException { return new Workspace(root, root.resolve("agent-local.properties")); }
    private void read(AgentSession session, Workspace workspace, String end) {
        session.remember(new ToolExchange(new ToolCall("read", "read_file", Map.of("path", "Main.java")),
                workspace.readFile("Main.java", "1", end)));
    }

    @Test void distinguishesMissingSourceFromMismatchInCurrentPartialRead() throws Exception {
        Files.writeString(root.resolve("Main.java"), "first\nsecond\n");
        var w=workspace(); var s=new AgentSession("edit");
        assertEquals(PatchSourceStatus.SOURCE_NOT_VISIBLE, s.assessCurrentPatchSource("Main.java", "first", w));
        read(s,w,"1");
        assertEquals(PatchSourceStatus.CURRENT, s.assessCurrentPatchSource("Main.java", "first", w));
        assertEquals(PatchSourceStatus.CURRENT_TEXT_MISMATCH, s.assessCurrentPatchSource("Main.java", "second", w));
        assertFalse(s.hasCurrentPatchSource("Main.java", "second", w));
    }

    @Test void unchangedTextWithDifferentWholeFileHashStillRequiresFreshSource() throws Exception {
        Files.writeString(root.resolve("Main.java"), "first\nsecond\n");
        var w=workspace(); var s=new AgentSession("edit"); read(s,w,"1");
        Files.writeString(root.resolve("Main.java"), "first\nexternal change\n");
        assertEquals(PatchSourceStatus.SOURCE_VERSION_CHANGED, s.assessCurrentPatchSource("Main.java", "first", w));
        assertFalse(s.hasCurrentPatchSource("Main.java", "first", w));
    }

    @Test void replacedTextIsReportedOnlyFromCurrentSuccessfulPatch() throws Exception {
        Files.writeString(root.resolve("Main.java"), "old other\n");
        var w=workspace(); var s=new AgentSession("edit");
        var call=new ToolCall("patch", "apply_patch", Map.of("path","Main.java","old_text","old","new_text","new"));
        s.remember(new ToolExchange(call,w.applyPatch("Main.java","old","new")));
        assertEquals(PatchSourceStatus.PREVIOUSLY_REPLACED,s.assessCurrentPatchSource("Main.java","old",w));
        assertEquals(PatchSourceStatus.CURRENT,s.assessCurrentPatchSource("Main.java","new",w));
        Files.writeString(root.resolve("Main.java"),"external other\n");
        assertNotEquals(PatchSourceStatus.PREVIOUSLY_REPLACED,s.assessCurrentPatchSource("Main.java","old",w));
        assertFalse(s.hasCurrentPatchSource("Main.java","new",w));
    }

    @Test void batchReplacementProvidesGuidanceWithoutJoiningIndependentFragments() throws Exception {
        Files.writeString(root.resolve("Main.java"), "one GAP two\n");
        var w=workspace(); var s=new AgentSession("edit");
        var edits=List.of(new PatchEdit("a","one","FIRST"),new PatchEdit("b","two","SECOND"));
        s.remember(new ToolExchange(new ToolCall("batch","apply_patch_batch",Map.of("path","Main.java","patches",ApplyPatchBatchTool.encode(edits))),
                w.applyPatchBatch("Main.java",w.fileFingerprint("Main.java").getSha256(),edits)));
        assertEquals(PatchSourceStatus.PREVIOUSLY_REPLACED,s.assessCurrentPatchSource("Main.java","one",w));
        assertEquals(PatchSourceStatus.CURRENT_TEXT_MISMATCH,s.assessCurrentPatchSource("Main.java","FIRSTSECOND",w));
        assertEquals(PatchSourceStatus.CURRENT_TEXT_MISMATCH,s.assessCurrentPatchSource("Main.java","GAP",w));
        assertFalse(s.hasCurrentPatchSource("Main.java","GAP",w));
    }

    @Test void hiddenHistoryCannotSupplyDiagnosticAuthority() throws Exception {
        Files.writeString(root.resolve("Main.java"),"old\n");
        var w=workspace(); var s=new AgentSession("edit"); read(s,w,"1");
        s.installProjection(new ContextProjection(4,1,1000,1,0,0,"",List.of()));
        assertEquals(PatchSourceStatus.SOURCE_NOT_VISIBLE,s.assessCurrentPatchSource("Main.java","old",w));
        assertFalse(s.hasCurrentPatchSource("Main.java","old",w));
    }

    @Test void detailedRejectionReachesModelAndDoesNotWriteOrAdvanceRevision() throws Exception {
        Files.writeString(root.resolve("Main.java"),"old\n");
        var w=workspace(); var s=new AgentSession("edit"); var step=new AtomicInteger();
        new AgentRuntime(request->switch(step.getAndIncrement()) {
            case 0 -> ModelResponse.callTool(new ToolCall("r","read_file",Map.of("path","Main.java","start_line","1","end_line","1")));
            case 1 -> ModelResponse.callTool(new ToolCall("p","apply_patch",Map.of("path","Main.java","old_text","old","new_text","new")));
            case 2 -> ModelResponse.callTool(new ToolCall("again","apply_patch",Map.of("path","Main.java","old_text","old","new_text","new")));
            case 3 -> {
                assertFalse(request.history().getLast().result().successful());
                assertTrue(request.history().getLast().result().content().contains("PATCH_SOURCE_PREVIOUSLY_REPLACED"));
                yield ModelResponse.callTool(new ToolCall("batch","apply_patch_batch",Map.of("path","Main.java","patches",ApplyPatchBatchTool.encode(List.of(
                        new PatchEdit("valid","new","changed"),new PatchEdit("invalid","missing","other"))))));
            }
            default -> {
                assertTrue(request.history().getLast().result().content().contains("CURRENT_TEXT_MISMATCH"));
                assertTrue(request.history().getLast().result().content().contains("本组全部未写入"));
                yield ModelResponse.finish("done");
            }
        },List.of(new ReadFileTool(w),new ApplyPatchTool(w,s)),5).run(s);
        assertEquals(AgentSession.Status.COMPLETED,s.status());
        assertEquals("new\n",Files.readString(root.resolve("Main.java")));
        assertEquals(1,s.workspaceState().getRevision());
    }
}
