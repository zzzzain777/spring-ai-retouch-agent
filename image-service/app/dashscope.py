"""阿里云百炼（DashScope）图像模型客户端。本项目自己的实现。

- 图像编辑：同步接口（multimodal-generation），一次请求等到结果。
- 文生图：异步任务（请求头 X-DashScope-Async: enable），拿 task_id 后轮询。
- 返回的是临时图片链接，这里立刻下载回字节，由调用方写进自有存储。

密钥由 Java 放在内网请求头 X-DashScope-Key 里转交（没有时读环境变量 DASHSCOPE_API_KEY），不落盘、不写日志。
注意：本项目没有用真实 DashScope 验证过这些请求，只用本地假服务测过请求格式与流程。
"""

import base64
import io
import os
import time
from collections.abc import Callable
from dataclasses import dataclass

import httpx
from PIL import Image

EDIT_PATH = "/api/v1/services/aigc/multimodal-generation/generation"
GENERATE_PATH = "/api/v1/services/aigc/image-generation/generation"
TASK_PATH = "/api/v1/tasks/{task_id}"

# 编辑接口要求输出边长在这个范围内
MIN_EDGE = 512
MAX_EDGE = 2048


class ModelError(Exception):
    """模型服务不可用、拒绝请求或返回了无法使用的结果；消息可直接展示。"""


@dataclass(frozen=True)
class ModelConfig:
    base_url: str
    edit_model: str
    generate_model: str


def edit_size(width: int, height: int) -> tuple[int, int]:
    """把期望尺寸等比调到编辑接口接受的范围：短边不小于 512，长边不大于 2048。"""
    w, h = float(width), float(height)
    if min(w, h) < MIN_EDGE:
        k = MIN_EDGE / min(w, h)
        w, h = w * k, h * k
    if max(w, h) > MAX_EDGE:
        k = MAX_EDGE / max(w, h)
        w, h = w * k, h * k
    return max(MIN_EDGE, int(w)), max(MIN_EDGE, int(h))


def _data_url(image: Image.Image) -> str:
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    return "data:image/png;base64," + base64.b64encode(buffer.getvalue()).decode()


class DashScope:
    def __init__(
        self,
        config: ModelConfig,
        api_key: str | None,
        *,
        transport: httpx.BaseTransport | None = None,
        poll_interval: float = 3.0,
        poll_timeout: float = 300.0,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        key = (api_key or os.environ.get("DASHSCOPE_API_KEY") or "").strip()
        if not key:
            raise ModelError("未配置 DASHSCOPE_API_KEY，图像模型不可用")
        self._config = config
        self._client = httpx.Client(
            base_url=config.base_url.rstrip("/"),
            headers={"Authorization": f"Bearer {key}"},
            timeout=httpx.Timeout(30.0, read=200.0),
            transport=transport,
        )
        self._downloads = httpx.Client(timeout=60.0, transport=transport, follow_redirects=True)
        self._poll_interval = poll_interval
        self._poll_timeout = poll_timeout
        self._sleep = sleep

    def close(self) -> None:
        self._client.close()
        self._downloads.close()

    def edit(
        self,
        image: Image.Image,
        prompt: str,
        *,
        count: int = 1,
        size: tuple[int, int] | None = None,
        negative_prompt: str | None = None,
    ) -> list[Image.Image]:
        parameters: dict = {"n": count, "watermark": False}
        if size is not None:
            w, h = edit_size(*size)
            parameters["size"] = f"{w}*{h}"
        if negative_prompt:
            parameters["negative_prompt"] = negative_prompt
        payload = {
            "model": self._config.edit_model,
            "input": {"messages": [{"role": "user", "content": [{"image": _data_url(image)}, {"text": prompt}]}]},
            "parameters": parameters,
        }
        body = self._checked(self._post(EDIT_PATH, payload))
        return [self._download(url) for url in self._image_urls(body.get("output") or {})]

    def generate(
        self,
        prompt: str,
        width: int,
        height: int,
        *,
        count: int = 1,
        negative_prompt: str | None = None,
        seed: int | None = None,
        references: list[Image.Image] | None = None,
    ) -> list[Image.Image]:
        content = [{"image": _data_url(ref)} for ref in references or []]
        content.append({"text": prompt})
        parameters: dict = {"n": count, "size": f"{width}*{height}", "watermark": False}
        if negative_prompt:
            parameters["negative_prompt"] = negative_prompt
        if seed is not None:
            parameters["seed"] = seed
        payload = {
            "model": self._config.generate_model,
            "input": {"messages": [{"role": "user", "content": content}]},
            "parameters": parameters,
        }
        body = self._checked(self._post(GENERATE_PATH, payload, headers={"X-DashScope-Async": "enable"}))
        task_id = (body.get("output") or {}).get("task_id")
        if not task_id:
            raise ModelError("图像模型没有返回任务编号")
        deadline = time.monotonic() + self._poll_timeout
        while True:
            output = self._checked(self._get(TASK_PATH.format(task_id=task_id))).get("output") or {}
            status = output.get("task_status")
            if status == "SUCCEEDED":
                return [self._download(url) for url in self._image_urls(output)]
            if status in {"FAILED", "CANCELED", "UNKNOWN"}:
                raise ModelError("图像生成失败：" + str(output.get("message") or status))
            if time.monotonic() > deadline:
                raise ModelError("图像生成超时")
            self._sleep(self._poll_interval)

    def _post(self, path: str, payload: dict, headers: dict | None = None) -> httpx.Response:
        try:
            return self._client.post(path, json=payload, headers=headers)
        except httpx.TimeoutException as exc:
            raise ModelError("图像模型请求超时") from exc
        except httpx.HTTPError as exc:
            raise ModelError("连不上图像模型服务") from exc

    def _get(self, path: str) -> httpx.Response:
        try:
            return self._client.get(path)
        except httpx.HTTPError as exc:
            raise ModelError("查询图像任务失败") from exc

    @staticmethod
    def _checked(response: httpx.Response) -> dict:
        try:
            body = response.json()
        except ValueError as exc:
            raise ModelError(f"图像模型返回了无法解析的内容（HTTP {response.status_code}）") from exc
        if response.status_code >= 400 or body.get("code"):
            raise ModelError("图像模型拒绝了请求：" + str(body.get("message") or body.get("code") or response.status_code))
        return body

    @staticmethod
    def _image_urls(output: dict) -> list[str]:
        urls = []
        for choice in output.get("choices") or []:
            for item in (choice.get("message") or {}).get("content") or []:
                if isinstance(item, dict) and item.get("image"):
                    urls.append(item["image"])
        if not urls:
            raise ModelError("图像模型没有返回图片")
        return urls

    def _download(self, url: str) -> Image.Image:
        if not url.startswith(("https://", "http://")):
            raise ModelError("图像模型返回的图片地址不合法")
        try:
            response = self._downloads.get(url)
        except httpx.HTTPError as exc:
            raise ModelError("下载生成结果失败") from exc
        if response.status_code != 200:
            raise ModelError("下载生成结果失败")
        try:
            image = Image.open(io.BytesIO(response.content))
            image.load()
        except OSError as exc:
            raise ModelError("生成结果不是有效图片") from exc
        return image.convert("RGBA")
