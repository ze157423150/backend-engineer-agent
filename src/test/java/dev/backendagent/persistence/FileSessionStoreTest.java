package dev.backendagent.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import dev.backendagent.model.ModelResponse;
import dev.backendagent.model.ToolCall;
import dev.backendagent.runtime.AgentRuntime;
import dev.backendagent.runtime.AgentSession;
import dev.backendagent.tools.ReadFileTool;
import dev.backendagent.tools.RememberFactTool;
import dev.backendagent.tools.Tool;
import dev.backendagent.tools.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FileSessionStoreTest {
    @TempDir Path root;

    @Test
    void newStoreInstanceCanReadCompletedHistoryEventsAndMemory() throws Exception {
        Files.writeString(root.resolve("Main.java"), "class Main {}\n");
        var store = new FileSessionStore(root.resolve("sessions"));
        var session = new AgentSession("inspect", store);
        store.create(session);
        var workspace = new Workspace(root, root.resolve("agent-local.properties"), root.resolve("sessions"));
        var turns = new AtomicInteger();
        new AgentRuntime(request -> switch (turns.getAndIncrement()) {
            case 0 -> ModelResponse.callTool(new ToolCall("read", "read_file", Map.of(
                    "path", "Main.java", "start_line", "1", "end_line", "20")));
            case 1 -> ModelResponse.callTool(new ToolCall("note", "remember_fact", Map.of(
                    "statement", "Main exists", "source_call_id", "read", "evidence_quote", "class Main {}")));
            default -> ModelResponse.finish("done\n中文回答");
        }, List.of(new ReadFileTool(workspace), new RememberFactTool(session)), 3).run(session);
        store.saveSnapshot(session);

        var saved = new FileSessionStore(root.resolve("sessions")).read(session.id());
        assertFalse(saved.path("snapshotOutOfDate").asBoolean());
        assertEquals("COMPLETED", saved.path("lastRecordedStatus").asText());
        assertEquals("done\n中文回答", saved.path("snapshot").path("answer").asText());
        assertEquals(3, saved.path("snapshot").path("modelCalls").asInt());
        assertEquals(2, saved.path("snapshot").path("history").size());
        assertEquals("read", saved.path("snapshot").path("workingMemory").get(0).path("sourceCallId").asText());
        var events = saved.path("events");
        assertEquals(session.events().size(), events.size());
        boolean foundExchange = false;
        for (int i = 0; i < events.size(); i++) {
            assertEquals(i + 1, events.get(i).path("sequence").asInt());
            java.time.Instant.parse(events.get(i).path("timestamp").asText());
            if (events.get(i).path("type").asText().equals("TOOL_EXECUTION_SUCCEEDED")
                    && events.get(i).path("payload").path("call").path("id").asText().equals("read")) {
                foundExchange = true;
                assertTrue(events.get(i).path("payload").path("result").path("successful").asBoolean());
                assertTrue(events.get(i).path("payload").path("result").path("content").asText().contains("class Main {}"));
            }
        }
        assertTrue(foundExchange);
        assertEquals(events.size(), Files.readAllLines(store.sessionDirectory(session.id()).resolve("events.jsonl")).size());
    }

    @Test
    void queryMarksSnapshotOutOfDateWhenFinalSnapshotWasNotSaved() throws Exception {
        var store = new FileSessionStore(root);
        var session = new AgentSession("inspect", store);
        store.create(session);
        new AgentRuntime(request -> ModelResponse.finish("done"), List.of(), 1).run(session);
        var saved = new FileSessionStore(root).read(session.id());
        assertTrue(saved.path("snapshotOutOfDate").asBoolean());
        assertEquals("CREATED", saved.path("snapshot").path("status").asText());
        assertEquals("COMPLETED", saved.path("lastRecordedStatus").asText());
    }

    @Test
    void storageFailureStopsBeforeExecutingToolAndNeverStartsAnotherModelTurn() throws Exception {
        var store = new FileSessionStore(root);
        var session = new AgentSession("inspect", store);
        store.create(session);
        var calls = new AtomicInteger();
        var executions = new AtomicInteger();
        Tool tool = new Tool() {
            public String name() { return "count"; }
            public dev.backendagent.model.ToolResult execute(Map<String, String> args) {
                executions.incrementAndGet();
                return new dev.backendagent.model.ToolResult(true, "done");
            }
        };
        var runtime = new AgentRuntime(request -> {
            calls.incrementAndGet();
            Path log = store.sessionDirectory(session.id()).resolve("events.jsonl");
            try {
                Files.move(log, log.resolveSibling("original.jsonl"));
                Files.createDirectory(log);
            } catch (IOException failure) { throw new IllegalStateException(failure); }
            return ModelResponse.callTool(new ToolCall("call", "count", Map.of()));
        }, List.of(tool), 3);
        assertThrows(PersistenceException.class, () -> runtime.run(session));
        assertEquals(1, calls.get());
        assertEquals(0, executions.get());
        assertThrows(PersistenceException.class, () -> store.saveSnapshot(session));
    }

    @Test
    void persistenceFailureInsideToolCannotBecomeNormalToolFeedback() {
        var calls = new AtomicInteger();
        Tool broken = new Tool() {
            public String name() { return "broken"; }
            public dev.backendagent.model.ToolResult execute(Map<String, String> args) {
                throw new PersistenceException("disk failure", null);
            }
        };
        var session = new AgentSession("inspect");
        var runtime = new AgentRuntime(request -> {
            calls.incrementAndGet();
            return ModelResponse.callTool(new ToolCall("call", "broken", Map.of()));
        }, List.of(broken), 3);
        assertThrows(PersistenceException.class, () -> runtime.run(session));
        assertEquals(1, calls.get());
        assertTrue(session.history().isEmpty());
    }

    @Test
    void rejectsPartialLogAndDoesNotOverwriteExistingSession() throws Exception {
        var store = new FileSessionStore(root);
        var session = new AgentSession("inspect", store);
        store.create(session);
        new AgentRuntime(request -> ModelResponse.finish("done"), List.of(), 1).run(session);
        store.saveSnapshot(session);
        Path snapshot = store.sessionDirectory(session.id()).resolve("session.json");
        String original = Files.readString(snapshot);
        assertThrows(PersistenceException.class, () -> new FileSessionStore(root).create(session));
        assertEquals(original, Files.readString(snapshot));
        Files.writeString(store.sessionDirectory(session.id()).resolve("events.jsonl"), "{", StandardOpenOption.APPEND);
        assertThrows(IOException.class, () -> new FileSessionStore(root).read(session.id()));
    }

    @Test
    void persistsOrdinaryFailureAndBudgetExhaustion() throws Exception {
        var store = new FileSessionStore(root);
        var failed = new AgentSession("fail", store);
        store.create(failed);
        new AgentRuntime(request -> { throw new IllegalStateException("model unavailable"); }, List.of(), 1).run(failed);
        store.saveSnapshot(failed);
        assertEquals("FAILED", store.read(failed.id()).path("snapshot").path("status").asText());
        var exhausted = new AgentSession("loop", store);
        store.create(exhausted);
        new AgentRuntime(request -> ModelResponse.callTool(new ToolCall("call", "missing", Map.of())), List.of(), 1).run(exhausted);
        store.saveSnapshot(exhausted);
        assertEquals("BUDGET_EXHAUSTED", store.read(exhausted.id()).path("snapshot").path("status").asText());
    }
}
