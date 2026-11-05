"""图片编解码与输出元数据。输出统一为 PNG，保留透明通道。"""

import hashlib
import io
from dataclasses import dataclass

from PIL import Image, ImageOps, UnidentifiedImageError


class BadImage(Exception):
    pass


@dataclass(frozen=True)
class Output:
    key: str
    width: int
    height: int
    image_format: str
    has_alpha: bool
    size_bytes: int
    sha256: str

    def as_json(self) -> dict:
        return {
            "key": self.key,
            "width": self.width,
            "height": self.height,
            "image_format": self.image_format,
            "has_alpha": self.has_alpha,
            "size_bytes": self.size_bytes,
            "sha256": self.sha256,
        }


def decode(data: bytes) -> Image.Image:
    try:
        image = Image.open(io.BytesIO(data))
        image.load()
    except (UnidentifiedImageError, OSError) as exc:
        raise BadImage("输入图片无法解码") from exc
    # 按 EXIF 方向摆正，与浏览器显示一致
    image = ImageOps.exif_transpose(image)
    if image.mode not in ("RGB", "RGBA"):
        image = image.convert("RGBA" if "A" in image.getbands() or image.mode == "P" else "RGB")
    return image


def encode_png(image: Image.Image) -> bytes:
    buffer = io.BytesIO()
    image.save(buffer, format="PNG", optimize=False)
    return buffer.getvalue()


def describe(key: str, image: Image.Image, data: bytes) -> Output:
    return Output(
        key=key,
        width=image.width,
        height=image.height,
        image_format="PNG",
        has_alpha=image.mode == "RGBA" and image.getchannel("A").getextrema()[0] < 255,
        size_bytes=len(data),
        sha256=hashlib.sha256(data).hexdigest(),
    )
