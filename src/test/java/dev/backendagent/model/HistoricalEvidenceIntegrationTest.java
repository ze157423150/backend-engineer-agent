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
            assertEquals(1,session.historicalEvidence().size());
            assertEquals(3, json.readTree(Files.readString(store.sessionDirectory(session.id()).resolve("checkpoint.json"))).path("schemaVersion").asInt());
            return session.id();
        }
    }
    @Test void survivesExplorationAndRestoreWithoutGrantingFreshWriteEvidence() throws Exception {
        var id = prepared();
        try (var store = new FileSessionStore(root.resolve("sessions"))) {
            var workspace = workspace(); var restored = store.restore(id, workspace, 6);
            new AgentRuntime(request -> {
                assertEquals(1,request.historicalEvidence().size());
                assertEquals(ObservationEvidence.Validity.STALE,request.historicalEvidence().getFirst().getCurrentValidity());
                assertTrue(request.historicalEvidence().getFirst().getContent().contains("RATE=225"));
                assertFalse(restored.hasCurrentRead("rate.txt", workspace));
                assertFalse(restored.hasCurrentPatchSource("rate.txt","RATE=225",workspace));
                assertTrue(request.history().stream().filter(x -> x.call().id().equals("retrieve")).findFirst().orElseThrow().result().content().contains("STALE_OBSERVATION_BODY_OMITTED"));
                var client = new DeepSeekModelClient("test-key", java.net.URI.create("https://api.deepseek.com"), "test-model", java.time.Duration.ofSeconds(1));
                var wire = assertDoesNotThrow(() -> client.buildRequest(request));
                assertEquals(wire.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length, client.inspectRequest(request).getRequestBytes());
                assertTrue(wire.toString().contains("historical_evidence_reference_data"));
                assertTrue(wire.toString().contains("HISTORICAL_ONLY"));
                for (String callId : List.of("original", "retrieve")) {
                    var message = java.util.stream.StreamSupport.stream(wire.path("messages").spliterator(), false)
                            .filter(m -> m.path("tool_call_id").asText().equals(callId)).findFirst().orElseThrow();
                    var result = assertDoesNotThrow(() -> json.readTree(message.path("content").asText()));
                    assertFalse(result.path("content").asText().contains("STALE_OBSERVATION_BODY_OMITTED"));
                    assertTrue(result.path("content").asText().contains("HISTORICAL_EXCERPT_IN_EVIDENCE_AREA"));
                }
                return ModelResponse.finish("old=225; current requires fresh read");
            }, tools(restored, workspace, store), 6).resume(restored);
            assertEquals(AgentSession.Status.COMPLETED, restored.status());
        }
    }
    @Test void rejectsForgedCheckpointExcerpt() throws Exception {
        var id = prepared(); var file = root.resolve("sessions").resolve(id.toString()).resolve("checkpoint.json");
        var checkpoint = json.readTree(Files.readString(file));
        var forged=checkpoint.path("history").get(2).path("result").path("historicalEvidence").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)forged).put("content","2: RATE=111\n");
        ((com.fasterxml.jackson.databind.node.ArrayNode)checkpoint.path("historicalEvidence")).removeAll().add(forged);
        Files.writeString(file, checkpoint.toString());
        try (var store = new FileSessionStore(root.resolve("sessions"))) { assertThrows(IllegalArgumentException.class, () -> store.restore(id, workspace(), 6)); }
    }
    @Test void rejectsForgedRetrievalSequenceEvenWhenCheckpointAndHistoryAgree() throws Exception {
        var id = prepared(); var file = root.resolve("sessions").resolve(id.toString()).resolve("checkpoint.json");
        var checkpoint = json.readTree(Files.readString(file));

        ((com.fasterxml.jackson.databind.node.ObjectNode)checkpoint.path("history").get(2).path("result").path("historicalEvidence")).put("sourceEventSequence", 999);
        Files.writeString(file, checkpoint.toString());
        try (var store = new FileSessionStore(root.resolve("sessions"))) { assertThrows(java.io.IOException.class, () -> store.restore(id, workspace(), 6)); }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 130})
    void evictsOldestSourceAndBoundsLongExcerpts(int padding) throws Exception {
        var workspace=workspace();var area=new HistoricalEvidenceArea();
        for(int i=0;i<5;i++) {
            String path="file"+i+".txt";Files.writeString(root.resolve(path),"MARKER="+i+"\n"+"background line\n".repeat(padding));
            var source=new ToolExchange(read("source"+i,path),workspace.readFile(path,"1","150"),null,i+1);
            area.retain(HistoricalEvidence.excerpt(source,i+1,ObservationEvidence.Validity.CURRENT,1,150));
        }
        assertTrue(area.snapshot().size()<=4);
        if(padding==0)assertEquals(4,area.snapshot().size());
        assertTrue(area.characterCount()<=HistoricalEvidenceArea.MAX_CHARACTERS);
        assertFalse(area.snapshot().stream().anyMatch(e->e.getSourceCallId().equals("source0")));
        assertTrue(area.snapshot().stream().allMatch(e->e.getContent().length()<=1600 && e.isTruncated()==(padding>0)));
    }
}
