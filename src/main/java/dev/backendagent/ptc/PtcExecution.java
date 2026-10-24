package dev.backendagent.ptc;

import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.backendagent.model.*;
import dev.backendagent.tools.*;
import dev.backendagent.runtime.AgentSession;
import dev.backendagent.persistence.PersistenceException;

/** Read-only program gateway. Child bodies go to the event archive, not ordinary model history. */
public final class PtcExecution {
    public static final String NAME = "run_code";
    public static final int MAX_CODE_CHARS = 8000;
    public static final int MAX_CALLS = 16;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final PtcExecutor executor;
    private final Map<String, Tool> bindings;

    public PtcExecution(PtcExecutor executor, Collection<Tool> tools) {
        this.executor = Objects.requireNonNull(executor);
        var selected = new LinkedHashMap<String, Tool>();
        tools.stream().filter(t -> PtcSdk.READ_ONLY.contains(t.name())).forEach(t -> selected.put(t.name(), t));
        this.bindings = Collections.unmodifiableMap(selected);
    }

    public ToolDefinition definition() {
        return new ToolDefinition(NAME,
                "执行Python异步函数体，支持await和return。仅编排下列只读工具，普通文件修改和测试仍直接调用原工具。"
                + "适合后一调用依赖前一结果、循环检索及结果整理；简单单次操作直接用普通工具。"
                + "中间结果留在程序，只把return值和print输出交给模型；失败抛ToolCallError，可try/except，属性tool_name。"
                + "无项目目录挂载、无网络，不用open读取源码。子调用串行，不支持嵌套run_code；最多16次子调用，代码最多8000字符，最终输出限6000字符。"
                + "历史结果保留来源和时效，程序输出不等于已验证的业务结论。"
                + PtcSdk.render(bindings.values().stream().map(Tool::definition).toList()),
                Map.of("code", "Python async函数体，不写def包装；直接await tools.name({...})，return必要结果",
                       "description", "程序用途，1至100字符"));
    }

    public ToolResult execute(AgentSession session, ToolCall parent) {
        var args = parent.arguments();
        if (!args.keySet().equals(Set.of("code", "description")) || args.get("code").isBlank()
                || args.get("code").length() > MAX_CODE_CHARS || args.get("description").isBlank()
                || args.get("description").length() > 100) {
            return new ToolResult(false, "PTC_INVALID_ARGUMENTS: code最多8000字符，description为1至100字符");
        }
        String runId = UUID.randomUUID().toString();
        var receipts = JSON.createArrayNode();
        int[] calls = {0}; boolean[] exhausted = {false}; long[] intermediateBytes = {0};
        PtcRunResult outcome = executor.run(args.get("code"), List.copyOf(bindings.keySet()), (name, arguments) -> {
            if (++calls[0] > MAX_CALLS) {
                exhausted[0] = true;
                throw new IllegalArgumentException("PTC_CALL_LIMIT: maximum 16 child calls");
            }
            String childId = parent.id() + ":ptc:" + runId + ":" + calls[0];
            var child = new ToolCall(childId, name, arguments);
            session.recordPtcSubcall(AgentSession.EventType.PTC_TOOL_CALL_STARTED, "parent=" + parent.id() + ", tool=" + name,
                    Map.of("parentCallId", parent.id(), "runId", runId, "call", child));
            ToolResult result;
            var tool = bindings.get(name);
            if (tool == null) result = new ToolResult(false, "PTC_TOOL_NOT_ALLOWED: binding not available");
            else if (!tool.definition().getParameters().keySet().containsAll(arguments.keySet())
                    || !arguments.keySet().containsAll(tool.definition().getRequiredParameters())) {
                result = new ToolResult(false, "PTC_INVALID_TOOL_ARGUMENTS: use the generated SDK parameters");
            } else {
                try { result = Objects.requireNonNull(tool.execute(arguments)); }
                catch (PersistenceException fatal) { throw fatal; }
                catch (RuntimeException failure) { result = new ToolResult(false, failure.getClass().getSimpleName() + ": " + failure.getMessage()); }
            }
            var exchange = new ToolExchange(child, result, null, session.modelCalls());
            session.recordPtcSubcall(AgentSession.EventType.PTC_TOOL_CALL_COMPLETED, "parent=" + parent.id() + ", tool=" + name
                    + ", successful=" + result.successful(), Map.of("parentCallId", parent.id(), "runId", runId, "exchange", exchange));
            var receipt = receipts.addObject().put("callId", childId).put("tool", name).put("successful", result.successful());
            if (!result.successful()) receipt.put("error", result.content().substring(0, Math.min(200, result.content().length())));
            if (result.historicalEvidence() != null) receipt.put("sourceCallId", result.historicalEvidence().getSourceCallId());
            else if (name.equals("read_observation") && result.successful()) receipt.put("sourceCallId", arguments.get("call_id"));
            intermediateBytes[0] += result.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (intermediateBytes[0] > 512 * 1024) {
                exhausted[0] = true; throw new IllegalArgumentException("PTC_INTERMEDIATE_LIMIT: maximum 512 KiB per program");
            }
            if (!result.successful()) throw new IllegalArgumentException(result.content());
            return canonical(name, arguments, result);
        });
        var body = JSON.createObjectNode().put("kind", "program_result").put("usageScope", "REFERENCE_ONLY");
        body.set("result", outcome.value()); body.set("logs", JSON.valueToTree(outcome.logs()));
        body.put("callCount", Math.min(calls[0], MAX_CALLS));
        var failed = body.putArray("failedCalls");
        for (var receipt : receipts) if (!receipt.path("successful").asBoolean()) failed.add(receipt);
        var sources = body.putArray("sources");
        var seen = new HashSet<String>();
        for (var receipt : receipts) if (receipt.has("sourceCallId") && seen.add(receipt.path("sourceCallId").asText()))
            sources.add(receipt.path("sourceCallId").asText());
        if (outcome.error() != null) body.put("error", outcome.error());
        if (exhausted[0]) body.put("error", "PTC_LIMIT_EXCEEDED");
        return new ToolResult(outcome.successful() && !exhausted[0], body.toString());
    }

    private JsonNode canonical(String name, Map<String, String> args, ToolResult result) {
        try {
            return switch (name) {
                case "integer_calculate" -> JSON.createObjectNode().put("expression", args.get("expression"))
                        .put("result_name", args.get("result_name")).put("value", result.content().substring(result.content().indexOf('=') + 1));
                case "search_history", "search_turns" -> JSON.readTree(result.content());
                case "read_turn" -> {
                    var message = (ObjectNode) JSON.readTree(result.content());
                    // Native rendering numbers each source line. Program values carry text,
                    // while the durable ToolResult remains byte-for-byte unchanged.
                    message.put("content", message.path("content").asText().replaceAll("(?m)^\\d+: ", ""));
                    yield message;
                }
                case "read_observation" -> JSON.createObjectNode().put("sourceCallId", args.get("call_id"))
                        .put("content", result.content()).set("evidence", JSON.valueToTree(result.historicalEvidence()));
                default -> throw new IllegalArgumentException("Unsupported binding");
            };
        } catch (java.io.IOException invalid) { throw new IllegalStateException("PTC_RESULT_SCHEMA: malformed structured tool result", invalid); }
    }
}
