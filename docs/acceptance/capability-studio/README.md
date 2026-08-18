# Capability Studio 验收制品

> 当前状态：`NO_GO`
>
> 本目录记录 Capability Studio 的治理边界、验收合同、证据追踪和未通过实例。GP-01 至 GP-10 已形成 Stage 0 开发切片，Canonical 运行证据已达到 `CERTIFIABLE`；目标环境候选认证、部署级 egress、客户级 Dataset/Payload Authority、完整 UX 矩阵和人工签署仍未闭合。任何局部完成都不表示整体产品验收通过，也不替代产品与架构签署。

## 1. 制品清单

| 制品 | 路径 | 用途 | 当前结论 |
|---|---|---|---|
| 资产投影 ADR | [`ADR-007`](../../adr/ADR-007-capability-studio-asset-projections-and-compiler-boundary.md) | 固定对象权威、编译和生产隔离边界 | `Proposed` |
| Acceptance Baseline v1 | [`capability-studio-acceptance-baseline-v1.json`](capability-studio-acceptance-baseline-v1.json) | 冻结 GP、黄金包、Spike、可用性、安全和 NFR 门禁 | `NO_GO` |
| Stage Acceptance Result v1 Schema | [`capability-studio-stage-acceptance-result-v1.schema.json`](../../schemas/resource-gateway-capability-studio/capability-studio-stage-acceptance-result-v1.schema.json) | 固定 `S*-AC-*` 单条验收结果、五项统一前置条件、证据和签署状态机 | 兼容协议，可消费，不代表任何合同已通过 |
| Stage Acceptance Result v2 Schema | [`capability-studio-stage-acceptance-result-v2.schema.json`](../../schemas/resource-gateway-capability-studio/capability-studio-stage-acceptance-result-v2.schema.json) | 固定 `AC-STD-01..09`、候选执行绑定、签署前证据闭包和正式 Stage 退出状态 | 语义协议已定义，外部 Evidence/签名 Authority 尚未闭合 |
| Browser Matrix Result v1 Schema | [`capability-studio-browser-matrix-result-v1.schema.json`](../../schemas/resource-gateway-capability-studio/capability-studio-browser-matrix-result-v1.schema.json) | 固定 `GP-01..10 × 中英文 × 3 视口` 的 60 格机器结果、候选/基线/环境/时间窗绑定和证据闭包 | 真实 producer 与本地干净候选 60/60 已闭合；CI Candidate/Environment Authority 和产品/UX/QA 签署未闭合 |
| Browser Anomaly Matrix Result v1 Schema | [`capability-studio-browser-anomaly-matrix-result-v1.schema.json`](../../schemas/resource-gateway-capability-studio/capability-studio-browser-anomaly-matrix-result-v1.schema.json) | 固定服务错误 60 格、目标请求断网 60 格和 GP-04 stale revision 冲突 6 格，校验故障真实触发、业务化反馈、恢复、数据保留和正常态 exact binding | Schema、构造器、真实 Chrome producer、独立 builder/verifier/CLI 与双矩阵脚本已闭合；同一干净候选的 5 个过滤 obligation 已通过，121 个未执行项保持 `NOT_RUN`；全量 `COMPLETE` 仍为 0/126 |
| Golden Path Manifest Schema | [`capability-studio-golden-path-acceptance-manifest-v1.schema.json`](../../schemas/resource-gateway-capability-studio/capability-studio-golden-path-acceptance-manifest-v1.schema.json) | 约束发布候选验收证据的机器结构 | 可消费，未证明通过 |
| Golden Path NO_GO fixture | [`capability-studio-golden-path-acceptance-manifest-v1.no-go.fixture.json`](capability-studio-golden-path-acceptance-manifest-v1.no-go.fixture.json) | 提供真实的初始缺证据状态 | `NO_GO` |
| Governed Baseline Schema | [`capability-studio-governed-baseline-v3.schema.json`](../../schemas/resource-gateway-capability-studio/capability-studio-governed-baseline-v3.schema.json) | 固定 GP-07/08 的 9 × 3 矩阵、逐 Case 业务 Oracle、部署候选与 canonical execution intent；失败态强制 `NOT_VERIFIED` | 当前为 `DEVELOPMENT_TEST_OWNED / CERTIFIABLE / NO_GO` |
| Governed Run Evidence Schema | [`capability-studio-governed-run-evidence-v1.schema.json`](../../schemas/resource-gateway-capability-studio/capability-studio-governed-run-evidence-v1.schema.json) | 固定 GP-10 的原运行读取、完整引用闭包、结构级 Data Lens 和确定性指纹 | 开发证据已互验，不代表目标环境验收通过 |
| Scenario Quality & Impact Schema | [`capability-studio-scenario-quality-impact-v1.schema.json`](../../schemas/resource-gateway-capability-studio/capability-studio-scenario-quality-impact-v1.schema.json) | 固定 GP-09 的准入事实、质量覆盖、exact-ref 影响闭包、确定性指纹和 Payload 边界 | test/staging 开发证据已互验，正式数据准入仍为 `BLOCKED` |
| Screen State Inventory v1 | [`screen-state-inventory-v1.md`](screen-state-inventory-v1.md) | 逐个 GP 固化页面状态和恢复动作 | GP-01 至 GP-10 均有开发证据；完整候选与人工矩阵未闭合 |
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
- `CapabilityStudioStageAcceptanceResultV2Builder` 使用与 verifier 相同的严格合同生成诚实 Stage 结果。调用方不能用成功布尔值伪造 `PASS`，也不能由构建脚本代填目标环境、部署级 egress、Evidence Authority 或 Owner 签署；缺少外部事实时必须保留逐项 `NOT_RUN`、`BLOCKED` 或 `FAIL`；
- `Browser Matrix Result v1` 严格 Schema、真实 Chrome producer、独立 Test Kit builder/verifier/CLI 和一键运行脚本；它把 `GP-01..10 × zh-CN/en-US × 1440×900/1024×768/390×844` 固定为不可缩减的 60 格，并绑定候选制品/source commit、Baseline、浏览器/driver/axe 环境和执行时间窗。Verifier 会重算单元身份、排序、汇总和 evidence closure，拒绝假通过、脏候选 Complete、伪造未执行观测和证据篡改。提交 `7112211d4` 的本地干净候选运行得到 `BMR-7112211d4a47-1787075364`：60/60 通过、0 跳过、0 P0/P1，候选 JAR fingerprint 为 `sha256:5bba9cd054109d798be188bfa71adca169c935da90271112e713460a950a3a00`，evidence closure 为 `sha256:7bc9f903616eac353aec8bdf52f2af41ca8758e442fcbc0ceffd54615d33e2cf`，独立 CLI 返回 `VALID status=COMPLETE`。这是本地开发级候选证据，不是 CI Candidate/Environment Authority 的正式证明；
- `Browser Anomaly Matrix Result v1` 已把异常态分母固定为 126 格，并提供 Resource Gateway obligation/汇总构造器、真实 Chrome producer、Test Kit builder/verifier/CLI、负向测试和双矩阵脚本。ERROR 使用目标路由的 CDP 503，OFFLINE 使用目标请求 transport failure 且无 HTTP 状态，CONFLICT 使用浏览器旧 revision、服务端真实并发写入和 409；每格都必须闭合业务化说明、错误摘要与恢复控件的视口可见性、键盘恢复、恢复后 `READY`、无遮挡、axe、技术信息不泄漏和 Evidence。真实 Chrome 开发诊断发现 GP-04 的恢复按钮只存在于 DOM、未完整进入 1024 视口，已推动页面自动滚动和焦点修复；producer 同时新增 canonical JAR、HEAD 和 `src/main` 三项一致性检查。同一 `7112211d4` 干净候选随后执行 ERROR/GP-04、ERROR/GP-09、OFFLINE/GP-04、OFFLINE/GP-09 和 CONFLICT/GP-04 的英文 1024 过滤集，5 个 obligation 均为 `PASS`，0 `FAIL`，121 个未执行项保持 `NOT_RUN`；独立 CLI 返回 `VALID status=NOT_RUN` 和退出码 3，因此不能冒充全量证据；
- GP-04 的教程分支业务句式编辑器，用户通过条件、表现和持续时间配置超时，无需编辑 Mock JSON；
- GP-04 的数据库 head、immutable revision、严格保存协议和隔离预检，以及 409 冲突保留输入、结构化恢复动作和断网分类测试；
- GP-04 的 SQL 原子 optimistic CAS、同版本并发单赢家、stale 同内容重试幂等、Authority 重建恢复和 Canonical Baseline 漂移失败关闭测试；
- GP-04 的四份严格 JSON Schema、独立 Test Kit 内容指纹重算和真实 HTTP before/after/preflight 互验；
- GP-04 中文 1440×900 真实 Chrome 保存与预检烟测。
- Scenario Dataset v1 的严格 payload-free 投影 Schema、由 Golden Demo Pack 确定性生成的服务端投影端点，以及独立 Test Kit 的指纹、Scope、引用闭包、质量计数和 Active readiness 验证器。每个 Case 现在显式携带四个 API 的完整 `RUNTIME_CONTROL`；幂等、禁止写入等 `BUSINESS_EXPECTATION` 单独进入 source map，不再被误编译为 Tool fixture，也不能虚增运行闭包覆盖率；
- GP-03 已从静态九行表切换为真实 Dataset master-detail 视图，显示 Dataset 分母、生命周期、分类、Owner、五项质量覆盖、Case 业务目标、来源、Oracle、适用契约、依赖表现与精确引用；协议、网络或语义校验失败时拒绝展示并提供恢复动作；
- GP-03 Case 详情已把原有混合的「依赖表现」拆成「隔离运行依赖」与「业务正确性要求」：前者展示四个被控制的 API 及其表现，后者展示幂等、禁止写入等 Oracle 义务；无额外义务时明确说明由 Oracle 校验业务结果；
- GP-03 中文 1440×900 与 390×844 真实 Chrome 烟测，覆盖 Dataset 摘要、质量状态、九条 Case、超时案例详情、移动端筛选与无横向溢出。
- GP-09 已新增 test/staging-only `GET /api/capability-studio/scenario-dataset/quality-impact`。严格 v1 投影如实固定 9 `DRAFT`、0 `ACTIVE`、0 `STALE`、五项覆盖 100%、新鲜度 `UNVERIFIED`、准入 `BLOCKED` 和两个稳定 blocker；根级 `targetRef` 独立锚定被验证能力，37 个 exact-ref 节点与 81 条语义边闭合 Dataset、Case、Source、Oracle、Contract、四个 runtime dependency 和同一 Target，每 Case 的 6 个影响资产可独立复算。投影只输出关系，不输出业务 Payload，也明确不把该边界冒充语义脱敏证明；
- `CapabilityStudioScenarioQualityImpactVerifier` 已作为独立 Test Kit 边界，验证公开 Schema、投影 fingerprint、稳定排序、Scope 闭包、多 Authority、节点/边语义、汇总基数、准入状态机与递归 Payload 字段拒绝。真实 wire bytes 已通过 verifier；前端直接用认证请求消费同一响应，真实 Chrome 覆盖中文 1440×1100 与 390×844、Case 切换后的 9 节点/8 边高亮、页面无横向溢出和 axe serious/critical 为 0；
- Dataset 经确定性适配器进入既有 `ScenarioDraftSet`，再委托既有 `ScenarioGovernedCompiler` 生成 FixtureBundle/TestSuite 注册计划；Canonical 9 Case、RETURN/ERROR/TIMEOUT/顺序消费/`MUST_NOT_CALL`、rule source map、exact target/contract/Scope/Authority、三次确定性和 fail-closed 负向语义已有自动化证据。source map 现已转换为强类型、稳定排序的 exact-ref provenance，并参与 FixtureBundle/TestSuite 内容寻址；`CapabilityStudioGovernedAssetPublisher` 只通过既有 Registry 注册，写后独立回读并复算内容；`CapabilityStudioGovernedCandidateService` 再以同一 exact suite 调用既有 `TestSuiteExecutionService`。完整闭包以可逆字典清单进入 aggregate evidence，Canonical suite metadata 为 11,863 bytes，低于 16,384 bytes 协议上限；child evidence 使用 suite ref 与 provenance/source-map fingerprint 做紧凑绑定。真实 Spring test profile 已完成同一注册闭包的 3 轮 9 Case 执行：3 个 suite run、27 个唯一 child run 全部通过，进程内真实外部调用为 0；旧 Scenario 编译路径不注入这些字段，内容寻址保持兼容；
- Data Lens 后端窄切片直接投影既有 `TestRunEvidence.nodeTrace/edgeTrace`，支持 structure-only 与 payload-visible 视图、稳定执行坐标、值 fingerprint、重试/回退、首个运行时差异和高基数截断；该投影已接入 GP-05/06 Feature API 与工作区。视图参数只表达请求：服务端先验证 credential 与 `CAPABILITY_STUDIO_REHEARSAL` purpose，再以受信 clearance 裁决 Payload；PUBLIC 身份可看结构，`CONFIDENTIAL` 及以上身份才可看受控数据；query、伪造 `X-Clearance` 和审计故障不能越过该边界；
- zero-egress 窄 Spike 已让 9 个 Canonical Case metadata 通过真实 `TestRunService` 与 `HttpResourceOperator` delegate 边界运行；任何真实 delegate 调用都会计数并 fail-fast，当前 connector counter 为 0，fallback-to-real 在执行前拒绝。该证据使用 test-owned runtime material，只证明进程内控制反转，不等同于部署级 network deny，也不构成 9/9 业务 Oracle 验收；
- GP-05/06 已新增 test/staging-only Feature Rehearsal API。该 API 用实际 BLOGE Graph 执行四个 `HttpResourceOperator`、一个纯聚合节点和一个纯决策节点，并把同一次 `TestRunEvidence` 投影为 6 个节点、5 条边和 Data Lens；超时会取消下游，任何 HTTP delegate 调用均 fail-fast；
- Feature Rehearsal v1 已有严格 JSON Schema 和独立 Test Kit verifier。它校验 6 节点、5 条边、Run/Data Lens 身份一致、调用点边闭包、权限投影、可见 Payload 指纹、Data Lens 指纹和投影声明的零真实调用；真实服务的 `STRUCTURE_ONLY` 响应已通过该 verifier。Controller 负向测试进一步证明缺少/无效凭证、purpose、低 clearance、伪造 clearance 和审计不可用均失败关闭，并在 rehearsal service 调用前停止。该结果不替代部署级 network deny、客户级 ABAC/Scope Authority 或 Graph/semantic fingerprint 的可信来源证明；
- 2026-08-18 又以两个独立真实服务身份完成 Data Lens 授权复验：`CONFIDENTIAL` 身份在中文桌面页面先看到 6 节点、5 条边和 0 次真实调用，再切换到受控数据并读取同一 Trace 的节点输入输出；`PUBLIC` 身份在中文桌面和 `390×844` 视口请求受控数据时收到可恢复拒绝，控件回到实际的「结构」状态，原结构投影保持可见，DOM 只保留 fingerprint 且不含演示 Payload。该证据只闭合 test/staging 演示身份的允许/拒绝路径；
- `GET /api/capability-studio/feature-rehearsal-baseline` 已提供 payload-free 的开发基线：固定 9 个 Case 各运行 3 次，共产生 27 个唯一 `runId`。独立业务 Oracle 覆盖标准结论、乘客无责、司机责任、政策缺失、空历史、预期超时、重复幂等、禁止写入和政策版本回归；超时 Case 保留依赖原始 `TIMEOUT` 尝试，BLOGE fallback 后最终 `PASSED` 并输出 `MANUAL_REVIEW / COMPENSATION_HISTORY_TIMEOUT`。两批并发运行仍保持 54 个唯一 `runId`，进程内真实调用计数为 0。严格 v1 Schema 与独立 Test Kit verifier 已对真实响应校验 Case/Oracle/Run/状态/fingerprint/operator/诊断闭包。该投影强制标记为 `DEVELOPMENT_TEST_OWNED`，不是 `S0-AC-04 PASS`；
- `POST /api/capability-studio/governed-baseline` 已成为 GP-07/08 Tool 页的真实运行入口。该 test/staging-only 端点无调用方 payload 和身份覆盖，复用上述受治理 compiler/Registry/suite runtime，严格要求 3 个唯一 suite run、27 个唯一 child run、9 个 Case 每轮恰好一次、全部通过、三轮 publication/provenance/source-map 不漂移且进程内真实调用为 0。Suite 完成后，服务端通过既有授权 API 回读每条完整签名 child evidence，校验 run/target/Fixture/integrity，导出 payload-free evidence fingerprint、semantic result fingerprint、断言与 Fixture 控制计数；同 Case 三轮结果指纹必须一致，timeout/duplicate/forbidden-write 由结构化事实形成专项证明。v3 新增部署侧候选绑定和 canonical execution intent：启动器只在实际 JAR SHA-256、Git commit 与 `CLEAN` source tree 同时成立时注入候选，调用方不能覆盖；Test Kit 会独立重算 intent 并拒绝篡改。成功投影固定 `verificationLevel=DEVELOPMENT_VERIFIED`、`DEVELOPMENT_TEST_OWNED / NO_GO`，Evidence 等级按 child evidence 如实投影；失败关闭固定为 `NOT_VERIFIED`，不伪造 evidence class、publication、Run 或 fingerprint；
- GP-07/08 已在 Tool 页上提供“运行 9 × 3 受治理验证”主操作。运行中、原位失败恢复、开发通过/发布不可验收双结论、3 轮 suite 摘要、9 × 3 Case 矩阵、9/9 业务 Oracle、27/27 业务断言、三项专项证明、`CERTIFIABLE` 等级和折叠技术证据已实现。四个 Canonical Resource descriptor 使用应用级 `ResourceRegistry`，`RETURN` 数据以 transport-level 响应经过真实 `HttpResourceOperator` 映射链；未解析 Resource 在调度前失败，output-level 替身保持 `EXPLORATORY`。候选已绑定时显示制品、source commit 和 execution intent，并只保留目标环境认证、部署级 egress 和 Owner 签署三项限制；未绑定时额外保留候选未绑定限制。既有真实浏览器覆盖中文桌面/移动端，无页面横向溢出，axe serious/critical 为 0；
- GP-10 已提供 test/staging-only 精确证据读取端点 `GET /api/capability-studio/governed-runs/{runId}/evidence?expectedCaseId=...`。服务只读取已持久化 child run，不重跑 Graph；重复读取的响应 bytes 与 projection fingerprint 保持一致。投影闭合 Tool、Contract、Dataset、Case、runtime target、Binding Plan、Fixture、Behavior、依赖、source map、provenance 和同一次 Run/Data Lens 身份，并以 `STRUCTURE_ONLY` 保留完整 7 节点运行证据而隐藏 Payload。前端从 Tool 的 9 × 3 Case 矩阵打开精确证据，使用 `task/runId/scenarioId/nodeId` 保持刷新、返回和焦点；Feature 画布只呈现当前 graph path 的 6 个业务节点，完整 Data Lens 仍保留外层 Tool `subject`。独立 Test Kit verifier 已用真实 wire bytes 复算 Schema、引用闭包、两类内容指纹、焦点和 Payload 边界；合同漂移、篡改、错误 Case、缺少权限和未知 Run 均失败关闭；
- Feature 工作区提供 9 个 Canonical Case 选择、结构/受控数据双权限态、运行状态、隔离绑定和真实调用计数。桌面 DAG 按稳定业务顺序完整展开，源节点与边中心对齐；移动端使用可聚焦的内部横向滚动区；
- Canonical Feature DAG 已可包装为真实 Tool binding，并经既有 `OperatorMicroGraphRunner -> TestRunService -> BLOGE nested graph` 路径执行；Tool 微图的 nested fixture selector 命中完整 6 节点子图，真实 HTTP delegate 调用为 0。Tool composability manifest 已声明四个 exact Resource 依赖，通用执行目标快照会在运行证据未证明闭包时拒绝认证；仍缺同一 Canonical 注册资产的独立认证结果；
- GP-05/06 的中文真实 Chrome 验收覆盖 1440×900、1440×1100、1024×768 和 390×844；英文覆盖 1024×768。自动化检查 6 节点、5 条边、稳定排序、桌面零横向截断、边对齐、移动端内部滚动、键盘权限切换、页面无横向溢出和 axe serious/critical 为 0；
- 生产运行协议边界已扩展到 fixture、stub、binding override、dependency behavior 和 Dataset 字段族，并在五类运行入口、三组 production profile 上验证 DTO 前拒绝、审计失败关闭和 Payload 不泄漏；
- GP-01/03/04 英文 1440×900、1024×768、390×844 真实 Chrome 证据，以及 GP-05/06 英文 1024×768 证据；覆盖 Dataset 的 Tab/Enter/Space 键盘路径、Feature 权限切换、六种组件状态和真实浏览器完整 axe-core 检查。真实 axe 曾检出并推动修复选中场景辅助文字对比度和 DAG 滚动区焦点问题。

仍未完成的是持久化且具备权限边界的 Dataset Authority、可信 freshness/review Authority、Active 生命周期与审批入口、Feature 的字段级 source map、客户级数据分类/ABAC/跨 Scope Payload Authority、目标环境 Candidate attestation、部署级 network deny/egress 观测、Data Lens 英文拒绝态、异常态真实浏览器全量执行、人工读屏、并发浏览器矩阵和六人可用性签署。目标浏览器分母已经在主方案中冻结为正常态 60 格、服务错误 60 格、目标请求断网 60 格和 GP-04 真实保存冲突 6 格，共 186 格；异常态的严格协议、producer 与复验工具已实现，同一干净候选的 5 个过滤 obligation 已通过，但尚无 126/126 `COMPLETE` 结果，因此正式异常态完成度仍按 0/126、浏览器验收进度仍按 60/186 计。正常态结果尚未由 CI Candidate/Environment Authority 签发，产品、UX、QA 也未签署。当前 `/scenario-dataset` 是 Stage 0 Golden Demo Pack 的不可变业务投影，不是客户生产 Dataset 的写入 Authority。当前页面已调用同一受治理编译/应用级 Resource Registry/真实 Resource Operator/注册/执行链路并闭合开发 Oracle，结果为 `DEVELOPMENT_TEST_OWNED / CERTIFIABLE / NO_GO`；证据可信度提升不等于目标环境或发布责任已经闭合，因此不能替代 Owner 签署的 `S0-AC-04` 证据。

因此 Baseline 和 Manifest 必须保持 `NO_GO`、`PENDING` 或 `NOT_RUN`。元数据可读、开发自动化通过和启动探针成功，只能证明当前纵向切片可演示，不能冒充 Capability Studio 产品验收通过。

### 2.1 当前浏览器证据

以下截图由 `CapabilityStudioBrowserAcceptanceTest` 或同源真实浏览器会话连接实际 Spring Boot 服务后生成，覆盖 GP-01 至 GP-10。自动化同时断言资产数量、API 选择、九行场景、中文业务状态文案、内部状态码不泄漏、GP-04 实际保存与隔离预检、GP-05/06 Trace 与权限态、GP-07/08 真实受治理 POST 与 9 × 3 矩阵、GP-09 质量准入与影响闭包、GP-10 原运行读取与 Deep Link，以及页面级无横向溢出。自动化截图使用 CDP 设置精确 viewport，并在截图前断言实际 `window.innerWidth/innerHeight`；表中的 1440、1024 和 390 是已执行尺寸，不是文件名约定。

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
| 1024×768 | 英文 GP-07/08 Tool 业务正确性说明、9 × 3 结果、双结论与完整 Case 矩阵 | [`capability-studio-gp07-gp08-governed-tool-en-1024.png`](../../assets/capability-studio/capability-studio-gp07-gp08-governed-tool-en-1024.png) |
| 1440×1100 | GP-09 五项 100%、9/0/0 准入事实、两个阻断、Payload 边界与 Case 影响入口 | [`capability-studio-gp09-quality-admission-zh-1440.png`](../../assets/capability-studio/capability-studio-gp09-quality-admission-zh-1440.png) |
| 1440×1100 | GP-09 选择超时 Case 后的 Owner、Source、Oracle、Contract、四依赖、Target 与 9 节点/8 边高亮闭包 | [`capability-studio-gp09-case-impact-zh-1440.png`](../../assets/capability-studio/capability-studio-gp09-case-impact-zh-1440.png) |
| 390×844 | GP-09 移动端准入首屏与无横向溢出 | [`capability-studio-gp09-quality-admission-zh-390.png`](../../assets/capability-studio/capability-studio-gp09-quality-admission-zh-390.png) |
| 390×844 | GP-09 移动端 Case 详情与可读影响关系列表 | [`capability-studio-gp09-case-impact-zh-390.png`](../../assets/capability-studio/capability-studio-gp09-case-impact-zh-390.png) |
| 1024×768 | 英文 GP-09 仅键盘选择第二条 Case 后的 9 节点/8 边影响闭包 | [`capability-studio-gp09-quality-keyboard-en-1024.png`](../../assets/capability-studio/capability-studio-gp09-quality-keyboard-en-1024.png) |
| 1024×768 | 英文 GP-09 真实 503 的业务化错误说明和单一恢复动作；无内部协议码 | [`capability-studio-gp09-quality-error-en-1024.png`](../../assets/capability-studio/capability-studio-gp09-quality-error-en-1024.png) |
| 1024×768 | 英文 GP-09 Retry 后恢复真实质量与影响投影 | [`capability-studio-gp09-quality-recovered-en-1024.png`](../../assets/capability-studio/capability-studio-gp09-quality-recovered-en-1024.png) |
| 1440×1100 | GP-10 从 Tool Case 打开的原运行精确证据与完整 exact-ref 闭包 | [`capability-studio-gp10-exact-evidence-zh-1440.png`](../../assets/capability-studio/capability-studio-gp10-exact-evidence-zh-1440.png) |
| 1440×1100 | GP-10 从精确证据进入当前 Feature DAG，保持原 `runId`、Case 和焦点节点 | [`capability-studio-gp10-exact-dag-zh-1440.png`](../../assets/capability-studio/capability-studio-gp10-exact-dag-zh-1440.png) |
| 1440×1100 | GP-10 返回 Tool 后仍保持同一原运行和精确证据 | [`capability-studio-gp10-exact-evidence-return-zh-1440.png`](../../assets/capability-studio/capability-studio-gp10-exact-evidence-return-zh-1440.png) |
| 1280×720 | GP-10 Tool Case 精确运行证据、原 `runId`、焦点节点和完整受治理引用闭包 | [`capability-studio-gp10-exact-evidence-zh-1280.png`](../../assets/capability-studio/capability-studio-gp10-exact-evidence-zh-1280.png) |
| 1440×900 | GP-10 Deep Link 上下文、当前 6 节点 Feature 子图和完整 7 节点结构级 Data Lens | [`capability-studio-gp10-exact-graph-context-zh-1440.png`](../../assets/capability-studio/capability-studio-gp10-exact-graph-context-zh-1440.png) |
| 390×844 | GP-10 移动端精确证据，原运行与引用闭包可读且页面无横向溢出 | [`capability-studio-gp10-exact-evidence-zh-390.png`](../../assets/capability-studio/capability-studio-gp10-exact-evidence-zh-390.png) |
| 390×844 | GP-10 移动端焦点节点、Feature 子图与完整 Data Lens 的边界 | [`capability-studio-gp10-exact-graph-context-zh-390.png`](../../assets/capability-studio/capability-studio-gp10-exact-graph-context-zh-390.png) |
| 1024×768 | 英文 GP-10 原 child run 精确证据、同一 Case 和完整引用闭包 | [`capability-studio-gp10-exact-evidence-en-1024.png`](../../assets/capability-studio/capability-studio-gp10-exact-evidence-en-1024.png) |
| 1024×768 | 英文 GP-10 从精确证据进入保持同一 Run/Case/Node 的 Feature DAG | [`capability-studio-gp10-exact-dag-en-1024.png`](../../assets/capability-studio/capability-studio-gp10-exact-dag-en-1024.png) |

这些截图与固定矩阵关闭了正常态 GP-01 至 GP-10 的中英文三视口、键盘路径、页面溢出、技术 ID/Raw JSON 泄漏和自动 axe 检查，但不覆盖计划中的 60 格服务错误、60 格目标请求断网和 6 格 GP-04 真实保存冲突，也不覆盖人工屏幕阅读器、CI Authority 签发的不可变发布候选证据或人工可用性签署。业务资产名称和说明仍是 Canonical Demo Pack 的中文权威数据，语言切换只承诺产品界面文案，不应误报为业务内容本地化。

## 3. 更新流程

### 3.1 运行固定浏览器矩阵

在干净提交上运行唯一正式入口：

```bash
./scripts/run-capability-studio-browser-matrix.sh
```

脚本会构建一次生产前端与候选 JAR，先执行固定 60 格正常态真实 Chrome 矩阵，再执行固定 126 格异常态矩阵，并分别由 `resource-gateway-test-kit` 独立校验。成功条件是正常态与异常态都绑定同一候选、Baseline 和浏览器环境，`186/186` 全部通过、0 跳过、0 P0/P1，并且两个 CLI 都输出 `VALID status=COMPLETE`。正常态结果写入 `resource-gateway-examples/target/acceptance/capability-studio-browser-matrix-result-v1.json`，异常态结果写入同目录的 `capability-studio-browser-anomaly-matrix-result-v1.json`；截图分别位于 `browser-matrix-evidence/` 和 `browser-anomaly-evidence/`。

工作树不干净时，默认命令会在浏览器启动前拒绝执行。开发诊断可显式运行：

```bash
./scripts/run-capability-studio-browser-matrix.sh \
  --allow-dirty --no-build \
  --anomaly-profile ERROR \
  --anomaly-gp GP-09 \
  --anomaly-locale en-US \
  --anomaly-viewport 1024x768
```

开发模式必须复用一个已经独立验证为 `COMPLETE` 的正常态 Base Matrix。异常过滤只改变本次实际执行的 obligation，不改变 126 格固定分母；未执行项保持 `NOT_RUN`，独立 CLI 返回退出码 3，脚本最多输出 `DEVELOPMENT_VERIFIED`。不得将过滤结果复制为正式证据。`--no-build` 只用于复用已存在且与 Base Matrix 指纹一致的候选 JAR，不改变候选干净度和 `COMPLETE` 门槛。`CONFLICT` 过滤只允许 `GP-04`；非法 profile、GP、语言或视口会在构建和浏览器启动前失败。

### 3.2 先改合同，再改实现

改变黄金路径的动作顺序、页面反馈、业务预期、资产数量、场景分母、生产隔离策略或签署门槛时，先更新 Baseline、Screen State Inventory 和 Traceability Matrix，并由产品/架构/QA 重新评审。实现不能先行改变验收含义。

不改变验收语义的实现修复，可以只补充对应证据引用和测试结果，但不得删除失败、限制或未覆盖记录。

### 3.3 更新 Baseline

1. 增加 revision，保留旧文件和变更说明。
2. 更新 `goldenPack`、`goldenPaths`、`spikes`、浏览器矩阵、可用性门槛、安全门禁和 NFR 门禁。
3. 所有引用使用 exact ref；没有真实资产时使用 `status: NOT_RUN` 或 `PENDING`，不得编造 pass。
4. 只有对应角色完成签署，Baseline 才能从 `NO_GO` 变为 `APPROVED`。签署字段不能由脚本代填。

### 3.4 生成 Manifest

每个发布候选生成一个新的 Manifest：

1. 记录候选构建、Baseline、Golden Demo Pack、Contract/Dataset/Binding Plan fingerprints。
2. 填写完整的 `gpResults`、9 个 `scenarioResults`、浏览器、可访问性、协议、安全和 egress 结果。
3. 不复制 payload，只写 evidence ref、脱敏摘要和 fingerprint。
4. 未执行的观测使用 `NOT_RUN`，未知计数使用 `null`，不使用 `0` 代替“没有观测”。
5. 结构校验通过不等于验收通过；需要执行 README 第 5 节的跨字段不变量。

## 4. 内容寻址

所有制品都使用 UTF-8 JSON 或 UTF-8 Markdown。JSON 的 fingerprint 计算使用项目统一的 RFC 8785 JCS canonical bytes，并将自身 `artifactFingerprint` 字段归一化为 `null` 后计算 `sha256:<64 位小写十六进制>`。签署信息仍然参与指纹；只有自身 fingerprint 字段被排除，避免循环引用。

Stage 0 Golden Demo Pack 的 `packFingerprint` 绑定整个 payload-free 投影内容。包内尚未物化的子引用仍使用可复算坐标摘要 `sha256("capability-studio-demo-v1|kind|id|revision")`。GP-03 Dataset 投影的根、Case 与 Behavior Profile 已使用内容指纹，Test Kit 可独立重算根指纹并校验 Scope、引用闭包和质量统计，但 Source、Oracle、Contract 等引用仍继承 Stage 0 坐标摘要，且没有持久化 Dataset Authority。GP-04 Tutorial Branch 已使用 test/staging 数据库 Authority：分支 fingerprint 绑定 `schemaVersion`、`branchId`、Canonical Baseline fingerprint、`dependencyId`、condition、behavior 和 duration；head 只做 SQL 原子 CAS，revision 只追加，Test Kit 会从 before/after 投影重算并校验 digest。GP-05/06 返回实际 Feature Graph 内容 fingerprint、Run ID、semantic fingerprint 和 Data Lens fingerprint。GP-07/08 现在从受治理 compiler 产生内容寻址 Fixture/Suite，通过既有 Registry 写后回读，再以确认的 exact suite 生成 3 suite/27 child 开发证据；Canonical `RETURN` 以 descriptor-backed transport fixture 经过应用级 Resource Registry 和真实 Resource Operator 链，child Evidence 为 `CERTIFIABLE`。v3 协议进一步将部署候选、publication、Suite、compilation 和 source map 归一化为 `candidateIntentFingerprint`，由 Test Kit 独立重算，同时闭合每 Case 三轮 semantic fingerprint、业务断言和三类专项 Oracle。GP-10 再从持久化 child evidence 确定性生成内容寻址 Binding Plan、结构级 Data Lens 和总 projection fingerprint；同一原 `runId` 重读不得改变 bytes 或 fingerprint，也不得触发新运行。但目标环境认证、部署级 egress 和 Owner 签署闭包仍缺失，因此 Manifest 继续保持 `NO_GO`。

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
  -Dtest=CapabilityStudioGovernedRunEvidenceVerifierTest test

mvn -f resource-gateway-test-kit/pom.xml \
  -Dtest=CapabilityStudioScenarioQualityImpactVerifierTest test

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
