"""Extract only pre-action inputs; target responses are stored as separate labels.

Labels reproduce observed next actions in accepted historical runs. They are not
proof that each historical action was necessary or that other actions were wrong.
"""
import argparse
import hashlib
import json
from pathlib import Path
from common import clip


# Frozen, stratified smoke-test sample: navigation, read, write, calculation,
# failed-test follow-up, historical retrieval, and four finished states.
POINTS = [(1, 1), (1, 4), (1, 5), (1, 12), (1, 14), (1, 16),
          (2, 18), (2, 21), (2, 23), (3, 27), (4, 40), (4, 41),
          (5, 48), (5, 53), (6, 72), (6, 73), (6, 74), (6, 78),
          (45, 284), (45, 285), (1, 17), (3, 31), (6, 79), (58, 356)]


def equivalent_catalog(left, right):
    def normalize(value):
        if isinstance(value, dict):
            return {k: sorted(v) if k == "required" else normalize(v) for k, v in value.items()}
        if isinstance(value, list):
            return [normalize(v) for v in value]
        return value
    # Separate JVMs may reorder Map fields and JSON Schema required arrays;
    # neither changes the callable schema. Other changes are not normalized away.
    return ({t["function"]["name"]: normalize(t) for t in left}
            == {t["function"]["name"]: normalize(t) for t in right})


def plain_user(message):
    if message.get("role") != "user" or not isinstance(message.get("content"), str):
        return False
    try:
        parsed = json.loads(message["content"])
        return not isinstance(parsed, dict) or "kind" not in parsed
    except ValueError:
        return True


def project(request):
    messages = request["messages"]
    index = max(i for i, message in enumerate(messages) if plain_user(message))
    task = messages[index]["content"]
    names = {}
    for message in messages[:]:
        for call in message.get("tool_calls", []):
            names[call["id"]] = call["function"]["name"]
    observations = []
    for message in messages[index + 1:]:
        if message["role"] != "tool":
            continue
        try:
            value = json.loads(message["content"])
        except ValueError:
            continue
        observations.append({"name": names.get(message.get("tool_call_id"), "unknown"),
                             "successful": value.get("successful", False),
                             "content": clip(value.get("content", ""), 1000)})
    return {"task": clip(task, 1800), "recentTools": observations[-3:],
            "projection": "Current user message (head/tail 1800 chars), last three tool results since that message (head/tail 1000 chars each); no target response"}


def build(root, output):
    if output.exists():
        raise ValueError("Refusing to overwrite a sample set")
    output.mkdir(parents=True)
    historical = root / "reports/automatic-checkpoint-retry-20261018-001/run"
    catalog_path = historical / "turn-01/attempt-01/http/call-0001/request.json"
    catalog = json.loads(catalog_path.read_text())["tools"]
    names = {tool["function"]["name"] for tool in catalog}
    (output / "catalog.json").write_text(json.dumps(catalog, ensure_ascii=False, indent=2))
    inputs, labels = [], []
    for turn, call in POINTS:
        directory = historical / f"turn-{turn:02d}/attempt-01/http/call-{call:04d}"
        request_bytes = (directory / "request.json").read_bytes()
        response_bytes = (directory / "response.json").read_bytes()
        request, response = json.loads(request_bytes), json.loads(response_bytes)
        assert equivalent_catalog(request["tools"], catalog), "Historical tool schemas changed; split the dataset rather than silently mixing catalogs"
        message = response["choices"][0]["message"]
        observed = sorted({c["function"]["name"] for c in message.get("tool_calls", [])})
        assert set(observed) <= names
        sample_id = f"turn-{turn:02d}-call-{call:04d}"
        inputs.append({"id": sample_id, "state": project(request)})
        groups = []
        for name in observed:
            group = ["apply_patch", "apply_patch_batch"] if name in {"apply_patch", "apply_patch_batch"} else [name]
            if group not in groups:
                groups.append(group)
        labels.append({"id": sample_id, "turn": turn, "observedNextTools": observed,
                       "acceptableGroups": groups, "finished": not observed,
                       "labelBasis": "Observed next action in an accepted turn, patch/batch treated as capability alternatives; not a minimal necessary tool oracle",
                       "requestSource": str((directory / "request.json").relative_to(root)),
                       "responseSource": str((directory / "response.json").relative_to(root)),
                       "requestSha256": hashlib.sha256(request_bytes).hexdigest(),
                       "responseSha256": hashlib.sha256(response_bytes).hexdigest()})
    for name, rows in [("inputs.jsonl", inputs), ("labels.jsonl", labels)]:
        (output / name).write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows))
    (output / "manifest.json").write_text(json.dumps({"samples": len(inputs), "positiveStates": sum(not l["finished"] for l in labels),
        "finishedStates": sum(l["finished"] for l in labels), "toolCount": len(catalog), "toolSchemas": "Frozen actual historical schemas, not synthesized distractors",
        "limitations": ["One synthetic log-analysis maintenance task family", "Stratified convenience sample, not random or held-out population", "Observed actions can be redundant; coverage is a proxy for necessary-tool recall", "Input uses a bounded projection, not the full original request", "No paid model calls"]}, ensure_ascii=False, indent=2))
    return len(inputs)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[3]
    print("Samples:", build(root, Path(args.output).resolve()))
