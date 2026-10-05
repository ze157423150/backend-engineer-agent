package dev.backendagent.runtime;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import dev.backendagent.model.ModelClient;
import dev.backendagent.model.ModelResponse;
import dev.backendagent.model.ToolCall;
import dev.backendagent.model.ToolResult;
import dev.backendagent.tools.ReadFileTool;
import dev.backendagent.tools.Workspace;
import dev.backendagent.tools.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static dev.backendagent.runtime.AgentSession.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentRuntimeTest {
    @TempDir
    Path repository;
    private Tool readTool;

    @BeforeEach
    void createRepository() throws IOException {
        Files.writeString(repository.resolve("OrderService.java"), "class OrderService { void cancel() { cache.evict(id); } }");
        readTool = new ReadFileTool(new Workspace(repository, repository.resolve("agent-local.properties")));
    }

    @Test
    void toolResultReachesNextModelTurnBeforeCompletion() {
        AtomicInteger turns = new AtomicInteger();
        ModelClient model = request -> {
            assertEquals("inspect cancellation", request.objective());
            assertEquals(List.of("read_file"), request.availableTools().stream()
                    .map(definition -> definition.getName()).toList());
            if (turns.getAndIncrement() == 0) {
                assertTrue(request.history().isEmpty());
                assertEquals(3, request.remainingModelCalls());
                return call("first", "read_file");
            }
            assertEquals(2, request.remainingModelCalls());
            assertEquals(1, request.history().size());
            var exchange = request.history().getFirst();
            assertEquals("first", exchange.call().id());
            assertTrue(exchange.result().successful());
            assertTrue(exchange.result().content().contains("cache.evict(id)"));
            return ModelResponse.finish("inspected");
        };
        var session = new AgentSession("inspect cancellation");
        new AgentRuntime(model, List.of(readTool), 3).run(session);

        assertEquals(COMPLETED, session.status());
        assertEquals("inspected", session.answer());
        assertEquals(2, session.modelCalls());
        assertEquals(List.of(
                AgentSession.EventType.SESSION_STARTED,
                AgentSession.EventType.CONTEXT_ASSEMBLED,
                AgentSession.EventType.MODEL_CALL_STARTED,
                AgentSession.EventType.MODEL_RESPONSE_RECEIVED,
                AgentSession.EventType.TOOL_CALL_REQUESTED,
                AgentSession.EventType.TOOL_EXECUTION_SUCCEEDED,
                AgentSession.EventType.CONTEXT_ASSEMBLED,
                AgentSession.EventType.MODEL_CALL_STARTED,
                AgentSession.EventType.MODEL_RESPONSE_RECEIVED,
                AgentSession.EventType.SESSION_COMPLETED),
                session.events().stream().map(AgentSession.Event::type).toList());
        for (int i = 0; i < session.events().size(); i++) {
            assertEquals(i + 1L, session.events().get(i).sequence());
        }
    }

    @Test
    void repeatedRequestsStopAtBudgetWithoutAnExtraModelCall() {
        AtomicInteger calls = new AtomicInteger();
        ModelClient model = request -> call("call-" + calls.incrementAndGet(), "read_file");
        var session = new AgentSession("never finish");
        new AgentRuntime(model, List.of(readTool), 2).run(session);

        assertEquals(BUDGET_EXHAUSTED, session.status());
        assertEquals(2, calls.get());
        assertEquals(2, session.history().size());
        assertNull(session.answer());
        assertEquals(AgentSession.EventType.BUDGET_EXHAUSTED, session.events().getLast().type());
    }

    @Test
    void unknownToolReturnsFailureContextAndModelCanRespond() {
        ModelClient model = request -> {
            if (request.history().isEmpty()) {
                return call("unknown", "missing_tool");
            }
            assertFalse(request.history().getFirst().result().successful());
            assertTrue(request.history().getFirst().result().content().contains("Unknown tool"));
            return ModelResponse.finish("无法完成任务：所需工具不存在");
        };
        var session = new AgentSession("inspect");
        new AgentRuntime(model, List.of(), 2).run(session);

        // COMPLETED means the loop produced a final response, not that a bug was fixed.
        assertEquals(COMPLETED, session.status());
        assertTrue(session.events().stream().anyMatch(
                event -> event.type() == AgentSession.EventType.TOOL_EXECUTION_FAILED));
    }

    @Test
    void toolExceptionIsFedBackToModel() {
        Tool brokenTool = new Tool() {
            public String name() { return "broken"; }
            public ToolResult execute(Map<String, String> arguments) {
                throw new IllegalStateException("fixture failure");
            }
        };
        ModelClient model = request -> {
            if (request.history().isEmpty()) {
                return call("broken-call", "broken");
            }
            assertFalse(request.history().getFirst().result().successful());
            assertTrue(request.history().getFirst().result().content().contains("fixture failure"));
            return ModelResponse.finish("tool failed");
        };
        var session = new AgentSession("inspect");
        new AgentRuntime(model, List.of(brokenTool), 2).run(session);
        assertEquals(COMPLETED, session.status());
    }

    @Test
    void modelFailureEndsSessionAndCountsAttempt() {
        ModelClient model = request -> { throw new IllegalStateException("model unavailable"); };
        var session = new AgentSession("inspect");
        new AgentRuntime(model, List.of(), 3).run(session);

        assertEquals(FAILED, session.status());
        assertEquals(1, session.modelCalls());
        assertEquals(AgentSession.EventType.SESSION_FAILED, session.events().getLast().type());
    }

    @Test
    void duplicateCallIdFailsBeforeExecutingToolAgain() {
        AtomicInteger executions = new AtomicInteger();
        Tool tool = new Tool() {
            public String name() { return "count"; }
            public ToolResult execute(Map<String, String> arguments) {
                executions.incrementAndGet();
                return new ToolResult(true, "done");
            }
        };
        var session = new AgentSession("repeat a call id");
        new AgentRuntime(request -> call("same-id", "count"), List.of(tool), 3).run(session);

        assertEquals(FAILED, session.status());
        assertEquals(1, executions.get());
        assertTrue(session.events().getLast().detail().contains("Duplicate tool call id"));
    }

    @Test
    void terminalSessionCannotRunAgainAndEventViewsAreImmutable() {
        var session = new AgentSession("inspect");
        var runtime = new AgentRuntime(request -> ModelResponse.finish("done"), List.of(readTool), 3);
        runtime.run(session);
        int eventCount = session.events().size();

        assertThrows(IllegalStateException.class, () -> runtime.run(session));
        assertThrows(UnsupportedOperationException.class, () -> session.events().clear());
        assertThrows(UnsupportedOperationException.class, () -> session.history().clear());
        assertEquals(COMPLETED, session.status());
        assertEquals(eventCount, session.events().size());
    }

    @Test
    void invalidConfigurationAndStateTransitionAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AgentSession(" "));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentRuntime(request -> ModelResponse.finish("done"), List.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> new AgentRuntime(request -> ModelResponse.finish("done"),
                List.of(readTool, readTool), 3));
        var session = new AgentSession("inspect");
        assertThrows(IllegalStateException.class, () -> session.transitionTo(COMPLETED));
        assertEquals(CREATED, session.status());
    }

    private static ModelResponse call(String id, String tool) {
        return ModelResponse.callTool(new ToolCall(id, tool, Map.of(
                "path", "OrderService.java", "start_line", "1", "end_line", "20")));
    }
}
