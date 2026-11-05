# AI 修图 Agent 改造项目指令

## 最终目的

本项目的目的是参考上游 AI 修图 Agent，做出用户自己的、用于应聘中大厂 Java / Spring AI Agent 应用开发岗位的**独立简历项目**。最终成果放在用户账号下的新仓库，**不向上游提交 PR，也不把 Fork 当作最终项目**。核心工作是把原 Python/FastAPI/LangGraph 承担的业务后端与 Agent 主路径重构为 Java/Spring AI：Java 负责 API、规划与执行、工具治理、状态和评测；Python 仅在有复用权限时保留图像处理/图像模型服务，否则独立实现或选用有授权的替代能力。必须交付可运行、可演示、可复现评测的项目，让用户能在面试中讲清参考了什么、自己改了什么、为何这样设计、效果如何验证。简历中的技术、数据和个人贡献都必须有代码、提交记录、测试或评测报告支持；公开说明参考来源，不把上游已有代码说成本人原创。

## 项目目标和已确定的边界

- 参考项目：<https://github.com/yuyuanweb/ai-picture-editor>。上游只作为只读研究和功能基线；本项目在独立仓库实施，不向上游提交代码或 PR。公开材料须说明参考来源、实际复用范围和本人实现的部分。
- 目标岗位：Java / Spring AI 的 Agent 应用开发。Java 是对外 API、Agent 规划与执行、工具治理、任务状态和业务数据的权威服务。
- Python 图像服务边界：Pillow 调色等像素处理、rembg 抠图、SAM 点选分割及 DashScope 图像生成/编辑调用可以继续由 Python 执行；若沿用上游的具体代码，先取得相应复用权限。Python 不再决定 Agent 计划或持有另一套独立业务状态。
- 在新项目中实现与上游对应的修图能力、画布交互、多步计划、人工确认、单步重试和取消。先核对原有 API 与数据语义，再迁移功能；不得以技术栈改造为由静默删功能，也不能未经授权直接复制上游前端或 Python 源文件到公开仓库。
- 评测按“指标、数据集、执行过程、运行监测”四层建设，先固定上游基线，再以相同样例验证 Java 改造。简历只写已实现且有证据的结果。

## Claude 项目中的文件与代码仓库

- 只需把本 `CLAUDE.md` 上传到 Claude 项目的 Library；`IMPLEMENTATION_PROMPT.md` 留在用户本地，用户每轮单独发送其中一段 A、B、C 或 D 阶段指令。云端线程先在项目文件目录 `/mnt/project-files` 查找本文件，关联代码仓库后也检查仓库根目录。
- A 阶段先只读检查上游代码，再由 Claude 在用户账号 <https://github.com/zzzzain777> 下新建并关联一个公开 GitHub 仓库，作为独立项目仓库；不 Fork 上游，不向上游提交 PR。把 `CLAUDE.md` 放到新仓库根目录；仓库根目录的 `CLAUDE.md` 会作为仓库指令由云端线程自动加载。先核查上游授权，未确认公开复用权限前不要将上游代码上传到公开仓库。若 Claude 缺少创建或关联权限，明确告诉用户需要完成的操作，不把临时沙箱中的代码当作已保存的项目成果。
- 本项目以 A–D 阶段指令为执行顺序。`business-agent-project` Skill 仅作验收与证据方法参考，不自动切换为其 P00–P14 流程。

## 当前事实，开始编码前重新核实

编写本指令时，本地工作区尚未导入上游源码，也没有 Java 实现、运行测试或评测结果；开始各阶段前须重新检查当前状态。以下是 2026-11-04 对上游默认分支的检查结果，阅读上游时应记录实际 commit SHA 并以该版本为准。

- README 称 21 种工具，但 `backend/app/tools/__init__.py` 的 `SPECS` 当前列出 22 项；对外先称“二十余种”，以导入版本清单定最终数字。
- `backend/app/agent/graph.py` 只做 `plan → verify`；`backend/app/agent/plan.py` 负责计划结构与依赖检查；`backend/app/services/agent.py` 负责确认、执行推进、重试和取消。不能把原项目描述成由 LangGraph 自动执行整个工具循环。
- `backend/app/eval/` 主要评测确定性图像处理结果，尚不能替代指令到计划、工具与最终任务的行为评测。
- `backend/app/edits/pixels.py` 的本地抠图用 rembg；`backend/app/edits/segment.py` 用 SAM 点选生成遮罩。`backend/app/providers/dashscope.py` 通过远程 API 生图和编辑；局部消除、扩图等不能笼统称作“Python 本地图像模型”。
- 原架构使用 PostgreSQL、Redis、MinIO、异步 Worker 和 SSE。迁移时查明各自的真实数据流和前端契约。
- 截至 2026-11-04，上游 GitHub 仓库元数据的 `license` 为 `null`。A 阶段重新核查仓库其他位置或作者声明；若仍无复用授权，新公开仓库只提交本项目原创实现与文档，不复制上游源码、前端资源或 Python 服务文件。需要沿用具体代码时先取得授权；功能和接口行为可作研究基线。参考 [GitHub 许可证说明](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/licensing-a-repository)。

## 技术约束

- 优先采用 Java 21、Spring Boot 4.1.x、Spring AI 2.0.x 的兼容组合，并锁定实际版本；Spring AI 2.0.x 官方支持 Spring Boot 4.0.x / 4.1.x：<https://docs.spring.io/spring-ai/reference/getting-started.html>。版本变更须在架构记录里解释。
- 保留上游的“模型提议计划 → 服务端校验 → 必要时用户确认 → 按依赖执行”语义。Spring AI `ToolCallingAdvisor` 默认会自动执行工具，不能在待确认计划上直接启用自动执行：<https://docs.spring.io/spring-ai/reference/api/tools/tool-calling-advisor.html>。可以用 Spring AI 生成结构化计划，再由 Java 的计划服务和状态机决定执行。
- 工具注册表只有一个权威定义，包含名称、描述、参数 Schema、权限/确认要求和执行器。Agent 与手动 API 共用同一业务服务和校验；`@Tool` 或 MCP 只是适配层，不能再复制一套规则。
- Java 持久化会话、计划、步骤、工具运行和资源修订。不要把 JDBC Chat Memory 当成完整工具轨迹存储：官方实现会过滤工具调用和工具响应消息，见 <https://docs.spring.io/spring-ai/reference/api/chat-memory.html>。若使用它，仅负责适合的对话窗口；完整轨迹另行持久化。
- pgVector、RAG、MCP、上下文压缩、多 Agent 均是可选扩展。只有具体用户场景、代码和验收证据齐备，才加入技术栈与简历。若使用 pgVector，必须接真实 PostgreSQL `vector` 扩展和真实 Embedding 模型，验证写入、检索、向量维度及失败路径；pgVector 本身不需要模型 API Key，远程 Embedding 模型需要。
- 图像任务需要明确超时、重试、幂等、取消、进度与失败状态。`@Async` 可用于局部异步工作，但不能单独证明任务在进程重启后可恢复。Java 与 Python 间只保留一个任务状态权威来源。
- 规划模型沿用上游 DashScope/百炼服务。Java 优先通过与 Spring AI 2.0.x 兼容的模型适配调用 DashScope 的 OpenAI 兼容接口；若选原生 DashScope Java SDK/HTTP 或 Spring AI Alibaba，先核实与锁定版本的兼容性并记录原因。Python 保留上游实际图像模型 API。不要以本地模型替代这些真实服务来完成验收。
- Java 项目在 `application.yml` 声明 DashScope 模型、接口地址和 `api-key: ${DASHSCOPE_API_KEY}` 等配置入口。实际密钥由执行环境提供；本地若使用 `.env` 或 `application-local.yml` 存密钥，先把这些文件加入 `.gitignore`，并提供不含真实密钥的示例配置。不要把真实密钥写进已跟踪的 `application.yml`。
- 当原版基线、Java 模型调用、真实图像处理或 Embedding 集成需要凭据时，明确请用户提供 DashScope API Key，并引导用户将它配置为执行环境中的 `DASHSCOPE_API_KEY`：Claude 云端线程使用项目所选云端环境的 Environment variables，本地执行可用环境变量或未提交的 `.env`。同时确认云端环境能访问所选区域的 DashScope API 域名。不要要求用户把密钥直接粘贴进聊天，也不得把密钥写入代码、提交记录、测试报告或输出日志。缺少密钥或网络不可达时继续做独立的静态分析和确定性单元测试，相关集成项标“等待 API Key / 待验收”，不能用 Mock、本地模型或伪造向量代替真实链路验收。
- 确定性单元测试可以使用 Mock 隔离外部依赖；项目主路径、模型效果与评测报告必须另有真实 DashScope 和真实依赖的运行证据。详见 Spring AI 的 [OpenAI 兼容模型配置](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html)、阿里云 [兼容接口地址](https://help.aliyun.com/en/model-studio/base-url) 与 [pgVector 要求](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html)。

## 待解决问题与验收证据

| ID | 问题 | 需要留下的证据 |
| --- | --- | --- |
| Q1 | 上游版本、授权及实际工具/API 清单未在本地固定 | commit SHA、来源说明、工具与接口对照表、许可核查记录 |
| Q2 | 原版启动与测试基线尚未复现 | 启动命令、测试输出、关键画布与 Agent 流程记录 |
| Q3 | Java/Python 服务边界、图片传输与任务状态权威未落地 | 架构图、接口契约、同一请求跨服务 Trace |
| Q4 | 多步计划校验、确认、重试、取消和失败恢复可能在迁移时失真 | 状态转换表、正常与异常用例、端到端运行记录 |
| Q5 | 手动 API 与 Agent 工具可能产生两份参数规则 | 单一工具规格、同参数的双入口测试 |
| Q6 | 会话、工具消息、图层/选区及资源修订的持久化边界未定 | 数据模型、重启后恢复与权限隔离测试 |
| Q7 | 长任务进度、SSE、超时、重复提交与取消语义未验证 | 队列/Worker 流程、事件顺序和故障注入记录 |
| Q8 | 当前评测集中在像素输出，缺 Agent 行为评测 | 冻结数据集、评分器、真实模型的上游与改造版配对报告 |
| Q9 | 图像生成具有随机性，不能用逐像素相等判断质量 | 可程序断言的约束、人工评分量表、复核记录 |
| Q10 | 简历中的技术点和数字尚无本人实现证据 | claim-ledger、代码/测试/真实调用 Trace 链接、分子分母与测量条件 |

## 评测和证据规则

1. 先定义主路径、边界、失败、越权和旧流程回归样例。每个样例记录输入图片、用户指令、初始图层/选区、允许的结果约束、判定方式和数据集版本；允许多条合理工具路径，不把唯一轨迹当成唯一正确答案。
2. 程序断言用于工具是否存在、参数合法、依赖无环、需要确认的操作是否等待、输出尺寸/透明度、任务状态与资源归属。生成图是否符合主观意图使用固定量表与人工复核；需要模型裁判时记录裁判版本，并与人工样本校准。
3. 记录请求 ID、模型/Prompt 版本、计划及校验、工具调用/结果、耗时、Token/费用、错误分类和最终资源。保护密钥及图片隐私。
4. 配对比较固定数据集、模型与配置，报告样本数、成功数、失败原因、延迟和成本。没有真实用户流量时，线上指标只定义采集口径，不写生产 KPI。
5. `project-state.md` 记录每阶段的状态、命令、环境、证据和剩余问题；`claim-ledger.md` 把每条简历陈述映射到本人改动、代码、测试与测量结果。状态只用“设计 / 已实现 / 已验证 / 待验收 / 失败”。

## 工作方式

- 读取本文件和用户本轮发送的阶段指令后先盘点现有工作区；若源码尚未导入，从上游取得并固定版本。不要把上游 README 当作代码事实。
- 按可运行的纵向闭环推进：基线 → 架构与契约 → Java Agent 主路径 → Python 图像调用 → 旧功能回归 → 行为评测 → 真实链路验证 → 简历证据。A、B、C、D 每阶段完成后，由 Claude 将该阶段实际产物、`project-state.md` 和证据提交并推送到上述 GitHub 仓库，汇报提交 SHA 与链接；提交前检查无密钥、个人隐私数据及未经授权公开的上游代码。只有收到用户发送的下一阶段指令才进入下一阶段；缺少必需密钥、授权或外部资源时标明具体阻断，未成功推送不得声称该阶段已提交到 GitHub。
- 不把设计、Mock 通过、局部测试或未运行的命令写成上线结果。对外文案注明参考项目及实际复用范围，区分上游已有能力和新仓库中的本人改造；无授权时不称已复制上游代码进行二次开发。

## 参考来源

- 上游项目：<https://github.com/yuyuanweb/ai-picture-editor>
- 工作方法参考：<https://github.com/yangluxin613/business-agent-project-skills/blob/main/business-agent-project/SKILL.md>
- Agent 开发流程视频：<https://www.douyin.com/video/7699063875946708672>
- Agent 评估指标视频：<https://www.douyin.com/video/7694951840383927473>
