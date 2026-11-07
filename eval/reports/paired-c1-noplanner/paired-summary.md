# 配对评测汇总

- 数据集 MANIFEST sha256：`0758dd2426eff8fbc3b35523a4c0f8a8442dd58624a32c3bb36bce9848db9d83`
- 备注：两版均不配规划模型（planner_unavailable）；原版 49f6ad8 不设 DASHSCOPE_API_KEY；Java 启动参数置空 DeepSeek 密钥；用户 Mac，2026-11-07

## upstream：各轮结果

| 轮 | 执行 | 通过 | 失败 | 异常 | 跳过 |
| --- | --- | --- | --- | --- | --- |
| 1（upstream-noplanner-mac-v2） | 17 | 17 | 0 | 0 | 27 |

## java：各轮结果

| 轮 | 执行 | 通过 | 失败 | 异常 | 跳过 |
| --- | --- | --- | --- | --- | --- |
| 1（java-noplanner-mac） | 17 | 17 | 0 | 0 | 27 |

## 按指标（断言计，跨轮合计）

| 指标 | upstream 通过/执行 | java 通过/执行 |
| --- | --- | --- |
| authz_isolation（硬性 100%） | 17/17 | 17/17 |
| failure_handling | 6/6 | 6/6 |
| no_side_effect（硬性 100%） | 1/1 | 1/1 |
| refusal | 1/1 | 1/1 |
| regression（硬性 100%） | 27/27 | 27/27 |

两版所有样例结论一致。

## 规划模型用量

- upstream：无 token 记录（该版本未上报轨迹，也没给转发器日志）
- java：无 token 记录（该版本未上报轨迹，也没给转发器日志）
