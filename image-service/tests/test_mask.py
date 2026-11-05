import io

import numpy as np
from PIL import Image

from app.ops import mask as masks


def _alpha(image: Image.Image) -> np.ndarray:
    return np.asarray(image) > 0


def test_short_stroke_covers_a_band_around_the_path():
    mask = masks.strokes((200, 100), [[(0.2, 0.5), (0.8, 0.5)]], radius=0.1)
    selected = _alpha(mask)
    assert selected[50, 100] and selected[50, 40] and selected[50, 159]
    assert not selected[5, 100] and not selected[50, 5]


def test_closed_loop_fills_the_inside():
    loop = [(0.2, 0.2), (0.8, 0.2), (0.8, 0.8), (0.2, 0.8)]
    selected = _alpha(masks.strokes((100, 100), [loop], radius=0.02))
    assert selected[50, 50]
    assert not selected[2, 2]


def test_points_select_a_region_around_each_point():
    selected = _alpha(masks.points((100, 100), [(0.25, 0.25)]))
    assert selected[25, 25] and not selected[90, 90]


def test_union_and_overlay_round_trip():
    left = masks.strokes((64, 64), [[(0.1, 0.1)]], radius=0.1)
    right = masks.strokes((64, 64), [[(0.9, 0.9)]], radius=0.1)
    merged = masks.union(left, right)
    png = io.BytesIO()
    masks.overlay(merged).save(png, format="PNG")
    back = _alpha(masks.to_luma(png.getvalue(), (64, 64)))
    assert back[6, 6] and back[57, 57] and not back[32, 32]
