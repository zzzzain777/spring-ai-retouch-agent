# D 阶段最终验收记录（用户 Mac）

运行时间：2026-11-07 约 12:57–13:01（UTC）；仓库 HEAD `ee859f4`（D 阶段文档提交前）；Java 21（Corretto 21.0.12）、OrbStack Docker、uv 0.13.7、Node 22.23.4。只运行、不改代码。

## 1. 自动化测试

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| Java | `cd server && ./mvnw -s <本机 Maven 代理 settings> test` | 17 个测试类、82 个测试，失败/错误 0，跳过 0；BUILD SUCCESS（需要 Docker 与 uv） |
| Python 图像服务 | `cd image-service && uv run pytest -q` | 39 passed, 1 warning in 3.26s |
| 前端 | `cd web && npm run build` | tsc 通过，vite 构建成功（✓ built in 407ms） |

### Java 各测试类

| 测试类 | 测试 | 失败 | 错误 | 跳过 |
| --- | --- | --- | --- | --- |
| AuthAndAssetTests | 5 | 0 | 0 | 0 |
| ImageToolTests | 2 | 0 | 0 | 0 |
| ModelKeyMissingTests | 1 | 0 | 0 | 0 |
| QueueCancelTests | 1 | 0 | 0 | 0 |
| RetouchAgentApplicationTests | 3 | 0 | 0 | 0 |
| SessionExportTests | 4 | 0 | 0 | 0 |
| SessionTests | 6 | 0 | 0 | 0 |
| ToolTests | 6 | 0 | 0 | 0 |
| WorkerFailureTests | 1 | 0 | 0 | 0 |
| agent.AgentImageTests | 2 | 0 | 0 | 0 |
| agent.AgentTests | 13 | 0 | 0 | 0 |
| agent.ModelToolTests | 6 | 0 | 0 | 0 |
| agent.PlanRulesTest | 12 | 0 | 0 | 0 |
| agent.PlannerNoExecutionTest | 7 | 0 | 0 | 0 |
| agent.SelectionTests | 4 | 0 | 0 | 0 |
| tool.DocumentToolsTest | 4 | 0 | 0 | 0 |
| tool.ToolSchemasTest | 5 | 0 | 0 | 0 |

## 2. 真实 DeepSeek 页面走查

- 证据：`docs/evidence/ui-walkthrough/mac-deepseek-d/`（15 张截图、`summary.json`、`walkthrough.log`；批量导出的 zip 未入库）。
- 条件：真实 DeepSeek 规划（`deepseek-chat`，密钥来自本机未跟踪配置）、本机 Chrome（Playwright `channel=chrome`）、本地 Python 图像服务、PostgreSQL/Redis（compose）、moto 作 S3；DashScope 图像模型**未配置**，脚本中两个模型工具步骤断言的是“未配置时明确失败”。
- 结果：15/15 步通过，第 1 次运行即通过，**重试 0 次**，运行期间服务端规划异常 0 次；耗时 16.5 s；浏览器控制台错误 0，非预期 HTTP 错误 0。无关请求一步的规划轨迹：模型 `deepseek-flash`（请求 `deepseek-chat`）、814 ms、token 4532 / 21。
- 覆盖：登录、上传建会话、文档工具（翻转）、本地图像工具经队列与 SSE（调色 100%）、撤销重做、笔刷选区、模型工具未配置时失败、去背景拆层、投放尺寸进图片墙、自然语言多步计划停在等待确认、确认后执行、取消、无关请求不产生计划、批量处理、文生图未配置时失败。
- 不在走查脚本里的“单步重试”由 `AgentTests.failedStepBlocksTheRestAndRetryRunsItAgain` 覆盖；取消排队中的任务由 `QueueCancelTests.canceledQueuedRunNeverExecutes` 覆盖；运行中任务的取消（`tool_runs.cancel_requested` 标记，Worker 在检查点响应）已实现，但**没有专门的自动化测试**。
