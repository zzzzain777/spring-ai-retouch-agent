"""新增图像接口：画布合成、去背景、整图编辑、局部编辑、拆层、提升图层、投放尺寸、文生图、编码。

调图像模型的接口接的是 tests/fake_dashscope.py 的假服务，只验证请求与流程，不代表真实模型效果。
"""

import io

import pytest
from fastapi.testclient import TestClient
from PIL import Image

import app.main as main
from app import storage
from app.dashscope import DashScope
from tests.fake_dashscope import EDIT_COLOR, FakeDashScope
from tests.test_api import RUN, _headers, s3  # noqa: F401

MODEL = {"base_url": "https://dashscope.example", "edit_model": "edit-model", "generate_model": "gen-model"}
PREFIX = f"runs/{RUN}/1/"


@pytest.fixture
def fake(monkeypatch):
    fake = FakeDashScope()
    monkeypatch.setattr(main, "DashScope", lambda config, key: DashScope(
        config, key, transport=fake.transport(), poll_interval=0, sleep=lambda _: None))
    monkeypatch.setenv("MATTING_PROVIDER", "corner")
    return fake


def _put(key, image):
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    storage.put(key, buffer.getvalue(), "image/png")


def _get(key):
    return Image.open(io.BytesIO(storage.get(key))).convert("RGBA")


def _product():
    """白底上一块蓝色方块，40×40。"""
    image = Image.new("RGBA", (40, 40), (255, 255, 255, 255))
    image.paste(Image.new("RGBA", (12, 12), (0, 0, 255, 255)), (14, 14))
    return image


def _canvas(key, **extra):
    layer = {"kind": "image", "key": key, "width": 40, "height": 40}
    return {"width": 40, "height": 40, "layers": [layer], **extra}


def _model_headers():
    return {**_headers(), "X-DashScope-Key": "test-model-key"}


def test_remove_background_uses_the_corner_key_without_a_model(s3, fake):
    _put("users/u1/p.png", _product())
    client = TestClient(main.app)
    response = client.post("/v1/ops/remove_background", json={"output_prefix": PREFIX, "input_key": "users/u1/p.png"},
                           headers=_headers())
    assert response.status_code == 200
    body = response.json()
    assert body["meta"] == {"method": "corner"}
    (output,) = body["outputs"]
    assert output["name"] == "subject" and output["has_alpha"] is True
    subject = _get(output["key"])
    assert subject.getpixel((1, 1))[3] == 0 and subject.getpixel((20, 20))[3] == 255
    assert fake.requests == []


def test_model_edit_flattens_the_canvas_and_fits_the_result(s3, fake):
    _put("users/u1/p.png", _product())
    client = TestClient(main.app)
    body = {"output_prefix": PREFIX, "source": {"canvas": _canvas("users/u1/p.png")}, "prompt": "背景换成木桌",
            "count": 2, "width": 40, "height": 50, "fit_width": 40, "fit_height": 50, "model": MODEL}
    response = client.post("/v1/ops/model_edit", json=body, headers=_model_headers())
    assert response.status_code == 200, response.text
    outputs = response.json()["outputs"]
    assert [o["name"] for o in outputs] == ["edited-1", "edited-2"]
    assert (outputs[0]["width"], outputs[0]["height"]) == (40, 50)
    sent = fake.requests[0]["body"]
    assert sent["parameters"]["size"] == "512*640"
    assert sent["input"]["messages"][0]["content"][1] == {"text": "背景换成木桌"}


def test_model_calls_need_the_forwarded_key(s3, fake, monkeypatch):
    monkeypatch.delenv("DASHSCOPE_API_KEY", raising=False)
    _put("users/u1/p.png", _product())
    client = TestClient(main.app)
    body = {"output_prefix": PREFIX, "source": {"key": "users/u1/p.png"}, "prompt": "x", "model": MODEL}
    response = client.post("/v1/ops/model_edit", json=body, headers=_headers())
    assert response.status_code == 503
    assert response.json() == {"error": "未配置 DASHSCOPE_API_KEY，图像模型不可用"}


def test_model_rejection_is_passed_on(s3, fake):
    fake.fail_edit = "输入内容可能包含不适当内容"
    _put("users/u1/p.png", _product())
    client = TestClient(main.app)
    body = {"output_prefix": PREFIX, "source": {"key": "users/u1/p.png"}, "prompt": "x", "model": MODEL}
    response = client.post("/v1/ops/model_edit", json=body, headers=_model_headers())
    assert response.status_code == 502
    assert "不适当内容" in response.json()["error"]


def test_edit_region_only_changes_the_selected_part_of_the_layer(s3, fake):
    _put("users/u1/p.png", _product())
    mask = Image.new("RGBA", (40, 40), (0, 0, 0, 0))
    mask.paste(Image.new("RGBA", (10, 10), (64, 140, 255, 255)), (0, 0))
    _put("users/u1/mask.png", mask)
    client = TestClient(main.app)
    body = {"output_prefix": PREFIX, "input_key": "users/u1/p.png", "layer": {"x": 0, "y": 0, "width": 40, "height": 40},
            "canvas_width": 40, "canvas_height": 40, "mask_key": "users/u1/mask.png", "prompt": "去掉", "model": MODEL}
    response = client.post("/v1/ops/edit_region", json=body, headers=_model_headers())
    assert response.status_code == 200, response.text
    result = _get(response.json()["outputs"][0]["key"])
    assert result.getpixel((3, 3)) == EDIT_COLOR
    assert result.getpixel((20, 20))[:3] == (0, 0, 255)

    # 选区在图层外：报错，不调模型
    calls = len(fake.requests)
    outside = dict(body, layer={"x": 30, "y": 30, "width": 40, "height": 40})
    response = client.post("/v1/ops/edit_region", json=outside, headers=_model_headers())
    assert response.status_code == 422 and response.json() == {"error": "选区没有覆盖到该图层"}
    assert len(fake.requests) == calls

    # 画幅变了：旧选区失效
    resized = dict(body, canvas_width=50)
    response = client.post("/v1/ops/edit_region", json=resized, headers=_model_headers())
    assert response.status_code == 409 and response.json() == {"error": "画幅已变，选区失效，请重新选择"}


def test_split_returns_background_and_subject(s3, fake):
    _put("users/u1/p.png", _product())
    client = TestClient(main.app)
    body = {"output_prefix": PREFIX, "canvas": _canvas("users/u1/p.png"), "prompt": "修补", "model": MODEL}
    response = client.post("/v1/ops/split", json=body, headers=_model_headers())
    assert response.status_code == 200, response.text
    data = response.json()
    assert [o["name"] for o in data["outputs"]] == ["background", "subject"]
    assert len(data["meta"]["subject_digest"]) == 16
    background = _get(data["outputs"][0]["key"])
    # 原来主体的位置被修补（假模型把洞里染成固定颜色），远处保持原样
    assert background.getpixel((20, 20)) == EDIT_COLOR
    assert background.getpixel((1, 1))[:3] == (255, 255, 255)
    # 发给模型的图里，主体已经被挖掉补平，不再是蓝色
    import base64

    sent = fake.requests[0]["body"]["input"]["messages"][0]["content"][0]["image"].split(",", 1)[1]
    prepared = Image.open(io.BytesIO(base64.b64decode(sent))).convert("RGB")
    assert prepared.getpixel((20, 20))[2] > 200 and prepared.getpixel((20, 20))[0] > 200


def test_promote_cuts_the_object_and_patches_or_punches_layers(s3, fake):
    _put("users/u1/p.png", _product())
    mask = Image.new("RGBA", (40, 40), (0, 0, 0, 0))
    mask.paste(Image.new("RGBA", (12, 12), (64, 140, 255, 255)), (14, 14))
    _put("users/u1/mask.png", mask)
    client = TestClient(main.app)
    body = {"output_prefix": PREFIX, "canvas": _canvas("users/u1/p.png", include_text=False),
            "mask_key": "users/u1/mask.png", "prompt": "修补", "model": MODEL}
    data = client.post("/v1/ops/promote", json=body, headers=_model_headers()).json()
    assert [o["name"] for o in data["outputs"]] == ["object", "background"]
    assert {k: data["meta"][k] for k in ("x", "y", "width", "height")} == {"x": 14, "y": 14, "width": 12, "height": 12}
    assert _get(data["outputs"][0]["key"]).getpixel((5, 5))[:3] == (0, 0, 255)

    layered = dict(body, layers=[{"id": "background", "key": "users/u1/p.png", "background": True},
                                 {"id": "subject", "key": "users/u1/p.png"}])
    data = client.post("/v1/ops/promote", json=layered, headers=_model_headers()).json()
    assert [o["name"] for o in data["outputs"]] == ["object", "layer-background", "layer-subject"]
    assert _get(data["outputs"][2]["key"]).getpixel((20, 20))[3] == 0


def test_mask_box_reports_the_bounding_box_and_digest(s3, fake):
    mask = Image.new("RGBA", (40, 40), (0, 0, 0, 0))
    mask.paste(Image.new("RGBA", (5, 6), (64, 140, 255, 255)), (3, 4))
    _put("users/u1/mask.png", mask)
    client = TestClient(main.app)
    body = {"output_prefix": PREFIX, "mask_key": "users/u1/mask.png", "width": 40, "height": 40}
    data = client.post("/v1/ops/mask_box", json=body, headers=_headers()).json()
    assert data["outputs"] == [] and data["meta"]["box"] == [3, 4, 8, 10] and len(data["meta"]["digest"]) == 16
    stale = client.post("/v1/ops/mask_box", json=dict(body, width=41), headers=_headers())
    assert stale.status_code == 409


def test_delivery_letterboxes_each_size_without_a_model(s3, fake):
    _put("users/u1/p.png", _product())
    client = TestClient(main.app)
    body = {"output_prefix": PREFIX, "source": {"canvas": _canvas("users/u1/p.png")},
            "sizes": [{"width": 108, "height": 108}, {"width": 108, "height": 192}]}
    outputs = client.post("/v1/ops/delivery", json=body, headers=_headers()).json()["outputs"]
    assert [(o["name"], o["width"], o["height"]) for o in outputs] == [
        ("delivery-108x108", 108, 108), ("delivery-108x192", 108, 192)]
    assert fake.requests == []


def test_generate_and_encode(s3, fake):
    client = TestClient(main.app)
    body = {"output_prefix": PREFIX, "prompt": "白底运动鞋", "width": 64, "height": 64, "count": 1, "model": MODEL}
    response = client.post("/v1/ops/generate", json=body, headers=_model_headers())
    assert response.status_code == 200, response.text
    (output,) = response.json()["outputs"]
    assert output["name"] == "generated-1"

    sub = f"{PREFIX}item-1/"
    encoded = client.post("/v1/ops/encode", json={"output_prefix": sub, "input_key": output["key"], "name": "export-1",
                                                  "image_format": "JPEG"}, headers=_headers()).json()["outputs"][0]
    assert encoded["key"] == f"{sub}export-1.jpg" and encoded["image_format"] == "JPEG"


def test_sub_prefix_must_stay_inside_the_run(s3, fake):
    client = TestClient(main.app)
    body = {"output_prefix": f"{PREFIX}../x/", "input_key": "users/u1/in.png", "name": "a"}
    assert client.post("/v1/ops/encode", json=body, headers=_headers()).status_code == 400
    body = dict(body, output_prefix=f"{PREFIX}a/b/")
    assert client.post("/v1/ops/encode", json=body, headers=_headers()).status_code == 400
