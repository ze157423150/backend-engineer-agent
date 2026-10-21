package dev.backendagent.selection;

import java.util.*;
import dev.backendagent.model.ToolExchange;
import dev.backendagent.tools.ToolDefinition;

/** Fixed phase heuristics from the offline replay; successful tests do not hide all tools. */
public final class RuleToolSelector implements ToolSelector {
    public List<ToolDefinition> select(String task, List<ToolExchange> history, List<ToolDefinition> catalog, int limit) {
        var scores = new HashMap<String, Double>();
        catalog.forEach(t -> scores.put(t.getName(), 0.0));
        var latest = history.isEmpty() ? null : history.getLast();
        String name = latest == null ? "" : latest.call().name();
        if (name.equals("run_tests") && !latest.result().successful())
            promote(scores, 12, "read_file", "apply_patch", "apply_patch_batch", "integer_calculate", "run_tests");
        else if (Set.of("apply_patch", "apply_patch_batch", "create_file").contains(name))
            promote(scores, 10, "run_tests", "workspace_diff", "read_file", "apply_patch", "apply_patch_batch");
        else if (name.equals("search_history")) promote(scores, 12, "read_observation", "search_history", "read_file");
        else if (name.equals("search_turns")) promote(scores, 12, "read_turn", "search_turns", "read_file");
        else if (Set.of("read_file", "read_observation", "search_code").contains(name))
            promote(scores, 7, "read_file", "apply_patch", "apply_patch_batch", "integer_calculate", "create_file", "run_tests");
        else promote(scores, 6, "read_file", "list_files", "search_code");
        if (Set.of("apply_patch", "apply_patch_batch", "create_file").contains(name)
                && task.matches("(?is).*(测试|回归|junit|test|新增|创建|新建).*"))
            promote(scores, 8, "create_file");
        if (task.matches("(?s).*(历史|过往|最早|之前|旧实现|旧规范|追溯).*"))
            promote(scores, 4, "search_history", "read_observation", "search_turns", "read_turn");
        if (task.matches("(?is).*(计算|期望|百分位|阈值|比例|算术|溢出|percentile).*")) promote(scores, 3, "integer_calculate");
        if (task.matches("(?s).*(新增|创建|新建|补充.*测试).*")) promote(scores, 2, "create_file");
        return catalog.stream().sorted(Comparator.<ToolDefinition>comparingDouble(t -> -scores.get(t.getName()))
                .thenComparing(ToolDefinition::getName)).limit(limit).toList();
    }
    private static void promote(Map<String, Double> scores, double weight, String... names) {
        for (int i = 0; i < names.length; i++) {
            if (scores.containsKey(names[i])) scores.merge(names[i], weight - i * 0.1, Double::sum);
        }
    }
}
