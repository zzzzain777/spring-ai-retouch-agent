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

## 冻结规则

- `agent-behavior-v1` 冻结于 2026-11-05，早于任何 Java 改造代码。
- 不修改旧样例。新问题放进新版本数据集，并在报告里分开列出。
- 重新生成清单只用于新版本：`cd eval/datasets/<版本> && sha256sum cases.json CASES.md RUBRIC.md images/*.png > MANIFEST.sha256`。
