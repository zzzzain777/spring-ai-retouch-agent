import pytest
from PIL import Image

from app.dashscope import edit_size
from app.ops import compose, matte, render


def _square(size=(40, 40), color=(255, 255, 255, 255), box=None, inner=(0, 0, 255, 255)):
    image = Image.new("RGBA", size, color)
    if box:
        image.paste(Image.new("RGBA", (box[2] - box[0], box[3] - box[1]), inner), box[:2])
    return image


def test_flatten_places_scaled_mirrored_and_rotated_layers_around_their_centre():
    red = Image.new("RGBA", (10, 10), (255, 0, 0, 255))
    layer = render.Layer(kind="image", width=10, height=10, image=red, x=5, y=5)
    canvas = render.flatten(40, 40, [layer])
    assert canvas.getpixel((10, 10))[:3] == (255, 0, 0)
    assert canvas.getpixel((30, 30))[:3] == (255, 255, 255)

    doubled = render.Layer(kind="image", width=10, height=10, image=red, x=15, y=15, scale_x=2, scale_y=2)
    canvas = render.flatten(40, 40, [doubled])
    # 绕中心 (20, 20) 放大到 20×20，覆盖 10..30
    assert canvas.getpixel((11, 11))[:3] == (255, 0, 0) and canvas.getpixel((8, 8))[:3] == (255, 255, 255)

    half = Image.new("RGBA", (20, 10), (0, 0, 0, 0))
    half.paste(Image.new("RGBA", (10, 10), (0, 255, 0, 255)), (0, 0))
    flipped = render.Layer(kind="image", width=20, height=10, image=half, x=0, y=0, scale_x=-1)
    assert render.flatten(20, 10, [flipped], background=render.CLEAR).getpixel((15, 5))[:3] == (0, 255, 0)

    hidden = render.Layer(kind="image", width=10, height=10, image=red, visible=False)
    assert render.flatten(10, 10, [hidden]).getpixel((5, 5))[:3] == (255, 255, 255)


def test_text_layers_are_drawn_unless_excluded():
    text = render.Layer(kind="text", width=60, height=30, text="SALE", font_size=20, fill="#000000")
    with_text = render.flatten(60, 30, [text])
    without = render.flatten(60, 30, [text], include_text=False)
    assert with_text.getchannel("R").getextrema()[0] < 100
    assert without.getchannel("R").getextrema() == (255, 255)


def test_blend_only_changes_the_masked_area():
    original = Image.new("RGBA", (40, 40), (255, 255, 255, 255))
    edited = Image.new("RGBA", (20, 20), (0, 0, 0, 255))
    mask = Image.new("L", (40, 40), 0)
    mask.paste(255, (10, 10, 30, 30))
    out = compose.blend(original, edited, mask)
    assert out.getpixel((20, 20))[:3] == (0, 0, 0)
    assert out.getpixel((2, 2))[:3] == (255, 255, 255)


def test_cut_punch_and_crop_to_layer():
    image = _square(box=(10, 10, 20, 20))
    mask = Image.new("L", (40, 40), 0)
    mask.paste(255, (10, 10, 20, 20))
    piece, box = compose.cut(image, mask)
    assert box == (10, 10, 20, 20) and piece.size == (10, 10)

    punched = compose.punch(image, mask)
    assert punched.getpixel((15, 15))[3] == 0 and punched.getpixel((30, 30))[3] == 255

    local = compose.crop_to_layer(mask, 5, 5, 20, 20)
    assert local.getpixel((10, 10)) == 255 and local.getpixel((2, 2)) == 0
    with pytest.raises(compose.EmptyMask):
        compose.crop_to_layer(mask, 30, 30, 5, 5)


def test_fill_hole_removes_the_object_colour():
    image = _square(box=(15, 15, 25, 25), inner=(0, 0, 255, 255))
    mask = Image.new("L", (40, 40), 0)
    mask.paste(255, (13, 13, 27, 27))
    filled = compose.fill_hole(image, mask)
    r, g, b, _ = filled.getpixel((20, 20))
    assert b > 200 and r > 200 and g > 200


def test_letterbox_keeps_the_whole_image_without_cropping():
    wide = Image.new("RGBA", (200, 100), (0, 0, 0, 255))
    boxed = compose.letterbox(wide, 100, 100)
    assert boxed.size == (100, 100)
    assert boxed.getpixel((50, 50))[:3] == (0, 0, 0)
    assert boxed.getpixel((50, 5))[:3] == (255, 255, 255)


def test_corner_key_makes_a_flat_background_transparent(monkeypatch):
    monkeypatch.setenv("MATTING_PROVIDER", "corner")
    image = _square(box=(15, 15, 25, 25))
    subject, method = matte.remove_background(image)
    assert method == "corner"
    assert subject.getpixel((2, 2))[3] == 0 and subject.getpixel((20, 20))[3] == 255


def test_digest_is_stable_for_the_same_mask():
    mask = Image.new("L", (10, 10), 0)
    mask.paste(255, (2, 2, 5, 5))
    assert compose.digest(mask) == compose.digest(mask.copy())
    other = mask.copy()
    other.putpixel((8, 8), 255)
    assert compose.digest(mask) != compose.digest(other)


def test_edit_size_stays_inside_the_model_limits():
    assert edit_size(100, 100) == (512, 512)
    assert edit_size(4000, 2000) == (2048, 1024)
    w, h = edit_size(3000, 400)
    assert w <= 2048 and h >= 512 or w == 2048
