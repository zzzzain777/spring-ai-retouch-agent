# /// script
# requires-python = ">=3.11"
# dependencies = ["pillow>=10"]
# ///
"""生成评测集用的合成图片。图片完全由本脚本绘制，不含任何第三方素材。

用法：uv run eval/tools/make_images.py eval/datasets/agent-behavior-v1/images
输出是确定性的，同一版本 Pillow 下重复运行得到相同像素。
"""

from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image, ImageDraw


def bottle_on_white(size: tuple[int, int] = (768, 768)) -> Image.Image:
    """白底商品图：一只蓝色瓶子，主体居中，四周留白。"""
    image = Image.new("RGB", size, (255, 255, 255))
    draw = ImageDraw.Draw(image)
    w, h = size
    body = (int(w * 0.36), int(h * 0.30), int(w * 0.64), int(h * 0.86))
    draw.rounded_rectangle(body, radius=int(w * 0.06), fill=(38, 92, 170))
    neck = (int(w * 0.45), int(h * 0.20), int(w * 0.55), int(h * 0.31))
    draw.rectangle(neck, fill=(38, 92, 170))
    cap = (int(w * 0.43), int(h * 0.14), int(w * 0.57), int(h * 0.21))
    draw.rounded_rectangle(cap, radius=8, fill=(30, 30, 30))
    label = (int(w * 0.39), int(h * 0.50), int(w * 0.61), int(h * 0.66))
    draw.rectangle(label, fill=(240, 240, 240))
    return image


def mug_on_gradient(size: tuple[int, int] = (1024, 768)) -> Image.Image:
    """横版场景图：浅色渐变背景上的红色杯子，左侧另有一个绿色小方块作为可消除的杂物。"""
    w, h = size
    image = Image.new("RGB", size)
    draw = ImageDraw.Draw(image)
    for y in range(h):
        shade = int(200 + 40 * y / h)
        draw.line([(0, y), (w, y)], fill=(shade, shade - 10, shade - 30))
    draw.rectangle((int(w * 0.40), int(h * 0.35), int(w * 0.60), int(h * 0.80)), fill=(190, 40, 40))
    draw.arc((int(w * 0.56), int(h * 0.45), int(w * 0.70), int(h * 0.68)), 270, 90, fill=(190, 40, 40), width=18)
    draw.ellipse((int(w * 0.38), int(h * 0.78), int(w * 0.62), int(h * 0.84)), fill=(120, 110, 100))
    draw.rectangle((int(w * 0.10), int(h * 0.60), int(w * 0.20), int(h * 0.75)), fill=(40, 150, 70))
    return image


def main(argv: list[str]) -> int:
    out = Path(argv[1] if len(argv) > 1 else "eval/datasets/agent-behavior-v1/images")
    out.mkdir(parents=True, exist_ok=True)
    bottle_on_white().save(out / "bottle-white.png", optimize=False)
    mug_on_gradient().save(out / "mug-scene.png", optimize=False)
    print(f"wrote 2 images to {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
