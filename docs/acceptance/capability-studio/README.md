# Capability Studio 验收制品

> 当前状态：`NO_GO`
>
> 本目录记录 Capability Studio 的治理边界、验收合同、证据追踪和未通过实例。GP-01 至 GP-10 已形成 Stage 0 开发切片，Canonical 运行证据已达到 `CERTIFIABLE`，本地干净候选的 186 格机器浏览器矩阵已闭合；目标环境候选认证、部署级 egress、客户级 Dataset/Payload Authority、人工读屏与六人可用性矩阵、正式签署仍未闭合。任何局部完成都不表示整体产品验收通过，也不替代产品与架构签署。

## 1. 制品清单

| 制品 | 路径 | 用途 | 当前结论 |
|---|---|---|---|
| 资产投影 ADR | [`ADR-007`](../../adr/ADR-007-capability-studio-asset-projections-and-compiler-boundary.md) | 固定对象权威、编译和生产隔离边界 | `Proposed` |
| Acceptance Baseline v1 | [`capability-studio-acceptance-baseline-v1.json`](capability-studio-acceptance-baseline-v1.json) | 冻结 GP、黄金包、Spike、可用性、安全和 NFR 门禁 | `NO_GO` |
| Stage Acceptance Result v1 Schema | [`capability-studio-stage-acceptance-result-v1.schema.json`](../../schemas/resource-gateway-capability-studio/capability-studio-stage-acceptance-result-v1.schema.json) | 固定 `S*-AC-*` 单条验收结果、五项统一前置条件、证据和签署状态机 | 兼容协议，可消费，不代表任何合同已通过 |
| Stage Acceptance Result v2 Schema | [`capability-studio-stage-acceptance-result-v2.schema.json`](../../schemas/resource-gateway-capability-studio/capability-studio-stage-acceptance-result-v2.schema.json) | 固定 `AC-STD-01..09`、候选执行绑定、签署前证据闭包和正式 Stage 退出状态 | 语义协议已定义，外部 Evidence/签名 Authority 尚未闭合 |
| Provider Conformance Result v1 Schema | [`capability-studio-stage-acceptance-provider-conformance-result-v1.schema.json`](../../schemas/resource-gateway-capability-studio/capability-studio-stage-acceptance-provider-conformance-result-v1.schema.json) | 历史 Provider Conformance TCK 的六项机制检查、汇总计数、外部检查清单和报告指纹 | 仅作为不可变历史协议验证；不代表外部五项检查或正式 Stage 验收通过 |
| Provider Conformance Result v2 Schema | [`capability-studio-stage-acceptance-provider-conformance-result-v2.schema.json`](../../schemas/resource-gateway-capability-studio/capability-studio-stage-acceptance-provider-conformance-result-v2.schema.json) | 当前部署门禁的七项机制检查，在 `LOCAL_PROTOCOL` 后显式绑定 `AUTHORITY_BINDING` 和部署 Authority fingerprint | v1 保持可离线验证；v2 是当前协议，仍不代表外部五项检查完成 |
| Authority Evidence Envelope v1 Schema | [`capability-studio-authority-evidence-envelope-v1.schema.json`](../../schemas/resource-gateway-capability-studio/capability-studio-authority-evidence-envelope-v1.schema.json) | 约束 Evidence Store 按精确坐标返回的无业务 Payload 权威事实、候选/环境/时间窗绑定和 Ed25519 seal | 通用 resolver 与 pinned issuer policy 已实现；企业存储、Issuer pin、Owner Authority 和目标环境证据仍待配置 |
| Mounted Authority Bundle v1 Schema | [`capability-studio-mounted-authority-bundle-v1.schema.json`](../../schemas/resource-gateway-capability-studio/capability-studio-mounted-authority-bundle-v1.schema.json) | 把部署方提供的 Evidence envelope、公开 Key Set pin、Issuer/Scope 和 Owner/Actor 策略绑定为一份只读、可复算的 Provider 配置 | 只负责装配和消费外部材料；不保存私钥、不签发 Evidence、不代替 Owner 审批 |
| Browser Matrix Result v1 Schema | [`capability-studio-browser-matrix-result-v1.schema.json`](../../schemas/resource-gateway-capability-studio/capability-studio-browser-matrix-result-v1.schema.json) | 固定 `GP-01..10 × 中英文 × 3 视口` 的 60 格机器结果、候选/基线/环境/时间窗绑定和证据闭包 | 真实 producer 与本地干净候选 60/60 已闭合；CI Candidate/Environment Authority 和产品/UX/QA 签署未闭合 |
| Browser Anomaly Matrix Result v1 Schema | [`capability-studio-browser-anomaly-matrix-result-v1.schema.json`](../../schemas/resource-gateway-capability-studio/capability-studio-browser-anomaly-matrix-result-v1.schema.json) | 固定服务错误 60 格、目标请求断网 60 格和 GP-04 stale revision 冲突 6 格，校验故障真实触发、业务化反馈、恢复、数据保留和正常态 exact binding | 同一干净候选已完成异常态 `COMPLETE` 126/126：ERROR 60、OFFLINE 60、CONFLICT 6；独立 CLI 和正式脚本合计返回 `COMPLETE: 186/186`，但仍属于本地 `DEVELOPMENT_VERIFIED`，不是正式 Stage 0 通过 |
| Browser Evidence Bundle Manifest v1 Schema | [`browser-evidence-bundle-manifest-v1.schema.json`](../../schemas/resource-gateway-capability-studio/browser-evidence-bundle-manifest-v1.schema.json) | 固定 438 份浏览器证据的精确引用、角色、字节数、内容指纹和 normal/anomaly closure 绑定，不复制业务 Payload | Test Kit 可离线生成并校验；本地文件闭包不能替代外部 Evidence Store 收据和 Owner 签署 |
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
- `Stage Acceptance Result v2` 严格 Schema 与独立 Test Kit 语义 verifier；它只承载正式 `STAGE_EXIT`，要求 `AC-STD-01` 至 `AC-STD-09` 精确集合，并把结果身份、候选执行、完整环境/egress 投影、检查和签署前 Evidence 绑定为可复算闭包。`NOT_RUN` 不允许伪造执行时间；运行前与运行中 `BLOCKED` 使用互斥时间语义；候选、环境、egress、签署投影与 `AC-STD-01/06/09` 必须一致；`PASS` 要求干净候选、完整观测窗口、零真实/被拒外呼尝试、应生成与实际持久化 Evidence manifest 精确相等，以及三类 Owner 签署。默认 verifier 不解析外部 Evidence，不验签名公钥、签发者权限或 Owner 身份，因此只能证明结果语义自洽，不能单独把 Stage 0 从 `NO_GO` 改为 `ACCEPTED`；
- `CapabilityStudioStageAcceptanceAuthorityVerifier` 已实现正式验收的外部 Authority 二阶段编排：先执行 v2 语义验证，协议非法或非 `PASS` 时保持零外部调用；随后按稳定顺序完整解析 Evidence 与 Owner signature，将目标环境的 issuer/Scope/候选制品/环境指纹/执行窗口、部署 egress 的 candidate intent/观测窗口，以及三类 Owner signature 的签署前闭包绑定到同一个 payload-free `AcceptanceContext`。外部不可用固定为 `BLOCKED`，确定性漂移或拒绝固定为 `REJECTED`，只有全部权威返回可信验证才得到 `ACCEPTED`。Test Kit 已补充严格、64 KiB 上限且拒绝业务 Payload 的 Authority Evidence Envelope、精确坐标 resolver、复用既有 Key Set verifier/Ed25519/撤销时间线的 pinned issuer policy，以及按角色、actor、issuer、Scope、候选、窗口和闭包验签的 pinned Owner Authority。正式 CLI 先完成本地语义验证，只有语义 `PASS` 才通过 `ServiceLoader` 加载唯一的企业 Authority Provider；当前仍缺企业 Evidence Store 适配器、部署级 issuer/Owner pin、真实角色目录、目标环境和 egress 证明，因此正式 Stage 0 仍为 `NO_GO`；
- `CapabilityStudioStageAcceptanceResultV2Builder` 使用与 verifier 相同的严格合同生成诚实 Stage 结果。调用方不能用成功布尔值伪造 `PASS`，也不能由构建脚本代填目标环境、部署级 egress、Evidence Authority 或 Owner 签署；缺少外部事实时必须保留逐项 `NOT_RUN`、`BLOCKED` 或 `FAIL`；
- `Browser Matrix Result v1` 严格 Schema、真实 Chrome producer、独立 Test Kit builder/verifier/CLI 和一键运行脚本；它把 `GP-01..10 × zh-CN/en-US × 1440×900/1024×768/390×844` 固定为不可缩减的 60 格，并绑定候选制品/source commit、Baseline、浏览器/driver/axe 环境和执行时间窗。最新本地干净候选 `40a6d47ca99d19515f03432508dd8d11ce72d13c`（`sourceTreeStatus=CLEAN`）得到 `BMR-40a6d47ca99d-1787091131`：60/60 通过、0 跳过、0 P0/P1；artifact fingerprint 为 `sha256:ba7c05e6c920d74390a73194ba6368574095876d5e0dbd824eefc710a28b2c35`，evidence closure 为 `sha256:b0d7a82627f57a4b63a4e73be1ef58e22b9a02f3166566182c7094043b433c8b`，独立 CLI 返回 `VALID status=COMPLETE`。这是本地开发级候选证据，不是 CI Candidate/Environment Authority 的正式证明；
- `Browser Anomaly Matrix Result v1` 已把异常态分母固定为 126 格，并提供 Resource Gateway obligation/汇总构造器、真实 Chrome producer、Test Kit builder/verifier/CLI、负向测试和双矩阵脚本。最新同一干净候选得到 `BAMR-40a6d47ca99d-1787091420`：ERROR 60、OFFLINE 60、CONFLICT 6，126/126 `COMPLETE`，0 `FAIL`、0 `NOT_RUN`；evidence closure 为 `sha256:3181acbb55da760e687464ae0f6ae6f321bbcf99d9a38456627830c8208b04c6`。ERROR 是目标路由 CDP 503，OFFLINE 是目标请求 transport failure 且无 HTTP 状态，CONFLICT 是浏览器 stale revision、服务端真实并发 PUT 与按 requestId 关联的 HTTP 409；每格均闭合业务化说明、错误摘要、恢复控件视口可见性、键盘恢复、恢复后 `READY`、无遮挡、axe、技术信息不泄漏和 Evidence。错误面板会校验请求语言兼容性；恢复动作在冻结视口内通过确定性布局副作用聚焦并居中，CONFLICT 额外证明 local draft retained/server revision preserved。正常态与异常态精确绑定同一 candidate/source/artifact/environment；独立 CLI 和正式脚本合计返回 `COMPLETE: 186/186`。这仍是本地 `DEVELOPMENT_VERIFIED` 事实，不是 CI Candidate/Environment Authority 的正式证明；
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

### 2.0 当前干净候选证据

以下是刚完成的本地干净候选正式开发矩阵事实。它们用于描述当前实现状态，不是外部 Authority 签发的 Stage 0 验收结果。

| 维度 | 当前事实 |
|---|---|
| 候选 | source commit `40a6d47ca99d19515f03432508dd8d11ce72d13c`；`sourceTreeStatus=CLEAN` |
| 制品 | artifact fingerprint `sha256:ba7c05e6c920d74390a73194ba6368574095876d5e0dbd824eefc710a28b2c35` |
| 环境 | environment fingerprint `sha256:4abf760df61cf15e4cf68400a69113cb52250348448a3ea5afd7874b3ba83599`；Chrome `151.0.7922.138`；driver `150.0.7871.124`；axe `4.12.1` |
| 正常态 | `BMR-40a6d47ca99d-1787091131`，`COMPLETE`，60/60，0 skipped，0 P0/P1；closure `sha256:b0d7a82627f57a4b63a4e73be1ef58e22b9a02f3166566182c7094043b433c8b` |
| 异常态 | `BAMR-40a6d47ca99d-1787091420`，`COMPLETE`，ERROR 60、OFFLINE 60、CONFLICT 6，共 126/126；0 failed、0 `NOT_RUN`；closure `sha256:3181acbb55da760e687464ae0f6ae6f321bbcf99d9a38456627830c8208b04c6` |
| 执行时间窗 | 正常态 `2026-08-18T22:12:11.630011Z` 至 `2026-08-18T22:16:02.373497Z`；异常态 `2026-08-18T22:17:00.095318Z` 至 `2026-08-18T22:30:02.991012Z` |
| 交叉校验 | 异常态 exact base binding 指向正常态 result/closure；独立 CLI 与正式脚本均返回 `COMPLETE: 186/186` |

异常语义为：ERROR 触发目标路由 CDP 503；OFFLINE 触发目标请求 transport failure 且无 HTTP status；CONFLICT 使用浏览器 stale revision、服务端真实并发 PUT 和按 requestId 关联的 HTTP 409。每个错误面板校验请求语言兼容性；恢复动作在冻结视口内确定性聚焦并居中；CONFLICT 证明 local draft retained/server revision preserved。环境风险必须显式保留：Chrome 151 与 driver 150 不一致，Selenium 可用 CDP 为 149，也与实际 Chrome 不一致。

仍未完成的是持久化且具备权限边界的 Dataset Authority、可信 freshness/review Authority、Active 生命周期与审批入口、Feature 的字段级 source map、客户级数据分类/ABAC/跨 Scope Payload Authority、目标环境 Candidate attestation、部署级 network deny/egress 观测、人工读屏、并发浏览器矩阵和六人可用性签署。目标浏览器分母已经冻结为正常态 60 格、服务错误 60 格、目标请求断网 60 格和 GP-04 真实保存冲突 6 格，共 186 格；本地干净候选已完成正常态 60/60 和异常态 126/126，但正式 Stage 0 仍按 `formalPassCount=0/27`、`formalImplementationGap=100%` 计。正式 NO_GO 的原因是这些结果尚未由 CI Candidate/Environment Authority 签发，外部 Evidence Store/issuer pins、目标环境/egress 证据、人工读屏、六人可用性以及产品/UX/QA、业务、安全、运行 Owner 签署仍缺失。环境风险仍需显式保留：Chrome `151.0.7922.138`、driver `150.0.7871.124`，Selenium 可用 CDP 为 149，存在版本不一致。当前 `/scenario-dataset` 是 Stage 0 Golden Demo Pack 的不可变业务投影，不是客户生产 Dataset 的写入 Authority。当前页面已调用同一受治理编译/应用级 Resource Registry/真实 Resource Operator/注册/执行链路并闭合开发 Oracle，结果为 `DEVELOPMENT_TEST_OWNED / CERTIFIABLE / NO_GO`；证据可信度提升不等于目标环境或发布责任已经闭合，因此不能替代 Owner 签署的 `S0-AC-04` 证据。

因此 Baseline 和 Manifest 必须保持 `NO_GO`、`PENDING` 或 `NOT_RUN`。元数据可读、开发自动化通过和启动探针成功，只能证明当前纵向切片可演示，不能冒充 Capability Studio 产品验收通过。

### 2.0.1 完整回归与证据完整性不是同一个结论

2026-08-19 的本地完整回归提供了一个必须保留的反例：

| 工程 | 测试观察 | 证据观察 | 可声明结论 |
|---|---|---|---|
| Resource Gateway | `clean verify` 报告 6717 项、0 失败、0 错误、28 跳过，Maven 返回 `BUILD SUCCESS` | Surefire 同时报告 `No space left on device`，至少一份报告未能写入；证据 manifest 不完整 | 仅作为开发观察，不产生 Acceptance 状态；按 `AC-STD-03/09` 不得作为正式 `PASS` |
| Test Kit | `clean verify` 报告 943 项、0 失败、0 错误、0 跳过 | JAR、shade 和 Javadoc 阶段完成 | Test Kit 本地开发回归通过；仍不替代目标环境与 Owner Authority |

正式验收必须先完成容量预检，再比较 `expectedEvidenceManifest` 与 `persistedEvidenceManifest`，并逐项验证写入收据、fingerprint 和可回放性。测试进程退出码为 0，只证明进程没有以失败退出；它不能覆盖跳过项、报告缺失、磁盘耗尽、存储失败或签署缺失。

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

这些截图与固定矩阵已关闭正常态 GP-01 至 GP-10 的中英文三视口、异常态 126 格、键盘路径、页面溢出、错误恢复、冲突数据保留、技术 ID/Raw JSON 泄漏和自动 axe 检查，但不覆盖人工屏幕阅读器、CI Authority 签发的不可变发布候选证据或人工可用性签署。业务资产名称和说明仍是 Canonical Demo Pack 的中文权威数据，语言切换只承诺产品界面文案，不应误报为业务内容本地化。

## 3. 更新流程

### 3.1 运行固定浏览器矩阵

在干净提交上运行唯一正式入口：

```bash
./scripts/run-capability-studio-browser-matrix.sh
```

脚本在任何 Maven 或 Chrome 运行前执行正式 fail-closed preflight：根文件系统至少剩余 `4194304 KiB`（4 GiB）和 `20000` 个 inode；artifact root、Resource Gateway 与 Test Kit 的 Maven `target`、`TMPDIR` 都必须通过实际可写探针。`CAPABILITY_STUDIO_MIN_FREE_KIB` 与 `CAPABILITY_STUDIO_MIN_FREE_INODES` 只能配置为非负整数；正式模式允许提高阈值，但低于合同下限会以 `RG.CAPABILITY_STUDIO.BROWSER_PREFLIGHT_FORMAL_THRESHOLD_BELOW_MINIMUM` 在 Maven 前拒绝。探针文件在检查后清理。2026-08-19 的一次本地观测约剩 `0.6 GiB`，在该条件下正式脚本会在候选构建前预检失败；每次运行仍以当次 preflight 实测为准，不能把这次观测视为稳定产品事实。

通过 preflight 后，默认输出使用唯一目录 `resource-gateway-examples/target/acceptance/runs/<commit-short>-<utc>-<pid>/`。正常态结果、异常态结果、`browser-matrix-evidence/`、`browser-anomaly-evidence/` 和 `capability-studio-browser-evidence-bundle-manifest-v1.json` 全部位于同一 root。显式 `--output` 与 `--anomaly-output` 必须同父目录，否则在启动前拒绝。clean 模式还要求该 root 在 preflight 写探针清理后保持 fresh；任何已有文件、子目录或 symlink，包括预存结果、manifest 或 evidence，都会以 `RG.CAPABILITY_STUDIO.BROWSER_PREFLIGHT_ARTIFACT_ROOT_NOT_FRESH` 在 Maven 前失败。dirty `--allow-dirty` 诊断不受 fresh 门禁影响，可以复用显式 existing base。脚本会构建一次生产前端与候选 JAR，先执行固定 60 格正常态真实 Chrome 矩阵，再执行固定 126 格异常态矩阵，并分别由 `resource-gateway-test-kit` 独立校验。证据文件固定分母为 438：60 个正常态 `.png` 截图，加上 126 个异常 obligation 各自同前缀的 `-error.png`、`-recovered.png` 和 `-trigger.json`，即 `60 + 126 × 3 = 438`；任意三文件、角色缺失、跨 obligation 引用或 normal 非 PNG 都以 `RG.CAPABILITY_STUDIO.BROWSER_EVIDENCE_BUNDLE_EVIDENCE_ROLE_MISMATCH` 拒绝。只有两个既有 JSON CLI 都输出 `VALID status=COMPLETE`，且 `CapabilityStudioBrowserEvidenceBundleCli` 以 normal-result、anomaly-result、artifact-root、manifest-output 四个固定参数退出 0，并只输出一行 `VALID status=COMPLETE expectedCount=438 persistedCount=438 manifestFingerprint=sha256:<64 lowercase hex>`，脚本才输出 `COMPLETE: 186/186` 和 `EVIDENCE_MANIFEST`。

工作树不干净时，默认命令会在浏览器启动前拒绝执行。开发诊断可显式运行：

```bash
./scripts/run-capability-studio-browser-matrix.sh \
  --allow-dirty --no-build \
  --anomaly-profile ERROR \
  --anomaly-gp GP-09 \
  --anomaly-locale en-US \
  --anomaly-viewport 1024x768
```

`--allow-dirty` 是强制开发模式，而不只是“允许工作树脏”：无论源码树为 `CLEAN` 还是 `DIRTY`，一旦传入该参数就跳过正式容量、fresh root 和 bundle 门禁，最多输出 `DEVELOPMENT_VERIFIED`，绝不生成正式 manifest 或 `COMPLETE: 186/186`。DIRTY 源码树必须复用一个已经独立验证为 `COMPLETE` 的正常态 Base Matrix。异常过滤只改变本次实际执行的 obligation，不改变 126 格固定分母；未执行项保持 `NOT_RUN`，独立 CLI 返回退出码 3。不得将开发结果复制为正式证据。`--no-build` 只用于复用已存在且与 Base Matrix 指纹一致的候选 JAR，不改变候选干净度和正式门槛。开发模式只能证明机制，不能替代外部 Candidate/Environment Authority、Evidence Store 或 Owner 签署。`CONFLICT` 过滤只允许 `GP-04`；非法 profile、GP、语言或视口会在构建和浏览器启动前失败。

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

### 3.5 Provider Conformance TCK 部署接入

Provider Conformance TCK 是部署接入的机制门禁，不是新的 Stage 退出合同。历史
[`v1`](../../schemas/resource-gateway-capability-studio/capability-studio-stage-acceptance-provider-conformance-result-v1.schema.json)
历史 v1 固定六项检查；当前
[`v2`](../../schemas/resource-gateway-capability-studio/capability-studio-stage-acceptance-provider-conformance-result-v2.schema.json)
增加 `AUTHORITY_BINDING` 和 `providerBindingFingerprint`。两个版本均使用 strict Draft 2020-12，所有对象禁止额外字段。v1 Schema 与语义保持不变，独立 Verifier 按 `schemaVersion` 分派，未知版本失败关闭；不得用 v2 规则重解释历史 v1 报告。

结果固定包含：

- `verdict`：`CONFORMANT`、`NON_CONFORMANT`、`BLOCKED`、`INPUT_INVALID`；
- `resultBinding`：只含 `resultId`、`revision`、`resultFingerprint`；
- `verifiedAt`、版本对应的固定检查、summary exact counts、固定且唯一的 `externalChecksRequired`，以及 `sha256:<64 位小写十六进制>` 的 `reportFingerprint`；
- v2 额外携带显式 `providerBindingFingerprint`；尚未取得合法 binding 时必须为 `null`，不能省略。

v1 的历史六项检查必须各出现一次：`LOCAL_PROTOCOL`、`BASELINE_AUTHORITY_ACCEPTANCE`、
`DETERMINISTIC_REPLAY`、`RESOLVER_WRONG_FINGERPRINT_FAIL_CLOSED`、
`EVIDENCE_POLICY_TAMPER_FAIL_CLOSED`、`OWNER_AUTHORITY_TAMPER_FAIL_CLOSED`。每项只能使用
`PASS`、`FAIL`、`BLOCKED`、`NOT_RUN`，并记录稳定 `reasonCode` 与 `challengeCount`。v2 在 `LOCAL_PROTOCOL` 后增加 `AUTHORITY_BINDING`，必须先验证合法、稳定的 lowercase SHA-256 binding，才允许访问后续 Authority。验证器按版本重新计算 summary；提交方提供的不一致计数必须拒绝。只有版本对应的全部检查均为 `PASS` 且总 `challengeCount > 0` 时，才允许 `CONFORMANT`。

部署接入按以下顺序执行：

1. 将部署拥有的 Provider 与 Test Kit 放在同一 classpath，并提供 `ServiceLoader` 注册。
2. 必须发现唯一一个 Provider。缺失、重复、注册非法或构造失败均产生 `BLOCKED` 报告，不得选择默认 Provider；只有输入 Stage Result 不合法时才产生 `INPUT_INVALID` 报告。
3. 先验证本地协议，再调用部署依赖；协议非法或前置结果不满足时保持零外部调用。
4. 按固定顺序运行正向与负向挑战，重算结果/报告指纹，并只写入显式输出路径。

正向挑战覆盖本地协议、Baseline Authority 接受、确定性重放；负向挑战覆盖错误 fingerprint 的 resolver、Evidence Policy 篡改和 Owner Authority 篡改，均必须 fail-closed。Provider 不得使用 fallback、替代信任根或自签结果。预期 CLI 形态为：

```bash
CapabilityStudioStageAcceptanceProviderConformanceCli \
  --result <stage-acceptance-result.json> \
  --output <provider-conformance-result.json>
```

CLI、TCK、结果 Builder、独立 Verifier 和双版本 Schema 打包均已实现。CLI 发布报告前会同时校验报告与原 Stage Result，独立复算 `resultId`、`revision` 和完整 Stage Result fingerprint；只验证报告自身不能证明来源绑定。v1 切片封板时的 20/20、44/44、978/978 只能作为历史观测；当前 v2 观测见 3.6 节。当前观测不改变固定 obligation 分母，也不是企业部署 Provider 的正式符合性报告。

四种结果语义必须保持可区分：`FAIL` 表示已执行挑战证明机制不符合；`BLOCKED` 表示 Provider 装配、依赖或信任输入不可用，无法作出结论；`INPUT_INVALID` 表示输入 Stage Result 不能按协议解释。三者都不能通过省略检查或 fallback 变成 `CONFORMANT`。`CONFORMANT` 只证明 Provider 相对当前部署信任配置的机制一致，不增加 `formalPassCount`，不替代外部组织归属、真实目标环境与 Owner 签署。固定外部检查要求为：

`TRUST_ROOT_ORGANIZATION`、`KMS_HSM_CUSTODY`、`TARGET_ENVIRONMENT_TRANSPORT`、
`DEPLOYMENT_EGRESS_ENFORCEMENT`、`OWNER_PROCESS_ATTESTATION`。

三组合同使用稳定 ID 而不是测试数量：`PCTCK-CONTRACT-v1` 的分母是
`PCTCK-AC-01..10`，`DEPLOY-CONTRACT-v1` 的分母是 `DEPLOY-AC-01..08`，
`AUTHBUNDLE-CONTRACT-v1` 的唯一 Bundle obligation denominator 是 `ABP-001..024`；
`AUTHBUNDLE-AC-01..10` 是聚合验收行，不额外增加分母。每组合同都必须有共享 Preconditions、Oracle、system
invariants、`FAIL/BLOCKED` 规则、Evidence manifest 和 Owner/签署角色；各 AC 行不得脱离共享
上下文单独解释。当前 Bundle loader 15/15、跨版本 Provider/TCK/CLI/shell 58/58、参考 Provider
6/6 和 Test Kit 1030/1030 仅是开发观测，不改变分母，也不增加 `formalPassCount=0/27`。

#### 3.5.1 挂载企业 Authority Bundle

参考 Provider 位于
[`resource-gateway-test-kit/examples/capability-studio-mounted-authority-provider/`](../../../resource-gateway-test-kit/examples/capability-studio-mounted-authority-provider/)。它只读取部署方挂载的 Bundle，并委托 `CapabilityStudioMountedAuthorityBundle` 构造 Resolver、Issuer Policy 和 Owner Authority。Provider 不包含网络 fallback、默认信任根、私钥或签发逻辑。

Bundle root 只允许包含 Manifest 直接引用的 JSON 普通文件：

```text
<authority-bundle-root>/
  authority-bundle-v1.json
  evidence-*.json
  owner-signature-*.json
  evidence-issuer-key-set.json
  owner-key-set.json
```

Manifest 通过 `bundleFingerprint` 绑定 Artifact 原始文件摘要、Key Set 语义 pin、Issuer/Scope、Evidence kind、Owner role、Actor allow-list 和 TTL。Loader 在构造时一次性读取并防御性快照全部文件；后续 Resolver 不回读挂载目录。Bundle 使用受信时钟验证生效和过期时间，目录逃逸、符号链接、未知字段、重复绑定、超限、指纹漂移和生命周期非法均失败关闭。

正式部署还必须提供两类 out-of-band 期望 pin。Manifest 的 `bundleFingerprint`、Provider 原子
`AuthorityBinding.fingerprint()` 与部署任务的
`BLOGE_EXPECTED_AUTHORITY_BINDING_FINGERPRINT` 固定同一份 post-run Authority material；
`BLOGE_EXPECTED_FORMAL_AUTHORITY_BINDING_FINGERPRINT` 则独立固定包含 Target Admission 与部署
Authority 配置的完整 formal outer。两者语义不同，不要求相等。期望 pin 必须来自受控部署清单、
制品签名系统或等价 Authority；不得由脚本读取 Bundle 后自行计算并回填。

```bash
mvn -f resource-gateway-test-kit/pom.xml clean install
mvn -f resource-gateway-test-kit/examples/capability-studio-mounted-authority-provider/pom.xml \
  clean verify

BLOGE_EXPECTED_TEST_KIT_JAR_SHA256='<64 位小写十六进制>' \
BLOGE_EXPECTED_STAGE_RESULT_SHA256='<64 位小写十六进制>' \
BLOGE_EXPECTED_PROVIDER_CLASSPATH_SHA256S='<64 位小写十六进制>,<64 位小写十六进制>' \
JAVA_TOOL_OPTIONS='-Dbloge.capabilityStudio.authorityBundleRoot=/mnt/authority-bundle' \
BLOGE_EXPECTED_AUTHORITY_BINDING_FINGERPRINT='sha256:<post-run AuthorityBinding material>' \
BLOGE_EXPECTED_FORMAL_AUTHORITY_BINDING_FINGERPRINT='sha256:<formal outer>' \
JAVA_BIN="$(command -v java)" \
resource-gateway-test-kit/scripts/verify-capability-studio-stage-acceptance.sh \
  --test-kit-jar resource-gateway-test-kit/target/bloge-resource-gateway-test-kit-1.0.0-cli.jar \
  --provider-classpath \
    'resource-gateway-test-kit/examples/capability-studio-mounted-authority-provider/target/bloge-capability-studio-mounted-authority-provider-1.0.0.jar' \
  --stage-result '<stage-acceptance-result-v2.json>' \
  --conformance-output '<new-provider-conformance-result-v2.json>'
```

`JAVA_TOOL_OPTIONS` 由部署任务控制，用于向两个一次性 JVM 注入 Authority Bundle root；formal-v2
所需的 Target Admission Bundle root 与 execution-lease state root 也仍由部署控制的 JVM options
注入。现有八参数 runner 不接收、复制或快照这些 root，不得混入未审批的 Java Agent、classpath
或调试选项。Bundle mount 仍应使用只读卷、独立运行身份或容器隔离；Provider fingerprint 的一致性
检查不能阻止同一 UID 在单个校验窗口内恶意修改并恢复文件。本步骤不是完整的 formal-v2 Bundle
snapshot/Evidence-manifest 模式。

该流程成功只能证明：两个阶段使用了同一份被部署方 pin 住的 Authority 配置，并且 Provider 通过机制挑战。它不能证明企业信任根归属、KMS/HSM 私钥托管、目标环境 transport、部署级 egress 原始观测和 Owner 审批真实发生。五项外部责任仍须以精确引用、指纹、时间窗和真实责任人签署进入同一 Stage Result Evidence 闭包。

#### 3.5.2 声明并快照 formal-v2 只读输入树

`CapabilityStudioFormalInputTreeSnapshotter` 为 `AUTHORITY_BUNDLE` 和
`TARGET_ADMISSION_BUNDLE` 提供同一套严格协议。协议 Schema 为
`capability-studio-formal-input-tree-v1.schema.json`。源 root 必须是 absolute normalized
路径。源 root 及所有已有祖先必须是具有稳定 `fileKey` 的真实目录，且不能是符号链接。

每棵树必须是 flat direct-child exact tree。Snapshotter 依据对应 Bundle Manifest 计算允许文件
闭包，并拒绝子目录、缺失文件、额外文件、空文件、不安全文件名、符号链接、硬链接和非普通文件。
Authority 输入树沿用 mounted loader 上限：最多 641 个文件，Bundle Manifest 1 MiB、artifact
64 KiB、key set 1 MiB、引用材料 32 MiB、完整 Bundle 树 33 MiB。Target Admission 必须恰好
8 个文件，Bundle Manifest、target/Attestation/key set 单文件 1 MiB，proof 单文件 64 KiB，
总计 8 MiB。文件系统不能证明稳定 `fileKey`、`unix:nlink`、POSIX 权限、同文件系统原子 move
或文件/目录 force 时，操作失败关闭。
边界测试构造真实 Authority 闭包：引用材料精确 32 MiB、Manifest 精确 1 MiB、完整树精确
33 MiB，并拒绝增加的第一个字节。

树内容身份只包含固定版本、`treeKind`、Bundle semantic fingerprint、排序后的 direct-child
相对文件名、字节数和原始 SHA-256。绝对路径、mtime、inode/`fileKey`、UID 和 GID 不进入树内容
身份。Snapshotter 仅使用这些主机坐标比较两次完整 inventory，并检测读取期间的文件替换、同尺寸
同 mtime 内容变化、同名重建和 root swap。

声明模式不写文件：

```bash
java -cp resource-gateway-test-kit/target/bloge-resource-gateway-test-kit-1.0.0-cli.jar \
  com.leanowtech.bloge.gateway.testkit.CapabilityStudioFormalInputTreeCli \
  --mode declare \
  --tree-kind TARGET_ADMISSION_BUNDLE \
  --source-root /mnt/target-admission-bundle \
  --expected-bundle-semantic-fingerprint 'sha256:<64 位小写十六进制>'
```

声明结果不是证据，也不能自行签发 pin。部署 Authority 必须通过独立渠道审批并发布
`treeFingerprint` 与 publication fingerprint。Manifest self-fingerprint 和文件 raw hash 只提供
本地完整性，正式真实性完全来自部署侧 out-of-band pin，不存在 source/loader 自签 fallback。
获得这些 pin 后，才能执行快照模式：

```bash
java -cp resource-gateway-test-kit/target/bloge-resource-gateway-test-kit-1.0.0-cli.jar \
  com.leanowtech.bloge.gateway.testkit.CapabilityStudioFormalInputTreeCli \
  --mode snapshot \
  --tree-kind TARGET_ADMISSION_BUNDLE \
  --source-root /mnt/target-admission-bundle \
  --expected-bundle-semantic-fingerprint 'sha256:<64 位小写十六进制>' \
  --snapshot-output-dir /run/formal-input/target-admission \
  --expected-tree-fingerprint 'sha256:<部署 Authority 独立批准的 tree pin>' \
  --expected-publication-fingerprint 'sha256:<部署 Authority 独立批准的 publication pin>' \
  --transaction-nonce 'sha256:<部署生成的高熵稳定 nonce>'
```

全新事务必须先验证 tree pin，再创建 publication lease、owner claim、staging 或 final；缺失或
格式错误的 tree/publication/nonce 坐标在任何文件系统写入前拒绝。恢复路径在读取 source 前先
按外部坐标检查已提交 final，因此 source 挂载已卸载时，精确发布仍可返回 `RECOVERED`。唯一发布
对象是 wrapper 目录，其中只允许
`bundle-root/` 和 `formal-input-tree-v1.json`；Manifest 位于 Provider 兼容的 `bundle-root/`
之外，但不再使用另一个独立输出路径。输出 parent 必须是精确 `0700`；固定的 owner-only、
single-link lock file 与跨进程 `FileLock` 覆盖 final 检查、PREPARED 恢复和 commit。source/output
祖先链通过 NOFOLLOW file identity、UID、mode 与 sticky-directory ownership 规则检查，并在关键
阶段复核。

transaction identity 绑定固定协议、kind、Bundle semantic fingerprint、tree pin、独立 publication
fingerprint 和高熵稳定 nonce。严格 owner descriptor 必须先于 payload 持久化；文件仅可通过该
transaction 的 `.part` 执行“写入、file force、chmod `0400`、再次 file force”，再原子安装子项。
final wrapper 通过 create-new 语义创建为 `0700` PREPARED，绝不替换已有 final。完整
`bundle-root/` 在 final 内原位 create-new，不再 rename 或递归协调非空 Bundle 目录。每个已认证
文件从 staging 单独原子安装到 final Bundle root，全部完成后才 force、chmod `0500` 并再次
force；source/target Bundle 目录 identity 相同会非破坏性 `BLOCKED`，绝不据此删除任一目录名。
随后才把
`formal-input-tree-v1.json` 原子安装为唯一逻辑 commit marker。协议不声称 wrapper 目录项一次性
出现。之后 wrapper chmod `0500`、force、force parent 并执行双重 persisted verify。每次原子文件
rename 都先 force target parent，再 force 不同的 source parent；同 parent 只 force 一次。文件恢复
显式区分 source-only、target-only、both、neither。source-only 与 target-only 必须各自精确为
`nlink=1`。both 仅在两个名字指向同一 inode、精确 `nlink=2`，且 owner descriptor/nonce、UID、
权限、bytes 与 declaration 全部匹配时可协调：先重新验证并 force target 文件，再 force target
parent，然后 unlink 已认证 source 名并 force source parent。unlink 前后崩溃均保留已持久化 target，
可确定性重试。distinct-inode both 与任何其他 link count 均保持原样并 `BLOCKED`。

public `verify` 是独立的纯只读离线审计，不创建或打开 publication lock，也不要求可写 `0700`
publication parent。审计 parent 可为私有只读目录或 `0755`，但不得 group/world writable，owner 与
祖先链仍须满足安全规则。调用方必须提供预期 kind、Bundle semantic/tree/publication fingerprint
和 transaction ID；操作前后均验证 wrapper/`bundle-root/` 为 `0500`，所有 Bundle 文件和 Manifest
为 `0400`，且不修改任何 sibling。验证成功只证明内容与坐标，不签发 durability receipt。
闭包结构、权限、canonical JSON 或 schema 不匹配为 `INVALID`；wrapper 缺失/不可读、运行时 I/O
或必要 metadata 能力故障保持 `UNAVAILABLE`。普通文件缺少稳定 `fileKey`、POSIX/`unix:*`
metadata 不可用或读取中 metadata 消失属于能力/依赖不可用；symlink 与错误文件类型仍是结构
`INVALID`。

失败时不按路径递归 cleanup。重试只认领精确 owner receipt 与 transaction-owned partial 前缀；
新发布返回 `COMMITTED`，精确 final（包括 commit marker 后 parent force/verify 中断）在补齐
barrier 并复验后返回同一 receipt 的 `RECOVERED`。final 独立复验后，仅可清理精确空 owned
staging 与 owner descriptor；清理失败保留可认证 residue，不影响 final。未知 owner/temp/
staging/final 返回 `BLOCKED`，且不被 chmod、覆盖或删除。stdout 写入失败后，持久化 final 仍
通过下一次调用恢复，不回滚发布。publication lease 使用固定 64 个 JVM `ReentrantLock` stripe，
不会随攻击者选择的 Path 永久增长。`FileChannel.tryLock` 每次 miss 或 overlapping lock 都消耗一个
独立硬尝试；尝试数按 `ceil(timeout / 20ms)` 推导并封顶 1024，因此 constant ticker 也有界。两个
锁层另共享同一 monotonic 5 秒预算。预算不使用绝对 deadline 或 `Long.MAX_VALUE` sentinel；
内部保存 last tick 与剩余预算，
每次用自然溢出且 wrap-safe 的非负 delta 单向
扣减，绝不补回。每次取得锁前后都复核剩余预算，过期后释放刚取得的锁；正常跨 wrap 后再次倒退、
振荡或失败的 ticker 都作为 `UNAVAILABLE` 关闭。超时稳定返回 `PUBLICATION_LOCK_TIMEOUT`，线程
中断状态会保留。

本地协议不能防御忽略 publication lease、在剩余 path-based 窗口完成精确 ABA 替换的同 UID
主动攻击者、特权 actor，也不能防御返回虚假 identity metadata 的内核或文件系统。部署必须使用
只读挂载、独立运行身份或进程隔离覆盖该威胁。

CP0-CP6 的重试状态固定为 `COMMITTED`，CP7-CP10 固定为 `RECOVERED`。子 JVM 输出重定向到
私有临时文件，等待、TERM、强制终止与 reap 全部有界，并用故意 HANG worker 认证 harness 不会
挂死。CP0-CP10 仅是本切片的 crash/output 开发测试矩阵，不是正式 Stage 27 验收结论，不改变
`formalPassCount=0/27`。

现有八参数 runner 尚未读取这两棵输入树的 declaration/snapshot，也尚未生成 formal-v2 Evidence
manifest。部署 Authority 后续仍须对批准的 declaration 签发可信材料，并将其绑定到完整 runner 和
Evidence 闭包。不能把本节 CLI 的 `DECLARED` 或 `SNAPSHOT` 输出当作正式验收结果。

#### 3.5.3 观测既有部署状态并生成 Lease Transcript

Test Kit 新增两份 strict Draft 2020-12 wire contract：
`capability-studio-deployment-state-observation-v1.schema.json` 与
`capability-studio-execution-lease-transcript-v1.schema.json`。Observation 使用稳定
`evidenceTransactionId`。可迁移的 `stateMaterialFingerprint` 绑定 state、checkpoint、revocation
head、lifecycle head、generation、fencing 与 lease inventory 语义；path、inode、UID、mode、mtime
和 wall-clock 不进入该指纹。BEFORE/AFTER 只用于交叉验证，不能单独证明某个并发 Lease 造成了
状态转换。

Mounted Provider 的 v4 full-evidence store 会在 Lease 原子事务中持久化 immutable transition
witness。state core、witness material、final state 与 checkpoint 采用无循环的分层承诺，解析时重放
完整 transition chain。witness 精确绑定 pre/post state core/final fingerprint、generation、fencing sequence、checkpoint/head、request
fingerprint 与 receipt fingerprint。`RECOVERED` 返回原事务保存的 witness；full-evidence 模式不会
从当前状态反推。旧 v2 store 仍可供现有 `CapabilityStudioStageAcceptanceCli` 使用，但不提供
`formalEvidenceAuthorityBinding()`，因此 full-evidence 请求会失败关闭。迁移必须使用新的 state
root，并重新声明与签发 descriptor/formal outer pin，不能原地升级 v2 store。

`MountedCapabilityStudioDeploymentStateObservationCli` 只打开既有 store。它在固定 JVM lock stripe
和共享 OS descriptor lock 下验证 root/ancestor/descriptor identity、四文件精确闭包、两次完整
inventory 与每个文件的双读一致性。生产调用链不执行 initialize、repair、force、chmod、create、
delete 或 move。`state generation=1` 但 `checkpoint generation=0` 的 crash intermediate 返回
`BLOCKED/UNAVAILABLE`，且 namespace、bytes、mode、UID 与 mtime 保持不变。普通读取可能更新
atime；若部署要求 atime 也稳定，必须使用只读或 `noatime` 挂载。

`CapabilityStudioExecutionLeaseEvidenceCli` 复用现有正式校验与 Lease commit 路径，但要求 v4
companion SPI。Provider 在同一个 store 独占锁内持久化 BEFORE、commit/recover、捕获精确 AFTER 并
持久化 committed journal；Transcript 交叉校验 observation 与 witness 共有的 pre/post commitment、
generation、fencing、checkpoint/head、request 和 receipt 坐标。仅存在于 observation 的 raw 字段是
独立交叉检查，不能表述为与 witness 全字段相等；generation 差异本身也不能证明归因。证据父目录
必须按 wrapper lock -> store lock 的固定顺序，通过稳定 ancestor identity 与
state root 做物理隔离检查。pending recovery 加载独立 binding，其中 `recovery()` 在独占事务锁内
读取既有 descriptor/state/checkpoint/head，不得 initialize、repair、force、chmod 或修改 store。
只有该查询返回可识别的一代 writer crash intermediate 时，`interruptedRecovery()` 才能执行既有
writer 的固定 predecessor/successor 修复；它不执行当前 admission，也不创建 Lease。稳定 v2 transaction wrapper 绑定 owner、Stage
raw 与 independently pinned outer，并保留按 generation 编号的 BEFORE journal、`ABSENT` closure、
committed transcript source 和 durable commit manifest。manifest 绑定 owner、request identity、
BEFORE raw/journal、transcript raw/canonical、receipt、witness、attempt generation 与前序 closure。
确定性 part 通过 file force、`0400`、link、target parent force、source unlink、source parent force
发布；未知或 distinct-BOTH 对象保持原样并阻断。已提交 final 会在 Provider discovery、Stage 时效与
lifecycle/revocation preflight 之前离线恢复；journal 恢复只读取原事务持久化的历史 transition
evidence，绝不从当前 state 反推，也不创建第二个 Lease。`ABSENT` 会不可变地关闭旧 attempt，并以新的 generation 和当前时效、
lifecycle、admission 输入重新执行；`CONFLICT` 映射为 `INVALID`，缺失、不可读、锁或 I/O outage
映射为 `BLOCKED`。
持久化字段固定为 `evidencePublicationStatus=COMMITTED`，本次调用的成功行才报告 `RECOVERED`。
Lease 提交后的证据失败不会回滚，也不会输出 `ACCEPTED`；stdout 部分写失败后可恢复同一 final。

部署操作顺序固定为 provision -> 独立保存 publication pin -> execute/recover -> read-only verify：

```bash
java -cp '<test-kit-cli-jar>' \
  com.leanowtech.bloge.gateway.testkit.CapabilityStudioExecutionLeaseEvidencePublicationProvisioningCli \
  --publication-parent /absolute/private/execution-lease-evidence \
  --publication-nonce 'sha256:<deployment-generated 64 lowercase hex>'

BLOGE_EXPECTED_AUTHORITY_BINDING_FINGERPRINT='sha256:<formal outer>' \
BLOGE_EXPECTED_CAPABILITY_STUDIO_EVIDENCE_PUBLICATION_FINGERPRINT='sha256:<publication pin>' \
java \
  -Dbloge.capabilityStudio.authorityBundleRoot=/absolute/path/to/authority-bundle \
  -Dbloge.capabilityStudio.targetAdmissionBundleRoot=/absolute/path/to/target-admission-bundle \
  -Dbloge.capabilityStudio.executionLeaseStateRoot=/absolute/path/to/private-lease-state \
  -cp '<test-kit-cli-jar>:<provider-jar>' \
  com.leanowtech.bloge.gateway.testkit.CapabilityStudioExecutionLeaseEvidenceCli \
  /absolute/path/to/stage-acceptance-result-v2.json \
  /absolute/private/execution-lease-evidence/execution-lease-transcript-v1.json

java -cp '<test-kit-cli-jar>' \
  com.leanowtech.bloge.gateway.testkit.CapabilityStudioExecutionLeaseEvidenceBundleVerifyCli \
  --transcript /absolute/private/execution-lease-evidence/execution-lease-transcript-v1.json \
  --expected-stage-result-raw-fingerprint 'sha256:<Stage Result raw pin>' \
  --expected-formal-outer-fingerprint 'sha256:<formal outer pin>' \
  --expected-publication-fingerprint 'sha256:<publication pin>'
```

Provision 成功为 `PROVISIONED`/exit `0`。Evidence 首次成功为 `ACCEPTED` 且
`evidencePublicationStatus=COMMITTED`，精确重试为 `RECOVERED`；Bundle verifier 成功为
`VERIFIED verificationScope=DURABLE_WRAPPER`/exit `0`。结构、Schema、pin 或坐标冲突固定为
`INVALID`/exit `2`；权限、锁、I/O、metadata、Provider 或 store 不可用固定为
`BLOCKED|UNAVAILABLE`/exit `3`；治理拒绝保持 `REJECTED`。所有失败输出均为 payload-free 闭集，
不转发 Provider reason、路径、凭证或业务数据。

一个 provisioned parent 只对应一个事务：declaration 固定绑定 direct-child
`execution-lease-transcript-v1.json` 与由 publication nonce 派生的 transaction identity。同一
parent 只允许同一 output 的精确重试；新业务事务必须新建独立 private parent，重新 provision，
并由部署方独立认证新的 publication pin。

`CapabilityStudioExecutionLeaseTranscriptVerifyCli` 只验证 Transcript 的 strict Schema、canonical
fingerprint 与嵌套语义自洽，不能作为 FELT-08/FELT-14 的 durable wrapper 证据。
`CapabilityStudioExecutionLeaseEvidenceBundleVerifyCli` 还要求部署方提供 Stage Result raw 与 formal
outer 的 out-of-band pin，并以只读方式验证 owner hard-link claim、完整 attempt chain、retained
committed source、strict commit manifest、final transcript、receipt 与 witness。该 verifier 不创建
publication lock，也不签发 durability receipt。

`commit-manifest-v1.json` 只是内层制品清单，绑定 owner、request、BEFORE、attempt chain、transcript、
receipt 与 witness；它不是最终提交标记。外层 `final-commit-v1.json` 还要独立绑定 manifest 的 raw/
canonical fingerprint、owner 与 final transcript。只有两层都经独立 verifier 闭合，才能认定 durable
Bundle 完整；单独复制 final 或重算一份自洽 manifest 都不能绕过 owner closure。

参考 store 的完整 closure 上限为 32 MiB，最多保留 1,024 个 immutable Lease；单个 evidence
transaction 最多保留 1,024 个 attempt。容量耗尽归类为不可用，不会自动删除、覆盖、压缩或归档
历史。扩容需要显式协议/配置升级和新的 state root，再签发新的 descriptor、component、
publication 与 formal outer pin，不能原地截断旧 store。普通文件继续要求协议规定的精确 nlink；
目录只要求稳定正数，因为 APFS 可对目录报告 `nlink=1`，这不放宽普通文件 hard-link 检查。

生产 Test Kit 与 Provider 不读取 ambient crash property，也不存在可由配置触发的 `Runtime.halt`
路径。测试构建会确定性生成一份 source/class pin 均固定的 shadow overlay，其中 17 个实现检查点完整
覆盖 FELT-10 冻结的 14 个语义崩溃窗口。strict harness manifest 固定逐点映射，ordinary/shaded Test
Kit JAR 则携带同一 Evidence CLI source/class identity；测试会独立重算两份 JAR 的 class digest，并在
执行前与当前源码及 shadow source digest 交叉验证。双进程矩阵必须先观测到第一 JVM 持有 publication
lock，再观测到第二 JVM 的真实 `tryLock()` miss，才允许释放第一 JVM；随后还要由 production-only JVM
恢复同一 receipt、witness 与 transcript。READY/LOCK_MISS 主 marker 都必须在 parent force 返回后再
发布 completion ACK；父进程等待 ACK 并重读主 marker。固定矩阵还会暂停在 marker 已可见但 parent force
未完成的窗口，证明路径可见不能提前放锁。只有独立 test-harness JAR 能触发这些观测或终止进程。它们是
实现覆盖，不是 FELT `14/14` 裁决。参考实现还假设受信内核/UID、真实 force 与原子文件系统语义、受保护
的 out-of-band pin 和诚实部署自动化；同 UID 或特权主体对全部可信对象做一致替换、虚假 durability、
主机时钟失真，以及外部 Candidate/Environment/Owner Authority 缺失均不在本地参考信任结论内。

当前八参数 runner 尚未编排 input-tree snapshot、BEFORE、formal run、AFTER 与 Transcript
publication。本轮全量回归与第九轮独立 P0/P1 复审均已闭合，因此组件机制可标记为
`DEVELOPMENT_VERIFIED`。FELT-01 仍为 `PARTIAL`，FELT-14 仍为 `NOT_RUN`；该开发裁决不是正式验收
证据，也不改变 `formalPassCount=0/27`。

### 3.6 CI 与目标环境部署门禁

部署任务必须使用
[`verify-capability-studio-stage-acceptance.sh`](../../../resource-gateway-test-kit/scripts/verify-capability-studio-stage-acceptance.sh)
串行执行 Provider Conformance 和正式 Stage Acceptance。脚本会把 Test Kit、Provider classpath 全部条目和 Stage Result 复制为权限收紧的运行快照，并在父 shell 内保存每个 SHA-256；两步只读取同一组快照字节，每步结束后必须重新计算全部摘要。源文件在快照后发生变化不会影响第二步，快照发生持久修改时脚本会在下一阶段前失败。禁止把两个命令拆到不同候选、不同环境或不同信任配置中执行。

```bash
BLOGE_EXPECTED_TEST_KIT_JAR_SHA256='<64 位小写十六进制>' \
BLOGE_EXPECTED_STAGE_RESULT_SHA256='<64 位小写十六进制>' \
BLOGE_EXPECTED_PROVIDER_CLASSPATH_SHA256S='<64 位小写十六进制>,<64 位小写十六进制>' \
JAVA_TOOL_OPTIONS='-Dbloge.capabilityStudio.authorityBundleRoot=/mnt/authority-bundle' \
BLOGE_EXPECTED_AUTHORITY_BINDING_FINGERPRINT='sha256:<post-run AuthorityBinding material>' \
BLOGE_EXPECTED_FORMAL_AUTHORITY_BINDING_FINGERPRINT='sha256:<formal outer>' \
JAVA_BIN="$(command -v java)" \
resource-gateway-test-kit/scripts/verify-capability-studio-stage-acceptance.sh \
  --test-kit-jar resource-gateway-test-kit/target/bloge-resource-gateway-test-kit-1.0.0-cli.jar \
  --provider-classpath '<enterprise-provider.jar>:<provider-dependency.jar>' \
  --stage-result '<stage-acceptance-result-v2.json>' \
  --conformance-output '<new-provider-conformance-result-v2.json>'
```

执行前必须满足以下条件：

1. `JAVA_BIN` 指向一个真实可执行文件；部署镜像提供 `sha256sum` 或 `shasum`。脚本不执行拼接命令，也不使用 `eval`。
2. Test Kit JAR、Stage Result 和每个 Provider classpath 条目都是可读、非符号链接的普通文件。
3. Stage Result 不超过 4 MiB。
4. Conformance 输出目录已存在且可写，输出文件尚不存在。
5. Test Kit、Provider classpath 和 Java runtime 来自部署控制的不可变制品位置，并至少保持到运行快照创建完成。运行快照不能替代制品签名或供应链校验。
6. 验收任务运行在专用一次性 JVM 中。Provider 不得启动未受管异步线程写入全局输出流。
7. `BLOGE_EXPECTED_TEST_KIT_JAR_SHA256`、`BLOGE_EXPECTED_STAGE_RESULT_SHA256` 和
   `BLOGE_EXPECTED_PROVIDER_CLASSPATH_SHA256S` 由部署 Authority 以 out-of-band 方式注入，分别
   固定 Test Kit、Stage Result 和按 classpath 顺序排列的 Provider 制品；不得由脚本读取文件后自行回填。
8. 部署 Authority 以 out-of-band 方式同时注入两个 lowercase SHA-256：
   `BLOGE_EXPECTED_AUTHORITY_BINDING_FINGERPRINT` 只固定 Conformance/post-run Authority material，
   `BLOGE_EXPECTED_FORMAL_AUTHORITY_BINDING_FINGERPRINT` 固定完整 formal outer。脚本不得从
   Stage Result、Provider 输出或 Bundle 运行时自发现，也不得要求二者相等。

当前 runner 已读取三类制品 pin 和两类 binding pin，并在 Java 启动前验证格式、数量、顺序和
快照一致性。预检后，父进程会删除两个部署环境变量；Conformance 子进程只接收 post-run
material pin，formal 子进程通过既有 child CLI 环境变量名只接收 formal outer pin，另一 pin 在
对应子进程中必须不存在。根目录仍由部署控制的 JVM options 注入，不属于现有八参数接口。该
runner 尚不提供 formal-v2 Bundle 快照或 Evidence manifest 模式。这只证明开发机制已对齐；正式
验收仍需部署 Authority 真实注入并签署同一证据闭包。

脚本先运行 Conformance CLI。只有退出码为 `0`、stdout 恰好为一行规范 `CONFORMANT` 结果，且
报告是 1 至 131072 字节的可读、非符号链接普通文件时，脚本才运行正式 Stage Acceptance CLI。
正式 CLI 也必须退出 `0`，且 stdout 恰好为一行固定顺序的 expanded `ACCEPTED` 结果。脚本验证
Conformance 与 formal inner 等于 post-run pin、formal outer 等于独立 formal pin，并按固定 JSON
域版本独立复算 deployment aggregate 与 formal outer。两类成功输出都必须由已解析字段重建为
canonical 单行及结尾换行，并与原始 stdout 逐字节相等；NUL、隐藏字节、额外空白、字段乱序或
附加行均失败关闭。脚本随后才计算该原始 formal transcript 的 SHA-256。成功时只输出：

```text
ACCEPTED status=ACCEPTED authorityMaterialFingerprint=sha256:<64 位小写十六进制> formalOuterFingerprint=sha256:<64 位小写十六进制> providerConformanceFingerprint=sha256:<64 位小写十六进制> leaseCommitStatus=<COMMITTED|RECOVERED> leaseReceiptFingerprint=sha256:<64 位小写十六进制> formalTranscriptFingerprint=sha256:<64 位小写十六进制>
```

退出码 `3` 表示 Conformance 或正式验收给出“不通过、受阻或拒绝”的有效裁决；退出码 `2` 表示参数、前置检查、读写、Provider 配置或子进程输出不合法。子进程 stdout/stderr 只进入输出目录下的权限受限临时目录，脚本不会在失败时回显这些内容。脚本收到 `HUP`、`INT` 或 `TERM` 时，会向当前 Java 子进程发送 `TERM`，最多等待 5 秒，必要时升级为 `KILL`；脚本回收子进程后再删除临时目录。Provider 不得启动未受管操作系统子进程。

正式 CLI 在本地协议通过后，会隔离 Provider discovery、accessor 和同步 Authority 回调对 `System.out/System.err` 的直接写入，并在异常或 `Error` 离开隔离窗口前恢复原输出流。该机制只处理同步调用，不是异步日志治理方案。

本门禁是部署执行机制，不是签署收据。脚本不生成组织信任根，不托管 KMS/HSM 私钥，不证明目标环境或部署 egress，也不替代 Owner 流程签署。缺少任一外部责任时，即使脚本机制测试全部通过，正式 `formalPassCount` 也不能增加。

2026-08-20 的开发复验结果为：Bundle loader 15/15、跨版本 Provider/TCK/CLI/shell 58/58、参考 Provider 6/6、Test Kit `clean verify` 1030/1030，均为 0 失败、0 错误、0 跳过；普通 JAR、shaded JAR、Provider JAR、v1/v2/Bundle Schema packaging 和 Javadoc/doclint 同时通过。该结果只允许把 `S0-DEV-GOV-29` 标为 `DEVELOPMENT_VERIFIED`。

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
