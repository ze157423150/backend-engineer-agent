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

    @Test
    void restoresRevisionAndStaleTestWithoutReplayingAndRejectsForgedState() throws Exception {
        Files.writeString(root.resolve("Main.java"), "class Main {}\n");
        UUID id;
        try (var store = new FileSessionStore(data())) {
            var workspace = workspace();
            store.bindWorkspace(workspace);
            var session = new AgentSession("test then edit", store);
            store.create(session);
            Tool tests = new Tool() {
                public String name() { return "run_tests"; }
                public dev.backendagent.model.ToolResult execute(Map<String, String> args) {
                    return new dev.backendagent.model.ToolResult(true, "status=PASSED");
                }
            };
            var turn = new AtomicInteger();
            new AgentRuntime(request -> turn.getAndIncrement() == 0
                    ? ModelResponse.callTools(List.of(new ToolCall("test", "run_tests", Map.of()), read("read", "Main.java")), null)
                    : ModelResponse.callTool(new ToolCall("patch", "apply_patch", Map.of("path", "Main.java",
                            "old_text", "class Main {}", "new_text", "class Main { int n; }"))),
                    List.of(tests, new ReadFileTool(workspace), new ApplyPatchTool(workspace, session)), 2).run(session);
            id = session.id();
            store.saveSnapshot(session);
            assertEquals("STALE", store.read(id).path("snapshot").path("testStatus").asText());
        }
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        Path file = data().resolve(id.toString()).resolve("checkpoint.json");
        var tree = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Files.readString(file));
        assertEquals(1, tree.path("workspaceState").path("revision").asLong());
        try (var store = new FileSessionStore(data())) {
            var restored = store.restore(id, workspace(), 3);
            assertEquals(1, restored.workspaceState().getRevision());
            assertEquals(dev.backendagent.runtime.WorkspaceState.TestStatus.STALE, restored.workspaceState().testStatus());
            assertTrue(restored.staleEvidenceIds().contains("test"));
            assertTrue(Files.readString(root.resolve("Main.java")).contains("int n"));
        }
        var forged = tree.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) forged.path("workspaceState").path("latestTest"))
                .put("workspaceRevision", 1); // Falsely claims the old test tested the edited source.
        Files.writeString(file, json.writeValueAsString(forged));
        try (var store = new FileSessionStore(data())) {
            assertThrows(IllegalArgumentException.class, () -> store.restore(id, workspace(), 3));
        }
        // Older checkpoints are reconstructed from history, with no writes or test execution.
        tree.remove("workspaceState");
        tree.set("staleEvidenceIds", json.createArrayNode().add("read"));
        Files.writeString(file, json.writeValueAsString(tree));
        try (var store = new FileSessionStore(data())) {
            var restored = store.restore(id, workspace(), 3);
            assertEquals(1, restored.workspaceState().getRevision());
            assertTrue(restored.staleEvidenceIds().contains("test"));
            new AgentRuntime(request -> {
                assertEquals(dev.backendagent.runtime.WorkspaceState.TestStatus.STALE, request.workspaceState().testStatus());
                return ModelResponse.finish("old test requires rerun");
            }, List.of(), 3).resume(restored);
            assertEquals(AgentSession.Status.COMPLETED, restored.status());
        }
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
    void restoresFilteredViewAndRawArchiveAndRejectsForgedPlaceholder() throws Exception {
        Files.writeString(root.resolve("Main.java"), "class Main {}\n");
        UUID id;
        try (var store = new FileSessionStore(data())) {
            var workspace = workspace();
            store.bindWorkspace(workspace);
            var session = new AgentSession("edit", store);
            store.create(session);
            var turn = new AtomicInteger();
            new AgentRuntime(request -> switch (turn.getAndIncrement()) {
                case 0 -> ModelResponse.callTool(read("old", "Main.java"));
                case 1 -> ModelResponse.callTool(new ToolCall("patch", "apply_patch", Map.of("path", "Main.java",
                        "old_text", "class Main {}", "new_text", "class Main { int n; }")));
                default -> ModelResponse.callTool(new ToolCall("list", "list_files", Map.of("path", ".")));
            }, List.of(new ReadFileTool(workspace), new ApplyPatchTool(workspace, session), new ListFilesTool(workspace)), 3).run(session);
            assertEquals(1, session.contextProjection().getFilteredExchanges());
            id = session.id();
        }
        try (var store = new FileSessionStore(data())) {
            var restored = store.restore(id, workspace(), 4);
            assertTrue(restored.history().getFirst().result().content().contains("class Main {}"));
            assertFalse(restored.contextProjection().getHistory().getFirst().result().content().contains("class Main {}"));
            new dev.backendagent.runtime.ContextAssembler().assemble(restored, List.of(), 1)
                    .contextProjection().validateAgainst(restored.history());
        }
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        Path file = data().resolve(id.toString()).resolve("checkpoint.json");
        var tree = json.readTree(Files.readString(file));
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("contextProjection").path("history").get(0).path("result"))
                .put("content", "forged stale notice");
        Files.writeString(file, json.writeValueAsString(tree));
        try (var store = new FileSessionStore(data())) {
            assertThrows(IllegalArgumentException.class, () -> store.restore(id, workspace(), 4));
        }
    }

    @Test
    void persistsFileEvidenceRejectsWrongRevisionAndTreatsLegacyReadAsUnknown() throws Exception {
        UUID id = stopAfterRead();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        Path file = data().resolve(id.toString()).resolve("checkpoint.json");
        var tree = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Files.readString(file));
        var exchange = (com.fasterxml.jackson.databind.node.ObjectNode) tree.path("history").get(0);
        assertEquals("read", exchange.path("evidence").path("sourceCallId").asText());
        try (var store = new FileSessionStore(data())) {
            var workspace = workspace();
            var restored = store.restore(id, workspace, 2);
            assertEquals(dev.backendagent.runtime.ObservationEvidence.Validity.CURRENT,
                    restored.assessFileEvidence("read", workspace));
            assertTrue(restored.hasCurrentRead("Main.java", workspace));
        }
        var forged = tree.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) forged.path("history").get(0).path("evidence"))
                .put("workspaceRevision", 1);
        Files.writeString(file, json.writeValueAsString(forged));
        try (var store = new FileSessionStore(data())) {
            // Version 2 rejects the forged record against the durable log before state reconstruction.
            assertThrows(IOException.class, () -> store.restore(id, workspace(), 2));
        }
        tree.put("schemaVersion", 1);
        tree.remove("historicalEvidence");
        exchange.remove("evidence");
        ((com.fasterxml.jackson.databind.node.ObjectNode) exchange.path("result")).remove("fileFingerprint");
        Files.writeString(file, json.writeValueAsString(tree));
        try (var store = new FileSessionStore(data())) {
            var workspace = workspace();
            var restored = store.restore(id, workspace, 2);
            assertEquals(dev.backendagent.runtime.ObservationEvidence.Validity.UNKNOWN,
                    restored.assessFileEvidence("read", workspace));
            assertFalse(restored.hasCurrentRead("Main.java"));
            assertEquals(1, restored.history().size());
        }
    }

    @Test
    void persistsSummaryAcrossBudgetStopAndResumeWithoutRepeatingCoveredCompaction() throws Exception {
        UUID id;
        String raw = "historical evidence\n" + "x".repeat(3000);
        try (var store = new FileSessionStore(data())) {
            store.bindWorkspace(workspace());
            var session = new AgentSession("inspect", store);
            store.create(session);
            var turns = new AtomicInteger();
            var model = new dev.backendagent.model.ModelClient() {
                public boolean supportsSummarization() { return true; }
                public List<dev.backendagent.runtime.SummaryNote> summarize(dev.backendagent.model.SummaryRequest request) {
                    return List.of(new dev.backendagent.runtime.SummaryNote(dev.backendagent.runtime.SummaryNote.Kind.PROGRESS,
                            "Observed Main historically", "read-1", "historical evidence"));
                }
                public ModelResponse execute(dev.backendagent.model.ModelRequest request) {
                    return ModelResponse.callTool(read("read-" + turns.incrementAndGet(), "Main.java"));
                }
            };
            Tool tool = new Tool() {
                public String name() { return "read_file"; }
                public dev.backendagent.model.ToolResult execute(Map<String, String> args) {
                    return new dev.backendagent.model.ToolResult(true, raw);
                }
            };
            new AgentRuntime(model, List.of(tool), 10, new dev.backendagent.runtime.ContextBudget(7000)).run(session);
            assertEquals(AgentSession.Status.BUDGET_EXHAUSTED, session.status());
            assertEquals(10, session.modelCalls());
            assertEquals(9, session.history().size());
            assertEquals(1, session.contextSummary().getRevision());
            assertEquals(4, session.contextSummary().getCoveredExchanges());
            id = session.id();
        }
        try (var store = new FileSessionStore(data())) {
            var restored = store.restore(id, workspace(), 12);
            assertEquals(raw, restored.originalObservation("read-1").result().content());
            assertEquals(1, restored.contextSummary().getRevision());
            new AgentRuntime(new dev.backendagent.model.ModelClient() {
                public boolean supportsSummarization() { return true; }
                public List<dev.backendagent.runtime.SummaryNote> summarize(dev.backendagent.model.SummaryRequest request) {
                    fail("Covered prefix should not be summarized again"); return List.of();
                }
                public ModelResponse execute(dev.backendagent.model.ModelRequest request) {
                    assertNotNull(request.contextSummary());
                    assertTrue(request.historyCharacters() <= 7000);
                    return ModelResponse.finish("continued");
                }
            }, List.of(), 12, new dev.backendagent.runtime.ContextBudget(7000)).resume(restored);
            assertEquals(AgentSession.Status.COMPLETED, restored.status());
            assertEquals(11, restored.modelCalls());
        }
    }

    @Test
    void refusesSummaryWithForgedQuotationOnRestore() throws Exception {
        UUID id = stopAfterRead();
        Path checkpoint = data().resolve(id.toString()).resolve("checkpoint.json");
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var tree = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Files.readString(checkpoint));
        tree.putObject("contextSummary").put("revision", 1).put("coveredExchanges", 1)
                .putArray("notes").addObject().put("kind", "PROGRESS").put("statement", "forged conclusion")
                .put("sourceCallId", "read").put("evidenceQuote", "not in original result");
        Files.writeString(checkpoint, json.writeValueAsString(tree));
        try (var store = new FileSessionStore(data())) {
            assertThrows(IllegalArgumentException.class, () -> store.restore(id, workspace(), 2));
        }
    }

    @Test
    void restoresRecentWindowProjectionAndOriginalObservationsAndRejectsTampering() throws Exception {
        UUID id;
        String raw = "header\n" + "noise\n".repeat(1800) + "middle evidence\n" + "tail\n".repeat(1800);
        try (var store = new FileSessionStore(data())) {
            store.bindWorkspace(workspace());
            var session = new AgentSession("inspect", store);
            store.create(session);
            var turn = new AtomicInteger();
            Tool read = new Tool() {
                public String name() { return "read_file"; }
                public dev.backendagent.model.ToolResult execute(Map<String, String> args) {
                    return new dev.backendagent.model.ToolResult(true, raw);
                }
            };
            Tool small = new Tool() {
                public String name() { return "list_files"; }
                public dev.backendagent.model.ToolResult execute(Map<String, String> args) {
                    return new dev.backendagent.model.ToolResult(true, "Main.java");
                }
            };
            new AgentRuntime(request -> {
                int call = turn.getAndIncrement();
                return ModelResponse.callTool(call < 2
                        ? new ToolCall(call == 0 ? "old" : "second", "read_file", Map.of("path", "Main.java"))
                        : new ToolCall("new", "list_files", Map.of("path", ".")));
            }, List.of(read, small), 3, new dev.backendagent.runtime.ContextBudget(26000)).run(session);
            // The checkpoint includes the third tool result and the last view sent before that result.
            id = session.id();
            assertEquals(0, session.contextProjection().getCompressedExchanges());
            assertEquals(4, session.contextProjection().getPolicyVersion());
        }
        try (var store = new FileSessionStore(data())) {
            var restored = store.restore(id, workspace(), 4);
            assertEquals(raw, restored.history().getFirst().result().content());
            assertEquals(0, restored.contextProjection().getCompressedExchanges());
            assertTrue(new ReadObservationTool(restored).execute(Map.of("call_id", "old",
                    "start_line", "1802", "end_line", "1802")).content().contains("middle evidence"));
        }
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        Path checkpoint = data().resolve(id.toString()).resolve("checkpoint.json");
        var tree = json.readTree(Files.readString(checkpoint));
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("contextProjection")
                .path("history").get(0).path("result")).put("content", "forged view");
        Files.writeString(checkpoint, json.writeValueAsString(tree));
        try (var store = new FileSessionStore(data())) {
            assertThrows(IllegalArgumentException.class, () -> store.restore(id, workspace(), 4));
        }
    }

    @Test
    void restoresOlderCheckpointWithoutProjection() throws Exception {
        UUID id = stopAfterRead();
        Path checkpoint = data().resolve(id.toString()).resolve("checkpoint.json");
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var tree = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Files.readString(checkpoint));
        tree.remove("contextProjection");
        Files.writeString(checkpoint, json.writeValueAsString(tree));
        try (var store = new FileSessionStore(data())) {
            var restored = store.restore(id, workspace(), 2);
            assertNull(restored.contextProjection());
            new AgentRuntime(request -> {
                assertNotNull(request.contextProjection());
                return ModelResponse.finish("restored");
            }, List.of(), 2).resume(restored);
            assertEquals(AgentSession.Status.COMPLETED, restored.status());
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
                    try {
                        Path checkpoint = store.sessionDirectory(session.id()).resolve("checkpoint.json");
                        Files.deleteIfExists(checkpoint);
                        Files.createDirectory(checkpoint);
                    }
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
