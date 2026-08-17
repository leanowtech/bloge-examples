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
| `GP-05` | Feature DAG + Data Lens | 四 API、转换、规则、Feature 输出和边值血缘无遮挡 | 无 Payload 权限时仍显示结构与差异类型 | Canvas DOM、像素检查、截图 | `MISSING` |
| `GP-06` | Feature 隔离运行 | 补偿历史为 `TIMEOUT`，Oracle 要求人工复核，真实调用为 0 | 缺 Binding 时预检阻断并定位调用点 | RunTrace、网络 deny、差异定位 | `MISSING` |
| `GP-07` | Tool 契约 | 输入、输出、错误、禁止结果、副作用和 exact dependency | 依赖 stale 时显示影响和迁移动作 | 契约 DOM、协议闭包 | `PARTIAL_READ_ONLY_SUMMARY` |
| `GP-08` | 九场景批量运行 | 五轴结论、9/9、三次语义一致、真实调用为 0 | 失败可重试失败项并恢复 Baseline | Batch DOM、RunTrace、语义 fingerprint | `MISSING` |
| `GP-09` | 数据质量与影响 | 来源、脱敏、新鲜度、覆盖、复用、影响；无孤立 Active Case | stale/quarantined 有明确修复或退役动作 | 质量投影、影响图、DOM | `MISSING` |
| `GP-10` | Evidence + Deep Link | exact capability/dataset/binding/run 闭包；返回正确节点和场景 | Evidence stale/tampered 时拒绝并显示恢复入口 | Evidence verifier、刷新与返回测试 | `MISSING` |

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
