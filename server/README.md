# server

Java 21 + Spring Boot 4.1.2 + Spring AI 2.0.2 后端。当前只有工程骨架和模型配置入口，Agent 规划、校验、调度等功能尚未实现。

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
./mvnw spring-boot:run -Dspring-boot.run.arguments=--retouch.model-check.enabled=true
```

会真实调用一次规划模型（纯文本，不带任何工具），打印回复、模型名、token 用量和耗时，然后退出。退出码 0 表示通过，1 表示调用失败，2 表示没配密钥。

## 构建与测试

```bash
./mvnw test
```

测试只验证配置能读取、应用在没有真实密钥时也能启动，不调用模型。
