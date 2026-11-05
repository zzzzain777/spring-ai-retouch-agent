# spring-ai-retouch-agent

用 Java 21、Spring Boot 4.1.x 和 Spring AI 2.0.x 实现的 AI 修图 Agent 后端。规划模型用 DeepSeek；Python 只负责图像计算和 DashScope 图像接口调用。

> 当前状态：A 阶段已完成，Java 工程刚起步。`server/` 里只有 Spring Boot 骨架和模型配置入口，Agent 功能尚未实现；Python 图像服务还没有代码。已有上游基线记录和改造前冻结的 Agent 行为评测集，评测结果只有上游原版的确定性部分。各阶段的真实进度以 [docs/project-state.md](docs/project-state.md) 为准。

## 参考来源与复用范围

- 功能设计参考 [yuyuanweb/ai-picture-editor](https://github.com/yuyuanweb/ai-picture-editor)，研究基线固定在 commit `49f6ad8c96740a68b7e6228a47e8e2be6876eb34`。
- 该项目没有声明开源许可证，因此本仓库**不包含**它的任何源码、前端、Prompt 文本、图片或其他资源，只参考它的功能和接口行为。本仓库中的代码与文档均为独立编写。
- 上游的功能、架构、工具与接口清单见 [docs/upstream-baseline.md](docs/upstream-baseline.md)。

## 计划中的改造（尚未实现）

- Java 作为对外 API、Agent 规划与计划校验、执行调度、统一工具规格、状态持久化和权限控制的权威服务。
- 规划阶段由模型生成结构化计划，不让 Spring AI 自动调用有副作用的修图工具；计划经服务端校验、必要时由用户确认后，再由 Java 显式调度执行。
- 按“指标、冻结数据集、执行轨迹、运行监测”四层评测，用同一份冻结数据集和真实模型对比上游与本项目。

## 文档

| 文件 | 内容 |
| --- | --- |
| [CLAUDE.md](CLAUDE.md) | 项目目标、边界、技术约束与证据规则 |
| [docs/upstream-baseline.md](docs/upstream-baseline.md) | 上游基线记录（版本、许可核查、功能、工具、接口、Agent 状态机） |
| [docs/project-state.md](docs/project-state.md) | 各阶段状态、命令、环境、证据与阻断 |
| [docs/claim-ledger.md](docs/claim-ledger.md) | 简历陈述与代码、测试、评测证据的对照 |
| [server/README.md](server/README.md) | Java 后端：模型密钥配置、连通性检查、构建 |
| [eval/README.md](eval/README.md) | 四层评测、冻结数据集 agent-behavior-v1、运行方法与 DeepSeek 转发器 |

## 密钥

DeepSeek 与 DashScope 的密钥只通过环境变量 `DEEPSEEK_API_KEY`、`DASHSCOPE_API_KEY`，或本地不提交的 `.env`、`application-local.yml` 提供，不写进任何被跟踪的文件。
