# 架构衔接说明：视频示例到本项目

仓库现有 `docs/architecture.md` 是详细的架构权威文档。本文件只说明采用视频所示结构时要怎样映射，不能覆盖原文。

```text
浏览器 React/Vite/TypeScript
     │ HTTP + SSE
     ▼
Java 21 / Spring Boot 4.1.2 / Spring AI 2.0.2（WebMVC）
     ├─ 认证、素材、会话、画布文档、历史
     ├─ Spring AI ChatClient → DeepSeek：取得工具调用提议
     ├─ ToolRegistry + PlanValidator → 确认门 → Java 步骤调度
     ├─ PostgreSQL 17：业务状态、计划、工具任务队列
     ├─ Redis：选区缓存、任务进度通知
     └─ Worker → Python FastAPI 图像服务 → S3/MinIO 图片字节
```

与研究基线的差异：上游 FastAPI/LangChain/LangGraph 用 `plan → verify → END` 形成计划，业务服务/Worker 推进执行；本项目由 Spring AI 取得提议，再由 Java 服务进行校验、持久化、确认和调度。Python 只保留图像计算与可选图像模型接口。Java 规划模型默认 DeepSeek；当前不使用 LangChain、LangGraph、RAG 或 pgVector。服务是 Spring WebMVC（Servlet），本项目代码不使用 WebFlux/Reactor 编程模型；`spring-webflux` 7.0.10 与 `reactor-core` 只作为 `spring-ai-starter-model-openai` → `spring-boot-starter-webclient` 的传递依赖出现在类路径上（2026-11-07 `mvnw dependency:tree` 核对），不是本项目的技术选型。

## 对照上游的功能与技术迁移

| 上游固定版本的结构/能力 | 本项目对应实现 | 当前差异或验收边界 |
| --- | --- | --- |
| React 19 + react-konva 画布 | `web/` 的 React 18 + TypeScript/Vite 画布 | 当前没有 `react-konva`，也没有画布上直接拖动/缩放图层的手势；按现状演示。 |
| FastAPI 路由、业务服务、SQLAlchemy | `server/` 的 Spring WebMVC API、Java 业务服务、Flyway/PostgreSQL | Java 是唯一对外业务 API；沿用评测所需 HTTP/SSE 行为。 |
| LangChain 规划模型 + LangGraph `plan → verify → END` | Spring AI ChatClient 取 tool calls 提议，Java `PlanValidator` 与 `AgentService` 校验并推进计划 | 这是先规划、再确认和执行的工作流；执行状态机不依赖 LangGraph。 |
| ARQ/Redis 异步图像任务与 Pub/Sub | PostgreSQL `tool_runs` 队列 + Java Worker 租约，Redis 只通知，SSE 对外推送 | 队列权威转到数据库；重启恢复、取消、迟到结果处理见现有架构文档。 |
| Python 图像处理和模型 Provider | `image-service/` 无状态 FastAPI 图像服务 | 保留必要的像素计算；真实 DashScope 图像质量未验收。 |
| 统一工具注册表、自然语言与手动共用工具 | Java `ToolRegistry` 及 `/api/tools` Schema，前端读 Schema 生成表单 | 现有 Java 注册数为 22；数量与上游宣传文案分开按源码核对。 |
| SAM 点选、OCR 文字拆层 | 现有笔刷与圆形点选；主体/背景拆层 | SAM 与 OCR 没有实现，不能在演示或简历中声称已有。 |
| PostgreSQL、Redis、MinIO | 同类本地中间件，业务数据由 Java 持有 | 当前是本地 Compose 环境，没有购买 RDS，也没有 pgVector/RAG。 |

## 模块与代码位置

| 视频示例职责 | 本项目目录 | 当前落位 |
| --- | --- | --- |
| 前端页面与 API 客户端 | `web/src/components/`、`web/src/api.ts` | React 画布、聊天、工具、创作、批量；Schema 表单读 `/api/tools`。 |
| 后端 API/业务/数据 | `server/src/main/java/io/github/zzzzain777/retouch/` | `auth`、`asset`、`session`、`selection`、`agent`、`tool` 等包。 |
| Agent 编排 | `server/.../agent/` | `SpringAiPlanner`、`PlanValidator`、`AgentService`；规划阶段零副作用。 |
| 图像算法与模型接口 | `image-service/app/` | Python FastAPI；不保存业务状态。 |
| 数据库迁移 | `server/src/main/resources/db/migration/` | Flyway 管理 schema。 |
| 集成与评测 | `server/src/test/`、`image-service/tests/`、`web/e2e/`、`eval/` | 单元/集成、页面走查、冻结行为评测。 |
| 本地部署 | 根目录 `docker-compose.yml` 与各服务 README | 目前是本地运行与演示，不是生产部署。 |

具体数据权威、22 个工具的执行位置、状态机、超时/重试与跨服务时序继续以现有 `docs/architecture.md` 为准。架构变更时在该文档记录真实改动，不创建第二套互相矛盾的设计。
