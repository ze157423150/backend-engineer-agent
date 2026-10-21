"""Offline checks for ranking, metric meaning and target-response isolation."""
import json
import unittest
from common import terms
from build_history_samples import project, equivalent_catalog
from evaluate import score
import bm25_selector
import rule_selector


def tool(name, description):
    return {"type": "function", "function": {"name": name, "description": description,
            "parameters": {"type": "object", "properties": {}}}}


class SelectorTest(unittest.TestCase):
    def test_catalog_ignores_required_order_but_not_tool_description_changes(self):
        a = tool("read_file", "Read")
        a["function"]["parameters"]["required"] = ["path", "start"]
        b = json.loads(json.dumps(a))
        b["function"]["parameters"]["required"] = ["start", "path"]
        self.assertTrue(equivalent_catalog([a], [b]))
        b["function"]["description"] = "Changed meaning"
        self.assertFalse(equivalent_catalog([a], [b]))

    def test_partial_batch_does_not_count_as_full_coverage(self):
        label = {"observedNextTools": ["read_file", "search_code"],
                 "acceptableGroups": [["read_file"], ["search_code"]], "finished": False}
        result = score(["read_file"], label)
        self.assertEqual(result["observedHits"], 1)
        self.assertEqual(result["observedCount"], 2)
        self.assertFalse(result["allCapabilitiesCovered"])

    def test_patch_alternative_does_not_falsify_exact_action_recall(self):
        label = {"observedNextTools": ["apply_patch"],
                 "acceptableGroups": [["apply_patch", "apply_patch_batch"]], "finished": False}
        result = score(["apply_patch_batch"], label)
        self.assertEqual(result["observedHits"], 0)
        self.assertTrue(result["allCapabilitiesCovered"])

    def test_finished_state_is_separate_from_positive_recall(self):
        label = {"observedNextTools": [], "acceptableGroups": [], "finished": True}
        self.assertTrue(score([], label)["emptyOnFinishedState"])
        self.assertFalse(score([], label)["allCapabilitiesCovered"])
        self.assertEqual(score(["read_file"], label)["observedCount"], 0)

    def test_bm25_prefers_matching_function_and_respects_limit(self):
        catalog = [tool("read_file", "Read source text"), tool("integer_calculate", "Calculate integer expression"),
                   tool("run_tests", "Run Maven tests")]
        state = {"task": "calculate integer expression", "recentTools": []}
        self.assertEqual(bm25_selector.select(state, catalog, 1), ["integer_calculate"])
        self.assertEqual(len(bm25_selector.select(state, catalog, 2)), 2)

    def test_lexical_tokens_include_identifier_parts_and_chinese_bigrams(self):
        output = terms("read_file timeoutMs 文件读取")
        self.assertIn("read", output)
        self.assertIn("timeout", output)
        self.assertIn("ms", output)
        self.assertIn("文件", output)
        self.assertIn("读取", output)

    def test_rules_keep_repair_capabilities_after_failed_test(self):
        catalog = [tool(n, "") for n in ["read_file", "apply_patch", "apply_patch_batch", "run_tests", "integer_calculate", "list_files"]]
        state = {"task": "修复代码", "recentTools": [{"name": "run_tests", "successful": False, "content": "failure"}]}
        selected = rule_selector.select(state, catalog, 5)
        self.assertIn("run_tests", selected)
        self.assertIn("read_file", selected)
        self.assertIn("apply_patch", selected)

    def test_input_projection_excludes_previous_turn_results_and_future_labels(self):
        request = {"messages": [
            {"role": "user", "content": "old task"},
            {"role": "assistant", "tool_calls": [{"id": "a", "function": {"name": "run_tests"}}]},
            {"role": "tool", "tool_call_id": "a", "content": json.dumps({"successful": True, "content": "OLD_RESULT"})},
            {"role": "user", "content": json.dumps({"kind": "state", "notice": "metadata"})},
            {"role": "user", "content": "new task"}]}
        value = project(request)
        self.assertEqual(value["task"], "new task")
        self.assertEqual(value["recentTools"], [])
        self.assertNotIn("OLD_RESULT", json.dumps(value))
        self.assertNotIn("observedNextTools", value)

    def test_input_keeps_current_tool_identity_without_including_next_action(self):
        request = {"messages": [
            {"role": "user", "content": "current task"},
            {"role": "assistant", "tool_calls": [{"id": "r", "function": {"name": "read_file"}}]},
            {"role": "tool", "tool_call_id": "r", "content": json.dumps({"successful": True, "content": "source"})}]}
        self.assertEqual(project(request)["recentTools"], [{"name": "read_file", "successful": True, "content": "source"}])


if __name__ == "__main__":
    unittest.main()
