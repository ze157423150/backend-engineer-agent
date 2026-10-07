package dev.backendagent.history;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.backendagent.model.*;
import dev.backendagent.runtime.*;
import dev.backendagent.tools.*;
import dev.backendagent.persistence.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static dev.backendagent.runtime.ObservationEvidence.Validity.*;

class HistoryArchiveTest {
    @TempDir Path root;
    private final ObjectMapper json = new ObjectMapper();
    private Workspace workspace() throws Exception {
        return new Workspace(root, root.resolve("agent-local.properties"), root.resolve("sessions"));
    }
    private ToolCall read(String id) {
        return new ToolCall(id, "read_file", Map.of("path", "Main.java", "start_line", "1", "end_line", "10"));
    }
    private Map<String, String> query(String tool, String path, String keyword, long before, int limit) {
        return Map.of("tool", tool, "path", path, "keyword", keyword,
                "before_sequence", Long.toString(before), "limit", Integer.toString(limit));
    }

    @Test
    void runtimeDiscoversStaleArchiveThenExplicitlyRetrievesOriginalBody() throws Exception {
        Files.writeString(root.resolve("Main.java"), "class Main {\n int ORIGINAL_VALUE = 1;\n}\n");
        var workspace = workspace();
        try (var store = new FileSessionStore(root.resolve("sessions"))) {
            store.bindWorkspace(workspace);
            var session = new AgentSession("edit and trace", store);
            store.create(session);
            var archive = store.observationArchive(session);
            var search = new SearchHistoryTool(new HistoryArchiveReader(archive, workspace));
            var retrieve = new ReadObservationTool(session, workspace, archive);
            var turns = new AtomicInteger();
            new AgentRuntime(request -> {
                switch (turns.getAndIncrement()) {
                    case 0: return ModelResponse.callTool(read("old-read"));
                    case 1: return ModelResponse.callTool(new ToolCall("patch", "apply_patch", Map.of(
                            "path", "Main.java", "old_text", "ORIGINAL_VALUE", "new_text", "NEW_VALUE")));
                    case 2: return ModelResponse.callTool(new ToolCall("lookup", "search_history",
                            query("read_file", "./Main.java", "ORIGINAL_VALUE", 0, 5)));
                    case 3:
                        try {
                            var page = json.readTree(request.history().getLast().result().content());
                            var match = page.path("matches").get(0);
                            assertEquals("old-read", match.path("callId").asText());
                            assertEquals("STALE", match.path("validity").asText());
                            assertFalse(match.path("snippet").asText().contains("ORIGINAL_VALUE"));
                            assertEquals(3, match.path("matchedLine").asInt()); // Header, first line, second line.
                        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
                        return ModelResponse.callTool(new ToolCall("recover", "read_observation",
                                Map.of("call_id", "old-read", "start_line", "3", "end_line", "3")));
                    default:
                        assertTrue(request.history().getLast().result().content().contains("ORIGINAL_VALUE"));
                        assertTrue(request.history().getLast().result().content().contains("EXPLICIT_HISTORICAL_RETRIEVAL: STALE"));
                        assertFalse(session.hasCurrentRead("Main.java"));
                        return ModelResponse.finish("traced");
                }
            }, List.of(new ReadFileTool(workspace), new ApplyPatchTool(workspace, session), search, retrieve), 5).run(session);
            assertEquals(AgentSession.Status.COMPLETED, session.status());
            assertTrue(Files.readString(root.resolve("Main.java")).contains("NEW_VALUE"));
            assertTrue(session.history().getFirst().result().content().contains("ORIGINAL_VALUE"));
        }
    }

    @Test
    void fileRetrievalWorksWithFreshReaderAndNoInMemoryHistoryOrSnapshot() throws Exception {
        Files.writeString(root.resolve("Main.java"), "class Main {}\n");
        java.util.UUID id;
        long watermark;
        try (var store = new FileSessionStore(root.resolve("sessions"))) {
            var session = new AgentSession("read", store);
            store.create(session);
            new AgentRuntime(request -> ModelResponse.callTool(read("read")), List.of(new ReadFileTool(workspace())), 1).run(session);
            id = session.id();
            watermark = session.events().size();
        }
        Files.delete(root.resolve("sessions").resolve(id.toString()).resolve("session.json"));
        var empty = new AgentSession("detached inspection");
        try (var reopened = new FileSessionStore(root.resolve("sessions"))) {
            ObservationArchive archive = visitor -> reopened.visitObservations(id, watermark, visitor);
            var result = new ReadObservationTool(empty, workspace(), archive).execute(
                    Map.of("call_id", "read", "start_line", "1", "end_line", "10"));
            assertTrue(result.successful());
            assertTrue(result.content().contains("class Main {}"));
            assertTrue(result.content().contains("fileEvidenceValidity=CURRENT"));
            assertTrue(empty.history().isEmpty());
        }
    }

    @Test
    void paginationUsesEventSequenceAndFiltersNormalizedPathsAndRootExactly() throws Exception {
        var entries = List.of(
                new ToolExchange(new ToolCall("first", "read_file", Map.of("path", "Main.java")), new ToolResult(true, "needle first")),
                new ToolExchange(new ToolCall("second", "read_file", Map.of("path", "./Main.java")), new ToolResult(true, "needle second")),
                new ToolExchange(new ToolCall("root", "search_code", Map.of("path", ".")), new ToolResult(true, "needle root")),
                new ToolExchange(new ToolCall("third", "read_file", Map.of("path", "Main.java")), new ToolResult(true, "needle third")),
                new ToolExchange(new ToolCall("recursive", "search_history", Map.of()), new ToolResult(true, "needle index")));
        ObservationArchive archive = visitor -> { int i = 0; for (var entry : entries) { visitor.accept((++i) * 10, entry); } };
        var reader = new HistoryArchiveReader(archive, null);
        var page = reader.search("read_file", "Main.java", "needle", 0, 2);
        assertEquals(List.of("third", "second"), page.getMatches().stream().map(HistoryReference::getCallId).toList());
        assertEquals(20, page.getNextBeforeSequence());
        var next = reader.search("read_file", "Main.java", "needle", page.getNextBeforeSequence(), 2);
        assertEquals("first", next.getMatches().getFirst().getCallId());
        assertEquals(0, next.getNextBeforeSequence());
        assertEquals("root", reader.search("", ".", "", 0, 5).getMatches().getFirst().getCallId());
        assertEquals(4, reader.search("", "", "needle", 0, 10).getMatches().size());
        assertEquals(UNKNOWN, page.getMatches().getFirst().getValidity());
        assertTrue(reader.search("", "", "NEEDLE", 0, 5).getMatches().isEmpty());
    }

    @Test
    void verifiesFileHashesOncePerPathAndNeverEquatesCurrentFailedTestWithPassed() throws Exception {
        Files.writeString(root.resolve("Main.java"), "needle code");
        var workspace = workspace();
        var result = workspace.readFile("Main.java", "1", "1");
        var reads = List.of("a", "b").stream().map(id -> new ToolExchange(read(id), result, null, 1,
                new ObservationEvidence(ObservationEvidence.Type.FILE_CONTENT, id, result.fileFingerprint(), 0))).toList();
        var hashes = new AtomicInteger();
        WorkspaceAccess proxy = (WorkspaceAccess) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{WorkspaceAccess.class}, (object, method, args) -> {
                    if (method.getName().equals("fileFingerprint")) { hashes.incrementAndGet(); return workspace.fileFingerprint((String) args[0]); }
                    throw new AssertionError("Unexpected filesystem operation");
                });
        ObservationArchive archive = visitor -> { long i = 0; for (var entry : reads) { visitor.accept(++i, entry); } };
        var reader = new HistoryArchiveReader(archive, proxy);
        assertTrue(reader.search("", "", "needle", 0, 5).getMatches().stream().allMatch(m -> m.getValidity() == CURRENT));
        assertEquals(1, hashes.get());
        Files.writeString(root.resolve("Main.java"), "changed code");
        assertTrue(reader.search("", "", "needle", 0, 5).getMatches().stream().allMatch(m -> m.getValidity() == STALE));
        Files.delete(root.resolve("Main.java"));
        assertEquals(UNKNOWN, reader.search("", "", "needle", 0, 5).getMatches().getFirst().getValidity());
        var failed = new ToolExchange(new ToolCall("test", "run_tests", Map.of()), new ToolResult(false, "FAILED"));
        var tests = new HistoryArchiveReader(visitor -> visitor.accept(10, failed), null).search("run_tests", "", "", 0, 5);
        assertEquals(CURRENT, tests.getMatches().getFirst().getValidity());
        assertFalse(tests.getMatches().getFirst().isSuccessful());
    }

    @Test
    void incompleteOrSymlinkArchiveIsFatalDespiteMemoryContainingRequestedBody() throws Exception {
        Files.writeString(root.resolve("Main.java"), "class Main {}");
        try (var store = new FileSessionStore(root.resolve("sessions"))) {
            var session = new AgentSession("read", store);
            store.create(session);
            new AgentRuntime(request -> ModelResponse.callTool(read("read")), List.of(new ReadFileTool(workspace())), 1).run(session);
            var retrieve = new ReadObservationTool(session, workspace(), store.observationArchive(session));
            Path log = store.sessionDirectory(session.id()).resolve("events.jsonl");
            String original = Files.readString(log);
            Files.writeString(log, original.substring(0, original.length() - 1));
            assertThrows(PersistenceException.class, () -> retrieve.execute(Map.of("call_id", "read", "start_line", "1", "end_line", "5")));
            assertFalse(session.history().isEmpty());
            Path outside = Files.writeString(root.resolve("outside.jsonl"), original);
            Files.delete(log);
            Files.createSymbolicLink(log, outside);
            assertThrows(PersistenceException.class, () -> retrieve.execute(Map.of("call_id", "read", "start_line", "1", "end_line", "5")));
        }
    }

    @Test
    void validatesWholeArchiveEvenAfterFindingBodyAndRejectsWatermarkOrDuplicateIds() throws Exception {
        Files.writeString(root.resolve("Main.java"), "class Main {}");
        try (var store = new FileSessionStore(root.resolve("sessions"))) {
            var session = new AgentSession("read", store);
            store.create(session);
            new AgentRuntime(request -> ModelResponse.callTool(read("read")), List.of(new ReadFileTool(workspace())), 1).run(session);
            Path log = store.sessionDirectory(session.id()).resolve("events.jsonl");
            String original = Files.readString(log);
            long watermark = session.events().size();
            assertThrows(java.io.IOException.class, () -> store.visitObservations(session.id(), watermark - 1, (sequence, exchange) -> {}));
            var duplicate = original.lines().map(line -> {
                try { return json.readTree(line); } catch (java.io.IOException failure) { throw new AssertionError(failure); }
            }).filter(event -> event.path("type").asText().equals("TOOL_EXECUTION_SUCCEEDED")).findFirst().orElseThrow();
            ((com.fasterxml.jackson.databind.node.ObjectNode) duplicate).put("sequence", watermark + 1);
            Files.writeString(log, original + json.writeValueAsString(duplicate) + "\n");
            assertThrows(java.io.IOException.class, () -> store.visitObservations(session.id(), watermark + 1, (sequence, exchange) -> {}));
            // The selected body is before this malformed tail; extraction must not return it early.
            Files.writeString(log, original + "{\"sequence\":999,\"type\":\"SESSION_COMPLETED\"}\n");
            var retrieve = new ReadObservationTool(session, workspace(), store.observationArchive(session));
            assertThrows(PersistenceException.class, () -> retrieve.execute(Map.of("call_id", "read", "start_line", "1", "end_line", "5")));
        }
    }

    @Test
    void importedMemoryVerificationIsSearchableAndQueryParametersCannotChooseAnotherSession() throws Exception {
        Files.writeString(root.resolve("Main.java"), "class Main {}");
        var workspace = workspace();
        try (var store = new FileSessionStore(root.resolve("sessions"))) {
            var session = new AgentSession("import", store);
            store.create(session);
            session.importVerifiedMemory(new dev.backendagent.memory.MemoryFact("main exists", "old", "read_file", "Main.java", "class Main {}"),
                    new ToolExchange(read("verified"), workspace.readFile("Main.java", "1", "10")), java.util.UUID.randomUUID());
            var tool = new SearchHistoryTool(new HistoryArchiveReader(store.observationArchive(session), workspace));
            var page = json.readTree(tool.execute(query("read_file", "Main.java", "class", 0, 5)).content());
            assertEquals("verified", page.path("matches").get(0).path("callId").asText());
            assertEquals("CURRENT", page.path("matches").get(0).path("validity").asText());
            var bad = new java.util.HashMap<>(query("", "", "", 0, 5));
            bad.put("session_id", java.util.UUID.randomUUID().toString());
            assertFalse(tool.execute(bad).successful());
            assertFalse(tool.execute(query("", "../", "", 0, 5)).successful());
            assertFalse(tool.execute(query("", "", "", 0, 21)).successful());
            assertFalse(tool.execute(query("", "", "", -1, 5)).successful());
            assertFalse(tool.execute(query("", "", "a\nb", 0, 5)).successful());
        }
    }
}
