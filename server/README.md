# server

Java 21 + Spring Boot 4.1.2 + Spring AI 2.0.2 后端。已实现：登录注册、素材、会话与画布文档、撤销重做、选区、统一工具规格与手动工具接口（上游全部 22 个工具）、文生图与批量处理接口、任务队列 Worker 与 SSE 进度、Agent 规划/计划校验/确认/取消/重试。

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

## 用真实模型走一遍规划接口

```bash
./mvnw test -Dtest=RealPlannerCheck -Dretouch.real-planner=true
./mvnw test -Dtest=RealPlannerCheck -Dretouch.real-planner=true -Dretouch.planner.follow-up-rounds=1   # 追问一轮
```

经完整 HTTP 接口发 5 条指令（1 条单步、3 条多步、1 条无关请求），规划模型用上面配置的真实密钥，中间件用 Testcontainers。只断言集成打通：拿到结构化计划、单步直接执行、多步停在待确认且不派发、无关请求没有计划。每条的工具、轮数与 token 用量先写进 `target/real-planner-check-r<轮数>.json`，再断言。这不是评测，规划质量以 `eval/` 下冻结评测集的结果为准。默认的 `./mvnw test` 不运行它。

`retouch.planner.follow-up-rounds`（环境变量 `RETOUCH_PLANNER_FOLLOW_UP_ROUNDS`，默认 0）：拿到工具调用后最多追问几轮，让一次只给一步的模型补全剩余步骤。追问时回给模型的是“已记录、尚未执行”，工具不会执行。

## 本地运行

```bash
docker compose up -d          # 仓库根目录：PostgreSQL 7311、Redis 7312、MinIO 7313（控制台 7314）
(cd ../image-service && uv sync && uv run python -m app.main &)   # Python 图像服务 7303，只在本机监听
./mvnw spring-boot:run        # 服务监听 7302，启动时自动执行数据库迁移，并启动任务 Worker
curl http://127.0.0.1:7302/api/health
```

图像服务的共享令牌两边默认都是 `dev-internal-token`，部署时用环境变量 `IMAGE_SERVICE_TOKEN` 覆盖。

## 图像模型（可选，默认不接）

换背景、扩图、放大、局部消除/替换、营销图、拆层、提升图层、文生图这 9 个工具要调 DashScope 图像接口（按次收费）。没配 `DASHSCOPE_API_KEY` 时它们会直接失败并提示「未配置 DASHSCOPE_API_KEY，图像模型不可用」，其余工具（9 个文档工具、调色、抠图、投放尺寸、批量里的本地操作）照常可用。要接的时候把密钥写进 `application-local.yml` 的 `retouch.image.dashscope.api-key`，Java 调图像服务时在内网请求头里转交。

抠图默认用四角取色去底（适合纯色背景的商品图）。想用真正的抠图模型，在图像服务里装可选依赖：`uv sync --extra matting`（rembg，免费，首次运行会下载模型文件）；`MATTING_PROVIDER=rembg|corner|auto` 可以强制指定，默认 `auto`。

## 连接别的 PostgreSQL（可选）

默认连本机 `docker compose` 里的 PostgreSQL（7311）。要换成别处的 PostgreSQL，在 `application-local.yml` 里填 `spring.datasource` 的地址、账号、密码（示例文件里有注释好的一段），或用环境变量 `DATABASE_URL`、`DATABASE_USER`、`DATABASE_PASSWORD`。账号需要能在 `public` 模式里建表，启动时由 Flyway 建表。测试始终用 Testcontainers 启动的临时数据库。

## 构建与测试

```bash
./mvnw test
```

集成测试用 Testcontainers 启动真实的 PostgreSQL、Redis 和 S3 兼容存储（moto），需要本机有 Docker。图像工具的测试会用 `uv` 启动真实的 Python 图像服务（`../image-service`）；本机没有 `uv` 时这些测试显示为 skipped。默认测试不调用任何真实模型：图像模型由测试里的本地假 DashScope 服务（`FakeDashScopeServer`，编辑返回纯红图、文生图返回纯绿图）代替，只验证请求格式、密钥转交和结果入库的链路，不代表任何模型效果；Agent 流程测试把规划模型换成按脚本返回的测试替身（`ScriptedChatModel`），只验证 Java 侧的计划校验、确认门、取消、重试、幂等与恢复，不代表模型的规划质量。
