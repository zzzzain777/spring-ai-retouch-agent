"""调色：亮度、对比度、高光、阴影、色温、色调、饱和度、自然饱和度、锐化、清晰度、晕影。

本项目自己的实现：在 0–1 浮点 RGB 上逐项调整，透明通道原样保留。各参数取值 -1 到 1（晕影 0 到 1），0 表示不变。
"""

import numpy as np
from PIL import Image, ImageFilter

PARAMS = (
    "brightness",
    "contrast",
    "highlights",
    "shadows",
    "temperature",
    "tint",
    "saturation",
    "vibrance",
    "sharpness",
    "clarity",
    "vignette",
)


def _luma(rgb: np.ndarray) -> np.ndarray:
    return rgb[..., 0] * 0.2126 + rgb[..., 1] * 0.7152 + rgb[..., 2] * 0.0722


def _smoothstep(edge0: float, edge1: float, x: np.ndarray) -> np.ndarray:
    t = np.clip((x - edge0) / (edge1 - edge0), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def _tone(rgb: np.ndarray, p: dict[str, float]) -> np.ndarray:
    b = p.get("brightness", 0.0)
    if b > 0:
        rgb = 1 - (1 - rgb) * (1 - 0.6 * b)
    elif b < 0:
        rgb = rgb * (1 + 0.6 * b)

    c = p.get("contrast", 0.0)
    if c:
        rgb = (rgb - 0.5) * (1 + c) + 0.5

    luma = _luma(np.clip(rgb, 0, 1))[..., None]
    h = p.get("highlights", 0.0)
    if h:
        rgb = rgb + 0.3 * h * _smoothstep(0.5, 1.0, luma)
    s = p.get("shadows", 0.0)
    if s:
        rgb = rgb + 0.3 * s * (1 - _smoothstep(0.0, 0.5, luma))

    t = p.get("temperature", 0.0)
    if t:
        rgb = rgb + np.array([0.1 * t, 0.0, -0.1 * t])
    tint = p.get("tint", 0.0)
    if tint:
        rgb = rgb + np.array([0.04 * tint, -0.08 * tint, 0.04 * tint])

    rgb = np.clip(rgb, 0, 1)
    gray = _luma(rgb)[..., None]
    sat = p.get("saturation", 0.0)
    if sat:
        rgb = gray + (rgb - gray) * (1 + sat)
    vib = p.get("vibrance", 0.0)
    if vib:
        current = rgb.max(axis=-1, keepdims=True) - rgb.min(axis=-1, keepdims=True)
        rgb = gray + (rgb - gray) * (1 + vib * (1 - current))

    v = p.get("vignette", 0.0)
    if v:
        height, width = rgb.shape[:2]
        yy, xx = np.mgrid[0:height, 0:width].astype(np.float32)
        r2 = ((xx - width / 2) / (width / 2)) ** 2 + ((yy - height / 2) / (height / 2)) ** 2
        rgb = rgb * (1 - 0.6 * v * np.clip(r2 / 2, 0, 1))[..., None]
    return np.clip(rgb, 0, 1)


def _detail(image: Image.Image, p: dict[str, float]) -> Image.Image:
    sharp = p.get("sharpness", 0.0)
    if sharp > 0:
        image = image.filter(ImageFilter.UnsharpMask(radius=2, percent=int(150 * sharp), threshold=2))
    elif sharp < 0:
        image = image.filter(ImageFilter.GaussianBlur(radius=-2 * sharp))
    clarity = p.get("clarity", 0.0)
    if clarity > 0:
        image = image.filter(ImageFilter.UnsharpMask(radius=20, percent=int(60 * clarity), threshold=0))
    elif clarity < 0:
        image = Image.blend(image, image.filter(ImageFilter.GaussianBlur(radius=8)), min(1.0, -clarity))
    return image


def adjust(image: Image.Image, params: dict[str, float]) -> Image.Image:
    p = {name: float(params.get(name) or 0.0) for name in PARAMS}
    alpha = image.getchannel("A") if image.mode == "RGBA" else None
    rgb = np.asarray(image.convert("RGB"), dtype=np.float32) / 255.0
    rgb = _tone(rgb, p)
    result = Image.fromarray((rgb * 255 + 0.5).astype(np.uint8), "RGB")
    result = _detail(result, p)
    if alpha is not None:
        result.putalpha(alpha)
    return result
