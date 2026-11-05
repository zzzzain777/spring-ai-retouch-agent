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

所有接口都只收对象键与参数，输出写到请求里的 `output_prefix`（必须是 `runs/{X-Run-Id}/{X-Attempt}/`，可再带一层 `[a-z0-9-]` 子目录），返回
`{outputs: [{key, name, width, height, image_format, has_alpha, size_bytes, sha256}], meta: {...}}`。
画布类输入（`canvas`）是 Java 给的图层列表（对象键、位置、缩放、旋转、镜像、透明度、显隐、文字），由 `app/ops/render.py` 拼平。

| 路径 | 说明 | 图像模型 |
| --- | --- | --- |
| `GET /healthz` | 存活检查 | — |
| `POST /v1/ops/adjust` | 调色（11 个参数） | — |
| `POST /v1/ops/mask` | 笔刷/点选栅格化成遮罩，可与已有遮罩合并 | — |
| `POST /v1/ops/remove_background` | 抠主体；`meta.method` 记用的方法 | — |
| `POST /v1/ops/delivery` | 按给定尺寸留白缩放出多张 | — |
| `POST /v1/ops/encode` | 转成 PNG 或 JPEG（透明处铺白） | — |
| `POST /v1/ops/mask_box` | 遮罩的外接框与摘要，不出图 | — |
| `POST /v1/ops/model_edit` | 整图编辑（换背景、扩图、放大、营销图），可指定尺寸与输出适配 | 编辑 |
| `POST /v1/ops/edit_region` | 改一层：按“选区 ∩ 图层”只贴回选区内的结果 | 编辑 |
| `POST /v1/ops/split` | 拆成背景 + 主体，背景先本地补洞再让模型修 | 编辑 |
| `POST /v1/ops/promote` | 按选区切出物体，原位置补洞再修 | 编辑 |
| `POST /v1/ops/generate` | 文生图（异步任务，每 3 秒轮询，最多 300 秒） | 文生图 |

请求头：`X-Internal-Token`（共享令牌）、`X-Run-Id`、`X-Attempt`（Java 任务 id 与第几次执行）；调模型的接口另收 `X-DashScope-Key`
（Java 转交，不落盘、不写日志；没有时读环境变量 `DASHSCOPE_API_KEY`），模型地址与模型名在请求体 `model` 里。
错误返回 `{"error": "可展示的原因"}`：400 前缀或键不合法、401 令牌不对、404 输入不存在、409 遮罩与画布尺寸不一致、
422 图片无法解码或选区/主体为空、502 模型服务出错、503 未配置图像模型密钥。

抠图：`MATTING_PROVIDER=auto`（默认，装了 rembg 用 rembg，否则四角取色）、`rembg`、`corner`。rembg 是可选依赖：`uv sync --extra matting`。

## 测试

```bash
uv run pytest
```

测试不访问网络：DashScope 由 `tests/fake_dashscope.py`（httpx MockTransport）代替，只验证请求格式与结果处理，不代表模型效果。
