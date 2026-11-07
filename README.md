# spring-ai-retouch-agent

用 Java 21、Spring Boot 4.1.x 和 Spring AI 2.0.x 实现的 AI 修图 Agent 后端。规划模型用 DeepSeek；Python 只负责图像计算和 DashScope 图像接口调用。

> 当前状态：A–C 阶段完成，D 阶段（最终验收与材料）已完成，**未上线**，只在本地运行与演示。上游 22 个工具全部迁移；Agent 规划用真实 DeepSeek；Java 82 个测试、Python 39 个测试、页面走查 15/15。与原版在同一冻结集上做了真实 DeepSeek 配对评测（各 3 轮），见 [eval/reports/paired-c1/](eval/reports/paired-c1/)。**图像模型（DashScope）未接入**：需要它的 9 个工具只用本地假服务验证过调用链路，出图质量未测。状态与证据以 [docs/implementation-state.md](docs/implementation-state.md)、[docs/evidence-index.md](docs/evidence-index.md) 为准。

## 组成

| 目录 | 内容 |
| --- | --- |
| `server/` | Java 21 + Spring Boot 4.1.2 + Spring AI 2.0.2：唯一对外 API、登录与权限、会话与画布文档、统一工具规格、Agent 规划/计划校验/确认/执行、任务队列与 SSE |
| `image-service/` | Python（FastAPI）图像服务：只按 Java 给的对象键做像素计算和 DashScope 图像接口调用，不持有业务状态 |
| `web/` | React + Vite + TypeScript 前端：画布、选区、工具面板、对话与计划确认、文生图、批量处理 |
| `eval/` | 冻结的 Agent 行为评测集与运行器 |

## 本地运行

按顺序启动：中间件 → 图像服务 → Java 服务 → 前端，然后检查 `GET http://127.0.0.1:7302/api/health`（api、database、storage、redis 均为 ok）。

```bash
docker compose up -d postgres redis                                              # PostgreSQL 7311、Redis 7312
docker run -d --name retouch-moto-s3 -p 127.0.0.1:7313:5000 motoserver/moto:5.1.4  # S3 兼容存储 7313（compose 里的 MinIO 镜像目前拉取不到，实际一直用 moto）
(cd image-service && uv sync && uv run python -m app.main) &                     # Python 图像服务 7303，只供 Java 调用
(cd server && ./mvnw spring-boot:run) &                                          # Java 服务 7302，唯一对外 API；规划模型密钥见 server/README.md
(cd web && npm ci && npm run dev)                                                # 前端 http://127.0.0.1:7301
```

- 组件职责：Java 持有 API、登录与权限、会话与画布文档、计划与任务状态，并决定每一步能否执行；Python 图像服务无状态，只按 Java 给的对象键读写图片；PostgreSQL 存业务数据与任务队列（`tool_runs`），Redis 存选区与进度通知，S3 兼容存储放图片字节。
- 环境：数据库用本机 Docker 里的 PostgreSQL，**没有使用阿里云 RDS**；没有任何线上部署。默认的 JWT 密钥、内部服务令牌和存储账号都是开发值。详见 [docs/deployment.md](docs/deployment.md)。

## 参考来源与复用范围

- 功能设计参考 [yuyuanweb/ai-picture-editor](https://github.com/yuyuanweb/ai-picture-editor)，研究基线固定在 commit `49f6ad8c96740a68b7e6228a47e8e2be6876eb34`。
- 该项目没有声明开源许可证，因此本仓库**不包含**它的任何源码、前端、Prompt 文本、图片或其他资源，只参考它的功能和接口行为。本仓库中的代码与文档均为独立编写。
- 上游的功能、架构、工具与接口清单见 [docs/upstream-baseline.md](docs/upstream-baseline.md)。

## 相对上游的改造

- Java 是对外 API、Agent 规划与计划校验、执行调度、统一工具规格、状态持久化和权限控制的权威服务（已实现，见 [docs/architecture.md](docs/architecture.md)）。
- 规划阶段由模型生成结构化计划，不让 Spring AI 自动调用有副作用的修图工具；计划经服务端校验、多步计划由用户确认后，再由 Java 显式调度执行（已实现并有测试）。
- 按“指标、冻结数据集、执行轨迹、运行监测”四层评测，用同一份冻结数据集和真实模型对比上游与本项目（数据集已冻结，Java 版对比尚未进行）。

## 文档

| 文件 | 内容 |
| --- | --- |
| [AGENTS.md](AGENTS.md) | 所有编码助手共用的项目目标、架构边界与证据规则 |
| [CLAUDE.md](CLAUDE.md) | Claude 的工作顺序、阶段与提交纪律、评测规则与待解决问题表 |
| [docs/研发流程与架构.md](docs/研发流程与架构.md) | C 阶段接入的规范文档入口：需求、业务规则、接口契约、开发流程、任务与审查 |
| [docs/upstream-baseline.md](docs/upstream-baseline.md) | 上游基线记录（版本、许可核查、功能、工具、接口、Agent 状态机） |
| [docs/implementation-state.md](docs/implementation-state.md) | 逐模块：已实现 / 已验证 / 未实现，各带证据 |
| [docs/evidence-index.md](docs/evidence-index.md) | 全部证据索引：测试、评测报告、走查截图、真实模型检查、提交 |
| [docs/interview/](docs/interview/) | 简历项目条目、5 分钟演示脚本、面试问答 |
| [docs/project-state.md](docs/project-state.md) | 各阶段状态、命令、环境、证据与阻断 |
| [docs/claim-ledger.md](docs/claim-ledger.md) | 简历陈述与代码、测试、评测证据的对照 |
| [docs/architecture.md](docs/architecture.md) | 组件职责、数据权威、22 个工具的落位、状态机与时序 |
| [server/README.md](server/README.md) | Java 后端：模型密钥配置、连通性检查、构建 |
| [image-service/README.md](image-service/README.md) | Python 图像服务的接口与测试 |
| [web/README.md](web/README.md) | 前端运行方法与页面走查脚本 |
| [eval/README.md](eval/README.md) | 四层评测、冻结数据集 agent-behavior-v1、运行方法与 DeepSeek 转发器 |

## 密钥

DeepSeek 与 DashScope 的密钥只通过环境变量 `DEEPSEEK_API_KEY`、`DASHSCOPE_API_KEY`，或本地不提交的 `.env`、`application-local.yml` 提供，不写进任何被跟踪的文件。
