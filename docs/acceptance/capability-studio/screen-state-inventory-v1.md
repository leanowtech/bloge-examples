# Capability Studio Screen State Inventory v1

> 状态：工作基线，未签署。`BROWSER_SMOKE_VERIFIED` 只表示列出的 `READY` 状态通过指定视口的真实 Chrome 检查，不表示异常状态、运行、读屏、人工可用性或产品验收通过。

## 通用状态合同

每个任务视图必须覆盖 `LOADING`、`READY`、`EMPTY`、`ERROR`、`FORBIDDEN`、`CONFLICT`、`STALE` 和 `OFFLINE`。失败反馈同时说明发生了什么、影响范围、主要恢复动作，以及未保存内容是否保留。技术 ID、fingerprint 和 Raw JSON 默认折叠。

| 状态 | 必须展示 | 主要恢复动作 | 证据 |
|---|---|---|---|
| `LOADING` | 当前正在读取的业务对象 | 允许取消或返回 | `aria-busy`、DOM |
| `EMPTY` | 为什么为空、需要什么前置资产 | 创建或选择推荐样例 | DOM、协议 |
| `ERROR` | 稳定错误码、影响、恢复动作 | 原地重试，不丢草稿 | DOM、失败协议 |
| `FORBIDDEN` | 缺少的权限与可申请范围 | 发起申请或返回 | DOM、安全审计 |
| `CONFLICT` | 本地与服务端 revision 差异 | 比较、重载、另存分支 | DOM、并发协议 |
| `STALE` | 漂移来源和受影响资产 | 重新编译或迁移 | DOM、影响报告 |
| `OFFLINE` | 当前仅可读取的缓存范围 | 恢复连接后重试 | DOM、网络记录 |

## Golden Path 状态矩阵

| GP | 路由 / 主视图 | READY 验收信号 | 重点异常与恢复 | DOM / 视觉证据 | 当前状态 |
|---|---|---|---|---|---|
| `GP-01` | `/capabilities/` 能力总览 | 4 API、1 Feature、1 Tool、9 Case；主要动作是查看订单查询契约 | Demo Pack 不可用时显示错误码、影响和重试 | `data-testid=capability-overview`；中文与英文 1440/1024/390 Chrome | `BROWSER_SMOKE_VERIFIED_READY` |
| `GP-02` | 订单信息查询契约 | 输入、成功结果、错误、副作用、Owner、SLA、敏感度 | 契约不完整时拒绝猜测，返回总览 | `data-testid=capability-contract`；中文 1440 Chrome API 选择 | `BROWSER_SMOKE_VERIFIED_ZH` |
| `GP-03` | 场景数据中心 | Dataset 分母、生命周期、分类、Owner、九条 Case、五项质量覆盖；选择 Case 后显示业务目标、来源、Oracle、适用契约、依赖表现与精确引用 | 空筛选可清除；网络、Schema、跨 Scope、重复引用、契约闭包、质量漂移或 Active readiness 失败时 fail closed 并提供重试 | `data-testid=capability-scenarios` / `capability-scenario-details`；严格前后端协议与 Test Kit；中英文三视口 Chrome；Tab/Space 键盘选择；真实 axe | `BROWSER_SMOKE_VERIFIED_STAGE0_PROJECTION` |
| `GP-04` | Tutorial Branch 行为编辑器 | 以「当什么条件、依赖如何表现、持续多久」编辑；保存生成数据库 revision；预检显示未解析依赖、真实调用和 fallback | 409 保留未保存值并可重载最新版本；断网与非法响应显示原因、影响和恢复动作 | `data-testid=capability-tutorial-branch`；数据库 CAS/重建/漂移测试；中文实际保存/预检；英文 1024 Chrome；Test Kit 实际 HTTP 互验；真实 axe | `BROWSER_SMOKE_VERIFIED_DURABLE_AUTHORITY` |
| `GP-05` | Feature DAG + Data Lens | 四 API、聚合、决策、节点状态、边值与 fingerprint 来自同一次 Trace；桌面完整展开，移动端内部滚动 | 结构权限隐藏输入、输出和边值，但保留 topology、状态与 fingerprint；Payload 权限显示受控演示数据 | `data-testid=capability-feature-rehearsal` / `feature-dag`；中英文 1440/1024/390 Chrome；几何、键盘和 axe | `BROWSER_SMOKE_VERIFIED_TRACE_PROJECTION` |
| `GP-06` | Feature 隔离运行 | 补偿历史原始尝试为 `TIMEOUT`，fallback 后聚合与决策安全继续，Feature 最终 `PASSED` 且真实调用为 0 | 未知 Case、非法控制计划和 Fixture 未命中均失败关闭；部署级 network deny 尚未证明 | 实际 BLOGE RunTrace、4 API fail-fast connector counter、24 并发、稳定 fingerprint、真实 Chrome | `PARTIAL_TEST_OWNED_RUNTIME` |
| `GP-07` | Tool 契约 | 输入、输出、错误、禁止结果、副作用、exact dependency 和 9 × 3 正确性目标；技术证据默认折叠 | 依赖 stale 时显示影响和迁移动作；运行失败在原位说明影响并可重试 | `data-testid=capability-tool` / `run-governed-baseline`；中文 1440/390 Chrome、axe、无溢出 | `BROWSER_SMOKE_VERIFIED_ZH` |
| `GP-08` | Tool 受治理 9 × 3 运行 | 9/9 场景、9/9 Oracle、3/3 轮次、27/27 业务断言、0；3 suite、9 × 3 Case 矩阵、稳定业务指纹、三项专项证明；“开发验证通过/发布仍不可验收”双结论和 `CERTIFIABLE` 等级；候选已绑定时显示制品、source commit 与 execution intent，并保留目标环境、部署级 egress、Owner 签署三项阻断 | child evidence 缺失/漂移、Resource descriptor 未解析、候选 intent 篡改、Oracle 失败或真实调用均失败关闭；失败态为 `NOT_VERIFIED`，不伪造 evidence class、publication、Run 或 fingerprint；原位显示恢复动作并允许整批重试 | 受治理 POST、v3 严格 Schema、独立 Test Kit、真实 Spring + Chrome、DOM 基数、axe | `PARTIAL_DEVELOPMENT_VERIFIED` |
| `GP-09` | 场景数据中心 → 质量与影响 | 首屏并列覆盖事实与准入结论：五项覆盖均为 100%，但 9 个 Case 均为 `DRAFT`、0 个 `ACTIVE`、新鲜度 `UNVERIFIED`，所以准入 `BLOCKED`；选择 Case 后只呈现其 Source、Oracle、Contract、runtime dependency 与 Target 影响闭包；0 个孤立 Case | `NO_ACTIVE_CASES`、`FRESHNESS_EVIDENCE_MISSING`、Schema/权限/网络失败均显示发生了什么、影响和恢复动作；失败时不以本地常量补图；页面明确「当前投影不导出 Payload」不等于源数据已经语义脱敏 | `data-testid=capability-quality-impact`；严格 v1 Schema/Test Kit；真实 wire fingerprint/闭包/37 节点/81 边/9 Case 基数；中文 1440/390 真实 Chrome；Case 切换后 9 节点/8 边高亮；axe serious/critical 为 0；无横向溢出 | `PARTIAL_DEVELOPMENT_VERIFIED_QUALITY_IMPACT` |
| `GP-10` | Tool 精确证据 + Feature Deep Link | 从 9 × 3 矩阵读取原 child run，不重跑；展示 Tool、Contract、Dataset、Case、runtime target、Binding Plan、Fixture、Behavior、依赖、source map、provenance 和结构级 Data Lens；进入 Feature 后聚焦原节点，刷新与返回保持同一 Run/Case/Node | 未知 Run/Case 返回可恢复 404；合同漂移、指纹篡改、引用缺失或越权时失败关闭，不展示部分可信结果 | `data-testid=capability-governed-run-evidence` / `feature-dag`；严格 v1 Schema、独立 Test Kit、真实 Spring + Chrome；中文 1440/1280/390，无页面横向溢出 | `PARTIAL_DEVELOPMENT_VERIFIED_EXACT_READ` |

## 视觉与可访问性基线

- 中文与英文分别覆盖 1440×900、1024×768、390×844。
- 桌面采用 240px 左侧任务导航、自适应主区和约 320px 就绪区；移动端折叠为单列任务选择器。
- Auto Layout、边标签、数据摘要、长中文名和最长技术引用不得重叠或改变固定控件尺寸。
- 所有任务、筛选、详情和恢复动作可用键盘完成；状态不只依赖颜色。
- 截图只证明视觉状态，不能替代 DOM、协议、运行、网络和 exact-ref 断言。
- 业务主界面不得直接显示 `NO_GO`、`NOT_RUN`、`METADATA_READY_RUNTIME_EVIDENCE_PENDING` 等内部枚举；对应机器值保留在协议或折叠技术详情中，主界面显示可行动的业务状态。

## 当前截图证据

| 文件 | 已证明 | 未证明 |
|---|---|---|
| [`capability-studio-gp01-gp03-zh-1440.png`](../../assets/capability-studio/capability-studio-gp01-gp03-zh-1440.png) | 中文桌面 Dataset 分母、质量摘要、九条 Case、超时行为详情、验收阻断可见 | 英文、键盘全路径、读屏、Dataset 写入 Authority、运行结果 |
| [`capability-studio-gp01-zh-1024.png`](../../assets/capability-studio/capability-studio-gp01-zh-1024.png) | 中文紧凑桌面布局、无页面级横向溢出 | 全部任务视图与异常状态 |
| [`capability-studio-gp03-zh-390.png`](../../assets/capability-studio/capability-studio-gp03-zh-390.png) | 移动端任务选择器、Dataset 摘要、无页面级横向溢出 | 移动端完整异常恢复与键盘路径 |
| [`capability-studio-gp03-quality-zh-390.png`](../../assets/capability-studio/capability-studio-gp03-quality-zh-390.png) | 移动端五项质量覆盖、搜索、筛选与有界 Case 列表 | Case 详情全路径与读屏 |
| [`capability-studio-gp04-zh-1440.png`](../../assets/capability-studio/capability-studio-gp04-zh-1440.png) | 中文桌面业务句式编辑、分支安全边界、实际保存后的隔离预检反馈、无页面级溢出 | 英文/移动端、409/断网视觉、持久化 Authority、业务签署 |
| [`capability-studio-gp01-gp03-en-1440.png`](../../assets/capability-studio/capability-studio-gp01-gp03-en-1440.png) | 英文桌面导航、Dataset、质量、Case 主从详情、无横向溢出和真实 axe 通过 | 权威业务数据本地化、异常状态、运行与人工读屏 |
| [`capability-studio-gp01-gp03-en-1024.png`](../../assets/capability-studio/capability-studio-gp01-gp03-en-1024.png) | 英文紧凑桌面 Tutorial Branch、业务句式编辑与无横向溢出 | 完整保存路径、异常恢复和键盘全路径 |
| [`capability-studio-gp01-gp03-en-390.png`](../../assets/capability-studio/capability-studio-gp01-gp03-en-390.png) | 英文移动端任务选择、Dataset 摘要、可见焦点和无横向溢出 | 移动端 Case 详情、异常恢复与人工读屏 |
| [`capability-studio-gp05-gp06-structure-zh-1440.png`](../../assets/capability-studio/capability-studio-gp05-gp06-structure-zh-1440.png) | 中文桌面超时场景、结构权限、隔离绑定和零真实调用 | 字段级 source map、身份授权和发布候选证据 |
| [`capability-studio-gp05-gp06-dag-payload-zh-1440.png`](../../assets/capability-studio/capability-studio-gp05-gp06-dag-payload-zh-1440.png) | 完整 6 节点、5 边 DAG；稳定业务顺序；源节点/边中心对齐；Data Lens 可见 | 更大复杂度 DAG、人工视觉签署、真实业务 Payload |
| [`capability-studio-gp05-gp06-payload-en-1024.png`](../../assets/capability-studio/capability-studio-gp05-gp06-payload-en-1024.png) | 英文紧凑桌面、键盘切换受控数据、节点与边可读 | 人工读屏、异常状态三视口 |
| [`capability-studio-gp05-gp06-payload-zh-390.png`](../../assets/capability-studio/capability-studio-gp05-gp06-payload-zh-390.png) | 中文移动端任务和场景选择、页面无横向溢出、DAG 区域内部滚动 | 移动端完整 DAG 阅读效率和人工可用性签署 |
| [`capability-studio-gp07-gp08-governed-tool-v2-zh-1280.png`](../../assets/capability-studio/capability-studio-gp07-gp08-governed-tool-v2-zh-1280.png) | 真实服务与浏览器触发的 v2 中文桌面结果；开发通过/发布不可验收、9/9 Oracle、27/27 断言和 `EXPLORATORY` 等级同屏可见 | 英文/1024、异常恢复、部署级 egress、Owner 签署和正式候选验收 |
| [`capability-studio-gp08-business-oracles-v2-zh-1280.png`](../../assets/capability-studio/capability-studio-gp08-business-oracles-v2-zh-1280.png) | 九个 Case 的业务结果指纹、业务判定和三轮 `1/1` 断言完整可见；结果表 `scrollWidth=clientWidth=553` | 业务 Owner 对 Oracle 语义的人工签署 |
| [`capability-studio-gp08-release-blockers-v2-zh-1280.png`](../../assets/capability-studio/capability-studio-gp08-release-blockers-v2-zh-1280.png) | timeout、duplicate、forbidden-write 三项专项证明与五项发布阻断同时可见 | 候选、环境、可认证证据、部署级 egress 和 Owner 签署本身 |
| [`capability-studio-gp08-governed-tool-v2-zh-390.png`](../../assets/capability-studio/capability-studio-gp08-governed-tool-v2-zh-390.png) | 390 × 844 真实 Chrome 下双结论、三轮摘要、五列表格和稳定结果完整可读；页面与结果表横向溢出均为 0 | 移动端人工可用性、读屏和失败恢复矩阵 |
| [`capability-studio-gp09-quality-admission-zh-1440.png`](../../assets/capability-studio/capability-studio-gp09-quality-admission-zh-1440.png) | 中文桌面同屏呈现五项 100%、9/0/0、两个业务阻断与 Payload 边界；真实响应直达 UI，页面无横向溢出且 axe serious/critical 为 0 | 正式数据准入、语义脱敏、英文/1024、人工读屏与 Data Owner 签署 |
| [`capability-studio-gp09-case-impact-zh-1440.png`](../../assets/capability-studio/capability-studio-gp09-case-impact-zh-1440.png) | 选择「补偿历史超时」后，Owner、Source、Oracle、Contract、四个依赖、Target 及 9 节点/8 边高亮闭包可读 | 更大客户图的视觉可扩展性、完整键盘与异常恢复矩阵 |
| [`capability-studio-gp09-quality-admission-zh-390.png`](../../assets/capability-studio/capability-studio-gp09-quality-admission-zh-390.png) | 390 × 844 首屏清楚呈现当前任务、准入阻断与新鲜度状态，无页面横向溢出 | 移动端六人可用性和读屏签署 |
| [`capability-studio-gp09-case-impact-zh-390.png`](../../assets/capability-studio/capability-studio-gp09-case-impact-zh-390.png) | 移动端 Case 详情与影响关系列表稳定降级，文本对比度通过真实 axe | 移动端更大图浏览效率、失败恢复和 Data Owner 签署 |
| [`capability-studio-gp10-exact-evidence-zh-1280.png`](../../assets/capability-studio/capability-studio-gp10-exact-evidence-zh-1280.png) | 原 `runId`、timeout 焦点节点和完整受治理引用闭包同屏可见；明确标注未重跑 | 目标环境、Payload replay、外部 Evidence Authority 和签署 |
| [`capability-studio-gp10-exact-graph-context-zh-1440.png`](../../assets/capability-studio/capability-studio-gp10-exact-graph-context-zh-1440.png) | Deep Link 上下文保持；Feature DAG 只呈现同一 graph path 的 6 个业务节点，完整 7 节点 Data Lens 保留外层 Tool 运行 | 英文/1024、人工视觉签署和更复杂嵌套图 |
| [`capability-studio-gp10-exact-evidence-zh-390.png`](../../assets/capability-studio/capability-studio-gp10-exact-evidence-zh-390.png) | 移动端原运行证据、焦点与引用闭包可读，页面横向溢出为 0 | 移动端读屏和异常恢复矩阵 |
| [`capability-studio-gp10-exact-graph-context-zh-390.png`](../../assets/capability-studio/capability-studio-gp10-exact-graph-context-zh-390.png) | 移动端 Feature 子图和完整 Data Lens 的信息边界可见 | 移动端 DAG 阅读效率的六人可用性签署 |
