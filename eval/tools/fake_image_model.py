# /// script
# requires-python = ">=3.11"
# dependencies = ["pillow>=10"]
# ///
"""评测用的占位图像接口（DashScope 兼容）。

作用与上游的 `IMAGE_PROVIDER=mock` 相同：让被测系统的图像工具能执行完成，从而判定**计划参数与流程**。
编辑接口返回纯红图，文生图返回纯绿图。它不是图像模型，输出没有任何语义，
对应能力声明 `image_exec`，**不能**用它得出出图质量结论（见 RUBRIC 第 1 节）。

用法：
  uv run eval/tools/fake_image_model.py --port 7340
被测 Java 版把 `retouch.image.dashscope.base-url` 指到这里，api-key 用任意占位值（只校验非空与一致）。
"""

from __future__ import annotations

import argparse
import base64
import io
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from PIL import Image

EDIT_COLOR = (200, 30, 30)
GENERATED_COLOR = (30, 160, 60)
MIN_EDGE, MAX_EDGE = 512, 2048


def _png(width: int, height: int, color: tuple[int, int, int]) -> bytes:
    buffer = io.BytesIO()
    Image.new("RGB", (max(1, width), max(1, height)), color).save(buffer, format="PNG")
    return buffer.getvalue()


class State:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.counter = 0
        self.files: dict[str, bytes] = {}
        self.tasks: dict[str, list[str]] = {}

    def next(self) -> int:
        with self.lock:
            self.counter += 1
            return self.counter


STATE = State()


def _edit_size(width: float, height: float) -> tuple[int, int]:
    """与真实编辑接口一致的边长收敛：短边不小于 512，长边不大于 2048。"""
    if min(width, height) < MIN_EDGE:
        k = MIN_EDGE / min(width, height)
        width, height = width * k, height * k
    if max(width, height) > MAX_EDGE:
        k = MAX_EDGE / max(width, height)
        width, height = width * k, height * k
    return max(MIN_EDGE, int(width)), max(MIN_EDGE, int(height))


class Handler(BaseHTTPRequestHandler):
    server_version = "fake-image-model/1"

    def log_message(self, fmt, *args):  # noqa: D102
        sys.stderr.write("fake-image-model " + (fmt % args) + "\n")

    def _json(self, status: int, body: dict) -> None:
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _file(self, width: int, height: int, color: tuple[int, int, int]) -> str:
        path = f"/files/{STATE.next()}.png"
        STATE.files[path] = _png(width, height, color)
        host = self.headers.get("Host", "127.0.0.1")
        return f"http://{host}{path}"

    @staticmethod
    def _choices(urls: list[str]) -> list[dict]:
        content = [{"image": url} for url in urls]
        return [{"message": {"role": "assistant", "content": content}}]

    def do_GET(self) -> None:  # noqa: N802
        if self.path in STATE.files:
            data = STATE.files[self.path]
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        if self.path.startswith("/api/v1/tasks/"):
            task = self.path.rsplit("/", 1)[-1]
            urls = STATE.tasks.get(task, [])
            self._json(200, {"output": {"task_id": task, "task_status": "SUCCEEDED", "choices": self._choices(urls)}})
            return
        self._json(404, {"code": "NotFound", "message": self.path})

    def do_POST(self) -> None:  # noqa: N802
        auth = self.headers.get("Authorization", "")
        if not auth.startswith("Bearer ") or not auth.removeprefix("Bearer ").strip():
            self._json(401, {"code": "InvalidApiKey", "message": "Invalid API-key provided."})
            return
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        params = body.get("parameters") or {}
        n = max(1, int(params.get("n") or 1))
        size = str(params.get("size") or "")
        if self.path.endswith("/multimodal-generation/generation"):
            width, height = 512, 512
            if "*" in size:
                width, height = (int(part) for part in size.split("*"))
            else:
                image = None
                for message in (body.get("input") or {}).get("messages") or []:
                    for item in message.get("content") or []:
                        if isinstance(item, dict) and item.get("image"):
                            image = item["image"]
                if image and image.startswith("data:"):
                    decoded = Image.open(io.BytesIO(base64.b64decode(image.split(",", 1)[1])))
                    width, height = _edit_size(decoded.width, decoded.height)
            urls = [self._file(width, height, EDIT_COLOR) for _ in range(n)]
            self._json(200, {"output": {"choices": self._choices(urls)}})
            return
        if self.path.endswith("/image-generation/generation"):
            width, height = 1024, 1024
            if "*" in size:
                width, height = (int(part) for part in size.split("*"))
            urls = [self._file(width, height, GENERATED_COLOR) for _ in range(n)]
            task = f"task-{STATE.next()}"
            STATE.tasks[task] = urls
            self._json(200, {"output": {"task_id": task, "task_status": "PENDING"}})
            return
        self._json(404, {"code": "NotFound", "message": self.path})


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=7340)
    args = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"占位图像接口（非真实模型，仅供 image_exec 流程判定）：http://127.0.0.1:{args.port}")
    server.serve_forever()


if __name__ == "__main__":
    main()
