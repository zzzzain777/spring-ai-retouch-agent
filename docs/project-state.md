# project-state

状态用语只用：设计 / 已实现 / 已验证 / 待验收 / 失败。

## 当前阶段：A（上游基线核查）

- 更新时间：2026-11-05（UTC）
- 上游固定版本：`yuyuanweb/ai-picture-editor@49f6ad8c96740a68b7e6228a47e8e2be6876eb34`
- 本仓库：`zzzzain777/spring-ai-retouch-agent`（公开）。A 阶段只提交文档与本人编写的评测材料，没有 Java 实现代码。
- Java 实现与 Java 测试：**均不存在**。评测结果只有原版的确定性部分。

| 项 | 状态 | 证据 / 说明 |
| --- | --- | --- |
| 读取 `CLAUDE.md` | 已验证 | 仓库根目录 `CLAUDE.md` |
| 克隆上游并固定 commit | 已验证 | `git ls-remote origin HEAD` = `49f6ad8…`，见 `upstream-baseline.md` §1 |
| 许可证与复用授权 | 已验证 | 上游无 LICENSE / 授权声明，结论为“无复用授权”，见 §2 |
| 功能、工具、接口、数据模型、Agent 状态机梳理 | 已验证 | 22 个工具、34 个接口，见 §4–§8，均对照源码 |
| 复用方式决定 | 已验证 | 2026-11-05 用户确认：上游无授权，前端与 Python 图像服务独立实现，上游只作行为基线 |
| 上游依赖安装 | 已验证 | 云端沙箱中 `uv sync --all-extras` 成功 |
| 上游中间件 | 已验证 | PostgreSQL 17 与 Redis 7 用 compose 启动并健康；MinIO 镜像不可获取，以 moto S3 作 S3 兼容替代 |
| 上游迁移、pytest、API/Worker 启动 | 已验证 | 用户同意后运行：alembic 成功；pytest 160 passed（64.27s）；像素评测 3/3；API/Worker 在 `IMAGE_PROVIDER=mock` 下启动，health 全 ok。见 `upstream-baseline.md` §11 |
| 原版前端页面截图 | 待验收 | 用户 2026-11-05 选择本阶段不做 |
| 冻结 Agent 行为评测集 v1 | 已实现 | `eval/datasets/agent-behavior-v1/`：44 例、评分口径、两张自绘合成图，`MANIFEST.sha256` 冻结 |
| 原版确定性 Agent 基线 | 已验证 | 执行 17 例全部通过（越权 7、旧流程回归 8、失败 2），报告 `eval/reports/upstream-49f6ad8-noplanner-*/` |
| DeepSeek 规划转发器 | 已实现 | `eval/tools/planner_proxy.py`，只用本地假服务验证了转发、换密钥、记轨迹和无密钥拒绝；尚未连过真实 DeepSeek |
| 原版真实模型基线 | 待验收 | 规划模型改用 DeepSeek。云端网络策略拒绝 `api.deepseek.com`（代理 403），环境未配置 `DEEPSEEK_API_KEY`；27 例记为跳过 |
| 新 GitHub 仓库与首个提交 | 已验证 | `zzzzain777/spring-ai-retouch-agent@85c0c365477f35b3f59d72e9e40d217f662c08fa`（tree `8fc8e76`），经用户 Mac 本机 git（gh 登录 zzzzain777）推送；云端重新克隆核对 tree 一致 |

## 环境记录

- 云端沙箱：Java 21.0.12、Maven 3.9.11、Gradle、Python 3.13.18、uv 0.11.32、Node 22.23、Docker 29.9（需手动 `dockerd` 启动）。
- Docker Hub 直连返回 429；`mirror.gcr.io` 可拉取官方镜像；`quay.io`、`dl.min.io` 被拒绝。
- Maven Central 可访问：Spring AI 最新 `2.0.2`，Spring Boot 最新 `4.1.2`。
- DashScope：`dashscope.aliyuncs.com`、`dashscope-intl.aliyuncs.com` 均被拒绝（代理 403）。
- DeepSeek：`api.deepseek.com` 被拒绝（代理 403，2026-11-05 复查）。

## 阻断

1. **DeepSeek 网络与密钥**（跑原版真实模型基线、B 阶段规划调用前需要）：在项目所选云端环境的网络设置里放行 `api.deepseek.com`，并在 Environment variables 配置 `DEEPSEEK_API_KEY`。本地运行 Java 版时把密钥写进未跟踪的 `application-local.yml`；不写进 `application.yml` 或任何被跟踪的文件。
2. **图像模型**（换背景、扩图、局部消除等真实出图前需要）：放行 `dashscope.aliyuncs.com` 并配置 `DASHSCOPE_API_KEY`。
3. **推送方式**：Claude 的 GitHub 连接账号对本仓库无写权限，每次推送经用户 Mac 的 Remote Control 用本机 git 完成，需要用户在 Mac 上确认。

## 下一步

收到用户的 B 阶段指令后：锁定 Java 21 + Spring Boot 4.1.x + Spring AI 2.0.x 版本组合，产出架构与接口契约（Q3）、状态转换表（Q4）、单一工具规格设计（Q5）、数据模型（Q6）。
