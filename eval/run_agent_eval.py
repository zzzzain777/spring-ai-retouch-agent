# /// script
# requires-python = ">=3.11"
# dependencies = ["httpx>=0.27"]
# ///
"""Agent 行为评测运行器（黑盒，走 HTTP 接口）。

同一份冻结数据集既用于测上游基线，也用于测本项目的 Java 实现，前提是两边对外接口契约一致。

用法：
  uv run eval/run_agent_eval.py --base-url http://127.0.0.1:7302 \
      --dataset eval/datasets/agent-behavior-v1 --label upstream-49f6ad8 \
      --capabilities none            # 可选：llm、image_model、planner_unavailable，逗号分隔

每个样例声明 requires；运行环境不具备的能力会让样例记为 skipped，而不是用 Mock 冒充。
输出：<out>/<label>-<时间>/results.json、traces.jsonl、report.md
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import time
import uuid
import zipfile
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import httpx

TURN_TIMEOUT = 180.0
RUN_TIMEOUT = 180.0
POLL_INTERVAL = 0.5


# ---------------------------------------------------------------- 数据集与冻结校验


def sha256_of(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_manifest(root: Path) -> str:
    """MANIFEST.sha256 列出数据集全部文件。任何改动都会让校验失败，必须新建版本而不是改旧样例。"""
    manifest = root / "MANIFEST.sha256"
    lines = [line.split() for line in manifest.read_text(encoding="utf-8").splitlines() if line.strip()]
    for digest, name in lines:
        actual = sha256_of(root / name)
        if actual != digest:
            raise SystemExit(f"数据集已被改动：{name} 的 sha256 与 MANIFEST 不一致，请新建数据集版本")
    return sha256_of(manifest)


# ---------------------------------------------------------------- 取值与断言


def resolve(ctx: dict, path: str) -> Any:
    value: Any = ctx
    for part in path.split("."):
        if isinstance(value, dict):
            value = value.get(part)
        elif isinstance(value, list) and part.lstrip("-").isdigit():
            index = int(part)
            value = value[index] if -len(value) <= index < len(value) else None
        else:
            return None
    return value


def is_subsequence(needle: list, haystack: list) -> bool:
    it = iter(haystack)
    return all(any(item == other for other in it) for item in needle)


def check(ctx: dict, spec: dict) -> tuple[bool, str]:
    """返回 (是否通过, 记录行)。"""
    path = spec["path"]
    actual = resolve(ctx, path)
    op, expected = spec["op"], spec.get("value")
    ok: bool
    if op == "eq":
        ok = actual == expected
    elif op == "ne":
        ok = actual != expected
    elif op == "in":
        ok = actual in expected
    elif op in {"gt", "lt", "ge", "le"}:
        ok = isinstance(actual, (int, float)) and {
            "gt": actual > expected,
            "lt": actual < expected,
            "ge": actual >= expected,
            "le": actual <= expected,
        }[op]
    elif op == "contains":
        ok = isinstance(actual, (list, str)) and expected in actual
    elif op == "contains_any":
        ok = isinstance(actual, list) and any(item in actual for item in expected)
    elif op == "not_contains_any":
        ok = isinstance(actual, list) and not any(item in actual for item in expected)
    elif op == "subset_of":
        ok = isinstance(actual, list) and all(item in expected for item in actual)
    elif op == "subsequence":
        ok = isinstance(actual, list) and is_subsequence(expected, actual)
    elif op == "len_between":
        ok = isinstance(actual, (list, str)) and expected[0] <= len(actual) <= expected[1]
    elif op == "truthy":
        ok = bool(actual)
    elif op == "falsy":
        ok = not actual
    else:
        raise ValueError(f"未知断言 {op}")
    label = spec.get("why") or f"{path} {op} {json.dumps(expected, ensure_ascii=False)}"
    return ok, f"{'PASS' if ok else 'FAIL'} {label} (actual={json.dumps(actual, ensure_ascii=False, default=str)[:200]})"


# ---------------------------------------------------------------- 被测系统客户端


class Client:
    def __init__(self, base_url: str) -> None:
        self.http = httpx.Client(base_url=base_url, timeout=60.0, trust_env=False)

    def register(self) -> dict:
        creds = {"username": f"eval_{uuid.uuid4().hex[:10]}", "password": "eval-pass-123"}
        response = self.http.post("/api/auth/register", json=creds)
        response.raise_for_status()
        return response.json()

    def upload(self, image: Path) -> dict:
        with image.open("rb") as handle:
            response = self.http.post("/api/assets", files={"file": (image.name, handle, "image/png")})
        response.raise_for_status()
        return response.json()

    def close(self) -> None:
        self.http.close()


@dataclass
class CaseRun:
    case: dict
    status: str = "pass"  # pass / fail / skipped / error
    checks: list[str] = field(default_factory=list)
    # 每条断言的结构化结果：{"metric", "result": pass/fail/skipped, "where"}
    outcomes: list[dict] = field(default_factory=list)
    trace: list[dict] = field(default_factory=list)
    reason: str | None = None
    metrics: dict = field(default_factory=dict)


def plan_view(turn: dict | None) -> dict:
    steps = (turn or {}).get("steps") or []
    return {
        "tools": [step["tool"] for step in steps],
        "count": len(steps),
        "statuses": [step["status"] for step in steps],
        "run_ids": [step.get("run_id") for step in steps],
        "dispatched": sum(1 for step in steps if step.get("run_id")),
    }


class Runner:
    def __init__(self, base_url: str, dataset: Path, capabilities: set[str]) -> None:
        self.base_url = base_url
        self.dataset = dataset
        self.capabilities = capabilities

    # ------------------------------------------------------------ 单个样例

    def run_case(self, case: dict) -> CaseRun:
        result = CaseRun(case=case)
        missing = [need for need in case.get("requires", []) if need not in self.capabilities]
        if missing:
            result.status, result.reason = "skipped", f"运行环境缺少能力：{', '.join(missing)}"
            return result

        actors = {"A": Client(self.base_url), "B": Client(self.base_url), "anon": Client(self.base_url)}
        ctx: dict[str, Any] = {"ids": {}}
        try:
            actors["A"].register()
            actors["B"].register()
            self._setup(actors["A"], case.get("setup") or {}, ctx, result)
            for index, step in enumerate(case["steps"]):
                self._step(actors, step, ctx, result, index)
            self._snapshot(actors["A"], ctx)
            for spec in case.get("final", []):
                self._assert(ctx, spec, result, "final")
        except Exception as exc:  # noqa: BLE001 评测要记录任何异常，而不是中断整轮
            result.status, result.reason = "error", f"{type(exc).__name__}: {exc}"
        finally:
            for client in actors.values():
                client.close()
        return result

    def _setup(self, client: Client, setup: dict, ctx: dict, result: CaseRun) -> None:
        if not setup.get("image"):
            return
        asset = client.upload(self.dataset / setup["image"])
        ctx["ids"]["asset_id"] = asset["id"]
        response = client.http.post("/api/sessions", json={"current_asset_id": asset["id"]})
        response.raise_for_status()
        session = response.json()
        ctx["ids"]["session_id"] = session["id"]
        ctx["session"] = session
        if setup.get("selection"):
            selection = {"revision": session["revision"], **setup["selection"]}
            response = client.http.post(f"/api/sessions/{session['id']}/selection", json=selection)
            response.raise_for_status()
            result.trace.append({"event": "selection", "status": response.status_code})

    def _path(self, template: str, ctx: dict) -> str:
        return template.format(**ctx["ids"])

    def _fill(self, value: Any, ctx: dict) -> Any:
        """请求体里的 "{asset_id}" 这类占位符替换成本例运行时拿到的 id。"""
        if isinstance(value, str):
            return value.format(**ctx["ids"]) if "{" in value else value
        if isinstance(value, list):
            return [self._fill(item, ctx) for item in value]
        if isinstance(value, dict):
            return {key: self._fill(item, ctx) for key, item in value.items()}
        return value

    def _step(self, actors: dict, step: dict, ctx: dict, result: CaseRun, index: int) -> None:
        kind = step["do"]
        client = actors[step.get("as", "A")]
        sid = ctx["ids"].get("session_id")
        started = time.perf_counter()
        if kind == "send":
            response = client.http.post(f"/api/sessions/{sid}/messages", json={"text": step["text"]})
            ctx["status"] = response.status_code
            if response.status_code == 201:
                turn = response.json()
                ctx["ids"]["turn_id"] = turn["id"]
                ctx["turn_at_send"] = turn
                ctx["plan_at_send"] = plan_view(turn)
                result.metrics["send_latency_s"] = round(time.perf_counter() - started, 3)
                self._settle(client, ctx)
                result.metrics["turn_settle_s"] = round(time.perf_counter() - started, 3)
        elif kind in {"confirm", "cancel", "retry"}:
            tid = ctx["ids"].get("turn_id")
            response = client.http.post(f"/api/sessions/{sid}/messages/{tid}/{kind}")
            ctx["status"] = response.status_code
            if response.status_code == 200:
                self._settle(client, ctx)
        elif kind == "http":
            response = client.http.request(
                step["method"], self._path(step["path"], ctx), json=self._fill(step.get("json"), ctx)
            )
            ctx["status"] = response.status_code
            body = _json_or_none(response)
            ctx["resp"] = body
            for name, path in (step.get("capture") or {}).items():
                ctx["ids"][name] = resolve({"resp": body}, path)
            if response.headers.get("content-type", "").startswith("application/zip"):
                ctx["zip_entries"] = zipfile.ZipFile(io.BytesIO(response.content)).namelist()
        elif kind == "sse":
            ctx["sse"] = self._sse(client, self._path(step["path"], ctx))
            ctx["sse_statuses"] = [frame.get("status") for frame in ctx["sse"]]
            ctx["sse_last"] = ctx["sse"][-1] if ctx["sse"] else None
        elif kind == "snapshot":
            self._snapshot(client, ctx)
        else:
            raise ValueError(f"未知步骤 {kind}")

        result.trace.append(
            {
                "event": kind,
                "as": step.get("as", "A"),
                "status": ctx.get("status"),
                "elapsed_s": round(time.perf_counter() - started, 3),
                "turn": ctx.get("turn"),
                "resp": ctx.get("resp") if kind == "http" else None,
            }
        )
        for spec in step.get("expect", []):
            self._assert(ctx, spec, result, f"step {index + 1} {kind}")

    def _assert(self, ctx: dict, spec: dict, result: CaseRun, where: str) -> None:
        """单条断言也可以声明 requires：能力不足时记为 SKIP，不算通过也不算失败。"""
        metric = spec.get("metric", result.case["category"])
        missing = [need for need in spec.get("requires", []) if need not in self.capabilities]
        if missing:
            result.checks.append(f"[{where}] SKIP {spec.get('why') or spec['path']}（缺少能力：{', '.join(missing)}）")
            result.outcomes.append({"metric": metric, "result": "skipped", "where": where})
            return
        ok, line = check(ctx, spec)
        result.checks.append(f"[{where}] {line}")
        result.outcomes.append({"metric": metric, "result": "pass" if ok else "fail", "where": where})
        if not ok:
            result.status = "fail"

    def _settle(self, client: Client, ctx: dict) -> None:
        """轮询到这一轮不再是 running：queued 表示等待确认，其余为终态。"""
        sid, tid = ctx["ids"]["session_id"], ctx["ids"]["turn_id"]
        deadline = time.monotonic() + TURN_TIMEOUT
        while True:
            turns = client.http.get(f"/api/sessions/{sid}/messages").json()
            turn = next((item for item in turns if item["id"] == tid), None)
            ctx["turn"] = turn
            ctx["plan"] = plan_view(turn)
            if turn is None or turn["status"] != "running" or time.monotonic() > deadline:
                return
            time.sleep(POLL_INTERVAL)

    def _sse(self, client: Client, path: str) -> list[dict]:
        frames: list[dict] = []
        with client.http.stream("GET", path, timeout=RUN_TIMEOUT) as response:
            for line in response.iter_lines():
                if line.startswith("data: "):
                    frames.append(json.loads(line[6:]))
        return frames

    def _snapshot(self, client: Client, ctx: dict) -> None:
        sid = ctx["ids"].get("session_id")
        if not sid:
            return
        session = client.http.get(f"/api/sessions/{sid}").json()
        ctx["final_session"] = session
        layers = (session.get("document") or {}).get("layers") or []
        images = [layer for layer in layers if layer.get("kind") == "image" and layer.get("visible", True)]
        ctx["top_image"] = images[-1] if images else None
        # 接口按 seq 倒序返回，且包含已撤销但未被覆盖的记录；这里只取当前生效的链，按时间正序
        entries = sorted(client.http.get(f"/api/sessions/{sid}/history").json(), key=lambda entry: entry["seq"])
        current = session.get("history_seq", entries[-1]["seq"] if entries else 0)
        ctx["history"] = [entry for entry in entries if entry["seq"] <= current]
        ctx["history_actions"] = [entry["action"] for entry in ctx["history"]]
        ctx["history_last"] = ctx["history"][-1] if ctx["history"] else None


def _json_or_none(response: httpx.Response) -> Any:
    try:
        return response.json()
    except ValueError:
        return None


# ---------------------------------------------------------------- 报告


def summarize(runs: list[CaseRun]) -> dict:
    by_category: dict[str, dict[str, int]] = {}
    for run in runs:
        bucket = by_category.setdefault(run.case["category"], {"pass": 0, "fail": 0, "skipped": 0, "error": 0})
        bucket[run.status] += 1
    executed = [run for run in runs if run.status in {"pass", "fail", "error"}]
    by_metric: dict[str, dict[str, int]] = {}
    for run in runs:
        for outcome in run.outcomes:
            bucket = by_metric.setdefault(outcome["metric"], {"pass": 0, "fail": 0, "skipped": 0})
            bucket[outcome["result"]] += 1
    latency = {}
    for key in ("send_latency_s", "turn_settle_s"):
        values = sorted(run.metrics[key] for run in runs if key in run.metrics)
        if values:
            latency[key] = {
                "n": len(values),
                "p50": values[len(values) // 2],
                "p95": values[min(len(values) - 1, int(len(values) * 0.95))],
                "max": values[-1],
            }
    return {
        "total": len(runs),
        "executed": len(executed),
        "passed": sum(1 for run in runs if run.status == "pass"),
        "failed": sum(1 for run in runs if run.status == "fail"),
        "errors": sum(1 for run in runs if run.status == "error"),
        "skipped": sum(1 for run in runs if run.status == "skipped"),
        "by_category": by_category,
        "by_metric": by_metric,
        "latency": latency,
    }


def render(meta: dict, summary: dict, runs: list[CaseRun]) -> str:
    lines = [
        f"# Agent 行为评测报告：{meta['label']}",
        "",
        f"- 时间：{meta['started_at']}",
        f"- 被测地址：`{meta['base_url']}`",
        f"- 数据集：`{meta['dataset']}`，MANIFEST sha256 `{meta['manifest_sha256']}`",
        f"- 运行环境能力：{', '.join(sorted(meta['capabilities'])) or '无（未接真实模型）'}",
        f"- 备注：{meta.get('note') or '—'}",
        "",
        f"共 {summary['total']} 例，执行 {summary['executed']} 例：通过 {summary['passed']}，失败 {summary['failed']}，"
        f"异常 {summary['errors']}；跳过 {summary['skipped']} 例（运行环境缺少所需能力，未用 Mock 代替）。",
        "",
        "| 类别 | 通过 | 失败 | 异常 | 跳过 |",
        "| --- | --- | --- | --- | --- |",
    ]
    for category, bucket in sorted(summary["by_category"].items()):
        lines.append(f"| {category} | {bucket['pass']} | {bucket['fail']} | {bucket['error']} | {bucket['skipped']} |")
    lines += ["", "## 指标（按断言计）", "", "| 指标 | 通过 | 失败 | 跳过 | 通过率（已执行） |", "| --- | --- | --- | --- | --- |"]
    for metric, bucket in sorted(summary["by_metric"].items()):
        executed = bucket["pass"] + bucket["fail"]
        rate = f"{bucket['pass']}/{executed}" if executed else "—"
        lines.append(f"| {metric} | {bucket['pass']} | {bucket['fail']} | {bucket['skipped']} | {rate} |")
    if summary["latency"]:
        lines += ["", "| 耗时 | 样本数 | p50 秒 | p95 秒 | 最大 秒 |", "| --- | --- | --- | --- | --- |"]
        for key, stats in summary["latency"].items():
            lines.append(f"| {key} | {stats['n']} | {stats['p50']} | {stats['p95']} | {stats['max']} |")
    lines += ["", "## 逐例结果", ""]
    for run in runs:
        case = run.case
        lines.append(f"### {case['id']} {case['title']}（{run.status}）")
        if run.reason:
            lines.append(f"- 原因：{run.reason}")
        for line in run.checks:
            lines.append(f"- `{line}`")
        lines.append("")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--capabilities", default="none")
    parser.add_argument("--out", type=Path, default=Path("eval/reports"))
    parser.add_argument("--note", default="")
    parser.add_argument("--only", default="", help="逗号分隔的样例 id，只跑这些")
    args = parser.parse_args()

    capabilities = {item.strip() for item in args.capabilities.split(",") if item.strip() and item != "none"}
    manifest_sha = verify_manifest(args.dataset)
    cases = json.loads((args.dataset / "cases.json").read_text(encoding="utf-8"))["cases"]
    if args.only:
        wanted = set(args.only.split(","))
        cases = [case for case in cases if case["id"] in wanted]

    started = datetime.now(UTC)
    runner = Runner(args.base_url, args.dataset, capabilities)
    runs = []
    for case in cases:
        run = runner.run_case(case)
        print(f"{case['id']:<5} {run.status:<8} {case['title']}" + (f"  ({run.reason})" if run.reason else ""))
        runs.append(run)

    meta = {
        "label": args.label,
        "base_url": args.base_url,
        "dataset": str(args.dataset),
        "manifest_sha256": manifest_sha,
        "capabilities": sorted(capabilities),
        "started_at": started.isoformat(timespec="seconds"),
        "note": args.note,
    }
    summary = summarize(runs)
    out = args.out / f"{args.label}-{started.strftime('%Y%m%dT%H%M%SZ')}"
    out.mkdir(parents=True, exist_ok=True)
    (out / "results.json").write_text(
        json.dumps(
            {
                "meta": meta,
                "summary": summary,
                "cases": [
                    {
                        "id": run.case["id"],
                        "category": run.case["category"],
                        "status": run.status,
                        "reason": run.reason,
                        "checks": run.checks,
                        "outcomes": run.outcomes,
                        "metrics": run.metrics,
                    }
                    for run in runs
                ],
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    with (out / "traces.jsonl").open("w", encoding="utf-8") as handle:
        for run in runs:
            handle.write(json.dumps({"id": run.case["id"], "trace": run.trace}, ensure_ascii=False, default=str) + "\n")
    (out / "report.md").write_text(render(meta, summary, runs), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False))
    print(f"report: {out / 'report.md'}")
    return 0 if summary["failed"] == 0 and summary["errors"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
