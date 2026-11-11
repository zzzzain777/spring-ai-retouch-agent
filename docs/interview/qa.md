# Java 后端 + AI 应用模拟面试题库：修图 Agent 主线

整理日期：2026-11-10。适用于当前 `spring-ai-retouch-agent`，不是通用八股全文，也不是把面经答案照搬过来。

## 怎么使用

- 每轮 20 题，一次只问一题。先由你回答，我指出答对与遗漏，再给面试版表达；不会时先讲解、再复述，仍算同一题。
- 优先抽项目题，再穿插与该题相连的高频基础题。1—18 是项目题，19—24 是基础题。题目顺序不是必须顺序。
- “项目现状”只依据当前代码；“改进”是面试中的思考，不能说成已经实现。没有压测数据，不声称性能提高了多少。
- 每条“依据”都指向本仓库代码或官方文档；点开能核对的才写，核不了的不写。

---

## 一、项目定位与主路径

### 1. 用一分钟介绍这个项目，重点讲什么？

**参考回答：**这是一个个人练习的 AI 修图 Agent：用户上传图片、用一句话描述目标，模型给出工具调用提议，Java 校验后单步直接执行、多步等用户确认，再按依赖顺序执行。我主要做了四块：规划与执行分离、统一工具规格、可恢复的异步任务、以及规范检索与合规检查。技术难点我会选一个能讲清的，例如“怎样保证模型不绕过确认直接改图”，先说问题、再说代码边界。不要把练手项目描述成线上系统。

**依据：**[README](../../README.md)、[架构说明](../architecture.md)。

### 2. 模型能直接改图吗？你怎么保证它不能？

**参考回答：**不能。规划阶段做了两道保险：一是每次调用都关掉 Spring AI 的 `ToolCallingAdvisor` 自动注册（`AdvisorParams.toolCallingAdvisorAutoRegister(false)`），模型返回的 `tool_calls` 原样交回 Java；二是交给模型的工具只有定义、没有实现，`PlanningOnlyTool.call` 一旦被调用就抛异常。所以即使框架行为变化，规划阶段也改不了图。

**依据：**[SpringAiPlanner](../../server/src/main/java/io/github/zzzzain777/retouch/agent/SpringAiPlanner.java)、[PlannerNoExecutionTest](../../server/src/test/java/io/github/zzzzain777/retouch/agent/PlannerNoExecutionTest.java)。

### 3. 模型给的计划，Java 校验哪些东西？

**参考回答：**工具是否已注册、参数是否合法（和手动接口走同一个 `ToolParams` 校验入口）、依赖是否成环、步数是否超过 8。模型带回的隐藏参数在校验前删掉。校验不通过就把整份计划转成一句回复，什么都不执行。

**依据：**[PlanValidator](../../server/src/main/java/io/github/zzzzain777/retouch/agent/PlanValidator.java)、[PlanRules](../../server/src/main/java/io/github/zzzzain777/retouch/agent/PlanRules.java)。

### 4. 单步和多步有什么区别？确认时会遇到什么问题？

**参考回答：**单步直接派发；多步落库为 `queued`，确认前派发数为 0。确认时校验画布修订号：等待期间画布被改过，就返回 409，旧计划不会作用在新画布上。确认后按依赖依次调度；某步失败会阻断后续，可单独重试失败步。终态上的重复确认、取消、重试返回 409；同一 `Idempotency-Key` 的重复请求返回同一轮。

**依据：**[AgentService](../../server/src/main/java/io/github/zzzzain777/retouch/agent/AgentService.java)、[AgentTests](../../server/src/test/java/io/github/zzzzain777/retouch/agent/AgentTests.java)。

### 5. 为什么不用 Graph 或 LangGraph，而用显式状态机？

**参考回答：**当前主路径和异常分支有限，Java 状态、版本条件更新和数据库记录更容易逐步核对；引入图框架要多一层状态恢复与调试成本。等分支复杂到现有代码难维护时，再比较 Graph 的检查点收益。这是设计取舍，不是“Graph 不好”。

**依据：**[AgentService](../../server/src/main/java/io/github/zzzzain777/retouch/agent/AgentService.java)、[架构说明](../architecture.md)。

---

## 二、统一工具规格

### 6. 手动接口和 Agent 怎么避免两套参数规则？

**参考回答：**`ToolRegistry` 里 22 个 `ToolSpec` 是唯一来源，每个工具的参数是一个 Java record（带 Bean Validation 注解）。同一个类型同时用于三处：交给模型的 JSON Schema、`GET /api/tools` 给前端生成表单的 Schema、以及 `ToolParams` 的参数校验入口。改一处三端同时生效。

**依据：**[ToolRegistry](../../server/src/main/java/io/github/zzzzain777/retouch/tool/ToolRegistry.java)、[ToolSchemas](../../server/src/main/java/io/github/zzzzain777/retouch/tool/ToolSchemas.java)、[ToolParams](../../server/src/main/java/io/github/zzzzain777/retouch/tool/ToolParams.java)。

### 7. 如果不用同一份定义，会出什么问题？

**参考回答：**最常见的是“漂移”：手写校验允许 0~1，模型 Schema 写成 0~100，前端又一套范围。结果同一句话从面板发能过、从 Agent 发被拒，或者反过来。项目用同源生成消除这一类问题，`ToolSchemasTest` 守住 Schema 形状，评测 R04 守住“手动与 Agent 共用校验”。

**依据：**[ToolTests](../../server/src/test/java/io/github/zzzzain777/retouch/ToolTests.java)、[ToolSchemasTest](../../server/src/test/java/io/github/zzzzain777/retouch/tool/ToolSchemasTest.java)。

### 8. 22 个工具里，有几个真能出图？

**参考回答：**22 个里 9 个是画布文档工具（即时生效），调色/去背景/投放尺寸由本地图像服务处理，9 个依赖 DashScope 图像模型的工具只验证了调用链路，**出图质量未测**。面试要主动说这一点，不能把“注册了 22 个工具”说成“22 个都能出好图”。

**依据：**[实现状态](../implementation-state.md)、[ToolRegistry](../../server/src/main/java/io/github/zzzzain777/retouch/tool/ToolRegistry.java)。

---

## 三、异步任务与 SSE

### 9. 为什么用数据库表当队列，不用 MQ 或线程池？

**参考回答：**任务状态要能在重启后恢复，并且只能有一个权威。`tool_runs` 表就是队列：Worker 用 `FOR UPDATE SKIP LOCKED` 领取并写租约（领取时 15 分钟，开始执行时按该工具的超时重设一次，之后不再周期续期）；进程崩溃后，租约过期的任务若是幂等工具且次数没用完就重新排队，否则标失败。`@Async` 线程池做不到重启恢复，所以没用它当任务权威。

**依据：**[RunWorker](../../server/src/main/java/io/github/zzzzain777/retouch/tool/RunWorker.java)、[ToolRunRepository](../../server/src/main/java/io/github/zzzzain777/retouch/tool/ToolRunRepository.java)。

### 10. `SKIP LOCKED` 和租约分别解决什么？

**参考回答：**`SKIP LOCKED` 让多个 Worker 同时领取时不互相阻塞、也不会领到同一条；租约解决“领了之后进程挂了怎么办”——租约过期后任务可被重新领取。两者一起用，才能既并发又不重复执行。

**依据：**[RunWorker](../../server/src/main/java/io/github/zzzzain777/retouch/tool/RunWorker.java)、[WorkerFailureTests](../../server/src/test/java/io/github/zzzzain777/retouch/WorkerFailureTests.java)。

### 11. 重试时怎么避免两次结果互相覆盖？

**参考回答：**输出按 `runs/{runId}/{attempt}/` 前缀隔离，迟到结果直接丢弃；完成时锁行，只有“仍在运行且 attempt 没变”才写结果。Python 只写给定前缀，Java 校验前缀后才登记素材。

**依据：**[RunWorker](../../server/src/main/java/io/github/zzzzain777/retouch/tool/RunWorker.java)、[ImageToolTests](../../server/src/test/java/io/github/zzzzain777/retouch/ImageToolTests.java)。

### 12. SSE 怎么保证不丢状态？

**参考回答：**`RunStreamController` 先订阅 Redis 频道，再读数据库快照发出去，之后转发事件；空闲时每 15 秒发心跳，到终态或 600 秒断开。先订阅后读快照，避免“读完快照到订阅之间”的事件丢失；Redis 只是通知通道，状态以数据库为准。

**依据：**[RunStreamController](../../server/src/main/java/io/github/zzzzain777/retouch/tool/RunStreamController.java)。

### 13. Worker 支持多实例吗？压测过吗？

**参考回答：**`SKIP LOCKED` 的设计允许多实例，但**没有做多实例和压力测试**，当前是单进程 2 线程（`retouch.worker.concurrency=2`）。面试不能说它在高并发下的表现。

**依据：**[application.yml](../../server/src/main/resources/application.yml)、[实现状态](../implementation-state.md)。

---

## 四、规范检索与合规检查

> 逐文件精读、实测分数与动手验证清单见 [knowledge-rag.html](../knowledge-rag.html)（浏览器打开）。

### 14. 这个模块解决什么问题？是 RAG 吗？

**参考回答：**它不回答开放问题，而是给修图计划提供“规范约束”。按本轮目标检索适用的规范条款，召回到的条款经规则表转成对计划的硬校验：不满足就不通过、交人工。检索结果进入校验器，不塞进提示词让模型自由发挥。**没有生成那一步，所以我不叫它 RAG**，准确说是“用检索给规则校验供给约束”。

**依据：**[KnowledgeRetriever](../../server/src/main/java/io/github/zzzzain777/retouch/knowledge/KnowledgeRetriever.java)、[ComplianceChecker](../../server/src/main/java/io/github/zzzzain777/retouch/knowledge/ComplianceChecker.java)、[knowledge-rag.md](../knowledge-rag.md)。

### 15. 为什么检索前先按平台/类目过滤？

**参考回答：**不同平台/类目的规范会互相矛盾（天猫允许浅灰背景，京东要求纯白），只靠向量相似度会把别的类目的条款召回。所以先用元数据把范围收紧，再在范围内做向量检索；过滤在返回给模型之前完成。

**依据：**[KnowledgeRetriever](../../server/src/main/java/io/github/zzzzain777/retouch/knowledge/KnowledgeRetriever.java)。

### 16. 检索词怎么构造？两档阈值为什么不一样？

**参考回答：**合规的检索词是「本轮目标 + 计划里工具的中文名」（中文名取自 `ToolRegistry` 的 label，和手动接口、模型 Schema 同一份规格）。goal 必填，因为**要查什么取决于用户想干什么，不取决于计划里已经有什么**——只有“翻转”的计划，拿工具名去检索根本召不回背景条款，而它恰恰因为没做背景处理才该被拦。

两档参数：规范问答 Top-3、阈值 0.5，答错了用户立刻看得出来，宁可不答；合规 Top-5、阈值 0.3，**漏检是静悄悄放过去的**，宁可多召回几条再逐条判。实测：问“首图背景有什么要求”召回 3 条（rule-1 0.776 排首），问“退货政策是什么”召回 0 条；阈值 0.35 时后者会误召回 2 条。阈值都写在 `application.yml`，是少量手工查询试出来的，样本不多。

**依据：**[application.yml](../../server/src/main/resources/application.yml)、[knowledge-rag.md](../knowledge-rag.md)。

### 17. 合规规则是不是硬编码的？加一条规则改哪里？

**参考回答：**不是。规则表在 `application.yml` 的 `retouch.knowledge.compliance.rules`：`keywords` 命中条款，`require-any` 要求计划包含某个工具，`forbid` 要求计划不得包含。加一条检查项不用改 Java，只改配置。

**依据：**[ComplianceRuleProperties](../../server/src/main/java/io/github/zzzzain777/retouch/knowledge/ComplianceRuleProperties.java)、[application.yml](../../server/src/main/resources/application.yml)。

### 18. 检索不到规范会怎样？这个方案最大的风险是什么？

**参考回答：**三个“不确定”的分支都不放行：检索不到规范、没配规则、召回了但没有规则命中——统一是 fail-closed，不确定就交人工。

**最大的风险是召回不足等于漏检**：只有召回到的条款会参与判定，漏召回不会报错，就是悄悄放行了。缓解有三点：空召回一律不放行；合规这档阈值放低、条数放多；写了一个测试 `onlyRecalledClausesAreChecked` 把“只判召回到的条款”这个行为钉死，免得以后被误当成全量校验。语料再大就要加关键词兜底做 hybrid 检索，或者把 `forbid` 类规则改成全量生效。

入库按 docId 存内容哈希：哈希没变就整份跳过，变了才先删旧向量再写新的；索引懒加载，第一次调合规检查才跑，避免测试启动就打 embedding 接口。

**依据：**[ComplianceChecker](../../server/src/main/java/io/github/zzzzain777/retouch/knowledge/ComplianceChecker.java)、[KnowledgeIndexService](../../server/src/main/java/io/github/zzzzain777/retouch/knowledge/KnowledgeIndexService.java)。

---

## 五、评测与成本

### 19. 怎么证明改造版更好？数字口径是什么？

**参考回答：**先于 Java 实现冻结了 44 例行为评测集（MANIFEST 哈希校验，运行器发现被改就拒绝运行），用真实 DeepSeek 对原版和 Java 版同集各跑 3 轮。硬门三轮合计：确认前零派发 原版 45/123、Java 113/113；未确认无副作用 12/36 vs 35/35；确认流程 18/42 vs 38/38；越权隔离 48/51 vs 51/51；旧流程回归 81/81 vs 81/81。结论只覆盖这 44 例和这个模型，不是线上效果。

**依据：**[paired-summary](../../eval/reports/paired-c1/paired-summary.md)、[claim-ledger](../claim-ledger.md)。

### 20. 原版为什么失败？

**参考回答：**把每轮 29 次发送和转发器记录的 29 次规划调用按时间配对（87 对工具完全一致），发现 87 次调用里模型没有一次返回超过 1 个工具调用。原版依赖模型一次给多个并行调用，DeepSeek 在它的提示下只给一步，原版就按单步立即执行，所有“应该先停住等确认”的断言连带失败。F03 失败也不是因为没有上限——原版源码有 `MAX_STEPS = 8`——而是上限从未被触发。

**依据：**[c1-upstream-failure-analysis](../../eval/reports/c1-upstream-failure-analysis.md)。

### 21. 评测里有没有发现你自己的问题？

**参考回答：**有。确定性子集里 R06“导出会话素材压缩包”在 Java 版失败（接口没实现），修复后同一数据集同命令复测 16/17 → 17/17，修复前后两份报告都留着。

**依据：**[SessionExportTests](../../server/src/test/java/io/github/zzzzain777/retouch/SessionExportTests.java)、[claim-ledger](../claim-ledger.md)。

### 22. 评测有什么不严谨的地方？

**参考回答：**两版运行日期不同（原版 11-05、Java 11-07），DeepSeek 服务端模型映射是否变化无法确认；Java 原始轮次有 6 次接口超时/500，记为异常并保留，恢复后只补跑这 6 例（6/6 通过），没有替换原轮次；图像执行用的是占位接口，只判定流程；主观出图质量量表没打分。

**依据：**[paired-summary](../../eval/reports/paired-c1/paired-summary.md)、[claim-ledger](../claim-ledger.md)。

### 23. 追问轮为什么默认关？token 花在哪？

**参考回答：**5 条真实冒烟里，追问 0 轮和 1 轮都是 5/5，但 1 轮 prompt token 多 85%（20,829 vs 11,235）、耗时多 35%（7.5s vs 5.6s），所以默认 0 轮。token 大头是 22 个工具的 Schema，每次规划都要发，单次输入约 4.5k。可做的优化是按画布状态裁剪工具集，目前还没做。

**依据：**[PlannerPrompt](../../server/src/main/java/io/github/zzzzain777/retouch/agent/PlannerPrompt.java)、[real-planner-check](../evidence/real-planner-check/0741ae4-r0.json)。

---

## 六、基础题（与项目相连）

### 24. 为什么上线前要确认 Java 21 和 JUnit 版本？

**参考回答：**项目用 Java 21 + Spring Boot 4.1，默认带 JUnit 6。本机终端默认 JDK 8 时，`"""`（text block）和 `record` 会直接编译失败；IDEA 2024.3 的测试运行器又不认 JUnit 6，点绿三角会报 `NoSuchMethodError`。所以本地一律用 `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw ...` 跑，这是环境问题，不是代码问题。

**依据：**[README](../../README.md)、[pom.xml](../../server/pom.xml)。

### 25. 懒加载和启动时加载，各自适合什么？

**参考回答：**启动时加载响应快，但会把“测试也要连真实模型”带进来；懒加载第一次请求慢一点（本项目要做 24 条 embedding），但测试、冷启动都更干净。项目选择懒加载，是为了不让 `@SpringBootTest` 在启动时调外部接口。

**依据：**[KnowledgeIndexService](../../server/src/main/java/io/github/zzzzain777/retouch/knowledge/KnowledgeIndexService.java)。
