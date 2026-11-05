"""去背景：优先用 rembg（开源抠图模型，本地运行、免费）；没装或指定 corner 时用四角取色兜底。

MATTING_PROVIDER：auto（默认，有 rembg 就用）、rembg（必须用 rembg）、corner（只用四角取色，测试用，不下载模型）。
"""

import io
import os

import numpy as np
from PIL import Image

_TOLERANCE = 32.0


class MattingUnavailable(Exception):
    pass


def provider() -> str:
    return os.environ.get("MATTING_PROVIDER", "auto").strip().lower()


def remove_background(image: Image.Image) -> tuple[Image.Image, str]:
    """返回抠好的 RGBA 图与实际使用的方法（rembg 或 corner）。"""
    mode = provider()
    if mode != "corner":
        try:
            from rembg import remove
        except ImportError as exc:
            if mode == "rembg":
                raise MattingUnavailable("未安装 rembg，无法去背景") from exc
        else:
            buffer = io.BytesIO()
            image.convert("RGBA").save(buffer, format="PNG")
            result = Image.open(io.BytesIO(remove(buffer.getvalue()))).convert("RGBA")
            return result, "rembg"
    return corner_key(image), "corner"


def corner_key(image: Image.Image) -> Image.Image:
    """把与四个角平均颜色接近的像素设为透明。只适合纯色或接近纯色的背景。"""
    rgba = np.asarray(image.convert("RGBA"), dtype=np.float64)
    h, w = rgba.shape[:2]
    patch = max(1, min(w, h) // 20)
    corners = [
        rgba[:patch, :patch, :3],
        rgba[:patch, w - patch :, :3],
        rgba[h - patch :, :patch, :3],
        rgba[h - patch :, w - patch :, :3],
    ]
    key = np.mean([c.reshape(-1, 3).mean(axis=0) for c in corners], axis=0)
    distance = np.sqrt(((rgba[:, :, :3] - key) ** 2).sum(axis=2))
    # 阈值附近线性过渡，边缘不至于锯齿
    alpha = np.clip((distance - _TOLERANCE * 0.6) / (_TOLERANCE * 0.4), 0, 1) * rgba[:, :, 3]
    out = rgba.copy()
    out[:, :, 3] = alpha
    return Image.fromarray(out.astype(np.uint8), "RGBA")
