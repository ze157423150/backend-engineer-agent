"""Shared offline selection inputs; gold labels are deliberately not accepted."""
import json
import re


def clip(text, size):
    text = str(text)
    if len(text) <= size:
        return text
    half = max(0, (size - 20) // 2)
    return text[:half] + "\n[excerpt omitted]\n" + text[-half:]


def terms(text):
    # No dictionary download: split English identifiers and Chinese character
    # bigrams. This is lexical matching, not a semantic embedding model.
    text = re.sub(r"([a-z])([A-Z])", r"\1 \2", text).lower().replace("_", " ")
    words = re.findall(r"[a-z][a-z0-9]*|[0-9]+", text)
    for run in re.findall(r"[\u4e00-\u9fff]+", text):
        words.extend(run[i:i + 2] for i in range(len(run) - 1))
        if len(run) == 1:
            words.append(run)
    return words


def query_text(state):
    # Same task/history projection for both strategies. Describe actions using
    # only past observed results; never include the target response or labels.
    parts = [state["task"], state["task"]]
    for item in state.get("recentTools", []):
        parts.extend([item["name"], item["content"]])
    return "\n".join(parts)


def schema_bytes(tools):
    return len(json.dumps(tools, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))
