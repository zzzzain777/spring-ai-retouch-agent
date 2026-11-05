# project-state

状态用语只用：设计 / 已实现 / 已验证 / 待验收 / 失败。

## 当前阶段：B（设计并实现 Java 主路径）

A 阶段已完成（提交 `85c0c36`、`0ad7240`），下面的 A 阶段条目保留作记录。

- 更新时间：2026-11-05 08:10（UTC）
- 上游固定版本：`yuyuanweb/ai-picture-editor@49f6ad8c96740a68b7e6228a47e8e2be6876eb34`
- 本仓库：`zzzzain777/spring-ai-retouch-agent`（公开）。A 阶段只提交文档与本人编写的评测材料，没有 Java 实现代码。
- Java 实现：B 阶段进行中，见下方 B 阶段表。评测结果只有原版的确定性部分。

| 项 | 状态 | 证据 / 说明 |
| --- | --- | --- |
| 读取 `CLAUDE.md` | 已验证 | 仓库根目录 `CLAUDE.md` |
| 克隆上游并固定 commit | 已验证 | `git ls-remote origin HEAD` = `49f6ad8…`，见 `upstream-baseline.md` §1 |
| 许可证与复用授权 | 已验证 | 上游无 LICENSE / 授权声明，结论为“无复用授权”，见 §2 |
| 功能、工具、接口、数据模型、Agent 状态机梳理 | 已验证 | 22 个工具、34 个接口，见 §4–§8，均对照源码 |
| 复用方式决定 | 已验证 | 2026-11-05 用户确认：上游无授权，前端与 Python 图像服务独立实现，上游只作行为基线 |
| 上游依赖安装 | 已验证 | 云端沙箱中 `uv sync --all-extras` 成功 |
| 上游中间件 | 已验证 | PostgreSQL 17 与 Redis 7 用 compose 启动并健康；MinIO 镜像不可获取，以 moto S3 作 S3 兼容替代 |
| 上游迁移、pytest、API/Worker 启动 | 已验证 | 用户同意后运行：alembic 成功；pytest 160 passed（64.27s）；像素评测 3/3；API/Worker 在 `IMAGE_PROVIDER=mock` 下启动，health 全 ok。见 `upstream-baseline.md` §11 |
| 原版前端页面截图 | 待验收 | 用户 2026-11-05 选择本阶段不做 |
| 冻结 Agent 行为评测集 v1 | 已实现 | `eval/datasets/agent-behavior-v1/`：44 例、评分口径、两张自绘合成图，`MANIFEST.sha256` 冻结 |
| 原版确定性 Agent 基线 | 已验证 | 执行 17 例全部通过（越权 7、旧流程回归 8、失败 2），报告 `eval/reports/upstream-49f6ad8-noplanner-*/` |
| DeepSeek 规划转发器 | 已实现 | `eval/tools/planner_proxy.py`，只用本地假服务验证了转发、换密钥、记轨迹和无密钥拒绝；尚未连过真实 DeepSeek |
| 原版真实模型基线 | 待验收 | 规划模型改用 DeepSeek。云端网络策略拒绝 `api.deepseek.com`（代理 403），环境未配置 `DEEPSEEK_API_KEY`；27 例记为跳过 |
| Java 工程骨架与模型配置入口 | 已实现 | `server/`：Spring Boot 4.1.2 + Spring AI 2.0.2（OpenAI 兼容接口，默认 DeepSeek，可切 DashScope 兼容模式）；`application.yml` 只有 `${DEEPSEEK_API_KEY:}`、`${DASHSCOPE_API_KEY:}` 入口，真实密钥放被忽略的 `application-local.yml`。云端已验证：`mvn test` 通过；无密钥可启动；本地 yml 中的 key 被读取；请求路径为 `{base-url}/chat/completions` 且不带工具（本地假服务只验证线路）。真实模型调用尚未验证 |
| 真实 DeepSeek 连通性检查 | 已验证 | 2026-11-05 在用户 Mac（Corretto 21.0.12）上用被忽略的 `application-local.yml` 运行 `--retouch.model-check.enabled=true`：退出码 0，reply=OK；请求 `deepseek-chat`，响应返回模型名 `deepseek-flash`（服务端映射，评测记录以返回名为准），promptTokens=18、completionTokens=1、1517 ms。密钥从未进入提交历史 |
| 新 GitHub 仓库与首个提交 | 已验证 | `zzzzain777/spring-ai-retouch-agent@85c0c365477f35b3f59d72e9e40d217f662c08fa`（tree `8fc8e76`），经用户 Mac 本机 git（gh 登录 zzzzain777）推送；云端重新克隆核对 tree 一致 |

## B 阶段进度

规划模型与评测基线：用户 2026-11-05 选定 DeepSeek（`deepseek-chat`）；DashScope 只用于图像模型。
数据存储：用户 2026-11-05 选定业务库用阿里云 RDS PostgreSQL（免费试用实例），Redis 用本机。自动化测试仍用 Testcontainers 临时库，不连 RDS。

| 项 | 状态 | 证据 / 说明 |
| --- | --- | --- |
| B1 架构文档 | 设计 | `docs/architecture.md`：组件职责、数据权威、统一工具规格、Agent 主路径、状态机、跨服务任务 ID、图像传输、幂等/超时/重启、主要时序。描述的是待实现结构 |
| B2 构建与基础集成测试 | 已验证 | Spring Boot 4.1.2、Spring AI 2.0.2、Hibernate 7.4.13、Flyway 12.4.0、Testcontainers 2.0.5、AWS SDK 2.56.0。`mvn test`：3 个集成测试通过（真实 PostgreSQL 17、Redis 7、moto S3；Flyway 建 7 张表；`/api/health` 返回 api/database/storage/redis 全 ok）。云端沙箱用 `hub.image.name.prefix=mirror.gcr.io/` 拉镜像（只在沙箱配置，不进仓库） |
| B3 登录与素材 | 已验证 | 注册/登录/登出/当前用户（JWT 放 httpOnly Cookie `session`，SameSite=Lax，与上游契约一致）；未登录与伪造会话返回 401 `{"detail":"未登录或会话已过期"}`；上传 JPEG/PNG/WEBP，校验格式、≤20 MB（413）、最短边 ≥32、≤5000 万像素（422）；素材按所有者过滤，他人素材返回 404；读取时签发短时 URL。`mvn test`：`AuthAndAssetTests` 5 个用例（含签名 URL 下载字节一致）全部通过。限制：会话是无状态 JWT，登出只清 Cookie，令牌在过期前被重放仍有效（与上游相同）；素材库分组接口放到 B4 |
| B4 会话、画布文档、历史、素材库 | 已验证 | `POST/GET/PATCH /api/sessions`、`undo`/`redo`/`history`、`GET /api/assets/library`，语义按上游：建会话生成锁定底图层；切换当前图回到该图上次的文档、修订号 +1；历史最多 20 条，新编辑截断重做分支，撤销/重做恢复当时的修订号；素材库按会话收拢，遮罩与抠图层不展示。改进：所有写操作先 `SELECT … FOR UPDATE` 锁会话行，另有乐观锁版本兜底（并发冲突返回 409）。`SessionTests` 6 个用例通过，含 8 个并发切换请求全部成功、历史序号连续无重复，文档以 jsonb 对象落库 |
| B5 统一工具规格与手动工具接口（文档工具部分） | 已验证 | `ToolRegistry` 是唯一注册表，`ToolSpec` 含名称、说明、参数类型、执行方式、是否需会话、对模型隐藏的参数、超时、重试次数、是否幂等；`ToolParams` 是唯一参数校验入口（类型转换 + Bean Validation，错误为“字段：原因”，跨字段规则报“参数：…”），`ToolService.submit` 供手动接口与 Agent 共用。已迁移 9 个文档工具（裁剪、翻转、透明度、显隐、改文字、图层顺序、缩放、旋转、移动），语义与上游一致（裁剪取整同为四舍六入五成双）。接口：`POST /api/sessions/{id}/tools`（202 `{run, session}`）、`GET /api/runs/{id}`、新增 `GET /api/tools`。工具自身失败记为任务 failed 且不改画布；参数非法 422；未注册工具 404；他人会话 404。`ToolTests` 6 个、`DocumentToolsTest` 4 个通过；全部 24 个测试通过。其余 13 个图像工具在 B6/B9 |
| B6 Python 图像服务、任务队列、SSE | 已验证（调色一个工具） | `image-service/`：本项目独立编写的 FastAPI 服务，只按对象键读写、只往 `runs/{run_id}/{attempt}/` 写输出，校验共享令牌与输出前缀；已实现调色（11 个参数，自己的实现），`uv run pytest` 8 个通过。Java：队列就是 `tool_runs` 表，`FOR UPDATE SKIP LOCKED` 领取并写租约，执行时不持有事务；完成时锁任务行，只接受“仍在运行且 attempt 未变”的结果；登记素材时校验对象键在本任务输出前缀下；租约过期扫描（启动即执行、之后每 30 秒）：幂等且未超次数的放回队列，否则记“执行中断，请重试”；排队任务可直接取消，运行中的只打标记、在下一个检查点结束。进度：每次状态提交后发布到 Redis `run:{id}`；`GET /events/runs/{id}` 先订阅再发库里快照，帧格式与上游一致（`data: {json}`），15 秒心跳，600 秒断开，另每 3 秒对一次库保证终态送达。测试：`ImageToolTests`（真实 Python 进程 + moto S3：排队→Worker→Python→素材→文档与历史→SSE 至少 2 帧且终态 succeeded；租约过期后第 2 次执行成功且输出在 `/2/` 前缀，次数用尽的记失败）、`WorkerFailureTests`（图像服务不可达 → failed「图像服务不可用」，画布不变，他人看 SSE 返回 404）、`QueueCancelTests`（取消排队任务，SSE 只发一帧 canceled）。全部 28 个 Java 测试通过。限制：单进程内 2 个 Worker 线程，未做多实例压测；远程调用不能中途取消；完成事务失败时已写入对象存储的输出会成为孤儿对象（在 `runs/` 前缀下，未做清理） |
| 阿里云 RDS PostgreSQL 接入 | 设计 | 配置入口已备好（`application-local.yml.example` 的 `spring.datasource` 段、`server/README.md` 步骤），等用户开通实例后在 Mac 上启动验证 Flyway 建表与健康检查 |
| 模型连通性检查独立化 | 已验证 | `server/scripts/model-check.sh` 改为独立小程序，排除数据库、迁移、JPA、Redis 自动配置；本地假服务验证：数据库与 Redis 地址不可达时仍能完成调用 |

## 环境记录

- 云端沙箱：Java 21.0.12、Maven 3.9.11、Gradle、Python 3.13.18、uv 0.11.32、Node 22.23、Docker 29.9（需手动 `dockerd` 启动）。
- Docker Hub 直连返回 429；`mirror.gcr.io` 可拉取官方镜像；`quay.io`、`dl.min.io` 被拒绝。
- Maven Central 可访问：Spring AI 最新 `2.0.2`，Spring Boot 最新 `4.1.2`。
- DashScope：`dashscope.aliyuncs.com`、`dashscope-intl.aliyuncs.com` 均被拒绝（代理 403）。
- DeepSeek：`api.deepseek.com` 被拒绝（代理 403，2026-11-05 复查）。

## 阻断

1. **DeepSeek 密钥**：用户 2026-11-05 决定把密钥写进 Java 项目的 yml。做法是本地未跟踪的 `server/src/main/resources/application-local.yml`（示例见同目录 `.example`），不写进被跟踪的 `application.yml`。云端网络拒绝 `api.deepseek.com`，所以真实模型调用（连通性检查、原版与 Java 版的 LLM 评测）在用户 Mac 上运行。
2. **图像模型**（换背景、扩图、局部消除等真实出图前需要）：放行 `dashscope.aliyuncs.com` 并配置 `DASHSCOPE_API_KEY`。
3. **推送方式**：Claude 的 GitHub 连接账号对本仓库无写权限，每次推送经用户 Mac 的 Remote Control 用本机 git 完成，需要用户在 Mac 上确认。

## 下一步

收到用户的 B 阶段指令后：锁定 Java 21 + Spring Boot 4.1.x + Spring AI 2.0.x 版本组合，产出架构与接口契约（Q3）、状态转换表（Q4）、单一工具规格设计（Q5）、数据模型（Q6）。
