"""DashScope 客户端的请求格式与流程（假服务，不代表真实模型效果）。"""

import pytest
from PIL import Image

from app.dashscope import DashScope, ModelConfig, ModelError
from tests.fake_dashscope import EDIT_COLOR, GENERATE_COLOR, FakeDashScope

CONFIG = ModelConfig("https://dashscope.example", "edit-model", "gen-model")


def _client(fake: FakeDashScope, key="test-model-key") -> DashScope:
    return DashScope(CONFIG, key, transport=fake.transport(), poll_interval=0, sleep=lambda _: None)


def test_edit_sends_the_image_inline_and_downloads_results():
    fake = FakeDashScope()
    images = _client(fake).edit(Image.new("RGB", (100, 80), "white"), "换成木纹桌面", count=2, size=(300, 200))
    assert len(images) == 2 and images[0].getpixel((0, 0)) == EDIT_COLOR
    sent = fake.requests[0]
    assert sent["path"] == "/api/v1/services/aigc/multimodal-generation/generation"
    assert sent["body"]["model"] == "edit-model"
    content = sent["body"]["input"]["messages"][0]["content"]
    assert content[0]["image"].startswith("data:image/png;base64,") and content[1] == {"text": "换成木纹桌面"}
    assert sent["body"]["parameters"] == {"n": 2, "watermark": False, "size": "768*512"}
    assert images[0].size == (768, 512)


def test_generate_polls_the_async_task():
    fake = FakeDashScope()
    images = _client(fake).generate("白底运动鞋", 64, 48, count=1, seed=7)
    assert images[0].getpixel((0, 0)) == GENERATE_COLOR
    assert fake.polls == 2
    assert fake.requests[0]["body"]["parameters"] == {"n": 1, "size": "64*48", "watermark": False, "seed": 7}


def test_errors_are_readable():
    fake = FakeDashScope()
    with pytest.raises(ModelError, match="图像模型拒绝了请求：Invalid API-key provided."):
        _client(fake, key="wrong").edit(Image.new("RGB", (10, 10)), "x")
    fake.fail_edit = "输入内容可能包含不适当内容"
    with pytest.raises(ModelError, match="不适当内容"):
        _client(fake).edit(Image.new("RGB", (10, 10)), "x")


def test_missing_key_is_reported_before_any_request(monkeypatch):
    monkeypatch.delenv("DASHSCOPE_API_KEY", raising=False)
    with pytest.raises(ModelError, match="未配置 DASHSCOPE_API_KEY"):
        DashScope(CONFIG, "")
