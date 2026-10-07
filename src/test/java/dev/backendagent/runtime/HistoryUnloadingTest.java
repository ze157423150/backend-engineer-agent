package dev.backendagent.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.backendagent.model.*;
import dev.backendagent.persistence.*;
import dev.backendagent.tools.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HistoryUnloadingTest {
    @TempDir Path root;
    private static final String QUOTE = "HIDDEN_MIDDLE_EVIDENCE";
    private String body() { return "HEAD" + "h".repeat(2500) + QUOTE + "t".repeat(2500) + "TAIL\n"; }
    private Path data() { return root.resolve("sessions"); }
    private Workspace workspace() throws Exception { return new Workspace(root, root.resolve("agent-local.properties"), data()); }
    private ToolCall read(String id) {
        return new ToolCall(id, "read_file", Map.of("path", "Main.java", "start_line", "1", "end_line", "10"));
    }

    @Test
    void unloadsOldBodiesAndVerifiesHiddenMemoryAndSummaryQuotesAcrossResume() throws Exception {
        Files.writeString(root.resolve("Main.java"), body());
        UUID id;
        try (var store = new FileSessionStore(data())) {
            var workspace = workspace();
            store.bindWorkspace(workspace);
            var session = new AgentSession("read and remember", store);
            store.create(session);
            var turns = new AtomicInteger();
            new AgentRuntime(request -> {
                int turn = turns.getAndIncrement();
                if (turn < 5) { return ModelResponse.callTool(read("read-" + turn)); }
                var old = session.history().getFirst();
                assertNotNull(old.archivedBody());
                assertFalse(old.result().content().contains(QUOTE));
                assertTrue(session.originalObservation("read-0").result().content().contains(QUOTE));
                session.installSummary(new ContextSummary(1, 1,
                        List.of(new SummaryNote(SummaryNote.Kind.PROGRESS, "middle observed", "read-0", QUOTE))));
                return ModelResponse.callTool(new ToolCall("memory", "remember_fact", Map.of(
                        "statement", "middle observed", "source_call_id", "read-0", "evidence_quote", QUOTE)));
            }, List.of(new ReadFileTool(workspace), new RememberFactTool(session)), 6).run(session);
            assertEquals(AgentSession.Status.BUDGET_EXHAUSTED, session.status());
            assertEquals(1, session.memoryFacts().size());
            assertFalse(session.history().getFirst().result().content().contains(QUOTE));
            assertTrue(session.events().stream().allMatch(event -> event.detail().length() <= 1200));
            assertTrue(Files.readString(store.sessionDirectory(session.id()).resolve("events.jsonl")).contains(QUOTE));
            assertTrue(session.history().get(2).result().content().contains(QUOTE)); // Last four complete batches stay inline.
            store.saveSnapshot(session);
            id = session.id();
            String checkpoint = Files.readString(store.sessionDirectory(id).resolve("checkpoint.json"));
            assertTrue(checkpoint.contains("archivedBody"));
        }
        try (var store = new FileSessionStore(data())) {
            var workspace = workspace();
            var restored = store.restore(id, workspace, 8);
            assertNotNull(restored.history().getFirst().archivedBody());
            assertFalse(restored.history().getFirst().result().content().contains(QUOTE));
            assertEquals(QUOTE, restored.memoryFacts().getFirst().getEvidenceQuote());
            assertEquals(QUOTE, restored.contextSummary().getNotes().getFirst().getEvidenceQuote());
            var retrieve = new ReadObservationTool(restored, workspace, store.observationArchive(restored));
            new AgentRuntime(request -> restored.modelCalls() == 7
                    ? ModelResponse.callTool(new ToolCall("retrieve", "read_observation", Map.of(
                        "call_id", "read-0", "start_line", "2", "end_line", "2")))
                    : ModelResponse.finish("continued"), List.of(retrieve), 8).resume(restored);
            assertEquals(AgentSession.Status.COMPLETED, restored.status());
            assertTrue(restored.history().getLast().result().content().contains(QUOTE));
            assertFalse(restored.history().getFirst().result().content().contains(QUOTE)); // Retrieval does not rehydrate history.
            store.saveSnapshot(restored);
            var destination = new AgentSession("import archived evidence", store);
            store.create(destination);
            var report = new dev.backendagent.memory.MemoryLoader().load(store, id, workspace, destination);
            assertEquals(1, report.getLoaded());
            assertEquals(QUOTE, destination.memoryFacts().getFirst().getEvidenceQuote());
        }
    }

    @Test
    void protectsWholeMultiToolBatchesAndDoesNotUnloadWithoutDurableStorage() throws Exception {
        Files.writeString(root.resolve("Main.java"), body());
        try (var store = new FileSessionStore(data())) {
            var session = new AgentSession("batches", store);
            store.create(session);
            var turns = new AtomicInteger();
            new AgentRuntime(request -> {
                int turn = turns.getAndIncrement();
                return ModelResponse.callTools(List.of(read("a-" + turn), read("b-" + turn)), "reading");
            }, List.of(new ReadFileTool(workspace())), 5).run(session);
            assertEquals(10, session.history().size());
            assertTrue(session.history().subList(0, 2).stream().allMatch(exchange -> exchange.archivedBody() != null));
            assertTrue(session.history().subList(2, 10).stream().allMatch(exchange -> exchange.archivedBody() == null));
        }
        var volatileSession = new AgentSession("no disk");
        var turns = new AtomicInteger();
        new AgentRuntime(request -> ModelResponse.callTool(read("v-" + turns.getAndIncrement())),
                List.of(new ReadFileTool(workspace())), 6).run(volatileSession);
        assertTrue(volatileSession.history().stream().allMatch(exchange -> exchange.archivedBody() == null));
    }

    private UUID archivedStop() throws Exception {
        Files.writeString(root.resolve("Main.java"), body());
        try (var store = new FileSessionStore(data())) {
            store.bindWorkspace(workspace());
            var session = new AgentSession("checkpoint", store);
            store.create(session);
            var turns = new AtomicInteger();
            new AgentRuntime(request -> ModelResponse.callTool(read("read-" + turns.getAndIncrement())),
                    List.of(new ReadFileTool(workspace())), 5).run(session);
            store.saveSnapshot(session);
            return session.id();
        }
    }

    @Test
    void rejectsForgedReferenceHashSequenceAndExcerptOnRestore() throws Exception {
        UUID id = archivedStop();
        var json = new ObjectMapper();
        Path checkpoint = data().resolve(id.toString()).resolve("checkpoint.json");
        var original = (ObjectNode) json.readTree(Files.readString(checkpoint));
        for (String field : List.of("sha256", "eventSequence", "excerpt")) {
            var forged = original.deepCopy();
            var entry = (ObjectNode) forged.path("history").get(0);
            if (field.equals("excerpt")) { ((ObjectNode) entry.path("result")).put("content", "forged snippet"); }
            else if (field.equals("eventSequence")) { ((ObjectNode) entry.path("archivedBody")).put(field, 999); }
            else { ((ObjectNode) entry.path("archivedBody")).put(field, "0".repeat(64)); }
            Files.writeString(checkpoint, json.writeValueAsString(forged));
            try (var store = new FileSessionStore(data())) {
                assertThrows(java.io.IOException.class, () -> store.restore(id, workspace(), 6));
            }
        }
    }

    @Test
    void validatesProjectionBuiltFromArchivedHistoryOnRestore() throws Exception {
        Files.writeString(root.resolve("Main.java"), body());
        UUID id;
        try (var store = new FileSessionStore(data())) {
            store.bindWorkspace(workspace());
            var session = new AgentSession("mixed results", store);
            store.create(session);
            var turns = new AtomicInteger();
            Tool small = new Tool() {
                public String name() { return "small"; }
                public ToolResult execute(Map<String, String> args) { return new ToolResult(true, "done"); }
            };
            new AgentRuntime(request -> {
                int turn = turns.getAndIncrement();
                return ModelResponse.callTool(turn == 0 ? read("first") : new ToolCall("small-" + turn, "small", Map.of()));
            }, List.of(new ReadFileTool(workspace()), small), 6).run(session);
            assertNotNull(session.contextProjection());
            assertNotNull(session.history().getFirst().archivedBody());
            store.saveSnapshot(session);
            id = session.id();
        }
        try (var store = new FileSessionStore(data())) {
            var restored = store.restore(id, workspace(), 7);
            restored.contextProjection().validateAgainst(restored.history());
            assertTrue(restored.history().getLast().result().content().equals("done"));
        }
    }

    @Test
    void archiveReadFailureIsFatalAndDoesNotRepopulateHistoryFromExcerpt() throws Exception {
        UUID id = archivedStop();
        try (var store = new FileSessionStore(data())) {
            var restored = store.restore(id, workspace(), 6);
            Path log = data().resolve(id.toString()).resolve("events.jsonl");
            String contents = Files.readString(log);
            Files.writeString(log, contents.substring(0, contents.length() - 1));
            assertThrows(PersistenceException.class, () -> restored.originalObservation("read-0"));
            assertFalse(restored.history().getFirst().result().content().contains(QUOTE));
        }
    }
}
