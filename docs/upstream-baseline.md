# A 阶段：上游项目基线记录

> 记录日期：2026-11-05（UTC）
> 记录人：Claude（按 `CLAUDE.md` A 阶段执行）
> 性质：对上游仓库的**只读**研究记录。本文只描述上游代码的事实与行为，不包含上游源码。所有结论以下列 commit 为准，不以上游 README 为准。

## 1. 来源与版本

| 项 | 值 |
| --- | --- |
| 仓库 | <https://github.com/yuyuanweb/ai-picture-editor> |
| 默认分支 | `main`（远端只有这一个分支，无 tag） |
| 固定 commit | `49f6ad8c96740a68b7e6228a47e8e2be6876eb34` |
| commit 时间 / 作者 | 2026-09-16 16:39:51 +0800 / `pine`，提交说明 `doc: README.md` |
| 历史 | 33 个提交，2026-08-26 至 2026-09-16 |
| 跟踪文件 | 210 个；后端 Python 约 10.1k 行（含测试），前端 `src/` 约 8.0k 行 |
| 获取方式 | `git clone` 到 Claude 云端沙箱的临时目录，用 `git ls-remote origin HEAD` 核对远端 HEAD 与本地一致 |

README 中的克隆命令写的是 `yuyuanweb/ai-retouch-agent`，与实际仓库名 `ai-picture-editor` 不一致；`pyproject.toml` 的项目名也是 `ai-retouch-agent-backend`，compose 项目名为 `ai-retouch-agent`。

## 2. 许可证与复用授权核查

| 检查项 | 结果 |
| --- | --- |
| 根目录或子目录 `LICENSE` / `COPYING` / `NOTICE` | 不存在 |
| `frontend/package.json`、`backend/pyproject.toml` 的 license 字段 | 未声明 |
| 全仓库搜索 license / copyright / MIT / Apache / GPL / 版权 / 授权 / 许可 / 开源 / 商用 | 无任何授权声明 |
| README 定位 | “教学项目”，属于「编程导航」付费会员课程；README 把“完整的项目源码”列为会员权益之一 |
| GitHub 仓库元数据 `license` | 本会话无法调用该仓库的 GitHub API（未关联仓库，代理拒绝）；`CLAUDE.md` 记录 2026-11-04 为 `null`，本次在仓库内容中未发现相反证据 |

**结论**：上游没有给出任何开源许可或复用授权。按 GitHub 的[许可证说明](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/licensing-a-repository)，无许可证即默认保留全部权利，他人可在 GitHub 上查看和 Fork，但不能复制、修改或再分发。以下不构成法律意见，仅作为本项目的执行规则：

1. 新的公开仓库**只提交本项目原创的代码、文档和自制素材**，不复制上游的前端、Python 服务、Prompt 文本、测试图片、品牌图标或其他源文件。
2. 上游只作为**行为基线和研究参考**：可以描述它的功能、接口语义和状态机，用同口径数据集在本地或沙箱运行它做对比测量，但运行用的上游代码不进入新仓库。
3. 若要沿用具体代码（尤其是前端），需先取得作者（yuyuanweb / 编程导航）的书面授权，并在仓库中保留授权记录。
4. README 与简历写明“参考 yuyuanweb/ai-picture-editor 的功能设计，代码为独立实现”，不写“基于上游二次开发”。

## 3. 技术栈（以代码为准）

| 层 | 实际使用 |
| --- | --- |
| 后端 | Python ≥3.13、FastAPI、SQLAlchemy async + asyncpg、Alembic、pydantic-settings、PyJWT、bcrypt、httpx、boto3 |
| Agent | `langgraph`、`langchain-core`、`langchain-openai`（`ChatOpenAI` 连 DashScope OpenAI 兼容接口）。`langgraph-checkpoint-postgres` 在依赖里声明了，但代码没有使用 checkpointer |
| 异步任务 | ARQ（Redis）Worker，`max_jobs=4`、`job_timeout=300s`、`keep_result=3600s` |
| 实时进度 | Redis Pub/Sub，频道 `run:{run_id}`，经 `/events/runs/{id}` 以 SSE 推给前端 |
| 存储 | PostgreSQL 17（业务数据，JSONB 存图层文档与计划）、Redis 7（队列、事件、选区）、MinIO（图片字节，预签名 URL 有效期 900s） |
| 本地图像算法 | Pillow（调色、letterbox、遮罩合成）、rembg（抠图；另用 rembg 的 `sam` 量化 ONNX 会话做点选分割）、RapidOCR（可选文字层）、OpenCV |
| 远程模型 | DashScope：规划 `qwen-plus`，文生图 `qwen-image-3.0-pro`，图像编辑 `qwen-image-edit-max` |
| 前端 | React 19、react-konva / Konva 10、TanStack Query 5、zustand 5、react-router 7、Tailwind 4、Vite 8、TypeScript 6 |
| 部署 | 多阶段 Dockerfile（前端构建产物由 FastAPI 同源托管），compose `deploy` profile 启动 app 与 worker |

## 4. 功能清单（以代码为准）

前端路由：`/` 落地页、`/auth` 登录注册、`/create` 文生图、`/candidates/:runId` 候选选图、`/editor[/:sessionId]` 编辑器、`/marketing[/:sessionId]` 营销图、`/batch[/:runId]` 批量处理。

| # | 能力 | 代码中的实现要点 |
| --- | --- | --- |
| F1 | 账号 | 用户名密码注册登录；JWT（HS256）写入 httpOnly Cookie `session`，SameSite=Lax |
| F2 | 素材上传与素材库 | JPEG/PNG/WEBP，≤20MB、≤5000 万像素；按会话分组的素材库 |
| F3 | 文生图 | 默认 4 张候选（1–6），5 种比例，支持负向提示词与最多 3 张参考图；DashScope 异步任务 + 3s 轮询，最长 300s |
| F4 | 编辑会话与画布 | 会话持有图层文档（JSONB）与修订号；图片墙保留所有候选；画布缩放平移、前后对比、裁剪框 |
| F5 | 线性撤销/重做 | `edit_history` 最多保留 20 条，新操作截断重做分支 |
| F6 | 22 个注册工具 | 见第 5 节。界面手动调用与 Agent 规划共用 `SPECS` 和同一个 pydantic 参数校验 |
| F7 | 智能选区 | 点选（SAM，embedding 在 API 进程内 LRU 缓存 4 张）、笔刷涂抹；选区以遮罩素材 + Redis 记录保存（TTL 24h），绑定会话修订号 |
| F8 | 局部消除 / 替换 | DashScope 编辑整层，再按遮罩合成回原图，只改选区内 |
| F9 | 拆层与提升图层 | rembg 分离主体 + DashScope 修补背景；可选 OCR 拆文字层；选区物体提升为独立图层 |
| F10 | 营销图与投放尺寸 | 4 类营销图（商品主图/场景/模特/海报）只进图片墙；投放尺寸为本地 letterbox，不调用模型 |
| F11 | 批量处理 | 最多 20 张图 × 6 种操作，输出 PNG/JPG，打包 zip 导出 |
| F12 | 自然语言修图 Agent | 规划 → 校验 → 单步直接执行 / 多步待确认 → 按依赖推进，支持确认、取消、单步重试（第 7 节） |
| F13 | 导出 | 会话素材打包 zip |
| F14 | 健康检查 | `/api/health` 返回 `api`、`database`、`storage` 三项；**不探测 Redis** |

## 5. 工具注册表（`backend/app/tools/__init__.py` 的 `SPECS`，共 22 项）

README 写 21 种，代码实际注册 22 项。“执行”列：`队列` 表示 `queued=True`，经 ARQ Worker 异步执行；`同步` 表示在 API 请求内直接执行。`needs_approval` 字段在全部 22 项中都是默认值 `False`。

| # | 工具名 | 中文名 | 执行 | 需会话 | 对模型隐藏的参数 | 主要参数（约束） | 实际执行方 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `generate_image` | 生成图片 | 队列 | 否 | `seed`, `reference_asset_ids` | prompt 1–1500，ratio，count 1–6（默认 4），negative_prompt | DashScope 文生图（异步任务） |
| 2 | `replace_background` | 换背景 | 队列 | 是 | `negative_prompt` | prompt 1–500，count 1–4 | DashScope 图像编辑 |
| 3 | `expand_canvas` | 扩图 | 队列 | 是 | — | ratio，prompt | DashScope 图像编辑（指定目标尺寸） |
| 4 | `upscale_image` | 超分 | 队列 | 是 | — | scale 2–4 | DashScope 图像编辑按放大尺寸重绘，边长被限制在 512–2048，**不是专用超分模型** |
| 5 | `remove_background` | 去背景 | 队列 | 是 | — | layer_id | 本地 rembg（`corner` 模式用四角取色兜底） |
| 6 | `adjust_image` | 调色 | 队列 | 是 | — | layer_id + 11 个参数（-1–1，晕影 0–1） | 本地 Pillow |
| 7 | `erase_region` | 局部消除 | 队列 | 是 | `mask_asset_id`, `revision` | layer_id，prompt | DashScope 编辑 + 本地遮罩合成 |
| 8 | `replace_region` | 局部替换 | 队列 | 是 | `mask_asset_id`, `revision` | layer_id，prompt（必填） | 同上 |
| 9 | `split_layers` | 拆层 | 队列 | 是 | — | include_text | rembg + DashScope 修补背景 + 可选 RapidOCR |
| 10 | `promote_object_to_layer` | 提升为图层 | 队列 | 是 | `mask_asset_id`, `revision` | name | 选区裁切 + DashScope 修补原位置 |
| 11 | `generate_marketing` | 营销图 | 队列 | 是 | — | kind ∈ product/scene/model/poster，caption，count 1–4，ratio | DashScope 图像编辑，结果只进图片墙 |
| 12 | `prepare_delivery_sizes` | 投放尺寸 | 同步 | 是 | — | ratios（默认 1:1、4:5、9:16），prompt | 本地 letterbox；`prompt` 参数被声明但执行时未使用 |
| 13 | `batch_process` | 批量处理 | 队列 | 否 | — | asset_ids 1–20，operations（6 种），formats | 组合 #2–#6、#12 的逻辑 |
| 14 | `crop_canvas` | 裁剪 | 同步 | 是 | — | ratio 或归一化 rect 二选一 | 只改图层文档 |
| 15 | `flip_layer` | 翻转 | 同步 | 是 | — | layer_id，direction | 只改图层文档 |
| 16 | `set_layer_opacity` | 透明度 | 同步 | 是 | — | opacity 0–1 | 只改图层文档 |
| 17 | `set_layer_visible` | 显隐 | 同步 | 是 | — | visible | 只改图层文档 |
| 18 | `set_layer_text` | 改文字 | 同步 | 是 | — | text ≤500，font_size 8–400，fill（#hex） | 只改图层文档 |
| 19 | `reorder_layer` | 图层顺序 | 同步 | 是 | — | place ∈ top/bottom/up/down | 只改图层文档 |
| 20 | `scale_layer` | 缩放 | 同步 | 是 | `x`, `y` | factor 或 scale_x/scale_y（0–8） | 只改图层文档 |
| 21 | `rotate_layer` | 旋转 | 同步 | 是 | — | angle（相对）或 rotation（绝对），-360–360 | 只改图层文档 |
| 22 | `move_layer` | 移动 | 同步 | 是 | — | x/y 或 dx/dy | 只改图层文档 |

分类小结：依赖远程 DashScope 的 9 项（#1–4、#7–11，拆层用它修补背景），纯本地像素 3 项（#5、#6、#12），只改文档 9 项（#14–22），组合 1 项（#13），合计 22 项。局部消除、扩图、超分、拆层修补都依赖远程图像模型，不能称作“Python 本地图像模型”。

## 6. HTTP 与 SSE 接口（34 个）

| 分组 | 方法与路径 | 说明 |
| --- | --- | --- |
| 健康 | `GET /api/health` | `{api, database, storage}` |
| 认证 | `POST /api/auth/register`、`POST /api/auth/login`、`POST /api/auth/logout`、`GET /api/auth/me` | Cookie 会话 |
| 素材 | `POST /api/assets`（multipart）、`GET /api/assets`、`GET /api/assets/library`、`GET /api/assets/{id}` | 返回的 URL 每次读取重新签发 |
| 文生图 | `POST /api/generations` → 202 `RunOut` | 内部提交 `generate_image` |
| 任务 | `GET /api/runs/{id}` | 状态、进度、阶段、候选图 |
| SSE | `GET /events/runs/{id}` | 不在 `/api` 下，便于反代关缓冲；先订阅再发快照帧，15s 心跳，任务终态或 600s 后断开 |
| 会话 | `POST /api/sessions`、`GET /api/sessions`、`GET/PATCH /api/sessions/{id}` | PATCH 可改标题或切换当前图 |
| 选区 | `POST /api/sessions/{id}/selection/prepare`、`POST/GET/DELETE /api/sessions/{id}/selection` | 修订号不一致返回 409 |
| 手动工具 | `POST /api/sessions/{id}/tools` → 202 `{run, session}` | 与 Agent 共用 `tools.submit` |
| 历史 | `POST /api/sessions/{id}/undo`、`POST .../redo`、`GET .../history` | 线性撤销 |
| 导出 | `POST /api/sessions/{id}/exports` | zip |
| Agent | `GET/POST /api/sessions/{id}/messages`、`POST .../messages/{turn}/confirm`、`.../cancel`、`.../retry` | 见第 7 节 |
| 批量 | `POST /api/batches`、`GET /api/batches`、`GET /api/batches/{run_id}`、`GET .../export` | 批量是一个 `batch_process` ToolRun |

前端开发服务器 7301 端口，Vite 把 `/api` 与 `/events` 代理到后端 7302，保证 Cookie 和 SSE 同源。

## 7. Agent 主路径（实际行为）

### 7.1 规划与校验

1. `POST /messages` 进入 `services/agent.respond`。先生成一段**画布文字摘要**（画幅、图层列表、修订号、当前图格式、是否有选区），这是模型拿到的唯一上下文。**没有对话历史或记忆**，每轮独立规划。
2. `agent/graph.py` 编译一个只有两个节点的 LangGraph：`plan → verify → END`，无 checkpointer。
3. `plan` 节点调用 `agent/llm.py` 的规划模型：`ChatOpenAI(model=qwen-plus, temperature=0, base_url=DASHSCOPE_BASE_URL + /compatible-mode/v1)`，`bind_tools` 绑定全部 22 个工具的函数签名（由 pydantic 模型生成，去掉对模型隐藏的参数）。模型返回的 `tool_calls` 直接被当作计划 `[{tool, params}]`，**模型并不执行工具**。没有 tool_calls 时，模型文本截成一句作为答复。
4. `verify` 节点：工具存在性、按工具 pydantic 模型校验参数，再 `assemble`：补步骤 id（s1、s2…）、最多 8 步、依赖不存在报错、Kahn 算法检测环。任何错误都清空计划并回复“这一步暂时执行不了：…”。
5. 依赖关系的实际情况：模型的 tool_calls 只带工具名和参数，所以 `depends_on` 总是被补成“依赖上一步”的**线性链**。DAG 就绪判断和环检测代码存在，但规划主路径产生不了非线性依赖；环检测只在单元测试中直接构造输入时生效。README 所说“拓扑排序”在主路径上退化为顺序执行。
6. `_pin_selection` 把当前选区的 `mask_asset_id` 写进需要遮罩的步骤，让同一轮多步共用一块选区。

### 7.2 确认、执行、取消、重试

| 情况 | 轮次状态 | 行为 |
| --- | --- | --- |
| 规划失败（无 Key、异常） | `failed` | 记录错误，不执行 |
| 0 步（纯文本答复） | `succeeded` | 无工具调用 |
| 1 步 | `running` | 立即提交执行，**不需要确认** |
| ≥2 步 | `queued` | 等用户确认（`needs_confirm = len(steps) > 1`） |

- **按工具确认**：`_advance` 支持把 `needs_approval=True` 的步骤置为 `waiting`，但当前没有任何工具开启这个标志，所以实际的确认语义只有“多于一步需确认”。
- **确认**：`waiting` 步骤改回 `pending` 并标记 `approved`，轮次改为 `running`，调用 `_advance`。
- **推进**：`_advance` 循环取就绪步骤 → `tools.submit` 创建 ToolRun；同步工具当场执行并在成功后继续循环，队列工具以 `job_id = run_id` 投递 ARQ 后停下。Worker 执行完后在 `tools.execute` 末尾调用 `agent.continue_plan`，回写步骤状态并推进下一步。也就是说，**多步计划的推进一部分发生在 API 进程，一部分发生在 Worker 进程**，两者都直接读写 `agent_runs.plan` JSONB。
- **取消**：`pending/waiting` 步骤标为 `canceled`；仍是 `queued` 的 ToolRun 标为 `canceled`，Worker 取到终态任务会跳过。**正在运行的任务不会被中断**（没有调用 ARQ abort），远程模型费用照常产生。
- **重试**：只重置**最后一个**失败步骤（清空 run_id、置 `pending`），轮次回到 `running` 再推进，会创建新的 ToolRun。`tool_runs.retries` 字段存在但从未递增；没有自动重试策略。
- **轮次修订号**：`agent_runs.revision` 记录了规划时的修订号，但确认和执行时没有比对；画布被改动后的过期判断只靠选区的修订号和遮罩尺寸校验。

### 7.3 状态机

- ToolRun：`queued → running → succeeded | failed`；`queued → canceled`（仅计划取消时）。
- 计划步骤：`pending →（waiting）→ queued/running →` 终态，步骤状态镜像其 ToolRun。
- 轮次汇总（`plan.settle`）：有未完成步骤时，若存在 `waiting` 且没有 `queued/running` 则为 `queued`，否则 `running`；全部结束后有 `failed` 为 `failed`，否则有 `canceled` 为 `canceled`，否则 `succeeded`。

### 7.4 进度与前端契约

- SSE 只针对单个 ToolRun。每次 `runs.start/report/finish` 先提交数据库再发布到 Redis，帧内容是 `{id, tool, status, progress, stage, error, result}`。
- Agent 轮次的进度**不走 SSE**：前端 `useTurns` 在最新一轮为 `running` 时每 800ms 轮询 `GET /messages`。
- 前端 `useRun` 合并 SSE 实时帧和 `GET /runs/{id}` 快照，终态后关闭 EventSource 并刷新快照。

## 8. 数据模型

| 存储 | 内容 |
| --- | --- |
| `users` | 用户名、密码哈希 |
| `assets` | 种类（original/generated/subject/background/mask/marketing/export）、来源、对象存储 key、格式、宽高、字节数、是否含透明 |
| `edit_sessions` | 标题、原图与当前图、`revision`（采用候选或切换图片墙等操作时递增）、`history_seq`、`document` JSONB（图层文档） |
| `session_assets` | 会话图片墙及顺序 |
| `edit_history` | 线性编辑记录，`(session_id, seq)` 唯一，最多 20 条 |
| `tool_runs` | 工具名、状态、进度、阶段、参数、结果、错误、`retries`（未用）、起止时间；id 兼作 ARQ job id |
| `agent_runs` | 目标、答复、`plan` JSONB（步骤列表即断点）、状态、错误、规划时修订号 |
| Redis | ARQ 队列；`run:{id}` 进度频道；`selection:{session_id}` 选区（TTL 24h） |
| MinIO | 全部图片字节；对外只给预签名 URL |

## 9. 运行方式（来自代码与 README，未全部复现）

```text
docker compose up -d                       # postgres:17-alpine:7311, redis:7-alpine:7312, minio:7313/7314
cd backend && uv sync --all-extras
cp ../.env.example ../.env                 # IMAGE_PROVIDER=mock 免费跑通；dashscope 走真实模型
uv run alembic upgrade head
uv run uvicorn app.main:app --host 0.0.0.0 --port 7302
uv run arq app.worker.WorkerSettings
cd frontend && npm install && npm run dev  # 7301
cd backend && uv run pytest                # mock 隔离模型与第三方
uv run python -m app.eval app/eval/dataset # 确定性像素评测
```

关键环境变量：`DATABASE_URL`、`REDIS_URL`、`S3_*`、`JWT_SECRET`、`IMAGE_PROVIDER`（mock/dashscope）、`DASHSCOPE_API_KEY`、`DASHSCOPE_BASE_URL`（默认 `https://dashscope.aliyuncs.com`）、`TEXT_TO_IMAGE_MODEL`、`IMAGE_EDIT_MODEL`、`PLANNER_MODEL`、`MATTING_PROVIDER`（auto/rembg/corner）、`OCR_PROVIDER`（auto/rapidocr/none）。

## 10. 测试与评测现状

- 测试：13 个测试文件，静态统计 152 个测试函数（agent 24、tools 38、edits 35、sessions 11、assets 9、generation 9、eval 8、auth 7、batch 5、exports 5、health 1）。测试需要真实的 PostgreSQL、Redis 和 S3 兼容存储；图像模型固定为 mock，抠图固定为 `corner`，OCR 关闭，规划模型用 `monkeypatch` 替换为预设的 `AIMessage`。队列任务在测试中直接调用 `run_tool` 执行，不经过真实 Worker。
- Agent 测试覆盖：单步派发、纯文本答复、长拒答截句、画布摘要和选区传给模型、未注册工具和非法参数拒绝、缺 Key 失败、多步等待确认、确认后按序执行、队列步骤解锁下一步、两图层共用选区、选区随画布变化失效、取消未开始步骤、失败步骤重试、环依赖与超长计划拒绝。**全部是假模型输出**，不能说明真实模型的规划质量。
- 评测模块 `backend/app/eval/`：自带 3 个确定性用例（抠图 alpha IoU、调色 MAE/PSNR、投放尺寸的尺寸与完整包含），只评像素结果，不评“指令 → 计划 → 工具 → 最终任务”的 Agent 行为。

## 11. 本次复现记录（Q2）

| 步骤 | 结果 |
| --- | --- |
| 克隆并核对 SHA | 已完成 |
| `uv sync --all-extras` | 成功，依赖按 `uv.lock` 安装 |
| PostgreSQL 17、Redis 7（compose） | 已启动且健康。Docker Hub 返回 429 限流，镜像改从 `mirror.gcr.io` 拉取后打成同名 tag |
| MinIO | 未能获取：Docker Hub 返回 `pull access denied`，quay.io 与 dl.min.io 被沙箱网络策略拒绝（403）。已在 7313 端口启动 moto S3 服务作为替代，尚未使用 |
| 迁移、pytest、API、Worker、前端 | **未运行**：沙箱安全策略拦截了执行上游代码的命令，需要用户明确同意后再跑 |
| DashScope 连通性 | `dashscope.aliyuncs.com` 与 `dashscope-intl.aliyuncs.com` 均被网络策略拒绝（代理 403）；环境中未设置 `DASHSCOPE_API_KEY` |

因此 Q2 目前状态为 **待验收**，上游测试通过数、启动截图和真实模型基线都还没有证据。

## 12. README 与代码的出入（迁移时以代码为准）

1. 工具数：README 21 种，代码 22 项。
2. “拓扑排序”：主路径依赖总是线性链（7.1 第 5 点）。
3. “按依赖自动执行”的确认语义：实际是“多于一步需确认、单步直接执行”；按工具确认的机制存在但未启用。
4. LangGraph 只做 `plan → verify`，确认、执行推进、重试、取消都在 `services/agent.py`；声明了 Postgres checkpointer 依赖但未使用。
5. “超分辨率放大”由图像编辑模型按放大尺寸重绘实现，输出边长上限 2048。
6. 健康检查只有三项，不含 Redis。
7. 取消不能中断正在运行的远程调用。
8. Agent 轮次进度靠前端轮询，SSE 只覆盖单个工具任务。

## 13. 对 Java 改造的约束与待决事项

- **必须保持的语义**：模型只提议计划、服务端校验后才执行；多步计划先确认；单步重试与取消；工具参数界面与 Agent 同一套规则；选区与修订号的过期判断；ToolRun 幂等（以 run id 去重、终态跳过）；SSE 先快照后增量；结果先转存自有存储再暴露 URL。
- **Spring AI 对应**：规划阶段把 22 个工具的 Schema 交给模型生成结构化计划，但**不启用** `ToolCallingAdvisor` 的自动执行，由 Java 计划服务和状态机执行。
- **可改进点（尚未实现，不得写进简历）**：计划推进集中到 Java 单一状态权威；轮次修订号在确认时校验；运行中任务的取消传播；自动重试与超时策略；Agent 轮次进度事件；Redis 也纳入健康检查；真实模型的 Agent 行为评测。
- **前端与 Python 服务**：因无授权，新仓库不能复制上游前端和 Python 图像服务，需要独立实现或先取得授权（待用户决定）。
- **对象存储**：本环境拉不到 `minio/minio:latest`，B 阶段需选定可获取的 S3 兼容存储或固定可用版本，并记录原因。
- **版本**：Maven Central 当前可见 Spring AI `2.0.0`–`2.0.2`、Spring Boot `4.1.0`–`4.1.2`，B 阶段锁定具体组合并验证兼容。

## 14. Q1–Q10 当前状态

| ID | 状态 | 说明 |
| --- | --- | --- |
| Q1 | 已验证 | commit SHA、来源、22 个工具与 34 个接口对照表、许可核查见本文 |
| Q2 | 待验收 | 依赖已装、PG/Redis 已起；执行上游代码待用户同意；MinIO 与 DashScope 网络受阻 |
| Q3 | 设计 | 边界原则已明确，架构图与契约在 B 阶段产出 |
| Q4 | 设计 | 上游状态转换已梳理（7.3），Java 用例待写 |
| Q5 | 设计 | 上游单一 `SPECS` 已核实，Java 双入口测试待写 |
| Q6 | 设计 | 上游数据模型已梳理（第 8 节） |
| Q7 | 设计 | 上游队列、SSE、取消语义已梳理，故障注入待做 |
| Q8 | 设计 | 确认上游只有像素评测，需要新建 Agent 行为数据集 |
| Q9 | 设计 | 待定义程序断言与人工量表 |
| Q10 | 设计 | 尚无本人实现，不写任何简历主张 |
