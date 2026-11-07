# Agent 行为评测报告：java-deepseek-envretry

- 时间：2026-11-07T07:53:22+00:00
- 被测地址：`http://127.0.0.1:7302`
- 数据集：`eval/datasets/agent-behavior-v1`，MANIFEST sha256 `0758dd2426eff8fbc3b35523a4c0f8a8442dd58624a32c3bb36bce9848db9d83`
- 运行环境能力：image_exec, llm
- 备注：补跑：r1/r2 中因 DeepSeek 接口超时/500 记为异常的 6 例，同配置；不替代原轮次结果

共 6 例，执行 6 例：通过 6，失败 0，异常 0；跳过 0 例（运行环境缺少所需能力，未用 Mock 代替）。

| 类别 | 通过 | 失败 | 异常 | 跳过 |
| --- | --- | --- | --- | --- |
| confirm_flow | 1 | 0 | 0 | 0 |
| multi_step | 1 | 0 | 0 | 0 |
| selection | 1 | 0 | 0 | 0 |
| single_step | 3 | 0 | 0 | 0 |

## 指标（按断言计）

| 指标 | 通过 | 失败 | 跳过 | 通过率（已执行） |
| --- | --- | --- | --- | --- |
| confirm_flow | 4 | 0 | 0 | 4/4 |
| confirm_gate | 10 | 0 | 0 | 10/10 |
| execution_result | 2 | 0 | 0 | 2/2 |
| no_side_effect | 1 | 0 | 0 | 1/1 |
| plan_shape | 1 | 0 | 0 | 1/1 |
| tool_selection | 8 | 0 | 0 | 8/8 |

规划调用（被测系统上报的轨迹）：6 次，prompt tokens 27202，completion tokens 325，模型耗时合计 7026 ms。

| 耗时 | 样本数 | p50 秒 | p95 秒 | 最大 秒 |
| --- | --- | --- | --- | --- |
| send_latency_s | 6 | 1.297 | 1.582 | 1.582 |
| turn_settle_s | 6 | 1.625 | 2.113 | 2.113 |

## 逐例结果

### S05 换背景（不重画主体）（pass）
- `[step 1 send] PASS plan_at_send.tools eq ["replace_background"] (actual=["replace_background"])`
- `[step 1 send] PASS plan_at_send.tools not_contains_any ["generate_image", "replace_region"] (actual=["replace_background"])`
- `[step 1 send] PASS 单步计划不需要确认，直接开始执行 (actual="running")`

### S06 扩成 16:9 且不裁主体（pass）
- `[step 1 send] PASS plan_at_send.tools eq ["expand_canvas"] (actual=["expand_canvas"])`
- `[step 1 send] PASS plan_at_send.tools not_contains_any ["crop_canvas"] (actual=["expand_canvas"])`
- `[step 1 send] PASS 单步计划不需要确认，直接开始执行 (actual="running")`
- `[final] PASS history_last.params.ratio eq "16:9" (actual="16:9")`

### S08 顺时针旋转 90 度（pass）
- `[step 1 send] PASS plan_at_send.tools eq ["rotate_layer"] (actual=["rotate_layer"])`
- `[step 1 send] PASS 单步计划不需要确认，直接开始执行 (actual="succeeded")`
- `[final] PASS top_image.transform.rotation in [90, 90.0, -270, -270.0] (actual=90.0)`

### L01 有选区时消除选中物体（pass）
- `[step 1 send] PASS plan_at_send.tools eq ["erase_region"] (actual=["erase_region"])`
- `[step 1 send] PASS plan_at_send.tools not_contains_any ["remove_background", "replace_background"] (actual=["erase_region"])`
- `[step 1 send] PASS 单步计划不需要确认，直接开始执行 (actual="running")`

### M02 调亮后裁成 4:5（pass）
- `[step 1 send] PASS plan_at_send.tools subsequence ["adjust_image", "crop_canvas"] (actual=["adjust_image", "crop_canvas"])`
- `[step 1 send] PASS plan_at_send.tools len_between [2, 3] (actual=["adjust_image", "crop_canvas"])`
- `[step 1 send] PASS 多步计划先停在等待确认 (actual="queued")`
- `[step 1 send] PASS 确认前不得派发任何工具 (actual=0)`
- `[step 1 send] PASS 等待期间也不得派发任何工具 (actual=0)`
- `[final] PASS 未确认或被拒绝时画布历史里不应出现任何编辑 (actual=["create_session"])`

### C02 确认后同步步骤与队列步骤都完成（pass）
- `[step 1 send] PASS 多步计划先停在等待确认 (actual="queued")`
- `[step 1 send] PASS 确认前不得派发任何工具 (actual=0)`
- `[step 1 send] PASS 等待期间也不得派发任何工具 (actual=0)`
- `[step 2 confirm] PASS status eq 200 (actual=200)`
- `[step 2 confirm] PASS turn.status eq "succeeded" (actual="succeeded")`
- `[final] PASS top_image.transform.scale_x lt 0 (actual=-1.0)`
- `[final] PASS history_actions subsequence ["flip_layer", "adjust_image"] (actual=["create_session", "flip_layer", "adjust_image"])`
