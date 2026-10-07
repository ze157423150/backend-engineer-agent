package dev.backendagent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.backendagent.runtime.*;
import dev.backendagent.tools.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HistoryFreshnessEvaluationTest {
    @TempDir Path root;
    private final ObjectMapper json = new ObjectMapper();
    @Test void acceptsVerifiedHistoricalValuesAndFreshRead() throws Exception {
        var result = evaluate(false, false);
        assertTrue(result.path("success").asBoolean(), result.toString());
    }
    @Test void rejectsOldRateReportedAsCurrentEvenWithCorrectToolTrace() throws Exception {
        var result = evaluate(true, false);
        assertTrue(result.path("extractedAllTargets").asBoolean());
        assertTrue(result.path("freshReadAfterStaleExtraction").asBoolean());
        assertFalse(result.path("outputCorrect").asBoolean());
        assertFalse(result.path("success").asBoolean());
    }
    @Test void rejectsCorrectOutputWithoutRereadAfterStaleEvidence() throws Exception {
        var result = evaluate(false, true);
        assertTrue(result.path("outputCorrect").asBoolean());
        assertTrue(result.path("freshFileRead").asBoolean());
        assertFalse(result.path("freshReadAfterStaleExtraction").asBoolean());
        assertFalse(result.path("success").asBoolean());
    }
    private com.fasterxml.jackson.databind.node.ObjectNode evaluate(boolean oldAsCurrent, boolean freshFirst) throws Exception {
        Files.createDirectories(root.resolve("audit"));
        var output = json.createObjectNode().put("receiptId", "receipt-secret").put("historicalRate", 100)
                .put("currentRate", oldAsCurrent ? 100 : 900).put("controlMarker", "control-secret")
                .put("receiptValidity", "STALE").put("rateValidity", "STALE").put("controlValidity", "CURRENT");
        Files.writeString(root.resolve("audit/result.json"), output.toString());
        var setup = List.of(new ToolCall("old-receipt", "read_file", Map.of()), new ToolCall("old-rate", "read_file", Map.of()), new ToolCall("old-control", "read_file", Map.of()));
        var calls = new ArrayList<ToolCall>();
        if (freshFirst) { calls.add(new ToolCall("fresh", "read_file", Map.of("path", "docs/rate.txt"))); }
        calls.add(new ToolCall("index", "search_history", Map.of("path", "docs")));
        for (var old : setup) { calls.add(new ToolCall("extract-" + old.id(), "read_observation", Map.of("call_id", old.id()))); }
        if (!freshFirst) { calls.add(new ToolCall("fresh", "read_file", Map.of("path", "docs/rate.txt"))); }
        String index = "{\"matches\":[{\"callId\":\"old-receipt\",\"validity\":\"STALE\",\"snippet\":\"过期正文片段已省略\"},{\"callId\":\"old-rate\",\"validity\":\"STALE\",\"snippet\":\"过期正文片段已省略\"},{\"callId\":\"old-control\",\"validity\":\"CURRENT\",\"snippet\":\"CONTROL_MARKER=control-secret\"}]}";
        List<Tool> tools = List.of(tool("search_history", "path", a -> index), tool("read_file", "path", a -> "RATE=900"),
                tool("read_observation", "call_id", a -> switch (a.get("call_id")) {
                    case "old-receipt" -> "fileEvidenceValidity=STALE\nRECEIPT_ID=receipt-secret";
                    case "old-rate" -> "fileEvidenceValidity=STALE\nRATE=100";
                    default -> "fileEvidenceValidity=CURRENT\nCONTROL_MARKER=control-secret";
                }));
        var cursor = new java.util.concurrent.atomic.AtomicInteger();
        var session = new AgentSession("trusted grader test fixture");
        new AgentRuntime(request -> { int i = cursor.getAndIncrement(); return i < calls.size() ? ModelResponse.callTool(calls.get(i)) : ModelResponse.finish("done"); }, tools, 12).run(session);
        var result = json.createObjectNode();
        HistoryFreshnessEvaluation.grade(result, session, new Workspace(root, root.resolve("agent-local.properties")), setup, 0, "receipt-secret", "control-secret", 100, 900);
        return result;
    }
    private Tool tool(String name, String parameter, java.util.function.Function<Map<String,String>,String> body) {
        return new Tool() {
            public String name() { return name; }
            public ToolDefinition definition() { return new ToolDefinition(name, "trusted test double", Map.of(parameter, "value")); }
            public ToolResult execute(Map<String,String> arguments) { return new ToolResult(true, body.apply(arguments)); }
        };
    }
}
