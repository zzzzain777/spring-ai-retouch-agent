# AI 修图 Agent：所有编码助手共用规则

## 项目目标与现阶段

这是用于 Java / Spring AI Agent 应用开发岗位展示的独立 AI 修图项目。公开仓库：`https://github.com/zzzzain777/spring-ai-retouch-agent`（用户账号 `zzzzain777` 下的独立仓库；不 Fork 上游、不向上游提交 PR）。当前已进入 **C 阶段：同口径 Agent 行为评测与改进**，A/B 阶段已建立功能基线和 Java 主路径。开始任务前读取 `docs/project-state.md` 并检查本机 `git status`、HEAD；文档状态可能落后于本机未提交工作。

费用原则：用户明确要求“怎么省钱怎么来”。付费云服务与模型调用一律取最省钱的做法——这是目前未启用 DashScope 图像模型、未用阿里云 RDS、真实规划调用只跑必要次数的原因；新增付费调用前先说明用量与理由。

## 架构边界

- `server/`：Java 21、Spring Boot 4.1.2、Spring AI 2.0.2、Spring WebMVC。Java 是外部 API、业务状态、Agent 计划校验与执行调度、工具规格和权限的权威服务。
- `web/`：React 18、TypeScript、Vite。画布、图层、选区、工具面板、Agent 对话、文生图、批量处理；工具参数表单读 Java 的 `/api/tools` Schema。
- `image-service/`：Python FastAPI，按 Java 给定的对象键处理像素与可选图像模型调用；不持有会话或 Agent 状态。
- PostgreSQL 17 存业务数据、计划、工具任务；Redis 存选区与进度通知；S3 兼容对象存储放图片。当前本地使用 `docker-compose.yml`，未采购 RDS。
- 规划模型默认是真实 DeepSeek `deepseek-chat`，经 Spring AI OpenAI 兼容适配器调用。图像模型 DashScope 尚未真实接入；涉及其 9 个工具的测试只证明占位服务链路，不证明出图质量。
- 版本锁定为 Java 21、Spring Boot 4.1.2、Spring AI 2.0.2；变更版本须在 `docs/architecture.md` 记录原因。
- Spring AI JDBC Chat Memory 会过滤工具调用与工具响应消息，不能当作完整工具轨迹存储；计划、步骤、工具运行和规划轨迹由 Java 自己的表持久化。
- `@Async` 只能用于局部异步，不能单独证明进程重启后任务可恢复；Java 与 Python 之间只有 `tool_runs` 一个任务状态权威。

## 开发与证据规则

1. 先读 `docs/requirements/product-prd.md`、当前阶段 PRD、`docs/business-rules.md`、`docs/architecture.md` 和相关 API 契约，再读代码；按 `docs/development-workflow.md` 执行。
2. 规划阶段只取得模型的工具调用提议。Java 校验工具、参数、依赖和修订号；多步计划经用户确认后由 Java 调度。不得开启修图工具的自动执行来绕过确认门。
3. 一份 `ToolRegistry`/参数类型是手动 API、Agent、前端 Schema 的共同来源。修改规则时同时检查三端与评测。
4. 任何“已实现”“已验证”必须给出命令、输出、提交或报告路径；Mock、脚本替身、占位图像与真实模型结论分开。状态只用五档：设计 / 已实现 / 已验证 / 待验收 / 失败。`docs/project-state.md` 记各项状态与证据，`docs/claim-ledger.md` 把每条简历陈述映射到本人改动、代码、测试与测量结果。
5. `eval/datasets/agent-behavior-v1` 已冻结。不得修改旧样例或 MANIFEST 以改善通过率；新增样例用新版本。
6. 不把真实密钥、隐私图片或未经授权的上游源码提交到公开仓库。密钥只在环境变量或已忽略的 `application-local.yml`；日志与报告不得含密钥。需要凭据时请用户自行配置到环境变量或未跟踪文件，不要让用户把密钥贴进聊天。缺密钥或网络不可达时相关项标“待验收”，不得用 Mock、本地模型或伪造数据代替真实链路验收。
7. 不把 RAG、pgVector、MCP、LangChain、LangGraph、WebFlux、SAM、OCR 写成当前项目已实现技术。它们须以实际代码和验证证据为准；只有具体用户场景、代码和验收证据齐备才加入技术栈。若将来引入 pgVector，必须接真实 PostgreSQL `vector` 扩展与真实 Embedding 模型，验证写入、检索、向量维度及失败路径。

## 常用检查

- Java：`cd server && ./mvnw test`（需要 Docker；某些集成测试还需要 `uv`）。
- Python：`cd image-service && uv run pytest`。
- 前端：`cd web && npm ci && npm run build`；页面走查见 `web/e2e/walkthrough.mjs`。
- 评测：见 `eval/README.md`；真实 DeepSeek 配对运行只在能访问 API 的用户 Mac 上执行。

公开仓库的参考来源、独立实现范围和许可核查见 `README.md`、`docs/upstream-baseline.md`。简历与面试只陈述本人实际完成、能解释和有证据的工作。
