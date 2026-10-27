package dev.backendagent.ptc;

import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.backendagent.model.*;
import dev.backendagent.tools.*;
import dev.backendagent.runtime.AgentSession;
import dev.backendagent.persistence.PersistenceException;

/** Program gateway. Java owns child execution/state; only outer output becomes model tool messages. */
public final class PtcExecution {
    public static final String NAME = "run_code";
    public static final int MAX_CODE_CHARS = 32000;
    public static final int MAX_CALLS = 16;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final PtcExecutor executor;
    private final Map<String, Tool> bindings;

    @FunctionalInterface
    public interface ChildExecutor { ToolExchange execute(AgentSession session, ToolCall call, String parent); }
    private final ChildExecutor children;

    public PtcExecution(PtcExecutor executor, Collection<Tool> tools, ChildExecutor children) {
        this.children = Objects.requireNonNull(children);
        this.executor = Objects.requireNonNull(executor);
        var selected = new LinkedHashMap<String, Tool>();
        tools.stream().forEach(t -> selected.put(t.name(), t));
        this.bindings = Collections.unmodifiableMap(selected);
    }

    public ToolDefinition definition() {
        return new ToolDefinition(NAME,
                "执行Python异步函数体，不写def包装；code与description均必填。"
                + "参数结构：{\"code\":\"<实际程序>\",\"description\":\"<用途>\"}，占位内容须替换，键名不可重复。"
                + "所有SDK工具仅在程序内await tools.name(参数名=值)调用；兼容旧dict写法，简单操作可直接return await工具结果。"
                + "子调用串行，可依赖已完成结果继续调用或做确定性循环、分支和筛选；仅return/print进入下次模型上下文，中间结果不单独展示，避免整批原样输出或同时print和return相同内容。"
                + "失败抛ToolCallError，属性tool_name/message；可try/except，按missing/unexpected修正参数。已完成调用不会自动回滚，失败后核对已有结果，不盲目重放整段程序。"
                + "无项目挂载、无网络，工具由Java校验执行；禁止嵌套run_code，最多16次子调用、32000字符代码、24000字符输出。"
                + PtcSdk.render(bindings.values().stream().map(Tool::definition).toList()),
                Map.of("code", "Python async函数体，不写def包装；按SDK签名await工具，return必要结果",
                       "description", "必填，与code同级的程序用途，1至100字符"));
    }

    public ToolResult execute(AgentSession session, ToolCall parent) {
        var args = parent.arguments();
        String argumentError=argumentError(args);
        if(argumentError!=null)return new ToolResult(false,argumentError);
        String runId = UUID.randomUUID().toString();
        var receipts = JSON.createArrayNode();
        int[] calls = {0}; boolean[] exhausted = {false}; long[] intermediateBytes = {0};
        PtcRunResult outcome = executor.runWithDefinitions(args.get("code"), bindings.values().stream().map(Tool::definition).toList(), (name, arguments) -> {
            if (++calls[0] > MAX_CALLS) {
                exhausted[0] = true;
                throw new IllegalArgumentException("PTC_CALL_LIMIT: maximum 16 child calls");
            }
            String childId = parent.id() + ":ptc:" + runId + ":" + calls[0];
            var child = new ToolCall(childId, name, arguments);
            session.recordPtcSubcall(AgentSession.EventType.PTC_TOOL_CALL_STARTED, "parent=" + parent.id() + ", tool=" + name,
                    Map.of("parentCallId", parent.id(), "runId", runId, "call", child));
            var exchange = children.execute(session, child, parent.id());
            var result = exchange.result();
            session.recordPtcSubcall(AgentSession.EventType.PTC_TOOL_CALL_COMPLETED, "parent=" + parent.id() + ", tool=" + name
                    + ", successful=" + result.successful(), Map.of("parentCallId", parent.id(), "runId", runId, "exchange", exchange));
            var receipt = receipts.addObject().put("callId", childId).put("tool", name).put("successful", result.successful());
            if (!result.successful()) receipt.put("error", failureDiagnostic(name,result.content()));
            receipt.put("sourceCallId", childId);
            if (result.historicalEvidence() != null) receipt.put("sourceCallId", result.historicalEvidence().getSourceCallId());
            else if (name.equals("read_observation") && result.successful()) receipt.put("sourceCallId", arguments.get("call_id"));
            intermediateBytes[0] += result.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (intermediateBytes[0] > 512 * 1024) {
                exhausted[0] = true; throw new IllegalArgumentException("PTC_INTERMEDIATE_LIMIT: maximum 512 KiB per program");
            }
            if (!result.successful()) throw new IllegalArgumentException(result.content());
            var value = canonical(name, arguments, result);
            if (value.isObject()) ((ObjectNode)value).put("call_id", childId);
            return value;
        });
        var body = JSON.createObjectNode().put("kind", "program_result").put("usageScope", "TOOL_PROGRAM_OUTPUT");
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

    private static String argumentError(Map<String,String> args) {
        var required=Set.of("code","description");
        var missing=new TreeSet<>(required);missing.removeAll(args.keySet());
        var unexpected=new TreeSet<>(args.keySet());unexpected.removeAll(required);
        var invalid=new ArrayList<String>();
        if(args.containsKey("code")) {
            if(args.get("code").isBlank())invalid.add("code:blank");
            else if(args.get("code").length()>MAX_CODE_CHARS)invalid.add("code:too_long");
        }
        if(args.containsKey("description")) {
            if(args.get("description").isBlank())invalid.add("description:blank");
            else if(args.get("description").length()>100)invalid.add("description:too_long");
        }
        if(missing.isEmpty()&&unexpected.isEmpty()&&invalid.isEmpty())return null;
        return "PTC_INVALID_ARGUMENTS: run_code; missing="+missing+"; unexpected="+unexpected+"; invalid="+invalid
                +"；code必须为非空Python函数体，最多32000字符；description为1至100字符。程序未启动，子工具未执行。"
                +(missing.equals(Set.of("description")) && invalid.isEmpty() && unexpected.isEmpty()
                    ? "保留原code，仅补齐同级description；不要用无关占位程序试探。" : "");
    }

    private static String failureDiagnostic(String tool, String content) {
        if (tool.equals("run_tests")) {
            var selected = new StringBuilder();
            for (String line : content.split("\\R")) {
                if (!(line.startsWith("[ERROR]") || line.contains("expected:") || line.contains("Caused by:"))) continue;
                int remaining = 1200 - selected.length();
                if (remaining <= 1) break;
                selected.append(line,0,Math.min(line.length(),remaining-1)).append('\n');
            }
            if (!selected.isEmpty()) return selected.toString();
        }
        return content.substring(0, Math.min(400,content.length()));
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
                default -> {
                    var value = JSON.createObjectNode().put("successful", result.successful());
                    String content = result.content();
                    if (name.equals("read_file")) {
                        if (content.startsWith("file=")) content = content.substring(content.indexOf('\n') + 1);
                        content = content.replaceAll("(?m)^\\d+: ", "");
                    }
                    value.put("content",content);
                    if (result.fileFingerprint()!=null) value.set("fileFingerprint",JSON.valueToTree(result.fileFingerprint()));
                    if (result.readMetadata()!=null) value.set("readMetadata",JSON.valueToTree(result.readMetadata()));
                    yield value;
                }
            };
        } catch (java.io.IOException invalid) { throw new IllegalStateException("PTC_RESULT_SCHEMA: malformed structured tool result", invalid); }
    }
}
