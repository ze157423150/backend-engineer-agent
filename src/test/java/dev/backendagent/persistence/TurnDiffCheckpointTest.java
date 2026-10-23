package dev.backendagent.persistence;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import dev.backendagent.runtime.*;
import dev.backendagent.model.*;
import dev.backendagent.tools.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TurnDiffCheckpointTest {
    @TempDir Path root;
    @Test void completedAndBudgetRecoveryPreserveTurnBaselineAndNextTurnResetsIt() throws Exception {
        Path repo=Files.createDirectory(root.resolve("repo")),data=root.resolve("sessions");
        Files.writeString(repo.resolve("Main.java"),"class Main { int n=1; }\n");
        UUID id;
        try(var store=new FileSessionStore(data)) {
            var w=new Workspace(repo,root.resolve("config.properties"));store.bindWorkspace(w);
            var s=new AgentSession("change n",store);store.create(s);id=s.id();
            var calls=new AtomicInteger();
            new AgentRuntime(r->switch(calls.getAndIncrement()) {
                case 0 -> ModelResponse.callTool(new ToolCall("read1","read_file",Map.of("path","Main.java","start_line","1","end_line","10")));
                default -> ModelResponse.callTool(new ToolCall("patch1","apply_patch",Map.of("path","Main.java","old_text","n=1","new_text","n=2")));
            },List.of(new ReadFileTool(w),new ApplyPatchTool(w,s),new WorkspaceDiffTool(w)),2).run(s);
            assertEquals(AgentSession.Status.BUDGET_EXHAUSTED,s.status());
        }
        try(var store=new FileSessionStore(data)) {
            var w=new Workspace(repo,root.resolve("config.properties"));
            var s=store.restore(id,w,10);
            assertTrue(w.diff("Main.java","turn").content().contains("n=1"));
            new AgentRuntime(r->ModelResponse.finish("done"),List.of(new WorkspaceDiffTool(w)),10).resume(s);
        }
        try(var store=new FileSessionStore(data)) {
            var w=new Workspace(repo,root.resolve("config.properties"));var s=store.openCompleted(id,w,10);
            assertTrue(w.diff("Main.java","turn").content().contains("n=1"));
            var calls=new AtomicInteger();
            new AgentRuntime(r->switch(calls.getAndIncrement()) {
                case 0 -> ModelResponse.callTool(new ToolCall("read2","read_file",Map.of("path","Main.java","start_line","1","end_line","10")));
                case 1 -> ModelResponse.callTool(new ToolCall("patch2","apply_patch",Map.of("path","Main.java","old_text","n=2","new_text","n=3")));
                default -> ModelResponse.finish("done");
            },List.of(new ReadFileTool(w),new ApplyPatchTool(w,s),new WorkspaceDiffTool(w)),10).continueConversation(s,"change to 3");
            String diff=w.diff("Main.java","turn").content();assertTrue(diff.contains("-class Main { int n=2; }"));assertFalse(diff.contains("n=1"));
            assertTrue(w.diff("Main.java","session").content().contains("n=1"));
            var json=new com.fasterxml.jackson.databind.ObjectMapper();
            var cp=json.readTree(Files.readString(store.sessionDirectory(id).resolve("checkpoint.json")));
            ((com.fasterxml.jackson.databind.node.ObjectNode)cp).remove("turnDiff");
            var legacy=json.treeToValue(cp,SessionCheckpoint.class);assertFalse(legacy.getTurnDiff().isAvailable());assertEquals(2,legacy.getTurnDiff().getTurnId());
        }
    }
}
