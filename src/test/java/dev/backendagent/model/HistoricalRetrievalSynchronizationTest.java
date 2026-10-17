package dev.backendagent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import dev.backendagent.history.HistoricalEvidence;
import dev.backendagent.runtime.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HistoricalRetrievalSynchronizationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final String indexText = """
            {"matches":[{"callId":"original","matchedLine":1,"validity":"STALE",
            "snippet":"[过期正文片段已省略；可显式提取历史]"},
            {"callId":"not-retained","matchedLine":1,"validity":"STALE",
            "snippet":"[过期正文片段已省略；可显式提取历史]"}],"nextBeforeSequence":17,
            "notice":"过期片段已省略；用callId追溯"}
            """;
    private ToolExchange source() {
        return new ToolExchange(new ToolCall("original", "read_file", Map.of("path", "contract.md")),
                new ToolResult(true, "first\nold rule\nthird"), null, 1);
    }
    private HistoricalEvidence evidence() {
        return HistoricalEvidence.excerpt(source(), 10, ObservationEvidence.Validity.STALE, 2, 2);
    }
    private ToolExchange search(String text) {
        return new ToolExchange(new ToolCall("search", "search_history", Map.of("keyword", "old")),
                new ToolResult(true, text), null, 2);
    }

    @Test void updatesOnlyRetainedMatchesAndPreservesValidityPaginationAndActualRange() throws Exception {
        var exchange = search(indexText);
        var wire = json.readTree(HistoricalEvidenceNotice.content(exchange, List.of(evidence()), json));
        var retained = wire.path("matches").get(0);
        assertTrue(retained.path("snippet").asText().contains("已提取到历史证据区"));
        assertEquals("STALE", retained.path("validity").asText());
        assertEquals(1, retained.path("matchedLine").asInt());
        assertEquals(2, retained.path("currentHistoricalRetrieval").path("startLine").asInt());
        assertEquals(2, retained.path("currentHistoricalRetrieval").path("endLine").asInt());
        assertEquals("HISTORICAL_ONLY", retained.path("currentHistoricalRetrieval").path("usageScope").asText());
        assertFalse(wire.path("matches").get(1).has("currentHistoricalRetrieval"));
        assertEquals("[过期正文片段已省略；可显式提取历史]", wire.path("matches").get(1).path("snippet").asText());
        assertEquals(17, wire.path("nextBeforeSequence").asInt());
        assertEquals(indexText, exchange.result().content());
    }

    @Test void evictionRemovesHintsAndReplacementUsesOnlyTheNewRange() throws Exception {
        var exchange = search(indexText);
        String retained = HistoricalEvidenceNotice.content(exchange, List.of(evidence()), json);
        assertTrue(retained.contains("IN_HISTORICAL_EVIDENCE_AREA"));
        assertEquals(indexText, HistoricalEvidenceNotice.content(exchange, List.of(), json));
        var replacement = HistoricalEvidence.excerpt(source(), 10, ObservationEvidence.Validity.STALE, 3, 3);
        var wire = json.readTree(HistoricalEvidenceNotice.content(exchange, List.of(replacement), json));
        assertEquals(3, wire.path("matches").get(0).path("currentHistoricalRetrieval").path("startLine").asInt());
    }

    @Test void leavesSourceCodeAndMalformedOrOmittedSearchResultsUntouched() throws Exception {
        var file = new ToolExchange(new ToolCall("file", "read_file", Map.of("path", "code.json")),
                new ToolResult(true, indexText), null, 2);
        assertEquals(indexText, HistoricalEvidenceNotice.content(file, List.of(evidence()), json));
        assertEquals("[STALE_OBSERVATION_BODY_OMITTED]", HistoricalEvidenceNotice.content(
                search("[STALE_OBSERVATION_BODY_OMITTED]"), List.of(evidence()), json));
        assertEquals("legacy result", HistoricalEvidenceNotice.content(search("legacy result"), List.of(evidence()), json));
    }

    private ModelRequest request(List<HistoricalEvidence> evidence) {
        var note = new SummaryNote(SummaryNote.Kind.PROGRESS, "历史索引正文已省略，需要提取", "search", indexText);
        var summary = new ContextSummary(1, 1, List.of(note));
        var turns = List.of(new ConversationTurn(1, "old audit", AgentSession.Status.COMPLETED,
                "[STALE_OBSERVATION_BODY_OMITTED] 未取回原文", 0, 1),
                new ConversationTurn(2, "audit again", AgentSession.Status.RUNNING, null, 1, 1));
        return new ModelRequest("old audit", List.of(search(indexText)), List.of(), 10, 0, 0,
                List.of(), null, summary, Set.of(), WorkspaceState.fromHistory(List.of()), evidence, turns, null, 2);
    }
    private JsonNode block(JsonNode body, String kind) throws Exception {
        for (var message : body.path("messages")) {
            if (!message.path("role").asText().equals("user")) { continue; }
            String text = message.path("content").asText();
            if (!text.startsWith("{")) { continue; }
            var value = json.readTree(text);
            if (value.path("kind").asText().equals(kind)) { return value; }
        }
        fail("Missing block " + kind); return null;
    }

    @Test void wireSynchronizesSummariesDialogueAndOldAnswersAndCountsTheEncodedBytes() throws Exception {
        var client = new DeepSeekModelClient("test-key", java.net.URI.create("https://api.deepseek.com"),
                "test-model", java.time.Duration.ofSeconds(1));
        var request = request(List.of(evidence()));
        var body = client.buildRequest(request);
        for (String kind : List.of("historical_context_summary_reference_data", "historical_dialogue_reference_data")) {
            var reference = block(body, kind);
            assertEquals("original", reference.path("currentHistoricalRetrievals").get(0).path("sourceCallId").asText());
            assertTrue(reference.path("retrievalNotice").asText().contains("不代表本次片段不可见"));
        }
        var summary = block(body, "historical_context_summary_reference_data");
        assertFalse(summary.path("summary").toString().contains("evidenceQuote"));
        assertEquals(indexText, request.contextSummary().getNotes().getFirst().getEvidenceQuote());
        assertTrue(summary.path("summary").path("sections").has("FINDINGS"));
        assertTrue(java.util.stream.StreamSupport.stream(body.path("messages").spliterator(), false)
                .anyMatch(m -> m.path("role").asText().equals("assistant")
                        && m.path("content").asText().startsWith("[历史回答 turn-1-answer；样例数值仅适用当时输入]")
                        && m.path("content").asText().contains("[HISTORICAL_ASSISTANT_ANSWER: UNVERIFIED]")));
        assertEquals("[STALE_OBSERVATION_BODY_OMITTED] 未取回原文", request.turns().getFirst().getAnswer());
        assertEquals(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                client.inspectRequest(request).getRequestBytes());
    }

    @Test void nextWireWithoutCachedEvidenceHasNoResidualExtractionState() throws Exception {
        var client = new DeepSeekModelClient("test-key", java.net.URI.create("https://api.deepseek.com"),
                "test-model", java.time.Duration.ofSeconds(1));
        client.buildRequest(request(List.of(evidence())));
        var body = client.buildRequest(request(List.of()));
        assertFalse(block(body, "historical_context_summary_reference_data").has("currentHistoricalRetrievals"));
        assertFalse(body.toString().contains("IN_HISTORICAL_EVIDENCE_AREA"));
        assertFalse(body.toString().contains("HISTORICAL_ASSISTANT_ANSWER: UNVERIFIED"));
    }
}
