"""Untrusted program runner. The Docker boundary and host allowlist provide authority, not Python syntax checks."""
import ast
import asyncio
import contextlib
import json
import inspect
import sys
import unicodedata

CONTROL = sys.stdout
INPUT = sys.stdin
MAX_FRAME = 256 * 1024


def emit(value):
    data = json.dumps(value, ensure_ascii=True, allow_nan=False, separators=(",", ":"))
    if len(data.encode()) > MAX_FRAME:
        raise ValueError("PTC_PROTOCOL_LIMIT")
    CONTROL.write(data + "\n")
    CONTROL.flush()


class ToolCallError(Exception):
    def __init__(self, name, message):
        super().__init__(message)
        self.tool_name = name
        self.message = message


class OutputCapture:
    def __init__(self, limit):
        self.limit = limit
        self.text = ""
        self.overflow = False

    def write(self, value):
        if len(self.text) + len(value) > self.limit:
            self.overflow = True
            raise ValueError("PTC_OUTPUT_LIMIT")
        self.text += value
        return len(value)

    def flush(self):
        pass


async def run(request):
    lock = asyncio.Lock()
    sequence = 0

    def binding(name):
        contract = request.get("contracts", {}).get(name)
        signature = None
        if contract is not None:
            required = set(contract["required"])
            signature = inspect.Signature([
                inspect.Parameter(key, inspect.Parameter.KEYWORD_ONLY,
                                  default=inspect.Parameter.empty if key in required else Ellipsis)
                for key in contract["parameters"]
            ])

        async def invoke(*positional, **keywords):
            nonlocal sequence
            if positional:
                if len(positional) != 1 or not isinstance(positional[0], dict) or keywords:
                    raise ToolCallError(name, "PTC_INVALID_TOOL_ARGUMENTS: use keyword arguments or one dict, not both")
                arguments = dict(positional[0])
            else:
                arguments = dict(keywords)
            if signature is not None:
                try:
                    signature.bind(**arguments)
                except TypeError:
                    missing = sorted(set(contract["required"]) - arguments.keys())
                    unexpected = sorted(arguments.keys() - set(contract["parameters"]))
                    raise ToolCallError(name, "PTC_INVALID_TOOL_ARGUMENTS: " + name
                                        + "; missing=[" + ", ".join(missing) + "]; unexpected=["
                                        + ", ".join(unexpected) + "]; tool not executed") from None
            async with lock:
                sequence += 1
                emit({"type": "call", "id": sequence, "tool": name, "arguments": arguments})
                raw = await asyncio.to_thread(INPUT.readline, MAX_FRAME + 1)
                if not raw.endswith("\n") or len(raw.encode()) > MAX_FRAME:
                    raise RuntimeError("PTC_PROTOCOL_ERROR: missing reply")
                response = json.loads(raw)
                if response.get("id") != sequence:
                    raise RuntimeError("PTC_PROTOCOL_ERROR: reply mismatch")
                if not response.get("ok"):
                    raise ToolCallError(name, response.get("error", "Tool failed"))
                return response.get("value")
        if signature is not None:
            invoke.__signature__ = signature
        return invoke

    class Tools:
        pass

    tools = Tools()
    for name in request["bindings"]:
        if not name.isidentifier() or name.startswith("_"):
            raise ValueError("Invalid binding")
        setattr(tools, name, binding(name))
    namespace = {"tools": tools, "ToolCallError": ToolCallError, "asyncio": asyncio, "json": json}
    # Wrap parsed statements, not source lines: indenting text also changes the
    # contents of multiline strings used for patches and newly created files.
    program = ast.parse(request["code"], filename="<ptc-program>", mode="exec")
    wrapper = ast.parse("async def __ptc_main__():\n    pass\n")
    function = wrapper.body[0]
    function.body = program.body or [ast.Pass()]
    if program.body:
        function.end_lineno = program.body[-1].end_lineno
        function.end_col_offset = program.body[-1].end_col_offset
    exec(compile(ast.fix_missing_locations(wrapper), "<ptc-program>", "exec"), namespace)
    return await namespace["__ptc_main__"]()


def syntax_excerpt(failure, code):
    lines = code.splitlines() if isinstance(code, str) else []
    source = lines[failure.lineno - 1] if isinstance(failure.lineno, int) and 1 <= failure.lineno <= len(lines) else failure.text
    if not isinstance(source, str):
        return ""
    source = source.rstrip("\r\n")
    column = failure.offset if isinstance(failure.offset, int) and failure.offset > 0 else None
    position = min(len(source), column - 1) if column is not None else 0
    # Expand tabs for the diagnostic only; never rewrite the executable program.
    anchor = len(source[:position].expandtabs(4))
    expanded = source.expandtabs(4)
    start = max(0, anchor - 80)
    prefix = "..." if start else ""
    fragment = prefix + expanded[start:start + 160] + ("..." if len(expanded) > start + 160 else "")
    if column is None:
        return "\n" + fragment
    before = prefix + expanded[start:anchor]
    width = sum(0 if unicodedata.combining(char) else 2 if unicodedata.east_asian_width(char) in ("W", "F") else 1 for char in before)
    return "\n" + fragment + "\n" + " " * width + "^"


def program_error(failure, code=None):
    # Report only the generated program's location, never the host/runtime stack.
    line = failure.lineno if isinstance(failure, SyntaxError) and failure.filename == "<ptc-program>" else None
    trace = failure.__traceback__
    while trace is not None:
        if trace.tb_frame.f_code.co_filename == "<ptc-program>":
            line = trace.tb_lineno
        trace = trace.tb_next
    message = failure.msg if isinstance(failure, SyntaxError) else str(failure)
    location = f" at program line {line}" if line is not None else ""
    if isinstance(failure, SyntaxError) and failure.filename == "<ptc-program>":
        if isinstance(failure.offset, int) and failure.offset > 0:
            location += f", column {failure.offset}"
        # Only Python's actual diagnostic position and generated source; no guessed cause.
        return (type(failure).__name__ + location + ": " + message[:240] + syntax_excerpt(failure, code))[:800]
    return type(failure).__name__ + location + ": " + message[:500]


def main():
    output = OutputCapture(24000)
    value = None
    error = None
    request = None
    try:
        raw = INPUT.readline(MAX_FRAME + 1)
        if not raw.endswith("\n") or len(raw.encode()) > MAX_FRAME:
            raise ValueError("Invalid initial frame")
        request = json.loads(raw)
        limit = request.get("maxOutputChars", 24000)
        if not isinstance(limit, int) or not 1 <= limit <= 24000:
            raise ValueError("Invalid output limit")
        output = OutputCapture(limit)
        if not isinstance(request.get("code"), str) or not 0 < len(request["code"]) <= 32000:
            raise ValueError("Invalid program length")
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            value = asyncio.run(run(request))
        encoded = json.dumps(value, ensure_ascii=False, allow_nan=False)
        if output.overflow or len(encoded) + len(output.text) > output.limit:
            raise ValueError("PTC_OUTPUT_LIMIT")
    except BaseException as failure:
        value = None
        error = program_error(failure, request.get("code") if isinstance(request, dict) else None)
        if output.overflow:
            error = "PTC_OUTPUT_LIMIT"
    emit({"type": "done", "value": value, "logs": [output.text] if output.text else [], "error": error})


if __name__ == "__main__":
    main()
