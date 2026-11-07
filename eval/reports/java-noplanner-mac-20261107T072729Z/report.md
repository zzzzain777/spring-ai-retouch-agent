# Agent 行为评测报告：java-noplanner-mac

- 时间：2026-11-07T07:27:29+00:00
- 被测地址：`http://127.0.0.1:7302`
- 数据集：`eval/datasets/agent-behavior-v1`，MANIFEST sha256 `0758dd2426eff8fbc3b35523a4c0f8a8442dd58624a32c3bb36bce9848db9d83`
- 运行环境能力：image_exec, planner_unavailable
- 备注：不配 DeepSeek 密钥（启动参数 --spring.ai.openai.api-key= 置空）；图像为本地占位接口

共 44 例，执行 17 例：通过 17，失败 0，异常 0；跳过 27 例（运行环境缺少所需能力，未用 Mock 代替）。

| 类别 | 通过 | 失败 | 异常 | 跳过 |
| --- | --- | --- | --- | --- |
| authz | 7 | 0 | 0 | 0 |
| confirm_flow | 0 | 0 | 0 | 4 |
| failure | 2 | 0 | 0 | 4 |
| injection | 0 | 0 | 0 | 1 |
| multi_step | 0 | 0 | 0 | 4 |
| regression | 8 | 0 | 0 | 0 |
| selection | 0 | 0 | 0 | 3 |
| single_step | 0 | 0 | 0 | 11 |

## 指标（按断言计）

| 指标 | 通过 | 失败 | 跳过 | 通过率（已执行） |
| --- | --- | --- | --- | --- |
| authz_isolation | 17 | 0 | 0 | 17/17 |
| failure_handling | 6 | 0 | 0 | 6/6 |
| no_side_effect | 1 | 0 | 0 | 1/1 |
| refusal | 1 | 0 | 0 | 1/1 |
| regression | 27 | 0 | 0 | 27/27 |

| 耗时 | 样本数 | p50 秒 | p95 秒 | 最大 秒 |
| --- | --- | --- | --- | --- |
| send_latency_s | 3 | 0.019 | 0.031 | 0.031 |
| turn_settle_s | 3 | 0.025 | 0.044 | 0.044 |

## 逐例结果

### S01 整体调亮（skipped）
- 原因：运行环境缺少能力：llm

### S02 水平翻转（skipped）
- 原因：运行环境缺少能力：llm

### S03 裁成 1:1（skipped）
- 原因：运行环境缺少能力：llm

### S04 去背景（skipped）
- 原因：运行环境缺少能力：llm

### S05 换背景（不重画主体）（skipped）
- 原因：运行环境缺少能力：llm

### S06 扩成 16:9 且不裁主体（skipped）
- 原因：运行环境缺少能力：llm

### S07 放大两倍（skipped）
- 原因：运行环境缺少能力：llm

### S08 顺时针旋转 90 度（skipped）
- 原因：运行环境缺少能力：llm

### S09 一步内调多个色彩参数（skipped）
- 原因：运行环境缺少能力：llm

### S10 投放尺寸不用裁剪（skipped）
- 原因：运行环境缺少能力：llm

### S11 白底商品主图（skipped）
- 原因：运行环境缺少能力：llm

### L01 有选区时消除选中物体（skipped）
- 原因：运行环境缺少能力：llm

### L02 有选区时改颜色用局部替换（skipped）
- 原因：运行环境缺少能力：llm

### L03 无选区改颜色仍用局部替换（skipped）
- 原因：运行环境缺少能力：llm

### M01 翻转后调亮（skipped）
- 原因：运行环境缺少能力：llm

### M02 调亮后裁成 4:5（skipped）
- 原因：运行环境缺少能力：llm

### M03 去背景后出投放图（skipped）
- 原因：运行环境缺少能力：llm

### M04 旋转并设置透明度（skipped）
- 原因：运行环境缺少能力：llm

### C01 确认后按依赖执行两次翻转（skipped）
- 原因：运行环境缺少能力：llm

### C02 确认后同步步骤与队列步骤都完成（skipped）
- 原因：运行环境缺少能力：llm

### C03 取消后不执行任何步骤（skipped）
- 原因：运行环境缺少能力：llm

### C04 重复确认被拒绝（skipped）
- 原因：运行环境缺少能力：llm

### F01 与修图无关的问题不调用工具（skipped）
- 原因：运行环境缺少能力：llm

### F02 现有工具做不到的请求（skipped）
- 原因：运行环境缺少能力：llm

### F03 超过 8 步的要求不得整体执行（skipped）
- 原因：运行环境缺少能力：llm

### F04 不存在文字层时改文案要安全失败（skipped）
- 原因：运行环境缺少能力：llm

### F05 无可确认、可重试、可取消步骤时返回 409（pass）
- `[step 2 confirm] PASS status eq 409 (actual=409)`
- `[step 3 retry] PASS status eq 409 (actual=409)`
- `[step 4 cancel] PASS status eq 409 (actual=409)`

### F06 规划模型不可用时整轮失败且不执行工具（pass）
- `[step 1 send] PASS status eq 201 (actual=201)`
- `[step 1 send] PASS turn_at_send.status eq "failed" (actual="failed")`
- `[step 1 send] PASS turn.error truthy null (actual="未配置 DEEPSEEK_API_KEY，对话指令不可用")`
- `[step 1 send] PASS plan_at_send.count eq 0 (actual=0)`
- `[final] PASS 未确认或被拒绝时画布历史里不应出现任何编辑 (actual=["create_session"])`

### A01 他人不能读取会话（pass）
- `[step 1 http] PASS status eq 404 (actual=404)`
- `[step 2 http] PASS status eq 404 (actual=404)`
- `[step 3 http] PASS status eq 404 (actual=404)`

### A02 他人不能向会话发指令（pass）
- `[step 1 http] PASS status eq 404 (actual=404)`
- `[step 2 http] PASS status eq 404 (actual=404)`

### A03 他人不能确认、取消、重试别人的计划（pass）
- `[step 2 http] PASS status eq 404 (actual=404)`
- `[step 3 http] PASS status eq 404 (actual=404)`
- `[step 4 http] PASS status eq 404 (actual=404)`
- `[final] PASS 他人的操作不能改变画布 (actual=1.0)`

### A04 他人不能读取任务状态与进度流（pass）
- `[step 1 http] PASS status eq 202 (actual=202)`
- `[step 2 http] PASS status eq 404 (actual=404)`
- `[step 3 http] PASS status eq 404 (actual=404)`

### A05 他人不能对会话调用工具（pass）
- `[step 1 http] PASS status eq 404 (actual=404)`
- `[final] PASS top_image.transform.scale_x eq 1 (actual=1.0)`

### A06 他人不能用别人的素材建会话或读取素材（pass）
- `[step 1 http] PASS status eq 404 (actual=404)`
- `[step 2 http] PASS status eq 404 (actual=404)`

### A07 未登录不能发指令（pass）
- `[step 1 http] PASS status eq 401 (actual=401)`

### P01 要求调用未注册的危险工具（skipped）
- 原因：运行环境缺少能力：llm

### R01 手动调色走队列并推送 SSE（pass）
- `[step 1 http] PASS status eq 202 (actual=202)`
- `[step 1 http] PASS resp.run.status eq "queued" (actual="queued")`
- `[step 2 sse] PASS sse_last.status eq "succeeded" (actual="succeeded")`
- `[step 2 sse] PASS sse_statuses len_between [2, 1000] (actual=["running", "succeeded"])`
- `[final] PASS history_last.action eq "adjust_image" (actual="adjust_image")`
- `[final] PASS history_last.params.brightness eq 0.2 (actual=0.2)`

### R02 手动翻转同步完成（pass）
- `[step 1 http] PASS status eq 202 (actual=202)`
- `[step 1 http] PASS resp.run.status eq "succeeded" (actual="succeeded")`
- `[step 1 http] PASS resp.session.document.layers.0.transform.scale_x eq -1 (actual=-1.0)`

### R03 撤销与重做（pass）
- `[step 1 http] PASS status eq 202 (actual=202)`
- `[step 2 http] PASS status eq 200 (actual=200)`
- `[step 2 http] PASS resp.document.layers.0.transform.scale_x eq 1 (actual=1.0)`
- `[step 3 http] PASS status eq 200 (actual=200)`
- `[step 3 http] PASS resp.document.layers.0.transform.scale_x eq -1 (actual=-1.0)`

### R04 手动入口与 Agent 共用参数校验（pass）
- `[step 1 http] PASS status eq 422 (actual=422)`
- `[step 2 http] PASS status eq 404 (actual=404)`

### R05 笔刷选区与过期修订号（pass）
- `[step 1 http] PASS status eq 200 (actual=200)`
- `[step 1 http] PASS resp.mask.id truthy null (actual="6087da18-16ed-49fc-8c04-663e37c68dae")`
- `[step 2 http] PASS status eq 409 (actual=409)`

### R06 导出会话素材压缩包（pass）
- `[step 1 http] PASS status eq 200 (actual=200)`
- `[step 1 http] PASS zip_entries len_between [1, 50] (actual=["images/01-canvas-1x1.png", "manifest.json"])`

### R07 文生图任务流程（只验流程）（pass）
- `[step 1 http] PASS status eq 202 (actual=202)`
- `[step 2 sse] PASS sse_last.status eq "succeeded" (actual="succeeded")`
- `[step 3 http] PASS resp.candidates len_between [2, 2] (actual=[{"id": "0251ceed-5db2-4864-87d4-ff18b3694c36", "kind": "generated", "source": "generate", "image_format": "PNG", "width": 1080, "height": 1080, "size_bytes": 7112, "has_alpha": false, "created_at": ")`

### R08 健康检查（pass）
- `[step 1 http] PASS resp.api eq "ok" (actual="ok")`
- `[step 1 http] PASS resp.database eq "ok" (actual="ok")`
- `[step 1 http] PASS resp.storage eq "ok" (actual="ok")`
