# AI 修图 Agent：对外接口契约索引

以下路由已从当前 Java 控制器核对。此文件用于 Agent 开发前定位 API，**不是生成的 OpenAPI 全字段定义**。修改字段、状态码或错误语义时，以 `server/src/main/java/` 的 Controller/DTO、测试和 `web/src/api.ts` 同步核实；评测脚本依赖既有 HTTP 行为。

| 能力 | 当前路由 | 关键约束 |
| --- | --- | --- |
| 认证 | `POST /api/auth/register`、`/login`、`/logout`；`GET /api/auth/me` | Cookie 登录态；越权不暴露其他用户资源。 |
| 素材 | `POST /api/assets`、`GET /api/assets`、`GET /api/assets/{assetId}`、`GET /api/assets/library` | Java 校验类型、尺寸、所有者；返回短期对象 URL。 |
| 会话/历史 | `POST/GET /api/sessions`、`GET/PATCH /api/sessions/{id}`、`POST …/undo`、`POST …/redo`、`GET …/history` | 写操作带修订号和历史语义；编辑改变画布时记录版本。 |
| 选区 | `POST/GET/DELETE /api/sessions/{id}/selection`、`POST …/selection/prepare` | 选区绑定当前修订号；过期返回 409；`prepare` 当前是占位 204。 |
| Agent | `POST/GET /api/sessions/{id}/messages`、`POST …/{turnId}/confirm`、`/cancel`、`/retry`、`GET …/{turnId}/trace` | 多步未确认不得执行；操作按用户隔离；轨迹有模型与工具提议。 |
| 工具 | `GET /api/tools`、`POST /api/sessions/{id}/tools`、`GET /api/runs/{runId}` | 手动与 Agent 共用 ToolRegistry；前端从 `/api/tools` 读参数 Schema。 |
| 进度 | `GET /events/runs/{runId}` | SSE 推送任务进度；状态以数据库为准；他人不得订阅。 |
| 生成 | `POST /api/generations` | 图像模型未配置时明确失败，不返回伪造候选。 |
| 批量 | `POST/GET /api/batches`、`GET /api/batches/{runId}`、`GET …/{runId}/export` | 每张图的子任务与 ZIP 导出；本地图像操作可运行，模型操作需真实密钥。 |
| 会话导出 | `POST /api/sessions/{id}/exports` | C 阶段补齐（`b3acd90`）；指定素材须属于该会话，否则 422；返回 ZIP 和 `manifest.json`。 |
| 健康 | `GET /api/health` | 汇报 API、数据库、对象存储与 Redis 连通状态。 |

## Agent 协议要点

`POST /messages` 创建一轮 Agent 任务。计划中每步有工具名、参数、依赖、状态和可选任务 ID。单步可直接执行，多步初始为 `queued`。`confirm` 前任何修图工具派发都属于严重错误。`trace` 用于回归和面试证据，但不得返回/记录真实密钥。

Java 与 Python 之间是**内部接口**，见 `image-service/README.md`：`/v1/ops/*` 只接收对象键和参数，头部使用 `X-Internal-Token`、`X-Run-Id`、`X-Attempt`；输出键限于任务前缀。前端不得直接调用 Python 图像服务。

## 改动检查

若修改上述契约，应检查：Java 控制器与业务测试、`web/src/api.ts` 与页面、`eval/run_agent_eval.py`、冻结数据集是否仍可运行。旧数据集不能因新实现而改写；确需新判定时创建 v2 并单列报告。
