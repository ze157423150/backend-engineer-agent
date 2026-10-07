package dev.backendagent.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import dev.backendagent.model.*;
import dev.backendagent.sandbox.*;
import dev.backendagent.tools.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static dev.backendagent.runtime.WorkspaceState.TestStatus.*;

class WorkspaceRevisionTest {
    @TempDir Path root;

    private Workspace workspace() throws Exception {
        Files.writeString(root.resolve("Main.java"), "class Main { int n = 0; }");
        Files.writeString(root.resolve("pom.xml"), "<project/>\n");
        return new Workspace(root, root.resolve("agent-local.properties"));
    }
    private ToolCall read(String id) {
        return new ToolCall(id, "read_file", Map.of("path", "Main.java", "start_line", "1", "end_line", "10"));
    }
    private ToolCall patch(String id, String old, String replacement) {
        return new ToolCall(id, "apply_patch", Map.of("path", "Main.java", "old_text", old, "new_text", replacement));
    }
    private ToolCall test(String id) { return new ToolCall(id, "run_tests", Map.of()); }

    @Test
    void orderedBatchBindsSnapshotAndLaterWriteInvalidatesTestsAndObservationAliases() throws Exception {
        var workspace = workspace();
        var session = new AgentSession("edit and verify");
        var runs = new AtomicInteger();
        var executor = new DockerSandboxExecutor((command, timeout) -> {
            if (command.get(1).equals("run")) {
                String mount = command.get(command.indexOf("--mount") + 1);
                Path snapshot = Path.of(mount.substring("type=bind,src=".length(), mount.indexOf(",dst=")));
                assertTrue(Files.readString(snapshot.resolve("Main.java")).contains("n = " + (runs.incrementAndGet())));
            }
            return new CommandResult(0, false, "BUILD SUCCESS");
        }, "test-image:local", Duration.ofSeconds(2));
        var turns = new AtomicInteger();
        new AgentRuntime(request -> {
            switch (turns.getAndIncrement()) {
                case 0:
                    assertEquals(NOT_RUN, request.workspaceState().testStatus());
                    return ModelResponse.callTool(read("read-0"));
                case 1:
                    return ModelResponse.callTools(List.of(patch("patch-1", "n = 0", "n = 1"), test("test-1")), null);
                case 2:
                    assertEquals(CURRENT_PASSED, request.workspaceState().testStatus());
                    assertEquals(1, request.workspaceState().getLatestTest().getWorkspaceRevision());
                    return ModelResponse.callTools(List.of(
                            new ToolCall("old-test-copy", "read_observation", Map.of("call_id", "test-1",
                                    "start_line", "1", "end_line", "5")),
                            read("read-1"), patch("patch-2", "n = 1", "n = 2")), null);
                case 3:
                    assertEquals(2, request.workspaceState().getRevision());
                    assertEquals(STALE, request.workspaceState().testStatus());
                    assertTrue(session.staleEvidenceIds().containsAll(List.of("test-1", "old-test-copy")));
                    var recovered = new ReadObservationTool(session).execute(Map.of("call_id", "test-1",
                            "start_line", "1", "end_line", "5"));
                    assertTrue(recovered.content().contains("stale=true"));
                    assertTrue(recovered.content().contains("workspaceRevision=1"));
                    return ModelResponse.callTool(test("test-2"));
                default:
                    assertEquals(CURRENT_PASSED, request.workspaceState().testStatus());
                    assertEquals(2, request.workspaceState().getLatestTest().getWorkspaceRevision());
                    assertFalse(session.staleEvidenceIds().contains("test-2"));
                    return ModelResponse.finish("verified");
            }
        }, List.of(new ReadFileTool(workspace), new ApplyPatchTool(workspace, session),
                new RunTestsTool(workspace, executor), new ReadObservationTool(session)), 6).run(session);
        assertEquals(AgentSession.Status.COMPLETED, session.status());
        assertEquals(2, runs.get());
    }

    @Test
    void failedWriteDoesNotInvalidateButCreatingFileDoesAndLatestFailureReplacesPass() throws Exception {
        var workspace = workspace();
        var session = new AgentSession("validate changes");
        var runs = new AtomicInteger();
        var executor = new DockerSandboxExecutor((command, timeout) -> command.get(1).equals("run")
                ? new CommandResult(runs.getAndIncrement() == 0 ? 0 : -1, runs.get() > 1, "test output")
                : new CommandResult(0, false, ""), "test-image:local", Duration.ofSeconds(2));
        var turns = new AtomicInteger();
        new AgentRuntime(request -> {
            switch (turns.getAndIncrement()) {
                case 0: return ModelResponse.callTool(test("passed"));
                case 1: return ModelResponse.callTool(patch("rejected", "n = 0", "n = 1")); // No preceding read.
                case 2:
                    assertEquals(0, request.workspaceState().getRevision());
                    assertEquals(CURRENT_PASSED, request.workspaceState().testStatus());
                    return ModelResponse.callTool(test("timeout"));
                case 3:
                    assertEquals(CURRENT_NOT_PASSED, request.workspaceState().testStatus());
                    assertEquals("timeout", request.workspaceState().getLatestTest().getCallId());
                    return ModelResponse.callTool(new ToolCall("create", "create_file",
                            Map.of("path", "New.java", "content", "class New {}")));
                default:
                    assertEquals(1, request.workspaceState().getRevision());
                    assertEquals(STALE, request.workspaceState().testStatus());
                    assertTrue(session.staleEvidenceIds().containsAll(List.of("passed", "timeout")));
                    return ModelResponse.finish("needs testing");
            }
        }, List.of(new ApplyPatchTool(workspace, session), new CreateFileTool(workspace, session),
                new RunTestsTool(workspace, executor)), 5).run(session);
        assertEquals(AgentSession.Status.COMPLETED, session.status());
    }

    @Test
    void prunedTestHistoryAndSummaryStillExposeCurrentStateAndStaleSource() {
        var session = new AgentSession("edit");
        session.remember(new ToolExchange(test("old-test"), new ToolResult(true, "BUILD SUCCESS\n" + "x".repeat(6000)), null, 1));
        session.remember(new ToolExchange(patch("edit", "old", "new"), new ToolResult(true, "updated"), null, 2));
        var summary = new ContextSummary(1, 1, List.of(new SummaryNote(SummaryNote.Kind.PROGRESS,
                "Historical tests passed", "old-test", "BUILD SUCCESS")));
        session.installSummary(summary);
        var request = new ContextAssembler(new ContextBudget(1000)).assemble(session, List.of(), 2);
        assertEquals(0, request.omittedExchanges()); // Placeholder fits; the archived test body remains intact.
        assertEquals(1, request.contextProjection().getFilteredExchanges());
        assertFalse(request.history().getFirst().result().content().contains("BUILD SUCCESS"));
        assertNull(request.contextSummary());
        assertEquals(STALE, request.workspaceState().testStatus());
        assertEquals("old-test", request.workspaceState().getLatestTest().getCallId());
        assertTrue(request.staleSummarySourceIds().contains("old-test"));
        assertEquals(2, session.history().size());
    }
}
