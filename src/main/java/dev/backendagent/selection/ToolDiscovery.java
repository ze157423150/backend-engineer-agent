package dev.backendagent.selection;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.backendagent.model.*;
import dev.backendagent.runtime.AgentSession;
import dev.backendagent.tools.ToolDefinition;

/** Turn-local discovery reconstructed from durable calls, without another checkpoint format. */
public final class ToolDiscovery {
    public static final String NAME = "search_tools";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RECOVERY_PREFIX = "toolSelectionRecovery=";
    private final List<ToolDefinition> catalog;
    private final ToolSelector selector;
    private final int initialLimit;
    private final ToolSearchRanker searchRanker;
    public ToolDiscovery(List<ToolDefinition> catalog) { this(catalog, new CoreToolSelector(), 8, new Bm25ToolSearchRanker()); }
    public ToolDiscovery(List<ToolDefinition> catalog, ToolSelector selector, int initialLimit) {
        this(catalog,selector,initialLimit,new LexicalToolSearchRanker());
    }
    public ToolDiscovery(List<ToolDefinition> catalog, ToolSelector selector, int initialLimit,ToolSearchRanker searchRanker) {
        if (initialLimit < 0 || catalog.stream().anyMatch(t -> t.getName().equals(NAME)))
            throw new IllegalArgumentException("Invalid discovery catalog or limit");
        this.catalog = List.copyOf(catalog);
        this.selector = Objects.requireNonNull(selector);
        this.initialLimit = initialLimit;
        this.searchRanker=Objects.requireNonNull(searchRanker);
    }
    public static ToolDefinition definition() {
        return new ToolDefinition(NAME,
                "加载工具：query可写目录中的准确名称或用途。精确名称直接加载；用途用相关性搜索。连续无新增时扩大范围并最终恢复全部注册工具。参数定义在下一次请求中提供，本次不执行目标工具。",
                Map.of("query", "需要的能力或工具名称，1至300字符；未找到时改用更宽泛的关键词"));
    }
    public List<ToolDefinition> definitions(AgentSession session) {
        var state = replay(session);
        var names = selected(task(session), currentHistory(session));
        names.addAll(state.discovered);
        var result = new ArrayList<>(catalog.stream().filter(t -> names.contains(t.getName())).toList());
        var search = definition();
        if (selector instanceof CoreToolSelector) {
            var directory = new StringBuilder("\n待加载目录（仅名称与用途，不可直接调用）：");
            for (var tool : catalog) {
                if (!names.contains(tool.getName())) directory.append('\n').append(tool.getName())
                        .append(": ").append(shortDescription(tool));
            }
            if (catalog.stream().anyMatch(t -> !names.contains(t.getName())))
                search = new ToolDefinition(NAME, search.getDescription() + directory, search.getParameters());
        }
        String feedback = recoveryFeedback(session);
        result.add(feedback.isEmpty() ? search : new ToolDefinition(NAME, search.getDescription() + feedback, search.getParameters()));
        return List.copyOf(result);
    }
    private static String shortDescription(ToolDefinition tool) {
        return switch (tool.getName()) {
            case "apply_patch_batch" -> "同一文件多处非重叠补丁，原子写入";
            case "create_file" -> "创建不存在的新文件，不覆盖已有文件";
            case "integer_calculate" -> "核算整数表达式，标注业务量和单位";
            case "read_observation" -> "按调用ID提取归档工具结果";
            case "search_history" -> "搜索归档工具结果，定位调用ID";
            case "search_turns" -> "搜索旧用户需求与助手回答";
            case "read_turn" -> "按轮次来源ID提取旧需求或回答";
            case "remember_fact" -> "保存带来源的重要结论";
            case "pin_verification_basis" -> "提取并保留相关规范原文";
            case "workspace_diff" -> "查看本轮或整个会话的文件改动";
            default -> tool.getDescription().substring(0, Math.min(50, tool.getDescription().length()));
        };
    }
    /** Persist only registered names, using the existing retry event. No rejected arguments are retained. */
    public String recoveryEvent(AgentSession session, List<String> proposedNames) {
        var known = catalog.stream().map(ToolDefinition::getName).filter(proposedNames::contains).toList();
        boolean full = known.size() != new HashSet<>(proposedNames).size();
        var data = JSON.createObjectNode().put("turn", session.turns().size()).put("modelCall", session.modelCalls())
                .put("full", full);
        var names = data.putArray("tools"); known.forEach(names::add);
        return RECOVERY_PREFIX + data;
    }
    private List<com.fasterxml.jackson.databind.JsonNode> recoveries(AgentSession session) {
        var result = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        for (var event : session.events()) {
            if (event.type() != AgentSession.EventType.MODEL_RETRY_SCHEDULED || !event.detail().startsWith(RECOVERY_PREFIX)) continue;
            try {
                var value = JSON.readTree(event.detail().substring(RECOVERY_PREFIX.length()));
                if (value.path("turn").asInt() == session.turns().size()) result.add(value);
            } catch (java.io.IOException invalid) { throw new IllegalStateException("Invalid durable tool-selection recovery"); }
        }
        return result;
    }
    private String recoveryFeedback(AgentSession session) {
        var events = recoveries(session);
        if (events.isEmpty()) return "";
        var latest = events.getLast();
        if (latest.path("modelCall").asInt() != session.modelCalls()) return "";
        return latest.path("full").asBoolean()
                ? "上次请求包含未注册工具，整个批次未执行；现已恢复全部已注册工具。请只用当前工具定义重新生成调用，缺少能力时如实说明。"
                : latest.path("tools").toString() + " 尚未加载；上次整个批次未执行。现已提供完整参数定义，请按定义重新生成必要调用，不要假定上次操作已成功。";
    }

    public ToolResult search(AgentSession session, Map<String, String> arguments) {
        String query = arguments.get("query");
        if (query == null || query.isBlank() || query.length() > 300 || arguments.size() != 1)
            return new ToolResult(false, "query必须为1至300字符的非空需求描述；仅接受query参数。");
        var state = replay(session);
        var visible = selected(task(session), currentHistory(session));
        visible.addAll(state.discovered);
        var outcome = find(query, state.attempts + 1, visible);
        var output = JSON.createObjectNode().put("status", outcome.status)
                .put("attempt", Math.min(state.attempts + 1, 3));
        var found = output.putArray("tools");
        for (var tool : outcome.tools) found.addObject().put("name", tool.getName())
                .put("description", tool.getDescription().substring(0, Math.min(90, tool.getDescription().length())));
        output.put("message", switch (outcome.status) {
            case "NO_MATCH" -> "未匹配到工具。请扩大需求描述或换用工具用途关键词再搜索；下一次会扩大匹配范围。";
            case "ALREADY_VISIBLE" -> "匹配的工具已可见，直接使用当前定义；再次搜索仍无新增时将恢复全部工具。";
            case "FULL_FALLBACK" -> "已恢复全部已注册工具，下一次请求可用。若仍无所需能力，请如实说明，不重复搜索。";
            default -> "已补充匹配工具，完整参数定义在下一次请求中可用；本次不执行这些工具。";
        });
        return new ToolResult(true, output.toString());
    }
    private State replay(AgentSession session) {
        var state = new State();
        for (var exchange : currentHistory(session)) {
            if (exchange.call().name().equals(NAME) && exchange.result().successful()) {
                // Restore the actual durable selection, not a fresh ranking that may change after upgrade.
                try {
                    var saved = JSON.readTree(exchange.result().content());
                    String status = saved.path("status").asText();
                    if (!Set.of("ADDED", "NO_MATCH", "ALREADY_VISIBLE", "FULL_FALLBACK").contains(status))
                        throw new IllegalStateException("Invalid durable tool search status");
                    for (var item : saved.path("tools")) {
                        String name = item.path("name").asText();
                        if (catalog.stream().anyMatch(t -> t.getName().equals(name))) state.discovered.add(name);
                    }
                    if (status.equals("FULL_FALLBACK")) catalog.forEach(t -> state.discovered.add(t.getName()));
                    state.attempts = status.equals("ADDED") ? 0 : state.attempts + 1;
                } catch (java.io.IOException invalid) {
                    throw new IllegalStateException("Invalid durable tool search result", invalid);
                }
            }
        }
        for (var recovery : recoveries(session)) {
            if (recovery.path("full").asBoolean()) catalog.forEach(t -> state.discovered.add(t.getName()));
            else for (var name : recovery.path("tools")) {
                if (catalog.stream().anyMatch(t -> t.getName().equals(name.asText()))) state.discovered.add(name.asText());
            }
        }
        return state;
    }
    private Outcome find(String query, int attempt, Set<String> visible) {
        var exact = catalog.stream().filter(t -> t.getName().equals(query.trim())).findFirst();
        if (exact.isPresent()) return visible.contains(exact.get().getName())
                ? new Outcome("ALREADY_VISIBLE", List.of()) : new Outcome("ADDED", List.of(exact.get()));
        if (attempt >= 3) return new Outcome("FULL_FALLBACK", catalog);
        var ranked = searchRanker.rank(query,catalog,attempt > 1);
        double best = ranked.isEmpty() ? 0 : ranked.getFirst().getScore();
        // Share the same 85% relative-score gate and bounded expansion across rankers.
        var additions = ranked.stream().filter(m -> m.getScore() >= best * 0.85)
                .map(ToolSearchRanker.Match::getTool).filter(t -> !visible.contains(t.getName()))
                .limit(attempt == 1 ? 3 : 5).toList();
        if (!additions.isEmpty()) return new Outcome("ADDED", additions);
        if (attempt >= 2) return new Outcome("FULL_FALLBACK", catalog);
        return new Outcome(ranked.isEmpty() ? "NO_MATCH" : "ALREADY_VISIBLE", List.of());
    }
    private LinkedHashSet<String> selected(String task, List<ToolExchange> history) {
        var names = new LinkedHashSet<String>();
        // A broken selector must not remove the discovery route or block the task.
        try {
            for (var tool : selector.select(task, history, catalog, initialLimit))
                if (catalog.stream().anyMatch(t -> t.getName().equals(tool.getName()))) names.add(tool.getName());
        } catch (RuntimeException failure) { catalog.forEach(t -> names.add(t.getName())); }
        // Executed calls carry validated tool identities, even when the operation failed.
        // Keep those capabilities for this turn so a phase change cannot hide a needed retry.
        // Intersect with the current catalog: history never grants new execution permissions.
        for (var exchange : history) {
            if (catalog.stream().anyMatch(t -> t.getName().equals(exchange.call().name())))
                names.add(exchange.call().name());
        }
        return names;
    }
    private static String task(AgentSession session) { return session.turns().getLast().getUserMessage(); }
    private static List<ToolExchange> currentHistory(AgentSession session) {
        return session.history().subList(session.turns().getLast().getStartHistoryIndex(), session.history().size());
    }
    private static final class State {
        private int attempts;
        private final Set<String> discovered = new LinkedHashSet<>();
    }
    private record Outcome(String status, List<ToolDefinition> tools) { }
}
