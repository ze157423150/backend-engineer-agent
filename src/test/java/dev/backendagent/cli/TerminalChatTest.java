package dev.backendagent.cli;

import java.io.BufferedReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import dev.backendagent.model.*;
import dev.backendagent.runtime.*;
import dev.backendagent.tools.Tool;
import dev.backendagent.tools.ToolDefinition;
import dev.backendagent.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class TerminalChatTest {
    BufferedReader input(String text) { return new BufferedReader(new StringReader(text)); }
    final StringWriter text = new StringWriter();
    PrintWriter output() { return new PrintWriter(text, true); }
    final AtomicInteger saves = new AtomicInteger();
    TerminalChat chat(String commands, int limit, ModelClient model) {
        return new TerminalChat(input(commands), output(), limit,
                cap -> new AgentRuntime(model, List.of(), cap), session -> saves.incrementAndGet());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/home/agent/backend-engineer-agent/examples/test-repair/test.java的文件是leetCode的239题，请修复bug",
        "  /home/agent/backend-engineer-agent/examples/test-repair/test.java 请修复bug",
        "/help/src/Main.java 请分析这个文件",
        "/test.java 请分析这个文件"
    })
    void pathPrefixedFirstMessageIsNotSwallowedByCommandParsing(String message) throws Exception {
        assertEquals(message, TerminalChat.readFirstMessage(input("/help\n" + message + "\n"), output()));
        assertTrue(text.toString().contains("允许以文件路径开头"));
    }

    @Test void pathPrefixedFollowUpReachesRuntimeAsAnUnchangedUserMessage() throws Exception {
        String message = "/home/agent/backend-engineer-agent/examples/test-repair/test.java 请继续修复";
        var session = new AgentSession("first");
        var calls = new AtomicInteger();
        chat(message + "\n/budget\t8\n/status\n/unknown\n/exit\n", 5, request -> {
            if (calls.getAndIncrement() > 0) assertEquals(message, request.turns().getLast().getUserMessage());
            return ModelResponse.finish("done");
        }).run(session, null, false);
        assertEquals(2, session.turns().size()); assertEquals(2, session.modelCalls());
        assertEquals(message, session.turns().getLast().getUserMessage());
        assertTrue(text.toString().contains("调用 2/8"));
    }

    @Test void twoMessagesUseSameSessionAndDoNotRepeatOldAnswerOrEventTrace() throws Exception {
        var session = new AgentSession("first");
        var calls = new AtomicInteger();
        var chat = chat("\n/status\nsecond\n/exit\n", 10, request -> {
            if (calls.getAndIncrement() == 0) return ModelResponse.finish("FIRST_ANSWER");
            assertEquals("first", request.objective());
            assertEquals("second", request.turns().getLast().getUserMessage());
            assertEquals(2, request.turns().size());
            assertEquals("FIRST_ANSWER", request.turns().getFirst().getAnswer());
            return ModelResponse.finish("SECOND_ANSWER");
        });
        chat.run(session, null, false);
        assertEquals(2, session.turns().size()); assertEquals(2, session.modelCalls());
        assertEquals(2, saves.get());
        assertEquals(1, text.toString().split("FIRST_ANSWER", -1).length - 1);
        assertFalse(text.toString().contains("MODEL_CALL_STARTED"));
    }

    @Test void eofAndExitBeforeFirstTaskCreateNoMessage() throws Exception {
        assertNull(TerminalChat.readFirstMessage(input(""), output()));
        assertNull(TerminalChat.readFirstMessage(input("/exit\n"), output()));
        assertEquals("first", TerminalChat.readFirstMessage(input("\n/help\n" + "x".repeat(8001) + "\nfirst\n"), output()));
    }

    @Test void changingBudgetAllowsNextTurnWithoutResettingUsedCalls() throws Exception {
        var session = new AgentSession("first");
        chat("second\n/budget nope\n/budget 1\n/budget 3\nsecond\n", 1,
                request -> ModelResponse.finish("done")).run(session, null, false);
        assertEquals(2, session.modelCalls()); assertEquals(2, session.turns().size());
        assertTrue(text.toString().contains("调用额度不足"));
        assertTrue(text.toString().contains("调用 2/3"));
    }

    @Test void failedTurnRejectsNewMessageAndResumeKeepsOriginalTurn() throws Exception {
        var session = new AgentSession("first");
        var calls = new AtomicInteger();
        chat("must-not-be-added\n/resume\nnext\n/exit\n", 5, request -> {
            int call = calls.getAndIncrement();
            if (call == 0) throw new IllegalStateException("interrupted");
            assertEquals("first", request.objective());
            assertEquals(call == 1 ? "first" : "next", request.turns().getLast().getUserMessage());
            return ModelResponse.finish("done");
        }).run(session, null, false);
        assertEquals(3, session.modelCalls()); assertEquals(2, session.turns().size());
        assertTrue(text.toString().contains("新需求未添加"));
        assertTrue(text.toString().contains("失败原因：IllegalStateException: interrupted"));
        assertEquals("first", session.turns().getFirst().getUserMessage());
    }

    @Test void budgetStopResumesPendingTurnBeforeAcceptingNewRequirement() throws Exception {
        var session = new AgentSession("first");
        Tool tool = new Tool() {
            public String name() { return "inspect"; }
            public ToolDefinition definition() { return new ToolDefinition(name(), "inspect", Map.of()); }
            public ToolResult execute(Map<String,String> args) { return new ToolResult(true, "observed"); }
        };
        var chat = new TerminalChat(input("ignored\n/resume\n/budget 3\n/resume\n/exit\n"), output(), 1,
                cap -> new AgentRuntime(request -> request.history().isEmpty()
                    ? ModelResponse.callTool(new ToolCall("read", "inspect", Map.of())) : ModelResponse.finish("done"),
                    List.of(tool), cap), s -> saves.incrementAndGet());
        chat.run(session, null, false);
        assertEquals(AgentSession.Status.COMPLETED, session.status());
        assertEquals(1, session.turns().size()); assertEquals(2, session.modelCalls());
        assertEquals(1, session.history().size());
    }

    @Test void reopeningCompletedSessionDoesNotCallModelUntilNextInput() throws Exception {
        var session = new AgentSession("first");
        new AgentRuntime(request -> ModelResponse.finish("done"), List.of(), 2).run(session);
        chat("/status\n/help\n/unknown\n/exit\n", 5,
                request -> { fail("No request expected"); return null; }).run(session, null, false);
        assertEquals(1, session.modelCalls()); assertEquals(0, saves.get());
    }

    @Test void longMessageIsRejectedWithoutAddingTurn() throws Exception {
        var session = new AgentSession("first");
        chat("x".repeat(8001) + "\n/exit\n", 5, request -> ModelResponse.finish("done")).run(session, null, false);
        assertEquals(1, session.turns().size()); assertEquals(1, session.modelCalls());
    }

    @Test void persistenceFailureStopsConsoleBeforeReadingAnotherRequirement() {
        var session = new AgentSession("first");
        var chat = new TerminalChat(input("next\n"), output(), 5,
                cap -> new AgentRuntime(request -> ModelResponse.finish("done"), List.of(), cap),
                s -> { throw new PersistenceException("storage unavailable", null); });
        assertThrows(PersistenceException.class, () -> chat.run(session, null, false));
        assertEquals(1, session.modelCalls()); assertEquals(1, session.turns().size());
    }
    @Test void diffAndApplyAreTerminalActionsWithoutExtraModelCalls(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var original = java.nio.file.Files.createDirectory(root.resolve("source"));
        var sessionDir = java.nio.file.Files.createDirectory(root.resolve("session"));
        var isolated = java.nio.file.Files.createDirectory(sessionDir.resolve("workspace"));
        java.nio.file.Files.writeString(original.resolve("Main.java"), "old");
        java.nio.file.Files.writeString(isolated.resolve("Main.java"), "old");
        var source = new dev.backendagent.tools.Workspace(original, original.resolve("agent-local.properties"));
        var copy = new dev.backendagent.tools.Workspace(isolated, isolated.resolve("agent-local.properties"));
        assertTrue(copy.applyPatch("Main.java", "old", "new").successful());
        var sync = new dev.backendagent.tools.WorkspaceSynchronizer(source, copy, sessionDir);
        var calls = new AtomicInteger();
        var chat = new TerminalChat(input("/diff\n/apply\n/diff\n/exit\n"), output(), 10,
                limit -> new AgentRuntime(request -> { calls.incrementAndGet(); return ModelResponse.finish("done"); }, List.of(), limit),
                session -> saves.incrementAndGet(), sync);
        var session = new AgentSession("fix");
        chat.run(session, null, false);
        assertEquals(1, calls.get());
        assertEquals(1, saves.get());
        assertEquals(1, session.turns().size());
        assertEquals("new", java.nio.file.Files.readString(original.resolve("Main.java")));
        assertTrue(text.toString().contains("修改位于隔离副本"));
        assertTrue(text.toString().contains("没有待写回"));
    }

    @Test void applyCannotWriteAnUnfinishedRound(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var original = java.nio.file.Files.createDirectory(root.resolve("source"));
        var sessionDir = java.nio.file.Files.createDirectory(root.resolve("session"));
        var isolated = java.nio.file.Files.createDirectory(sessionDir.resolve("workspace"));
        java.nio.file.Files.writeString(original.resolve("Main.java"), "old");
        java.nio.file.Files.writeString(isolated.resolve("Main.java"), "old");
        var source = new dev.backendagent.tools.Workspace(original, original.resolve("agent-local.properties"));
        var copy = new dev.backendagent.tools.Workspace(isolated, isolated.resolve("agent-local.properties"));
        assertTrue(copy.applyPatch("Main.java", "old", "new").successful());
        var sync = new dev.backendagent.tools.WorkspaceSynchronizer(source, copy, sessionDir);
        var chat = new TerminalChat(input("/apply\n/exit\n"), output(), 1,
                limit -> new AgentRuntime(request -> ModelResponse.callTool(new ToolCall("unknown", "unknown", Map.of())), List.of(), limit),
                session -> saves.incrementAndGet(), sync);
        var session = new AgentSession("fix");
        chat.run(session, null, false);
        assertEquals(AgentSession.Status.BUDGET_EXHAUSTED, session.status());
        assertEquals("old", java.nio.file.Files.readString(original.resolve("Main.java")));
        assertFalse(java.nio.file.Files.exists(sessionDir.resolve("source-sync.json")));
        assertTrue(text.toString().contains("当前轮尚未完成，不能写回"));
    }

}
