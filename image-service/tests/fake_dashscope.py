"""测试用的假 DashScope：只模拟请求格式与返回结构，不代表任何真实模型的效果。

编辑接口把输入图整体染成固定颜色返回；文生图接口先返回 PENDING 再返回 SUCCEEDED。
"""

import base64
import io
import json

import httpx
from PIL import Image

EDIT_COLOR = (200, 30, 30, 255)
GENERATE_COLOR = (30, 160, 60, 255)


def _png(image: Image.Image) -> bytes:
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    return buffer.getvalue()


class FakeDashScope:
    def __init__(self) -> None:
        self.requests: list[dict] = []
        self.files: dict[str, bytes] = {}
        self.polls = 0
        self.fail_edit: str | None = None

    def transport(self) -> httpx.MockTransport:
        return httpx.MockTransport(self.handle)

    def handle(self, request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path.startswith("/files/"):
            return httpx.Response(200, content=self.files[path])
        if request.headers.get("authorization") != "Bearer test-model-key":
            return httpx.Response(401, json={"code": "InvalidApiKey", "message": "Invalid API-key provided."})
        if request.method == "GET" and path.startswith("/api/v1/tasks/"):
            self.polls += 1
            if self.polls < 2:
                return httpx.Response(200, json={"output": {"task_id": "t1", "task_status": "PENDING"}})
            return httpx.Response(200, json={"output": {"task_id": "t1", "task_status": "SUCCEEDED",
                                                        "choices": self._choices("gen", GENERATE_COLOR, (64, 48), 1)}})
        body = json.loads(request.content)
        self.requests.append({"path": path, "headers": dict(request.headers), "body": body})
        if path.endswith("/multimodal-generation/generation"):
            if self.fail_edit:
                return httpx.Response(400, json={"code": "DataInspectionFailed", "message": self.fail_edit})
            content = body["input"]["messages"][0]["content"]
            source = Image.open(io.BytesIO(base64.b64decode(content[0]["image"].split(",", 1)[1])))
            size = body["parameters"].get("size")
            dims = tuple(int(v) for v in size.split("*")) if size else source.size
            return httpx.Response(200, json={"output": {"choices": self._choices("edit", EDIT_COLOR, dims,
                                                                                  body["parameters"]["n"])}})
        if path.endswith("/image-generation/generation"):
            assert request.headers.get("x-dashscope-async") == "enable"
            return httpx.Response(200, json={"output": {"task_id": "t1", "task_status": "PENDING"}})
        return httpx.Response(404, json={"code": "NotFound", "message": path})

    def _choices(self, prefix: str, color, size, count: int) -> list[dict]:
        content = []
        for index in range(count):
            name = f"/files/{prefix}-{len(self.files)}-{index}.png"
            self.files[name] = _png(Image.new("RGBA", size, color))
            content.append({"image": "https://dashscope-result.example" + name})
        return [{"message": {"role": "assistant", "content": content}}]
