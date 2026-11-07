# 本地运行与演示环境

状态：此文档描述**本地开发与演示**，不是生产上线证明。仓库已有 `server/README.md`、`image-service/README.md`、`web/README.md`；实际命令若变化，以代码和这些 README 重新核对。

## 1. 组件与端口

| 组件 | 运行方式 | 默认地址/用途 |
| --- | --- | --- |
| PostgreSQL 17 | 根目录 `docker compose up -d` | `127.0.0.1:7311`，业务、计划、任务持久化。 |
| Redis 7 | 同上 | `127.0.0.1:7312`，选区与进度通知。 |
| MinIO（compose 声明） | 同上 | `127.0.0.1:7313`，图片字节；控制台 `7314`。**镜像当前拉取不到**（云端 Docker Hub 429、本机 Docker Hub 拒绝，`quay.io` 401），到目前为止所有实际运行都以 moto S3 替代，端口沿用 7313，见下方说明。 |
| Python 图像服务 | `cd image-service && uv sync && uv run python -m app.main` | `127.0.0.1:7303`，仅 Java 调用。 |
| Java 服务 | `cd server && ./mvnw spring-boot:run` | `127.0.0.1:7302`，唯一业务 API。 |
| React 前端 | `cd web && npm ci && npm run dev` | `127.0.0.1:7301`，开发服务器。 |

先启动中间件、图像服务、Java，最后启动前端；检查 `GET /api/health`。数据库迁移由 Java 启动时 Flyway 执行。部署前检查 Docker、Java 21、Node、Python/uv 与 Maven Wrapper 可用。

MinIO 拉不到时的实际做法（B10 页面走查、C 阶段评测与 D 阶段最终验收都这样运行）：只起 compose 里的 PostgreSQL 与 Redis，再单独起 moto 作 S3 兼容服务。Java 启动时若桶不存在会自动创建；moto 数据只在内存里，停掉即清空。

```bash
docker compose up -d postgres redis
docker run -d --name retouch-moto-s3 -p 127.0.0.1:7313:5000 motoserver/moto:5.1.4
```

## 2. 密钥与收费服务

- `server/src/main/resources/application.yml` 只声明 `${DEEPSEEK_API_KEY}`、`${DASHSCOPE_API_KEY}` 等入口。真实值放本机已忽略的 `application-local.yml` 或环境变量；示例见 `application-local.yml.example`。
- 真实 DeepSeek 用于规划；可用 `cd server && ./scripts/model-check.sh` 单独检查连通性。云端环境此前无法访问 DeepSeek，C 阶段真实运行在用户 Mac。
- DashScope 图像模型目前未开通。9 个依赖它的工具没有真实出图验收；本地占位图像服务仅供测试与评测流程判定，不用于用户演示出图质量。
- 本项目使用本机 PostgreSQL，没有阿里云 RDS。默认配置是开发值，若以后公开部署必须重新配置 JWT、对象存储、内部服务令牌及 HTTPS/Cookie 设置，并补安全与负载验证。

## 3. 演示和评测口径

可演示的主路径：登录、上传、会话/画布、文档工具与本地图像工具、自然语言规划、单步执行、多步确认/取消/重试、SSE、撤销重做、批量本地操作与 ZIP 导出。用真实 DeepSeek 时可展示规划轨迹；图像模型相关步骤只展示明确的“未配置”反馈或标注为本地占位链路。

自动化检查：`cd server && ./mvnw test`、`cd image-service && uv run pytest`、`cd web && npm run build`。行为评测与能力声明见 `eval/README.md`。每次演示或报告注明运行地点、配置、模型、使用的真实/占位依赖和当次结果。
