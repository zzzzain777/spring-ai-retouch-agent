# claim-ledger

把每条简历或面试陈述映射到本人改动、代码、测试与测量结果。状态只用：设计 / 已实现 / 已验证 / 待验收 / 失败。

| ID | 陈述 | 本人改动 | 代码 / 提交 | 测试 | 测量结果（分子/分母、条件） | 状态 |
| --- | --- | --- | --- | --- | --- | --- |
| C1 | 自建 44 例冻结评测集与黑盒运行器，先测上游基线再测改造版，数据集 sha256 冻结且早于实现代码 | 评测集、RUBRIC、运行器全部自写 | `0ad7240`（冻结）、`eval/` | 运行器启动即校验 MANIFEST，被改即拒绝运行 | 原版确定性基线 17/17（`eval/reports/upstream-49f6ad8-noplanner-*/`） | 已验证 |
| C2 | 用冻结集在真实失败上做了一次「发现→修复→同集复测」闭环（会话导出接口缺失） | Python 渲染算子 + Java 导出接口 + 共用画布构造 | `b3acd90` | `SessionExportTests` 4 个、Java 全量 82、Python 39 | 修复前 16/17（R06 fail，`java-51a9086-noplanner-20261105T135400Z`）→ 修复后 17/17（`java-exportfix-noplanner-20261105T140823Z`），同数据集同命令 | 已验证 |
| C3 | 原版与 Java 版同模型（deepseek-chat）同数据集配对评测，各 3 轮，含 token 成本 | 运行器轨迹采集 + `aggregate.py` 汇总 | `b3acd90`；报告 `eval/reports/paired-c1/` | — | 用户 Mac，`llm,image_exec`：原版每轮 33/43；Java 42/43、38/43、43/43（6 个异常均为 DeepSeek 接口超时，补跑 6/6 通过）；硬门 confirm_gate 原版 45/123 vs Java 113/113、no_side_effect 12/36 vs 35/35、confirm_flow 18/42 vs 38/38、authz_isolation 48/51 vs 51/51、regression 81/81 vs 81/81；token 原版 421,032/4,418（87 次），Java 367,196/4,809（81 次）。原版失败根因：87 次规划调用 0 次返回多步。两版运行日期不同（11-05 / 11-07） | 已验证 |
| C4 | 出图质量 | —（未接真实图像模型，省钱决定） | — | — | 未测：占位图像接口只验流程，RUBRIC §4 人工量表无法打分 | 待验收 |
| J1 | 用 Java 21 / Spring Boot 4.1 / Spring AI 2.0 独立重写 Agent 主路径：模型只产出工具调用提议，Java 校验（工具存在、参数同源校验、依赖、环、≤ 8 步）、多步先确认、确认后显式调度 | `SpringAiPlanner`（关闭 ToolCallingAdvisor 自动注册，工具回调被调用即抛异常）、`PlanValidator`、`AgentService` | `server/src/main/java/.../agent/`；`134105d`、`0741ae4` | `AgentTests` 13、`PlanRulesTest` 12、`PlannerNoExecutionTest` 7 | 真实 DeepSeek 配对评测 Java 三轮硬门 confirm_gate 113/113、no_side_effect 35/35、confirm_flow 38/38（`eval/reports/paired-c1/`） | 已验证 |
| J2 | 22 个工具一份规格，手动接口、Agent 规划与前端表单共用同一参数类型与 JSON Schema | `ToolRegistry`/`ToolSpec`/`ToolParams`/`ToolSchemas`；前端读 `GET /api/tools` 生成表单 | `.../tool/`、`web/src/`；`2c5d2af`、`51a9086`、`34aa219` | `ToolTests`（`hasSize(22)`）、`ToolSchemasTest` 5、评测 R04 | 评测 regression 81/81（两版） | 已验证 |
| J3 | 以 PostgreSQL `tool_runs` 表作任务队列：`FOR UPDATE SKIP LOCKED` 领取、租约过期恢复、attempt 级输出前缀、迟到结果丢弃、Redis 通知 + SSE（先订阅后补快照、心跳） | `RunWorker`、`RunStreamController`、Python 只写 `runs/{run_id}/{attempt}/` | `2c5d2af` | `QueueCancelTests`、`WorkerFailureTests`、`ImageToolTests` 2（含租约过期重排）、`AgentImageTests` 2 | 页面走查 SSE 步骤通过（`docs/evidence/ui-walkthrough/mac-deepseek-d/`）；单进程 2 线程，未做多实例/压力测试 | 已验证 |
| J4 | 独立实现 React + TypeScript 前端，真实 DeepSeek 页面走查覆盖登录到批量处理 | `web/` | `34aa219` | `npm run build`；`web/e2e/walkthrough.mjs` | 15/15（B10 `8232ede`；D 阶段复验 15/15、重试 0） | 已验证 |
| J5 | 针对真实 DeepSeek 只回一步的问题改提示词并加可选追问轮，用数据决定默认关闭追问 | `PlannerPrompt`、`retouch.planner.follow-up-rounds` | `0741ae4` | `docs/evidence/real-planner-check/0741ae4-r0.json`、`-r1.json` | 5 条冒烟各 1 次：追问 0 轮 5/5、5562 ms、prompt 11,235；追问 1 轮 5/5、7519 ms、prompt 20,829（效果相同、token +85%） | 已验证（样本 5 条） |
| J6 | 自动化测试与验收 | 测试代码自写 | `server/src/test/`、`image-service/tests/` | D 阶段最终验收 | Java 82/82（17 类，0 跳过）、Python 39 passed（`docs/evidence/d-acceptance/README.md`） | 已验证 |

J1–J6 为拟进简历的功能陈述（D 阶段补入）。参考项目与独立实现范围见根目录 `README.md`“参考来源与复用范围”；不陈述生产效果或线上性能。
