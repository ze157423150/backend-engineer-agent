"""Offline recall evaluation. No HTTP clients, model configuration or API calls."""
import argparse
import json
import time
from collections import defaultdict
from pathlib import Path
import bm25_selector
import rule_selector
import all_tools_selector
from common import schema_bytes


def score(selected, label):
    chosen = set(selected)
    observed = set(label["observedNextTools"])
    groups = label["acceptableGroups"]
    hit = sum(bool(chosen.intersection(group)) for group in groups)
    return {"observedHits": len(chosen & observed), "observedCount": len(observed),
            "groupHits": hit, "groupCount": len(groups),
            "allObservedCovered": bool(observed) and observed <= chosen,
            "allCapabilitiesCovered": bool(groups) and hit == len(groups),
            "emptyOnFinishedState": not chosen and label["finished"]}


def evaluate(samples, output, limits):
    if output.exists():
        raise ValueError("Refusing to overwrite results")
    output.mkdir(parents=True)
    catalog = json.loads((samples / "catalog.json").read_text())
    inputs = [json.loads(l) for l in (samples / "inputs.jsonl").read_text().splitlines()]
    labels = {l["id"]: l for l in map(json.loads, (samples / "labels.jsonl").read_text().splitlines())}
    definitions = {tool["function"]["name"]: tool for tool in catalog}
    full_bytes = schema_bytes(catalog)
    details, summaries = [], []
    for strategy in ("all", "rules", "bm25"):
        for k in ([len(catalog)] if strategy == "all" else limits):
            rows = []
            for item in inputs:
                state = item["state"]  # The selector receives no id, labels or future response.
                started = time.perf_counter_ns()
                selected = {"all": all_tools_selector, "rules": rule_selector, "bm25": bm25_selector}[strategy].select(state, catalog, k)
                elapsed = (time.perf_counter_ns() - started) / 1_000_000
                if len(selected) != len(set(selected)) or not set(selected) <= definitions.keys():
                    raise ValueError("Invalid selector output")
                label = labels[item["id"]]
                row = {"id": item["id"], "strategy": strategy, "k": k, "selected": selected,
                       "milliseconds": elapsed, "selectedSchemaBytes": schema_bytes([definitions[n] for n in selected]),
                       "finished": label["finished"], **score(selected, label)}
                rows.append(row)
                details.append(row)
            positive = [r for r in rows if not r["finished"]]
            finished = [r for r in rows if r["finished"]]
            total_observed = sum(r["observedCount"] for r in positive)
            total_groups = sum(r["groupCount"] for r in positive)
            per_tool = defaultdict(lambda: {"states": 0, "covered": 0})
            for row in positive:
                for name in labels[row["id"]]["observedNextTools"]:
                    per_tool[name]["states"] += 1
                    per_tool[name]["covered"] += int(name in row["selected"])
            summaries.append({"strategy": strategy, "k": k,
                "observedToolRecall": sum(r["observedHits"] for r in positive) / total_observed,
                "capabilityRecall": sum(r["groupHits"] for r in positive) / total_groups,
                "allObservedCoveredRate": sum(r["allObservedCovered"] for r in positive) / len(positive),
                "allCapabilitiesCoveredRate": sum(r["allCapabilitiesCovered"] for r in positive) / len(positive),
                "emptySelectionOnFinishedStates": sum(r["emptyOnFinishedState"] for r in finished),
                "finishedStates": len(finished), "positiveStates": len(positive),
                "averageSelectedTools": sum(len(r["selected"]) for r in rows) / len(rows),
                "averageSelectionMilliseconds": sum(r["milliseconds"] for r in rows) / len(rows),
                "averageSchemaByteReduction": 1 - sum(r["selectedSchemaBytes"] for r in rows) / (full_bytes * len(rows)),
                "perToolObservedCoverage": dict(per_tool), "missedStates": [r["id"] for r in positive if not r["allCapabilitiesCovered"]]})
    report = {"dataset": json.loads((samples / "manifest.json").read_text()), "fullSchemaBytes": full_bytes,
              "apiCalls": 0, "paidSelectors": {"generalModel": "NOT_IMPLEMENTED_PER_USER", "jev": "NOT_IMPLEMENTED_PER_USER"},
              "metricsMeaning": "Empirical next-action/capability coverage on 20 labeled positive states, not calibrated probabilities or end-to-end task success. Empty selection on finished states is diagnostic, not a correctness requirement.",
              "summaries": summaries}
    (output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
    (output / "decisions.jsonl").write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in details))
    lines = ["工具筛选离线历史回放", "", "策略 | k | 历史下一步工具召回率 | 允许等价工具后的整组覆盖率 | 工具定义字节减少率"]
    for row in summaries:
        lines.append(f"{row['strategy']} | {row['k']} | {row['observedToolRecall']:.2%} | {row['allCapabilitiesCoveredRate']:.2%} | {row['averageSchemaByteReduction']:.2%}")
    lines.extend(["", "API调用：0。普通模型和Jev按用户要求未编写、未测试。", "仅统计工具schema字节，不是完整请求或token节省率。", report["metricsMeaning"], "", "样本局限："] + report["dataset"]["limitations"])
    (output / "结果说明.txt").write_text("\n".join(lines) + "\n")
    return summaries


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--samples", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--k", default="3,5,8")
    args = parser.parse_args()
    limits = [int(k) for k in args.k.split(",")]
    if any(k < 1 for k in limits):
        parser.error("k must be positive")
    for result in evaluate(Path(args.samples), Path(args.output), limits):
        print(result["strategy"], result["k"], "tool recall", f"{result['observedToolRecall']:.2%}", "all capabilities", f"{result['allCapabilitiesCoveredRate']:.2%}")
