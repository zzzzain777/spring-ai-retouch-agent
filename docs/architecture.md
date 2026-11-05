# 架构

- 状态：**设计**（B 阶段起点，2026-11-05）。本文描述要实现的结构；每一部分实现并验证后，在 `project-state.md` 里单独标状态，本文不代表已完成。
- 对外契约：沿用上游 `yuyuanweb/ai-picture-editor@49f6ad8` 的 HTTP 与 SSE 接口（路径、请求体、响应字段、状态码），这样冻结评测集 `agent-behavior-v1` 和前端可以不改就对比两边。新增接口只加不改。
- 代码来源：全部独立编写，不含上游源码。图像算法用有许可证的现成组件（rembg、Pillow、onnxruntime 等），不重写。

## 1. 组件与职责

| 组件 | 端口 | 职责 | 不负责 |
| --- | --- | --- | --- |
| 前端（React + Vite，独立实现） | 7301 | 画布展示、手动工具面板、对话与计划确认、选区笔刷与点选、SSE 进度展示 | 不做业务校验，不直连 Python 或存储写入 |
| Java 服务 `server/`（Spring Boot 4.1.2 + Spring AI 2.0.2） | 7302 | **唯一对外 API**；登录与权限；素材、会话、画布文档、历史；统一工具规格与参数校验；Agent 规划、计划校验、确认、调度、取消、重试；任务队列与 SSE；所有状态持久化 | 不做像素计算，不直接调图像模型 |
| Python 图像服务 `image-service/`（独立实现） | 7303，仅内网 | 无状态的图像计算：渲染合成、调色、抠图、遮罩栅格化与点选分割、投放尺寸、拆层；调用 DashScope 图像接口 | 不持有业务状态，不做用户鉴权，不决定执行顺序 |
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

## 4. Agent 主路径

```text
POST /messages ─▶ 构造画布摘要 ─▶ Spring AI ChatClient（携带 22 个工具的 Schema，
                                   internalToolExecutionEnabled=false）
                ─▶ 读取模型返回的 tool_calls 作为“提议”
                ─▶ PlanValidator：工具存在 → 参数校验（ToolSpec）→ 补全 id 与线性依赖
                    → 依赖存在性与环检测 → 步数 ≤ 8 → 钉住选区遮罩 → 记录规划时修订号
                ─▶ 持久化 agent_turns
                ─▶ 单步：立即派发；多步：status=queued 等确认；无计划：succeeded + 一句答复
```

- **规划阶段不执行工具**：Spring AI 的工具自动执行关闭；注册给模型的工具回调本身一旦被调用就抛异常，并有测试守住这一点。
- **执行只由 Java 显式调度**：`PlanExecutor.advance(turn)` 在锁住轮次行（`SELECT … FOR UPDATE`）后找就绪步骤并派发，同步步骤完成后继续推进；排队步骤完成时由完成事务调用 `advance`。
- **确认、取消、重试**遵循上游语义：确认把等待中的步骤放行；取消把未开始的步骤与排队中的任务标为 canceled；重试把最后一个失败步骤重置为 pending 再推进。终态轮次上的确认、取消、重试返回 409。
- **修订号**：确认时若会话修订号已不同于规划时（等待确认期间画布被改过），返回 409 并要求重新发送指令（上游记录了修订号但不校验，见 baseline G5）。

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
- **图像不走 HTTP 正文**：Java 只给 Python 传对象键与参数；Python 从对象存储读输入，把输出写到 `runs/{runId}/{attempt}/` 下，只回传元数据（键、宽高、格式、是否含透明、sha256）。Java 校验元数据后建 `assets` 行，再写文档与历史。
- **上传**：浏览器 → Java（multipart，校验 JPEG/PNG/WEBP、≤20 MB、≤5000 万像素）→ 对象存储。
- **下载**：前端只拿 Java 签发的短时签名 URL。
- **Java 调 Python**：内网 HTTP + 共享令牌头 `X-Internal-Token`；Python 不对公网开放。

## 7. 幂等、超时、失败、重复与重启

| 场景 | 处理 |
| --- | --- |
| 重复发送同一条指令 | `POST /messages` 支持 `Idempotency-Key` 请求头，`(session_id, key)` 唯一，重复请求返回同一轮次 |
| 重复确认或并发确认 | 锁住轮次行后检查状态；第二次返回 409；步骤派发以 `(turn_id, step_id, attempt)` 唯一约束防止重复建任务 |
| 迟到的结果（旧 Worker 在租约过期后才返回） | 写结果用条件更新 `WHERE id=? AND status='running' AND attempt=?`，不匹配就丢弃 |
| Python 重复执行 | 输出键由 runId 与 attempt 决定，重复执行覆盖同一位置，不产生多余素材 |
| 超时 | 每个工具有超时；Java 调 Python 的 HTTP 超时略大于工具超时；超时记 failed，可单步重试 |
| Python 或 DashScope 失败 | 错误映射为任务 failed 与可读错误；不自动重试非幂等工具 |
| 规划模型不可用或返回非法计划 | 轮次 failed 或给一句答复，不派发任何工具 |
| Redis 不可用 | SSE 退回为只发快照；选区不可用时区域工具作用于整层；健康检查报告 Redis 异常 |
| Java 进程重启 | 启动时与定时扫描租约过期的 running 任务按上表处理；排队任务原样保留；计划从库里继续推进 |

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
- 真实密钥放被 `.gitignore` 忽略的 `application-local.yml`，或环境变量。Java 是配置的唯一来源：调用 Python 时把 DashScope 密钥放在内网请求头 `X-DashScope-Key` 里转交，Python 不落盘、不写日志；Python 也可以直接读自己的环境变量 `DASHSCOPE_API_KEY`（优先级更低）。
