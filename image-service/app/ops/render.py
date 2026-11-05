"""按画布文档合成一张图。本项目自己的实现。

几何约定与前端画布一致：图层先按 scale_x / scale_y 缩放（负值表示镜像），再绕图层原始框的中心
（x + width / 2, y + height / 2）顺时针旋转 rotation 度，最后按 opacity 叠到画布上。
"""

import os
from dataclasses import dataclass
from functools import lru_cache

from PIL import Image, ImageDraw, ImageFont, ImageOps

WHITE = (255, 255, 255, 255)
CLEAR = (0, 0, 0, 0)

# 常见系统里能显示中文的字体；都没有时退回 Pillow 自带字体（中文会显示成方框）
_FONTS = (
    "/System/Library/Fonts/PingFang.ttc",
    "/System/Library/Fonts/STHeiti Medium.ttc",
    "/Library/Fonts/Arial Unicode.ttf",
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
    "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
    "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
)


@dataclass(frozen=True)
class Layer:
    kind: str
    width: int
    height: int
    image: Image.Image | None = None
    x: float = 0
    y: float = 0
    scale_x: float = 1
    scale_y: float = 1
    rotation: float = 0
    opacity: float = 1
    visible: bool = True
    text: str | None = None
    font_size: float = 24
    fill: str = "#141414"


def flatten(width: int, height: int, layers: list[Layer], *, background=WHITE, include_text: bool = True) -> Image.Image:
    canvas = Image.new("RGBA", (width, height), background)
    for layer in layers:
        if not layer.visible or layer.opacity <= 0:
            continue
        if layer.kind == "text":
            if include_text:
                tile = _text_tile(layer)
                if tile is not None:
                    _place(canvas, layer, tile)
        elif layer.kind == "image" and layer.image is not None:
            _place(canvas, layer, layer.image.convert("RGBA"))
    return canvas


def _place(canvas: Image.Image, layer: Layer, tile: Image.Image) -> None:
    w = max(1, round(layer.width * abs(layer.scale_x)))
    h = max(1, round(layer.height * abs(layer.scale_y)))
    if tile.size != (w, h):
        tile = tile.resize((w, h), Image.Resampling.LANCZOS)
    if layer.scale_x < 0:
        tile = ImageOps.mirror(tile)
    if layer.scale_y < 0:
        tile = ImageOps.flip(tile)
    if layer.opacity < 1:
        alpha = tile.getchannel("A").point(lambda v: round(v * layer.opacity))
        tile.putalpha(alpha)
    if layer.rotation % 360:
        # Pillow 的正角度是逆时针，画布约定顺时针为正
        tile = tile.rotate(-layer.rotation, resample=Image.Resampling.BICUBIC, expand=True)
    cx = layer.x + layer.width / 2
    cy = layer.y + layer.height / 2
    left = round(cx - tile.width / 2)
    top = round(cy - tile.height / 2)
    _composite(canvas, tile, left, top)


def _composite(canvas: Image.Image, tile: Image.Image, left: int, top: int) -> None:
    """alpha_composite 不接受负坐标，先把露在画布外的部分裁掉。"""
    x0, y0 = max(0, left), max(0, top)
    x1, y1 = min(canvas.width, left + tile.width), min(canvas.height, top + tile.height)
    if x1 <= x0 or y1 <= y0:
        return
    piece = tile.crop((x0 - left, y0 - top, x1 - left, y1 - top))
    canvas.alpha_composite(piece, (x0, y0))


def _text_tile(layer: Layer) -> Image.Image | None:
    content = (layer.text or "").strip()
    if not content:
        return None
    tile = Image.new("RGBA", (max(1, layer.width), max(1, layer.height)), CLEAR)
    draw = ImageDraw.Draw(tile)
    font = _font(max(8, round(layer.font_size)))
    left, top, right, bottom = draw.textbbox((0, 0), content, font=font)
    x = (tile.width - (right - left)) / 2 - left
    y = (tile.height - (bottom - top)) / 2 - top
    draw.text((x, y), content, font=font, fill=_color(layer.fill))
    return tile


@lru_cache(maxsize=32)
def _font(size: int):
    for path in _FONTS:
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size)
            except OSError:
                continue
    return ImageFont.load_default(size=size)


def _color(fill: str | None) -> tuple[int, int, int, int]:
    value = (fill or "").lstrip("#")
    if len(value) == 3:
        value = "".join(c * 2 for c in value)
    try:
        return int(value[0:2], 16), int(value[2:4], 16), int(value[4:6], 16), 255
    except (ValueError, IndexError):
        return 20, 20, 20, 255
