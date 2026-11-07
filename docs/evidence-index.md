# 证据索引

所有可复查证据的位置。数字的口径与条件以各报告原文为准；本表只做导航。运行地点：未注明的均为用户 Mac（真实 DeepSeek 只能在 Mac 上访问）。

## 1. 自动化测试

| 证据 | 位置 | 结果 |
| --- | --- | --- |
| D 阶段最终验收（Java / Python / 前端构建 / 页面走查） | `docs/evidence/d-acceptance/README.md` | Java 17 类 82 个测试全部通过、0 跳过；Python 39 passed；`npm run build` 成功；走查 15/15 |
| 测试代码 | `server/src/test/java/`、`image-service/tests/` | — |

## 2. 真实模型检查与页面走查

| 证据 | 位置 | 结果 |
| --- | --- | --- |
| 真实 DeepSeek 规划冒烟（追问 0 轮 / 1 轮） | `docs/evidence/real-planner-check/0741ae4-r0.json`、`0741ae4-r1.json` | 两者均 5/5；0 轮合计 5562 ms、prompt 11,235；1 轮 7519 ms、prompt 20,829，故默认 0 轮 |
| 页面走查：云端（规划为联调替身） | `docs/evidence/ui-walkthrough/cloud-fake-planner/` | 15/15（不含真实模型结论） |
| 页面走查：Mac 真实 DeepSeek（B10） | `docs/evidence/ui-walkthrough/mac-deepseek/` | 15/15 |
| 页面走查：Mac 真实 DeepSeek（D 阶段最终验收） | `docs/evidence/ui-walkthrough/mac-deepseek-d/` | 15/15，重试 0 次，16.5 s |

## 3. Agent 行为评测（冻结集 `agent-behavior-v1`，44 例，MANIFEST `0758dd24…`）

| 证据 | 位置 | 结果 |
| --- | --- | --- |
| 数据集、评分口径、运行器 | `eval/datasets/agent-behavior-v1/`、`eval/run_agent_eval.py`、`eval/README.md` | — |
| 原版确定性基线（云端） | `eval/reports/upstream-49f6ad8-noplanner-20261105T025609Z/` | 17/17 |
| 修复闭环 R06：修复前 / 修复后 | `eval/reports/java-51a9086-noplanner-20261105T135400Z/`、`java-exportfix-noplanner-20261105T140823Z/` | 16/17 → 17/17 |
| 原版真实 DeepSeek 3 轮 + 转发器轨迹 | `eval/reports/upstream-deepseek-r1/r2/r3-20261105T15*Z/`、`eval/reports/planner-trace-upstream-r1/r2/r3.jsonl` | 每轮 33/43 |
| Java 真实 DeepSeek 3 轮 | `eval/reports/java-deepseek-r1-20261107T073111Z/`、`r2-20261107T073926Z/`、`r3-20261107T075203Z/` | 42/43（异常 1）、38/43（异常 5）、43/43；失败 0 |
| Java 环境异常补跑（只补 6 例，不替代原轮次） | `eval/reports/java-deepseek-envretry-20261107T075322Z/` | 6/6 |
| 配对汇总（3+3 轮） | `eval/reports/paired-c1/` | 硬门、差异样例、token |
| 不配规划模型：原版 v2、Java、汇总 | `eval/reports/upstream-noplanner-mac-v2-20261107T071235Z/`、`java-noplanner-mac-20261107T072729Z/`、`paired-c1-noplanner/` | 17/17、17/17 |
| 不同条件的保留记录（规划服务返回 503） | `eval/reports/upstream-noplanner-mac-20261105T150658Z/` | 14/17，不作基线 |
| 原版失败归因 | `eval/reports/c1-upstream-failure-analysis.md` | 87 次规划调用 0 次返回多步 |

## 4. 设计与状态文档

| 文档 | 内容 |
| --- | --- |
| `docs/upstream-baseline.md` | 上游版本、许可核查、功能/工具/接口清单与原版复现 |
| `docs/architecture.md` | 组件职责、数据权威、22 个工具落位、状态机与时序 |
| `docs/implementation-state.md` | 逐模块已实现 / 已验证 / 未实现 |
| `docs/project-state.md` | 各阶段状态、环境、阻断与下一步 |
| `docs/claim-ledger.md` | 简历陈述与证据的对照 |
| `docs/reviews/c-stage-review.md` | C 阶段审查记录（待人工复核） |
| `docs/interview/` | 简历条目、演示脚本、面试问答 |

## 5. 提交

| 提交 | 内容 |
| --- | --- |
| `85c0c36`、`0ad7240` | A 阶段：上游基线、冻结评测集与原版确定性基线 |
| `8d969b0`、`2117514`、`2c5d2af`、`134105d`、`0741ae4`、`51a9086`、`34aa219`、`8232ede` | B 阶段：Java 主路径、工具、队列/SSE、规划器、选区、图像工具、前端与走查 |
| `b3acd90` | C 阶段：会话导出修复（R06）、评测汇总与规划轨迹采集 |
| `2b0ad9c` | C 阶段：接入 SDD 文档流程 |
| `3e6465c`、`ee859f4` | C 阶段证据：原版与 Java 真实 DeepSeek 配对评测 |
| 本文件所在提交 | D 阶段：最终验收、文档收口与面试材料 |
