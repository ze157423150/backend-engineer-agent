package dev.backendagent.runtime;

import dev.backendagent.model.*;
import dev.backendagent.persistence.FileSessionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SummaryRejectionTest {
    @TempDir Path root;

    private AgentSession run(java.util.function.Function<SummaryRequest, List<SummaryNote>> summary) {
        return run(summary, 3000);
    }

    private AgentSession run(java.util.function.Function<SummaryRequest, List<SummaryNote>> summary, int padding) {
        var store = new FileSessionStore(root);
        var session = new AgentSession("inspect", store);
        store.create(session);
        for (int i = 0; i < 8; i++) {
            session.remember(new ToolExchange(new ToolCall("read-" + i, "read_file", Map.of("path", "Main.java")),
                    new ToolResult(true, "evidence " + i + "\n" + "x".repeat(padding)), null, i + 1));
        }
        ModelClient model = new ModelClient() {
            public boolean supportsSummarization() { return true; }
            public List<SummaryNote> summarize(SummaryRequest request) { return summary.apply(request); }
            public ModelResponse execute(ModelRequest request) { return ModelResponse.finish("continued"); }
        };
        new AgentRuntime(model, List.of(), 3, new ContextBudget(64000), 1, message -> { }).run(session);
        assertEquals(AgentSession.Status.COMPLETED, session.status());
        assertNull(session.contextSummary());
        assertEquals(2, session.modelCalls());
        return session;
    }

    private com.fasterxml.jackson.databind.JsonNode rejection(AgentSession session) throws java.io.IOException {
        var events = new FileSessionStore(root).read(session.id()).path("events");
        for (var event : events) {
            if (event.path("type").asText().equals("COMPACTION_FAILED")) return event;
        }
        fail("Missing persisted rejection");
        return null;
    }

    @Test void quoteFailurePersistsStageReasonAndBatchWithoutModelText() throws Exception {
        var session = run(request -> List.of(new SummaryNote(SummaryNote.Kind.PROGRESS,
                "sensitive summary text", "read-0", "invented secret quotation")));
        var event = rejection(session);
        var payload = event.path("payload");
        assertEquals("SUMMARY_VALIDATION", payload.path("stage").asText());
        assertEquals("QUOTE_NOT_IN_ORIGINAL", payload.path("reasonCode").asText());
        assertEquals(1, payload.path("modelCallNumber").asInt());
        assertEquals(0, payload.path("fromIndex").asInt());
        assertEquals(4, payload.path("toIndex").asInt());
        assertTrue(payload.path("reason").asText().contains("连续原文"));
        assertFalse(event.toString().contains("sensitive summary text"));
        assertFalse(event.toString().contains("invented secret quotation"));
        assertTrue(event.path("detail").asText().contains("reasonCode=QUOTE_NOT_IN_ORIGINAL"));
    }

    @Test void jsonSyntaxFailureIsDistinguishedFromValidationFailure() throws Exception {
        var session = run(request -> { throw new IllegalStateException("Invalid summary JSON syntax"); });
        var payload = rejection(session).path("payload");
        assertEquals("MODEL_SUMMARY", payload.path("stage").asText());
        assertEquals("INVALID_JSON_SYNTAX", payload.path("reasonCode").asText());
    }

    @Test void unknownFailureDoesNotPersistMessageOrCause() throws Exception {
        var session = run(request -> { throw new IllegalStateException("Bearer secret-key\nraw response",
                new IllegalArgumentException("nested secret")); });
        var event = rejection(session);
        assertEquals("UNCLASSIFIED_FAILURE", event.path("payload").path("reasonCode").asText());
        assertFalse(event.toString().contains("secret"));
        assertFalse(event.toString().contains("raw response"));
    }

    @Test void noCompressionGainIsPersistedAsItsOwnReason() throws Exception {
        var session = run(request -> List.of(new SummaryNote(SummaryNote.Kind.PROGRESS,
                "s".repeat(600), "read-0", "evidence 0")), 0);
        var payload = rejection(session).path("payload");
        assertEquals("SUMMARY_VALIDATION", payload.path("stage").asText());
        assertEquals("NO_COMPRESSION_GAIN", payload.path("reasonCode").asText());
    }

    @Test void httpAndBudgetReasonsDoNotCopyDynamicExceptionText() {
        var http = SummaryRejection.from(SummaryRejection.Stage.MODEL_SUMMARY,
                new IllegalStateException("DeepSeek HTTP 429; check API key, balance, model availability and request limits"), 1, 0, 4);
        assertEquals("MODEL_HTTP_ERROR", http.getReasonCode());
        var budget = SummaryRejection.from(SummaryRejection.Stage.REQUEST_BUDGET,
                new IllegalStateException("unexpected budget failure secret-key"), 1, 0, 4);
        assertEquals("REQUEST_BUDGET_CHECK_FAILED", budget.getReasonCode());
        assertFalse(budget.detail().contains("secret-key"));
    }
}
