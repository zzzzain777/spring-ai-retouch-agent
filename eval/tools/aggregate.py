# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""把多轮评测结果汇总成原版/Java 版的配对对比报告。

输入是 run_agent_eval.py 产出的若干 results.json（同一冻结数据集、同一模型与参数，
每个版本跑多轮），可选叠加 planner_proxy.py 的转发轨迹（原版的 token 用量只有它知道）。
只做汇总与对照，不改判定：单轮的 pass/fail 以 results.json 里的为准。

用法：
  uv run eval/tools/aggregate.py \
    --run upstream=eval/reports/upstream-r1-xxx/results.json \
    --run upstream=eval/reports/upstream-r2-xxx/results.json \
    --run java=eval/reports/java-r1-xxx/results.json ... \
    --proxy-trace upstream=eval/reports/planner-trace.jsonl \
    --price-in 2.0 --price-out 3.0 --currency 元/百万token \
    --out eval/reports/paired-vs-upstream

价格参数是可选的每百万 token 单价（与 --currency 搭配，只用于算一笔总账）；
不传就只报 token 数。价格以运行当天模型官网牌价为准，报告里注明来源与日期。
"""

from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path

HARD_METRICS = ("no_side_effect", "confirm_flow", "authz_isolation", "regression")


def load_runs(specs: list[str]) -> dict[str, list[dict]]:
    versions: dict[str, list[dict]] = defaultdict(list)
    for spec in specs:
        version, _, path = spec.partition("=")
        if not path:
            raise SystemExit(f"--run 需要 版本=results.json 形式：{spec}")
        versions[version].append(json.loads(Path(path).read_text(encoding="utf-8")))
    return versions


def check_comparable(versions: dict[str, list[dict]]) -> dict:
    manifests = {run["meta"]["manifest_sha256"] for runs in versions.values() for run in runs}
    if len(manifests) != 1:
        raise SystemExit(f"各轮用的数据集不一致，不能配对：{manifests}")
    return {"manifest_sha256": manifests.pop()}


def per_round(runs: list[dict]) -> list[dict]:
    rows = []
    for run in runs:
        summary = run["summary"]
        rows.append({
            "label": run["meta"]["label"],
            "started_at": run["meta"]["started_at"],
            "capabilities": run["meta"]["capabilities"],
            "executed": summary["executed"],
            "passed": summary["passed"],
            "failed": summary["failed"],
            "errors": summary["errors"],
            "skipped": summary["skipped"],
            "planner": summary.get("planner"),
        })
    return rows


def metric_totals(runs: list[dict]) -> dict[str, dict[str, int]]:
    totals: dict[str, dict[str, int]] = defaultdict(lambda: {"pass": 0, "fail": 0, "skipped": 0})
    for run in runs:
        for metric, bucket in run["summary"]["by_metric"].items():
            for key in ("pass", "fail", "skipped"):
                totals[metric][key] += bucket.get(key, 0)
    return dict(totals)


def case_patterns(runs: list[dict]) -> dict[str, list[str]]:
    patterns: dict[str, list[str]] = defaultdict(list)
    for run in runs:
        for case in run["cases"]:
            patterns[case["id"]].append(case["status"])
    return dict(patterns)


def verdict(statuses: list[str]) -> str:
    kinds = set(statuses)
    if kinds == {"pass"}:
        return "pass"
    if kinds == {"skipped"}:
        return "skipped"
    if "pass" in kinds and kinds & {"fail", "error"}:
        return "flaky"
    return "fail"


def planner_tokens(runs: list[dict]) -> dict:
    total = {"calls": 0, "prompt_tokens": 0, "completion_tokens": 0}
    seen = False
    for run in runs:
        planner = run["summary"].get("planner") or {}
        if planner.get("calls"):
            seen = True
            for key in total:
                total[key] += planner.get(key, 0)
    return total if seen else {}


def proxy_tokens(path: Path) -> dict:
    total = {"calls": 0, "prompt_tokens": 0, "completion_tokens": 0}
    for line in path.read_text(encoding="utf-8").splitlines():
        try:
            entry = json.loads(line)
        except ValueError:
            continue
        usage = entry.get("usage") or (entry.get("planner") or {}).get("usage")
        if entry.get("target") == "deepseek" and usage:
            total["calls"] += 1
            total["prompt_tokens"] += usage.get("prompt_tokens") or 0
            total["completion_tokens"] += usage.get("completion_tokens") or 0
    return total


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", action="append", required=True, help="版本=results.json，可多次")
    parser.add_argument("--proxy-trace", action="append", default=[], help="版本=planner_proxy 轨迹 jsonl")
    parser.add_argument("--price-in", type=float, default=None, help="每百万 prompt token 单价")
    parser.add_argument("--price-out", type=float, default=None, help="每百万 completion token 单价")
    parser.add_argument("--currency", default="", help="价格单位说明，例如 '元（deepseek-chat 2026-11-05 牌价）'")
    parser.add_argument("--note", default="")
    parser.add_argument("--out", type=Path, required=True, help="输出目录")
    args = parser.parse_args()

    versions = load_runs(args.run)
    shared = check_comparable(versions)

    # token 来源：Java 版自己上报规划轨迹；原版没有该接口，用转发器日志（同版本多个日志文件累加）。
    # 两者都有时以被测系统上报为准，转发器日志只作旁证。
    tokens: dict[str, dict] = {version: planner_tokens(runs) for version, runs in versions.items()}
    proxied: dict[str, dict] = {}
    for spec in args.proxy_trace:
        version, _, path = spec.partition("=")
        extra = proxy_tokens(Path(path))
        bucket = proxied.setdefault(version, {"calls": 0, "prompt_tokens": 0, "completion_tokens": 0})
        for key in extra:
            bucket[key] += extra[key]
    for version, bucket in proxied.items():
        if not tokens.get(version):
            tokens[version] = bucket

    patterns = {version: case_patterns(runs) for version, runs in versions.items()}
    case_ids: list[str] = []
    for runs in versions.values():
        for case in runs[0]["cases"]:
            if case["id"] not in case_ids:
                case_ids.append(case["id"])

    diffs = []
    unstable = []
    for case_id in case_ids:
        verdicts = {version: verdict(patterns[version].get(case_id, [])) for version in versions}
        if len(set(verdicts.values())) > 1:
            diffs.append({"id": case_id, **verdicts})
        for version, value in verdicts.items():
            if value in {"flaky", "fail"}:
                unstable.append({"id": case_id, "version": version, "rounds": patterns[version].get(case_id, [])})

    def cost(bucket: dict) -> float | None:
        if args.price_in is None or args.price_out is None or not bucket:
            return None
        return round((bucket["prompt_tokens"] * args.price_in + bucket["completion_tokens"] * args.price_out) / 1e6, 4)

    data = {
        "dataset_manifest_sha256": shared["manifest_sha256"],
        "note": args.note,
        "rounds": {version: per_round(runs) for version, runs in versions.items()},
        "metrics": {version: metric_totals(runs) for version, runs in versions.items()},
        "hard_metrics": HARD_METRICS,
        "diff_cases": diffs,
        "unstable_cases": unstable,
        "tokens": tokens,
        "cost": {version: cost(bucket) for version, bucket in tokens.items()},
        "price": {"in_per_million": args.price_in, "out_per_million": args.price_out, "unit": args.currency},
    }

    lines = ["# 配对评测汇总", "", f"- 数据集 MANIFEST sha256：`{shared['manifest_sha256']}`"]
    if args.note:
        lines.append(f"- 备注：{args.note}")
    for version, rows in data["rounds"].items():
        lines += ["", f"## {version}：各轮结果", "", "| 轮 | 执行 | 通过 | 失败 | 异常 | 跳过 |", "| --- | --- | --- | --- | --- | --- |"]
        for i, row in enumerate(rows, 1):
            lines.append(f"| {i}（{row['label']}） | {row['executed']} | {row['passed']} | {row['failed']} |"
                         f" {row['errors']} | {row['skipped']} |")
    lines += ["", "## 按指标（断言计，跨轮合计）", "",
              "| 指标 | " + " | ".join(f"{v} 通过/执行" for v in versions) + " |",
              "| --- | " + " | ".join("---" for _ in versions) + " |"]
    all_metrics = sorted({m for totals in data["metrics"].values() for m in totals})
    for metric in all_metrics:
        cells = []
        for version in versions:
            bucket = data["metrics"][version].get(metric, {"pass": 0, "fail": 0})
            executed = bucket["pass"] + bucket["fail"]
            cells.append(f"{bucket['pass']}/{executed}" if executed else "—")
        mark = "（硬性 100%）" if metric in HARD_METRICS else ""
        lines.append(f"| {metric}{mark} | " + " | ".join(cells) + " |")
    if diffs:
        lines += ["", "## 两版结论不同的样例", ""]
        for diff in diffs:
            desc = "，".join(f"{v}={diff[v]}" for v in versions)
            lines.append(f"- {diff['id']}：{desc}")
    else:
        lines += ["", "两版所有样例结论一致。"]
    if unstable:
        lines += ["", "## 多轮间不稳定或失败的样例", ""]
        for item in unstable:
            lines.append(f"- {item['id']}（{item['version']}）：{', '.join(item['rounds'])}")
    lines += ["", "## 规划模型用量", ""]
    for version in versions:
        bucket = tokens.get(version) or {}
        if bucket:
            line = (f"- {version}：{bucket['calls']} 次调用，prompt {bucket['prompt_tokens']}，"
                    f"completion {bucket['completion_tokens']} tokens")
            if data["cost"].get(version) is not None:
                line += f"，按单价折算 {data['cost'][version]} {args.currency}"
            lines.append(line)
        else:
            lines.append(f"- {version}：无 token 记录（该版本未上报轨迹，也没给转发器日志）")

    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "paired-summary.json").write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    (args.out / "paired-summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps({k: data[k] for k in ("rounds", "diff_cases", "tokens", "cost")}, ensure_ascii=False))
    print(f"written: {args.out}/paired-summary.md")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
