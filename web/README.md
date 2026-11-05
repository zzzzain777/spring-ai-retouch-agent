# web

本项目独立编写的前端（React 18 + Vite 6 + TypeScript，没有使用上游前端的任何代码或资源）。只和 Java 服务通信：
开发服务器把 `/api` 与 `/events`（SSE）转发到 `http://127.0.0.1:7302`，浏览器端是同源请求，登录 Cookie 照常携带。

## 运行

需要 Node 18 以上。

```bash
npm ci
npm run dev        # http://127.0.0.1:7301
npm run build      # 类型检查 + 生产构建到 dist/
```

Java 服务地址不是 7302 时：`RETOUCH_API=http://host:port npm run dev`。

## 页面

| 页面 | 内容 | 用到的接口 |
| --- | --- | --- |
| 登录 / 注册 | 用户名与密码 | `/api/auth/*` |
| 修图 | 左：上传与会话列表；中：按画布文档渲染图层、笔刷与点选选区、撤销重做；右：对话、工具、图层、图片墙、历史 | `/api/sessions/*`、`/api/assets`、`/api/sessions/{id}/selection`、`/api/sessions/{id}/tools`、`/api/sessions/{id}/messages/*`、`/events/runs/{id}` |
| 文生图 | 描述、比例、张数，选一张候选开始修图 | `/api/generations`、`/api/runs/{id}` |
| 批量处理 | 选图、排操作（参数表单同单张工具）、选格式，下载 zip | `/api/batches/*` |

设计要点：

- **工具表单不在前端写死**：工具列表和参数表单都由 `GET /api/tools` 返回的 JSON Schema 生成，和交给规划模型的是同一份（`ToolSchemas`），最终校验仍在 Java。
- **对话只展示，不判断**：计划是否需要确认、能否取消或重试都由 Java 的状态机决定，前端按轮次和步骤状态显示按钮。
- **进度走 SSE**：排队执行的工具订阅 `/events/runs/{id}`，结束后再读一次完整结果；对话里有步骤在执行时每秒刷新一次轮次。
- **图层渲染规则与图像服务一致**：绕图层框中心先缩放/镜像、再顺时针旋转、再乘不透明度，文字在框内居中。

## 页面走查

`e2e/walkthrough.mjs` 用真实浏览器（Playwright 驱动本机 Chrome，不下载浏览器）把主要功能点一遍，每步截图并断言：
注册登录、上传建会话、文档工具、排队工具与 SSE、撤销重做与历史、笔刷选区、未配置图像模型时明确失败、去背景与图层、
投放尺寸进图片墙、对话（多步等待确认 → 确认执行、多步取消、无关请求不出计划、规划详情）、批量处理与 zip 下载、文生图失败提示。
最后检查浏览器控制台没有报错、除登录前读当前用户的 401 外没有 4xx/5xx。

```bash
# 先启动中间件、图像服务、Java 服务和 npm run dev
npm run e2e                                   # 截图与 summary.json 在 e2e/output/
E2E_CHAT=0 npm run e2e                        # 跳过对话部分（对话会调用规划模型）
E2E_BROWSER=/path/to/chromium npm run e2e     # 不用本机 Chrome 时
```

对话部分会真实调用配置的规划模型（4 次请求）。走查记录见 `docs/evidence/ui-walkthrough/`。
