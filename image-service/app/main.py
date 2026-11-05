"""图像服务入口。只在内网监听，Java 是唯一调用方：

- 请求头 X-Internal-Token 必须等于共享令牌；X-Run-Id 与 X-Attempt 标识 Java 的任务，
  输出只能写到 runs/{run_id}/{attempt}/（批量处理可再带一级子目录）下，同一任务重复执行会覆盖同一位置。
- 图片不走 HTTP 正文：请求里只有对象键与参数，响应里只有输出对象的元数据。
- 业务决定（改哪一层、用什么提示词、结果放哪）都在 Java；这里只做像素计算和调用图像模型。
- 调图像模型的接口需要请求头 X-DashScope-Key（Java 从配置里转交），模型地址与模型名放在请求体 model 里。
"""

import io
import logging
import re
import time
from typing import Annotated, Literal

from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import JSONResponse
from PIL import Image
from pydantic import BaseModel, Field, model_validator

from app import imaging, storage
from app.config import settings
from app.dashscope import DashScope, ModelConfig, ModelError
from app.ops import compose, matte, render
from app.ops import mask as masks
from app.ops.adjust import adjust

logger = logging.getLogger("image-service")
app = FastAPI(title="retouch image service", docs_url=None, redoc_url=None)

_KEY = re.compile(r"^[A-Za-z0-9/_.\-]{1,512}$")
_SUBDIR = re.compile(r"^[a-z0-9\-]{1,40}/$")


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


class PointIn(BaseModel):
    x: float = Field(ge=0, le=1)
    y: float = Field(ge=0, le=1)


class MaskIn(BaseModel):
    """选区遮罩。strokes 与 points 二选一；base_key 是已有选区的遮罩，给了就合并。"""

    output_prefix: str
    width: int = Field(ge=1, le=12000)
    height: int = Field(ge=1, le=12000)
    strokes: list[list[PointIn]] = []
    points: list[PointIn] = []
    radius: float = Field(default=0.03, ge=0.005, le=0.12)
    base_key: str | None = None


class LayerIn(BaseModel):
    kind: Literal["image", "text", "shape"]
    key: str | None = None
    width: int = Field(ge=1, le=20000)
    height: int = Field(ge=1, le=20000)
    x: float = 0
    y: float = 0
    scale_x: float = 1
    scale_y: float = 1
    rotation: float = 0
    opacity: float = Field(default=1, ge=0, le=1)
    visible: bool = True
    text: str | None = None
    font_size: float = 24
    fill: str | None = None


class CanvasIn(BaseModel):
    """画布文档的渲染所需部分；图像层的 key 由 Java 按用户授权后解析好。"""

    width: int = Field(ge=1, le=20000)
    height: int = Field(ge=1, le=20000)
    layers: list[LayerIn]
    include_text: bool = True
    background: Literal["white", "transparent"] = "white"


class SourceIn(BaseModel):
    """一张输入图：直接给对象键，或给画布让这里先合成。"""

    key: str | None = None
    canvas: CanvasIn | None = None

    @model_validator(mode="after")
    def _one(self):
        if (self.key is None) == (self.canvas is None):
            raise ValueError("key 与 canvas 必须且只能给一个")
        return self


class ModelIn(BaseModel):
    base_url: str = Field(pattern=r"^https?://")
    edit_model: str = ""
    generate_model: str = ""


class RemoveBackgroundIn(BaseModel):
    output_prefix: str
    input_key: str


class ModelEditIn(BaseModel):
    """整图交给图像模型编辑：换背景、扩图、超分、营销图都走这里，差别只在提示词与尺寸。"""

    output_prefix: str
    source: SourceIn
    prompt: str = Field(min_length=1, max_length=2000)
    count: int = Field(default=1, ge=1, le=4)
    width: int | None = Field(default=None, ge=1, le=20000)
    height: int | None = Field(default=None, ge=1, le=20000)
    negative_prompt: str | None = Field(default=None, max_length=500)
    fit_width: int | None = Field(default=None, ge=1, le=20000)
    fit_height: int | None = Field(default=None, ge=1, le=20000)
    model: ModelIn


class Box(BaseModel):
    x: float = 0
    y: float = 0
    width: int = Field(ge=1, le=20000)
    height: int = Field(ge=1, le=20000)


class EditRegionIn(BaseModel):
    output_prefix: str
    input_key: str
    layer: Box
    canvas_width: int = Field(ge=1, le=20000)
    canvas_height: int = Field(ge=1, le=20000)
    mask_key: str | None = None
    prompt: str = Field(min_length=1, max_length=2000)
    model: ModelIn


class SplitIn(BaseModel):
    output_prefix: str
    canvas: CanvasIn
    prompt: str = Field(min_length=1, max_length=2000)
    model: ModelIn


class PromoteLayerIn(BaseModel):
    id: str = Field(pattern=r"^[A-Za-z0-9_\-]{1,64}$")
    key: str
    x: float = 0
    y: float = 0
    background: bool = False


class PromoteIn(BaseModel):
    output_prefix: str
    canvas: CanvasIn
    mask_key: str
    layers: list[PromoteLayerIn] = []
    prompt: str = Field(min_length=1, max_length=2000)
    model: ModelIn


class MaskBoxIn(BaseModel):
    output_prefix: str
    mask_key: str
    width: int = Field(ge=1, le=20000)
    height: int = Field(ge=1, le=20000)


class SizeIn(BaseModel):
    width: int = Field(ge=1, le=8000)
    height: int = Field(ge=1, le=8000)


class DeliveryIn(BaseModel):
    output_prefix: str
    source: SourceIn
    sizes: list[SizeIn] = Field(min_length=1, max_length=8)


class GenerateIn(BaseModel):
    output_prefix: str
    prompt: str = Field(min_length=1, max_length=2000)
    width: int = Field(ge=64, le=4096)
    height: int = Field(ge=64, le=4096)
    count: int = Field(default=1, ge=1, le=6)
    negative_prompt: str | None = Field(default=None, max_length=1500)
    seed: int | None = Field(default=None, ge=0, le=2147483647)
    reference_keys: list[str] = Field(default=[], max_length=3)
    model: ModelIn


class EncodeIn(BaseModel):
    output_prefix: str
    input_key: str
    name: str = Field(pattern=r"^[a-z0-9\-]{1,40}$")
    image_format: Literal["PNG", "JPEG"] = "PNG"


def _authorize(token: str | None, run_id: str | None, attempt: str | None, output_prefix: str) -> None:
    if not token or token != settings().internal_token:
        raise HTTPException(status_code=401, detail="unauthorized")
    root = f"runs/{run_id}/{attempt}/"
    if not run_id or not attempt or not output_prefix.startswith(root):
        raise OpError(400, "输出前缀与任务不符")
    rest = output_prefix[len(root):]
    if rest and not _SUBDIR.match(rest):
        raise OpError(400, "输出前缀与任务不符")


def _read(key: str) -> bytes:
    if not _KEY.match(key) or ".." in key:
        raise OpError(400, "对象键不合法")
    try:
        return storage.get(key)
    except storage.ObjectMissing as exc:
        raise OpError(404, "输入图片不存在") from exc


def _image(key: str) -> Image.Image:
    try:
        return imaging.decode(_read(key))
    except imaging.BadImage as exc:
        raise OpError(422, str(exc)) from exc


def _mask(key: str, size: tuple[int, int]) -> Image.Image:
    """读选区遮罩。尺寸与画布不一致说明画幅已变，旧选区不能再用（不拉伸）。"""
    data = _read(key)
    image = Image.open(io.BytesIO(data))
    if image.size != size:
        raise OpError(409, "画幅已变，选区失效，请重新选择")
    return masks.to_luma(data, size)


def _save(prefix: str, name: str, image: Image.Image, image_format: str = "PNG") -> dict:
    if image_format == "JPEG":
        data, ext = imaging.encode_jpeg(image), "jpg"
    else:
        data, ext = imaging.encode_png(image), "png"
    key = f"{prefix}{name}.{ext}"
    storage.put(key, data, "image/jpeg" if ext == "jpg" else "image/png")
    return imaging.describe(key, image, data, name=name, image_format=image_format).as_json()


def _flatten(canvas: CanvasIn) -> Image.Image:
    layers = []
    for layer in canvas.layers:
        image = _image(layer.key) if layer.kind == "image" and layer.key and layer.visible else None
        layers.append(
            render.Layer(
                kind=layer.kind,
                width=layer.width,
                height=layer.height,
                image=image,
                x=layer.x,
                y=layer.y,
                scale_x=layer.scale_x,
                scale_y=layer.scale_y,
                rotation=layer.rotation,
                opacity=layer.opacity,
                visible=layer.visible,
                text=layer.text,
                font_size=layer.font_size,
                fill=layer.fill or "#141414",
            )
        )
    background = render.WHITE if canvas.background == "white" else render.CLEAR
    return render.flatten(canvas.width, canvas.height, layers, background=background, include_text=canvas.include_text)


def _source(source: SourceIn) -> Image.Image:
    return _image(source.key) if source.key is not None else _flatten(source.canvas)


def _model(config: ModelIn, api_key: str | None) -> DashScope:
    try:
        return DashScope(ModelConfig(config.base_url, config.edit_model, config.generate_model), api_key)
    except ModelError as exc:
        raise OpError(503, str(exc)) from exc


def _guard(token, run_id, attempt, prefix) -> float:
    _authorize(token, run_id, attempt, prefix)
    return time.perf_counter()


def _done(op: str, run_id, attempt, started: float) -> None:
    logger.info("%s run_id=%s attempt=%s %.0f ms", op, run_id, attempt, (time.perf_counter() - started) * 1000)


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


@app.post("/v1/ops/mask")
def mask_op(
    body: MaskIn,
    x_internal_token: Annotated[str | None, Header()] = None,
    x_run_id: Annotated[str | None, Header()] = None,
    x_attempt: Annotated[str | None, Header()] = None,
) -> dict:
    _authorize(x_internal_token, x_run_id, x_attempt, body.output_prefix)
    size = (body.width, body.height)
    if body.strokes and any(body.strokes):
        mask = masks.strokes(size, [[(p.x, p.y) for p in stroke] for stroke in body.strokes], body.radius)
    elif body.points:
        mask = masks.points(size, [(p.x, p.y) for p in body.points])
    else:
        raise OpError(422, "选区为空")
    if body.base_key:
        mask = masks.union(masks.to_luma(_read(body.base_key), size), mask)
    if masks.coverage(mask) == 0:
        raise OpError(422, "选区为空")
    result = masks.overlay(mask)
    data = imaging.encode_png(result)
    key = body.output_prefix + "mask.png"
    storage.put(key, data, "image/png")
    return {"outputs": [imaging.describe(key, result, data).as_json()]}


Token = Annotated[str | None, Header(alias="X-Internal-Token")]
RunId = Annotated[str | None, Header(alias="X-Run-Id")]
Attempt = Annotated[str | None, Header(alias="X-Attempt")]
ModelKey = Annotated[str | None, Header(alias="X-DashScope-Key")]


@app.post("/v1/ops/remove_background")
def remove_background_op(body: RemoveBackgroundIn, token: Token = None, run_id: RunId = None, attempt: Attempt = None) -> dict:
    started = _guard(token, run_id, attempt, body.output_prefix)
    try:
        subject, method = matte.remove_background(_image(body.input_key))
    except matte.MattingUnavailable as exc:
        raise OpError(503, str(exc)) from exc
    if subject.getchannel("A").getbbox() is None:
        raise OpError(422, "没有识别到主体")
    output = _save(body.output_prefix, "subject", subject)
    _done("remove_background", run_id, attempt, started)
    return {"outputs": [output], "meta": {"method": method}}


@app.post("/v1/ops/model_edit")
def model_edit_op(
    body: ModelEditIn, token: Token = None, run_id: RunId = None, attempt: Attempt = None, model_key: ModelKey = None
) -> dict:
    started = _guard(token, run_id, attempt, body.output_prefix)
    source = _source(body.source)
    client = _model(body.model, model_key)
    size = (body.width, body.height) if body.width and body.height else None
    try:
        images = client.edit(source, body.prompt, count=body.count, size=size, negative_prompt=body.negative_prompt)
    except ModelError as exc:
        raise OpError(502, str(exc)) from exc
    finally:
        client.close()
    outputs = []
    for index, image in enumerate(images[: body.count], start=1):
        if body.fit_width and body.fit_height:
            image = compose.fit(image, body.fit_width, body.fit_height)
        outputs.append(_save(body.output_prefix, f"edited-{index}", image))
    _done("model_edit", run_id, attempt, started)
    return {"outputs": outputs, "meta": {"source_width": source.width, "source_height": source.height}}


@app.post("/v1/ops/edit_region")
def edit_region_op(
    body: EditRegionIn, token: Token = None, run_id: RunId = None, attempt: Attempt = None, model_key: ModelKey = None
) -> dict:
    """改一个图层：有选区只改选区与该层的交集，没有选区改该层不透明的部分。"""
    started = _guard(token, run_id, attempt, body.output_prefix)
    source = _image(body.input_key)
    if body.mask_key:
        canvas_mask = _mask(body.mask_key, (body.canvas_width, body.canvas_height))
        try:
            region = compose.crop_to_layer(canvas_mask, body.layer.x, body.layer.y, body.layer.width, body.layer.height)
        except compose.EmptyMask as exc:
            raise OpError(422, "选区没有覆盖到该图层") from exc
    else:
        region = compose.alpha_mask(source)
        if region.getbbox() is None:
            raise OpError(422, "该图层是空的")
    client = _model(body.model, model_key)
    try:
        (edited, *_) = client.edit(source, body.prompt)
    except ModelError as exc:
        raise OpError(502, str(exc)) from exc
    finally:
        client.close()
    result = compose.blend(source, edited, region)
    output = _save(body.output_prefix, "region", result)
    _done("edit_region", run_id, attempt, started)
    return {"outputs": [output]}


def _repair(client: DashScope, image: Image.Image, hole: Image.Image, prompt: str) -> Image.Image:
    """先挖洞并用周围颜色补平（模型看不到原物体，不会把它画回去），再让模型只修补洞里的部分。"""
    prepared = compose.fill_hole(image, hole)
    (edited, *_) = client.edit(prepared, prompt)
    return compose.blend(prepared, edited, hole)


@app.post("/v1/ops/split")
def split_op(
    body: SplitIn, token: Token = None, run_id: RunId = None, attempt: Attempt = None, model_key: ModelKey = None
) -> dict:
    """拆成主体与背景两层：本地抠出主体，背景挖掉主体后交给图像模型修补。"""
    started = _guard(token, run_id, attempt, body.output_prefix)
    source = _flatten(body.canvas)
    try:
        subject, method = matte.remove_background(source)
    except matte.MattingUnavailable as exc:
        raise OpError(503, str(exc)) from exc
    shape = subject.getchannel("A")
    if shape.getbbox() is None:
        raise OpError(422, "没有识别到主体")
    hole = compose.grow(shape.point(lambda v: 255 if v > 16 else 0), compose.grow_for(source.size))
    client = _model(body.model, model_key)
    try:
        background = _repair(client, source, hole, body.prompt)
    except ModelError as exc:
        raise OpError(502, str(exc)) from exc
    finally:
        client.close()
    outputs = [_save(body.output_prefix, "background", background), _save(body.output_prefix, "subject", subject)]
    _done("split", run_id, attempt, started)
    return {"outputs": outputs, "meta": {"subject_digest": compose.digest(shape), "method": method}}


@app.post("/v1/ops/promote")
def promote_op(
    body: PromoteIn, token: Token = None, run_id: RunId = None, attempt: Attempt = None, model_key: ModelKey = None
) -> dict:
    """把选区里的物体单独做成图层，原位置补上背景。

    layers 为空：画布还没拆层，输出整张修补后的背景与物体。
    layers 不为空：已拆层，背景层在洞里换成修补结果，其余图像层在选区处打透明，输出每层的新图。
    """
    started = _guard(token, run_id, attempt, body.output_prefix)
    size = (body.canvas.width, body.canvas.height)
    selection = _mask(body.mask_key, size)
    source = _flatten(body.canvas)
    try:
        piece, box = compose.cut(source, selection)
    except compose.EmptyMask as exc:
        raise OpError(422, "选区为空，请重新选择") from exc
    hole = compose.grow(selection, compose.grow_for(size))
    client = _model(body.model, model_key)
    try:
        filled = _repair(client, source, hole, body.prompt)
    except ModelError as exc:
        raise OpError(502, str(exc)) from exc
    finally:
        client.close()
    outputs = [_save(body.output_prefix, "object", piece)]
    if not body.layers:
        outputs.append(_save(body.output_prefix, "background", filled))
    for layer in body.layers:
        image = _image(layer.key)
        if layer.background:
            local = compose.fit(filled, image.width, image.height) if image.size != size else filled
            updated = compose.blend(image, local, hole if image.size == size else compose.fit(hole, image.width, image.height))
        else:
            updated = compose.punch(image, selection, layer.x, layer.y)
        outputs.append(_save(body.output_prefix, f"layer-{layer.id.lower()}", updated))
    left, top, right, bottom = box
    meta = {"x": left, "y": top, "width": right - left, "height": bottom - top, "digest": compose.digest(selection)}
    _done("promote", run_id, attempt, started)
    return {"outputs": outputs, "meta": meta}


@app.post("/v1/ops/mask_box")
def mask_box_op(body: MaskBoxIn, token: Token = None, run_id: RunId = None, attempt: Attempt = None) -> dict:
    """选区的外接框与指纹：Java 据此决定改哪一层、物体是否已经提升过。不写任何对象。"""
    _guard(token, run_id, attempt, body.output_prefix)
    selection = _mask(body.mask_key, (body.width, body.height))
    box = selection.getbbox()
    return {"outputs": [], "meta": {"box": list(box) if box else None, "digest": compose.digest(selection)}}


@app.post("/v1/ops/delivery")
def delivery_op(body: DeliveryIn, token: Token = None, run_id: RunId = None, attempt: Attempt = None) -> dict:
    """投放尺寸：整张图等比放进各个画幅，白底留边，不裁切、不调模型。"""
    started = _guard(token, run_id, attempt, body.output_prefix)
    source = _source(body.source)
    outputs = []
    for size in body.sizes:
        boxed = compose.letterbox(source, size.width, size.height)
        outputs.append(_save(body.output_prefix, f"delivery-{size.width}x{size.height}", boxed))
    _done("delivery", run_id, attempt, started)
    return {"outputs": outputs}


@app.post("/v1/ops/generate")
def generate_op(
    body: GenerateIn, token: Token = None, run_id: RunId = None, attempt: Attempt = None, model_key: ModelKey = None
) -> dict:
    started = _guard(token, run_id, attempt, body.output_prefix)
    references = [_image(key) for key in body.reference_keys]
    client = _model(body.model, model_key)
    try:
        images = client.generate(
            body.prompt,
            body.width,
            body.height,
            count=body.count,
            negative_prompt=body.negative_prompt,
            seed=body.seed,
            references=references,
        )
    except ModelError as exc:
        raise OpError(502, str(exc)) from exc
    finally:
        client.close()
    outputs = [_save(body.output_prefix, f"generated-{i}", image) for i, image in enumerate(images[: body.count], start=1)]
    _done("generate", run_id, attempt, started)
    return {"outputs": outputs}


@app.post("/v1/ops/encode")
def encode_op(body: EncodeIn, token: Token = None, run_id: RunId = None, attempt: Attempt = None) -> dict:
    """批量导出：按格式重新编码；JPG 铺白底。"""
    _guard(token, run_id, attempt, body.output_prefix)
    image = _image(body.input_key)
    if body.image_format == "JPEG":
        image = compose.flatten_on_white(image)
    return {"outputs": [_save(body.output_prefix, body.name, image, body.image_format)]}


def main() -> None:
    import uvicorn

    logging.basicConfig(level=logging.INFO)
    uvicorn.run("app.main:app", host="127.0.0.1", port=7303)


if __name__ == "__main__":
    main()
