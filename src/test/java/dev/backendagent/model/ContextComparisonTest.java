package dev.backendagent.model;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.backendagent.history.HistoryArchiveReader;
import dev.backendagent.persistence.FileSessionStore;
import dev.backendagent.runtime.*;
import dev.backendagent.tools.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Paired wire-size replay on one real tool trajectory; no model-quality or Docker claim. */
class ContextComparisonTest {
    @TempDir Path root;
    private final ObjectMapper json = new ObjectMapper();
    private final RequestBudget budget = new RequestBudget(65536, 2048, 1024);
    private final List<Map<String, Object>> rows = new ArrayList<>();
    private final List<Long> summaryBytes = new ArrayList<>();
    private final AtomicInteger rounds = new AtomicInteger();
    private final AtomicReference<AgentSession> current = new AtomicReference<>();

    private ToolCall step(int round) {
        if (round == 0) { return new ToolCall("list", "list_files", Map.of("path", ".")); }
        if (round <= 16) { return read("read-" + round); }
        if (round == 17 || round == 19) { return new ToolCall("tests-" + round, "run_tests", Map.of()); }
        if (round == 18) { return new ToolCall("patch", "apply_patch", Map.of("path", "Main.java", "old_text", "return 1;", "new_text", "return 2;")); }
        if (round == 20) { return new ToolCall("lookup", "search_history", Map.of("tool", "read_file", "path", "Main.java",
                "keyword", "ORIGINAL_VALUE", "before_sequence", "0", "limit", "5")); }
        if (round == 21) { return new ToolCall("retrieve", "read_observation", Map.of("call_id", "read-1", "start_line", "3", "end_line", "3")); }
        return read("fresh-read");
    }
    private ToolCall read(String id) { return new ToolCall(id, "read_file", Map.of("path", "Main.java", "start_line", "1", "end_line", "200")); }
    private Workspace workspace() throws Exception { return new Workspace(root, root.resolve("agent-local.properties"), root.resolve("sessions")); }
    private List<Tool> tools(AgentSession session, Workspace workspace, FileSessionStore store) {
        var archive = store.observationArchive(session);
        var tests = new AtomicInteger();
        Tool fixtureTests = new Tool() {
            public String name() { return "run_tests"; }
            public ToolResult execute(Map<String, String> args) {
                boolean passed = tests.incrementAndGet() > 1;
                return new ToolResult(passed, "[FIXED TEST DOUBLE; no Docker or Maven execution] "
                        + (passed ? "fixture status PASSED" : "fixture assertion expected 2 but was 1"));
            }
        };
        return List.of(new ListFilesTool(workspace), new ReadFileTool(workspace), new ApplyPatchTool(workspace, session),
                fixtureTests, new SearchHistoryTool(new HistoryArchiveReader(archive, workspace)),
                new ReadObservationTool(session, workspace, archive));
    }

    @Test
    void comparesFullHistoryAndLayeredRequestsIncludingSummaryOverheadAndResume() throws Exception {
        String source = "class Main {\n static int value() { return 1; } // ORIGINAL_VALUE\n"
                + (" // fixture padding " + "x".repeat(80) + "\n").repeat(110) + "}\n";
        Files.writeString(root.resolve("Main.java"), source);
        var summaries = new AtomicInteger();
        var summaryResponses = new ArrayList<String>();
        var transmittedNormal = new ArrayList<Long>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] incoming = exchange.getRequestBody().readAllBytes();
            var body = json.readTree(incoming);
            var envelope = json.createObjectNode();
            var choice = envelope.putArray("choices").addObject();
            choice.put("index", 0);
            var message = choice.putObject("message").put("role", "assistant");
            if (body.has("response_format")) {
                summaryBytes.add((long) incoming.length);
                int number = summaries.incrementAndGet();
                var input = json.readTree(body.path("messages").get(1).path("content").asText());
                var output = json.createObjectNode();
                var notes = output.putArray("notes");
                // One deliberate invalid response verifies fallback without changing the fixed task trace.
                com.fasterxml.jackson.databind.JsonNode observation = null;
                for (var candidate : input.path("observations")) {
                    if (!candidate.path("result").path("content").asText().startsWith("[STALE_OBSERVATION_BODY_OMITTED]")) {
                        observation = candidate;
                        break;
                    }
                }
                summaryResponses.add(number == 2 ? "deliberately invalid empty notes"
                        : observation == null ? "all input sources stale; no valid fixture note" : "fixed valid fixture note");
                if (number != 2 && observation != null) {
                    String content = observation.path("result").path("content").asText();
                    String quote = content.lines().filter(line -> !line.isBlank()).findFirst().orElseThrow();
                    quote = quote.substring(0, Math.min(80, quote.length()));
                    notes.addObject().put("kind", "PROGRESS").put("statement", "固定测试摘要，核验流程用")
                            .put("sourceCallId", observation.path("call").path("id").asText()).put("evidenceQuote", quote);
                }
                message.put("content", json.writeValueAsString(output));
                choice.put("finish_reason", "stop");
            } else {
                transmittedNormal.add((long) incoming.length);
                int round = rounds.getAndIncrement();
                if (round == 23) { message.put("content", "固定轨迹完成；测试状态来自测试替身。"); choice.put("finish_reason", "stop"); }
                else {
                    var call = step(round);
                    message.putNull("content");
                    var tool = message.putArray("tool_calls").addObject().put("id", call.id()).put("type", "function");
                    tool.putObject("function").put("name", call.name()).put("arguments", json.writeValueAsString(call.arguments()));
                    choice.put("finish_reason", "tool_calls");
                }
            }
            byte[] response = json.writeValueAsBytes(envelope);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
            exchange.close();
        });
        server.start();
        try {
            var client = new DeepSeekModelClient("offline-test-key", URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    "deepseek-chat", Duration.ofSeconds(10));
            ModelClient measured = new ModelClient() {
                public RequestBudgetReport inspectRequest(ModelRequest request) { return client.inspectRequest(request); }
                public boolean supportsSummarization() { return true; }
                public List<SummaryNote> summarize(SummaryRequest request) {
                    assertFalse(request.getObservations().isEmpty());
                    assertTrue(request.getObservations().stream().noneMatch(item ->
                            item.result().content().contains("STALE_OBSERVATION_BODY_OMITTED")));
                    return client.summarize(request);
                }
                public ModelResponse execute(ModelRequest request) {
                    var session = current.get();
                    var originals = session.history().stream().map(item -> session.originalObservation(item.call().id())).toList();
                    var full = StaleObservationFilter.filter(originals);
                    var baseline = new ModelRequest(request.objective(), full, request.availableTools(), request.remainingModelCalls(),
                            0, 0, request.workingMemory(), null, null, Set.of(), request.workspaceState());
                    try {
                        String fullJson = json.writeValueAsString(client.buildRequest(baseline));
                        String layeredJson = json.writeValueAsString(client.buildRequest(request));
                        var fullReport = budget.measure(fullJson);
                        var layeredReport = budget.measure(layeredJson);
                        rows.add(Map.of("round", rows.size() + 1, "historySize", originals.size(),
                                "fullHistoryBytes", fullReport.getRequestBytes(), "layeredBytes", layeredReport.getRequestBytes(),
                                "fullHistoryWithinBudget", fullReport.isWithinBudget(), "layeredWithinBudget", layeredReport.isWithinBudget(),
                                "visibleExchanges", request.history().size(), "summaryNotes", request.contextSummary() == null ? 0 : request.contextSummary().getNotes().size()));
                        if (rounds.get() == 20) {
                            assertTrue(full.stream().filter(item -> item.call().id().equals("read-1")).findFirst().orElseThrow()
                                    .result().content().contains("STALE_OBSERVATION_BODY_OMITTED"));
                        }
                        if (rounds.get() == 21) {
                            var page = json.readTree(session.history().getLast().result().content());
                            assertTrue(page.path("matches").size() > 0);
                            assertEquals("STALE", page.path("matches").get(0).path("validity").asText());
                        }
                        if (rounds.get() == 22) {
                            assertTrue(request.history().getLast().result().content().contains("EXPLICIT_HISTORICAL_RETRIEVAL: STALE"));
                            assertTrue(request.history().getLast().result().content().contains("return 1;"));
                        }
                    } catch (java.io.IOException failure) { throw new AssertionError(failure); }
                    return client.execute(request);
                }
            };
            java.util.UUID id;
            try (var store = new FileSessionStore(root.resolve("sessions"))) {
                var workspace = workspace();
                store.bindWorkspace(workspace);
                var session = new AgentSession("把 Main.value 的返回值从 1 改为 2，并追溯历史读取", store);
                current.set(session);
                store.create(session);
                new AgentRuntime(measured, tools(session, workspace, store), 12).run(session);
                assertEquals(AgentSession.Status.BUDGET_EXHAUSTED, session.status());
                store.saveSnapshot(session);
                id = session.id();
            }
            try (var store = new FileSessionStore(root.resolve("sessions"))) {
                var workspace = workspace();
                var restored = store.restore(id, workspace, 40);
                current.set(restored);
                assertTrue(restored.history().stream().anyMatch(item -> item.archivedBody() != null));
                new AgentRuntime(measured, tools(restored, workspace, store), 40).resume(restored);
                assertEquals(AgentSession.Status.COMPLETED, restored.status());
                assertEquals(23, restored.history().size());
                assertTrue(Files.readString(root.resolve("Main.java")).contains("return 2;"));
                assertEquals(1, restored.workspaceState().getRevision());
                assertEquals(WorkspaceState.TestStatus.CURRENT_PASSED, restored.workspaceState().testStatus());
                // Short input is skipped; only the deliberate invalid response is rejected.
                assertEquals(1, restored.events().stream().filter(event -> event.type() == AgentSession.EventType.COMPACTION_FAILED).count());
                assertEquals(3, summaries.get());
                assertEquals(2, restored.events().stream().filter(event -> event.type() == AgentSession.EventType.COMPACTION_COMPLETED).count());
                assertTrue(restored.events().stream().anyMatch(event -> event.type() == AgentSession.EventType.WINDOW_SUMMARY_SKIPPED));
                assertTrue(restored.events().stream().anyMatch(event -> event.type() == AgentSession.EventType.SESSION_RESUMED));
            }
            assertEquals(24, rows.size());
            assertEquals(rows.stream().map(row -> (Long) row.get("layeredBytes")).toList(), transmittedNormal);
            long fullTotal = rows.stream().mapToLong(row -> (Long) row.get("fullHistoryBytes")).sum();
            long layeredNormal = rows.stream().mapToLong(row -> (Long) row.get("layeredBytes")).sum();
            long summaryTotal = summaryBytes.stream().mapToLong(Long::longValue).sum();
            assertTrue(summaryTotal > 0);
            assertTrue(summaryBytes.stream().allMatch(bytes -> new RequestBudgetReport(bytes, 2048, 1024, 65536).isWithinBudget()));
            assertTrue(layeredNormal + summaryTotal < fullTotal);
            assertTrue(rows.stream().allMatch(row -> (boolean) row.get("layeredWithinBudget")));
            assertTrue(rows.stream().anyMatch(row -> !(boolean) row.get("fullHistoryWithinBudget")));
            var report = json.createObjectNode();
            report.put("method", "paired full-history counterfactual replay of one fixed trajectory");
            report.put("externalApiCalls", 0).put("realDockerTests", false).put("normalRequests", rows.size());
            report.put("contextWindowEstimateLimit", 65536).put("outputReserve", 2048).put("safetyMargin", 1024);
            report.put("summaryRequests", summaryBytes.size()).put("deliberatelyInvalidSummaryResponses", 1);
            report.put("rejectedSummaries", current.get().events().stream().filter(event -> event.type() == AgentSession.EventType.COMPACTION_FAILED).count());
            report.put("fullHistoryRequestBytes", fullTotal).put("layeredNormalRequestBytes", layeredNormal)
                    .put("summaryRequestBytes", summaryTotal).put("layeredTotalRequestBytes", layeredNormal + summaryTotal);
            report.put("requestByteReductionIncludingSummaries", 1.0 - (double) (layeredNormal + summaryTotal) / fullTotal);
            report.put("fullHistoryOverBudgetRequests", rows.stream().filter(row -> !(boolean) row.get("fullHistoryWithinBudget")).count());
            report.put("layeredOverBudgetRequests", 0);
            report.put("limitations", "UTF-8 request body bytes, not billed tokens; fixed responses, not task-success evidence; baseline over-budget bodies were encoded only and never transmitted; same remaining-call metadata used in paired requests; run_tests is a fixed test double.");
            report.set("rounds", json.valueToTree(rows));
            report.set("summaryBytes", json.valueToTree(summaryBytes));
            report.set("summaryResponseKinds", json.valueToTree(summaryResponses));
            Path directory = Path.of("target", "context-comparison");
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("report.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            var csv = new StringBuilder("round,historySize,fullHistoryBytes,layeredBytes,fullHistoryWithinBudget,layeredWithinBudget,visibleExchanges,summaryNotes\n");
            for (var row : rows) {
                csv.append(row.get("round")).append(',').append(row.get("historySize")).append(',').append(row.get("fullHistoryBytes"))
                        .append(',').append(row.get("layeredBytes")).append(',').append(row.get("fullHistoryWithinBudget"))
                        .append(',').append(row.get("layeredWithinBudget")).append(',').append(row.get("visibleExchanges"))
                        .append(',').append(row.get("summaryNotes")).append('\n');
            }
            Files.writeString(directory.resolve("rounds.csv"), csv.toString(), StandardCharsets.UTF_8);
        } finally { server.stop(0); }
    }
}
