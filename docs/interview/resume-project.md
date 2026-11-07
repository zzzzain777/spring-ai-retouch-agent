# 简历项目条目

> 口径：每条数字都能在 `docs/claim-ledger.md` 找到代码、提交、测试或报告；运行地点为本人 Mac 本地环境，**项目未上线**，不陈述生产效果或线上性能。

## AI 修图 Agent（Java / Spring AI 重构）　个人项目　2026.11

**项目说明**：参考开源项目 yuyuanweb/ai-picture-editor（Python/FastAPI，研究基线固定在 commit `49f6ad8`）的功能与接口行为，用 Java 独立重写 Agent 后端，并独立编写 Python 图像服务、React 前端和评测体系。上游没有开源许可证，本仓库不含其任何代码、Prompt 或素材，只把它作为功能基线和对照组。仓库：github.com/zzzzain777/spring-ai-retouch-agent

**技术栈**：Java 21、Spring Boot 4.1、Spring AI 2.0（OpenAI 兼容接口接 DeepSeek）、PostgreSQL 17 + Flyway、Redis、S3 兼容对象存储（本地用 moto）、Python FastAPI + Pillow/OpenCV、React 18 + TypeScript + Vite、Testcontainers、Playwright

**主要工作**：

- **规划与执行分离的 Agent 主路径**：Spring AI 只用来取得模型的工具调用提议（关闭 ToolCallingAdvisor 自动执行，工具回调一旦被调用就抛异常），由 Java 校验工具、参数、依赖、环和步数（≤ 8）；单步直接执行，多步落库后等用户确认，确认时校验画布修订号，支持取消、失败步骤重试和 Idempotency-Key 去重，每轮记录模型、耗时、token 与原始工具调用。
- **一份工具规格三处复用**：迁移上游全部 22 个修图工具，参数类型同时生成手动接口校验、交给模型的 JSON Schema 和前端表单，消除手动入口与 Agent 入口两套规则。
- **可恢复的异步任务**：以 PostgreSQL `tool_runs` 表作队列，`FOR UPDATE SKIP LOCKED` 领取、租约续期与过期恢复，按 attempt 隔离输出路径并丢弃迟到结果；Redis 推送进度，SSE 先订阅后补数据库快照，保证不丢终态。
- **评测驱动的对比与修复**：自建并冻结 44 例 Agent 行为评测集（先于 Java 实现冻结），用真实 DeepSeek 对原版与 Java 版同集各跑 3 轮。Java 版在“确认前不得派发”断言上 113/113（原版 45/123），“未确认无副作用” 35/35（原版 12/36），确认流程 38/38（原版 18/42），越权隔离 51/51（原版 48/51），旧流程回归两版均 81/81；原版有 8 例三轮都失败、Java 版三轮都通过。通过分析 87 次规划调用定位原版根因：模型从未一次返回多步。评测中还发现会话导出接口缺失，修复后同集复测从 16/17 到 17/17。
- **用数据做配置取舍**：针对真实 DeepSeek 只回一步的问题改写规划提示词并加可选追问轮；5 条真实冒烟中，开启追问与关闭效果相同（均 5/5），但 prompt token 多 85%（20,829 vs 11,235）、耗时多 35%（7.5 s vs 5.6 s），因此默认关闭。
- **质量保障**：Java 82 个测试（Testcontainers 起真实 PostgreSQL/Redis/S3 与 Python 服务）、Python 39 个测试，真实 DeepSeek 页面走查 15/15。

**如实说明的局限**：图像生成/编辑模型（DashScope）未接入，9 个模型类工具只验证了调用链路，出图质量未测；点选为圆形区域近似，无 OCR 文字拆层；前端无画布拖拽手势与前后对比；Worker 单进程两线程，未做多实例与压力测试；JWT 登出不吊销；孤儿对象未清理；两版评测运行日期不同（原版 11-05、Java 11-07），Java 原始轮次有 6 次 DeepSeek 接口超时/500（补跑 6/6 通过，原记录保留）。

## 一句话版本

参考开源 AI 修图项目，用 Java 21 + Spring AI 独立重写 Agent 后端（规划只取提议、Java 校验与确认后调度、数据库队列与 SSE），并以自建 44 例冻结集与真实 DeepSeek 做原版/改造版 3 轮配对评测：确认前零派发断言 113/113（原版 45/123）。
