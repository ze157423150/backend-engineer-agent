package dev.backendagent.runtime;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayDeque;
import dev.backendagent.model.ToolExchange;
import dev.backendagent.model.ToolResult;

/** Pure replay of managed writes: changes only the outgoing view, never the original history. */
public final class StaleObservationFilter {
    private StaleObservationFilter() { }

    public static Set<String> staleIds(List<ToolExchange> raw) {
        var stale = new HashSet<String>();
        var reads = new HashMap<Path, Set<String>>();
        var searches = new HashSet<String>();
        var tests = new HashSet<String>();
        var aliases = new HashMap<String, Set<String>>();
        for (var exchange : raw) {
            String tool = exchange.call().name();
            String id = exchange.call().id();
            String path = exchange.call().arguments().get("path");
            if (WorkspaceState.isSuccessfulWrite(exchange)) {
                var changedReads = path == null ? null : reads.remove(Path.of(path).normalize());
                if (changedReads != null) { markStale(changedReads, stale, aliases); }
                markStale(searches, stale, aliases);
                markStale(tests, stale, aliases);
                searches.clear();
                tests.clear();
            }
            if (tool.equals("read_file") && exchange.result().successful() && path != null) {
                reads.computeIfAbsent(Path.of(path).normalize(), ignored -> new HashSet<>()).add(id);
            } else if ((tool.equals("search_code") || tool.equals("search_history")) && exchange.result().successful()) {
                searches.add(id);
            } else if (tool.equals("run_tests")) {
                tests.add(id);
            } else if (tool.equals("read_observation")) {
                String source = exchange.call().arguments().get("call_id");
                if (source != null) {
                    aliases.computeIfAbsent(source, ignored -> new HashSet<>()).add(id);
                    if (stale.contains(source)) { markStale(Set.of(id), stale, aliases); }
                }
            }
        }
        return Set.copyOf(stale);
    }

    private static void markStale(Set<String> ids, Set<String> stale, Map<String, Set<String>> aliases) {
        var pending = new ArrayDeque<>(ids);
        while (!pending.isEmpty()) {
            String id = pending.removeFirst();
            if (stale.add(id)) { pending.addAll(aliases.getOrDefault(id, Set.of())); }
        }
    }

    public static int newestStart(List<ToolExchange> raw) {
        if (raw.isEmpty()) { return 0; }
        int start = raw.size() - 1;
        int batch = raw.get(start).modelCallNumber();
        while (batch > 0 && start > 0 && raw.get(start - 1).modelCallNumber() == batch) { start--; }
        return start;
    }

    public static List<ToolExchange> filter(List<ToolExchange> raw) {
        var stale = staleIds(raw);
        int newest = newestStart(raw);
        var view = new ArrayList<ToolExchange>();
        for (int index = 0; index < raw.size(); index++) {
            var exchange = raw.get(index);
            // An intentional retrieval must reach the next model turn, with its historical warning.
            boolean explicitRetrieval = index >= newest && exchange.call().name().equals("read_observation");
            if (!stale.contains(exchange.call().id())) { view.add(exchange); continue; }
            if (explicitRetrieval) {
                String warning = "[EXPLICIT_HISTORICAL_RETRIEVAL: STALE] 本次主动取回旧正文，仅供追溯。"
                        + "源记录在本次上下文组装时已过期；正文中的核验字段是提取当时的状态，不能证明当前状态。\n";
                view.add(new ToolExchange(exchange.call(), new ToolResult(exchange.result().successful(),
                        warning + exchange.result().content(), exchange.result().fileFingerprint()),
                        exchange.assistantContent(), exchange.modelCallNumber(), exchange.evidence()));
                continue;
            }
            String action = switch (exchange.call().name()) {
                case "read_file" -> "需要当前代码请重新 read_file";
                case "search_code" -> "搜索范围可能变化，需要当前结果请重新 search_code";
                case "search_history" -> "旧索引中的证据状态可能变化，请重新 search_history";
                case "run_tests" -> "源码已修改，需要当前验证请重新 run_tests";
                default -> "旧提取内容已过期，需要历史追溯请重新 read_observation";
            };
            String placeholder = "[STALE_OBSERVATION_BODY_OMITTED] call_id=" + exchange.call().id()
                    + " tool=" + exchange.call().name() + "\n" + action
                    + "；原文仍保存，可用 read_observation 按调用 ID 提取，仅供历史追溯。";
            view.add(new ToolExchange(exchange.call(), new ToolResult(exchange.result().successful(), placeholder,
                    exchange.result().fileFingerprint()), exchange.assistantContent(), exchange.modelCallNumber(), exchange.evidence()));
        }
        return List.copyOf(view);
    }
}
