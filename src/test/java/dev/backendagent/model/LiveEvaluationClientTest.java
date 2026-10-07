package dev.backendagent.model;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import dev.backendagent.config.ModelConfig;
import dev.backendagent.runtime.*;
import dev.backendagent.tools.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LiveEvaluationClientTest {
    private HttpServer server(String usage, AtomicInteger requests) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] response = ("{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"done\"}}]"
                    + usage + "}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        return server;
    }
    private DeepSeekModelClient client(HttpServer server, List<ModelCallMetrics> calls) {
        return new DeepSeekModelClient(new ModelConfig("offline-test-key", URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "deepseek-chat", Duration.ofSeconds(2), Duration.ofSeconds(5)), calls::add);
    }
    @Test
    void recordsProviderUsageAndDoesNotExposeCredentialOrRawResponse() throws Exception {
        var requests = new AtomicInteger();
        var server = server(",\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20,\"total_tokens\":120}", requests);
        try {
            var calls = new ArrayList<ModelCallMetrics>();
            assertEquals("done", client(server, calls).execute(new ModelRequest("inspect", List.of(), List.of(), 1)).getAnswer());
            assertEquals(1, requests.get());
            var metric = calls.getFirst();
            assertEquals(100L, metric.getPromptTokens());
            assertEquals(20L, metric.getCompletionTokens());
            assertEquals(120L, metric.getTotalTokens());
            assertTrue(metric.getRequestBytes() > 0);
            String encoded = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(metric);
            assertFalse(encoded.contains("offline-test-key"));
            assertFalse(encoded.contains("\"content\""));
        } finally { server.stop(0); }
    }
    @Test
    void absentOrInvalidReportedUsageRemainsUnknown() throws Exception {
        for (String usage : List.of("", ",\"usage\":{\"prompt_tokens\":-1,\"completion_tokens\":\"20\",\"total_tokens\":1.5}")) {
            var server = server(usage, new AtomicInteger());
            try {
                var calls = new ArrayList<ModelCallMetrics>();
                client(server, calls).execute(new ModelRequest("inspect", List.of(), List.of(), 1));
                assertNull(calls.getFirst().getPromptTokens());
                assertNull(calls.getFirst().getCompletionTokens());
                assertNull(calls.getFirst().getTotalTokens());
            } finally { server.stop(0); }
        }
    }
    @Test
    void fullHistoryModeDoesNotSilentlyClipAndBudgetRejectionMakesNoHttpRequest() throws Exception {
        var requests = new AtomicInteger();
        var server = server("", requests);
        try {
            var session = new AgentSession("comparison");
            var turns = new AtomicInteger();
            Tool tool = new Tool() {
                public String name() { return "read_file"; }
                public ToolResult execute(Map<String, String> args) { return new ToolResult(true, "x".repeat(14000)); }
            };
            new AgentRuntime(request -> ModelResponse.callTool(new ToolCall("read-" + turns.getAndIncrement(), "read_file", Map.of())),
                    List.of(tool), 5).run(session);
            var request = new ContextAssembler().assemble(session, List.of(tool.definition()), 1);
            var client = client(server, new ArrayList<>());
            var baseline = RealModelEvaluation.evaluationModel(client, session, "full-history");
            assertFalse(baseline.supportsSummarization());
            assertFalse(baseline.inspectRequest(request).isWithinBudget());
            assertTrue(RealModelEvaluation.evaluationModel(client, session, "layered").inspectRequest(request).isWithinBudget());
            assertThrows(IllegalStateException.class, () -> baseline.execute(request));
            assertEquals(0, requests.get());
        } finally { server.stop(0); }
    }
}
