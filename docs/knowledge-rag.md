# 规范检索与合规检查

> 范围：「内部商品图助手」方向下的**规范检索 + 计划合规校验**。
> 只记录本仓库已实现、已验证的部分；未实现的能力在第 8 节明确列出。

## 1. 它解决什么问题

内部 Agent 在给出修图计划之前，需要先知道「这个平台、这个类目的商品图有什么规范」。
本能力把规范做成可检索的知识库，并把**按本轮目标召回到的条款**变成对计划的**硬校验**：

- 检索不到规范 → 不通过，交人工核对；
- 计划违反召回到的条款 → 不通过，列出条款、原因与出处；
- 计划满足 → 放行。

它不是聊天问答式的 RAG：检索结果进入校验器，不交给模型改写，**没有生成步骤**。
主对话流程（`Planner` / `AgentService`）没有改动，本能力通过独立接口提供。

## 2. 组件

| 组件 | 文件 | 职责 |
|---|---|---|
| `KnowledgeDocumentLoader` | `knowledge/KnowledgeDocumentLoader.java` | 读 `classpath:knowledge/*.md`，解析 front-matter，按 `##` 条款切块 |
| `KnowledgeIndexService` | `knowledge/KnowledgeIndexService.java` | 按 `docId` 算内容哈希，变化才重建；懒加载触发 |
| `KnowledgeRetriever` | `knowledge/KnowledgeRetriever.java` | 元数据过滤 + 向量 topK；问答与合规两档参数 |
| `ComplianceRuleProperties` | `knowledge/ComplianceRuleProperties.java` | 绑定 `retouch.knowledge.compliance.rules` 规则表 |
| `ComplianceChecker` | `knowledge/ComplianceChecker.java` | 拼检索词 → 召回条款 → 规则表 → 对计划判通过/不通过 |
| `ComplianceController` | `knowledge/ComplianceController.java` | `POST /api/compliance/check` |
| `KnowledgeSearchRunner` | `knowledge/KnowledgeSearchRunner.java` | 仅 `retouch.knowledge.demo=true` 时运行的冒烟 |

## 3. 数据

### 3.1 语料

`server/src/main/resources/knowledge/` 下 3 份模拟规范，每份 8 条条款，共 24 个片段：

| 文件 | docId | platform | category | 条款 |
|---|---|---|---|---|
| `jd-apparel.md` | `JD-APPAREL-001` | jd | apparel | 背景、尺寸与比例、文案、主体占比、模特、色彩还原、图片格式、多图顺序 |
| `jd-shoes.md` | `JD-SHOES-001` | jd | shoes | 背景、拍摄角度、文案、尺寸与比例、成对展示、鞋内细节、配件、图片格式 |
| `tmall-apparel.md` | `TMALL-APPAREL-001` | tmall | apparel | 背景、尺寸与比例、文案、主体占比、品牌标识、色彩还原、模特、图片格式 |

每份文件开头是 front-matter：

```markdown
---
docId: JD-APPAREL-001
title: 京东服饰类目首图规范
platform: jd
category: apparel
version: 2
---
```

### 3.2 表

| 表 | 作用 |
|---|---|
| `knowledge_doc` | 每份规范的版本与内容哈希，用于幂等重建（`V3__knowledge.sql`） |
| `vector_store` | Spring AI `PgVectorStore` 管理；`id uuid / content text / metadata json / embedding vector(1024)`，HNSW 余弦索引 |

`metadata` 里带 `docId / docVersion / platform / category / ruleId / sourceRef`。
`sourceRef` 形如 `JD-APPAREL-001#rule-1`，是引用回原文的锚点，也是响应里给人看的出处。

## 4. 入库

`KnowledgeIndexService.indexAll()`：

1. 加载全部片段，按 `docId` 分组；
2. 对每份规范算 SHA-256（片段文本拼接）；
3. 与 `knowledge_doc.content_hash` 比对：
   - 相同 → 跳过；
   - 不同 → 按元数据 `docId` 删除旧向量 → 写入新向量 → upsert `knowledge_doc`。

`ensureIndexed()` 是**懒加载**：首次调用才执行，之后直接返回。
这样 `@SpringBootTest` 不会在启动时调用真实 embedding 接口。

实测：首次启动 `规范加载完成：24 个片段`，三份依次 `入库完成：8 块`；语料不变时重启三份均跳过。

## 5. 两条检索路径

| 路径 | 方法 | 检索词 | topK | 阈值 | 用途 |
|---|---|---|---|---|---|
| 规范问答 | `search` | 用户的问题 | 3 | 0.5 | 问得准才答，无关问题返回空 |
| 合规检查 | `searchForCompliance` | 本轮目标 + 计划里工具的中文名 | 5 | 0.3 | 宁可多召回几条再逐条判规则 |

两条都先按 `platform == ? and category == ?` 做元数据过滤，再做向量检索；参数见
`application.yml` 的 `retouch.knowledge.top-k / similarity-threshold` 与
`retouch.knowledge.compliance.top-k / similarity-threshold`。

合规的检索词由 `ComplianceChecker.query()` 拼成，例如：

```
把这张商品图做成京东服饰类目的首图；计划操作：去背景、改文字
```

工具中文名取自 `ToolRegistry` 的 `label`，与手动接口、模型 Schema 同一份规格。

规范问答实测（jd / apparel）：

| 查询 | 结果 |
|---|---|
| 「首图背景有什么要求」 | 3 条：rule-1 背景 0.776、rule-3 文案 0.703、rule-2 尺寸 0.697 |
| 「退货政策是什么」 | **0 条** |

阈值 0.5 是在上面两个查询上试出来的（0.35 时无关问题会误召回 2 条），属演示级取值，不是调参结论。

## 6. 合规检查

`ComplianceChecker.check(platform, category, goal, toolNames)`：

1. `ensureIndexed()`；
2. 用「目标 + 工具中文名」检索该平台/类目下适用的条款（`searchForCompliance`）；
3. 召回为空 → 直接不通过（没有依据就不放行）；
4. 对召回到的每条条款，逐条规则匹配关键词；
5. 匹配上的规则检查计划里的工具：
   - `require-any`：计划必须包含其中之一；
   - `forbid`：计划不得包含；
6. 返回 `passed`、检索词、召回条款出处、逐条 `findings`（含相似度）与 `violations`。

规则表在 `application.yml`，新增检查项不需要改 Java：

```yaml
retouch:
  knowledge:
    compliance:
      top-k: 5
      similarity-threshold: 0.3
      rules:
        - id: background-white
          message: 背景必须纯白
          keywords: [纯白, 白底, 白色背景]
          require-any: [remove_background, replace_background]
        - id: no-text
          message: 主图不得出现文字/促销元素
          keywords: [促销, 水印, 文字]
          forbid: [set_layer_text]
        - id: square-size
          message: 首图需裁剪或补边到规定比例
          keywords: [正方形, 比例]
          require-any: [crop_canvas, prepare_delivery_sizes, expand_canvas]
```

实测（jd / apparel，目标均为「把这张商品图做成京东服饰类目的首图」，最后一组除外）：

| 计划工具 | 召回 | 结果 | 原因 |
|---|---|---|---|
| `flip_layer` | 5 条 | ❌ | rule-1 背景 0.724 缺去背景工具；rule-2 比例 0.722 缺裁剪/补边工具 |
| `remove_background` + `crop_canvas` | 5 条 | ✅ | rule-1 0.830、rule-3 0.773、rule-2 0.768 全部满足 |
| `remove_background` + `set_layer_text` | 5 条 | ❌ | rule-3 文案 0.791 命中禁止工具 `set_layer_text`；rule-2 仍缺比例工具 |
| 目标换成「帮我写一首关于春天的诗」+ `flip_layer` | **0 条** | ❌ | 没有检索到适用规范，需人工核对 |

同一个 `flip_layer`：目标是做首图就被背景与比例两条拦下，目标与商品图无关时连依据都检索不到。
**决定「这次该查哪些条款」的是检索**，这是按类目取全部条款做不到的。

## 7. 接口

```
POST /api/compliance/check   （需登录）
```

请求：

```json
{"platform":"jd","category":"apparel","goal":"把这张商品图做成京东服饰类目的首图","tools":["flip_layer"]}
```

响应（节选）：

```json
{
  "passed": false,
  "query": "把这张商品图做成京东服饰类目的首图；计划操作：翻转",
  "sources": ["JD-APPAREL-001#rule-7", "JD-APPAREL-001#rule-3", "JD-APPAREL-001#rule-1", "JD-APPAREL-001#rule-2", "JD-APPAREL-001#rule-8"],
  "findings": [
    {"sourceRef":"JD-APPAREL-001#rule-1","clause":"rule-1 背景","score":0.724,"rule":"背景必须纯白","satisfied":false,"detail":"计划缺少 remove_background / replace_background"}
  ],
  "violations": ["JD-APPAREL-001#rule-1：背景必须纯白（计划缺少 remove_background / replace_background）"]
}
```

## 8. 边界（未实现 / 已知代价）

- **召回不足等于漏检**：只校验召回到的条款，漏召回的规则不会参与判定。因此空召回一律不放行，
  并且有测试把这个行为钉死（`onlyRecalledClausesAreChecked`）。语料变大后应补 hybrid 检索或关键词兜底。
- **只做计划级校验**：不验证执行结果（例如最终图片是否真为白底）。
- **规则是关键词映射**：当前覆盖「背景」「文字/促销」「比例」三类；角度、色彩等未纳入硬校验。
- **没有生成步骤**：检索结果直接进校验器，不是完整的检索增强生成。
- **阈值样本少**：问答 0.5 与合规 0.3 都来自少量手工查询，没有标注集。
- **pgvector 侧无自动化测试**：元数据过滤、阈值、入库判重只有手动运行记录；自动化测试只覆盖判定逻辑。
- **无冲突检测**：同一平台多份规范冲突时，没有「列双方来源、交人工」的专门分支。
- **无生效时间过滤**：`effectiveFrom/To` 未参与检索；当前靠人工维护语料版本。
- **无 rerank**：只做向量 topK。
- **首次请求较慢**：懒加载会在第一次 `check` 时把 24 条规范做 embedding。
- **语料规模小**：3 份模拟规范、24 个条款、3 条校验规则，不能据此推断大语料效果。

## 9. 测试

- `ComplianceCheckerTest`：7 例（纯逻辑，Mockito 给定检索结果，不连库不调模型）。
  - 检索词由目标与工具中文名拼成
  - 缺背景工具 → 不通过
  - 去背景 → 通过
  - 加文字 → 不通过
  - 空召回 → 不通过
  - 只召回部分条款 → 只判这部分（漏召回=漏检的已知代价）
  - 有规范但无匹配规则 → 不通过
- `EmbeddingConfigTest`：嵌入维度冒烟，会真的调用远程接口，**默认跳过**；
  显式打开：`./mvnw test -Dtest=EmbeddingConfigTest -Dretouch.real-embedding=true`。
- 全量后端测试：**19 个类、89 个通过、0 失败、1 跳过**（跳过的即上面那条）。

## 10. 本机运行

```bash
# 1. 向量库与 Redis（pgvector 镜像）
docker compose up -d postgres redis

# 2. 启动（首次会自动建表并入库；合规检查首次调用时懒加载）
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw spring-boot:run

# 3. 检查
curl -s http://127.0.0.1:7302/api/health

# 4. 冒烟：问答命中 / 无关返回空 / 四组合规计划
RETOUCH_KNOWLEDGE_DEMO=true JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw spring-boot:run
```

> 依赖：`pgvector/pgvector:pg17`；嵌入模型走 OpenAI 兼容接口，密钥、地址与模型名由
> `EMBEDDING_API_KEY` / `EMBEDDING_BASE_URL` / `EMBEDDING_MODEL` 提供（见 `application-local.yml.example`），
> 维度需为 1024。本机实测用的是阿里云百炼的 1024 维嵌入模型。
