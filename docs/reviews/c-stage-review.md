# C 阶段 Code Review 与验收记录

本记录按报告、测试输出与提交填写证据（2026-11-07）；“结果”列中“可复查通过”表示证据文件里的数字可直接核对，“待人工复核”表示需要人判断。**审查人与审查日期留给人工填写**，本记录不代表人工审查已完成。

| 核对项 | 证据 | 结果（通过/失败/未运行/待人工复核） |
| --- | --- | --- |
| 本机未提交改动已盘点，无覆盖别人工作 | 原版 4 个目录与 3 个轨迹审查后入库（`3e6465c`）；无效的原版 r1（rembg 首次下载模型超时）移出仓库；交付目录 `sdd-handoff/` 未提交 | 可复查通过 |
| 数据集 MANIFEST 一致，旧样例未改 | 所有轮次 `results.json` 的 `manifest_sha256` = `0758dd2426eff8fbc3b35523a4c0f8a8442dd58624a32c3bb36bce9848db9d83`；运行器启动即校验，`aggregate.py` 校验各轮一致；`eval/datasets/agent-behavior-v1/` 自 `0ad7240` 后未改 | 可复查通过 |
| 两版模型、temperature、能力声明和运行环境可比 | 同一冻结集、请求模型 `deepseek-chat`、temperature 0、能力 `llm,image_exec`、同一台 Mac；差异：原版经转发器且 `IMAGE_PROVIDER=mock`，Java 用本地占位图像接口；运行日期不同（原版 11-05、Java 11-07），服务端模型映射是否变化无法确认 | 待人工复核 |
| 原版真实 DeepSeek 至少 3 轮、轨迹完整 | `eval/reports/upstream-deepseek-r1/r2/r3-20261105T15*Z/`（每轮 43/33/10，跳过 1）；`planner-trace-upstream-r1/r2/r3.jsonl` 各 29 次调用 | 可复查通过 |
| Java 版真实 DeepSeek 至少 3 轮、轨迹完整 | `java-deepseek-r1-20261107T073111Z`（执行 43、通过 42、异常 1：M02）、`r2-…073926Z`（43、38、异常 5：S05、S06、S08、L01、C02）、`r3-…075203Z`（43、43）；**原始 6 次异常保留**，均为运行器 `ReadTimeout`，对应时段 DeepSeek 返回 500/超时；只补跑这 6 例 6/6（`java-deepseek-envretry-20261107T075322Z`，不替代原轮次） | 可复查通过（异常归因待人工复核） |
| 确认前零派发、取消无副作用、权限隔离等硬门逐项复核 | `eval/reports/paired-c1/paired-summary.md`：三轮合计（原版 vs Java）confirm_gate 45/123 vs 113/113、no_side_effect 12/36 vs 35/35、confirm_flow 18/42 vs 38/38、authz_isolation 48/51 vs 51/51、regression 81/81 vs 81/81；`paired-c1-noplanner` 两版 17/17 | 可复查通过 |
| 失败案例与跨轮波动已解释，保留修复前后证据 | `eval/reports/c1-upstream-failure-analysis.md`（原版 87 次规划调用 0 次返回多步；F03 上限未触发；A03 为 v1 样例弱点）；R06 修复前后报告 `java-51a9086-noplanner-…`、`java-exportfix-noplanner-…` | 待人工复核 |
| 图像质量明确标“未测”，没有把占位图称为真实模型结果 | `docs/claim-ledger.md` C4 “未测”；评测与走查报告注明图像为占位/未配置 | 可复查通过 |
| Java/Python/前端受影响测试和构建已执行 | `docs/evidence/d-acceptance/README.md`：Java 17 类 82 个全部通过、0 跳过；Python 39 passed；`npm run build` 成功；页面走查 15/15（重试 0） | 可复查通过 |
| 密钥、隐私数据、未授权源码、生成大文件检查 | 每次提交前扫描密钥样式字符串为 0；报告只含 json/jsonl/md；签名 URL 只含本地 moto 占位凭据 `retouch`；截图只有随机测试账号与自绘合成图；无上游源文件 | 可复查通过 |
| `project-state.md`、`claim-ledger.md` 与实际结果一致 | 数字取自各轮 `results.json` 与 `paired-summary.json` | 待人工复核 |
| 提交与推送 SHA 可复查 | `b3acd90`、`2b0ad9c`、`3e6465c`、`ee859f4` 均在 `origin/main` | 可复查通过 |

## 必填结论

- 被审提交 / 分支 / 工作树状态：`main`，C 阶段末 `ee859f4`；工作区除未跟踪的 `sdd-handoff/` 外干净。
- 本次评测的原版 SHA、Java SHA、数据集 MANIFEST：原版 `49f6ad8c96740a68b7e6228a47e8e2be6876eb34`；Java 运行时代码为 `b3acd90`（之后到 `ee859f4` 只有文档与报告）；MANIFEST `0758dd24…`。
- 实际执行轮数、样例数、通过/失败/错误/跳过：原版 3 轮 × 43 执行，每轮 33/10/0/1；Java 3 轮 × 43 执行：42/0/1/1、38/0/5/1、43/0/0/1；补跑 6 例 6/6；`planner_unavailable` 两版各 17/17。
- 真实模型、占位图像、未测能力的界限：规划为真实 DeepSeek；图像执行为原版 mock 与 Java 本地占位接口，只判定流程；出图质量未测。
- 主要失败与剩余风险：原版多步类与确认类 10 例稳定失败（模型只返回一步）；Java 原始轮次 6 次 DeepSeek 接口异常；两版运行日期不同；A03 样例对规划行为敏感（v2 候选）。
- 返工项及其文件/行号：本轮无 Java 非环境失败，无返工项。
- 审查日期与审查人：（待人工填写）
