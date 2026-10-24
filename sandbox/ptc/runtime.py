"""Untrusted program runner. The Docker boundary and host allowlist provide authority, not Python syntax checks."""
import asyncio
import contextlib
import json
import sys
import textwrap

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
        async def invoke(arguments):
            nonlocal sequence
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
        return invoke

    class Tools:
        pass

    tools = Tools()
    for name in request["bindings"]:
        if not name.isidentifier() or name.startswith("_"):
            raise ValueError("Invalid binding")
        setattr(tools, name, binding(name))
    namespace = {"tools": tools, "ToolCallError": ToolCallError, "asyncio": asyncio, "json": json}
    source = "async def __ptc_main__():\n" + textwrap.indent(request["code"], "    ")
    exec(compile(source, "<ptc-program>", "exec"), namespace)
    return await namespace["__ptc_main__"]()


def main():
    output = OutputCapture(6000)
    value = None
    error = None
    try:
        raw = INPUT.readline(MAX_FRAME + 1)
        if not raw.endswith("\n") or len(raw.encode()) > MAX_FRAME:
            raise ValueError("Invalid initial frame")
        request = json.loads(raw)
        if not isinstance(request.get("code"), str) or not 0 < len(request["code"]) <= 8000:
            raise ValueError("Invalid program length")
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            value = asyncio.run(run(request))
        encoded = json.dumps(value, ensure_ascii=False, allow_nan=False)
        if output.overflow or len(encoded) + len(output.text) > 6000:
            raise ValueError("PTC_OUTPUT_LIMIT")
    except BaseException as failure:
        value = None
        error = type(failure).__name__ + ": " + str(failure)[:500]
        if output.overflow:
            error = "PTC_OUTPUT_LIMIT"
    emit({"type": "done", "value": value, "logs": [output.text] if output.text else [], "error": error})


if __name__ == "__main__":
    main()
