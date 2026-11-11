# spring-ai-retouch-agent

自然语言驱动的 AI 修图 Agent：用户说一句话，模型给出工具调用提议，服务端校验、必要时等用户确认，再由 Java 按依赖顺序执行；也可以直接用工具面板手动修图。后端 Java 21 + Spring Boot 4.1.x + Spring AI 2.0.x，前端 React，图像计算由独立的 Python 服务承担。

> 状态：本地可运行、可演示，**未上线**。22 个修图工具、Agent 规划与确认门、任务队列与进度推送、规范检索与合规检查均已实现；Java **89 个测试**、Python 39 个测试、前端页面走查 15/15 全部通过。**图像生成/编辑模型（DashScope）未接入**，依赖它的 9 个工具只验证了调用链路，出图质量未测。逐模块状态见 [docs/implementation-state.md](docs/implementation-state.md)，证据见 [docs/evidence-index.md](docs/evidence-index.md)。

## 能做什么

- 上传图片建会话，画布按图层文档渲染，支持撤销、重做与编辑历史。
- 一句话修图：单步计划直接执行，多步计划先展示、等用户确认，可取消，失败步骤可单独重试。
- 22 个工具：9 个画布文档工具即时生效；调色、去背景、投放尺寸由本地图像服务完成；9 个依赖图像模型的工具已接好调用链路。
- 笔刷与点选选区、图层显隐与顺序、图片墙、批量处理与 ZIP 导出、会话导出。
- 图像任务异步执行，进度通过 SSE 实时推送到页面。
- 规范检索与合规检查：平台/类目规范按条款切块入库（pgvector）；按**本轮目标 + 计划里的工具**检索适用条款，再由可配置规则表校验修图计划，通过 `POST /api/compliance/check` 提供。

## 设计要点

- **模型只提议，不执行**：关闭 Spring AI 的自动工具调用，并给规划用的工具装上禁止实际调用的回调；任何修图动作都由 Java 在校验与确认之后显式调度。
- **计划校验**：工具是否存在、参数（与手动接口同一套校验入口）、依赖关系、环检测、步数上限 8；多步计划在用户确认前派发数为 0。
- **一份工具规格三处复用**：手动接口、交给模型的工具 Schema、前端参数表单都来自同一个 `ToolRegistry`，不存在两套参数规则。
- **任务权威在数据库**：`tool_runs` 表即队列，`FOR UPDATE SKIP LOCKED` 领取并写租约，租约过期可恢复，输出前缀按尝试次数隔离，迟到结果直接丢弃。
- **状态一致性**：确认时校验画布修订号，过期返回 409；取消会传播到运行中的任务；选区绑定修订号存在 Redis，过期即视为无选区。
- **权限隔离**：会话、素材、计划、任务与进度流全部按用户隔离，访问他人资源返回 404。
- **合规是独立校验，不塞进提示词**：检索决定这次该查哪些条款，条款进入 `ComplianceChecker` 做成对工具计划的硬校验（规则表配置在 `application.yml`），主对话流程不变；检索不到规范或没有匹配规则时不放行，返回条款出处供人工复核。

## 组成

| 目录 | 内容 |
| --- | --- |
| `server/` | Java 21 + Spring Boot 4.1.2 + Spring AI 2.0.2：唯一对外 API、登录与权限、会话与画布文档、统一工具规格、Agent 规划与计划校验、确认与执行调度、任务队列与 SSE、规范检索与合规检查 |
| `image-service/` | Python（FastAPI）图像服务：按 Java 给定的对象键做像素计算与图像模型接口调用，不持有业务状态 |
| `web/` | React 18 + Vite + TypeScript 前端：画布、选区、工具面板、对话与计划确认、文生图、批量处理 |
| `eval/` | 冻结的 Agent 行为评测集与黑盒运行器 |

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
- 环境：数据库用本机 Docker 里的 PostgreSQL，**没有使用阿里云 RDS**，也没有任何线上部署。默认的 JWT 密钥、内部服务令牌与存储账号都是开发值。详见 [docs/deployment.md](docs/deployment.md)。

## 行为评测

评测按“指标、冻结数据集、执行轨迹、运行监测”四层建设，数据集 `agent-behavior-v1` 共 44 例，在任何实现代码之前冻结并用 sha256 锁定；运行器只走 HTTP 黑盒接口，缺少能力的样例记为跳过，不用 Mock 顶替。

真实 DeepSeek 下跑了三轮，硬性指标全部满分：确认门 113/113、零副作用 35/35、确认流程 38/38、权限隔离 51/51、旧流程回归 81/81。评测本身也驱动了修复：确定性子集最初发现会话导出接口缺失（17 例通过 16），补齐后同口径复测 17/17。报告见 [eval/reports/paired-c1/](eval/reports/paired-c1/)，方法与运行方式见 [eval/README.md](eval/README.md)。

## 文档

| 文件 | 内容 |
| --- | --- |
| [docs/architecture.md](docs/architecture.md) | 组件职责、数据权威、22 个工具的落位、状态机与时序 |
| [docs/研发流程与架构.md](docs/研发流程与架构.md) | 规范文档入口：需求、业务规则、接口契约、开发流程、任务与审查 |
| [docs/implementation-state.md](docs/implementation-state.md) | 逐模块：已实现 / 已验证 / 未实现，各带证据 |
| [docs/evidence-index.md](docs/evidence-index.md) | 全部证据索引：测试、评测报告、走查截图、真实模型检查、提交 |
| [docs/knowledge-rag.md](docs/knowledge-rag.md) | 规范检索与合规检查：语料、表、入库幂等、两档检索参数、规则表、接口、边界与测试 |
| [docs/interview/](docs/interview/) | 项目经历条目、5 分钟演示脚本、面试问答 |
| [docs/project-state.md](docs/project-state.md) | 各阶段状态、命令、环境、证据与阻断 |
| [docs/claim-ledger.md](docs/claim-ledger.md) | 每条对外陈述与代码、测试、评测证据的对照 |
| [server/README.md](server/README.md) | Java 后端：模型密钥配置、连通性检查、构建 |
| [image-service/README.md](image-service/README.md) | Python 图像服务的接口与测试 |
| [web/README.md](web/README.md) | 前端运行方法与页面走查脚本 |
| [eval/README.md](eval/README.md) | 四层评测、冻结数据集与运行方法 |

## 密钥

DeepSeek 与 DashScope 的密钥只通过环境变量 `DEEPSEEK_API_KEY`、`DASHSCOPE_API_KEY`，或本地不提交的 `server/src/main/resources/application-local.yml` 提供（示例见同目录 `.example`），不写进任何被跟踪的文件。

## 功能对标与独立实现范围

本项目的功能范围对标了一个公开的开源图片编辑 Agent 项目（Python 实现，仓库未声明开源许可证）。本仓库**不包含**它的任何源码、前端、Prompt 文本或资源，代码与文档均为独立编写；行为评测中的对照数据来自在本地按其公开版本运行所得。对照基线的版本与功能清单见 [docs/upstream-baseline.md](docs/upstream-baseline.md)。
