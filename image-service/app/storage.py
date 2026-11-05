"""对象存储读写。Python 只按 Java 给的键读输入，并只往本次任务的输出前缀下写结果。"""

from functools import lru_cache

import boto3
from botocore.client import BaseClient
from botocore.config import Config
from botocore.exceptions import ClientError

from app.config import settings


class ObjectMissing(Exception):
    pass


@lru_cache
def client() -> BaseClient:
    s = settings()
    return boto3.client(
        "s3",
        endpoint_url=s.s3_endpoint,
        region_name=s.s3_region,
        aws_access_key_id=s.s3_access_key,
        aws_secret_access_key=s.s3_secret_key,
        config=Config(s3={"addressing_style": "path"}, retries={"max_attempts": 2}),
    )


def get(key: str) -> bytes:
    try:
        return client().get_object(Bucket=settings().s3_bucket, Key=key)["Body"].read()
    except ClientError as exc:
        if exc.response.get("Error", {}).get("Code") in {"NoSuchKey", "404"}:
            raise ObjectMissing(key) from exc
        raise


def put(key: str, data: bytes, content_type: str) -> None:
    client().put_object(Bucket=settings().s3_bucket, Key=key, Body=data, ContentType=content_type)
