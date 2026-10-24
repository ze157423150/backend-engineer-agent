import json
import subprocess
import sys
import unittest
from pathlib import Path

RUNTIME = Path(__file__).with_name("runtime.py")


class RuntimeTests(unittest.TestCase):
    def execute(self, code, replies=(), bindings=("integer_calculate",)):
        frames = [{"code": code, "bindings": list(bindings)}, *replies]
        result = subprocess.run([sys.executable, "-I", "-u", str(RUNTIME)],
                                input="".join(json.dumps(x)+"\n" for x in frames),
                                capture_output=True, text=True, timeout=5, check=True)
        return [json.loads(x) for x in result.stdout.splitlines()]

    def test_next_call_can_use_previous_result_without_model(self):
        frames = self.execute("first = await tools.integer_calculate({'expression':'2+3'})\n"
                              "second = await tools.integer_calculate({'expression':first['value']+'*2'})\n"
                              "return second", [{"id":1,"ok":True,"value":{"value":"5"}},
                                                {"id":2,"ok":True,"value":{"value":"10"}}])
        self.assertEqual("5*2",frames[1]["arguments"]["expression"])
        self.assertEqual("10",frames[-1]["value"]["value"])
        self.assertEqual([],frames[-1]["logs"])

    def test_tool_error_is_catchable_and_program_output_is_separate(self):
        frames=self.execute("try:\n    await tools.integer_calculate({})\n"
                            "except ToolCallError as e:\n    print(e.tool_name)\nreturn {'handled': True}",
                            [{"id":1,"ok":False,"error":"bad arguments"}])
        self.assertTrue(frames[-1]["value"]["handled"])
        self.assertEqual(["integer_calculate\n"],frames[-1]["logs"])

    def test_syntax_failure_does_not_dispatch(self):
        frames=self.execute("if:")
        self.assertEqual(1,len(frames));self.assertIn("SyntaxError",frames[0]["error"])

    def test_oversized_return_is_not_silently_truncated(self):
        result=self.execute("return 'x'*7000")[-1]
        self.assertIsNone(result["value"]);self.assertIn("PTC_OUTPUT_LIMIT",result["error"])

    def test_catching_print_limit_does_not_hide_overflow(self):
        result=self.execute("try:\n    print('x'*7000)\nexcept ValueError:\n    pass\nreturn 1")[-1]
        self.assertIsNone(result["value"]);self.assertIn("PTC_OUTPUT_LIMIT",result["error"])


if __name__ == '__main__':
    unittest.main()
