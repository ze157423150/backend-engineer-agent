package dev.backendagent.runtime;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import dev.backendagent.model.ModelResponse;
import dev.backendagent.model.ToolCall;
import dev.backendagent.model.ToolExchange;
import dev.backendagent.model.ToolResult;
import dev.backendagent.tools.Tool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContextAssemblerTest {
    private final ContextBudget measure = new ContextBudget(1000);

    @Test
    void retainsAllHistoryAtExactBudgetAndKeepsObjective() {
        var session = new AgentSession("inspect");
        var first = exchange("a", 1, "old");
        var second = exchange("b", 2, "new");
        session.remember(first);
        session.remember(second);
        int size = (int) (measure.measure(first) + measure.measure(second));
        var request = assemble(session, size);
        assertEquals(List.of(first, second), request.history());
        assertEquals(size, request.historyCharacters());
        assertEquals(0, request.omittedExchanges());
        assertEquals("inspect", request.objective());
    }

    @Test
    void dropsWholeOlderBatchEvenWhenOneOfItsCallsWouldFit() {
        var session = new AgentSession("inspect");
        var olderA = exchange("a", 1, "old");
        var olderB = exchange("b", 1, "old");
        var latest = exchange("c", 2, "new");
        session.remember(olderA);
        session.remember(olderB);
        session.remember(latest);
        var request = assemble(session, (int) (measure.measure(latest) + measure.measure(olderB)));
        assertEquals(List.of(latest), request.history());
        assertEquals(2, request.omittedExchanges());
        assertEquals(List.of(olderA, olderB, latest), session.history());
        assertThrows(UnsupportedOperationException.class, () -> request.history().clear());
    }

    @Test
    void neverSplitsLatestBatchToFitBudget() {
        var session = new AgentSession("inspect");
        var first = exchange("a", 1, "data");
        session.remember(first);
        session.remember(exchange("b", 1, "data"));
        var failure = assertThrows(IllegalStateException.class,
                () -> assemble(session, (int) measure.measure(first)));
        assertTrue(failure.getMessage().contains("Latest tool batch"));
        assertEquals(2, session.history().size());
    }

    @Test
    void standaloneLegacyExchangesAreNotGroupedTogether() {
        var session = new AgentSession("inspect");
        session.remember(exchange("a", 0, "old"));
        var latest = exchange("b", 0, "new");
        session.remember(latest);
        assertEquals(List.of(latest), assemble(session, (int) measure.measure(latest)).history());
    }

    @Test
    void emptyHistoryFitsAndInvalidBudgetIsRejected() {
        var request = assemble(new AgentSession("inspect"), 1);
        assertTrue(request.history().isEmpty());
        assertEquals(0, request.historyCharacters());
        assertThrows(IllegalArgumentException.class, () -> new ContextBudget(0));
    }

    @Test
    void oversizedLatestResultStopsBeforeAnotherModelRequestAndPreservesHistory() {
        var calls = new AtomicInteger();
        Tool tool = new Tool() {
            public String name() { return "large"; }
            public ToolResult execute(Map<String, String> arguments) {
                return new ToolResult(true, "x".repeat(100));
            }
        };
        var session = new AgentSession("inspect");
        new AgentRuntime(request -> {
            calls.incrementAndGet();
            return ModelResponse.callTool(new ToolCall("a", "large", Map.of()));
        }, List.of(tool), 3, new ContextBudget(10)).run(session);
        assertEquals(AgentSession.Status.FAILED, session.status());
        assertEquals(1, calls.get());
        assertEquals(1, session.modelCalls());
        assertEquals(100, session.history().getFirst().result().content().length());
        assertTrue(session.events().getLast().detail().contains("--max-history-chars"));
    }

    private dev.backendagent.model.ModelRequest assemble(AgentSession session, int limit) {
        return new ContextAssembler(new ContextBudget(limit)).assemble(session, List.of(), 3);
    }

    private ToolExchange exchange(String id, int turn, String content) {
        return new ToolExchange(new ToolCall(id, "read_file", Map.of("path", "Main.java")),
                new ToolResult(true, content), "reading", turn);
    }
}
