package dev.backendagent.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import dev.backendagent.model.ModelResponse;
import dev.backendagent.model.ToolCall;
import dev.backendagent.tools.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PatchLoopTest {
    @TempDir Path root;

    @Test
    void readsRemembersPatchesInvalidatesOldEvidenceAndChecksDiff() throws IOException {
        Files.writeString(root.resolve("Main.java"), "class Main { int n = 1; }\n");
        var workspace = new Workspace(root, root.resolve("agent-local.properties"));
        var session = new AgentSession("change n to 2");
        var turns = new AtomicInteger();
        new AgentRuntime(request -> {
            int turn = turns.getAndIncrement();
            switch (turn) {
                case 0: return read("read-old");
                case 1: return note("note-old", "read-old", "n = 1");
                case 2:
                    assertEquals(1, request.workingMemory().size());
                    return patch("patch-1", "n = 1", "n = 2");
                case 3:
                    assertTrue(request.workingMemory().isEmpty());
                    return note("stale-note", "read-old", "n = 1");
                case 4:
                    assertFalse(request.history().getLast().result().successful());
                    return read("read-new");
                case 5: return note("note-new", "read-new", "n = 2");
                case 6: return call("diff-1", "workspace_diff", Map.of("path", "Main.java"));
                default:
                    assertTrue(request.history().getLast().result().content().contains("+class Main { int n = 2; }"));
                    assertEquals("n = 2", request.workingMemory().getFirst().getEvidenceQuote());
                    return ModelResponse.finish("modified; tests not run");
            }
        }, List.of(new ReadFileTool(workspace), new RememberFactTool(session), new ApplyPatchTool(workspace, session),
                new WorkspaceDiffTool(workspace)), 8).run(session);
        assertEquals(AgentSession.Status.COMPLETED, session.status());
        assertEquals("class Main { int n = 2; }\n", Files.readString(root.resolve("Main.java")));
        assertTrue(session.events().stream().anyMatch(event -> event.type() == AgentSession.EventType.FILE_EVIDENCE_INVALIDATED));
    }

    @Test
    void requiresFreshReadBeforeEachPatchAndFailedPatchPreservesMemory() throws IOException {
        Files.writeString(root.resolve("Main.java"), "class Main { int n = 1; }\n");
        var workspace = new Workspace(root, root.resolve("agent-local.properties"));
        var session = new AgentSession("modify");
        var turns = new AtomicInteger();
        new AgentRuntime(request -> {
            switch (turns.getAndIncrement()) {
                case 0: return patch("unread", "n = 1", "n = 2");
                case 1:
                    assertFalse(request.history().getLast().result().successful());
                    return read("read-1");
                case 2: return note("note-1", "read-1", "n = 1");
                case 3: return patch("missing", "n = 7", "n = 2");
                case 4:
                    assertFalse(request.history().getLast().result().successful());
                    assertEquals(1, request.workingMemory().size());
                    return patch("good", "n = 1", "n = 2");
                case 5: return patch("without-reread", "n = 2", "n = 3");
                default:
                    assertFalse(request.history().getLast().result().successful());
                    return ModelResponse.finish("done");
            }
        }, List.of(new ReadFileTool(workspace), new RememberFactTool(session), new ApplyPatchTool(workspace, session)), 7).run(session);
        assertEquals(AgentSession.Status.COMPLETED, session.status());
        assertTrue(Files.readString(root.resolve("Main.java")).contains("n = 2"));
    }

    private ModelResponse read(String id) {
        return call(id, "read_file", Map.of("path", "./Main.java", "start_line", "1", "end_line", "20"));
    }

    @Test
    void createsChecksReadsAndModifiesNewFileThroughRuntime() throws IOException {
        var workspace = new Workspace(root, root.resolve("agent-local.properties"));
        var session = new AgentSession("add a class");
        var turns = new AtomicInteger();
        new AgentRuntime(request -> {
            switch (turns.getAndIncrement()) {
                case 0: return call("create", "create_file", Map.of("path", "Main.java", "content", "class Main { int n = 1; }\n"));
                case 1: return call("overwrite", "create_file", Map.of("path", "Main.java", "content", "replacement"));
                case 2:
                    assertFalse(request.history().getLast().result().successful());
                    return read("read-created");
                case 3: return patch("edit-created", "n = 1", "n = 2");
                case 4: return call("diff-created", "workspace_diff", Map.of("path", "Main.java"));
                default:
                    assertTrue(request.history().getLast().result().content().contains("--- /dev/null"));
                    assertTrue(request.history().getLast().result().content().contains("+class Main { int n = 2; }"));
                    return ModelResponse.finish("added class; tests not run");
            }
        }, List.of(new CreateFileTool(workspace, session), new ReadFileTool(workspace),
                new ApplyPatchTool(workspace, session), new WorkspaceDiffTool(workspace)), 6).run(session);
        assertEquals(AgentSession.Status.COMPLETED, session.status());
        assertEquals("class Main { int n = 2; }\n", Files.readString(root.resolve("Main.java")));
    }
    private ModelResponse patch(String id, String oldText, String newText) {
        return call(id, "apply_patch", Map.of("path", "Main.java", "old_text", oldText, "new_text", newText));
    }
    private ModelResponse note(String id, String source, String quote) {
        return call(id, "remember_fact", Map.of("statement", "current value " + quote,
                "source_call_id", source, "evidence_quote", quote));
    }
    private ModelResponse call(String id, String name, Map<String, String> args) {
        return ModelResponse.callTool(new ToolCall(id, name, args));
    }
}
