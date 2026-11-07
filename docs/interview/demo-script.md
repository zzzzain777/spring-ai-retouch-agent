# 5 分钟演示脚本

演示前（提前 5 分钟）：按根目录 `README.md`“本地运行”启动 PostgreSQL/Redis、moto、图像服务、Java 服务和前端，确认 `GET /api/health` 四项 ok；`application-local.yml` 已配 DeepSeek 密钥（不展示）。准备一张商品图（可用 `eval/datasets/agent-behavior-v1/images/bottle-white.png`）。
DeepSeek 不可用时的备选：直接展示 `docs/evidence/ui-walkthrough/mac-deepseek-d/` 的截图与 `summary.json`，并说明是录制证据。

| 时间 | 操作（点什么） | 看什么 | 对应设计点 |
| --- | --- | --- | --- |
| 0:00–0:30 | 打开架构图（`docs/architecture.md`）或口述 | 浏览器 → Java（唯一 API）→ PostgreSQL/Redis/S3；Java Worker → Python 图像服务 | Java 是业务状态与执行决策的权威，Python 无状态，只按对象键处理像素 |
| 0:30–1:00 | 注册登录，上传图片新建会话 | 左侧会话、右上修订号 1 | httpOnly Cookie JWT；素材按用户隔离；短期签名 URL |
| 1:00–1:40 | 工具面板选“翻转”，再选“调色”执行 | 翻转立即完成；调色出现进度条到 100% | 文档工具同步改画布；图像工具进 `tool_runs` 队列，Worker 调 Python，SSE 推进度 |
| 1:40–2:00 | 点“撤销”“重做”，打开“历史” | 修订号前后移动、历史列表 | 画布文档 + 修订号 + 20 条历史 |
| 2:00–3:10 | 对话框输入“先水平翻转，再把亮度调高一点” | 出现两步计划，状态“等待确认”，画布没变；点“规划详情”看模型、耗时、token | 模型只给提议；Java 校验后多步先停住，确认前零派发；轨迹可审计 |
| 3:10–3:40 | 点“确认执行” | 两步依次完成、修订号推进 | 确认时校验修订号，按依赖显式调度 |
| 3:40–4:00 | 再发一条两步指令，点“取消” | 状态“已取消”，画布不变 | 取消无副作用；终态再确认返回 409 |
| 4:00–4:20 | 输入“帮我写一首关于春天的诗” | 只回复一句，不产生计划 | 无关请求不调用工具 |
| 4:20–5:00 | 打开 `eval/reports/paired-c1/paired-summary.md` | 原版 vs Java 的硬门对比：确认前零派发 45/123 vs 113/113 | 冻结集 + 真实模型 3 轮配对；原版根因：87 次规划调用 0 次多步 |

可选补充（被追问时）：

- 选区：点“笔刷”涂一块区域，选“局部替换”——说明选区绑定修订号；因未接 DashScope，这里会明确提示“未配置 DASHSCOPE_API_KEY”，不伪造结果。
- 批量处理页：选图、加“去背景 + 投放尺寸”、导出 zip——说明每张图是独立子任务。
- 单步重试与租约恢复：走查里不演示，指向 `AgentTests.failedStepBlocksTheRestAndRetryRunsItAgain`、`ImageToolTests.expiredLeaseIsRequeuedForIdempotentToolsAndFailsWhenAttemptsAreUsedUp`。

演示中主动说明的边界：出图质量未测（未接 DashScope）；点选为圆形近似；无拖拽手势与前后对比；本地运行、未上线。
