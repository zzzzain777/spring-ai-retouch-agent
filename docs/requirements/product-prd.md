# AI 修图 Agent：产品与工程需求（现状版）

版本：2026-11-06 用户提供的候选稿；2026-11-07 按用户 Mac 工作树（HEAD `b3acd90`）核对并修正后合入。此文档描述**已定目标与公开仓库已核实状态**，不是从项目立项时就存在的历史 PRD。

## 1. 目标与使用者

用户在画布中上传或生成图片，用自然语言提出修图目标，也可手动选择工具。项目要能运行、演示并回答 Java / Spring AI Agent 应用开发岗位的工程追问；所有简历数据需由代码、测试或评测支撑。

设计研究基线为 `yuyuanweb/ai-picture-editor@49f6ad8c96740a68b7e6228a47e8e2be6876eb34`。其 Python/FastAPI 业务服务用 LangChain 接入规划模型，LangGraph 仅执行 `plan → verify → END`，计划确认、执行推进、重试和取消在服务层。本项目独立实现 Java/Spring AI 主路径；参考功能与 HTTP 行为，不将上游无明确许可证的代码或资源复制进公开仓库。来源与复用范围在公开 README 中说明。

## 2. 当前产品范围

| 模块 | 用户可见目标 | 当前公开仓库事实 |
| --- | --- | --- |
| 用户与素材 | 登录、上传、素材库、图片墙 | Java API、权限隔离与短期签名 URL 已实现。 |
| 画布与历史 | 图层、变换、撤销重做、前后对比 | React 画布与会话历史已实现；没有画布上直接拖动/缩放图层的手势（画布指针事件只用于笔刷/点选）；`web/src` 中未找到前后对比视图，按未实现处理。 |
| 自然语言修图 | 单步直接执行；多步先确认；取消与失败步骤重试 | Spring AI 规划 + Java 计划校验/状态机已实现，真实 DeepSeek 冒烟与页面走查已有证据。 |
| 工具 | 手动与 Agent 共用二十余种编辑能力 | `ToolRegistry` 登记 22 个工具；9 个依赖 DashScope 图像模型的工具尚无真实模型验收。 |
| 选区与图层 | 笔刷/点选、局部编辑、拆层 | 点选当前是圆形区域近似，不是 SAM 分割；文字拆层/OCR 未实现。 |
| 长任务与进度 | 异步执行、状态恢复、SSE | PostgreSQL `tool_runs` 任务表、Worker 租约恢复、Redis 进度通知、SSE 已实现。 |
| 创作与批量 | 文生图候选、批量处理与 ZIP 导出 | 接口与前端流程已实现；模型输出质量未验证；会话导出在 C 阶段修复后已通过确定性回归。 |
| 评测 | 行为、流程、风险与质量可复测 | 44 例冻结数据集和运行器已存在。真实 DeepSeek 配对评测未完成：原版 3 轮结果在用户 Mac 上、未提交，待审查；Java 版 3 轮尚未运行；配对汇总尚无结果。 |

## 3. 技术与权威边界

- `server/`：Java 21、Spring Boot 4.1.2、Spring AI 2.0.2、**Spring WebMVC**。模型只提出工具调用，Java 决定能否执行。
- `web/`：React 18.3.1、TypeScript 5.9.3、Vite 6.4.5。当前依赖不含 `react-konva`，不能把它写进已完成技术栈。
- `image-service/`：Python 3.12+、FastAPI、Pillow/OpenCV；可选 `rembg`；负责图像计算及图像模型接口，不负责 Agent 状态。
- PostgreSQL 17 是业务、计划和任务状态的权威存储；Redis 负责选区与进度通知；MinIO/S3 兼容服务保存图片。本地用 `docker-compose.yml`，未使用阿里云 RDS。
- 规划模型默认 DeepSeek `deepseek-chat`，Spring AI 走 OpenAI 兼容接口；图像模型配置为 DashScope，但用户目前为控制成本未启用真实调用。

## 4. 必须保持的行为

1. 模型规划阶段零修图副作用。Java 校验工具存在、参数、依赖、环、步数上限与资源权限。
2. 单步计划可直接派发；多步计划 `queued` 等用户确认，确认前派发数为 0。取消不执行后续，失败步骤可单步重试。
3. 工具手动入口与 Agent 入口共用规格与参数校验；前端工具表单从 `/api/tools` 的 Schema 生成。
4. 会话、素材、任务、SSE、计划操作按用户隔离；跨用户访问不能泄漏资源。
5. 异步图像任务有排队、执行、失败、取消、超时与重启恢复语义；SSE 展示进度但不充当状态权威。
6. 评测结论区分真实规划模型、图像模型、占位实现与确定性测试；未执行的能力标为跳过或未测。

## 5. 本阶段外的目标

RAG、pgVector、Embedding、MCP、多 Agent、真实 SAM、OCR、线上生产指标都没有作为当前完成要求。后续若增加，须先有具体用户场景、需求、实现和真实验收。`CLAUDE.md` 与简历不得把规划中的能力写成已实现。

## 6. 验收资料

代码与运行方法见仓库 README、`docs/architecture.md`、`server/README.md`、`image-service/README.md`、`web/README.md`；现状与局限见 `docs/project-state.md`；评测标准见 `eval/datasets/agent-behavior-v1/RUBRIC.md`；简历表述见 `docs/claim-ledger.md`。
