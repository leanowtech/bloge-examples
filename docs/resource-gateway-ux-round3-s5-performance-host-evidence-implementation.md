# Resource Gateway UX Round 3 S5 性能、宿主与现场证据实现说明

> 状态：E2 engineering implemented / E3-E4 awaiting real organization evidence
>
> 实施日期：2026-08-09
>
> 对应计划：[Round 3 资深体验审阅与演进计划](resource-gateway-ux-round3-expert-audit-and-evolution-plan.md)

## 1. 本阶段解决什么问题

S5 处理的不是视觉润色，而是三个发布级问题：访问任一路由却同步下载全部工作面；VS Code dispose 只能
寄希望于浏览器事件；95 分缺少可执行的真实用户和连续组织运行证据。

本阶段把三项要求变成工程协议：

```text
RouteChunkBudget       路由按需加载、意图后预取、production 构建硬门禁
WebViewHostBridge      API/recovery 消息协议、加密宿主存储、可等待 disposal receipt
UxEvidenceContract     12 人固定任务、VS Code P75、两团队两周期自动判定
```

## 2. 路由性能改造

`App.tsx` 不再同步导入 Author、Library、Rehearsal 与 Showcase。当前路由使用 React `lazy + Suspense`，
其它路由只有在导航获得 pointer/focus 意图后才预取。稳定 author domain 被拆成独立命名缓存单元，避免
`AuthorCanvas` 成为超大单块解析任务。

改造前后 production minified 体积：

| 单元 | S4 | S5 |
|---|---:|---:|
| 同步应用主包 | `867.23 kB` | `151.44 kB` |
| Author 交互块 | 混在主包 | `315.15 kB` |
| Author domain | 混在主包 | `73.28 kB` |
| Library | 混在主包 | `138.73 kB` |
| Rehearsal | 混在主包 | `69.13 kB` |
| Showcase | 混在主包 | `19.97 kB` |

构建后的 `check-route-chunk-budget.mjs` 会检查：应用壳 `<=180 KiB`、每个应用 chunk `<=350 KiB`、五个
命名工作单元必须存在。它还会读取 Vite manifest，递归汇总“壳 + 当前路由 + 所有静态 JS/CSS 依赖”的
gzip 闭包；最重的 Author 首次传输为 `320.40 KiB`，低于 `350 KiB`。这样既不把 vendor 归咎于某个
业务 chunk，也不靠拆小文件掩盖总传输量。`npm run build` 已串入两层门禁。

## 3. VS Code WebView 宿主协议

浏览器继续使用 fetch 与 session recovery；只有检测到 `acquireVsCodeApi` 时，入口才动态加载
`VsCodeWebviewBridge`。桥接器提供：

| 协议 | 用途 |
|---|---|
| `bloge.vscodeWebviewRequest.v1` | `FETCH / RECOVERY_LOAD / RECOVERY_SAVE / RECOVERY_REMOVE` |
| `bloge.vscodeWebviewResponse.v1` | 按 `requestId` 返回响应或稳定错误码 |
| `bloge.vscodeHostWillDispose.v1` | extension host 请求关闭前准备 |
| `bloge.vscodeHostDisposalReceipt.v1` | 所有 authoring surface flush 完成后的 ready/failure 汇总 |

Recovery store 在 WebView 中明确标记为 `HOST_ENCRYPTED`。请求超时、畸形 status、未知 response 或桥接器
提前销毁都 fail closed；pending 请求会被拒绝，不会无限悬挂。协议传输业务 payload 是执行职责所需，
但桥接器和测试不记录 payload、credential 或 response body。

`useWorkspaceContinuity` 现在会加入统一 disposal barrier。宿主只有在 receipt `ready=true` 后才能销毁
面板；flush 返回 `false`、抛错或超过宿主 deadline 时都返回 `ready=false`，receipt 带 failure count 与
`timedOut`，extension 必须保留面板并允许重试。

## 4. E3/E4 可执行证据计划

新增版本化、无业务 payload 的 `resourceGateway.uxEvidence.v1`。评分器不接受“页面看起来不错”或空样本：

- 12 名参与者，每种角色至少 3 名；
- 核心任务、上下文识别、证明权威判断均至少 95%；
- 至少 2 名真实 VS Code 用户，cold start P75 不超过 2 秒；
- P0/P1 为 0；
- 两个团队各两个周期，静默丢失、跨环境误操作、Mock 误发布均为 0。

详细招募、固定任务、主持纪律和录入方式见
[E3/E4 现场验证手册](resource-gateway-ux-round3-e3-e4-field-study-runbook.md)。模板故意是 `NOT READY`，
避免未采集值被当成通过。

## 5. 验证状态

| 门禁 | 当前结果 |
|---|---|
| 路由 lazy 与意图后预取 | 自动化通过；四个重工作面无同步 App import |
| production chunk budget | 通过；壳 `147.89 KiB`，最大应用块 Author `307.77 KiB`，Author 启动 gzip 闭包 `320.40 KiB` |
| host request/recovery correlation | 组件协议测试通过 |
| disposal barrier | receipt 在 recovery flush 完成后才发出；`false/reject/timeout` fail closed；hook 集成测试通过 |
| evidence evaluator | 正向/反向 Node 测试通过；空模板返回 `NOT READY` |
| 前端全量测试与 production build | `96` 个文件、`728` 项测试全绿；TypeScript、Vite 与 chunk gate 通过 |
| Java / Maven | S5 无 Java wire contract 修改；`VisualAuthoringAppJsTest` 29 项通过；S4 同基线全量 `5,898` 项已通过 |
| 真实 VS Code E3 | 未执行，不能宣称通过 |
| 两团队两周期 E4 | 未执行，不能宣称通过 |

## 6. 阶段复评

S5 的 E2 工程目标已经具备实现和门禁：同步主包下降约 82.5%，所有应用 chunk 低于预算；宿主 disposal
从单向通知升级为等待 recovery 的协议；E3/E4 从文字愿望升级成可机器判定的证据合同。

仍不能关闭的差距只有外部事实：真实 VS Code cold start P75、12 名目标角色任务数据、两个团队连续两个
发布周期。这些不能由更多单元测试生成。当前应把工程阶段标记为完成、现场证据阶段标记为待执行，并继续
保持对外成熟度上限 89，直到评分器对真实证据返回 `PASS`。
