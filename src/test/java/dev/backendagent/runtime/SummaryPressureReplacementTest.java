package dev.backendagent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.backendagent.model.*;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SummaryPressureReplacementTest {
    private AgentSession session() {
        var session = new AgentSession("inspect");
        for (int i = 1; i <= 8; i++) session.remember(new ToolExchange(
                new ToolCall("read-" + i, "read_file", Map.of("path", "A" + i + ".java")),
                new ToolResult(true, "evidence-" + i + "\n" + "x".repeat(1000)), null, i));
        return session;
    }
    private ContextSummary summary(int covered) {
        return new ContextSummary(1, covered, List.of(new SummaryNote(SummaryNote.Kind.PROGRESS,
                "first observation", "read-1", "evidence-1")));
    }
    private List<String> ids(ModelRequest request) {
        return request.history().stream().map(e -> e.call().id()).toList();
    }
    private AgentRuntime runtime(long fixedBytes) {
        return new AgentRuntime(new ModelClient() {
            public ModelResponse execute(ModelRequest request) { throw new AssertionError("Offline only"); }
            public RequestBudgetReport inspectRequest(ModelRequest request) {
                return new RequestBudgetReport(fixedBytes + request.historyCharacters(), 4096, 1024, 65536);
            }
        }, List.of(), 20, new ContextBudget(64000), s -> {}, false);
    }
    private ModelRequest prepare(AgentRuntime runtime, AgentSession session) throws Exception {
        var prepare = AgentRuntime.class.getDeclaredMethod("prepareContext", AgentSession.class);
        prepare.setAccessible(true);
        var result = prepare.invoke(runtime, session);
        var request = result.getClass().getDeclaredMethod("request"); request.setAccessible(true);
        return (ModelRequest) request.invoke(result);
    }

    @Test void shortHistoryIsReplacedOnlyInPressureProjectionAndOldVersionsRemainValid() throws Exception {
        var session = session(); var summary = summary(4);
        var assembler = new ContextAssembler(new ContextBudget(64000));
        var ordinary = assembler.preview(session, List.of(), 10, summary);
        assertEquals(8, ordinary.history().size()); assertNull(ordinary.contextSummary());
        var legacy = assembler.project(session.history(), 8, 4);
        assertEquals(8, legacy.getHistory().size()); legacy.validateAgainst(session.history());
        var pressure = assembler.previewForRequestPressure(session, List.of(), 10, summary, null);
        assertEquals(List.of("read-5", "read-6", "read-7", "read-8"), ids(pressure));
        assertNotNull(pressure.contextSummary());
        assertEquals(session.history().subList(4, 8), pressure.history());
        var json = new ObjectMapper();
        json.readValue(json.writeValueAsString(pressure.contextProjection()), ContextProjection.class)
                .validateAgainst(session.history());
        assertEquals(8, session.history().size()); // Archive/ledger was not deleted.
    }

    @Test void uncoveredMiddleRecordsAndRecentFourBatchesRemainRaw() {
        var session = session(); var assembler = new ContextAssembler(new ContextBudget(64000));
        var pressure = assembler.previewForRequestPressure(session, List.of(), 10, summary(2), null);
        assertEquals(List.of("read-3", "read-4", "read-5", "read-6", "read-7", "read-8"), ids(pressure));
        var noSummary = assembler.previewForRequestPressure(session, List.of(), 10, null, null);
        assertEquals(8, noSummary.history().size());
    }

    @Test void coverageCannotRemoveRecentProtectedBatches() {
        var session = session();
        var pressure = new ContextAssembler(new ContextBudget(64000))
                .previewForRequestPressure(session, List.of(), 10, summary(8), null);
        assertEquals(List.of("read-5", "read-6", "read-7", "read-8"), ids(pressure));
    }

    @Test void finalRequestUsesReplacementOnlyAfterCompleteRequestExceedsBudget() throws Exception {
        var session = session(); session.installSummary(summary(4));
        var within = prepare(runtime(1000), session);
        assertEquals(8, within.history().size()); assertEquals(7, within.contextProjection().getPolicyVersion());
        var pressured = prepare(runtime(55000), session);
        assertEquals(List.of("read-5", "read-6", "read-7", "read-8"), ids(pressured));
        assertNotNull(pressured.contextSummary()); assertEquals(9, pressured.contextProjection().getPolicyVersion());
        assertTrue(new RequestBudgetReport(55000 + pressured.historyCharacters(), 4096, 1024, 65536).isWithinBudget());
        pressured.contextProjection().validateAgainst(session.history());
    }

    @Test void installationStillRejectsNoGainAndDoesNotChangeSessionState() throws Exception {
        var session = session(); var old = summary(2); session.installSummary(old);
        var method = AgentRuntime.class.getDeclaredMethod("requirePressureGain", AgentSession.class,
                ContextSummary.class, ConversationSummary.class); method.setAccessible(true);
        var runtime = runtime(55000);
        method.invoke(runtime, session, summary(4), null);
        var failure = assertThrows(InvocationTargetException.class, () -> method.invoke(runtime, session, old, null));
        assertEquals("Summary does not reduce outgoing request", failure.getCause().getMessage());
        assertSame(old, session.contextSummary()); assertEquals(8, session.history().size());
    }
}
