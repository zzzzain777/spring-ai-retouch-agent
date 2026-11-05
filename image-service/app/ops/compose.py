"""局部合成、抠图、补洞与改尺寸。本项目自己的实现。

遮罩一律是 L 模式灰度图（0 未选中，255 选中），尺寸与它所描述的图一致。
"""

import hashlib

import numpy as np
from PIL import Image, ImageChops, ImageFilter


class EmptyMask(Exception):
    """遮罩没有覆盖到任何像素。"""


def alpha_mask(image: Image.Image) -> Image.Image:
    """图层自身的不透明区域：没有选区时，局部编辑就作用在这块区域上。"""
    return image.convert("RGBA").getchannel("A")


def crop_to_layer(canvas_mask: Image.Image, x: float, y: float, width: int, height: int) -> Image.Image:
    """把画布坐标的遮罩切成某个图层自己的坐标（图层左上角在画布 (x, y)，不考虑旋转缩放）。"""
    local = Image.new("L", (width, height), 0)
    local.paste(canvas_mask, (-round(x), -round(y)))
    if local.getbbox() is None:
        raise EmptyMask
    return local


def blend(original: Image.Image, edited: Image.Image, mask: Image.Image, feather: float = 1.5) -> Image.Image:
    """选区内用编辑结果，选区外保留原图；边缘稍作羽化，避免硬边。"""
    base = original.convert("RGBA")
    patch = edited.convert("RGBA")
    if patch.size != base.size:
        patch = patch.resize(base.size, Image.Resampling.LANCZOS)
    soft = mask.resize(base.size, Image.Resampling.NEAREST) if mask.size != base.size else mask
    if feather > 0:
        soft = soft.filter(ImageFilter.GaussianBlur(feather))
    return Image.composite(patch, base, soft)


def cut(image: Image.Image, mask: Image.Image) -> tuple[Image.Image, tuple[int, int, int, int]]:
    """按遮罩抠出物体并裁到外接框，返回抠图与外接框 (left, top, right, bottom)。"""
    rgba = image.convert("RGBA")
    box = mask.getbbox()
    if box is None:
        raise EmptyMask
    rgba.putalpha(ImageChops.multiply(rgba.getchannel("A"), mask))
    return rgba.crop(box), box


def punch(image: Image.Image, canvas_mask: Image.Image, x: float = 0, y: float = 0) -> Image.Image:
    """把遮罩覆盖到的像素变透明。遮罩是画布坐标，(x, y) 是该图层在画布上的原点。"""
    rgba = image.convert("RGBA")
    local = Image.new("L", rgba.size, 0)
    local.paste(canvas_mask, (-round(x), -round(y)))
    rgba.putalpha(ImageChops.multiply(rgba.getchannel("A"), ImageChops.invert(local)))
    return rgba


def grow(mask: Image.Image, pixels: int) -> Image.Image:
    """向外扩几像素，挖洞时不留下物体的一圈边。"""
    if pixels <= 0:
        return mask
    return mask.filter(ImageFilter.MaxFilter(pixels * 2 + 1))


def grow_for(size: tuple[int, int]) -> int:
    return max(6, min(size) // 60)


def fill_hole(image: Image.Image, mask: Image.Image) -> Image.Image:
    """把遮罩处挖掉并用周围像素补上，交给模型修补前先保证画面里不再有原物体。

    装了 OpenCV 时用它的 Telea 修补；否则用逐步缩小半径的高斯模糊从洞的边缘往里扩散颜色。
    """
    rgb = image.convert("RGB")
    hole = np.asarray(mask) > 16
    if not hole.any():
        return image.convert("RGBA")
    try:
        import cv2
    except ImportError:
        filled = _diffuse(rgb, Image.fromarray((hole * 255).astype(np.uint8)))
    else:
        radius = max(3, min(rgb.size) // 90)
        filled = Image.fromarray(cv2.inpaint(np.asarray(rgb), (hole * 255).astype(np.uint8), radius, cv2.INPAINT_TELEA))
    out = filled.convert("RGBA")
    out.putalpha(image.convert("RGBA").getchannel("A") if image.mode == "RGBA" else 255)
    return out


def _diffuse(rgb: Image.Image, hole: Image.Image) -> Image.Image:
    keep = np.asarray(hole) == 0
    pixels = np.asarray(rgb, dtype=np.float64)
    if keep.any():
        mean = pixels[keep].mean(axis=0)
    else:
        mean = np.array([255.0, 255.0, 255.0])
    seeded = pixels.copy()
    seeded[~keep] = mean
    current = Image.fromarray(seeded.astype(np.uint8))
    for radius in (32, 16, 8, 4, 2):
        blurred = current.filter(ImageFilter.GaussianBlur(radius))
        current = Image.composite(blurred, rgb, hole)
    return current


def letterbox(image: Image.Image, width: int, height: int, fill=(255, 255, 255, 255)) -> Image.Image:
    """整张图等比放进目标画幅，不裁切，空出来的地方用 fill 填满。"""
    source = image.convert("RGBA")
    if source.size == (width, height):
        return source
    scale = min(width / source.width, height / source.height)
    w, h = max(1, round(source.width * scale)), max(1, round(source.height * scale))
    placed = source.resize((w, h), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (width, height), fill)
    canvas.alpha_composite(placed, ((width - w) // 2, (height - h) // 2))
    return canvas


def fit(image: Image.Image, width: int, height: int) -> Image.Image:
    """直接缩放到指定像素（模型给的尺寸和要求的投放尺寸略有出入时用）。"""
    if image.size == (width, height):
        return image
    return image.resize((width, height), Image.Resampling.LANCZOS)


def flatten_on_white(image: Image.Image) -> Image.Image:
    """导出 JPG 前铺白底。"""
    rgba = image.convert("RGBA")
    canvas = Image.new("RGB", rgba.size, (255, 255, 255))
    canvas.paste(rgba, mask=rgba.getchannel("A"))
    return canvas


def digest(mask: Image.Image) -> str:
    """同一块选区得到同一个短指纹，用来判断这个物体是否已经提升过。"""
    return hashlib.sha256(np.asarray(mask.convert("L")).tobytes()).hexdigest()[:16]
