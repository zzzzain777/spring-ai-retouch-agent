# image-service

无状态的 Python 图像计算服务（本项目独立实现）。Java 服务是唯一调用方：请求里只有对象键和参数，
Python 从对象存储读输入、把结果写到 `runs/{run_id}/{attempt}/` 下，只返回输出对象的元数据。
它不持有业务状态，不做用户鉴权，不决定执行顺序。

## 运行

```bash
uv sync
IMAGE_SERVICE_TOKEN=dev-internal-token uv run python -m app.main   # 127.0.0.1:7303，只在本机监听
```

环境变量：`IMAGE_SERVICE_TOKEN`（与 Java 的 `retouch.image-service.token` 一致）、`S3_ENDPOINT`、`S3_BUCKET`、
`S3_ACCESS_KEY`、`S3_SECRET_KEY`、`S3_REGION`，默认值对应仓库根目录的 `docker-compose.yml`。

## 接口

| 路径 | 说明 |
| --- | --- |
| `GET /healthz` | 存活检查 |
| `POST /v1/ops/adjust` | 调色：`{input_key, output_prefix, params}`，返回 `{outputs: [{key, width, height, image_format, has_alpha, size_bytes, sha256}]}` |

请求头：`X-Internal-Token`（共享令牌）、`X-Run-Id`、`X-Attempt`（Java 任务 id 与第几次执行）。
错误返回 `{"error": "可展示的原因"}`：400 前缀或键不合法、401 令牌不对、404 输入不存在、422 图片无法解码。

## 测试

```bash
uv run pytest
```
