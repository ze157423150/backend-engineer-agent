package dev.backendagent.model;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import dev.backendagent.runtime.AgentRuntime;
import dev.backendagent.runtime.AgentSession;
import dev.backendagent.tools.ReadFileTool;
import dev.backendagent.tools.Tool;
import dev.backendagent.tools.Workspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class DeepSeekModelClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ConcurrentLinkedQueue<String> responses = new ConcurrentLinkedQueue<>();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger responseDelayMillis = new AtomicInteger();
    @TempDir
    Path repository;
    private Tool readTool;
    private HttpServer server;
    private URI baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        Files.writeString(repository.resolve("OrderService.java"), "class OrderService { void cancel() { cache.evict(id); } }");
        readTool = new ReadFileTool(new Workspace(repository, repository.resolve("agent-local.properties")));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/");
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                requests.add(json.readTree(exchange.getRequestBody()));
                if (responseDelayMillis.get() > 0) {
                    try {
                        Thread.sleep(responseDelayMillis.get());
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                String body = responses.poll();
                if (body == null) {
                    body = "{}";
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(status.get(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void runtimeCompletesTwoTurnsWithSchemaAndPairedHistory() throws IOException {
        responses.add(toolResponse("{\"path\":\"OrderService.java\",\"start_line\":\"1\",\"end_line\":\"20\"}"));
        responses.add(finalResponse("代码依次取消订单并删除缓存。"));
        var session = new AgentSession("read repository code");
        new AgentRuntime(client(Duration.ofSeconds(5)), List.of(readTool), 4).run(session);

        assertEquals(AgentSession.Status.COMPLETED, session.status());
        assertEquals("代码依次取消订单并删除缓存。", session.answer());
        assertEquals(2, requests.size());
        assertEquals("Bearer test-key", authorization.get());
        JsonNode first = requests.getFirst();
        assertEquals("test-model", first.path("model").asText());
        assertEquals("disabled", first.path("thinking").path("type").asText());
        assertFalse(first.path("stream").asBoolean());
        assertEquals("required", first.path("tool_choice").asText());
        JsonNode function = first.path("tools").get(0).path("function");
        assertEquals("read_file", function.path("name").asText());
        assertEquals("string", function.path("parameters").path("properties").path("path").path("type").asText());
        assertEquals(3, function.path("parameters").path("required").size());
        assertTrue(function.path("parameters").path("required").toString().contains("path"));
        assertFalse(function.path("parameters").path("additionalProperties").asBoolean());

        JsonNode second = requests.get(1);
        assertEquals("auto", second.path("tool_choice").asText());
        JsonNode messages = second.path("messages");
        assertEquals(4, messages.size());
        assertEquals("user", messages.get(1).path("role").asText());
        assertEquals("read repository code", messages.get(1).path("content").asText());
        assertEquals("assistant", messages.get(2).path("role").asText());
        assertEquals("我先读取代码。", messages.get(2).path("content").asText());
        JsonNode replayedCall = messages.get(2).path("tool_calls").get(0);
        assertEquals("call-1", replayedCall.path("id").asText());
        JsonNode replayedArguments = json.readTree(replayedCall.path("function").path("arguments").asText());
        assertEquals("OrderService.java", replayedArguments.path("path").asText());
        assertEquals("tool", messages.get(3).path("role").asText());
        assertEquals("call-1", messages.get(3).path("tool_call_id").asText());
        JsonNode result = json.readTree(messages.get(3).path("content").asText());
        assertTrue(result.path("successful").asBoolean());
        assertTrue(result.path("content").asText().contains("cache.evict(id)"));
    }

    @Test
    void failedToolResultIsIncludedInNextRequest() throws IOException {
        responses.add(toolResponse("{\"path\":\"Missing.java\",\"start_line\":\"1\",\"end_line\":\"20\"}"));
        responses.add(finalResponse("文件不存在，无法分析。"));
        var session = new AgentSession("read missing repository file");
        new AgentRuntime(client(Duration.ofSeconds(5)), List.of(readTool), 4).run(session);

        JsonNode result = json.readTree(requests.get(1).path("messages").get(3).path("content").asText());
        assertFalse(result.path("successful").asBoolean());
        assertTrue(result.path("content").asText().contains("文件不可读取"));
        assertEquals(AgentSession.Status.COMPLETED, session.status());
    }

    @Test
    void httpErrorDoesNotLeakResponseBodyIntoSessionTrace() {
        status.set(401);
        responses.add("{\"error\":\"server echoed sensitive-dummy-key\"}");
        var session = new AgentSession("inspect");
        new AgentRuntime(client(Duration.ofSeconds(5)), List.of(readTool), 4).run(session);

        assertEquals(AgentSession.Status.FAILED, session.status());
        assertEquals(1, requests.size());
        assertTrue(session.events().getLast().detail().contains("HTTP 401"));
        assertFalse(session.events().toString().contains("sensitive-dummy-key"));
        assertFalse(session.events().toString().contains("test-key"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"length", "content_filter", "aborted", "insufficient_system_resource"})
    void incompleteResponsesCannotCompleteSession(String finishReason) throws IOException {
        ObjectNode root = (ObjectNode) json.readTree(finalResponse("partial answer"));
        ((ObjectNode) root.path("choices").get(0)).put("finish_reason", finishReason);
        responses.add(root.toString());
        var session = new AgentSession("inspect");
        new AgentRuntime(client(Duration.ofSeconds(5)), List.of(readTool), 4).run(session);

        assertEquals(AgentSession.Status.FAILED, session.status());
        assertNull(session.answer());
        assertTrue(session.history().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"path\":42}", "{\"path\":\"OrderService.java\",\"extra\":\"x\"}", "not-json"})
    void malformedArgumentsAreRejectedBeforeToolExecution(String arguments) throws IOException {
        responses.add(toolResponse(arguments));
        var session = new AgentSession("inspect");
        new AgentRuntime(client(Duration.ofSeconds(5)), List.of(readTool), 4).run(session);

        assertEquals(AgentSession.Status.FAILED, session.status());
        assertTrue(session.history().isEmpty());
        assertEquals(1, requests.size());
    }

    @Test
    void multipleBatchesPreserveAssistantMessagesAndAllResults() throws IOException {
        responses.add(batchResponse("call-1", "call-2", "Missing.java"));
        responses.add(batchResponse("call-3", "call-4", "OrderService.java"));
        responses.add(finalResponse("分析完成。"));
        var session = new AgentSession("inspect");
        new AgentRuntime(client(Duration.ofSeconds(5)), List.of(readTool), 3).run(session);

        assertEquals(AgentSession.Status.COMPLETED, session.status());
        assertEquals(3, session.modelCalls());
        assertEquals(4, session.history().size());
        JsonNode messages = requests.get(2).path("messages");
        assertEquals(8, messages.size());
        for (int batch = 0; batch < 2; batch++) {
            int offset = 2 + batch * 3;
            assertEquals("assistant", messages.get(offset).path("role").asText());
            assertEquals("我先读取代码。", messages.get(offset).path("content").asText());
            assertEquals(2, messages.get(offset).path("tool_calls").size());
            for (int index = 0; index < 2; index++) {
                String id = "call-" + (batch * 2 + index + 1);
                assertEquals(id, messages.get(offset).path("tool_calls").get(index).path("id").asText());
                JsonNode resultMessage = messages.get(offset + index + 1);
                assertEquals("tool", resultMessage.path("role").asText());
                assertEquals(id, resultMessage.path("tool_call_id").asText());
                JsonNode result = json.readTree(resultMessage.path("content").asText());
                assertEquals(!id.equals("call-2"), result.path("successful").asBoolean());
                if (!id.equals("call-2")) {
                    assertTrue(result.path("content").asText().contains("cache.evict(id)"));
                }
            }
        }
    }

    @Test
    void duplicateIdsInBatchAreRejectedBeforeAnyToolExecution() throws IOException {
        responses.add(batchResponse("call-1", "call-1", "OrderService.java"));
        var session = new AgentSession("inspect");
        new AgentRuntime(client(Duration.ofSeconds(5)), List.of(readTool), 4).run(session);

        assertEquals(AgentSession.Status.FAILED, session.status());
        assertTrue(session.history().isEmpty());
        assertTrue(session.events().getLast().detail().contains("Duplicate tool call id"));
        assertFalse(session.events().stream().anyMatch(event ->
                event.type() == AgentSession.EventType.TOOL_CALL_REQUESTED));
    }

    @Test
    void malformedSecondCallRejectsEntireBatchBeforeExecution() throws IOException {
        ObjectNode root = (ObjectNode) json.readTree(batchResponse("call-1", "call-2", "OrderService.java"));
        ((ObjectNode) root.path("choices").get(0).path("message").path("tool_calls").get(1)
                .path("function")).put("arguments", "{}");
        responses.add(root.toString());
        var session = new AgentSession("inspect");
        new AgentRuntime(client(Duration.ofSeconds(5)), List.of(readTool), 4).run(session);

        assertEquals(AgentSession.Status.FAILED, session.status());
        assertTrue(session.history().isEmpty());
        assertTrue(session.events().getLast().detail().contains("do not match"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "not-json", "{\"choices\":[]}",
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":null}}]}"})
    void malformedOrEmptyResponsesFail(String response) {
        responses.add(response);
        assertThrows(IllegalStateException.class, () -> client(Duration.ofSeconds(5)).execute(request()));
    }

    @Test
    void requestTimeoutIsReported() {
        responseDelayMillis.set(500);
        responses.add("{}");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> client(Duration.ofMillis(100)).execute(request()));
        assertTrue(failure.getMessage().contains("timed out"));
    }

    @Test
    void invalidConfigurationIsRejectedBeforeAnyRequest() {
        assertThrows(IllegalArgumentException.class, () -> new DeepSeekModelClient(
                " ", baseUrl, "model", Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class, () -> new DeepSeekModelClient(
                "test-key", URI.create("http://api.deepseek.com"), "model", Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class, () -> new DeepSeekModelClient(
                "test-key", baseUrl, "model", Duration.ZERO));
        assertTrue(requests.isEmpty());
    }

    private DeepSeekModelClient client(Duration timeout) {
        return new DeepSeekModelClient("test-key", baseUrl, "test-model", timeout);
    }

    @Test
    void includesWorkingMemoryAsReferenceDataBeforePairedToolHistory() throws IOException {
        responses.add(finalResponse("done"));
        var fact = new dev.backendagent.memory.MemoryFact("entry point", "old-read", "read_file",
                "Main.java", "public static void main");
        var retained = new ToolExchange(new ToolCall("recent", "read_file", java.util.Map.of(
                "path", "OrderService.java", "start_line", "1", "end_line", "20")),
                new ToolResult(true, "code evidence"), null, 3);
        client(Duration.ofSeconds(5)).execute(new ModelRequest("inspect", List.of(retained),
                List.of(readTool.definition()), 2, 5, 100, List.of(fact)));
        var messages = requests.getFirst().path("messages");
        assertEquals(5, messages.size());
        assertEquals("user", messages.get(2).path("role").asText());
        var memory = json.readTree(messages.get(2).path("content").asText());
        assertEquals("working_memory_reference_data", memory.path("kind").asText());
        assertEquals("old-read", memory.path("notes").get(0).path("sourceCallId").asText());
        assertEquals("public static void main", memory.path("notes").get(0).path("evidenceQuote").asText());
        assertEquals("recent", messages.get(3).path("tool_calls").get(0).path("id").asText());
        assertEquals("recent", messages.get(4).path("tool_call_id").asText());
    }

    @Test
    void tellsModelWhenOlderHistoryWasOmittedAndReplaysRetainedCall() throws IOException {
        responses.add(finalResponse("done"));
        var retained = new ToolExchange(new ToolCall("recent", "read_file", java.util.Map.of(
                "path", "OrderService.java", "start_line", "1", "end_line", "20")),
                new ToolResult(true, "code evidence"), null, 3);
        client(Duration.ofSeconds(5)).execute(new ModelRequest("inspect", List.of(retained),
                List.of(readTool.definition()), 2, 5, 100));
        var messages = requests.getFirst().path("messages");
        assertTrue(messages.get(0).path("content").asText().contains("省略了较早的 5 条"));
        assertEquals(4, messages.size());
        assertEquals("recent", messages.get(2).path("tool_calls").get(0).path("id").asText());
        assertEquals("recent", messages.get(3).path("tool_call_id").asText());
    }

    private ModelRequest request() {
        return new ModelRequest("inspect", List.of(), List.of(readTool.definition()), 4);
    }

    private String toolResponse(String arguments) {
        ObjectNode root = json.createObjectNode();
        ObjectNode choice = root.putArray("choices").addObject().put("finish_reason", "tool_calls");
        ObjectNode message = choice.putObject("message").put("role", "assistant")
                .put("content", "我先读取代码。");
        message.putArray("tool_calls").addObject().put("id", "call-1").put("type", "function")
                .putObject("function").put("name", "read_file").put("arguments", arguments);
        return root.toString();
    }

    private String finalResponse(String answer) {
        ObjectNode root = json.createObjectNode();
        root.putArray("choices").addObject().put("finish_reason", "stop")
                .putObject("message").put("role", "assistant").put("content", answer);
        return root.toString();
    }

    private String batchResponse(String firstId, String secondId, String secondPath) throws IOException {
        ObjectNode root = (ObjectNode) json.readTree(toolResponse(
                "{\"path\":\"OrderService.java\",\"start_line\":\"1\",\"end_line\":\"20\"}"));
        var calls = (com.fasterxml.jackson.databind.node.ArrayNode) root.path("choices").get(0)
                .path("message").path("tool_calls");
        ((ObjectNode) calls.get(0)).put("id", firstId);
        ObjectNode second = calls.get(0).deepCopy();
        second.put("id", secondId);
        ObjectNode arguments = json.createObjectNode().put("path", secondPath)
                .put("start_line", "1").put("end_line", "20");
        ((ObjectNode) second.path("function")).put("arguments", arguments.toString());
        calls.add(second);
        return root.toString();
    }
}
