package dev.backendagent.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import dev.backendagent.cli.AgentOptions;
import dev.backendagent.model.ModelResponse;
import dev.backendagent.model.ToolCall;
import dev.backendagent.runtime.AgentRuntime;
import dev.backendagent.runtime.AgentSession;
import dev.backendagent.tools.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SessionCheckpointTest {
    @TempDir Path root;
    private Path data() { return root.resolve("sessions"); }
    private Workspace workspace() throws IOException {
        return new Workspace(root, root.resolve("agent-local.properties"), data());
    }
    private ToolCall read(String id, String path) {
        return new ToolCall(id, "read_file", Map.of("path", path, "start_line", "1", "end_line", "20"));
    }
    private UUID stopAfterRead() throws Exception {
        Files.writeString(root.resolve("Main.java"), "class Main {}\n");
        try (var store = new FileSessionStore(data())) {
            var workspace = workspace();
            store.bindWorkspace(workspace);
            var session = new AgentSession("inspect", store);
            store.create(session);
            new AgentRuntime(request -> ModelResponse.callTool(read("read", "Main.java")),
                    List.of(new ReadFileTool(workspace)), 1).run(session);
            store.saveSnapshot(session);
            return session.id();
        }
    }

    @Test
    void restoresSameTaskHistoryMemoryCountersAndContinuousEvents() throws Exception {
        Files.writeString(root.resolve("Main.java"), "class Main {}\n");
        UUID id;
        int eventCount;
        try (var store = new FileSessionStore(data())) {
            var workspace = workspace();
            store.bindWorkspace(workspace);
            var session = new AgentSession("inspect Main", store);
            store.create(session);
            var turn = new AtomicInteger();
            new AgentRuntime(request -> turn.getAndIncrement() == 0
                    ? ModelResponse.callTool(read("read", "Main.java"))
                    : ModelResponse.callTool(new ToolCall("note", "remember_fact", Map.of(
                        "statement", "Main exists", "source_call_id", "read", "evidence_quote", "class Main {}"))),
                    List.of(new ReadFileTool(workspace), new RememberFactTool(session)), 2).run(session);
            store.saveSnapshot(session);
            assertEquals(AgentSession.Status.BUDGET_EXHAUSTED, session.status());
            id = session.id();
            eventCount = session.events().size();
        }
        // A fresh store/session/workspace, with no original Java objects reused.
        try (var store = new FileSessionStore(data())) {
            var session = store.restore(id, workspace(), 3);
            assertEquals(id, session.id());
            assertEquals(2, session.modelCalls());
            assertEquals(2, session.history().size());
            assertEquals("Main exists", session.memoryFacts().getFirst().getStatement());
            new AgentRuntime(request -> {
                assertEquals("inspect Main", request.objective());
                assertEquals(1, request.remainingModelCalls());
                assertEquals(2, request.history().size());
                assertEquals("read", request.workingMemory().getFirst().getSourceCallId());
                return ModelResponse.finish("continued");
            }, List.of(), 3).resume(session);
            store.saveSnapshot(session);
            assertEquals(AgentSession.Status.COMPLETED, session.status());
            assertEquals(3, session.modelCalls());
            assertEquals(AgentSession.EventType.SESSION_RESUMED, session.events().get(eventCount).type());
            assertEquals(eventCount + 1, session.events().get(eventCount).sequence());
            assertFalse(store.read(id).path("snapshotOutOfDate").asBoolean());
            assertThrows(IOException.class, () -> new FileSessionStore(data()).restore(id, workspace(), 4));
        }
    }

    @Test
    void restoresInvalidatedEvidenceAndBaselinesWithoutReplayingMutations() throws Exception {
        Files.writeString(root.resolve("Main.java"), "class Main {}\n");
        UUID id;
        try (var store = new FileSessionStore(data())) {
            var workspace = workspace();
            store.bindWorkspace(workspace);
            var session = new AgentSession("modify", store);
            store.create(session);
            var turn = new AtomicInteger();
            new AgentRuntime(request -> turn.getAndIncrement() == 0
                    ? ModelResponse.callTool(read("read", "Main.java"))
                    : ModelResponse.callTools(List.of(
                        new ToolCall("patch", "apply_patch", Map.of("path", "Main.java",
                                "old_text", "class Main {}", "new_text", "class Main { int n; }")),
                        new ToolCall("create", "create_file", Map.of("path", "New.java", "content", "class New {}\n"))), "changes"),
                    List.of(new ReadFileTool(workspace), new ApplyPatchTool(workspace, session),
                            new CreateFileTool(workspace, session)), 2).run(session);
            store.saveSnapshot(session);
            id = session.id();
        }
        String current = Files.readString(root.resolve("Main.java"));
        try (var store = new FileSessionStore(data())) {
            var workspace = workspace();
            var session = store.restore(id, workspace, 3);
            assertEquals(current, Files.readString(root.resolve("Main.java")));
            assertFalse(session.hasCurrentRead("Main.java"));
            assertTrue(workspace.diff("Main.java").content().contains("-class Main {}"));
            assertTrue(workspace.diff("New.java").content().contains("/dev/null"));
            assertEquals(2, session.history().get(1).modelCallNumber());
            assertEquals(2, session.history().get(2).modelCallNumber());
            new AgentRuntime(request -> ModelResponse.callTool(read("read", "Main.java")),
                    List.of(new ReadFileTool(workspace)), 3).resume(session);
            assertEquals(AgentSession.Status.FAILED, session.status());
            assertEquals(3, session.history().size()); // Duplicate old IDs still cannot execute.
        }
    }

    @Test
    void refusesChangedWorkspaceInsufficientBudgetAndConcurrentWriter() throws Exception {
        UUID id = stopAfterRead();
        try (var store = new FileSessionStore(data())) {
            assertThrows(IOException.class, () -> store.restore(id, workspace(), 1));
            Files.writeString(root.resolve("Main.java"), "class Changed {}\n");
            assertThrows(IOException.class, () -> store.restore(id, workspace(), 2));
            Files.writeString(root.resolve("Main.java"), "class Main {}\n");
            var session = store.restore(id, workspace(), 2);
            try (var competing = new FileSessionStore(data())) {
                assertThrows(IOException.class, () -> competing.restore(id, workspace(), 2));
            }
            assertEquals(id, session.id());
        }
    }

    @Test
    void refusesEventTailBeyondCheckpointAndIncompleteLog() throws Exception {
        UUID id = stopAfterRead();
        try (var store = new FileSessionStore(data())) {
            var session = store.restore(id, workspace(), 2);
            // Model starts, then throws: checkpoint remains at the old budget stop.
            new AgentRuntime(request -> { throw new IllegalStateException("interrupted turn"); }, List.of(), 2)
                    .resume(session);
            store.saveSnapshot(session);
        }
        try (var store = new FileSessionStore(data())) {
            assertThrows(IOException.class, () -> store.restore(id, workspace(), 3));
            Files.writeString(store.sessionDirectory(id).resolve("events.jsonl"), "{",
                    java.nio.file.StandardOpenOption.APPEND);
            assertThrows(IOException.class, () -> store.restore(id, workspace(), 3));
        }
    }

    @Test
    void acceptsFreshBudgetCheckpointEvenIfInspectionSnapshotIsBehind() throws Exception {
        Files.writeString(root.resolve("Main.java"), "class Main {}\n");
        UUID id;
        try (var store = new FileSessionStore(data())) {
            var workspace = workspace();
            store.bindWorkspace(workspace);
            var session = new AgentSession("inspect", store);
            store.create(session);
            new AgentRuntime(request -> ModelResponse.callTool(read("read", "Main.java")),
                    List.of(new ReadFileTool(workspace)), 1).run(session);
            id = session.id(); // No saveSnapshot: checkpoint alone contains the complete budget boundary.
        }
        try (var store = new FileSessionStore(data())) {
            assertTrue(store.read(id).path("snapshotOutOfDate").asBoolean());
            assertEquals(1, store.restore(id, workspace(), 2).history().size());
        }
    }

    @Test
    void refusesPendingToolThatMayAlreadyHaveModifiedAFile() throws Exception {
        Files.writeString(root.resolve("Main.java"), "class Main {}\n");
        UUID id;
        try (var store = new FileSessionStore(data())) {
            var workspace = workspace();
            store.bindWorkspace(workspace);
            var session = new AgentSession("modify", store);
            store.create(session);
            id = session.id();
            var turn = new AtomicInteger();
            Tool interrupted = new Tool() {
                public String name() { return "interrupted_write"; }
                public dev.backendagent.model.ToolResult execute(Map<String, String> args) {
                    try { Files.writeString(root.resolve("Main.java"), "class Changed {}\n"); }
                    catch (IOException failure) { throw new IllegalStateException(failure); }
                    throw new PersistenceException("Process stopped before recording tool result", null);
                }
            };
            assertThrows(PersistenceException.class, () -> new AgentRuntime(request ->
                    ModelResponse.callTool(turn.getAndIncrement() == 0 ? read("read", "Main.java")
                            : new ToolCall("write", "interrupted_write", Map.of())),
                    List.of(new ReadFileTool(workspace), interrupted), 3).run(session));
            assertEquals("class Changed {}\n", Files.readString(root.resolve("Main.java")));
        }
        try (var store = new FileSessionStore(data())) {
            assertThrows(IOException.class, () -> store.restore(id, workspace(), 4));
            assertEquals("class Changed {}\n", Files.readString(root.resolve("Main.java")));
        }
    }

    @Test
    void checkpointWriteFailureStopsBeforeAnotherModelCall() throws Exception {
        try (var store = new FileSessionStore(data())) {
            store.bindWorkspace(workspace());
            var session = new AgentSession("inspect", store);
            store.create(session);
            var calls = new AtomicInteger();
            Tool sabotage = new Tool() {
                public String name() { return "sabotage"; }
                public dev.backendagent.model.ToolResult execute(Map<String, String> args) {
                    try { Files.createDirectory(store.sessionDirectory(session.id()).resolve("checkpoint.json")); }
                    catch (IOException failure) { throw new IllegalStateException(failure); }
                    return new dev.backendagent.model.ToolResult(true, "done");
                }
            };
            assertThrows(PersistenceException.class, () -> new AgentRuntime(request -> {
                calls.incrementAndGet();
                return ModelResponse.callTool(new ToolCall("sabotage", "sabotage", Map.of()));
            }, List.of(sabotage), 3).run(session));
            assertEquals(1, calls.get());
        }
    }

    @Test
    void parsesResumeWithoutTaskAndRejectsConflictingOptions() {
        String id = UUID.randomUUID().toString();
        assertEquals(UUID.fromString(id), AgentOptions.parse(new String[]{
                "--resume-session", id, "--workspace", root.toString()}).getResumeSession());
        assertThrows(IllegalArgumentException.class, () -> AgentOptions.parse(new String[]{
                "--resume-session", id, "--workspace", root.toString(), "--task", "new task"}));
        assertThrows(IllegalArgumentException.class, () -> AgentOptions.parse(new String[]{
                "--resume-session", id, "--workspace", root.toString(), "--memory-from", id}));
        assertThrows(IllegalArgumentException.class, () -> AgentOptions.parse(new String[]{
                "--resume-session", id, "--show-session", id}));
        assertThrows(IllegalArgumentException.class, () -> AgentOptions.parse(new String[]{
                "--resume-session", "invalid", "--workspace", root.toString()}));
    }
}
