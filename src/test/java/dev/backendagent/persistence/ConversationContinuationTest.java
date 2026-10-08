package dev.backendagent.persistence;

import dev.backendagent.runtime.*;
import dev.backendagent.model.*;
import dev.backendagent.tools.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ConversationContinuationTest {
    @TempDir Path root;
    private Workspace workspace() throws Exception { return new Workspace(root, root.resolve("agent-local.properties"), root.resolve("sessions")); }
    private UUID firstTurn() throws Exception {
        Files.writeString(root.resolve("rate.txt"), "RATE=90\n");
        try (var store = new FileSessionStore(root.resolve("sessions"))) {
            var workspace = workspace(); store.bindWorkspace(workspace);
            var session = new AgentSession("read rate", store); store.create(session);
            var calls = new AtomicInteger();
            new AgentRuntime(request -> calls.getAndIncrement() == 0
                    ? ModelResponse.callTool(new ToolCall("read1", "read_file", Map.of("path", "rate.txt", "start_line", "1", "end_line", "5")))
                    : ModelResponse.finish("Rate is 90"), List.of(new ReadFileTool(workspace)), 2).run(session);
            store.saveSnapshot(session);
            return session.id();
        }
    }
    @Test void opensFinishedTurnInNewStoreAndKeepsWorkspaceHistoryAndAnswer() throws Exception {
        var id = firstTurn();
        try (var store = new FileSessionStore(root.resolve("sessions"))) {
            var workspace = workspace(); var session = store.openCompleted(id, workspace, 5);
            assertEquals("Rate is 90", session.answer());
            var calls = new AtomicInteger();
            new AgentRuntime(request -> {
                assertEquals("change rate to 80", request.turns().getLast().getUserMessage());
                assertEquals("Rate is 90", request.turns().getFirst().getAnswer());
                return switch(calls.getAndIncrement()) {
                    case 0 -> ModelResponse.callTool(new ToolCall("read2", "read_file", Map.of("path", "rate.txt", "start_line", "1", "end_line", "5")));
                    case 1 -> ModelResponse.callTool(new ToolCall("patch2", "apply_patch", Map.of("path", "rate.txt", "old_text", "90", "new_text", "80")));
                    default -> ModelResponse.finish("Rate is 80");
                };
            }, List.of(new ReadFileTool(workspace), new ApplyPatchTool(workspace, session)), 5).continueConversation(session, "change rate to 80");
            store.saveSnapshot(session);
            assertEquals("RATE=80\n", Files.readString(root.resolve("rate.txt")));
            assertEquals(2, session.turns().size());
            assertEquals(1, session.turns().getLast().getStartHistoryIndex());
            assertEquals(3, session.turns().getLast().getEndHistoryIndex());
            assertTrue(session.staleEvidenceIds().contains("read1"));
            assertEquals(5, session.modelCalls());
            assertEquals(1, session.workspaceState().getRevision());
        }
        try (var store = new FileSessionStore(root.resolve("sessions"))) {
            var loaded = store.openCompleted(id, workspace(), 6);
            assertEquals("Rate is 80", loaded.answer());
            assertEquals(2, loaded.turns().size());
            assertEquals("Rate is 90", loaded.turns().getFirst().getAnswer());
        }
    }
    @Test void budgetResumeContinuesCurrentUserTurnWithoutCreatingAnother() throws Exception {
        var id = firstTurn();
        try (var store = new FileSessionStore(root.resolve("sessions"))) {
            var workspace = workspace(); var session = store.openCompleted(id, workspace, 3);
            new AgentRuntime(request -> ModelResponse.callTool(new ToolCall("read2", "read_file",
                    Map.of("path", "rate.txt", "start_line", "1", "end_line", "5"))), List.of(new ReadFileTool(workspace)), 3)
                    .continueConversation(session, "check rate again");
            assertEquals(AgentSession.Status.BUDGET_EXHAUSTED, session.status());
        }
        try (var store = new FileSessionStore(root.resolve("sessions"))) {
            var session = store.restore(id, workspace(), 4);
            new AgentRuntime(request -> {
                assertEquals(2, request.turns().size());
                assertEquals("check rate again", request.turns().getLast().getUserMessage());
                return ModelResponse.finish("still 90");
            }, List.of(), 4).resume(session);
            assertEquals(2, session.turns().size());
            assertEquals(1, session.events().stream().filter(e -> e.type()==AgentSession.EventType.USER_TURN_STARTED).count());
        }
    }
    @Test void alteredUserMessageIsRejectedBeforeContinuation() throws Exception {
        var id = firstTurn();
        try (var store = new FileSessionStore(root.resolve("sessions"))) {
            var session = store.openCompleted(id, workspace(), 3);
            new AgentRuntime(request -> ModelResponse.finish("second answer"), List.of(), 3).continueConversation(session, "second request");
            var p = store.sessionDirectory(id).resolve("checkpoint.json");
            var json = new com.fasterxml.jackson.databind.ObjectMapper(); var node = json.readTree(p.toFile());
            ((com.fasterxml.jackson.databind.node.ObjectNode)node.path("turns").get(1)).put("userMessage", "forged request");
            Files.writeString(p, node.toString());
        }
        try (var store = new FileSessionStore(root.resolve("sessions"))) {
            var failure = assertThrows(java.io.IOException.class, () -> store.openCompleted(id, workspace(), 4));
            assertTrue(failure.getMessage().contains("Turn messages disagree"));
        }
    }
    @Test void continuationDoesNotReplaceBudgetResumeAndInvalidMessageAddsNoEvent() {
        var session = new AgentSession("task");
        var runtime = new AgentRuntime(request -> ModelResponse.finish("done"), List.of(), 3);
        assertThrows(IllegalStateException.class, () -> runtime.continueConversation(session, "new"));
        runtime.run(session);
        int events = session.events().size();
        assertThrows(IllegalArgumentException.class, () -> runtime.continueConversation(session, " "));
        assertEquals(events, session.events().size());
        assertEquals(1, session.turns().size());
    }
}
