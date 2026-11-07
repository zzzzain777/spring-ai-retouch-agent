# C 阶段 PRD：同口径 Agent 行为评测与改进

状态：**已完成，待人工复核**（2026-11-07）。依据公开仓库 `b3acd906` 的 `docs/project-state.md` 与评测脚本编写，2026-11-07 按用户 Mac 工作树核对后合入；原本机未提交的真实模型报告已审查入库（`3e6465c`、`ee859f4`）。

## 1. 本阶段要解决的问题

B 阶段已能用真实 DeepSeek 走通 5 条规划冒烟用例、前端 15 步页面走查；这些证据不足以推断 44 例行为集的稳定性。C 阶段以冻结数据集对原版基线和 Java 版做可复现配对比较，定位失败、修复可验证问题，产出带限制条件的结论。

## 2. 当前事实

- `agent-behavior-v1` 共 44 例，MANIFEST SHA-256 为 `0758dd2426eff8fbc3b35523a4c0f8a8442dd58624a32c3bb36bce9848db9d83`；以仓库文件重新校验，不手工改 v1。
- 不配规划模型的确定性子集已发现 R06 会话导出缺失：修复前执行 17 例、通过 16；修复后同口径执行 17 例、通过 17。两份报告已提交在 `eval/reports/`。
- 原版与 Java 版各 3 轮的真实 DeepSeek 配对评测**已完成**（用户 Mac；汇总 `eval/reports/paired-c1/`，归因 `eval/reports/c1-upstream-failure-analysis.md`）：
  - 原版（`49f6ad8`，2026-11-05）：r1–r3 均为执行 43、通过 33、失败 10、跳过 1；87 次规划调用没有一次返回多步，M01–M04、C01–C04、F03、A03 连带失败。
  - Java 版（2026-11-07）：r1 执行 43、通过 42、异常 1；r2 43、38、异常 5；r3 43、43；失败均为 0，跳过均为 1。6 个异常全是 DeepSeek 接口超时或 500（原始轮次如实保留），同配置只补跑这 6 例 6/6 通过（`java-deepseek-envretry-20261107T075322Z`）。
  - 硬门三轮合计（原版 vs Java）：confirm_gate 45/123 vs 113/113、no_side_effect 12/36 vs 35/35、confirm_flow 18/42 vs 38/38、authz_isolation 48/51 vs 51/51、regression 81/81 vs 81/81。
  - `planner_unavailable`：原版（与云端同条件）17/17、Java 17/17（`eval/reports/paired-c1-noplanner/`）。
  - token：原版 87 次 prompt 421,032 / completion 4,418；Java 81 次 367,196 / 4,809。未折算费用。
  - 限制：两版运行日期不同（11-05 / 11-07）；出图质量未测。
- 规划请求的模型名是 `deepseek-chat`（temperature 0）；DeepSeek 响应里返回的模型名是 `deepseek-flash`（服务端映射，见 `project-state.md`）。原版转发器轨迹记录的是请求里的模型名。
- 真实 DashScope 图像模型未接入，主观图像质量量表本阶段写“未测”。本地占位服务只验证 `image_exec` 流程。

## 3. 需求与验收标准

| ID | 需求 | 可检查的完成条件 |
| --- | --- | --- |
| C-01 | 盘点既有证据 | 列出本机 HEAD、未跟踪报告、各运行的版本/时间/能力/模型/数据集；重复或不合格运行注明原因，不静默覆盖。 |
| C-02 | 可比环境 | 原版固定上游 SHA；两版用同一 `agent-behavior-v1`、同一 `deepseek-chat` 与 temperature 0；记录 Prompt/工具 Schema 差异、软件版本和环境。 |
| C-03 | 完整真实模型运行 | 原版与 Java 版各至少 3 轮包含 `llm` 的评测，保存每轮 `results.json`、`traces.jsonl`、`report.md` 与规划 usage/耗时；缺失轮次补跑，已有合格轮次复用。 |
| C-04 | 确定性硬门 | `confirm_gate`、`no_side_effect`、`confirm_flow`、`authz_isolation`、`regression` 的实际执行断言逐项报告；任一违反先定位，不得只报总体通过率。 |
| C-05 | 配对报告 | `aggregate.py` 校验 MANIFEST 一致，列每轮分子/分母、跳过、分类指标、两版差异、跨轮波动、延迟和 token；费用只在核实官方当日价格后计算，否则只报 token。 |
| C-06 | 评测驱动修复 | 保留已完成的 R06 修复前后报告；若真实模型结果暴露新缺陷，按问题复现、修复、同集复测，记录代码/报告 SHA。不能为了满足数量制造改进。 |
| C-07 | 诚实边界与安全 | 图像质量标“未测”；不将 Mock/占位图当真实出图；秘密信息不进入 Git、日志或报告；结论只覆盖实际执行样例。 |

## 4. 运行约束

- DeepSeek 真实请求在用户 Mac 上运行；云端沙箱此前访问 `api.deepseek.com` 返回 403。若网络状态变化可再测，但不能用替身代替真实评测。
- 原版可经 `eval/tools/planner_proxy.py` 将规划请求转给 DeepSeek，原版图像执行用自带 Mock；Java 版规划经 Spring AI，图像执行用 `eval/tools/fake_image_model.py`。两者声明 `llm,image_exec` 时，结论是规划和工作流，不是图像生成质量。
- 凭据在本机未跟踪的 `application-local.yml` 或进程环境变量中提供；不要将密钥写入命令参数、聊天或报告。当前不启用收费 DashScope 图像模型、RDS 或 pgVector。

## 5. 交付物

配对运行原始报告、汇总报告、问题修复及复测证据、`docs/project-state.md` 更新、`docs/claim-ledger.md` 中可支持的陈述、提交 SHA。若某项受网络或环境阻断，保留已完成部分和可复跑命令，并标明阻断，不能标“已验证”。
