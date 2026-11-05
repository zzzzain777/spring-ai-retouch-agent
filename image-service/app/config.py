"""配置只来自环境变量；默认值与仓库根目录 docker-compose.yml 的本地开发环境一致。"""

import os
from dataclasses import dataclass
from functools import lru_cache


@dataclass(frozen=True)
class Settings:
    internal_token: str
    s3_endpoint: str
    s3_region: str
    s3_bucket: str
    s3_access_key: str
    s3_secret_key: str


@lru_cache
def settings() -> Settings:
    return Settings(
        internal_token=os.environ.get("IMAGE_SERVICE_TOKEN", "dev-internal-token"),
        s3_endpoint=os.environ.get("S3_ENDPOINT", "http://127.0.0.1:7313"),
        s3_region=os.environ.get("S3_REGION", "us-east-1"),
        s3_bucket=os.environ.get("S3_BUCKET", "retouch"),
        s3_access_key=os.environ.get("S3_ACCESS_KEY", "retouch"),
        s3_secret_key=os.environ.get("S3_SECRET_KEY", "retouch-secret"),
    )
