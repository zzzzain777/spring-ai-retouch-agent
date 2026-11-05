# project-state

状态用语只用：设计 / 已实现 / 已验证 / 待验收 / 失败。

## 当前阶段：A（上游基线核查）

- 更新时间：2026-11-05（UTC）
- 上游固定版本：`yuyuanweb/ai-picture-editor@49f6ad8c96740a68b7e6228a47e8e2be6876eb34`
- 本仓库：`zzzzain777/spring-ai-retouch-agent`（公开）。A 阶段只提交文档，没有实现代码。
- Java 实现、运行测试、评测结果：**均不存在**。

| 项 | 状态 | 证据 / 说明 |
| --- | --- | --- |
| 读取 `CLAUDE.md` | 已验证 | 仓库根目录 `CLAUDE.md` |
| 克隆上游并固定 commit | 已验证 | `git ls-remote origin HEAD` = `49f6ad8…`，见 `upstream-baseline.md` §1 |
| 许可证与复用授权 | 已验证 | 上游无 LICENSE / 授权声明，结论为“无复用授权”，见 §2 |
| 功能、工具、接口、数据模型、Agent 状态机梳理 | 已验证 | 22 个工具、34 个接口，见 §4–§8，均对照源码 |
| 复用方式决定 | 已验证 | 2026-11-05 用户确认：上游无授权，前端与 Python 图像服务独立实现，上游只作行为基线 |
| 上游依赖安装 | 已验证 | 云端沙箱中 `uv sync --all-extras` 成功 |
| 上游中间件 | 待验收 | PostgreSQL 17 与 Redis 7 已用 compose 启动并健康；MinIO 镜像不可获取，暂以 moto S3 替代（未使用） |
| 上游迁移、pytest、API/Worker/前端启动 | 待验收 | 沙箱策略拦截了执行上游代码，需用户明确同意 |
| 原版真实 DashScope 基线 | 待验收 | 云端网络策略拒绝 DashScope 域名；执行环境未配置 `DASHSCOPE_API_KEY` |
| 新 GitHub 仓库与首个提交 | 待验收 | 仓库已建；会话的 GitHub 连接账号无该仓库写权限，改用用户本机 git 推送 |

## 环境记录

- 云端沙箱：Java 21.0.12、Maven 3.9.11、Gradle、Python 3.13.18、uv 0.11.32、Node 22.23、Docker 29.9（需手动 `dockerd` 启动）。
- Docker Hub 直连返回 429；`mirror.gcr.io` 可拉取官方镜像；`quay.io`、`dl.min.io` 被拒绝。
- Maven Central 可访问：Spring AI 最新 `2.0.2`，Spring Boot 最新 `4.1.2`。
- DashScope：`dashscope.aliyuncs.com`、`dashscope-intl.aliyuncs.com` 均被拒绝（代理 403）。

## 阻断

1. **执行上游代码**：跑上游测试与启动（mock 模式）以及原版真实模型基线，需要用户同意在云端沙箱中运行上游代码；运行用的上游代码不进入本仓库。
2. **DashScope 网络与密钥**（B 阶段真实调用前需要）：在项目所选云端环境的网络设置里放行 `dashscope.aliyuncs.com`，并在 Environment variables 配置 `DASHSCOPE_API_KEY`。密钥不写进 `application.yml` 或任何被跟踪的文件。
3. **云端推送权限**：后续阶段要从云端直接推送，需要让 Claude 的 GitHub 连接对本仓库有写权限（把 Claude 连接改为 zzzzain777，或给当前连接账号协作者权限）。

## 下一步

收到用户的 B 阶段指令后：锁定 Java 21 + Spring Boot 4.1.x + Spring AI 2.0.x 版本组合，产出架构与接口契约（Q3）、状态转换表（Q4）、单一工具规格设计（Q5）、数据模型（Q6）。
