# 架构

- 状态：**设计**（B 阶段起点，2026-11-05；B7 起按实现更新第 4、7 节）。本文描述结构；每一部分实现并验证后，在 `project-state.md` 里单独标状态，本文不代表已完成。
- 对外契约：沿用上游 `yuyuanweb/ai-picture-editor@49f6ad8` 的 HTTP 与 SSE 接口（路径、请求体、响应字段、状态码），这样冻结评测集 `agent-behavior-v1` 和前端可以不改就对比两边。新增接口只加不改。
- 代码来源：全部独立编写，不含上游源码。图像算法用有许可证的现成组件（rembg、Pillow、onnxruntime 等），不重写。

## 1. 组件与职责

| 组件 | 端口 | 职责 | 不负责 |
| --- | --- | --- | --- |
| 前端（React + Vite，独立实现） | 7301 | 画布展示、手动工具面板、对话与计划确认、选区笔刷与点选、SSE 进度展示 | 不做业务校验，不直连 Python 或存储写入 |
| Java 服务 `server/`（Spring Boot 4.1.2 + Spring AI 2.0.2） | 7302 | **唯一对外 API**；登录与权限；素材、会话、画布文档、历史；统一工具规格与参数校验；Agent 规划、计划校验、确认、调度、取消、重试；任务队列与 SSE；所有状态持久化 | 不做像素计算，不直接调图像模型 |
| Python 图像服务 `image-service/`（独立实现） | 7303，仅内网 | 无状态的图像计算：渲染合成、调色、抠图、遮罩栅格化（点选暂以圆形区域代替，未接分割模型）、投放尺寸、拆层；调用 DashScope 图像接口 | 不持有业务状态，不做用户鉴权，不决定执行顺序 |
| PostgreSQL 17 | 7311 | 业务数据与任务队列的权威存储 | — |
| Redis 7 | 7312 | 选区缓存（TTL 24 小时）、任务进度事件的发布订阅 | 不是任何业务数据的权威来源 |
| S3 兼容对象存储（MinIO；云端沙箱用 moto） | 7313 | 图片字节 | 不存业务元数据 |
| 规划模型（DeepSeek 或 DashScope 兼容模式，OpenAI 兼容接口） | 外部 | 把自然语言变成工具调用提议 | 不执行任何工具 |
| DashScope 图像模型 | 外部 | 文生图、图像编辑 | — |

## 2. 数据权威

| 数据 | 权威位置 | 说明 |
| --- | --- | --- |
| 用户、密码散列 | PostgreSQL `users` | 登录态为 httpOnly Cookie 中的 JWT（与上游契约一致） |
| 素材元数据（所有者、类型、尺寸、格式、sha256、对象键） | PostgreSQL `assets` | 每次读取按所有者过滤；URL 为短时签名 URL，读取时重新签发 |
| 图片字节 | 对象存储 | 只能通过 `assets` 行引用；Python 只按 Java 给的键读写 |
| 会话、画布文档（JSONB）、修订号、历史游标 | PostgreSQL `edit_sessions` | `revision` 只由 Java 递增 |
| 编辑历史 | PostgreSQL `edit_history` | 撤销与重做只移动 `history_seq` |
| Agent 轮次与计划（步骤、依赖、状态、run_id） | PostgreSQL `agent_turns` | 计划就是断点，重启后从库里的步骤状态继续 |
| 任务（ToolRun）：参数、状态、进度、尝试次数、租约 | PostgreSQL `tool_runs` | **队列也在这张表**，不依赖 Redis 队列，进程重启不丢任务 |
| 选区 | Redis `selection:{sessionId}` | 记录 `{revision, maskAssetId, markers}`；修订号不一致即视为无选区；遮罩字节本身是 `mask` 类素材 |
| 进度事件 | Redis 频道 `run:{runId}` | 只用于推送；SSE 连接先读库里的快照，再转发事件，Redis 丢消息也不会丢状态 |

## 3. 统一工具规格

Java 里有且只有一份工具注册表 `ToolRegistry`，每个工具一个 `ToolSpec`：

| 字段 | 用途 |
| --- | --- |
| `name`、`label`、`description` | 工具名（与上游 22 个一致）、界面文案、给模型的说明 |
| `paramsType` | 参数 record；Bean Validation 注解即参数规则；JSON Schema 由同一个类型生成，交给规划模型 |
| `execution` | `DOCUMENT`（同步改文档）、`IMAGE`（排队，调用 Python）、`COMPOSITE`（排队，组合多个操作） |
| `sessionRequired`、`usesMask` | 是否必须在会话里用；是否需要钉住选区遮罩 |
| `timeout`、`maxAttempts`、`idempotent` | 超时与重试策略；只有幂等工具在中断后自动重排 |
| `needsApproval` | 单步也要确认的高风险工具（默认与上游一致为 false，按 B 阶段评估开启） |

手动接口 `POST /api/sessions/{id}/tools` 与 Agent 计划执行都走 `ToolService.submit(user, tool, params, sessionId)`：同一套参数校验、同一套资源授权（会话、图层、素材都按当前用户校验）、同一套执行器。前端可读 `GET /api/tools`（新增）拿到规格生成表单。

### 3.1 22 个工具落在哪里（B9 后）

分工原则：**决定做什么的都在 Java**（作用哪一层、用不用选区、给模型的提示词、结果进画布还是只进图片墙、修订号加不加）；Python 只按 Java 给的对象键渲染、合成、抠图、调 DashScope，并把输出写回本任务前缀。提示词与工具说明都是本项目自己写的。

| 工具 | 执行方式 | 像素在哪算 | 图像模型 | 结果去向 |
| --- | --- | --- | --- | --- |
| 9 个文档工具（裁剪、翻转、透明度、显隐、改文字、图层顺序、缩放、旋转、移动） | DOCUMENT | 不算像素，只改文档 | — | 画布，修订号 +1 |
| `adjust_image` | IMAGE | Python 调色 | — | 替换目标层 |
| `remove_background` | IMAGE | Python 抠图：装了可选依赖 `rembg` 用它，否则用四角取色抠底（`MATTING_PROVIDER`） | — | 替换目标层（subject 类素材） |
| `prepare_delivery_sizes` | IMAGE | Python 拼平画布、按 1:1/4:5/9:16 等留白缩放 | — | 只进图片墙（export），修订号不变 |
| `replace_background` | IMAGE | Python 拼平背景层 | 编辑 | 1 张且未分层：替换背景层；多张或已分层：只进图片墙 |
| `expand_canvas`、`upscale_image` | IMAGE | Python 拼平画布 | 编辑 | 未分层：结果成为当前图；已分层：只进图片墙 |
| `erase_region`、`replace_region` | IMAGE，`usesMask` | Python 按“选区 ∩ 图层”裁遮罩，模型结果只贴回选区（羽化） | 编辑 | 替换该层，清空选区；画幅已变或修订号过期返回 409/失败，不调模型 |
| `generate_marketing` | IMAGE | Python 拼平画布、按比例适配 | 编辑 | 只进图片墙（marketing） |
| `split_layers` | IMAGE | Python 抠主体、补洞（OpenCV Telea） | 编辑（修背景） | 改成 background（锁定）+ subject 两层；已拆过不重复；`include_text=true` 明确报“暂不支持”（未接 OCR） |
| `promote_object_to_layer` | IMAGE，`usesMask` | Python 按选区切物体、补原位置 | 编辑（修原位置） | 新增 `object-{摘要}` 图层；同一物体不重复提升 |
| `generate_image` | IMAGE，不需会话 | — | 文生图（异步任务轮询） | 候选图（`GET /api/runs/{id}` 的 `candidates`） |
| `batch_process` | COMPOSITE，不需会话 | 每一步一次 Python 调用，上一步输出键即下一步输入 | 视操作而定 | 每张图按格式（png/jpg）导出，`GET /api/batches/{id}/export` 打包 zip；单张失败只记在该项 |

没有配置 `DASHSCOPE_API_KEY` 时（本项目当前状态，为省钱不接），需要图像模型的 9 个工具在 Java 侧直接失败「未配置 DASHSCOPE_API_KEY，图像模型不可用」，不发请求、画布不变；不需要模型的工具照常可用。

## 4. Agent 主路径

```text
POST /messages ─▶ 构造画布摘要 ─▶ Spring AI ChatClient（携带全部已登记工具的定义与参数 Schema，
                                   关闭 ToolCallingAdvisor 自动注册）
                ─▶ 读取模型返回的 tool_calls 作为“提议”，并把模型名、耗时、token、原样调用写进轨迹
                ─▶ PlanValidator：工具存在 → 去掉服务端填写的隐藏参数 → 参数校验（与手动接口同一入口）
                    → 补全 id 与线性依赖 → 依赖存在性与环检测 → 步数 ≤ 8 → 钉住选区遮罩
                ─▶ 持久化 agent_turns（含规划时修订号与轨迹）
                ─▶ 单步：立即派发；多步：status=queued 等确认；无计划：succeeded + 一句答复
```

- **规划阶段不执行工具**：Spring AI 2.0.2 的 `ToolCallingChatOptions` 已没有 `internalToolExecutionEnabled` 选项，工具执行改由 ChatClient 自动加入的 `ToolCallingAdvisor` 负责。规划调用显式关闭它的自动注册（`AdvisorParams.toolCallingAdvisorAutoRegister(false)`），模型返回的 tool_calls 原样交回 Java；注册给模型的工具只有定义没有实现，回调一旦被调用就抛异常。`PlannerNoExecutionTest` 守住这两点，并有对照用例证明不关掉时 Spring AI 确实会去执行。
- **工具 Schema**：由 `ToolSpec` 的参数类型用 victools 生成（字段名下划线、必填与取值范围取 Bean Validation 注解、枚举取接口取值），与参数校验同源；隐藏参数不出现在 Schema 里，模型即使给了也会在校验前删掉。
- **提示词**：本项目自写。通用规则固定；工具选择规则只收录所涉工具都已登记的条目，不引导模型调用尚未实现的工具。多步指令要求模型在一次回复里按顺序给出全部工具调用：B7 在 Mac 上用真实 DeepSeek 检查时，“先上下翻转，然后把透明度改成 60%”只返回了翻转一步，原规则“依次调用”被模型理解成等结果再给下一步。
- **可选追问一轮**：`retouch.planner.follow-up-rounds`（默认 0）。大于 0 时，拿到工具调用后回给模型一条“已记录、尚未执行”的工具结果，请它补上剩余步骤或回复“好”；模型原样重复上一轮视为没有更多步骤；累计不超过 8 步，token 合计记进轨迹（`rounds` 字段）。工具始终没有被执行，追问只多一次模型请求。真实 DeepSeek 检查里（`docs/evidence/real-planner-check/`），提示词修正后 0 轮已能一次给全多步，开 1 轮结果相同而 prompt token 约翻倍，所以默认 0。
- **选区钉入**：规划时把 Redis 里当前修订号的选区写进画布摘要（“已有笔刷选区”“已有选区，n 个标点”或“当前没有选区”）；计划校验通过后，`usesMask` 的步骤若没有 `mask_asset_id`，填入该选区的遮罩素材 id。之后再改选区不影响已生成的计划；修订号不一致或 Redis 不可用视为没有选区，区域工具作用于整层。
- **执行只由 Java 显式调度**：推进计划时先锁轮次行（`SELECT … FOR UPDATE`），按 `tool_runs` 对账步骤状态，再派发就绪步骤；文档工具同步完成后立即推进下一步，图像工具完成后由 Worker 回调推进。派发经 `ToolService.submit`，它总在独立事务里建任务与改文档（`REQUIRES_NEW`），工具失败不会把外层的计划更新标成只能回滚。
- **确认、取消、重试**遵循上游语义：确认把等待中的步骤放行；取消把未开始的步骤标为 canceled、排队中的任务直接取消；重试把最后一个失败步骤重置为 pending 再推进，新建一条任务（`step_attempt` 递增）。终态轮次上的确认、取消、重试返回 409。
- **相对上游的改动**（对应 `upstream-baseline.md` §13）：
  - G5 修订号：确认时若会话修订号已不同于规划时（等待确认期间画布被改过），返回 409「画布在等待确认期间已被修改，请重新发送指令」，不执行旧计划。
  - G6 取消传播：运行中的任务也会收到取消请求，Worker 在下一个检查点结束并丢弃远程结果，轮次在它结束后汇总为 canceled。
  - 失败阻断：上游在前一步失败后，把永远不会执行的后续步骤仍算作未完成，轮次一直是 running；这里汇总为 failed，单步重试后后续步骤照常执行。
  - 轨迹：每轮记录规划调用的模型名、耗时、token 用量、模型原样给出的工具调用与被拒原因，`GET /api/sessions/{id}/messages/{turnId}/trace` 可查（新增接口）。

## 5. 状态机

ToolRun：

| 当前 | 事件 | 下一个 |
| --- | --- | --- |
| — | 提交（同步工具） | running → succeeded / failed |
| — | 提交（排队工具） | queued |
| queued | Worker 领取（`FOR UPDATE SKIP LOCKED`，写入租约） | running |
| queued | 取消 | canceled |
| running | 执行完成 | succeeded / failed |
| running | 取消请求 | canceled（Worker 在阶段之间与等待 Python 返回时检查；远程调用结果丢弃） |
| running | 租约过期（进程崩溃或重启） | 幂等且未超次数：queued（attempt+1）；否则 failed「执行中断」 |
| succeeded / failed / canceled | 任何事件 | 不变（终态） |

计划步骤：`pending → (waiting) → queued/running → succeeded / failed / canceled`。
轮次状态由步骤汇总：有 running/queued 步骤为 running；只剩等待确认为 queued；任一失败为 failed；有取消为 canceled；否则 succeeded（与上游 `settle` 一致）。

## 6. 跨服务任务 ID 与图像传输

- **任务 ID**：Java `tool_runs.id`（UUID）贯穿全链路：SSE 路径、Redis 频道、调用 Python 的请求头 `X-Run-Id` 与 `X-Attempt`、对象存储输出前缀 `runs/{runId}/{attempt}/`、日志 MDC。
- **图像不走 HTTP 正文**：Java 只给 Python 传对象键与参数；Python 从对象存储读输入，把输出写到 `runs/{runId}/{attempt}/` 下（批量等多步任务再加一层由 Java 指定、格式受限的子目录，如 `i1-o2-f1/`），只回传元数据（键、宽高、格式、是否含透明、sha256、输出名）。Java 校验元数据与前缀后建 `assets` 行，再写文档与历史。
- **调 DashScope**：Python 把模型返回的图片下载后写进自有对象存储，Java 只登记自有存储里的对象，不保存模型服务的临时 URL。
- **上传**：浏览器 → Java（multipart，校验 JPEG/PNG/WEBP、≤20 MB、≤5000 万像素）→ 对象存储。
- **下载**：前端只拿 Java 签发的短时签名 URL。
- **Java 调 Python**：内网 HTTP + 共享令牌头 `X-Internal-Token`；Python 不对公网开放。

## 7. 幂等、超时、失败、重复与重启

| 场景 | 处理 |
| --- | --- |
| 重复发送同一条指令 | `POST /messages` 支持 `Idempotency-Key` 请求头（1–64 字符），`(session_id, key)` 唯一，重复请求返回同一轮次并带 `Idempotent-Replayed: true`。限制：两个重复请求真正并发时可能各调一次模型，但只落一轮、只执行一次 |
| 重复确认或并发确认 | 锁住轮次行后检查状态；第二次返回 409；步骤派发以 `(turn_id, step_id, attempt)` 唯一约束防止重复建任务 |
| 迟到的结果（旧 Worker 在租约过期后才返回） | 写结果用条件更新 `WHERE id=? AND status='running' AND attempt=?`，不匹配就丢弃 |
| Python 重复执行 | 输出键由 runId 与 attempt 决定，重复执行覆盖同一位置，不产生多余素材 |
| 超时 | 每个工具有超时；Java 调 Python 的 HTTP 超时略大于工具超时；超时记 failed，可单步重试 |
| Python 或 DashScope 失败 | 错误映射为任务 failed 与可读错误；不自动重试非幂等工具 |
| 规划模型不可用或返回非法计划 | 轮次 failed 或给一句答复，不派发任何工具 |
| Redis 不可用 | SSE 退回为只发快照；选区不可用时区域工具作用于整层；健康检查报告 Redis 异常 |
| Java 进程重启 | 启动时与定时扫描租约过期的 running 任务按上表处理；排队任务原样保留；启动时对所有执行中轮次对账一次，之后每 30 秒对账超过 30 秒没更新的执行中轮次：步骤状态以 `tool_runs` 为准（按派发次数认领），“任务已提交、计划未更新”之间崩溃也不会重复执行 |

**限制（不宣称生产级）**：远程图像模型调用无法中途取消，只能丢弃结果；单个 Worker 线程池，未做多实例压测；没有限流与配额。

## 8. 主要时序

正常单步（同步工具，如翻转）：

```text
前端 → Java POST /messages → 规划模型 → 校验 → 存轮次(running) → ToolService.submit
     → 文档工具同步改文档、revision+1、写历史 → 轮次 succeeded → 201 返回
```

正常多步（含图像工具）：

```text
POST /messages → 规划 → 校验 → 存轮次(queued，步骤 pending)
POST /confirm  → 锁轮次 → 校验修订号 → 放行 → 派发第 1 步(queued) → 200
Worker 领取 → 调 Python（X-Run-Id）→ Python 读写对象存储 → 回元数据
     → Java 建素材、改文档、写历史、run succeeded、发 Redis 事件 → advance 派发下一步
前端 GET /events/runs/{runId}：先收快照，再收进度，直到终态
```

失败与重启：

```text
Python 超时 → run failed（错误：图像服务超时）→ 轮次 failed → POST /retry → 重置该步 → 重新派发（attempt+1）
Java 崩溃 → 重启扫描到租约过期的 running → 幂等工具重排、非幂等标 failed → 轮次按步骤汇总
取消 → queued 任务直接 canceled；running 任务置取消标记，Worker 在下一个检查点结束并丢弃远程结果
```

## 9. 配置与密钥

- `server/src/main/resources/application.yml`：规划模型（OpenAI 兼容接口，默认 DeepSeek，可切 DashScope 兼容模式）与 DashScope 图像模型的地址、模型名，密钥只写 `${DEEPSEEK_API_KEY:}`、`${DASHSCOPE_API_KEY:}`。
- **为什么规划用 DeepSeek 而不是 DashScope**：项目说明里原写的是 DashScope 规划。2026-11-05 用户在 A 阶段选定 DeepSeek 作为规划模型与评测基线（已在 Mac 上接通，原版基线也用它测，两边可比）；DashScope 只用于图像模型。接入走 Spring AI 自带的 OpenAI 兼容适配器：Spring AI Alibaba 的 DashScope starter 目前只有面向 Boot 4.0 的预览版，与本项目的 Boot 4.1.2 不兼容。切换到 DashScope 兼容模式只需改 `spring.ai.openai.base-url` 与模型名，代码不变。
- 真实密钥放被 `.gitignore` 忽略的 `application-local.yml`，或环境变量。Java 是配置的唯一来源：调用 Python 时把 DashScope 密钥放在内网请求头 `X-DashScope-Key` 里转交，Python 不落盘、不写日志；Python 也可以直接读自己的环境变量 `DASHSCOPE_API_KEY`（优先级更低）。
