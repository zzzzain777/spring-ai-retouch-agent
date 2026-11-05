import numpy as np
from PIL import Image

from app.ops.adjust import adjust


def _image(mode: str = "RGB") -> Image.Image:
    gradient = np.tile(np.linspace(20, 230, 64, dtype=np.uint8), (32, 1))
    rgb = np.stack([gradient, gradient // 2 + 40, 255 - gradient], axis=-1)
    image = Image.fromarray(rgb, "RGB")
    if mode == "RGBA":
        image.putalpha(Image.fromarray(np.full((32, 64), 128, dtype=np.uint8), "L"))
    return image


def _mean(image: Image.Image) -> float:
    return float(np.asarray(image.convert("RGB"), dtype=np.float32).mean())


def test_zero_params_keep_pixels():
    image = _image()
    assert np.array_equal(np.asarray(adjust(image, {})), np.asarray(image))


def test_brightness_moves_mean_in_the_requested_direction():
    image = _image()
    assert _mean(adjust(image, {"brightness": 0.5})) > _mean(image)
    assert _mean(adjust(image, {"brightness": -0.5})) < _mean(image)


def test_saturation_minus_one_is_gray():
    rgb = np.asarray(adjust(_image(), {"saturation": -1}), dtype=np.int16)
    assert np.abs(rgb[..., 0] - rgb[..., 1]).max() <= 1
    assert np.abs(rgb[..., 1] - rgb[..., 2]).max() <= 1


def test_temperature_warms_red_and_cools_blue():
    base = np.asarray(_image(), dtype=np.float32)
    warm = np.asarray(adjust(_image(), {"temperature": 1}), dtype=np.float32)
    assert warm[..., 0].mean() > base[..., 0].mean()
    assert warm[..., 2].mean() < base[..., 2].mean()


def test_alpha_is_preserved_and_size_unchanged():
    image = _image("RGBA")
    result = adjust(image, {"contrast": 0.4, "sharpness": 0.5, "vignette": 0.8})
    assert result.mode == "RGBA"
    assert result.size == image.size
    assert np.array_equal(np.asarray(result.getchannel("A")), np.asarray(image.getchannel("A")))
