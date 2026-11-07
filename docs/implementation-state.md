# 实现状态（逐模块）

更新：2026-11-07（D 阶段最终验收）。状态只用：设计 / 已实现 / 已验证 / 待验收 / 失败。“已验证”必须有可复查的测试、评测报告或走查证据；证据总表见 [evidence-index.md](evidence-index.md)。本项目参考 `yuyuanweb/ai-picture-editor@49f6ad8` 的功能与 HTTP 行为，Java 服务、Python 图像服务、前端与评测均为本仓库独立实现（上游无许可证，未复制其代码、Prompt 或资源）。

| 模块 | 状态 | 证据 | 未实现 / 限制 |
| --- | --- | --- | --- |
| 认证与权限隔离 | 已验证 | `AuthAndAssetTests`、`SessionTests`（他人资源 404）、`AgentTests.otherUsersAndOtherSessionsCannotTouchATurn`；评测 authz 7 例与 A01–A07 | JWT 放 httpOnly Cookie；登出只清 Cookie，**不吊销**已签发令牌 |
| 素材上传、素材库、图片墙 | 已验证 | `AuthAndAssetTests`（JPEG/PNG/WEBP、大小与像素校验、422）；`LibraryController`；页面走查 upload/delivery-sizes-wall | 对象存储里的**孤儿对象不清理**（代码中无删除对象逻辑） |
| 会话、画布文档、修订号、20 条历史、撤销重做 | 已验证 | `SessionTests` 6；评测 R03；页面走查 undo-redo-history | — |
| 统一工具规格（22 个工具） | 已验证 | `ToolRegistry`；`ToolTests`（`hasSize(22)`）、`ToolSchemasTest`；`GET /api/tools` 的 Schema 与交给规划模型的 Schema 同源，前端表单由它生成；评测 R04（手动与 Agent 共用参数校验） | — |
| 文档工具 9 个（翻转、旋转、缩放、移动、透明度、可见、层序、文字、裁画布） | 已验证 | `DocumentToolsTest` 4、`ToolTests` 6；评测 S/M/C 类 | — |
| 本地图像工具 3 个（调色、去背景、投放尺寸）+ 批量 | 已验证 | `ImageToolTests` 2（经队列、Python、SSE）、`AgentImageTests` 2；页面走查 tool-adjust-image-sse、remove-background-layers、delivery-sizes-wall、batch | 去背景的 rembg 是可选依赖，测试、评测与走查环境**未安装**，走四角取色兜底 |
| 依赖 DashScope 的模型工具 9 个（换背景、扩图、放大、局部消除/替换、营销图、文生图、拆层、提升图层） | 已实现 | `ModelToolTests` 6 与 `ModelKeyMissingTests` 1 用**本地假 DashScope** 验证调用链路与未配置时的明确失败；页面走查 model-tool-without-key、generate-without-key | **出图质量未测**：未接真实 DashScope（省钱决定）；拆层无 OCR 文字层（`include_text=true` 明确报不支持） |
| 选区与遮罩 | 已验证 | `SelectionTests` 4（笔刷遮罩绑定修订号、点选、清除、他人/无效选区）；页面走查 brush-selection | 点选是**圆形区域近似**，不是 SAM 分割；`POST …/selection/prepare` 为占位 204 |
| Agent 规划与执行（Spring AI） | 已验证 | `AgentTests` 13、`PlanRulesTest` 12、`PlannerNoExecutionTest` 7；真实 DeepSeek 冒烟 5/5（`docs/evidence/real-planner-check/`）；配对评测 Java r1–r3（`eval/reports/paired-c1/`） | 规划模型只用 DeepSeek `deepseek-chat`；可选追问轮默认 0 |
| 任务队列、Worker、SSE | 已验证 | `QueueCancelTests`、`WorkerFailureTests`、`ImageToolTests.expiredLeaseIsRequeued…`、`AgentTests.lostPlanUpdateIsReconciled…`；页面走查 SSE 100% | Worker 为**单进程 2 线程**（`retouch.worker.concurrency=2`），未做多实例或压力测试；运行中任务的取消（`cancel_requested` 标记）已实现但无专门自动化测试 |
| 文生图、批量处理与 ZIP、会话导出 | 已验证（流程） | 批量：页面走查 batch；会话导出：`SessionExportTests` 4、评测 R06 16/17→17/17；文生图：未配置模型时明确失败 | 文生图与模型类批量操作的出图质量未测 |
| 前端（React + Vite + TypeScript） | 已验证 | `npm run build`；页面走查 15/15（`docs/evidence/ui-walkthrough/`） | 无画布上直接拖拽/缩放图层的手势；无前后对比视图 |
| 评测体系（冻结 44 例、运行器、转发器、汇总、占位图像接口） | 已验证 | `eval/`；原版/Java 配对 `eval/reports/paired-c1/`、`paired-c1-noplanner/`；失败归因 `eval/reports/c1-upstream-failure-analysis.md` | 两版运行日期不同（原版 11-05、Java 11-07）；Java 原始轮次有 6 次 DeepSeek 接口异常（已补跑，原记录保留）；图像主观质量量表未打分 |
| 部署 | 已实现（本地） | `docker-compose.yml`、`docs/deployment.md` | **未上线**；未用阿里云 RDS；MinIO 镜像拉取不到，实际以 moto 作 S3 兼容存储；默认密钥/令牌为开发值 |
