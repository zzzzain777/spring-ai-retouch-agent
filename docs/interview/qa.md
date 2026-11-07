# 面试问答

回答以本仓库代码与证据为准（代码路径前缀 `server/src/main/java/io/github/zzzzain777/retouch/`）。数字见 `docs/claim-ledger.md`、`docs/evidence-index.md`。

## 一、项目定位

**Q：这是你自己做的项目吗？参考了什么？**
参考 yuyuanweb/ai-picture-editor（Python/FastAPI，固定在 `49f6ad8`）的功能、工具清单和 HTTP 行为。它没有开源许可证，所以我没有复制它的代码、Prompt、前端或素材；Java 服务、Python 图像服务、React 前端、评测集与评测工具都是我独立编写的。上游的价值在于功能基线和对照组：同一套冻结评测集先测原版，再测我的 Java 版。

**Q：原版是怎么做 Agent 的？你改了什么？**
原版用 LangChain 绑定工具调用规划模型，LangGraph 只跑“plan → verify”两个节点，确认、推进、重试、取消在服务层。我改为：Spring AI 只负责取得模型的工具调用提议，校验、落库、确认和调度全部由 Java 服务和状态机决定；任务状态从“ARQ/Redis 队列”换成 PostgreSQL `tool_runs` 表作唯一权威。

## 二、Agent 规划与执行

**Q：为什么不直接让 Spring AI 自动执行工具？**
修图工具有副作用，多步计划必须先给用户确认。Spring AI 2.0 的 ChatClient 默认会挂 ToolCallingAdvisor 自动执行工具调用，所以在 `agent/SpringAiPlanner.java` 里每次调用都关掉它的自动注册，并且给每个工具装的回调是 `PlanningOnlyTool`：一旦被调用就抛 `IllegalStateException("规划阶段禁止执行工具")`。这样规划阶段即使框架行为变化也不会改图，`PlannerNoExecutionTest` 7 个测试专门守这一点。

**Q：模型返回的计划怎么校验？**
`agent/PlanValidator.java`：工具必须已注册；参数走和手动接口同一个校验入口（同一参数类型）；模型带回的隐藏参数在校验前删掉；检查依赖引用和环；步数上限 8（`PlanRules.MAX_STEPS`）。校验不过的提议变成一句回复，什么都不执行（`AgentTests.invalidProposalsBecomeAReplyAndNothingRuns`）。

**Q：单步和多步有什么区别？确认时会遇到什么问题？**
单步直接派发；多步落库为 `queued`，确认前派发数为 0。确认时校验画布修订号：等待期间画布被改过，就返回 409“画布在等待确认期间已被修改”，旧计划不会作用在新画布上（`AgentTests.stalePlanIsNotExecutedAfterTheCanvasChanged`）。确认后按依赖依次调度；某步失败会阻断后续，可单独重试失败步（`failedStepBlocksTheRestAndRetryRunsItAgain`）。终态上的重复确认、取消、重试返回 409。同一 `Idempotency-Key` 的重复请求返回同一轮，只规划一次。

**Q：取消怎么传播？**
排队中的任务直接取消（`QueueCancelTests.canceledQueuedRunNeverExecutes`）；运行中的任务在 `tool_runs.cancel_requested` 上打标记，Worker 在检查点响应并丢弃结果。后者已实现，但我没有为它写专门的自动化测试，这是要补的。

**Q：规划轨迹记了什么？**
每轮写进 `agent_turns.trace`：模型名、耗时、prompt/completion token、原样的工具调用、被拒绝的提议及原因。经授权接口 `GET …/messages/{turnId}/trace` 查询，不含密钥。

## 三、统一工具规格

**Q：手动接口和 Agent 怎么避免两套参数规则？**
`tool/ToolRegistry.java` 里 22 个 `ToolSpec` 是唯一来源，每个工具的参数是一个 Java record 类型（带 Bean Validation 注解）。同一个类型同时用于：交给模型的 JSON Schema 与 `GET /api/tools` 返回给前端生成表单的 Schema（都由 `ToolSchemas` 生成）；`ToolParams` 是唯一的参数校验入口（类型转换 → Bean Validation → 规范化），手动接口 `POST /api/sessions/{id}/tools` 和 Agent 计划校验都调用它。改一处三端同时生效，`ToolSchemasTest` 和评测 R04（手动与 Agent 共用校验）守住。

## 四、异步任务、可靠性与 SSE

**Q：为什么用数据库表当队列？**
任务状态要能在重启后恢复，并且只能有一个权威。`tool_runs` 表就是队列：Worker 用 `FOR UPDATE SKIP LOCKED` 领取，写租约 `lease_until` 并定期续期；进程崩溃后，租约过期的任务若是幂等工具且次数没用完就重新排队，否则标失败（`ImageToolTests.expiredLeaseIsRequeuedForIdempotentToolsAndFailsWhenAttemptsAreUsedUp`）。完成时锁行，只有“仍在运行且 attempt 没变”才写结果，迟到的旧结果直接丢弃。Python 只能写 `runs/{runId}/{attempt}/` 前缀，Java 校验前缀后才登记素材，重试之间互不覆盖。`@Async` 之类的线程池做不到重启恢复，所以没用它当任务权威。

**Q：SSE 怎么保证不丢状态？**
`tool/RunStreamController.java`：先订阅 Redis 频道，再读数据库快照发出去，之后转发事件；空闲时每 15 秒发心跳，到终态或 600 秒断开。先订阅后读快照，避免“读完快照到订阅之间”的事件丢失；Redis 只是通知通道，状态以数据库为准。

**Q：Worker 并发和扩展？**
当前单进程 2 个线程（`retouch.worker.concurrency=2`）。`SKIP LOCKED` 的设计允许多实例，但我没有做多实例和压力测试，不能说它在高并发下的表现。

## 五、选区

**Q：选区和图层的关系？选区过期怎么办？**
`layer_id` 决定改哪个图层，选区决定改哪块区域，两者独立。选区存 Redis（`selection:{sessionId}`，24 小时过期），绑定画布修订号：修订号对不上或 Redis 不可用都当作“没有选区”。用到遮罩的步骤在计划校验时就把当前选区钉进参数，确认前用户再改选区也不会让目标漂移。点选目前是圆形区域近似，不是 SAM 分割。

## 六、评测

**Q：怎么证明改造版更好？**
先于 Java 实现冻结了 44 例行为评测集（MANIFEST 哈希校验，运行器发现被改就拒绝运行），覆盖单步、多步、确认流程、失败、越权、注入和旧流程回归。用真实 DeepSeek（`deepseek-chat`，temperature 0）对原版和 Java 版同集各跑 3 轮，硬门三轮合计：确认前零派发 原版 45/123、Java 113/113；未确认无副作用 12/36 vs 35/35；确认流程 18/42 vs 38/38；越权隔离 48/51 vs 51/51；旧流程回归 81/81 vs 81/81。原版 M01、M03、M04、C01、C03、C04、F03、A03 三轮都失败，Java 三轮都通过。结论只覆盖这 44 例和这个模型，不是线上效果。

**Q：原版为什么失败？**
我把每轮 29 次发送和转发器记录的 29 次规划调用按时间一一配对（87 对工具完全一致），发现 87 次调用里模型没有一次返回超过 1 个工具调用。原版依赖模型在一次回复里给出多个并行工具调用，DeepSeek 在它的提示下只给一步，原版就按单步立即执行，所有“应该先停住等确认”的断言连带失败。F03（超过 8 步）失败也不是因为没有上限——原版源码有 `MAX_STEPS = 8`——而是上限从未被触发。A03 的越权断言本身 3/3 通过，失败是因为画布被用户自己的单步改动改了，这是评测集 v1 的样例设计弱点，记为 v2 候选（v1 冻结不改）。

**Q：评测里有没有发现你自己的问题？**
有。不配规划模型的确定性子集里，R06“导出会话素材压缩包”在 Java 版失败（接口没实现），修复后同一数据集同命令复测 16/17 → 17/17，修复前后两份报告都留着（`b3acd90`）。

**Q：评测结果有什么不严谨的地方？**
两版运行日期不同（原版 11-05、Java 11-07），DeepSeek 服务端模型映射是否变化无法确认；Java 原始轮次有 6 次 DeepSeek 接口超时/500，记为异常并保留，恢复后只补跑这 6 例（6/6 通过），没有替换原轮次；图像执行用的是占位接口，只判定流程；主观出图质量量表没打分。

## 七、提示词与成本

**Q：真实模型接进来遇到过什么问题？**
第一次用真实 DeepSeek，“先上下翻转，然后把透明度改成 60%”只返回了翻转一步。我改了提示词（要求一次给出全部步骤、说明工具调用不会立即执行），并加了可选“追问”轮。5 条真实冒烟各跑一次：追问 0 轮 5/5、合计 5562 ms、prompt 11,235；追问 1 轮也是 5/5，但 7519 ms、prompt 20,829（多 85%）。效果相同、成本翻倍，所以默认 0 轮。样本只有 5 条，只用于这个配置决策。

**Q：token 成本主要在哪？**
22 个工具的 Schema 每次规划都要发，单次输入约 4.5k token（无关请求一次 4,532 / 21）。配对评测中 Java 81 次调用共 prompt 367,196、completion 4,809；原版 87 次共 421,032 / 4,418。可做的优化是按画布状态裁剪工具集或瘦身 Schema，目前还没做。

## 八、局限（主动说明）

- 图像生成/编辑模型 DashScope 未接入（省钱决定）：9 个模型类工具只用本地假服务验证了调用链路，出图质量未测。
- 点选是圆形区域近似，没有 SAM；拆层没有 OCR 文字层（`include_text=true` 明确报不支持）。
- 前端没有在画布上直接拖拽/缩放图层的手势，也没有前后对比视图。
- Worker 单进程两线程，未做多实例与压力测试；运行中任务取消无专门测试。
- JWT 登出只清 Cookie，不吊销已签发令牌；对象存储里的孤儿对象没有清理。
- 只在本机运行，未上线；没有用云数据库（RDS）；MinIO 镜像拉不到，实际用 moto 作 S3 兼容存储。
