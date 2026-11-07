# 配对评测汇总

- 数据集 MANIFEST sha256：`0758dd2426eff8fbc3b35523a4c0f8a8442dd58624a32c3bb36bce9848db9d83`
- 备注：同一冻结数据集 agent-behavior-v1、deepseek-chat temperature 0、能力 llm,image_exec；原版 49f6ad8 经 planner_proxy（IMAGE_PROVIDER=mock）、Java 版经 Spring AI（本地占位图像接口）；用户 Mac。原版 2026-11-05、Java 2026-11-07 运行；Java r1/r2 的 6 个异常均为 DeepSeek 接口超时/500，补跑见 java-deepseek-envretry。未传价格，只报 token

## upstream：各轮结果

| 轮 | 执行 | 通过 | 失败 | 异常 | 跳过 |
| --- | --- | --- | --- | --- | --- |
| 1（upstream-deepseek-r1） | 43 | 33 | 10 | 0 | 1 |
| 2（upstream-deepseek-r2） | 43 | 33 | 10 | 0 | 1 |
| 3（upstream-deepseek-r3） | 43 | 33 | 10 | 0 | 1 |

## java：各轮结果

| 轮 | 执行 | 通过 | 失败 | 异常 | 跳过 |
| --- | --- | --- | --- | --- | --- |
| 1（java-deepseek-r1） | 43 | 42 | 0 | 1 | 1 |
| 2（java-deepseek-r2） | 43 | 38 | 0 | 5 | 1 |
| 3（java-deepseek-r3） | 43 | 43 | 0 | 0 | 1 |

## 按指标（断言计，跨轮合计）

| 指标 | upstream 通过/执行 | java 通过/执行 |
| --- | --- | --- |
| authz_isolation（硬性 100%） | 48/51 | 51/51 |
| confirm_flow（硬性 100%） | 18/42 | 38/38 |
| confirm_gate | 45/123 | 113/113 |
| execution_result | 39/39 | 37/37 |
| failure_handling | 18/18 | 18/18 |
| no_side_effect（硬性 100%） | 12/36 | 35/35 |
| plan_shape | 3/12 | 11/11 |
| refusal | 9/9 | 9/9 |
| regression（硬性 100%） | 81/81 | 81/81 |
| tool_selection | 72/84 | 76/76 |

## 两版结论不同的样例

- S05：upstream=pass，java=flaky
- S06：upstream=pass，java=flaky
- S08：upstream=pass，java=flaky
- L01：upstream=pass，java=flaky
- M01：upstream=fail，java=pass
- M02：upstream=fail，java=flaky
- M03：upstream=fail，java=pass
- M04：upstream=fail，java=pass
- C01：upstream=fail，java=pass
- C02：upstream=fail，java=flaky
- C03：upstream=fail，java=pass
- C04：upstream=fail，java=pass
- F03：upstream=fail，java=pass
- A03：upstream=fail，java=pass

## 多轮间不稳定或失败的样例

- S05（java）：pass, error, pass
- S06（java）：pass, error, pass
- S08（java）：pass, error, pass
- L01（java）：pass, error, pass
- M01（upstream）：fail, fail, fail
- M02（upstream）：fail, fail, fail
- M02（java）：error, pass, pass
- M03（upstream）：fail, fail, fail
- M04（upstream）：fail, fail, fail
- C01（upstream）：fail, fail, fail
- C02（upstream）：fail, fail, fail
- C02（java）：pass, error, pass
- C03（upstream）：fail, fail, fail
- C04（upstream）：fail, fail, fail
- F03（upstream）：fail, fail, fail
- A03（upstream）：fail, fail, fail

## 规划模型用量

- upstream：87 次调用，prompt 421032，completion 4418 tokens
- java：81 次调用，prompt 367196，completion 4809 tokens
