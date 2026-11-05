import io
import os

import boto3
import pytest
from fastapi.testclient import TestClient
from moto import mock_aws
from PIL import Image

os.environ.setdefault("IMAGE_SERVICE_TOKEN", "test-token")
os.environ.setdefault("S3_ENDPOINT", "https://s3.amazonaws.com")

from app import storage  # noqa: E402
from app.config import settings  # noqa: E402
from app.main import app  # noqa: E402

RUN = "5b6f8d4e-0000-4000-8000-000000000001"


@pytest.fixture
def s3():
    settings.cache_clear()
    storage.client.cache_clear()
    with mock_aws():
        os.environ["S3_ENDPOINT"] = "https://s3.amazonaws.com"
        settings.cache_clear()
        storage.client.cache_clear()
        boto3.client("s3", region_name="us-east-1").create_bucket(Bucket=settings().s3_bucket)
        buffer = io.BytesIO()
        Image.new("RGB", (40, 30), (100, 120, 140)).save(buffer, format="PNG")
        storage.put("users/u1/in.png", buffer.getvalue(), "image/png")
        yield
    storage.client.cache_clear()


def _headers(token="test-token", attempt="1"):
    return {"X-Internal-Token": token, "X-Run-Id": RUN, "X-Attempt": attempt}


def test_adjust_writes_output_under_the_run_prefix(s3):
    client = TestClient(app)
    body = {"input_key": "users/u1/in.png", "output_prefix": f"runs/{RUN}/1/", "params": {"brightness": 0.3}}
    response = client.post("/v1/ops/adjust", json=body, headers=_headers())
    assert response.status_code == 200
    (output,) = response.json()["outputs"]
    assert output["key"] == f"runs/{RUN}/1/adjusted.png"
    assert (output["width"], output["height"], output["image_format"], output["has_alpha"]) == (40, 30, "PNG", False)
    stored = storage.get(output["key"])
    assert len(stored) == output["size_bytes"]
    assert Image.open(io.BytesIO(stored)).getpixel((0, 0))[0] > 100


def test_rejects_wrong_token_and_foreign_prefix(s3):
    client = TestClient(app)
    body = {"input_key": "users/u1/in.png", "output_prefix": f"runs/{RUN}/1/", "params": {}}
    assert client.post("/v1/ops/adjust", json=body, headers=_headers(token="nope")).status_code == 401
    other = dict(body, output_prefix="users/u2/")
    response = client.post("/v1/ops/adjust", json=other, headers=_headers())
    assert response.status_code == 400
    assert response.json() == {"error": "输出前缀与任务不符"}


def test_missing_or_broken_input_is_a_readable_error(s3):
    client = TestClient(app)
    missing = {"input_key": "users/u1/none.png", "output_prefix": f"runs/{RUN}/1/", "params": {}}
    response = client.post("/v1/ops/adjust", json=missing, headers=_headers())
    assert response.status_code == 404
    assert response.json() == {"error": "输入图片不存在"}
    storage.put("users/u1/broken.png", b"not an image", "image/png")
    broken = dict(missing, input_key="users/u1/broken.png")
    assert client.post("/v1/ops/adjust", json=broken, headers=_headers()).json() == {"error": "输入图片无法解码"}
