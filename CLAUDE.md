# Claude 项目指令：AI 修图 Agent

本文件与 `AGENTS.md` 配套。先遵守 `AGENTS.md` 的共同规则；本文件只补充 Claude 的工作顺序。项目已在 **C 阶段**，不得从 A 阶段重新建仓库或重做 Java 主路径。

## 阶段与提交

- 按 A → B → C → D 阶段推进。用户把 `IMPLEMENTATION_PROMPT.md` 留在本地，每轮单独发送其中一段阶段指令；**只有收到用户发送的下一阶段指令才进入下一阶段**。`business-agent-project` Skill 只作验收与证据方法参考，不自动切换为其 P00–P14 流程。
- 每阶段（或用户指定的子任务）完成后提交并推送 GitHub，汇报实际 SHA；未成功推送不得声称已提交。Claude 云端连接的 GitHub 账号对本仓库没有写权限，提交与推送经用户 Mac 的本机 git（登录 `zzzzain777`）完成。
- 提交前检查无密钥、个人隐私数据及未经授权公开的上游代码。对外文案注明参考项目与实际复用范围，区分上游已有能力和本仓库中的本人实现。

## 每轮开始

1. 在 `spring-ai-retouch-agent` 代码仓库检查 `git status --short`、HEAD、当前分支与未提交评测文件；不覆盖用户或其他会话的改动。
2. 阅读 `docs/project-state.md`、本轮对应的 `docs/requirements/` 与 `docs/tasks/`、`docs/architecture.md`、`eval/README.md`。文档与代码冲突时，以可运行代码和证据为事实，明确记录差异。
3. 先说明本轮任务的验收标准、相关文件、要运行的最小检查和外部依赖，再实施。若需求不完整，先完成可独立验证的部分。

## C 阶段执行

- 按 `docs/requirements/c-stage-prd.md` 和 `docs/tasks/c-stage-tasks.md` 继续，不把已完成的确定性评测重做成新成果。
- 真实规划模型用 DeepSeek `deepseek-chat`；原版经 `eval/tools/planner_proxy.py` 转发，Java 版经 Spring AI。两边同一冻结数据集与配置，各至少 3 轮。先检查本机已有结果是否完整、可比；满足条件时直接汇总。
- DashScope 图像模型当前未启用。上游 `IMAGE_PROVIDER=mock` 与 Java 本地占位图像服务只能用于 `image_exec` 流程；报告中出图质量写“未测”。
- `server/src/main/resources/application.yml` 只保留 `${DEEPSEEK_API_KEY}`、`${DASHSCOPE_API_KEY}` 等配置入口。真实值留在环境变量或 Git 忽略的 `application-local.yml`。绝不在聊天、命令输出、提交或报告中显示密钥。
- 真实模型评测在用户 Mac 运行；若当前执行环境无法访问 DeepSeek，标明阻断并保留可复现命令。不要用 Mock 报告冒充真实评测。

模型选择的变更记录：原 `CLAUDE.md` 规定规划模型沿用上游的 DashScope/百炼。用户 2026-11-05 改为规划与评测基线都用 DeepSeek，DashScope 只留给图像模型（原因见 `docs/architecture.md` 与 `docs/project-state.md`）。

## 每项任务完成

运行相关检查，核对 `git diff` 和密钥/大文件/来源风险，记录测试命令、结果、环境、报告路径与限制；更新 `docs/project-state.md`、必要时更新 `docs/claim-ledger.md`。按用户既定要求提交并推送该阶段产物，汇报实际 SHA；推送失败时明确说明。进入 D 阶段需要 C 阶段的真实结论与剩余限制已清楚记录。

本项目的开发方法按 `docs/development-workflow.md`：需求与规则 → 架构/接口 → 小任务 → 实现 → 测试 → Code Review → 证据与提交。不要在未看现有实现时整包重写。

## 评测与证据规则（自原 `CLAUDE.md` 保留）

评测按“指标、数据集、执行过程、运行监测”四层建设（见 `eval/README.md`），先固定上游基线，再以相同样例验证 Java 改造。

1. 样例覆盖主路径、边界、失败、越权和旧流程回归。每个样例记录输入图片、用户指令、初始图层/选区、允许的结果约束、判定方式和数据集版本；允许多条合理工具路径，不把唯一轨迹当成唯一正确答案。
2. 程序断言用于工具是否存在、参数合法、依赖无环、需要确认的操作是否等待、输出尺寸/透明度、任务状态与资源归属。生成图是否符合主观意图用固定量表与人工复核；需要模型裁判时记录裁判版本，并与人工样本校准。图像生成有随机性，不用逐像素相等判断质量。
3. 记录请求 ID、模型/Prompt 版本、计划及校验、工具调用/结果、耗时、Token/费用、错误分类和最终资源；保护密钥与图片隐私。
4. 配对比较固定数据集、模型与配置，报告样本数、成功数、失败原因、延迟和成本。没有真实用户流量时，线上指标只定义采集口径，不写生产 KPI。

## 待解决问题与验收证据（自原 `CLAUDE.md` 保留）

各项当前状态以 `docs/project-state.md` 为准。

| ID | 问题 | 需要留下的证据 |
| --- | --- | --- |
| Q1 | 上游版本、授权及实际工具/API 清单 | commit SHA、来源说明、工具与接口对照表、许可核查记录 |
| Q2 | 原版启动与测试基线复现 | 启动命令、测试输出、关键画布与 Agent 流程记录 |
| Q3 | Java/Python 服务边界、图片传输与任务状态权威 | 架构图、接口契约、同一请求跨服务 Trace |
| Q4 | 多步计划校验、确认、重试、取消和失败恢复在迁移时失真 | 状态转换表、正常与异常用例、端到端运行记录 |
| Q5 | 手动 API 与 Agent 工具产生两份参数规则 | 单一工具规格、同参数的双入口测试 |
| Q6 | 会话、工具消息、图层/选区及资源修订的持久化边界 | 数据模型、重启后恢复与权限隔离测试 |
| Q7 | 长任务进度、SSE、超时、重复提交与取消语义 | 队列/Worker 流程、事件顺序和故障注入记录 |
| Q8 | 评测集中在像素输出，缺 Agent 行为评测 | 冻结数据集、评分器、真实模型的上游与改造版配对报告 |
| Q9 | 图像生成具有随机性，不能用逐像素相等判断质量 | 可程序断言的约束、人工评分量表、复核记录 |
| Q10 | 简历中的技术点和数字需本人实现证据 | claim-ledger、代码/测试/真实调用 Trace 链接、分子分母与测量条件 |

## 参考来源

- 上游项目（只读研究与功能基线）：<https://github.com/yuyuanweb/ai-picture-editor>
- 工作方法参考：<https://github.com/yangluxin613/business-agent-project-skills/blob/main/business-agent-project/SKILL.md>
- Agent 开发流程视频：<https://www.douyin.com/video/7699063875946708672>
- Agent 评估指标视频：<https://www.douyin.com/video/7694951840383927473>
- SDD 文档组织参考视频（C 阶段接入）：<https://www.douyin.com/video/7704966037086752051>
