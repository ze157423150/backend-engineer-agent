package dev.backendagent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.backendagent.history.*;
import dev.backendagent.persistence.FileSessionStore;
import dev.backendagent.runtime.*;
import dev.backendagent.tools.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HistoricalEvidenceIntegrationTest {
    @TempDir Path root;
    private final ObjectMapper json = new ObjectMapper();
    private Workspace workspace() throws Exception { return new Workspace(root, root.resolve("agent-local.properties"), root.resolve("sessions")); }
    private ToolCall read(String id, String path) { return new ToolCall(id, "read_file", Map.of("path", path, "start_line", "1", "end_line", "150")); }
    private List<Tool> tools(AgentSession session, Workspace workspace, FileSessionStore store) {
        return List.of(new ReadFileTool(workspace), new ApplyPatchTool(workspace, session), new ListFilesTool(workspace),
                new ReadObservationTool(session, workspace, store.observationArchive(session)));
    }
    private UUID prepared() throws Exception {
        Files.writeString(root.resolve("rate.txt"), "RATE=225\n");
        try (var store = new FileSessionStore(root.resolve("sessions"))) {
            var workspace = workspace(); store.bindWorkspace(workspace);
            var session = new AgentSession("historical rate audit", store); store.create(session);
            var calls = List.of(read("original", "rate.txt"), new ToolCall("patch", "apply_patch", Map.of("path", "rate.txt", "old_text", "225", "new_text", "958")),
                    new ToolCall("retrieve", "read_observation", Map.of("call_id", "original", "start_line", "1", "end_line", "3")),
                    new ToolCall("explore", "list_files", Map.of("path", ".")));
            var next = new AtomicInteger();
            new AgentRuntime(request -> ModelResponse.callTool(calls.get(next.getAndIncrement())), tools(session, workspace, store), 4).run(session);
            assertEquals(AgentSession.Status.BUDGET_EXHAUSTED, session.status());
            assertEquals(1, session.historicalEvidence().size());
            assertEquals(2, json.readTree(Files.readString(store.sessionDirectory(session.id()).resolve("checkpoint.json"))).path("schemaVersion").asInt());
            return session.id();
        }
    }
    @Test void survivesExplorationAndRestoreWithoutGrantingFreshWriteEvidence() throws Exception {
        var id = prepared();
        try (var store = new FileSessionStore(root.resolve("sessions"))) {
            var workspace = workspace(); var restored = store.restore(id, workspace, 6);
            new AgentRuntime(request -> {
                var evidence = request.historicalEvidence().getFirst();
                assertEquals(HistoricalEvidence.UsageScope.HISTORICAL_ONLY, evidence.getUsageScope());
                assertEquals(ObservationEvidence.Validity.STALE, evidence.getValidityAtRetrieval());
                assertTrue(evidence.getContent().contains("RATE=225"));
                assertFalse(restored.hasCurrentRead("rate.txt", workspace));
                assertTrue(request.history().stream().filter(x -> x.call().id().equals("retrieve")).findFirst().orElseThrow().result().content().contains("STALE_OBSERVATION_BODY_OMITTED"));
                var client = new DeepSeekModelClient("test-key", java.net.URI.create("https://api.deepseek.com"), "test-model", java.time.Duration.ofSeconds(1));
                var wire = assertDoesNotThrow(() -> client.buildRequest(request));
                assertEquals(wire.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length, client.inspectRequest(request).getRequestBytes());
                assertTrue(wire.toString().contains("historical_evidence_reference_data"));
                assertTrue(wire.toString().contains("HISTORICAL_ONLY"));
                return ModelResponse.finish("old=225; current requires fresh read");
            }, tools(restored, workspace, store), 6).resume(restored);
            assertEquals(AgentSession.Status.COMPLETED, restored.status());
        }
    }
    @Test void rejectsForgedCheckpointExcerpt() throws Exception {
        var id = prepared(); var file = root.resolve("sessions").resolve(id.toString()).resolve("checkpoint.json");
        var checkpoint = json.readTree(Files.readString(file));
        ((com.fasterxml.jackson.databind.node.ObjectNode)checkpoint.path("historicalEvidence").get(0)).put("content", "2: RATE=111\n");
        Files.writeString(file, checkpoint.toString());
        try (var store = new FileSessionStore(root.resolve("sessions"))) { assertThrows(IllegalArgumentException.class, () -> store.restore(id, workspace(), 6)); }
    }
    @Test void rejectsForgedRetrievalSequenceEvenWhenCheckpointAndHistoryAgree() throws Exception {
        var id = prepared(); var file = root.resolve("sessions").resolve(id.toString()).resolve("checkpoint.json");
        var checkpoint = json.readTree(Files.readString(file));
        ((com.fasterxml.jackson.databind.node.ObjectNode)checkpoint.path("historicalEvidence").get(0)).put("sourceEventSequence", 999);
        ((com.fasterxml.jackson.databind.node.ObjectNode)checkpoint.path("history").get(2).path("result").path("historicalEvidence")).put("sourceEventSequence", 999);
        Files.writeString(file, checkpoint.toString());
        try (var store = new FileSessionStore(root.resolve("sessions"))) { assertThrows(java.io.IOException.class, () -> store.restore(id, workspace(), 6)); }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 130})
    void evictsOldestSourceAndBoundsLongExcerpts(int padding) throws Exception {
        for (int i = 0; i < 5; i++) { Files.writeString(root.resolve("file"+i+".txt"), "MARKER="+i+"\n"+"background line\n".repeat(padding)); }
        try (var store = new FileSessionStore(root.resolve("sessions"))) {
            var workspace = workspace(); store.bindWorkspace(workspace); var session = new AgentSession("capacity test", store);store.create(session);
            var step = new AtomicInteger();
            new AgentRuntime(request -> {
                int n=step.getAndIncrement(),i=n/2;
                return ModelResponse.callTool(n%2==0 ? read("source"+i,"file"+i+".txt") : new ToolCall("extract"+i,"read_observation",Map.of("call_id","source"+i,"start_line","1","end_line","150")));
            },tools(session,workspace,store),10).run(session);
            assertTrue(session.historicalEvidence().size()<=4);
            if (padding == 0) { assertEquals(4,session.historicalEvidence().size()); }
            assertTrue(session.historicalEvidenceCharacters()<=HistoricalEvidenceArea.MAX_CHARACTERS);
            assertFalse(session.historicalEvidence().stream().anyMatch(e->e.getSourceCallId().equals("source0")));
            assertTrue(session.historicalEvidence().stream().allMatch(e->e.getContent().length()<=1600 && e.isTruncated() == (padding > 0)));
        }
    }
}
