# C 阶段 Code Review 与验收记录

此文件是**待填写模板**，当前不代表任何检查已通过。每项填审查人、日期、HEAD、命令或文件/报告路径与结论；未运行写“未运行”。

| 核对项 | 证据 | 结果（通过/失败/未运行） |
| --- | --- | --- |
| 本机未提交改动已盘点，无覆盖别人工作 | 2026-11-07 Claude 核对：本机未提交的原版 4 个目录与 3 个轨迹已逐一审查后入库（`3e6465c`），无效一轮移出仓库 | 通过（Claude 自查，待人工复核） |
| 数据集 MANIFEST 一致，旧样例未改 | 所有轮次 `manifest_sha256` = `0758dd24…`，运行器启动即校验；`aggregate.py` 校验各轮一致 | 通过（Claude 自查，待人工复核） |
| 两版模型、temperature、能力声明和运行环境可比 | 同一冻结集、请求模型 `deepseek-chat`、temperature 0、能力 `llm,image_exec`，同一台 Mac；差异：原版经转发器、`IMAGE_PROVIDER=mock`，Java 用本地占位图像接口；运行日期不同（11-05 / 11-07） | 通过（Claude 自查，待人工复核） |
| 原版真实 DeepSeek 至少 3 轮、轨迹完整 | `upstream-deepseek-r1/r2/r3-20261105T15*Z/`、`planner-trace-upstream-r1/r2/r3.jsonl`（各 29 次） | 通过（Claude 自查，待人工复核） |
| Java 版真实 DeepSeek 至少 3 轮、轨迹完整 | `java-deepseek-r1/r2/r3-20261107T07*Z/`；r1/r2 共 6 个环境异常，补跑 `java-deepseek-envretry-…` | 通过（Claude 自查，待人工复核） |
| 确认前零派发、取消无副作用、权限隔离等硬门逐项复核 | `eval/reports/paired-c1/paired-summary.md` 硬门分子分母；`paired-c1-noplanner` 两版 17/17 | 通过（Claude 自查，待人工复核） |
| 失败案例与跨轮波动已解释，保留修复前后证据 | `eval/reports/c1-upstream-failure-analysis.md`；Java 6 个环境异常与补跑 `java-deepseek-envretry-…`；R06 修复前后报告保留 | 通过（Claude 自查，待人工复核） |
| 图像质量明确标“未测”，没有把占位图称为真实模型结果 | `claim-ledger.md` C4 “未测”；报告注明图像为占位接口 | 通过（Claude 自查，待人工复核） |
| Java/Python/前端受影响测试和构建已执行 |  | 未运行 |
| 密钥、隐私数据、未授权源码、生成大文件检查 | 提交前扫描：无密钥样式字符串；报告只有 json/jsonl/md；签名 URL 只含本地 moto 占位凭据 `retouch`；无上游源文件 | 通过（Claude 自查，待人工复核） |
| `project-state.md`、`claim-ledger.md` 与实际结果一致 | 数字取自各轮 `results.json` 与 `paired-summary.json` | 通过（Claude 自查，待人工复核） |
| 提交与推送 SHA 可复查 |  | 未运行 |

## 必填结论

- 被审提交 / 分支 / 工作树状态：
- 本次评测的原版 SHA、Java SHA、数据集 MANIFEST：
- 实际执行轮数、样例数、通过/失败/错误/跳过：
- 真实模型、占位图像、未测能力的界限：
- 主要失败与剩余风险：
- 返工项及其文件/行号：
- 审查日期与审查人：
