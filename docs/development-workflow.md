# 开发流程：从进行中的 C 阶段接入规范驱动开发

本流程对应视频展示的「上下文 → 需求/业务规则 → 架构/API → 任务拆解 → 开发 → 测试 → Code Review」。它只约束**后续工作**；不改写 A/B 阶段历史，也不要求重建项目目录。

## 1. 进入任务

1. 检查本机 `git status --short`、HEAD、未提交文件及报告。保留已有成果，不覆盖别的会话或用户改动。
2. 读根目录 `AGENTS.md`、`CLAUDE.md`、`docs/project-state.md`；按任务读对应 PRD、`business-rules.md`、`architecture.md`、`api-contract.md` 和实际代码。
3. 对照 PRD 列出本轮**一个可验收任务**的输入、输出、边界、失败路径与检查命令。对现有项目先验证是否已完成，已完成则引用证据，不重复实施。

## 2. 设计与实现

- 需求影响用户行为时，先更新对应 `docs/requirements/`；影响数据权威、任务状态或服务边界时更新 `docs/architecture.md`；影响 HTTP 契约时更新 `docs/api-contract.md` 与调用方。
- 修改 `server/` 时沿用现有 ToolRegistry、PlanValidator、Worker 和 Flyway 约定；修改 `web/` 时沿用 `web/src/api.ts` 与 Schema 驱动工具表单；修改 `image-service/` 时保持无状态与对象键边界。
- 对确定性行为编写/运行有意义的测试。涉及模型效果时跑真实模型评测并保存轨迹；脚本替身只验证控制流。
- 一次变更要能解释“哪个真实失败促成、改了什么、同一口径复测结果如何”。

## 3. 测试层级

| 变更 | 最小检查 | 完成前附加检查 |
| --- | --- | --- |
| Java API/Agent/任务 | 相关 JUnit 测试 | `cd server && ./mvnw test`；跳过项明确记录。 |
| Python 图像计算 | 相关 pytest | `cd image-service && uv run pytest`。 |
| React 前端 | `cd web && npm run build` | 相关页面走查；变动到主流程时运行 `npm run e2e` 并检查截图/日志。 |
| Agent 规划质量 | `eval/run_agent_eval.py` 对应样例 | 原版/Java 版同集真实 DeepSeek 多轮配对与 `aggregate.py`。 |
| 文档 | 路径、命令、版本、链接、状态核对 | `git diff --check`，检查文档间无矛盾及无密钥。 |

## 4. Code Review 要点

审查者按 `docs/reviews/c-stage-review.md` 核对需求覆盖、边界条件、状态机、权限、数据一致性、Agent 规划零副作用、模型/Mock 证据类型、评测分母与环境、密钥与来源。报告只记录可定位问题与实际证据；没有执行的检查标“未运行”。

## 5. 完成与提交

记录本轮任务 ID、提交前后 HEAD、测试命令和结果、评测报告路径、已知限制，更新 `docs/project-state.md`。只有证据充分的简历陈述才进入 `docs/claim-ledger.md`。按用户既定要求每阶段提交并推送 GitHub，报告 SHA；推送失败时保留本地提交并说明阻断。
