"""图像服务入口。只在内网监听，Java 是唯一调用方：

- 请求头 X-Internal-Token 必须等于共享令牌；X-Run-Id 与 X-Attempt 标识 Java 的任务，
  输出只能写到 runs/{run_id}/{attempt}/ 下，同一任务重复执行会覆盖同一位置，不产生多余对象。
- 图片不走 HTTP 正文：请求里只有对象键与参数，响应里只有输出对象的元数据。
"""

import logging
import re
import time
from typing import Annotated

from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from app import imaging, storage
from app.config import settings
from app.ops.adjust import adjust

logger = logging.getLogger("image-service")
app = FastAPI(title="retouch image service", docs_url=None, redoc_url=None)

_KEY = re.compile(r"^[A-Za-z0-9/_.\-]{1,512}$")


class OpError(Exception):
    def __init__(self, status: int, message: str) -> None:
        super().__init__(message)
        self.status = status
        self.message = message


@app.exception_handler(OpError)
async def _op_error(_, exc: OpError) -> JSONResponse:
    return JSONResponse(status_code=exc.status, content={"error": exc.message})


class AdjustParams(BaseModel):
    brightness: float = Field(default=0, ge=-1, le=1)
    contrast: float = Field(default=0, ge=-1, le=1)
    highlights: float = Field(default=0, ge=-1, le=1)
    shadows: float = Field(default=0, ge=-1, le=1)
    temperature: float = Field(default=0, ge=-1, le=1)
    tint: float = Field(default=0, ge=-1, le=1)
    saturation: float = Field(default=0, ge=-1, le=1)
    vibrance: float = Field(default=0, ge=-1, le=1)
    sharpness: float = Field(default=0, ge=-1, le=1)
    clarity: float = Field(default=0, ge=-1, le=1)
    vignette: float = Field(default=0, ge=0, le=1)


class AdjustIn(BaseModel):
    input_key: str
    output_prefix: str
    params: AdjustParams = AdjustParams()


def _authorize(token: str | None, run_id: str | None, attempt: str | None, output_prefix: str) -> None:
    if not token or token != settings().internal_token:
        raise HTTPException(status_code=401, detail="unauthorized")
    if not run_id or not attempt or output_prefix != f"runs/{run_id}/{attempt}/":
        raise OpError(400, "输出前缀与任务不符")


def _read(key: str) -> bytes:
    if not _KEY.match(key) or ".." in key:
        raise OpError(400, "对象键不合法")
    try:
        return storage.get(key)
    except storage.ObjectMissing as exc:
        raise OpError(404, "输入图片不存在") from exc


@app.get("/healthz")
def healthz() -> dict:
    return {"status": "ok"}


@app.post("/v1/ops/adjust")
def adjust_op(
    body: AdjustIn,
    x_internal_token: Annotated[str | None, Header()] = None,
    x_run_id: Annotated[str | None, Header()] = None,
    x_attempt: Annotated[str | None, Header()] = None,
) -> dict:
    _authorize(x_internal_token, x_run_id, x_attempt, body.output_prefix)
    started = time.perf_counter()
    try:
        image = imaging.decode(_read(body.input_key))
    except imaging.BadImage as exc:
        raise OpError(422, str(exc)) from exc
    result = adjust(image, body.params.model_dump())
    data = imaging.encode_png(result)
    key = body.output_prefix + "adjusted.png"
    storage.put(key, data, "image/png")
    output = imaging.describe(key, result, data)
    logger.info("adjust run_id=%s attempt=%s %.0f ms", x_run_id, x_attempt, (time.perf_counter() - started) * 1000)
    return {"outputs": [output.as_json()]}


def main() -> None:
    import uvicorn

    logging.basicConfig(level=logging.INFO)
    uvicorn.run("app.main:app", host="127.0.0.1", port=7303)


if __name__ == "__main__":
    main()
