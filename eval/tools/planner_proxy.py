# /// script
# requires-python = ">=3.11"
# dependencies = ["httpx>=0.27"]
# ///
"""评测用的规划模型转发器：让上游原版在不改代码的情况下改用 DeepSeek 做规划，并记录执行轨迹。

上游把规划模型地址写死为 `DASHSCOPE_BASE_URL + "/compatible-mode/v1"`，规划和图像共用
`DASHSCOPE_API_KEY`。本转发器只改配置、不改上游代码：

  上游 DASHSCOPE_BASE_URL=http://127.0.0.1:7320   PLANNER_MODEL=deepseek-chat
  上游 DASHSCOPE_API_KEY=placeholder（转发器丢弃这个值，换成下面的真实密钥）

路由：
  /compatible-mode/v1/*  ->  DEEPSEEK_BASE_URL（默认 https://api.deepseek.com/v1），密钥取 DEEPSEEK_API_KEY
  其余路径（图像接口）     ->  DASHSCOPE_UPSTREAM（默认 https://dashscope.aliyuncs.com），密钥取 DASHSCOPE_API_KEY；
                              未配置该密钥时返回 503，不伪造图像结果

每次规划调用写一行 JSONL 轨迹：请求的工具数、消息数，模型返回的 tool_calls、用量与耗时。
密钥只从本进程环境变量读取，不写进轨迹。

用法：DEEPSEEK_API_KEY=... uv run eval/tools/planner_proxy.py --port 7320 --trace eval/reports/planner-trace.jsonl
"""

from __future__ import annotations

import argparse
import json
import os
import threading
import time
from datetime import UTC, datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import httpx

PLANNER_PREFIX = "/compatible-mode/v1"
HOP_HEADERS = {"host", "content-length", "authorization", "connection", "accept-encoding", "transfer-encoding"}


class Config:
    def __init__(self, trace: Path) -> None:
        self.deepseek_base = os.environ.get("DEEPSEEK_BASE_URL", "https://api.deepseek.com/v1").rstrip("/")
        self.deepseek_key = os.environ.get("DEEPSEEK_API_KEY", "")
        self.dashscope_base = os.environ.get("DASHSCOPE_UPSTREAM", "https://dashscope.aliyuncs.com").rstrip("/")
        self.dashscope_key = os.environ.get("DASHSCOPE_API_KEY", "")
        self.trace = trace
        self.lock = threading.Lock()
        self.http = httpx.Client(timeout=120.0)

    def route(self, path: str) -> tuple[str, str, str]:
        """返回 (目标 URL, 密钥, 目标名)。"""
        if path.startswith(PLANNER_PREFIX):
            return self.deepseek_base + path[len(PLANNER_PREFIX):], self.deepseek_key, "deepseek"
        return self.dashscope_base + path, self.dashscope_key, "dashscope"

    def record(self, entry: dict) -> None:
        with self.lock, self.trace.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(entry, ensure_ascii=False) + "\n")


def summarize_planner(request: dict, response: dict | None) -> dict:
    choice = ((response or {}).get("choices") or [{}])[0]
    message = choice.get("message") or {}
    calls = [
        {"name": call.get("function", {}).get("name"), "arguments": call.get("function", {}).get("arguments")}
        for call in message.get("tool_calls") or []
    ]
    return {
        "model": request.get("model"),
        "messages": len(request.get("messages") or []),
        "tools_offered": len(request.get("tools") or []),
        "tool_calls": calls,
        "content": (message.get("content") or "")[:500],
        "finish_reason": choice.get("finish_reason"),
        "usage": (response or {}).get("usage"),
    }


def make_handler(config: Config) -> type[BaseHTTPRequestHandler]:
    class Handler(BaseHTTPRequestHandler):
        def _forward(self) -> None:
            body = self.rfile.read(int(self.headers.get("content-length") or 0))
            url, key, target = config.route(self.path)
            if not key:
                self._reply(503, {"error": f"转发器未配置 {target} 的密钥，拒绝请求而不是返回伪造结果"})
                return
            headers = {k: v for k, v in self.headers.items() if k.lower() not in HOP_HEADERS}
            headers["Authorization"] = f"Bearer {key}"
            started = time.perf_counter()
            try:
                upstream = config.http.request(self.command, url, content=body, headers=headers)
            except httpx.HTTPError as exc:
                self._reply(502, {"error": f"转发失败：{type(exc).__name__}"})
                config.record({"at": _now(), "target": target, "path": self.path, "error": type(exc).__name__})
                return
            elapsed = round(time.perf_counter() - started, 3)
            if target == "deepseek":
                try:
                    request_json = json.loads(body or b"{}")
                    response_json = upstream.json()
                except ValueError:
                    request_json, response_json = {}, None
                config.record(
                    {"at": _now(), "target": target, "status": upstream.status_code, "elapsed_s": elapsed}
                    | summarize_planner(request_json, response_json)
                )
            else:
                config.record({"at": _now(), "target": target, "path": self.path, "status": upstream.status_code, "elapsed_s": elapsed})
            self.send_response(upstream.status_code)
            for name, value in upstream.headers.items():
                if name.lower() not in HOP_HEADERS | {"content-encoding"}:
                    self.send_header(name, value)
            self.send_header("Content-Length", str(len(upstream.content)))
            self.end_headers()
            self.wfile.write(upstream.content)

        def _reply(self, code: int, payload: dict) -> None:
            data = json.dumps(payload, ensure_ascii=False).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        do_GET = do_POST = _forward

        def log_message(self, *_: object) -> None:  # 轨迹已写 JSONL，不再打印访问日志
            return

    return Handler


def _now() -> str:
    return datetime.now(UTC).isoformat(timespec="milliseconds")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=7320)
    parser.add_argument("--trace", type=Path, default=Path("eval/reports/planner-trace.jsonl"))
    args = parser.parse_args()
    args.trace.parent.mkdir(parents=True, exist_ok=True)
    config = Config(args.trace)
    if not config.deepseek_key:
        print("警告：未设置 DEEPSEEK_API_KEY，规划请求会返回 503")
    server = ThreadingHTTPServer(("127.0.0.1", args.port), make_handler(config))
    print(f"planner proxy on 127.0.0.1:{args.port} -> {config.deepseek_base}; trace {args.trace}")
    server.serve_forever()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
