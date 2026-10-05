package dev.backendagent.memory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.backendagent.model.ModelResponse;
import dev.backendagent.model.ToolCall;
import dev.backendagent.persistence.FileSessionStore;
import dev.backendagent.persistence.PersistenceException;
import dev.backendagent.runtime.AgentSession;
import dev.backendagent.runtime.AgentRuntime;
import dev.backendagent.tools.ReadFileTool;
import dev.backendagent.tools.RememberFactTool;
import dev.backendagent.tools.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MemoryLoaderTest {
    @TempDir Path root;

    @Test
    void deserializesNotesRevalidatesEvidenceAndSuppliesFirstModelRequest() throws Exception {
        var store = store();
        var source = createSource(store);
        String original = Files.readString(store.sessionDirectory(source.id()).resolve("session.json"));
        var destination = new AgentSession("new objective", store);
        store.create(destination);
        var report = new MemoryLoader().load(new FileSessionStore(root.resolve("sessions")), source.id(), workspace(), destination);
        assertEquals(2, report.getLoaded());
        assertEquals(0, report.getSkipped());
        assertEquals(AgentSession.Status.CREATED, destination.status());
        assertEquals(0, destination.modelCalls());
        assertNull(destination.answer());
        assertNotEquals(source.id(), destination.id());
        assertEquals(original, Files.readString(store.sessionDirectory(source.id()).resolve("session.json")));
        new AgentRuntime(request -> {
            assertEquals("new objective", request.objective());
            assertEquals(2, request.workingMemory().size());
            assertEquals(2, request.history().size());
            assertTrue(request.workingMemory().getFirst().getSourceCallId().startsWith("memory-load-"));
            assertEquals(request.history().getFirst().call().id(), request.workingMemory().getFirst().getSourceCallId());
            return ModelResponse.finish("used imported notes");
        }, List.of(), 1).run(destination);
        store.saveSnapshot(destination);
        var saved = store.read(destination.id());
        assertEquals(2, saved.path("snapshot").path("workingMemory").size());
        assertEquals("WORKING_MEMORY_LOADED", saved.path("events").get(0).path("type").asText());
        assertEquals(source.id().toString(), saved.path("events").get(0).path("payload").path("sourceSessionId").asText());
    }

    @Test
    void skipsChangedAndMissingFilesRatherThanTrustingOldQuotes() throws Exception {
        var store = store();
        var source = createSource(store);
        Files.writeString(root.resolve("Main.java"), "class Changed {}\n");
        var destination = new AgentSession("new task");
        var report = new MemoryLoader().load(store, source.id(), workspace(), destination);
        assertEquals(1, report.getLoaded());
        assertEquals(1, report.getSkipped());
        assertEquals("Other.java", destination.memoryFacts().getFirst().getSourcePath());
        Files.delete(root.resolve("Other.java"));
        var empty = new AgentSession("another task");
        report = new MemoryLoader().load(store, source.id(), workspace(), empty);
        assertEquals(0, report.getLoaded());
        assertEquals(2, report.getSkipped());
        assertTrue(empty.memoryFacts().isEmpty());
    }

    @Test
    void rejectsStaleSnapshotsAndSkipsForgedSavedEvidence() throws Exception {
        var store = store();
        var source = createSource(store);
        var json = new ObjectMapper();
        Path path = store.sessionDirectory(source.id()).resolve("session.json");
        ObjectNode snapshot = (ObjectNode) json.readTree(Files.readString(path));
        ((ObjectNode) snapshot.path("workingMemory").get(0)).put("evidenceQuote", "forged evidence");
        Files.writeString(path, snapshot.toPrettyString());
        var report = new MemoryLoader().load(store, source.id(), workspace(), new AgentSession("new"));
        assertEquals(1, report.getLoaded());
        assertEquals(1, report.getSkipped());
        snapshot.put("lastEventSequence", 0);
        Files.writeString(path, snapshot.toPrettyString());
        assertThrows(IOException.class, () -> new MemoryLoader().load(store, source.id(), workspace(), new AgentSession("new")));
    }

    @Test
    void loadingRespectsCurrentProtectedPathsAndPropagatesPersistenceFailure() throws Exception {
        var store = store();
        var source = createSource(store);
        var protectedWorkspace = new Workspace(root, root.resolve("Main.java"), root.resolve("sessions"));
        var report = new MemoryLoader().load(store, source.id(), protectedWorkspace, new AgentSession("new"));
        assertEquals(1, report.getSkipped());
        var failing = new AgentSession("new", (session, event, payload) -> {
            throw new PersistenceException("disk failure", null);
        });
        assertThrows(PersistenceException.class, () -> new MemoryLoader().load(store, source.id(), workspace(), failing));
    }

    @Test
    void refreshIdsRemainProtectedAgainstDuplicateModelToolCalls() throws Exception {
        var store = store();
        var source = createSource(store);
        var destination = new AgentSession("new");
        new MemoryLoader().load(store, source.id(), workspace(), destination);
        String id = destination.history().getFirst().call().id();
        new AgentRuntime(request -> ModelResponse.callTool(new ToolCall(id, "read_file", Map.of())), List.of(), 2).run(destination);
        assertEquals(AgentSession.Status.FAILED, destination.status());
        assertTrue(destination.events().getLast().detail().contains("Duplicate tool call id"));
    }

    private AgentSession createSource(FileSessionStore store) throws Exception {
        Files.writeString(root.resolve("Main.java"), "class Main {}\n");
        Files.writeString(root.resolve("Other.java"), "class Other {}\n");
        var session = new AgentSession("old task", store);
        store.create(session);
        var turns = new AtomicInteger();
        new AgentRuntime(request -> switch (turns.getAndIncrement()) {
            case 0 -> ModelResponse.callTools(List.of(read("read-main", "Main.java"), read("read-other", "Other.java")), null);
            case 1 -> ModelResponse.callTools(List.of(note("note-main", "read-main", "class Main {}"),
                    note("note-other", "read-other", "class Other {}")), null);
            default -> ModelResponse.finish("done");
        }, List.of(new ReadFileTool(workspace()), new RememberFactTool(session)), 3).run(session);
        store.saveSnapshot(session);
        return session;
    }

    @Test
    void reexecutesSavedSearchAndImportsItsCurrentEvidence() throws Exception {
        Files.writeString(root.resolve("Main.java"), "class Main {}\n");
        var store = store();
        var source = new AgentSession("search", store);
        store.create(source);
        var turns = new AtomicInteger();
        new AgentRuntime(request -> switch (turns.getAndIncrement()) {
            case 0 -> ModelResponse.callTool(new ToolCall("search", "search_code", Map.of("path", ".", "query", "class Main")));
            case 1 -> ModelResponse.callTool(note("note", "search", "Main.java:1: class Main {}"));
            default -> ModelResponse.finish("done");
        }, List.of(new dev.backendagent.tools.SearchCodeTool(workspace()), new RememberFactTool(source)), 3).run(source);
        store.saveSnapshot(source);
        var destination = new AgentSession("new task");
        var report = new MemoryLoader().load(store, source.id(), workspace(), destination);
        assertEquals(1, report.getLoaded());
        assertEquals("search_code", destination.history().getFirst().call().name());
        assertEquals("class Main", destination.history().getFirst().call().arguments().get("query"));
    }
    private ToolCall read(String id, String path) {
        return new ToolCall(id, "read_file", Map.of("path", path, "start_line", "1", "end_line", "20"));
    }
    private ToolCall note(String id, String source, String quote) {
        return new ToolCall(id, "remember_fact", Map.of("statement", "found " + quote,
                "source_call_id", source, "evidence_quote", quote));
    }
    private FileSessionStore store() { return new FileSessionStore(root.resolve("sessions")); }
    private Workspace workspace() throws IOException {
        return new Workspace(root, root.resolve("agent-local.properties"), root.resolve("sessions"));
    }
}
