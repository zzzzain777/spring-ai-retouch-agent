# 评测

本目录是本项目自己写的评测材料，不含上游代码。目标是用**同一份冻结数据集**先测上游原版基线，再测 Java 改造版，比较 Agent 行为是否保持或改进。

## 四层结构

| 层 | 内容 | 位置 |
| --- | --- | --- |
| 指标 | 工具选择、计划步数、确认门、无副作用、执行结果、确认流程、拒答、失败处理、越权隔离、旧流程回归；时延与规划 token | `datasets/agent-behavior-v1/RUBRIC.md` |
| 冻结数据集 | 44 个样例 + 2 张自绘合成图，sha256 清单冻结 | `datasets/agent-behavior-v1/` |
| 执行轨迹 | 每例每步的请求、状态码、轮次快照；规划调用的 tool_calls 与 usage | 报告目录下 `traces.jsonl`；规划轨迹见 `tools/planner_proxy.py` |
| 运行监测 | 被测系统健康检查、任务状态流（SSE）、耗时分位；Java 版另接 Actuator 指标（B 阶段设计） | 报告 `report.md` 的耗时表；样例 R08、R01 |

## 文件

- `run_agent_eval.py`：黑盒运行器，只走 HTTP 接口。启动时校验 `MANIFEST.sha256`，数据集被改过就拒绝运行。
- `datasets/agent-behavior-v1/cases.json`：样例定义（机器读）；`CASES.md` 是同一内容的人读版本。
- `datasets/agent-behavior-v1/RUBRIC.md`：评分口径与图像人工量表。
- `tools/make_images.py`：生成两张合成测试图（不含第三方素材）。
- `tools/planner_proxy.py`：评测用规划模型转发器，见下文。
- `tools/fake_image_model.py`：占位图像接口（DashScope 兼容），等价于上游 `IMAGE_PROVIDER=mock`，只用于 `image_exec` 流程判定，不是图像模型。
- `tools/aggregate.py`：把两版多轮 results.json 汇总成配对对比报告（含 token 与可选成本折算）。
- `reports/`：每次运行的 `results.json`、`traces.jsonl`、`report.md`。

## 运行

被测系统需要实现与上游 `49f6ad8` 相同的 HTTP 接口（注册登录、素材、会话、选区、工具、对话轮次、任务、SSE）。

```bash
uv run eval/run_agent_eval.py \
  --base-url http://127.0.0.1:7302 \
  --dataset eval/datasets/agent-behavior-v1 \
  --label upstream-49f6ad8 \
  --capabilities llm,image_exec \
  --note "规划 deepseek-chat，temperature 0"
```

`--capabilities` 照实声明被测环境具备什么（`llm`、`planner_unavailable`、`image_exec`、`image_model`）。不具备的能力对应的样例记为跳过，**不用 Mock 结果代替**。含 `llm` 的样例每个被测版本至少跑 3 轮。

## 规划模型：DeepSeek

本项目的规划模型统一用 DeepSeek（OpenAI 兼容接口，`deepseek-chat`）。上游原版把规划模型写死为 `DASHSCOPE_BASE_URL + "/compatible-mode/v1"`，且规划与图像共用 `DASHSCOPE_API_KEY`。为了不改上游代码就能让原版用 DeepSeek 跑基线，用 `tools/planner_proxy.py` 做配置层转发：

```bash
# 转发器进程的环境变量里放真实密钥；不要写进任何被跟踪的文件
DEEPSEEK_API_KEY=*** uv run eval/tools/planner_proxy.py --port 7320 --trace eval/reports/planner-trace.jsonl

# 上游只改配置
DASHSCOPE_BASE_URL=http://127.0.0.1:7320 DASHSCOPE_API_KEY=placeholder PLANNER_MODEL=deepseek-chat IMAGE_PROVIDER=mock
```

- `/compatible-mode/v1/*` 转到 `https://api.deepseek.com/v1`，换成 `DEEPSEEK_API_KEY`；其余路径（图像接口）转到 DashScope，需要另配 `DASHSCOPE_API_KEY`，未配置时返回 503，不伪造图像结果。
- 每次规划调用写一行轨迹：消息数、提供的工具数、模型返回的 tool_calls、usage、耗时。密钥不进轨迹。
- `IMAGE_PROVIDER=mock` 时图像工具用上游自带的占位实现跑完，此时只能声明 `image_exec`，结论只覆盖规划与流程，不覆盖出图质量。

Java 版在 `application.yml` 里用 `${DEEPSEEK_API_KEY}` 读取密钥，本地用未跟踪的 `application-local.yml`（B 阶段提供示例文件）。


## 配对评测（C 阶段）步骤

两版都在同一台机器、同一数据集、同一模型与参数（`deepseek-chat`，temperature 0）下跑；含 `llm` 的样例每版 3 轮。密钥只放环境变量或未跟踪文件（如 `application-local.yml`），运行时自行注入，**不要把密钥写进命令行参数、日志或任何输出**。

原版（上游 `49f6ad8`，临时克隆，不进本仓库）：

```bash
# 中间件、迁移、API、Worker 的启动见 docs/upstream-baseline.md §11
DEEPSEEK_API_KEY=*** uv run eval/tools/planner_proxy.py --port 7320 --trace eval/reports/planner-trace-upstream-r1.jsonl
# 上游环境：DASHSCOPE_BASE_URL=http://127.0.0.1:7320 DASHSCOPE_API_KEY=placeholder PLANNER_MODEL=deepseek-chat IMAGE_PROVIDER=mock
uv run eval/run_agent_eval.py --base-url http://127.0.0.1:8000 --dataset eval/datasets/agent-behavior-v1 \
  --label upstream-deepseek-r1 --capabilities llm,image_exec --note "deepseek-chat，经 planner_proxy；IMAGE_PROVIDER=mock"
```

Java 版（本仓库）：

```bash
uv run eval/tools/fake_image_model.py --port 7340   # 占位图像接口，对应能力 image_exec
# 启动 server：DEEPSEEK_API_KEY 来自环境或 application-local.yml；--retouch.image.dashscope.base-url=http://127.0.0.1:7340
uv run eval/run_agent_eval.py --base-url http://127.0.0.1:7302 --dataset eval/datasets/agent-behavior-v1 \
  --label java-deepseek-r1 --capabilities llm,image_exec --note "deepseek-chat；图像为本地占位接口"
```

F06 需要 `planner_unavailable`：两版各再跑一轮不配规划密钥的运行（Java 版即云端用的 `--capabilities image_exec,planner_unavailable`；原版给 proxy 不配 DEEPSEEK_API_KEY 即可拒绝）。

汇总（token 单价传当天 DeepSeek 牌价，并在 --note 注明来源与日期；不传就只报 token 数）：

```bash
uv run eval/tools/aggregate.py \
  --run upstream=eval/reports/upstream-deepseek-r1-*/results.json \
  --run upstream=eval/reports/upstream-deepseek-r2-*/results.json \
  --run upstream=eval/reports/upstream-deepseek-r3-*/results.json \
  --run java=eval/reports/java-deepseek-r1-*/results.json \
  --run java=eval/reports/java-deepseek-r2-*/results.json \
  --run java=eval/reports/java-deepseek-r3-*/results.json \
  --proxy-trace upstream=eval/reports/planner-trace-upstream-all.jsonl \
  --out eval/reports/paired-c1
```

出图质量：本项目未接真实图像模型（省钱决定），RUBRIC §4 的人工图像量表本轮**无法打分**，报告必须写「未测」，不得以占位图结果冒充。

## 冻结规则

- `agent-behavior-v1` 冻结于 2026-11-05，早于任何 Java 改造代码。
- 不修改旧样例。新问题放进新版本数据集，并在报告里分开列出。
- 重新生成清单只用于新版本：`cd eval/datasets/<版本> && sha256sum cases.json CASES.md RUBRIC.md images/*.png > MANIFEST.sha256`。
