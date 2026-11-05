"""选区遮罩：笔刷涂抹栅格化、点选生成区域、与已有选区合并。本项目自己的实现。

遮罩以 RGBA PNG 存储：选中处为不透明的提示色，其余全透明；读回时取透明通道作为灰度遮罩。
"""

import io

import numpy as np
from PIL import Image, ImageChops, ImageDraw

# 选区提示色（前端叠在画布上显示）
TINT = (64, 140, 255)


def to_luma(data: bytes, size: tuple[int, int]) -> Image.Image:
    """已存的遮罩读回成 L 模式；尺寸不同时按最近邻缩放（只在合并时用，执行工具时尺寸不符视为失效）。"""
    image = Image.open(io.BytesIO(data))
    luma = image.getchannel("A") if image.mode in ("RGBA", "LA") else image.convert("L")
    if luma.size != size:
        luma = luma.resize(size, Image.Resampling.NEAREST)
    return luma


def strokes(size: tuple[int, int], paths: list[list[tuple[float, float]]], radius: float) -> Image.Image:
    """把归一化坐标的笔画画成遮罩。

    笔刷宽度按画布短边乘 radius；三个点以上的笔画同时按多边形填充，圈住一个物体就选中整块；
    沿笔画再描一遍粗线和圆点，自相交或很短的笔画也一定覆盖涂过的地方。
    """
    width, height = size
    mask = Image.new("L", size, 0)
    draw = ImageDraw.Draw(mask)
    brush = max(2, round(min(width, height) * radius))
    for path in paths:
        points = [(x * (width - 1), y * (height - 1)) for x, y in path]
        if not points:
            continue
        if len(points) >= 3:
            draw.polygon(points, fill=255)
        if len(points) >= 2:
            draw.line(points, fill=255, width=brush)
        half = brush / 2
        for x, y in points:
            draw.ellipse((x - half, y - half, x + half, y + half), fill=255)
    return mask


def points(size: tuple[int, int], marks: list[tuple[float, float]]) -> Image.Image:
    """点选的简易实现：以每个点为圆心、画布短边 15% 为半径的圆形区域。

    这不是语义分割；接入分割模型（B9 引入 rembg 后）之前，点选只给出点附近的大致区域。
    """
    width, height = size
    mask = Image.new("L", size, 0)
    draw = ImageDraw.Draw(mask)
    radius = max(16, round(min(width, height) * 0.15))
    for nx, ny in marks:
        x, y = nx * (width - 1), ny * (height - 1)
        draw.ellipse((x - radius, y - radius, x + radius, y + radius), fill=255)
    return mask


def union(left: Image.Image, right: Image.Image) -> Image.Image:
    return ImageChops.lighter(left, right)


def overlay(mask: Image.Image) -> Image.Image:
    """L 遮罩 → 选中处为提示色、其余透明的 RGBA 图。"""
    alpha = np.asarray(mask, dtype=np.uint8)
    rgba = np.zeros((*alpha.shape, 4), dtype=np.uint8)
    selected = alpha > 0
    rgba[selected, 0], rgba[selected, 1], rgba[selected, 2] = TINT
    rgba[selected, 3] = 255
    return Image.fromarray(rgba, mode="RGBA")


def coverage(mask: Image.Image) -> float:
    return float((np.asarray(mask) > 0).mean())
