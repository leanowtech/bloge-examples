# Capability Studio 验收制品

> 当前状态：`NO_GO`
>
> 本目录记录 Capability Studio 的治理边界、验收合同、证据追踪和未通过实例。GP-01 至 GP-08 已形成 Stage 0 开发切片，Canonical 运行证据已达到 `CERTIFIABLE`；GP-09/10、目标环境候选认证、部署级 egress 和人工签署仍未闭合。任何局部完成都不表示整体产品验收通过，也不替代产品与架构签署。

## 1. 制品清单

| 制品 | 路径 | 用途 | 当前结论 |
|---|---|---|---|
| 资产投影 ADR | [`ADR-007`](../../adr/ADR-007-capability-studio-asset-projections-and-compiler-boundary.md) | 固定对象权威、编译和生产隔离边界 | `Proposed` |
| Acceptance Baseline v1 | [`capability-studio-acceptance-baseline-v1.json`](capability-studio-acceptance-baseline-v1.json) | 冻结 GP、黄金包、Spike、可用性、安全和 NFR 门禁 | `NO_GO` |
| Stage Acceptance Result v1 Schema | [`capability-studio-stage-acceptance-result-v1.schema.json`](../../schemas/resource-gateway-capability-studio/capability-studio-stage-acceptance-result-v1.schema.json) | 固定 `S*-AC-*` 单条验收结果、五项统一前置条件、证据和签署状态机 | 兼容协议，可消费，不代表任何合同已通过 |
| Stage Acceptance Result v2 Schema | [`capability-studio-stage-acceptance-result-v2.schema.json`](../../schemas/resource-gateway-capability-studio/capability-studio-stage-acceptance-result-v2.schema.json) | 固定 `AC-STD-01..09`、候选执行绑定、签署前证据闭包和正式 Stage 退出状态 | 语义协议已定义，外部 Evidence/签名 Authority 尚未闭合 |
| Golden Path Manifest Schema | [`capability-studio-golden-path-acceptance-manifest-v1.schema.json`](../../schemas/resource-gateway-capability-studio/capability-studio-golden-path-acceptance-manifest-v1.schema.json) | 约束发布候选验收证据的机器结构 | 可消费，未证明通过 |
| Golden Path NO_GO fixture | [`capability-studio-golden-path-acceptance-manifest-v1.no-go.fixture.json`](capability-studio-golden-path-acceptance-manifest-v1.no-go.fixture.json) | 提供真实的初始缺证据状态 | `NO_GO` |
| Governed Baseline Schema | [`capability-studio-governed-baseline-v3.schema.json`](../../schemas/resource-gateway-capability-studio/capability-studio-governed-baseline-v3.schema.json) | 固定 GP-07/08 的 9 × 3 矩阵、逐 Case 业务 Oracle、部署候选与 canonical execution intent；失败态强制 `NOT_VERIFIED` | 当前为 `DEVELOPMENT_TEST_OWNED / CERTIFIABLE / NO_GO` |
| Screen State Inventory v1 | [`screen-state-inventory-v1.md`](screen-state-inventory-v1.md) | 逐个 GP 固化页面状态和恢复动作 | GP-01 至 GP-08 已有开发证据；GP-09/10 仍缺失 |
| Requirement Traceability v1 | [`requirement-to-evidence-traceability-matrix-v1.md`](requirement-to-evidence-traceability-matrix-v1.md) | 把 GP、Spike、安全和 NFR 绑定到证据与责任人 | 缺口已显式列出 |

跨工具 JSON Schema 位于 [`docs/schemas/resource-gateway-capability-studio/`](../../schemas/resource-gateway-capability-studio/)，因为它们是稳定协议，而不是某一个验收运行的私有 fixture。

## 2. 当前事实

审计确认现有仓库拥有可复用底座，包括 Graph/Canvas、Correctness Studio、Scenario/Fixture 编译、Mirror 隔离和 Run Evidence。当前已经新增：

- 默认 `/capabilities/` 入口和只读 `CapabilityAsset` 产品投影；
- 4 API、1 Feature、1 Tool、9 Case 的 payload-free Canonical Golden Demo Pack；
- Canonical Baseline 与 Tutorial Branch 的不可混用引用及加载期不变量；
- GP-01 至 GP-03 的组件、严格协议、真实 HTTP 路由和启动脚本探针；
- GP-01 至 GP-03 的中文真实 Chrome 烟测，覆盖 1440×900、1024×768 和 390×844；
- Capability Studio 的 Baseline/Manifest Schema、初始 `NO_GO` fixture 和追踪矩阵。
- `Stage Acceptance Result v1` 严格 Schema 与独立 Test Kit verifier；它将 `NOT_RUN`、`BLOCKED`、`FAIL`、`PARTIAL`、`PASS` 固定为互斥状态语义，禁止 `PARTIAL` 退出 Stage，并对 `PASS` 的五项前置条件、执行矩阵、证据可用性、P0/P1 阻断和 Owner 签署失败关闭。协议可复验不表示当前 Stage 0 已通过；
- `Stage Acceptance Result v2` 严格 Schema 与独立 Test Kit 语义 verifier；它只承载正式 `STAGE_EXIT`，要求 `AC-STD-01` 至 `AC-STD-09` 精确集合，并把结果身份、候选执行、完整环境/egress 投影、检查和签署前 Evidence 绑定为可复算闭包。`NOT_RUN` 不允许伪造执行时间；运行前与运行中 `BLOCKED` 使用互斥时间语义；候选、环境、egress、签署投影与 `AC-STD-01/06/09` 必须一致；`PASS` 要求干净候选、完整观测窗口、零真实/被拒外呼尝试和三类 Owner 签署。默认 verifier 不解析外部 Evidence，不验签名公钥、签发者权限或 Owner 身份，因此只能证明结果语义自洽，不能单独把 Stage 0 从 `NO_GO` 改为 `ACCEPTED`；
- GP-04 的教程分支业务句式编辑器，用户通过条件、表现和持续时间配置超时，无需编辑 Mock JSON；
- GP-04 的数据库 head、immutable revision、严格保存协议和隔离预检，以及 409 冲突保留输入、结构化恢复动作和断网分类测试；
- GP-04 的 SQL 原子 optimistic CAS、同版本并发单赢家、stale 同内容重试幂等、Authority 重建恢复和 Canonical Baseline 漂移失败关闭测试；
- GP-04 的四份严格 JSON Schema、独立 Test Kit 内容指纹重算和真实 HTTP before/after/preflight 互验；
- GP-04 中文 1440×900 真实 Chrome 保存与预检烟测。
- Scenario Dataset v1 的严格 payload-free 投影 Schema、由 Golden Demo Pack 确定性生成的服务端投影端点，以及独立 Test Kit 的指纹、Scope、引用闭包、质量计数和 Active readiness 验证器。每个 Case 现在显式携带四个 API 的完整 `RUNTIME_CONTROL`；幂等、禁止写入等 `BUSINESS_EXPECTATION` 单独进入 source map，不再被误编译为 Tool fixture，也不能虚增运行闭包覆盖率；
- GP-03 已从静态九行表切换为真实 Dataset master-detail 视图，显示 Dataset 分母、生命周期、分类、Owner、五项质量覆盖、Case 业务目标、来源、Oracle、适用契约、依赖表现与精确引用；协议、网络或语义校验失败时拒绝展示并提供恢复动作；
- GP-03 Case 详情已把原有混合的「依赖表现」拆成「隔离运行依赖」与「业务正确性要求」：前者展示四个被控制的 API 及其表现，后者展示幂等、禁止写入等 Oracle 义务；无额外义务时明确说明由 Oracle 校验业务结果；
- GP-03 中文 1440×900 与 390×844 真实 Chrome 烟测，覆盖 Dataset 摘要、质量状态、九条 Case、超时案例详情、移动端筛选与无横向溢出。
- Dataset 经确定性适配器进入既有 `ScenarioDraftSet`，再委托既有 `ScenarioGovernedCompiler` 生成 FixtureBundle/TestSuite 注册计划；Canonical 9 Case、RETURN/ERROR/TIMEOUT/顺序消费/`MUST_NOT_CALL`、rule source map、exact target/contract/Scope/Authority、三次确定性和 fail-closed 负向语义已有自动化证据。source map 现已转换为强类型、稳定排序的 exact-ref provenance，并参与 FixtureBundle/TestSuite 内容寻址；`CapabilityStudioGovernedAssetPublisher` 只通过既有 Registry 注册，写后独立回读并复算内容；`CapabilityStudioGovernedCandidateService` 再以同一 exact suite 调用既有 `TestSuiteExecutionService`。完整闭包以可逆字典清单进入 aggregate evidence，Canonical suite metadata 为 11,863 bytes，低于 16,384 bytes 协议上限；child evidence 使用 suite ref 与 provenance/source-map fingerprint 做紧凑绑定。真实 Spring test profile 已完成同一注册闭包的 3 轮 9 Case 执行：3 个 suite run、27 个唯一 child run 全部通过，进程内真实外部调用为 0；旧 Scenario 编译路径不注入这些字段，内容寻址保持兼容；
- Data Lens 后端窄切片直接投影既有 `TestRunEvidence.nodeTrace/edgeTrace`，支持 structure-only 与 payload-visible 权限态、稳定执行坐标、值 fingerprint、重试/回退、首个运行时差异和高基数截断；该投影已接入 GP-05/06 Feature API 与工作区；
- zero-egress 窄 Spike 已让 9 个 Canonical Case metadata 通过真实 `TestRunService` 与 `HttpResourceOperator` delegate 边界运行；任何真实 delegate 调用都会计数并 fail-fast，当前 connector counter 为 0，fallback-to-real 在执行前拒绝。该证据使用 test-owned runtime material，只证明进程内控制反转，不等同于部署级 network deny，也不构成 9/9 业务 Oracle 验收；
- GP-05/06 已新增 test/staging-only Feature Rehearsal API。该 API 用实际 BLOGE Graph 执行四个 `HttpResourceOperator`、一个纯聚合节点和一个纯决策节点，并把同一次 `TestRunEvidence` 投影为 6 个节点、5 条边和 Data Lens；超时会取消下游，任何 HTTP delegate 调用均 fail-fast；
- Feature Rehearsal v1 已有严格 JSON Schema 和独立 Test Kit verifier。它校验 6 节点、5 条边、Run/Data Lens 身份一致、调用点边闭包、权限投影、可见 Payload 指纹、Data Lens 指纹和投影声明的零真实调用；真实服务的 `STRUCTURE_ONLY` 响应已通过该 verifier。该结果不替代部署级 network deny、企业身份授权或 Graph/semantic fingerprint 的可信来源证明；
- `GET /api/capability-studio/feature-rehearsal-baseline` 已提供 payload-free 的开发基线：固定 9 个 Case 各运行 3 次，共产生 27 个唯一 `runId`。独立业务 Oracle 覆盖标准结论、乘客无责、司机责任、政策缺失、空历史、预期超时、重复幂等、禁止写入和政策版本回归；超时 Case 保留依赖原始 `TIMEOUT` 尝试，BLOGE fallback 后最终 `PASSED` 并输出 `MANUAL_REVIEW / COMPENSATION_HISTORY_TIMEOUT`。两批并发运行仍保持 54 个唯一 `runId`，进程内真实调用计数为 0。严格 v1 Schema 与独立 Test Kit verifier 已对真实响应校验 Case/Oracle/Run/状态/fingerprint/operator/诊断闭包。该投影强制标记为 `DEVELOPMENT_TEST_OWNED`，不是 `S0-AC-04 PASS`；
- `POST /api/capability-studio/governed-baseline` 已成为 GP-07/08 Tool 页的真实运行入口。该 test/staging-only 端点无调用方 payload 和身份覆盖，复用上述受治理 compiler/Registry/suite runtime，严格要求 3 个唯一 suite run、27 个唯一 child run、9 个 Case 每轮恰好一次、全部通过、三轮 publication/provenance/source-map 不漂移且进程内真实调用为 0。Suite 完成后，服务端通过既有授权 API 回读每条完整签名 child evidence，校验 run/target/Fixture/integrity，导出 payload-free evidence fingerprint、semantic result fingerprint、断言与 Fixture 控制计数；同 Case 三轮结果指纹必须一致，timeout/duplicate/forbidden-write 由结构化事实形成专项证明。v3 新增部署侧候选绑定和 canonical execution intent：启动器只在实际 JAR SHA-256、Git commit 与 `CLEAN` source tree 同时成立时注入候选，调用方不能覆盖；Test Kit 会独立重算 intent 并拒绝篡改。成功投影固定 `verificationLevel=DEVELOPMENT_VERIFIED`、`DEVELOPMENT_TEST_OWNED / NO_GO`，Evidence 等级按 child evidence 如实投影；失败关闭固定为 `NOT_VERIFIED`，不伪造 evidence class、publication、Run 或 fingerprint；
- GP-07/08 已在 Tool 页上提供“运行 9 × 3 受治理验证”主操作。运行中、原位失败恢复、开发通过/发布不可验收双结论、3 轮 suite 摘要、9 × 3 Case 矩阵、9/9 业务 Oracle、27/27 业务断言、三项专项证明、`CERTIFIABLE` 等级和折叠技术证据已实现。四个 Canonical Resource descriptor 使用应用级 `ResourceRegistry`，`RETURN` 数据以 transport-level 响应经过真实 `HttpResourceOperator` 映射链；未解析 Resource 在调度前失败，output-level 替身保持 `EXPLORATORY`。候选已绑定时显示制品、source commit 和 execution intent，并只保留目标环境认证、部署级 egress 和 Owner 签署三项限制；未绑定时额外保留候选未绑定限制。既有真实浏览器覆盖中文桌面/移动端，无页面横向溢出，axe serious/critical 为 0；
- Feature 工作区提供 9 个 Canonical Case 选择、结构/受控数据双权限态、运行状态、隔离绑定和真实调用计数。桌面 DAG 按稳定业务顺序完整展开，源节点与边中心对齐；移动端使用可聚焦的内部横向滚动区；
- Canonical Feature DAG 已可包装为真实 Tool binding，并经既有 `OperatorMicroGraphRunner -> TestRunService -> BLOGE nested graph` 路径执行；Tool 微图的 nested fixture selector 命中完整 6 节点子图，真实 HTTP delegate 调用为 0。Tool composability manifest 已声明四个 exact Resource 依赖，通用执行目标快照会在运行证据未证明闭包时拒绝认证；仍缺同一 Canonical 注册资产的独立认证结果；
- GP-05/06 的中文真实 Chrome 验收覆盖 1440×900、1440×1100、1024×768 和 390×844；英文覆盖 1024×768。自动化检查 6 节点、5 条边、稳定排序、桌面零横向截断、边对齐、移动端内部滚动、键盘权限切换、页面无横向溢出和 axe serious/critical 为 0；
- 生产运行协议边界已扩展到 fixture、stub、binding override、dependency behavior 和 Dataset 字段族，并在五类运行入口、三组 production profile 上验证 DTO 前拒绝、审计失败关闭和 Payload 不泄漏；
- GP-01/03/04 英文 1440×900、1024×768、390×844 真实 Chrome 证据，以及 GP-05/06 英文 1024×768 证据；覆盖 Dataset 的 Tab/Enter/Space 键盘路径、Feature 权限切换、六种组件状态和真实浏览器完整 axe-core 检查。真实 axe 曾检出并推动修复选中场景辅助文字对比度和 DAG 滚动区焦点问题。

仍未完成的是持久化且具备权限边界的 Dataset Authority、Feature 的字段级 source map 与企业身份授权、目标环境 Candidate attestation、部署级 network deny/egress 观测、契约与 Tutorial 全键盘/人工读屏/并发浏览器矩阵、GP-07/08 的英文与 1024 视口、GP-09/10 的开发切片和六人可用性签署。当前 `/scenario-dataset` 是 Stage 0 Golden Demo Pack 的不可变业务投影，不是客户生产 Dataset 的写入 Authority。当前页面已调用同一受治理编译/应用级 Resource Registry/真实 Resource Operator/注册/执行链路并闭合开发 Oracle，结果为 `DEVELOPMENT_TEST_OWNED / CERTIFIABLE / NO_GO`；证据可信度提升不等于目标环境或发布责任已经闭合，因此不能替代 Owner 签署的 `S0-AC-04` 证据。

因此 Baseline 和 Manifest 必须保持 `NO_GO`、`PENDING` 或 `NOT_RUN`。元数据可读、开发自动化通过和启动探针成功，只能证明当前纵向切片可演示，不能冒充 Capability Studio 产品验收通过。

### 2.1 当前浏览器证据

以下截图由 `CapabilityStudioBrowserAcceptanceTest` 启动真实 Spring Boot 服务，并通过 headless Chrome 执行 GP-01 至 GP-08 后生成。自动化同时断言资产数量、API 选择、九行场景、中文业务状态文案、内部状态码不泄漏、GP-04 实际保存与隔离预检、GP-05/06 Trace 与权限态、GP-07/08 真实受治理 POST 与 9 × 3 矩阵，以及页面级无横向溢出。

| 视口 | 覆盖内容 | 证据 |
|---|---|---|
| 1440×900 | GP-01 总览、GP-02 API 选择、GP-03 Dataset 分母、质量摘要、九条 Case 与超时案例详情 | [`capability-studio-gp01-gp03-zh-1440.png`](../../assets/capability-studio/capability-studio-gp01-gp03-zh-1440.png) |
| 1440×900 | GP-04 业务句式编辑、分支边界、保存后的隔离预检 | [`capability-studio-gp04-zh-1440.png`](../../assets/capability-studio/capability-studio-gp04-zh-1440.png) |
| 1024×768 | GP-01 紧凑桌面布局 | [`capability-studio-gp01-zh-1024.png`](../../assets/capability-studio/capability-studio-gp01-zh-1024.png) |
| 390×844 | GP-03 移动端任务选择、Dataset 摘要 | [`capability-studio-gp03-zh-390.png`](../../assets/capability-studio/capability-studio-gp03-zh-390.png) |
| 390×844 | GP-03 五项质量覆盖、搜索、筛选与有界 Case 列表 | [`capability-studio-gp03-quality-zh-390.png`](../../assets/capability-studio/capability-studio-gp03-quality-zh-390.png) |
| 1440×900 | 英文 GP-01/03、Dataset 质量与 Case 详情；真实 axe serious/critical 为 0 | [`capability-studio-gp01-gp03-en-1440.png`](../../assets/capability-studio/capability-studio-gp01-gp03-en-1440.png) |
| 1024×768 | 英文 GP-04 Tutorial Branch 紧凑桌面 | [`capability-studio-gp01-gp03-en-1024.png`](../../assets/capability-studio/capability-studio-gp01-gp03-en-1024.png) |
| 390×844 | 英文 GP-03 移动端任务选择与 Dataset 摘要 | [`capability-studio-gp01-gp03-en-390.png`](../../assets/capability-studio/capability-studio-gp01-gp03-en-390.png) |
| 1440×900 | GP-05/06 超时场景、结构权限、运行状态和零真实调用 | [`capability-studio-gp05-gp06-structure-zh-1440.png`](../../assets/capability-studio/capability-studio-gp05-gp06-structure-zh-1440.png) |
| 1440×1100 | GP-05/06 受控数据权限下的完整 6 节点、5 边 DAG 与 Data Lens | [`capability-studio-gp05-gp06-dag-payload-zh-1440.png`](../../assets/capability-studio/capability-studio-gp05-gp06-dag-payload-zh-1440.png) |
| 1024×768 | GP-05/06 中文紧凑桌面布局 | [`capability-studio-gp05-gp06-payload-zh-1024.png`](../../assets/capability-studio/capability-studio-gp05-gp06-payload-zh-1024.png) |
| 1024×768 | GP-05/06 英文界面和键盘权限切换 | [`capability-studio-gp05-gp06-payload-en-1024.png`](../../assets/capability-studio/capability-studio-gp05-gp06-payload-en-1024.png) |
| 390×844 | GP-05/06 移动端任务选择、场景选择和有界 DAG 滚动入口 | [`capability-studio-gp05-gp06-payload-zh-390.png`](../../assets/capability-studio/capability-studio-gp05-gp06-payload-zh-390.png) |
| 1440×1100 | GP-07/08 Tool 契约、受治理 9 × 3 开发验证、双结论、3 轮与 9 × 3 Case 矩阵 | [`capability-studio-gp07-gp08-governed-tool-zh-1440.png`](../../assets/capability-studio/capability-studio-gp07-gp08-governed-tool-zh-1440.png) |
| 390×844 | GP-07/08 移动端双结论、三轮摘要和完整 Case 矩阵 | [`capability-studio-gp07-gp08-governed-tool-zh-390.png`](../../assets/capability-studio/capability-studio-gp07-gp08-governed-tool-zh-390.png) |

这些证据关闭了所列中英文开发状态、Dataset 键盘路径、Feature 权限切换、Tool 受治理开发验证和自动 axe 检查，但不覆盖契约/Tutorial 全键盘、人工屏幕阅读器、异常状态三视口、GP-07/08 英文和 1024 视口、不可变发布候选运行证据或人工可用性签署。业务资产名称和说明仍是 Canonical Demo Pack 的中文权威数据，语言切换只承诺产品界面文案，不应误报为业务内容本地化。

## 3. 更新流程

### 3.1 先改合同，再改实现

改变黄金路径的动作顺序、页面反馈、业务预期、资产数量、场景分母、生产隔离策略或签署门槛时，先更新 Baseline、Screen State Inventory 和 Traceability Matrix，并由产品/架构/QA 重新评审。实现不能先行改变验收含义。

不改变验收语义的实现修复，可以只补充对应证据引用和测试结果，但不得删除失败、限制或未覆盖记录。

### 3.2 更新 Baseline

1. 增加 revision，保留旧文件和变更说明。
2. 更新 `goldenPack`、`goldenPaths`、`spikes`、浏览器矩阵、可用性门槛、安全门禁和 NFR 门禁。
3. 所有引用使用 exact ref；没有真实资产时使用 `status: NOT_RUN` 或 `PENDING`，不得编造 pass。
4. 只有对应角色完成签署，Baseline 才能从 `NO_GO` 变为 `APPROVED`。签署字段不能由脚本代填。

### 3.3 生成 Manifest

每个发布候选生成一个新的 Manifest：

1. 记录候选构建、Baseline、Golden Demo Pack、Contract/Dataset/Binding Plan fingerprints。
2. 填写完整的 `gpResults`、9 个 `scenarioResults`、浏览器、可访问性、协议、安全和 egress 结果。
3. 不复制 payload，只写 evidence ref、脱敏摘要和 fingerprint。
4. 未执行的观测使用 `NOT_RUN`，未知计数使用 `null`，不使用 `0` 代替“没有观测”。
5. 结构校验通过不等于验收通过；需要执行 README 第 5 节的跨字段不变量。

## 4. 内容寻址

所有制品都使用 UTF-8 JSON 或 UTF-8 Markdown。JSON 的 fingerprint 计算使用项目统一的 RFC 8785 JCS canonical bytes，并将自身 `artifactFingerprint` 字段归一化为 `null` 后计算 `sha256:<64 位小写十六进制>`。签署信息仍然参与指纹；只有自身 fingerprint 字段被排除，避免循环引用。

Stage 0 Golden Demo Pack 的 `packFingerprint` 绑定整个 payload-free 投影内容。包内尚未物化的子引用仍使用可复算坐标摘要 `sha256("capability-studio-demo-v1|kind|id|revision")`。GP-03 Dataset 投影的根、Case 与 Behavior Profile 已使用内容指纹，Test Kit 可独立重算根指纹并校验 Scope、引用闭包和质量统计，但 Source、Oracle、Contract 等引用仍继承 Stage 0 坐标摘要，且没有持久化 Dataset Authority。GP-04 Tutorial Branch 已使用 test/staging 数据库 Authority：分支 fingerprint 绑定 `schemaVersion`、`branchId`、Canonical Baseline fingerprint、`dependencyId`、condition、behavior 和 duration；head 只做 SQL 原子 CAS，revision 只追加，Test Kit 会从 before/after 投影重算并校验 digest。GP-05/06 返回实际 Feature Graph 内容 fingerprint、Run ID、semantic fingerprint 和 Data Lens fingerprint。GP-07/08 现在从受治理 compiler 产生内容寻址 Fixture/Suite，通过既有 Registry 写后回读，再以确认的 exact suite 生成 3 suite/27 child 开发证据；Canonical `RETURN` 以 descriptor-backed transport fixture 经过应用级 Resource Registry 和真实 Resource Operator 链，child Evidence 为 `CERTIFIABLE`。v3 协议进一步将部署候选、publication、Suite、compilation 和 source map 归一化为 `candidateIntentFingerprint`，由 Test Kit 独立重算，同时闭合每 Case 三轮 semantic fingerprint、业务断言和三类专项 Oracle。但目标环境认证、部署级 egress 和 Owner 签署闭包仍缺失，因此 Manifest 继续保持 `NO_GO`。

`resource-gateway-test-kit` 已提供 `CapabilityStudioAcceptanceVerifier`。它使用随 JAR 打包的两份权威 Schema 校验结构，并检查 GP/Case 精确集合、Case 类型映射、证据闭包、零真实外呼和签署绑定。运行回归：
验证器还会将自身 `artifactFingerprint` 归一化为 `null`，递归排序对象 key 后独立复算内容地址；仅修改说明、时间或状态而不更新指纹也会失败关闭。

```bash
mvn -f resource-gateway-test-kit/pom.xml \
  -Dtest=CapabilityStudioAcceptanceVerifierTest test

mvn -f resource-gateway-test-kit/pom.xml \
  -Dtest=CapabilityStudioScenarioDatasetVerifierTest test

mvn -f resource-gateway-test-kit/pom.xml \
  -Dtest=CapabilityStudioFeatureRehearsalVerifierTest test

mvn -f resource-gateway-test-kit/pom.xml \
  -Dtest=CapabilityStudioFeatureRehearsalBaselineVerifierTest test

mvn -f resource-gateway-test-kit/pom.xml \
  -Dtest=CapabilityStudioGovernedBaselineVerifierTest test

mvn -f resource-gateway-test-kit/pom.xml \
  -Dtest=CapabilityStudioStageAcceptanceResultVerifierTest test
```

也可以用以下命令复算当前 Stage 0 JSON 的临时内容摘要：

```bash
jq -e . docs/acceptance/capability-studio/capability-studio-acceptance-baseline-v1.json
jq -e . docs/acceptance/capability-studio/capability-studio-golden-path-acceptance-manifest-v1.no-go.fixture.json
jq -S -c 'del(.artifactFingerprint) + {artifactFingerprint:null}' \
  docs/acceptance/capability-studio/capability-studio-acceptance-baseline-v1.json \
  | tr -d '\n' | shasum -a 256
```

命令中的 `tr -d '\n'` 不可省略，尾部换行不属于 canonical bytes。`jq -S -c` 也不是通用 RFC 8785 实现，不能单独作为发布验收证明；权威结果以 Test Kit verifier 使用的 canonicalizer 为准。当前 Test Kit verifier 负责结构与跨字段门禁，但尚不替代组织签名系统、证据存储的指纹解析、时间戳信任或业务 Owner 的人工判断。发布链还必须验证 exact ref 闭包、证据内容与引用指纹一致、签署主体权限和时间关系。

## 5. 跨字段验证不变量

JSON Schema 负责字段、类型、枚举、数量和 fingerprint 格式；Test Kit verifier 执行其中可确定化的门禁，组织权限和业务结论仍需人工或外部治理系统审阅：

- `gpResults` 的集合必须恰好是 `GP-01` 至 `GP-10`，不能缺失、重复或多出；
- `scenarioResults` 必须恰好覆盖 Golden Demo Pack 的 9 个 Case；
- Canonical Baseline 与 Tutorial Branch 的 exact ref、revision 和 fingerprint 不得混用；
- 每个 Active Case 必须闭合到 Contract、Owner、Oracle、来源和适用 Scope；
- `ACCEPTED` 必须要求所有 GP 和 Case 为 `PASSED`，所有必需证据存在且不是 `NOT_RUN`；
- `realExternalCallCount` 必须是已观测的 `0`，不能以 `null`、无日志或未执行替代；
- 浏览器必须覆盖中文/英文、1440px/1024px/390px，关键状态和可访问性结果必须有证据；
- `SEC-*`、`NFR-*` 和 Spike 结果必须达到 Baseline 规定的门槛；
- 所有 signoff 均为 `APPROVED`，签署时间不晚于生成时间，且引用的证据指纹一致；
- 任一 P0/P1 限制、stale 引用、冲突、越权或未解释 404/400 都阻止 `ACCEPTED`。

## 6. 签署规则

签署人必须是对应职责的真实主体，并通过组织规定的签名或审批系统写入 `signoffs`。本次提交不包含签名，不代表任何角色批准。签署前允许更新制品并保持 `NO_GO`；签署后任何影响验收语义的变更都必须提升 revision、撤销旧签署并重新生成 fingerprint。

## 7. 证据命名建议

建议证据引用按以下格式组织：

```text
evidence://capability-studio/<baseline-or-candidate>/<requirement-id>/<evidence-id>@<revision>#sha256:<64>
```

引用指向 DOM、协议响应、运行 trace、网络拒绝、截图、可访问性报告或安全报告。证据内容由对应系统存储；本目录只保存定位信息和验证结果，不保存业务 payload。
