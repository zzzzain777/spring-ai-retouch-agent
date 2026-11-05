# server

Java 21 + Spring Boot 4.1.2 + Spring AI 2.0.2 后端。已实现：登录注册、素材、会话与画布文档、撤销重做、统一工具规格与手动工具接口（9 个文档工具 + 调色）、任务队列 Worker 与 SSE 进度。Agent 规划、校验、调度尚未实现。

## 配置大模型密钥

`application.yml` 会提交到公开仓库，只写配置入口，不写真实密钥。真实密钥放在本地文件里：

```bash
cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml
# 编辑 application-local.yml，把 spring.ai.openai.api-key 换成你的 key
```

`application-local.yml` 已被 `.gitignore` 忽略。也可以不建文件，改用环境变量 `DEEPSEEK_API_KEY`（规划模型）和 `DASHSCOPE_API_KEY`（图像模型）。

规划模型走 OpenAI 兼容接口，默认 DeepSeek（`https://api.deepseek.com`，`deepseek-chat`）。改用阿里云百炼的通义千问时，把 `base-url` 换成 `https://dashscope.aliyuncs.com/compatible-mode/v1`，`model` 换成 `qwen-plus`，见示例文件。

> 没有用 Spring AI Alibaba 的 DashScope starter：它面向 Spring AI 2.0 的版本目前只有 `2.0.0-M1.1`，依赖 Spring AI `2.0.0-M1` 和 Spring Boot `4.0.0`，和本项目锁定的 Spring Boot 4.1.2 + Spring AI 2.0.2 正式版不匹配（2026-11-05 查 Maven Central）。

## 检查密钥能不能用

```bash
./scripts/model-check.sh
```

会真实调用一次规划模型（纯文本，不带任何工具），打印回复、模型名、token 用量和耗时，然后退出；退出码非 0 表示失败。这个检查是独立的小程序，不需要数据库、Redis 或对象存储。

## 本地运行

```bash
docker compose up -d          # 仓库根目录：PostgreSQL 7311、Redis 7312、MinIO 7313（控制台 7314）
(cd ../image-service && uv sync && uv run python -m app.main &)   # Python 图像服务 7303，只在本机监听
./mvnw spring-boot:run        # 服务监听 7302，启动时自动执行数据库迁移，并启动任务 Worker
curl http://127.0.0.1:7302/api/health
```

图像服务的共享令牌两边默认都是 `dev-internal-token`，部署时用环境变量 `IMAGE_SERVICE_TOKEN` 覆盖。

## 使用阿里云 RDS PostgreSQL

业务数据也可以放在阿里云 RDS PostgreSQL（例如免费试用实例），Redis 和对象存储仍在本机。

1. 在 RDS 控制台创建一个账号，再创建数据库 `retouch`，把它的所有者（授权账号）设为这个账号。服务启动时由 Flyway 在这个库里建表，账号需要在 `public` 模式里建表的权限，数据库所有者默认就有。
2. 白名单里加上运行本服务那台电脑的公网 IP，并开通外网地址。
3. 在 `application-local.yml` 里填 `spring.datasource` 的地址、账号、密码（示例文件里有注释好的一段）。也可以用环境变量 `DATABASE_URL`、`DATABASE_USER`、`DATABASE_PASSWORD`。
4. 只启动本机的 Redis 和对象存储：`docker compose up -d redis minio`，再 `./mvnw spring-boot:run`，用 `curl http://127.0.0.1:7302/api/health` 确认 `database` 为 `ok`。

`sslmode=prefer` 在 RDS 未开启 SSL 时会走明文连接（密码认证本身不明文传输，但查询数据是明文的）；在 RDS 控制台开启 SSL 后改成 `sslmode=require`。测试始终用 Testcontainers 启动的临时数据库，不连 RDS。

## 构建与测试

```bash
./mvnw test
```

集成测试用 Testcontainers 启动真实的 PostgreSQL、Redis 和 S3 兼容存储（moto），需要本机有 Docker。图像工具的测试会用 `uv` 启动真实的 Python 图像服务（`../image-service`）；本机没有 `uv` 时这些测试显示为 skipped。测试不调用任何模型。
