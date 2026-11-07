# project-state

状态用语只用：设计 / 已实现 / 已验证 / 待验收 / 失败。

## 当前阶段：D（最终验收与材料：已完成，待人工复核）

B 阶段已完成（提交至 `8232ede`）。

A 阶段已完成（提交 `85c0c36`、`0ad7240`），下面的 A 阶段条目保留作记录。

- 更新时间：2026-11-07（UTC）
- 上游固定版本：`yuyuanweb/ai-picture-editor@49f6ad8c96740a68b7e6228a47e8e2be6876eb34`
- 本仓库：`zzzzain777/spring-ai-retouch-agent`（公开）。A 阶段只提交文档与本人编写的评测材料，没有 Java 实现代码。
- Java 实现：B 阶段已完成，见下方 B 阶段表；C 阶段进行中，见 C 阶段表。（本节其余条目为 A 阶段记录。）

| 项 | 状态 | 证据 / 说明 |
| --- | --- | --- |
| 读取项目指令 | 已验证 | 本地项目指令文件（不入库） |
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
数据存储：PostgreSQL 与 Redis 都用本机（`docker compose`）。用户 2026-11-05 曾选阿里云 RDS PostgreSQL 免费试用，08:41 因需付费取消。自动化测试用 Testcontainers 临时库。

| 项 | 状态 | 证据 / 说明 |
| --- | --- | --- |
| B1 架构文档 | 设计 | `docs/architecture.md`：组件职责、数据权威、统一工具规格、Agent 主路径、状态机、跨服务任务 ID、图像传输、幂等/超时/重启、主要时序。描述的是待实现结构 |
| B2 构建与基础集成测试 | 已验证 | Spring Boot 4.1.2、Spring AI 2.0.2、Hibernate 7.4.13、Flyway 12.4.0、Testcontainers 2.0.5、AWS SDK 2.56.0。`mvn test`：3 个集成测试通过（真实 PostgreSQL 17、Redis 7、moto S3；Flyway 建 7 张表；`/api/health` 返回 api/database/storage/redis 全 ok）。云端沙箱用 `hub.image.name.prefix=mirror.gcr.io/` 拉镜像（只在沙箱配置，不进仓库） |
| B3 登录与素材 | 已验证 | 注册/登录/登出/当前用户（JWT 放 httpOnly Cookie `session`，SameSite=Lax，与上游契约一致）；未登录与伪造会话返回 401 `{"detail":"未登录或会话已过期"}`；上传 JPEG/PNG/WEBP，校验格式、≤20 MB（413）、最短边 ≥32、≤5000 万像素（422）；素材按所有者过滤，他人素材返回 404；读取时签发短时 URL。`mvn test`：`AuthAndAssetTests` 5 个用例（含签名 URL 下载字节一致）全部通过。限制：会话是无状态 JWT，登出只清 Cookie，令牌在过期前被重放仍有效（与上游相同）；素材库分组接口放到 B4 |
| B4 会话、画布文档、历史、素材库 | 已验证 | `POST/GET/PATCH /api/sessions`、`undo`/`redo`/`history`、`GET /api/assets/library`，语义按上游：建会话生成锁定底图层；切换当前图回到该图上次的文档、修订号 +1；历史最多 20 条，新编辑截断重做分支，撤销/重做恢复当时的修订号；素材库按会话收拢，遮罩与抠图层不展示。改进：所有写操作先 `SELECT … FOR UPDATE` 锁会话行，另有乐观锁版本兜底（并发冲突返回 409）。`SessionTests` 6 个用例通过，含 8 个并发切换请求全部成功、历史序号连续无重复，文档以 jsonb 对象落库 |
| B5 统一工具规格与手动工具接口（文档工具部分） | 已验证 | `ToolRegistry` 是唯一注册表，`ToolSpec` 含名称、说明、参数类型、执行方式、是否需会话、对模型隐藏的参数、超时、重试次数、是否幂等；`ToolParams` 是唯一参数校验入口（类型转换 + Bean Validation，错误为“字段：原因”，跨字段规则报“参数：…”），`ToolService.submit` 供手动接口与 Agent 共用。已迁移 9 个文档工具（裁剪、翻转、透明度、显隐、改文字、图层顺序、缩放、旋转、移动），语义与上游一致（裁剪取整同为四舍六入五成双）。接口：`POST /api/sessions/{id}/tools`（202 `{run, session}`）、`GET /api/runs/{id}`、新增 `GET /api/tools`。工具自身失败记为任务 failed 且不改画布；参数非法 422；未注册工具 404；他人会话 404。`ToolTests` 6 个、`DocumentToolsTest` 4 个通过；全部 24 个测试通过。其余 13 个图像工具在 B6/B9 |
| B6 Python 图像服务、任务队列、SSE | 已验证（调色一个工具） | `image-service/`：本项目独立编写的 FastAPI 服务，只按对象键读写、只往 `runs/{run_id}/{attempt}/` 写输出，校验共享令牌与输出前缀；已实现调色（11 个参数，自己的实现），`uv run pytest` 8 个通过。Java：队列就是 `tool_runs` 表，`FOR UPDATE SKIP LOCKED` 领取并写租约，执行时不持有事务；完成时锁任务行，只接受“仍在运行且 attempt 未变”的结果；登记素材时校验对象键在本任务输出前缀下；租约过期扫描（启动即执行、之后每 30 秒）：幂等且未超次数的放回队列，否则记“执行中断，请重试”；排队任务可直接取消，运行中的只打标记、在下一个检查点结束。进度：每次状态提交后发布到 Redis `run:{id}`；`GET /events/runs/{id}` 先订阅再发库里快照，帧格式与上游一致（`data: {json}`），15 秒心跳，600 秒断开，另每 3 秒对一次库保证终态送达。测试：`ImageToolTests`（真实 Python 进程 + moto S3：排队→Worker→Python→素材→文档与历史→SSE 至少 2 帧且终态 succeeded；租约过期后第 2 次执行成功且输出在 `/2/` 前缀，次数用尽的记失败）、`WorkerFailureTests`（图像服务不可达 → failed「图像服务不可用」，画布不变，他人看 SSE 返回 404）、`QueueCancelTests`（取消排队任务，SSE 只发一帧 canceled）。全部 28 个 Java 测试通过。限制：单进程内 2 个 Worker 线程，未做多实例压测；远程调用不能中途取消；完成事务失败时已写入对象存储的输出会成为孤儿对象（在 `runs/` 前缀下，未做清理） |
| B7 规划、计划校验、确认/取消/重试 | 已验证（真实 DeepSeek 冒烟检查） | `agent/`：`SpringAiPlanner` 用 Spring AI ChatClient 调 OpenAI 兼容接口（默认 DeepSeek），带全部已登记工具的定义与 Schema（`ToolSchemas` 由参数类型生成，与校验同源，隐藏参数不出现），关闭 `ToolCallingAdvisor` 自动注册，工具回调一被调用就抛异常；`PlanValidator` 独立校验工具存在、参数（与手动接口同一入口）、依赖、环（Kahn）、步数 ≤ 8；轮次落库 `agent_turns`（计划 JSON 即断点，`trace` 列记模型名、耗时、token、原样工具调用、被拒原因）。接口 `GET/POST /api/sessions/{id}/messages`、`…/{turnId}/confirm`、`/cancel`、`/retry`，新增 `Idempotency-Key` 请求头与 `…/{turnId}/trace`。并发与恢复：确认/取消/重试/推进都锁轮次行；派发带 `(turn_id, step_id, step_attempt)` 唯一约束；步骤状态以 `tool_runs` 为准对账，启动时与每 30 秒兜底。相对上游的改动：确认时校验修订号（G5）、取消传播到运行中任务（G6）、前一步失败时整轮汇总为 failed 而不是一直 running。测试：`PlanRulesTest` 12、`PlannerNoExecutionTest` 5（含对照：不关自动注册时 Spring AI 会去执行工具）、`ToolSchemasTest` 5、`AgentTests` 13（单步直接执行、多步确认前 0 派发、取消无副作用、修订号过期 409、非法提议只回复不执行、隐藏参数被删、无计划时三种操作 409、规划异常、幂等键、失败阻断与单步重试、越权 404/401、丢失计划更新后对账不重复执行）、`AgentImageTests` 2（图像步骤经真实 Python 服务完成后回调推进下一步）。云端全部 65 个 Java 测试通过。Agent 流程测试的规划模型是脚本替身，只验证 Java 侧逻辑。真实模型：见下一行 `RealPlannerCheck`。限制：两个重复请求真正并发时可能各调一次模型（只落一轮、只执行一次）；当时其余 12 个图像工具尚未登记（B9 已补齐） |
| B7 真实模型检查 | 已验证 | 第一次（`134105d`，用户 Mac）：真实 DeepSeek 请求成功、返回结构化工具调用与 token，但“先上下翻转，然后把透明度改成 60%”只返回翻转一步，未通过。修正：提示词第 2 条要求一次给出全部步骤并说明调用不会立即执行；新增可选追问 `retouch.planner.follow-up-rounds`；检查改为 5 条冒烟用例（1 单步、3 多步、1 无关请求），先写记录再断言。第二次（`0741ae4`，用户 Mac）：追问 0 轮与 1 轮各跑一次，都是 5/5 符合预期，多步指令都停在 queued 且 0 派发；0 轮合计 5562 ms、prompt 11235 token，1 轮合计 7519 ms、prompt 20829 token，结果相同，所以默认保持 0。记录：`docs/evidence/real-planner-check/`。只是冒烟检查，不是评测 |
| B8 选区与遮罩 | 已验证 | Python `POST /v1/ops/mask`（本项目自写：笔刷 3 点以上填多边形、2 点画线、1 点画圆，笔刷宽度按短边比例；点选暂以圆形区域代替，不是分割模型；可与已有遮罩合并；空选区 422「选区为空」），Python 测试 14 个通过。Java：`SelectionStore` 用 Redis `selection:{sessionId}`（TTL 24 小时，值为修订号、遮罩素材 id、标点），修订号不一致或 Redis 异常都视为无选区；`POST/GET/DELETE /api/sessions/{id}/selection` 与 `POST …/selection/prepare`（204，占位）按上游语义：笔刷与已有遮罩合并、点选 `append` 追加标点、修订号过期 409「画布已更新，请重新选择」、空输入 422「请点选或涂抹选区」、无选区时 GET 返回 `null`；遮罩登记为 `mask` 类素材，素材库不展示。规划：画布摘要写明选区状态；计划校验后，`usesMask` 的步骤若没有 `mask_asset_id` 就钉入当前选区。`SelectionTests` 4 个（需要 uv，真实 Python 进程）通过；云端 Java 全量 71 个、Python 14 个通过。用户 Mac 装 uv 后在 `0741ae4` 上补跑 `SelectionTests` 4 个通过。限制：点选不是分割（SAM 在 B9 再评估）；目前登记的工具都不用遮罩，钉入逻辑要等 B9 的区域工具才在真实流程里生效 |
| B9 其余 12 个图像工具 + 文生图、批量接口 | 已实现（图像模型只用本地假服务验证） | 上游 22 个工具全部登记在 `ToolRegistry`（顺序与上游一致，启动时检查齐全）。分工见 `architecture.md` §3.1：作用哪层、用不用选区、提示词、结果进画布还是只进图片墙、修订号加不加都由 Java 决定；Python 新增 9 个接口（抠图、整图编辑、区域编辑、拆层、提升图层、遮罩外接框、投放尺寸、文生图、转格式），图像模型由 Python 调 DashScope、结果下载进自有对象存储。不需要图像模型、本地即可用：`remove_background`（默认四角取色抠底；装可选依赖 rembg 后用 rembg）、`prepare_delivery_sizes`、`adjust_image`，以及 `batch_process` 里的这三种操作。需要图像模型的 9 个：`replace_background`、`expand_canvas`、`upscale_image`、`erase_region`、`replace_region`、`generate_marketing`、`split_layers`、`promote_object_to_layer`、`generate_image`。新增接口 `POST /api/generations`、`POST/GET /api/batches`、`GET /api/batches/{id}`、`GET /api/batches/{id}/export`（zip）。测试：Python 38 个（`fake_dashscope.py` 用 httpx MockTransport 代替 DashScope）；Java `ModelToolTests` 6 个（真实 Python 进程 + moto S3 + 本地假 DashScope `FakeDashScopeServer`：本地工具不发模型请求、投放尺寸修订号不变；换背景请求带转交的密钥与本项目提示词、多张候选只上墙；扩图尺寸与采纳；放大；营销图；区域工具用当前选区并在成功后清空、修订号过期不调模型；拆层两层且不重复、`include_text` 明确报不支持、提升图层改名；Agent 计划钉入选区后执行；文生图候选；批量 2 张 × 2 步 × 2 种格式、详情、zip 4 个文件、素材不存在 404、操作参数非法 422）、`ModelKeyMissingTests` 1 个（不配密钥时模型工具失败并说明原因、画布不变）。云端 Java 全量 78 个、Python 38 个全部通过。**未接真实图像模型**：用户 2026-11-05 要求省钱，不开通 DashScope；假服务返回纯色图，只证明请求格式、密钥转交、结果入库与文档更新这条链路，不能当作模型效果或模型验证。限制：`split_layers` 不支持拆文字层（未接 OCR，`include_text=true` 直接报错）；点选仍是圆形区域，不是分割模型；批量任务按图串行执行；会话导出接口 `POST /api/sessions/{id}/exports` 未做 |
| 工具说明改写 | 已实现 | 发现 `2c5d2af` 里 9 个文档工具的说明与图层参数说明直接沿用了上游原文。本次全部改成自己的措辞（`DocumentTools`、`ImageTools`、`ModelTools`、`LayerTools`、`BatchTool`），给模型的提示词也都是自己写的。保留与上游一致的只有接口契约：工具名、参数名、错误与提示文案（评测对比要用） |
| B10 独立前端与页面走查 | 已验证（云端与用户 Mac 各 15/15） | `web/`：React 18.3.1 + Vite 6.4.5 + TypeScript 5.9.3，本项目独立编写，未使用上游前端的代码或资源。页面：登录注册；修图（上传建会话、按画布文档渲染图层、笔刷与点选选区、撤销重做、对话与计划确认/取消/重试/规划详情、工具面板、图层显隐与顺序、图片墙、历史）；文生图；批量处理（zip 下载）。工具列表与参数表单由 `GET /api/tools` 生成：本次给该接口加了 `parameters`（与交给规划模型的 JSON Schema 同源，`ToolTests` 断言隐藏参数不出现、共 22 个工具），前端不另写参数规则。排队工具用 SSE 显示进度。`npm run build`（tsc 严格模式 + vite build）通过。页面走查 `web/e2e/walkthrough.mjs`：15 步，覆盖文档工具、排队工具与 SSE、撤销重做、选区、去背景与图层、投放尺寸进图片墙、对话多步等待确认→确认执行、取消、无关请求、批量与 zip、未配置图像模型时的失败提示，并检查控制台与 HTTP 错误。云端结果 15/15（`docs/evidence/ui-walkthrough/cloud-fake-planner/`）：Java、Python、PostgreSQL、Redis 是真实运行的，对象存储用 moto；**对话部分的规划模型是本地联调替身（云端连不上 DeepSeek，替身没有提交），只证明前端与 Java 的计划交互，不代表规划能力**；用户 Mac（`34aa219`，Node 22.23.4，本机 Chrome）用真实 DeepSeek 再跑一次：15/15、23.1 秒，4 次规划请求都按预期（两条多步指令停在等待确认、确认后执行、取消后画布不变、无关请求无计划），记录 `docs/evidence/ui-walkthrough/mac-deepseek/`（提交 `8232ede`）。观察：无关请求的 prompt token 为 4532，B8 时同样请求是 2244，原因是登记工具从 10 个增至 22 个、每次请求都带全部工具 Schema；按 DeepSeek 定价每次规划仍不到一分钱，但评测时要记进成本。限制：没有画布上直接拖动/缩放图层的手势（移动、缩放、旋转走工具面板）；没有会话导出按钮（导出接口未做）；只在 Chromium 内核验证过 |
| 推送 B1–B9 | 已验证 | `2117514`（B1–B3，Mac 上 8 个测试通过）、`2c5d2af`（B4–B6，Mac 上 26 个通过、2 个因本机没有 `uv` 跳过；云端 28 个与 Python 8 个全部通过）、`134105d`（B7，Mac 上 61 通过、4 跳过；云端 65 个全部通过）、`0741ae4`（B7 修正 + B8，Mac 上 63 通过、8 跳过；云端 Java 71 个、Python 14 个全部通过）、`51a9086`（B9，用户 Mac 装好 uv 后：Java 78 个全部通过、0 跳过，Python 38 个通过；只调本地假 DashScope，未调真实模型）。前几次跳过的都是需要 Python 图像服务（`uv`）的测试 |
| 阿里云 RDS PostgreSQL 接入 | 不做 | 用户 2026-11-05 08:41 决定：要付费，不用。业务库用本机 PostgreSQL；`application-local.yml.example` 的 `spring.datasource` 段仍可指向任意 PostgreSQL |
| 模型连通性检查独立化 | 已验证 | `server/scripts/model-check.sh` 改为独立小程序，排除数据库、迁移、JPA、Redis 自动配置；本地假服务验证：数据库与 Redis 地址不可达时仍能完成调用 |

## C 阶段进度

口径：同一冻结数据集 `agent-behavior-v1`（MANIFEST 校验），原版与 Java 版同模型（`deepseek-chat`，temperature 0）、同参数配对；含 `llm` 的样例每版至少 3 轮；Mock/占位只用于 `image_exec` 流程判定，不作模型结论。

| 项 | 状态 | 证据 / 说明 |
| --- | --- | --- |
| 真实失败捕获 | 已验证 | 云端对 `51a9086` 跑确定性子集（`image_exec,planner_unavailable`）：17 例中 R06「导出会话素材压缩包」失败（404，接口未实现），报告 `eval/reports/java-51a9086-noplanner-20261105T135400Z/` |
| 修复：会话导出 | 已验证 | Python `POST /v1/ops/render`（拼平画布，不调模型，测试 39 个通过）；Java `POST /api/sessions/{id}/exports` 按上游语义（指定素材必须在图片墙否则 422「素材不在该会话中」、默认导营销/投放图、无则导当前画布拼平图、`manifest.json`、`filename*=UTF-8''`），画布入参构造与 Worker 共用 `Canvases`。新增 `SessionExportTests` 4 个（真实 Python 渲染、zip 内容、422、他人素材 422）；云端 Java 全量 82 个通过 |
| 同集复测 | 已验证 | 修复后同命令同数据集重跑：17/17，报告 `eval/reports/java-exportfix-noplanner-20261105T140823Z/`。修复前后两份报告都保留 |
| 运行器增强：规划轨迹 | 已实现 | `run_agent_eval.py` 在每次 send 落定后取 `…/{turnId}/trace`（只有 Java 版有，原版 404 时静默跳过），token/耗时记进 `results.json` 与报告；只加观测，断言与判定不变，数据集未动（MANIFEST 不含运行器） |
| 配对汇总脚本 | 已实现 | `eval/tools/aggregate.py`：多轮 results.json 合并为两版对照（各轮通过率、按指标合计、两版结论不同的样例、多轮不稳定样例、token 与可选成本折算）；校验各轮 MANIFEST 一致 |
| Mac 配对评测（真实 DeepSeek，各 3 轮） | 已验证 | 用户 Mac，同一冻结集 `agent-behavior-v1`、`deepseek-chat` temperature 0、能力 `llm,image_exec`。原版 `49f6ad8`（经 `planner_proxy.py`，`IMAGE_PROVIDER=mock`，2026-11-05）三轮均为执行 43、通过 33、失败 10、跳过 1；Java 版（Spring AI，本地占位图像接口，2026-11-07）r1 执行 43/通过 42/异常 1、r2 43/38/异常 5、r3 43/43，跳过均为 1，失败 0。Java 的 6 个异常（r1 M02；r2 S05、S06、S08、L01、C02）全是运行器 `ReadTimeout`，对应时段 DeepSeek 接口反复返回 500/超时（服务端日志可见），属环境问题；DeepSeek 恢复后同配置只补跑这 6 例，6/6 通过（`java-deepseek-envretry-20261107T075322Z`，不替代原轮次）。汇总 `eval/reports/paired-c1/`：两版结论不同 14 例（原版三轮失败而 Java 通过：M01、M03、M04、C01、C03、C04、F03、A03；原版失败、Java 含 1 轮环境异常：M02、C02；两版都通过但 Java 有 1 轮环境异常：S05、S06、S08、L01）。硬门断言（三轮合计，通过/总数）：原版 confirm_gate 45/123、no_side_effect 12/36、confirm_flow 18/42、authz_isolation 48/51、regression 81/81；Java 113/113、35/35、38/38、51/51、81/81（异常的 6 个用例轮次不产生断言，故分母较小）。Token：原版 87 次调用 prompt 421,032 / completion 4,418（转发器轨迹）；Java 81 次 367,196 / 4,809（Java 规划轨迹）。未折算费用。原版失败归因见 `eval/reports/c1-upstream-failure-analysis.md`（87 次规划调用 0 次返回多步）。注意：两版运行日期不同（原版 11-05、Java 11-07），DeepSeek 服务端模型映射在此期间是否变化无法确认 |
| C 阶段引入文档流程 | 已实现 | 2026-11-06 用户提供 SDD 候选文档（`sdd-handoff/`，按视频示例“项目指令 → 需求 → 业务规则 → 架构与接口 → 任务拆解 → 测试与审查”组织），在 C 阶段中途接入，**不是项目开始就有**。2026-11-07 在用户 Mac 上按 HEAD `b3acd90` 逐条核对版本、路由（37 个映射）、端口、工具数与分类（文档 9、模型 9、本地图像 3、批量 1）、选区/OCR、R06 数字与 MANIFEST 后合入：本地项目指令文件（合并旧版仍有效的约束，不入库）与 `docs/` 下需求、业务规则、接口契约、架构衔接、部署、开发流程、任务、审查模板。修正：MinIO 镜像不可得改记 moto、C3/C4/C6 真实状态、前后对比视图未实现、会话导出 422 例外、`spring-webflux` 为传递依赖、DeepSeek 响应模型名。`docs/reviews/c-stage-review.md` 保持模板（全部“未运行”）。交付目录 `sdd-handoff/` 本身未提交 |
| 出图质量 | 待验收 | 未接真实图像模型（省钱决定），RUBRIC §4 人工图像量表本轮无法打分；报告如实标注「未测」 |
| pgVector | 不做 | 本阶段未引入向量检索，无真实数据库扩展与 Embedding 验证，不写入简历与技术栈 |

## D 阶段进度

| 项 | 状态 | 证据 / 说明 |
| --- | --- | --- |
| 最终验收：自动化测试 | 已验证 | 2026-11-07 用户 Mac：Java 17 类 82 个全部通过、0 跳过；Python 39 passed；`npm run build` 成功。`docs/evidence/d-acceptance/README.md` |
| 最终验收：真实 DeepSeek 页面走查 | 已验证 | 15/15，16.5 s，重试 0 次，运行期间规划异常 0；`docs/evidence/ui-walkthrough/mac-deepseek-d/`。单步重试由 `AgentTests` 覆盖；运行中任务取消已实现但无专门自动化测试 |
| C 阶段文档收口 | 已实现 | `c-stage-prd.md` 改为实际结果；`docs/reviews/c-stage-review.md` 填入证据与 SHA，人工判断项“待人工复核”，审查人与日期留空 |
| 实现状态与证据索引 | 已实现 | `docs/implementation-state.md`、`docs/evidence-index.md` |
| 简历与面试材料 | 已实现 | `docs/interview/resume-project.md`、`demo-script.md`、`qa.md`；陈述均回指 `claim-ledger.md` |
| 出图质量 | 待验收 | 未接 DashScope（省钱决定），9 个模型工具只验占位链路 |
| 人工复核 | 待验收 | `c-stage-review.md` 审查人签字；材料由用户本人确认能讲清 |

## 环境记录

- 云端沙箱：Java 21.0.12、Maven 3.9.11、Gradle、Python 3.13.18、uv 0.11.32、Node 22.23、Docker 29.9（需手动 `dockerd` 启动）。
- Docker Hub 直连返回 429；`mirror.gcr.io` 可拉取官方镜像；`quay.io`、`dl.min.io` 被拒绝。
- Maven Central 可访问：Spring AI 最新 `2.0.2`，Spring Boot 最新 `4.1.2`。
- DashScope：`dashscope.aliyuncs.com`、`dashscope-intl.aliyuncs.com` 均被拒绝（代理 403）。
- DeepSeek：`api.deepseek.com` 被拒绝（代理 403，2026-11-05 复查）。
- 用户 Mac（macOS arm64，真实 DeepSeek 运行地）：Corretto 21.0.12；Docker 由 OrbStack 提供；uv 0.13.7 与 Node 22.23.4 装在用户目录（`~/.local/bin`、`~/.local/node`），未用 sudo、未改 shell 配置；Maven 经仓库外的 settings 文件走本机 HTTP 代理（系统 SOCKS 代理会让 Maven 卡住）。`minio/minio` 镜像拉不到（Docker Hub 拒绝、`quay.io` 401），实际运行以 `motoserver/moto:5.1.4` 占 7313 端口替代。原版依赖需 Python 3.13（`onnxruntime` 无 3.15 轮子）；rembg 首次使用会下载约 1 GB 的 `bria-rmbg-2.0` 模型。

## 阻断

1. **DeepSeek 密钥**：用户 2026-11-05 决定把密钥写进 Java 项目的 yml。做法是本地未跟踪的 `server/src/main/resources/application-local.yml`（示例见同目录 `.example`），不写进被跟踪的 `application.yml`。云端网络拒绝 `api.deepseek.com`，所以真实模型调用（连通性检查、原版与 Java 版的 LLM 评测）在用户 Mac 上运行。
2. **图像模型**：用户 2026-11-05 决定为省钱暂不接 DashScope（按次收费）。需要图像模型的 9 个工具只用本地假服务验证过链路；以后要真实出图，需要在 Mac 上配置 `DASHSCOPE_API_KEY`（云端网络也拒绝 `dashscope.aliyuncs.com`）。简历与文档不得写成“已接入图像模型”。
3. **推送方式**：云端开发环境的 GitHub 连接账号对本仓库无写权限，每次推送经用户 Mac 的本机 git 完成，需要用户在 Mac 上确认。

## 下一步

1. B9 已推送 `51a9086`。
2. 用户 Mac 已装 uv 0.13.7（用户同意，免费，装在用户目录）。`0741ae4` 补跑：Java 71 个全部通过、0 跳过（含 `SelectionTests` 4、`ImageToolTests` 2、`AgentImageTests` 2），Python 14 个通过。
3. B 阶段各项已完成并推送（`34aa219`、`8232ede`）。C 阶段：会话导出修复与评测工具已推送 `b3acd90`；SDD 文档流程已接入（见 C 阶段表）。
4. C 阶段配对评测已完成（`eval/reports/paired-c1/`）；D 阶段最终验收与材料已完成（见 D 阶段表）。剩余：出图质量未测；两版运行日期不同；`c-stage-review.md` 待人工复核签字。
5. 可选：规划提示词与画布摘要按 22 个工具复查；工具 Schema 瘦身以降低每次规划的 prompt token。（会话导出已在 `b3acd90` 完成。）
