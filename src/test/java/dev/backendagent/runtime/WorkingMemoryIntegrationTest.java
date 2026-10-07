package dev.backendagent.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import dev.backendagent.model.ModelResponse;
import dev.backendagent.model.ToolCall;
import dev.backendagent.model.ToolExchange;
import dev.backendagent.model.ToolResult;
import dev.backendagent.tools.ReadFileTool;
import dev.backendagent.tools.RememberFactTool;
import dev.backendagent.tools.Workspace;
import dev.backendagent.tools.CreateFileTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class WorkingMemoryIntegrationTest {
    @TempDir Path repository;

    @Test
    void memorySurvivesHistoryCroppingAndIsScopedToSession() throws IOException {
        Files.writeString(repository.resolve("Main.java"), "// supporting context for the entry point\n".repeat(30)
                + "class Main { public static void main(String[] args) {} }\n");
        var session = new AgentSession("inspect entry point");
        var read = new ReadFileTool(new Workspace(repository, repository.resolve("agent-local.properties")));
        var call = new ToolCall("read-1", "read_file", Map.of("path", "Main.java", "start_line", "1", "end_line", "40"));
        int budget = (int) new ContextBudget(10000).measure(new ToolExchange(call, read.execute(call.arguments())));
        var turns = new AtomicInteger();
        new AgentRuntime(request -> {
            switch (turns.getAndIncrement()) {
                case 0: return ModelResponse.callTool(call);
                case 1: return ModelResponse.callTool(new ToolCall("memory-1", "remember_fact", Map.of(
                        "statement", "Main contains the main entry point", "source_call_id", "read-1",
                        "evidence_quote", "public static void main(String[] args)")));
                default:
                    assertEquals(1, request.omittedExchanges());
                    assertEquals("remember_fact", request.history().getFirst().call().name());
                    assertEquals(1, request.workingMemory().size());
                    assertEquals("read-1", request.workingMemory().getFirst().getSourceCallId());
                    assertEquals("Main.java", request.workingMemory().getFirst().getSourcePath());
                    return ModelResponse.finish("done");
            }
        }, List.of(read, new RememberFactTool(session)), 3, new ContextBudget(budget)).run(session);
        assertEquals(AgentSession.Status.COMPLETED, session.status());
        assertEquals(2, session.history().size());
        assertEquals(1, session.memoryFacts().size());
        assertTrue(new AgentSession("another task").memoryFacts().isEmpty());
        assertTrue(session.events().stream().anyMatch(event -> event.type() == AgentSession.EventType.WORKING_MEMORY_UPDATED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"fabricated quote", "", " "})
    void invalidQuoteCannotEnterMemory(String quote) {
        var session = preparedSession("read_file", true);
        assertThrows(IllegalArgumentException.class, () -> session.saveFact("note", "read-1", quote));
        assertTrue(session.memoryFacts().isEmpty());
        assertTrue(session.events().isEmpty());
    }

    @Test
    void rejectsFailedUnknownAndMemoryToolSources() {
        assertThrows(IllegalArgumentException.class,
                () -> preparedSession("read_file", false).saveFact("note", "read-1", "code"));
        assertThrows(IllegalArgumentException.class,
                () -> preparedSession("remember_fact", true).saveFact("note", "read-1", "code"));
        assertThrows(IllegalArgumentException.class,
                () -> preparedSession("list_files", true).saveFact("note", "read-1", "code"));
        assertThrows(IllegalArgumentException.class,
                () -> preparedSession("read_file", true).saveFact("note", "missing", "code"));
    }

    @Test
    void successfulSearchCanSupplyEvidenceAndMemoryIsNotMutableOutsideExecution() {
        var session = preparedSession("search_code", true);
        session.saveFact("search finding", "read-1", "code");
        assertEquals("search_code", session.memoryFacts().getFirst().getSourceTool());
        assertThrows(UnsupportedOperationException.class, () -> session.memoryFacts().clear());
        session.transitionTo(AgentSession.Status.RUNNING);
        assertThrows(IllegalStateException.class, () -> session.saveFact("note", "read-1", "code"));
    }

    @Test
    void invalidEvidenceIsReturnedAsToolFailureWithoutEndingSession() {
        var session = new AgentSession("inspect");
        var turns = new AtomicInteger();
        new AgentRuntime(request -> {
            if (turns.getAndIncrement() == 0) {
                return ModelResponse.callTool(new ToolCall("memory-1", "remember_fact", Map.of(
                        "statement", "unsupported", "source_call_id", "missing", "evidence_quote", "invented")));
            }
            assertFalse(request.history().getFirst().result().successful());
            assertTrue(request.workingMemory().isEmpty());
            return ModelResponse.finish("cannot establish fact");
        }, List.of(new RememberFactTool(session)), 2).run(session);
        assertEquals(AgentSession.Status.COMPLETED, session.status());
        assertTrue(session.memoryFacts().isEmpty());
    }

    private AgentSession preparedSession(String tool, boolean successful) {
        var session = new AgentSession("inspect");
        session.transitionTo(AgentSession.Status.RUNNING);
        session.remember(new ToolExchange(new ToolCall("read-1", tool, Map.of("path", "Main.java")),
                new ToolResult(successful, "1: code"), null, 1));
        session.transitionTo(AgentSession.Status.WAITING_FOR_TOOL);
        return session;
    }

    @Test
    void creatingFileInvalidatesEarlierSearchEvidence() throws IOException {
        var session = preparedSession("search_code", true);
        session.saveFact("search finding", "read-1", "code");
        var workspace = new Workspace(repository, repository.resolve("agent-local.properties"));
        var result = new CreateFileTool(workspace, session).execute(Map.of("path", "Added.java", "content", "class Added {}"));
        assertTrue(result.successful());
        assertTrue(session.memoryFacts().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> session.saveFact("stale", "read-1", "code"));
    }

    @Test
    void patchInvalidatesAllSearchEvidenceButKeepsUnrelatedFileNotes() throws IOException {
        var session = preparedSession("read_file", true);
        session.remember(new ToolExchange(new ToolCall("search-1", "search_code", Map.of("path", ".")),
                new ToolResult(true, "Main.java:1: code"), null, 2));
        Files.writeString(repository.resolve("Other.java"), "other code");
        var workspace = new Workspace(repository, repository.resolve("agent-local.properties"));
        session.remember(new ToolExchange(new ToolCall("other-1", "read_file", Map.of("path", "Other.java")),
                workspace.readFile("Other.java", "1", "10"), null, 3));
        session.saveFact("main note", "read-1", "code");
        session.saveFact("search note", "search-1", "code");
        session.saveFact("other note", "other-1", "other code");
        session.invalidateFileEvidence("./Main.java");
        assertEquals(1, session.memoryFacts().size());
        assertEquals("other-1", session.memoryFacts().getFirst().getSourceCallId());
        assertFalse(session.hasCurrentRead("Main.java"));
        assertTrue(session.hasCurrentRead("Other.java"));
        assertThrows(IllegalArgumentException.class, () -> session.saveFact("stale", "search-1", "code"));
    }
}
