import json
import ast
import importlib.util
import unicodedata
import subprocess
import sys
import unittest
from pathlib import Path

RUNTIME = Path(__file__).with_name("runtime.py")


class RuntimeTests(unittest.TestCase):
    def execute(self, code, replies=(), bindings=("integer_calculate",), output_limit=24000, contracts=None):
        frames = [{"code": code, "bindings": list(bindings), "maxOutputChars": output_limit, "contracts": contracts or {}}, *replies]
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

    def test_keyword_call_and_old_dict_share_required_signature(self):
        contract = {"read_file": {"parameters": ["path", "start_line", "end_line"], "required": ["path", "start_line", "end_line"]}}
        for call in ("tools.read_file(path='A.java',start_line=1,end_line=2)",
                     "tools.read_file({'path':'A.java','start_line':1,'end_line':2})"):
            frames = self.execute("return await " + call, [{"id":1,"ok":True,"value":{"content":"source"}}],
                                  bindings=("read_file",), contracts=contract)
            self.assertEqual({"path":"A.java","start_line":1,"end_line":2}, frames[0]["arguments"])
            self.assertIsNone(frames[-1]["error"])

    def test_missing_range_is_catchable_before_dispatch_then_can_be_corrected(self):
        contract = {"read_file": {"parameters": ["path", "start_line", "end_line"], "required": ["path", "start_line", "end_line"]}}
        code = "try:\n    await tools.read_file(path='A.java')\nexcept ToolCallError as e:\n    assert 'missing=[end_line, start_line]' in e.message\nreturn await tools.read_file(path='A.java',start_line=1,end_line=2)"
        frames = self.execute(code, [{"id":1,"ok":True,"value":{"content":"source"}}], bindings=("read_file",), contracts=contract)
        self.assertEqual(2,len(frames));self.assertEqual(1,frames[0]["id"]);self.assertIsNone(frames[-1]["error"])

    def test_mixed_dict_and_keyword_arguments_do_not_dispatch(self):
        frames = self.execute("return await tools.integer_calculate({'expression':'1+1'},result_name='sum')")
        self.assertEqual(1,len(frames));self.assertIn('not both',frames[0]['error'])

    def test_optional_signature_parameter_is_not_injected_as_a_default(self):
        contract = {"workspace_diff": {"parameters": ["path", "scope"], "required": ["path"]}}
        code = "import inspect\nassert list(inspect.signature(tools.workspace_diff).parameters) == ['path','scope']\nreturn await tools.workspace_diff(path='A.java')"
        frames = self.execute(code,[{"id":1,"ok":True,"value":{}}],bindings=("workspace_diff",),contracts=contract)
        self.assertEqual({"path":"A.java"},frames[0]["arguments"]);self.assertIsNone(frames[-1]["error"])

    def test_empty_signature_accepts_zero_arguments(self):
        frames = self.execute("return await tools.run_tests()",[{"id":1,"ok":True,"value":{}}],bindings=("run_tests",),
                              contracts={"run_tests":{"parameters":[],"required":[]}})
        self.assertEqual({},frames[0]['arguments']);self.assertIsNone(frames[-1]['error'])

    def test_tool_error_is_catchable_and_program_output_is_separate(self):
        frames=self.execute("try:\n    await tools.integer_calculate({})\n"
                            "except ToolCallError as e:\n    print(e.tool_name)\nreturn {'handled': True}",
                            [{"id":1,"ok":False,"error":"bad arguments"}])
        self.assertTrue(frames[-1]["value"]["handled"])
        self.assertEqual(["integer_calculate\n"],frames[-1]["logs"])

    def test_multiline_tool_arguments_preserve_spaces_and_trailing_newline(self):
        code = "old = \"\"\"package example;\nclass A {\n    int n = 1;\n}\n\"\"\"\n" \
               "new = \"\"\"package example;\nclass A {\n    int n = 2;\n}\n\"\"\"\n" \
               "return await tools.apply_patch({'old_text':old,'new_text':new})"
        frames=self.execute(code,[{"id":1,"ok":True,"value":{"successful":True}}],bindings=("apply_patch",))
        self.assertEqual("package example;\nclass A {\n    int n = 1;\n}\n",frames[0]["arguments"]["old_text"])
        self.assertEqual("package example;\nclass A {\n    int n = 2;\n}\n",frames[0]["arguments"]["new_text"])
        self.assertIsNone(frames[-1]["error"])

    def test_nested_raw_and_formatted_multiline_strings_keep_their_values(self):
        code = "tag='A'\nif True:\n    raw = r\"\"\"first\n\\n\n    last\"\"\"\n    text = f\"\"\"{tag}\n  body\n\"\"\"\nreturn [raw,text]"
        result=self.execute(code)[-1]
        self.assertIsNone(result["error"])
        self.assertEqual(["first\n\\n\n    last","A\n  body\n"],result["value"])

    def test_comment_only_body_is_valid(self):
        result=self.execute("# nothing to execute")[-1]
        self.assertIsNone(result["error"]);self.assertIsNone(result["value"])

    def test_syntax_failure_does_not_dispatch(self):
        frames=self.execute("if:")
        self.assertEqual(1,len(frames));self.assertIn("SyntaxError",frames[0]["error"])

    def test_runtime_error_reports_original_program_line_only(self):
        result=self.execute("first = 1\nassert 'old' in None or True\nreturn first")[-1]
        self.assertIn("TypeError at program line 2:",result["error"])
        self.assertNotIn("runtime.py",result["error"])

    def test_nested_function_error_reports_the_failing_program_line(self):
        result=self.execute("def broken():\n    return 1/0\nreturn broken()")[-1]
        self.assertIn("ZeroDivisionError at program line 2:",result["error"])

    def test_syntax_error_reports_original_line(self):
        result=self.execute("first = 1\nif:\n    pass")[-1]
        self.assertIn("SyntaxError at program line 2, column 3:",result["error"])
        self.assertEqual(["if:", "  ^"], result["error"].splitlines()[-2:])
        self.assertNotIn("runtime.py",result["error"])

    def test_long_syntax_line_is_excerpted_around_original_column(self):
        code = "value = " + repr("padding" * 100) + " +"
        try:
            ast.parse(code, filename="<ptc-program>")
        except SyntaxError as original:
            offset = original.offset
        result = self.execute(code)[-1]["error"]
        self.assertIn(f"column {offset}:", result)
        fragment, pointer = result.splitlines()[-2:]
        self.assertTrue(fragment.startswith("..."));self.assertLessEqual(len(fragment),166)
        self.assertLessEqual(len(result),800)
        start = max(0, min(len(code), offset - 1) - 80)
        self.assertEqual(3 + min(len(code),offset - 1) - start, pointer.index("^"))

    def test_tabs_and_unicode_keep_python_column_and_display_pointer(self):
        code = "if True:\n\tlabel = '中文'; broken = {'x\": 1}\n"
        try:
            ast.parse(code, filename="<ptc-program>")
        except SyntaxError as original:
            offset = original.offset
        result = self.execute(code)[-1]["error"]
        self.assertIn(f"program line 2, column {offset}:", result)
        fragment, pointer = result.splitlines()[-2:]
        self.assertEqual(code.splitlines()[1].expandtabs(4), fragment)
        before = code.splitlines()[1][:offset-1].expandtabs(4)
        width = sum(0 if unicodedata.combining(c) else 2 if unicodedata.east_asian_width(c) in ("W","F") else 1 for c in before)
        self.assertEqual(width, pointer.index("^"))

    def test_missing_syntax_column_does_not_invent_position_or_break_feedback(self):
        spec = importlib.util.spec_from_file_location("ptc_runtime_diagnostic", RUNTIME)
        module = importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        error = SyntaxError("invalid syntax", ("<ptc-program>",2,None,"bad line\n"))
        rendered = module.program_error(error, "first\nbad line")
        self.assertIn("program line 2:",rendered);self.assertNotIn("column",rendered)
        self.assertTrue(rendered.endswith("\nbad line"));self.assertNotIn("runtime.py",rendered)

    def test_failed_binding_reports_the_call_line(self):
        result=self.execute("first=1\nawait tools.integer_calculate({})",[{"id":1,"ok":False,"error":"bad arguments"}])[-1]
        self.assertIn("ToolCallError at program line 2: bad arguments",result["error"])

    def test_oversized_return_is_not_silently_truncated(self):
        result=self.execute("return 'x'*30000")[-1]
        self.assertIsNone(result["value"]);self.assertIn("PTC_OUTPUT_LIMIT",result["error"])

    def test_host_output_allowance_can_narrow_but_not_expand_hard_limit(self):
        self.assertEqual(8000,len(self.execute("return 'x'*8000")[-1]["value"]))
        self.assertIn("PTC_OUTPUT_LIMIT",self.execute("return 'x'*8000",output_limit=6000)[-1]["error"])
        self.assertIn("Invalid output limit",self.execute("return 1",output_limit=30000)[-1]["error"])

    def test_catching_print_limit_does_not_hide_overflow(self):
        result=self.execute("try:\n    print('x'*30000)\nexcept ValueError:\n    pass\nreturn 1")[-1]
        self.assertIsNone(result["value"]);self.assertIn("PTC_OUTPUT_LIMIT",result["error"])


if __name__ == '__main__':
    unittest.main()
