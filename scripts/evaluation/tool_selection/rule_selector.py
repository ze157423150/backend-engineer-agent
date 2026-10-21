"""Small fixed heuristic. No rules are fitted to the replay labels."""
import re


def rank(state, catalog):
    task = state["task"].lower()
    history = state.get("recentTools", [])
    latest = history[-1] if history else None
    scores = {tool["function"]["name"]: 0.0 for tool in catalog}

    def promote(names, weight):
        for index, name in enumerate(names):
            if name in scores:
                scores[name] += weight - index * 0.1

    # This selector chooses capabilities, not executable parameters or permissions.
    if latest and latest["name"] == "run_tests" and latest["successful"]:
        return []
    if latest and latest["name"] == "run_tests" and not latest["successful"]:
        promote(["read_file", "apply_patch", "apply_patch_batch", "integer_calculate", "run_tests"], 12)
    elif latest and latest["name"] in {"apply_patch", "apply_patch_batch", "create_file"}:
        promote(["run_tests", "workspace_diff", "read_file", "apply_patch", "apply_patch_batch"], 10)
    elif latest and latest["name"] == "search_history":
        promote(["read_observation", "search_history", "read_file"], 12)
    elif latest and latest["name"] == "search_turns":
        promote(["read_turn", "search_turns", "read_file"], 12)
    elif latest and latest["name"] in {"read_file", "read_observation", "search_code"}:
        promote(["read_file", "apply_patch", "apply_patch_batch", "integer_calculate", "create_file", "run_tests"], 7)
    else:
        promote(["read_file", "list_files", "search_code"], 6)
    if re.search(r"历史|过往|最早|之前|旧实现|旧规范|追溯", task):
        promote(["search_history", "read_observation", "search_turns", "read_turn"], 4)
    if re.search(r"计算|期望|百分位|阈值|比例|算术|溢出|percentile", task):
        promote(["integer_calculate"], 3)
    if re.search(r"新增|创建|新建|补充.*测试", task):
        promote(["create_file"], 2)
    return sorted(scores, key=lambda name: (-scores[name], name))


def select(state, catalog, k):
    return rank(state, catalog)[:k]
