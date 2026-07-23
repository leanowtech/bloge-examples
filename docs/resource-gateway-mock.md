# Resource Gateway 业务能力镜像与保真演练工业化演进方案 v2.0

> 核心判断：客服业务的长期壁垒不是接入了多少接口，而是对所服务客户业务的拟合能力。
> 拟合保真度决定自动化服务、策略优化和正确性保障的上限。Resource Gateway 应在现有
> 画布、DAG、测试控制面、证据和回放能力之上，演进为可生成、运行、度量并持续校准业务
> 能力镜像的 Tool Authoring Runtime。

| 文档属性 | 内容 |
|---|---|
| 状态 | Accepted / In implementation；Stage 0 仓库内工程退出门禁已通过；Stage 1 compiler、resolver provenance、payload-free evidence 签发/独立复验、scope-isolated durable store、受保护 Plan/Run/Evidence API、durable request fencing、动态 occurrence budget、payload-free operation audit、固定基数指标、部署隔离证明协议/离线验真、M-of-N authority key-set trusted distribution、full-scope attestation ingest/current-only distribution/irreversible revocation、deployment agent pinned mTLS/atomic cache 已完成；运行时双重绑定、跨语言 canonicalization 与 certification 门禁继续实施 |
| 目标读者 | Resource Gateway、BLOGE Runtime、ANEKE、TEE/数据平台、QA、SRE、安全与业务运营团队 |
| 设计范围 | external/composed 能力建模、镜像运行、保真语料、有状态世界、场景演练、证据、保真度与结果校准 |
| 非目标 | 不重做 ANEKE 的资产治理和发布门禁；不允许测试控制进入生产业务请求；不把观测频率直接当成业务正确性 |
| 基准日期 | 2026-07-23 |

### 实施快照（2026-07-23）

- Stage 0 第一增量已完成 `CapabilitySnapshot`、`CapabilityContract`、`EffectContract`、
  `ArtifactProvenance` 和 exact `MirrorArtifactRef` Java 协议内核。
- Stage 0 第二增量已完成 `CapabilityProjectionService`、`CapabilityProjectionContext` 和
  `CapabilityEffectAnalyzer`：Resource、外部 Operator、Graph 可生成 sealed snapshot；Graph 只闭包 external/nested
  capability，PURE 内部节点仍由 graph source fingerprint 完整覆盖。
- Graph projection 已补齐节点级 `httpResource` 身份解析：常量 `resourceId` 精确闭包到 Resource snapshot；
  动态 `ctx`/表达式绑定保留为 effect unknown、runtime blocked 的通用 Operator snapshot，不用虚假精确换取可运行假象。
- CapabilitySnapshot 已将 tenant/organization/project/environment/region 作为一等 immutable scope；
  capabilityId 只在完整 scope 内唯一，scope 与 provenance tenant 不一致时拒绝封印。
- HTTP read、受管 external write、未受管 external write、operator ports、条件分支、图级 read/write/mixed/unknown
  effect 与 runtime readiness 已按 fail-closed 语义投影。未知 effect、缺失/未封印/身份不匹配的 child snapshot、
  冲突 error contract 和不明确 state model 均不会被静默放行。
- capability snapshot 采用完整 canonical JSON 内容寻址；source、contract、effect、runtime、dependency、
  ownership、lifecycle、provenance 或时间字段被修改都会导致完整性校验失败。
- Stage 0 第四增量已完成 `CapabilityClosure`、`CapabilityClosureIntegrity` 和
  `BuiltInCapabilityClosureService`。闭包必须具有一个 COMPOSED root、同一完整 scope、所有 exact reachable
  snapshots，且无缺失 child、孤儿、重复引用、同 revision 指纹分叉和递归环；closure 自身也使用 canonical
  fingerprint 封印。Java 与 JSON Schema 同步限制 root 加依赖最多 10001 个快照，图遍历使用显式栈避免深链耗尽 JVM 栈。
- 7 张内置资源图现在直接从 classpath DSL、`GatewayGraphContractCatalog`、当前 operator catalog 和 resource
  registry 生成闭包，不维护第二份手工拓扑。`enrichOrderList` 的 `foreach` 内部资源 invocation 会以稳定结构路径和
  条件依赖进入闭包；raw DSL digest 进入 graph source fingerprint，避免 importer 尚未平铺的 DSL 变化逃逸。
- `aiEnrichedSearch` 的三个 streaming Java operator 会进入闭包，但因 visual runtime 尚不支持该执行模式而保持
  runtime blocked；`resourceDispatch` 的动态资源选择保持 effect unknown/runtime blocked，其余五张静态资源图 ready。
- strict JSON Schema 均存放在 `docs/schemas/resource-gateway-mirror/`，所有协议对象拒绝不完整引用、
  矛盾 effect、伪造统计置信度和无 lineage 的 recorded/inferred provenance。
- Stage 0 第三增量已完成 full-scope H2 append-only repository、lifecycle 状态机和受身份/用途/clearance
  约束的 Integration API。revision gap、内容篡改、非法跃迁、跨 scope 与越级读取均 fail closed；同 fingerprint
  的 exact retry 幂等。
- capability probe 已如实声明 snapshot/closure protocol、projection、7 图 closure projection、API/lifecycle 为可用；
  Stage 0 门禁完成时 MirrorPlan、external-leaf interception 和 mirror serving 均保持为 `false`，后续 Stage 1
  只有在对应受保护 adapter 真实装配后才逐项开启。
- 独立 `resource-gateway-test-kit` 已打包全部 Mirror Schema 和共享 Stage 0 compatibility fixture；
  `CapabilityMirrorCompatibility` 对 capability probe 做前向兼容的协议/对象/feature 协商，
  `CapabilityMirrorVerifier` 可离线复验 Snapshot/Closure 的 strict Schema、canonical fingerprint、完整 scope、
  exact dependency、环和孤儿，无需依赖服务端 Spring 类。服务端测试直接读取同一 fixture，阻止双边预期漂移。
- 通用 `GraphDraftCapabilityClosureService` 已覆盖任意 visual draft：保存态 operator snapshot 原样保留，便携 draft
  从单次 catalog 视图补齐并固定；resource leaf 从权威 registry 投影；PURE 实现节点只进入 graph fingerprint。
  缺 operator、旧 fingerprint、重复 node id、缺 resource descriptor 和没有 exact child closure 的 nested graph
  均以稳定错误码失败关闭。
- 新增受鉴权的 `POST /api/integration/capability-closures/project`。请求不能声明企业 scope、owner、purpose、region
  或 lifecycle；服务端从 workload identity 派生这些字段，强制 `DRAFT`，并拒绝 clearance 以上分类和超出时钟偏差
  的未来时间。probe 已公开请求版本、endpoint 与 `visualCapabilityClosureProjection=true`。
- 3 个画布复杂示例现在各有稳定 graph identity，导出文件名也随 graphName 变化。真实 Chrome 用例逐个加载示例，
  读取浏览器实际导出的 GraphDraft，经鉴权调用 projection API 两次，并验证 root identity、snapshot 数量、完整
  scope 和 fingerprint 确定性。因此 7 个 resource graph 加 3 个 visual examples 的 Stage 0 仓库内工程门禁已闭合。
- Stage 1 第一增量已冻结 `resourceGateway.mirrorPlan.v1` 与 strict JSON Schema。plan 内嵌 exact capability
  closure，逐条绑定 external dependency edge 到唯一 BLOGE invocation site，并固定 resolver precedence、现有
  FixtureBundle revision、logical clock/random seed、scope、purpose、classification、region、lifecycle、预算和
  24 小时以内的 expiry；`executionControlFingerprint` 额外固定 BLOGE runtime binding inventory 与实际
  EffectiveExecutionPlan generation。封印前会拒绝缺/重 external binding、调用点复用、state-model closure 缺失、未知 effect、
  stale/revoked/过期 artifact，以及任何真实 external call、真实凭据或网络出口授权。
- probe 已声明 `mirrorPlanProtocol=true`；MirrorPlan compiler、external-leaf runtime 和 payload-free resolver
  provenance 已形成受保护的 test/staging Plan 服务，profile/property 双栅栏与普通业务 run 的 mirror 控制字段拒绝
  已经落地；sealed plan 与 signed evidence 的 scope-isolated append-only 仓储已经落地。真实 Plan adapter 装配时
  `mirrorPlanCompilation` 与 `mirrorExternalLeafInterception` 为 `true`；第十四增量完成受保护执行/证据链后，
  `mirrorServing` 也只在完整 run adapter 装配时为 `true`。这表示可交付带明确 limitation 的 exploratory evidence，
  不表示部署级 egress attestation 或 certification 已完成。客户环境的数据
  使用授权、跨系统 schema owner、部署/namespace 形态等组织决策仍是生产准入前置，不由仓库测试冒充完成。
- Stage 0 验证基线：前端 Vitest `150/150` 全绿并完成 TypeScript/Vite 生产构建；带 `-Pfrontend` 的真实
  Chrome 示例投影用例 `1/1` 全绿。纳入 Stage 1 compiler 与内部 mirror runtime kernel 后，Resource Gateway
  最新 `clean verify` 为 4529 项测试、0 失败、0 错误、3 条前端 bundle 条件跳过，并成功执行真实浏览器工作流、
  重打可执行 Spring Boot JAR。
- Stage 1 第二增量已实现 `MirrorPlanCompiler`、`MirrorPlanCompilationRequest`、`CompiledMirrorPlan` 和
  `ExecutionControlCompiler.compileMirror` adapter。编译器把每条 direct/nested external capability edge 对账到
  递归冻结的 BLOGE `InvocationInventory`，再复用 FixtureBundle selector、replay、schema check 和 test-double
  runtime；无 owner rule 会生成 implicit deny/`ABSTAINED`，read-only external 也不能逃逸。Graph/fixture drift、
  缺失/歧义 site、runtime 多出 external、REAL/SPY/fallback、非确定性 execution services、越级 classification、
  认证计划 schema waiver 均在调度前以稳定 `RG.MIRROR.*` code 拒绝。第十三增量已将该 compiler kernel 接入
  受保护 Plan API；只有真实 adapter 装配后 `mirrorPlanCompilation` 才为 true。
- ADR-004 已冻结 FixtureBundle 复用边界：不建立第二套 MirrorFixture；public MirrorPlan 只保留 exact fixture ref、
  rule ids、resolver source 和 execution-control fingerprint，业务 fixture/replay payload 只存在于进程内
  `CompiledMirrorPlan`。Stage 1 compiler 聚焦套件当前 `42/42` 全绿；planning/runtime 扩大回归 `163/163`
  全绿。Mirror binding 与 execution control 复用同一个已冻结 InvocationInventory，消除了两次 registry lookup
  之间的并发 binding 漂移窗口。
- Stage 1 第三增量增加 framework-neutral 的 `MirrorRunService` kernel。`CompiledMirrorPlan` 现在自包含 exact Graph、
  FixtureBundle、replay closure 与 execution control，运行时不再回查 mutable registry/repository。准入会重验 plan
  seal、authenticated scope/purpose、TTL、graph/fixture/control generation、external-site 完整覆盖和静态调用预算；
  FixtureBundle 若试图替换内部业务算子会在编译时拒绝。运行仍复用独立短生命周期 BLOGE test engine，external
  read-only operator 的真实调用测试计数为 0，内部算子真实执行，并继承 plan logical timeout 对应的
  `ExecutionBudget`。本增量聚焦回归 `75/75`、planning/runtime package 回归 `160/160` 全绿。
- 第三增量落地时上述 runtime 仍只是内部 kernel；第十四增量已补齐受保护执行/证据 API 和 durable execution
  request coordination，第十六增量已补齐动态 occurrence budget，第十七增量已冻结部署隔离证明协议与离线验真。
  可信分发、部署侧签发和运行时绑定仍是更高认证等级门禁，当前
  evidence 因 `DEPLOYMENT_EGRESS_NOT_ATTESTED` 明确保持 `EXPLORATORY`。
- Stage 1 第四增量已冻结 `resourceGateway.mirrorResolution.v1` Java 线模型与 strict JSON Schema。每个结果绑定
  exact run/plan/capability/site/occurrence/attempt/request fingerprint，并区分 `RESOLVED`、`ABSTAINED`、
  `REJECTED`。协议显式区分 resolved null、可见/脱敏 payload、hash-only 与无输出，分别封印 output 和完整
  resolution 指纹；非拒答结果必须携带 exact artifact provenance、置信区间、新鲜度和限制，普通日志表示不会
  输出 payload 或 error message。协议聚焦测试 `10/10`、mirror 协议包 `69/69` 全绿；第七增量已完成 runtime wiring。
- Stage 1 第五增量把 resolver precedence 固定进 `CompiledExecutionControl`：普通测试继续按 selector specificity，
  mirror 则先按协议 `MirrorSource`、再按 source 内 selector specificity。因而 owner fallback 必然先于更具体的
  governed replay；跨 source 重叠合法，同 source 歧义仍失败关闭。strategy、每个 site 的 resolver order 和空/非空
  mandatory external site 集合都进入 execution-control fingerprint；即使 closure 没有 external edge，也会拒绝
  FixtureBundle 替换内部节点。规划/运行聚焦回归 `77/77`、完整 planning/runtime package `162/162` 全绿。
- Stage 1 第六增量实现 `MirrorResolver` SPI 与 `MirrorResolverChain`。每个 resolver 只拥有一个具体来源，接收
  ephemeral input、规范 request fingerprint 和已匹配候选，但不得保留或记录业务输入；返回规则、置信区间、
  freshness 和显式 limitations。chain 严格执行 control 中冻结的 resolver order，统一产生 `ABSTAINED`，对缺失来源、
  重复来源、普通 control 越界和同来源运行时歧义失败关闭。Stage 1 先提供 OWNER_SPECIFIED 与 GOVERNED_REPLAY 两个
  exact FixtureRule adapter，planning/runtime package `169/169` 全绿。
- Stage 1 第七增量已把 resolver chain 接入 `TestDoubleFactory` 的 mirror 专用分支，普通测试继续走原有 selector
  路径。`MirrorResolutionJournal` 对每个 external occurrence/attempt 立即计算最大 16 MiB 的 canonical request
  fingerprint；成功输出只保留 hash，不复制业务 payload；异常文本不进入 resolution。OWNER_SPECIFIED 绑定 exact
  FixtureBundle，GOVERNED_REPLAY 额外绑定 exact ReplayPayload；显式 THROW/TIMEOUT 作为 resolved business error，
  abstention 与 policy/runtime rejection 分开表达。共享 kernel 生成 runId 后，journal 按 site/correlation/occurrence/
  attempt 排序、去重并封印每条 resolution；journal 只允许完成一次，结果再次校验 exact plan/run 与坐标唯一性。
  resolver chain 可由 `TestDoubleFactory` 显式注入。端到端覆盖 owner output、governed replay、business error、
  abstention、hash-only 输出和 artifact provenance，planning/runtime package `172/172` 全绿。第十四增量已通过
  受保护 API 暴露该内核；第十二增量提供的 terminal bundle durable boundary 被纳入原子提交事务。
- Stage 1 第八增量已冻结 `resourceGateway.mirrorRunEvidence.v1`、
  `resourceGateway.mirrorEvidenceAttestation.v1` 与 `resourceGateway.mirrorEvidenceBundle.v1` Java 协议和 strict
  JSON Schema。bundle 只允许 `HASH_ONLY`：node input/output、edge value、request context 与 resolver output 均只留
  canonical fingerprint；每个 resolution 必须先独立封印，且 run/plan/coordinate 必须一致。证据同时绑定 exact
  capability closure、execution control、FixtureBundle revision、semantic result 和隔离事实。签名域包含 run、plan、
  evidence fingerprint 与 signedAt，新签名会被立即复验，完整 bundle 另有 canonical fingerprint；签名器不可用、
  嵌套 seal 篡改、签名时间/签名篡改、payload 可见性违规、重复坐标与越界集合均失败关闭。密码学来源可信不等于
  可认证：只有 deployment egress 已证明且不存在 limitation 时才能声明 `CERTIFIABLE`。协议/完整性聚焦测试
  `15/15` 全绿。test-kit 独立 verifier、durable store 已在后续增量完成；部署隔离证明的协议和 verifier 已完成，但
  可信分发与运行时 binding 仍是门禁，因此 probe
  不变。
- Stage 1 第九增量已将 evidence vNext 接入真实 `MirrorRunService`。`MirrorRunEvidenceProjector` 对 request context、
  node/attempt input/output 和 edge value 分别执行最大 16 MiB 的 canonical hash，并在签名前证明 external node 的
  每个 delegate attempt 与 `MirrorResolution` 在 site/correlation/occurrence/attempt、capability、graph path、request
  fingerprint 和成功 output fingerprint 上形成 exact closure。projector 不复制 test diagnostics、assertion 或任何
  业务值。`MirrorRunService` 不安装隐式开发密钥：默认无 signer 的执行最终以
  `RG.MIRROR.EVIDENCE_SIGNER_UNAVAILABLE` 拒绝交付；显式 signer 的签名若不能立即自验，则以
  `RG.MIRROR.EVIDENCE_INTEGRITY_REJECTED` 失败关闭。`MirrorRunResult` 现在强制携带与 exact plan/run/resolution/
  semantic result 一致的 verified bundle。当前部署尚无 exact `DEPLOYMENT_ISOLATION_ATTESTATION`，所以运行证据
  诚实降级为 `EXPLORATORY`；布尔 egress 声明不能脱离 exact attestation ref 自证。真实运行、缺 provenance、
  request mismatch、无 signer 和坏签名故障注入套件 `12/12` 全绿。第十四增量已开放受保护服务 API；部署隔离证明协议
  已在第十七增量完成，但尚未接入每次运行；
  mirror integration 加 testing planning/runtime 扩大回归 `259/259` 全绿。
- Stage 1 第十增量已完成不依赖 Spring/服务端类的 `MirrorEvidenceVerifier`。portable evidence 新增 exact
  `externalBindings`，使离线消费者能证明每个已执行 external attempt 恰有一条 resolution，并拒绝漏记、多记、
  capability/graph path 错绑、request/output hash 错绑和 payload 回流。verifier 按严格 Schema、数组规范顺序、
  nested resolution seal、evidence fingerprint、bundle fingerprint、签名时间、key state/algorithm 和域分离 Ed25519
  签名逐层失败关闭；结果只暴露 reason code、id 与 fingerprint。服务端与 test-kit 共同消费固定
  `mirror-evidence-stage1-v1.fixture.json`，分别通过 Java model 与独立 JSON verifier 复验同一签名，阻止双边预期漂移。
  test-kit 聚焦回归 `12/12`、服务端 evidence/runtime 聚焦回归 `28/28` 全绿。当前独立消费者认证范围仅为 Java；
  非 Java 客户端必须先通过固定 fixture，且在 RFC 8785 或等价的语言中立数字 canonicalization profile 冻结前，
  不得宣称跨语言生产兼容。
- Stage 1 第十一增量建立 `MirrorRuntimeConfiguration` 隔离装配根。只有显式
  `gateway.testing.mirror.enabled=true` 且 profile 为 `test` 或 `staging` 时才装配 `MirrorPlanCompiler` 与
  `MirrorRunService`；只要 `production` 出现在 active profiles 中，即使同时激活 `test` 也物理排除全部 mirror
  bean。装配测试同时证明独立引擎没有生产 interceptor、context carrier、extension listener 和 durable store。
  普通业务 run 的 DTO 前置 guard 新增 mirror、replay、replacement、resolver override 与 scenario pack 字段族，
  嵌套注入会在 controller 前拒绝并提交安全审计，审计不可用时继续失败关闭。聚焦回归 `13/13` 全绿。
  受保护 Plan/Run/Evidence endpoint 均归属同一隔离装配根，应用级测试证明 production 与
  `production,test` 下全部 mirror route 物理不存在；availability marker 只有完整服务链装配后才开启对应 probe。
- Stage 1 第十二增量建立 `MirrorPlanRepository` 与 `MirrorEvidenceRepository` 两个内容寻址、append-only durable
  boundary。`mirror_plans` 与 `mirror_run_evidence` 都把 tenant/organization/project/environment/region 放入复合
  主键；同一 scope 下相同 planId/runId 只允许相同 fingerprint 的幂等重试。写入与读取均重算 plan seal 或复验
  nested resolution、bundle fingerprint 与 detached signature，并交叉核对数据库索引列和 JSON 内身份；行被篡改、
  搬移到另一 scope、验证 key 不可用或同 ID 内容冲突时全部失败关闭。表中没有 fixture/replay/context/result payload
  列，存储对象也只允许 public `MirrorPlan` 与 `HASH_ONLY` evidence，业务 FixtureBundle 和 replay payload 仍只存在于
  短生命周期执行闭包。仓库 bean 与 compiler/runtime 共用同一个 profile/property 隔离根和 evidence integrity
  boundary，在 production 或关闭开关时物理不存在。持久化、重启、完整 scope 隔离、幂等冲突、索引/JSON 篡改、
  未知验证 key 和列级 payload omission 聚焦回归 `10/10`，连同运行/装配回归共 `25/25` 全绿。
- Stage 1 第十三增量开放首个受保护服务面：`POST /api/mirror/plans` 与
  `GET /api/mirror/plans/{planId}`。公开 `resourceGateway.mirrorPlanCreateRequest.v1` 只允许提交 planId、已审阅
  graph fingerprint、sealed CapabilityClosure、exact FixtureBundle ref、受限预算和 expiresAt；purpose、scope、
  clearance、region、lifecycle、真实调用、外部凭证和网络策略全部由服务端生成。应用服务在编译前重新计算当前
  Graph artifact fingerprint、独立复验 fixture 存储 envelope、冻结 governed replay closure，并要求
  `MIRROR_REHEARSAL`、完整 tenant/org/project/environment/region 与 test/staging 环境。mirror replay 新路径只能
  服务编译期闭包解析，不能借此 capture 或直接读取 payload。
  租户内 fixture registry 原先只有 tenant/environment scope，因此本增量没有用文档掩盖这个隔离缺口，而是增加
  payload-free、append-only `mirror_fixture_scope_bindings`。fixture 注册成功时服务端自动绑定
  organization/project/region；mirror 编译先验证该 exact binding 再读取 fixture。历史 fixture 未绑定时失败关闭，
  以相同内容重试注册即可补写；知道或猜到 fixture id/revision/fingerprint 本身不能跨项目获得 mirror 使用权。
  scope binding 的重启持久化、完整 scope 隔离、幂等冲突、索引篡改和列级 payload omission 已覆盖。
  相同请求复用原 `compiledAt` 得到相同 plan seal；同
  planId 的任何制品、预算、认证或有效期漂移都冲突。Controller、service 与 capability marker 受同一个
  `!production & (test | staging)` 加显式开关约束；production 与 `production,test` 下 HTTP mapping 物理不存在。
  Probe 只有在真实 adapter 装配后才报告 `mirrorPlanCompilation=true` 和
  `mirrorExternalLeafInterception=true`。服务、replay 权限隔离、路由隔离与装配聚焦回归及 fixture scope 根治
  聚焦门禁全绿；执行/evidence HTTP 与 request lease 已由第十四增量闭合，deployment egress attestation 的可信分发与
  runtime binding 仍是下一门禁。
- Stage 1 第十四增量开放 `POST /api/mirror/executions`、`GET /api/mirror/runs/{runId}` 与
  `GET /api/mirror/runs/{runId}/evidence`，冻结 `resourceGateway.mirrorExecutionRequest.v1` 和
  `resourceGateway.mirrorRunSummary.v1`。请求只能提交 stable requestId、planId、expected plan fingerprint 与业务
  context；scope/purpose/policy/fixture/replay 均由认证身份和 sealed plan 决定。服务端拒绝 caller-supplied
  `bloge.tenantId`、`bloge.namespace` 与 `__nodeOutput:` 内部状态键，再注入 authenticated tenant/project，最终
  effective context 在执行前按与 evidence projector 相同的 16 MiB 规范计算 fingerprint。
  public plan 每次运行前从 root capability 的 exact Graph source、full-scope fixture binding 和 governed replay
  authority 重建 `CompiledMirrorPlan`；只有完整重编译 plan 与存储 plan 逐字段相等才调度。`mirror_run_requests`
  只保存 scope、request/context/plan fingerprints、lease owner/epoch/expiry、terminal run/evidence fingerprint，
  没有 JSON/CLOB 或业务 payload 列。并发 retry 返回 retryable `409`；lease 过期后 epoch+1 接管。claim、busy
  retry delay、release、takeover 与 terminal commit 都以协调数据库时钟为唯一租约时间权威，副本墙钟快慢不能提前
  夺权或延迟恢复；terminal commit 同时校验 owner、epoch、原 expiry 与 database coordination time，因此即使尚未
  发生接管，过期 authority 也不能发布；行锁必须先于数据库时间采样，且 H2 的采样使用独立短连接，避免事务级
  `CURRENT_TIMESTAMP` 冻结让 worker 携带陈旧时间越过边界；
  失败主动释放，进程崩溃依靠有界 expiry 恢复；成功时 signed evidence 插入与 request terminal transition 位于
  同一事务，过期、释放或被接管的 lease 都会回滚 evidence，避免孤儿。完成态 retry 直接读取并交叉验证
  stored evidence，不重复执行。跨 scope plan/run/evidence 均返回 404。test-kit 同步打包 request/summary schema；
  profile 路由、真实 Spring Boot 装配、并发首抢、重启、到期拒绝、接管、旧 epoch、原子回滚、payload omission、原始
  请求 duplicate-key/类型/空白严格解码、
  exact rehydration 与 API service 测试均已覆盖。完整链装配时 probe 报告 `mirrorServing=true`；因部署 egress 尚无
  exact attestation，输出仍为 `EXPLORATORY` 而非 `CERTIFIABLE`。本增量的 Mirror 聚焦回归 `122/122` 全绿。
- Stage 1 第十五增量闭合受保护 Mirror 操作的最小可运营观测协议。`PLAN_CREATE`、`PLAN_READ`、
  `RUN_CREATE`、`RUN_READ`、`EVIDENCE_READ` 每次只允许提交一个 terminal observation；成功、调用方拒绝和服务失败
  分开计数，失败再归入 `INVALID_REQUEST`、`FORBIDDEN`、`NOT_FOUND`、`CONFLICT`、`EXPIRED`、`CAPACITY`、
  `UNAVAILABLE`、`AUDIT_UNAVAILABLE`、`UNEXPECTED` 九个封闭原因。Micrometer 预注册 operation/outcome/reason
  枚举组合，总计 75 条固定基数 series；tenant、scope、actor、correlation、request、plan、run、异常或业务值在类型上
  都不能成为 tag。`mirror_operation_audit` 只保存数据库 sequence/time、完整 scope、trace/actor 坐标、封闭结果、
  stable `RG.MIRROR.*` code、request/plan/run id 和 duration；表结构没有 context、fixture、replay、node/edge value、
  exception message 或 stack 列。Plan 创建与 Run evidence/request completion 的成功审计加入同一业务事务，审计失败
  会回滚未发布结果；业务失败审计通过 `REQUIRES_NEW` 独立提交，因而不会随被解释的外层失败一起消失。审计事实
  无法构造或持久化时统一以脱敏、可重试的 `503 RG.MIRROR.OPERATION_AUDIT_UNAVAILABLE` 失败关闭；指标仅为旁路
  信号，绝不降级这一规则。完整隔离链装配后 probe 新增 `mirrorOperationObservability=true`。数据库重启/scope
  隔离/payload omission、固定标签白名单、异常归类、single-completion、成功原子回滚、失败审计跨回滚存活、audit
  outage 回滚 evidence/request 的专项回归当前 `15/15` 全绿；纳入既有服务/能力探针的本增量精准回归 `62/62`
  全绿，完整 Mirror 聚焦回归 `137/137` 全绿。部署侧 audit 分区/归档/保留期、磁盘容量演练、受限导出、dashboard 和 page route 仍是客户生产准入项，
  feature 为 true 不冒充这些环境控制已完成。
- Stage 1 第十六增量把 plan 中既有 `maximumInvocations` 从静态提示升级为真实的双层运行安全协议。编译器先对
  递归冻结的 `InvocationInventory` 执行静态下限证明，预算连结构节点都容纳不下时以
  `RG.MIRROR.INVOCATION_BUDGET_TOO_SMALL` 拒绝封印。运行时为每个 run 创建独立的
  `MirrorInvocationBudget`，并在 BLOGE 继承到 root/nested/foreach/loop/streaming/compensation 的
  `ExecutionOperatorResolver` 中用 CAS 逐次扣减；检查位于 exact inventory 对账之后、fixture binding 和 operator
  execution 之前，因此并发展开不能超卖，触顶 occurrence 也不能产生外部副作用。retry 保持为已准入 occurrence
  内的 attempt，不重复占用 occurrence。触顶后已准入工作可收尾，新工作以非重试控制错误
  `RG.MIRROR.INVOCATION_BUDGET_EXHAUSTED` 终止；签名 evidence 返回 `EXECUTION_FAILED` 并携带稳定
  `INVOCATION_BUDGET_EXHAUSTED` limitation。shared-kernel metadata 只记录 maximum/admitted/rejected 三个计数，
  不保留 site、correlation 或业务值；projector 在签名前交叉验证该快照与 sealed plan。旧 projector 入口看到预算
  metadata 却未收到本次 run 的真实快照时同样失败关闭，不能通过兼容 API 降级绕过对账。静态拒绝、精确边界、64 路
  并发不超卖、非法快照、真实五项 foreach 在预算 3 下仅准入 root + 两个 child、retry 两次 attempt 只扣一个
  occurrence、external 调用保持 0、快照篡改失败和 evidence 复验的本增量新增场景 `6/6` 全绿；完整 Mirror
  聚焦回归 `143/143`、Mirror 加共享 kernel 扩大回归 `181/181` 全绿，并纳入 `4520/4520` 全量门禁。
- Stage 1 第十七增量把 `deploymentIsolationRef` 背后的外部证明冻结为
  `resourceGateway.mirrorDeploymentIsolationAttestation.v1`。短生命周期 material 绑定 exact deployment scope、cluster、
  namespace、workload、service account、immutable image digest、独立 enforcement layers、六项 mandatory fail-closed
  deny fact、network/credential/allowlist fingerprint、非业务 egress class 与 payload-free policy proof refs；proof ref
  以 `(kind, id, revision)` 唯一，同坐标不同 fingerprint 会失败关闭；有效期最多
  15 分钟，观察到签发最多 5 分钟，执行必须完整落在 `[max(validFrom, signedAt), expiresAt)`。
  `MirrorDeploymentIsolationAttestationIntegrity` 使用独立 SRE/security authority key 校验双层 canonical fingerprint、
  key/issuer/algorithm/lifecycle/signing window、local immutable deployment identity 和 Ed25519 signature；evidence signer
  不能冒充隔离权威。strict Schema、固定签名 fixture 和无 Spring/server 依赖的 test-kit verifier 同步落地，服务端
  producer/schema/compatibility 场景 `9/9`、test-kit identity drift、policy tamper、wrong/revoked key、window、unknown field
  与 detached-copy 场景 `6/6` 全绿。该增量完成时仍没有 attestation repository/API、authority key-set 可信发布、部署
  agent 刷新、原子缓存或 `MirrorRunEvidenceProjector` 绑定，所以这一步证明的是协议可验，不是部署已经隔离；run 仍必须保留
  `DEPLOYMENT_EGRESS_NOT_ATTESTED` 和 `EXPLORATORY`。完整 Mirror 回归 `152/152`，独立 test-kit `clean verify`
  `260/260`，Resource Gateway 全量门禁 `4529/4529`（另有 3 条条件跳过），真实 Chrome 与可执行 JAR 构建均通过。
- Stage 1 第十八增量补上隔离证明 authority key 不应靠静态 `Map` 或调用方上传来获得信任的根问题，冻结
  `resourceGateway.mirrorDeploymentIsolationAuthorityKeySetPublication.v1`。每个短时 publication 同时绑定完整
  tenant/organization/project/environment/region scope、exact deployment generation、attestation issuer、稳定
  `keySetId`、bootstrap root trust domain、local-required M-of-N threshold、policy fingerprint、最多 24 小时有效窗、
  单调 generation 与 predecessor fingerprint；authority keys 和 root signatures 都要求 canonical order、唯一坐标和
  canonical Base64。至少一把 `ACTIVE` attestation key 必须覆盖整个 publication window，根签名必须来自不同
  authority 和不同密码学根公钥、完整落在 issuance-to-activation window，且所有携带的签名都必须是已 pin、未吊销、
  签发时有效并通过 Ed25519 验证，不能用“阈值已够”为理由忽略一个未知或坏签名。服务端
  `MirrorDeploymentIsolationAuthorityKeySetIntegrity` 和无 Spring/server 依赖的 test-kit verifier 都要求 exact local
  binding 与 trusted floor，拒绝 threshold downgrade、scope/deployment/issuer/trust-domain drift、bootstrap 非第一代、
  generation rollback/fork/gap、predecessor mismatch 和过期 publication；只有全部通过才暴露可交给既有 attestation
  verifier 的公钥。strict Schema 与 public-only 双 root 固定 fixture 同步落地，focused producer/schema `10/10`、
  test-kit verifier/packaging `13/13`、完整 Mirror `162/162`、独立 test-kit `clean verify` `269/269`、Resource Gateway
  全量 `4539` tests（0 failures、0 errors、3 条条件跳过）全绿，真实 Chrome 与可执行 JAR 同时通过。这个增量建立的是
  可验证发行格式和纯函数验真内核；HTTPS/mTLS refresh、
  full-scope append-only repository/API、durable floor CAS、部署 agent 原子缓存、attestation ingest 与 run admission/evidence
  commit binding 仍未实现，所以 capability 和 trust class 继续失败关闭。
- Stage 1 第十九增量把 authority key-set 从“可验文件”推进成 full-scope trusted distribution control plane。
  `MirrorDeploymentIsolationAuthorityTrustPolicyProvider` 强制 expected binding、accepted policy、M-of-N threshold 与
  bootstrap roots 来自 operator-owned 本地信任源；默认 provider 不可用，HTTP 请求不能上传或选择 trust roots。
  `mirror_isolation_authority_publications` 只追加 canonical publication，
  `mirror_isolation_authority_trusted_floors` 钉死完整 enterprise scope、immutable deployment 和当前
  `(generation, publicationFingerprint)`；正文 insert、`SELECT ... FOR UPDATE` 后的 floor CAS 与成功 audit 在同一事务
  提交。exact retry 幂等，bootstrap 非第一代、rollback、fork、gap、predecessor mismatch、deployment drift、损坏的
  index/JSON/head 和跨 scope 读取全部失败关闭；双副本竞争同一 successor 只允许一个胜出。三条受保护 API 分别用
  `MIRROR_TRUST_ADMIN` 和 `MIRROR_TRUST_DISTRIBUTION`/`MIRROR_REHEARSAL` 鉴权，POST 在密码学工作前拒绝 duplicate
  key、unknown field、2 MiB/32-depth/10000-node 越界；GET 每次重新解析本地 policy、复验签名/有效期，且 exact
  generation 只有仍等于 current floor 才返回，不能成为历史降级入口。probe 分开报告 protocol、API assembly 与
  trust-provider readiness；新增 23 个场景，authority 联合回归 `66/66` 全绿。七个 authority 公共类型经
  `javadoc --release 25 -Werror -Xdoclint:all` 校验为 0 warnings、0 errors；Resource Gateway 全量门禁
  `4565/4565`（另有 3 条条件跳过）、独立 test-kit `269/269` 全绿，真实 Chrome、普通 JAR、可执行 Boot JAR
  和 test-kit 双 JAR/schema 打包均通过。该增量仍不宣称 deployment isolation ready：
  agent mTLS/HTTPS refresh 与 atomic cache、attestation repository/API、revocation propagation、execution admission 和
  evidence commit 同代绑定尚未完成，运行继续保持 `EXPLORATORY`。操作与接线见
  [trusted distribution guide](resource-gateway-mirror-authority-trusted-distribution.md)。
- Stage 1 第二十增量把短时 isolation attestation 从“可离线验真的文件”推进为 full-scope trust-control plane。
  `MirrorDeploymentIsolationAttestationAdmissionPolicyProvider` 由 operator-owned 本地快照钉住每条 stream 的 exact
  bootstrap revision，补上 v1 attestation 没有 predecessor fingerprint 时空库无法区分真实 head 与窗口内旧版本的
  根缺口；默认 provider 不可用，请求不能选择首次 floor。`mirror_isolation_attestations` 只追加外部签名正文和 exact
  authority generation/fingerprint，`mirror_isolation_attestation_statuses` 只追加本地 content-addressed `ACTIVE` 或
  `REVOKED` 状态，`mirror_isolation_attestation_heads` 保存完整 scope、immutable deployment、authority、attestation 与
  status current floor。ingest 将正文、初始 ACTIVE、head CAS 和成功 audit 放在同一事务；后续只接受 floor + 1，拒绝
  rollback、same-revision fork、gap、跨 scope/content-address reuse 和损坏索引。revocation 只能从 status revision 1
  单向追加 revision 2，精确重试幂等，同一外部 revision 永不 re-activate；必须签发下一连续 attestation revision 才能恢复
  ACTIVE。GET 始终返回一个重新计算 fingerprint 的 atomic bundle，exact 地址仅在仍等于 current head 时可读。ACTIVE
  每次绑定并复验同一 current authority publication/key/deployment/time window；REVOKED 则可在 authority outage、换代或
  attestation 过期后继续分发，避免正向信任依赖阻断安全否决。三类新 strict Schema 和 test-kit packaging 同步落地，
  duplicate/unknown/size/depth/node bounds 在服务前失败。核心 repository/service/controller/transaction 场景 `22/22`、
  路由/能力探针/真实 Spring 扩大回归 `58/58`、test-kit schema packaging `6/6` 全绿。该增量仍不提升运行 trust class：
  deployment agent mTLS/HTTPS atomic cache、execution admission 与 evidence commit 同代双重校验、跨语言固定 fixture 和
  环境 certification 尚未闭合。接线、错误语义和演练清单见
  [attestation control-plane guide](resource-gateway-mirror-attestation-control-plane.md)。
- Stage 1 第二十一增量补上 deployment-side trust distribution。`HttpMirrorDeploymentIsolationTrustSource`
  只接受 private PKIX trust store、server SPKI pin、mutual TLS 和 client/server exact X.509 workload identity
  同时成立的 `ControlPlaneHttpTransport`，不允许 system-root-only、未 pin、单向 TLS 或 identity-unbound 降级；GET
  还要求 exact vendor media、`X-BLOGE-Mirror-Trust-Protocol`、动态逐请求 workload authorization、严格 envelope
  字段集和 payload fingerprint。`MirrorDeploymentIsolationTrustAgent` 不做 TOFU：空 cache 的 authority generation /
  fingerprint、attestation revision/fingerprint、status revision/fingerprint 必须全部等于 operator 通过独立安全通道
  预置的 floor；后续 authority 走 signed predecessor chain，attestation/status 只接受 same-exact 或连续 successor，
  同 revision 被撤销后永不 re-activate。ACTIVE 刷新同时复验 current authority 与 attestation；REVOKED 则绕过正向
  authority 可用性先落 denial，避免过期 key/source outage 阻断吊销。`AtomicFileMirrorDeploymentIsolationAgentCache`
  以 expected fingerprint + 连续 local generation 做单 writer CAS，在同目录写入并 fsync 临时文件、要求 atomic rename、
  fsync 目录并读回复验；不支持 atomic move、symlink、损坏/截断/unknown/oversize 文件全部 fail closed。旧 ACTIVE 只在
  `maximumSnapshotAge` 内降级可用，形成 missed-revocation 的硬上界；REVOKED 过期仍是 denial。真实 private-CA/SPKI/
  mTLS/SPIFFE 双端身份、协议降级、rollback/fork/gap、revocation-with-authority-outage、same-revision reactivation、硬过期、
  原子 cache/CAS/corruption 与协议 schema 场景聚焦回归 `50/50`，test-kit schema packaging `6/6` 全绿；
  Resource Gateway 干净全量门禁 `4608/4608`（另有 3 条条件跳过）通过，真实 Chrome 与可执行 Boot JAR 同时验证。
  运行时 admission/evidence commit 尚未 pin 同一 cache generation，trust class 仍保持 `EXPLORATORY`。接线与 SLO/runbook 见
  [deployment-agent guide](resource-gateway-mirror-deployment-agent.md)。

---

## 1. 结论与战略假设

事业部接口是所有集成方都能获得的公共品，单纯接入 API 不构成长期壁垒。真正能够持续复利的是：

1. **数据壁垒**：经数据权利确认、脱敏、真实 outcome 校准，并能完整回放的业务行为语料。
2. **认知壁垒**：资源、能力、状态变化、失败模式和业务处置之间透明、可验证的依赖模型。
3. **运营壁垒**：业务 owner 对能力行为、状态流转和正确处置的指定精度，以及持续消化漂移债务的能力。

这三者必须形成闭环，而不是三个独立功能：

```text
真实行为与结果
  -> 受治理语料
  -> 能力镜像
  -> 场景演练与回归
  -> 可验证证据
  -> 真实结果校准
  -> 契约、语料和判据更新
```

单个资产的 contract test 是发布底线；域级拟合保真度是长期竞争力水位。前者回答“这次能否发布”，
后者回答“我们是否越来越理解并能正确模拟客户业务”。

### 1.1 可证伪假设

方案必须通过客户业务域验证，不能只靠架构推理自证：

| 假设 | 观测指标 | 失败判据 |
|---|---|---|
| 真实行为语料能显著降低 fixture 建模成本 | 每个可执行 case 的人工分钟数 | 两个季度后没有显著下降 |
| 状态镜像能发现普通 API mock 无法发现的问题 | 状态一致性缺陷发现数、线上逃逸率 | 没有新增缺陷发现且运行成本显著增加 |
| 业务人员可以参与确认而无需理解 DSL | 候选确认率、微调完成率、撤销率 | 大多数任务仍由研发代操作 |
| 保真度与服务结果存在正相关 | 保真度分量与权威 outcome 的时序相关 | 长期无相关或只与代理指标相关 |
| contract mock 能提升效率且不损伤正确性 | 展开运行差异率、节省时长、撤销率 | 差异债务持续扩大或无法按时回收 |

## 2. 范围、术语与不可破坏的不变量

### 2.1 统一术语

| 术语 | 定义 |
|---|---|
| Resource | 一个外部系统端点或资源描述，当前由 `ResourceDescriptor` 表达 |
| Operator | BLOGE DAG 的最小执行单元，可包装 Resource、函数或业务实现 |
| external capability | 以外部系统为事实来源、运行行为不受本平台控制的原子能力 |
| composed capability | 由 DAG 组合其他能力形成的工具；默认真实执行其编排逻辑 |
| CapabilitySnapshot | 对 Resource、Operator 或 Graph 的不可变、版本化跨系统投影，不是新的主数据源 |
| CapabilityClosure | 一个 COMPOSED root 及其所有 exact reachable CapabilitySnapshot 的内容寻址闭包，是 MirrorPlan 的无注册表输入 |
| Mirror | 在不触达真实副作用的情况下，对能力可观察行为和状态变化的受治理模拟 |
| Fidelity | 镜像与目标业务行为在明确覆盖范围内的多维相似度和可信度 |
| Rehearsal | 在一个隔离、确定性的镜像世界中执行场景、故障和 what-if |
| Outcome | 来自独立业务结果源的权威或代理结果，不能由被测执行历史自证 |

### 2.2 不变量

1. 生产请求不能携带 fixture、mirror、replay 或替换规则。
2. 镜像运行时在身份、凭据和网络层面不具备真实外部写能力。
3. external 默认在镜像边界替换；composed 默认真实执行，只有受治理授权的稳定子工具可 contract mock。
4. 任何 serving 结果都必须声明来源、适用范围、置信度、契约版本和降级状态。
5. 写能力没有已批准 `WriteEffectSpec` 或缺少历史基线时必须拒绝，不能合成写结果冒充成功。
6. 日志只证明“发生过”，不能单独证明“正确”；自动归纳只能生成候选。
7. 语料、规格、场景、授权和证据均不可变修订、内容寻址并保留 lineage。
8. control plane 不持有原始业务 payload；payload 只驻留在经授权的数据面或 TEE vault。
9. 要求状态一致性的场景在 state store 不可用时必须失败关闭，不能静默退化为无状态运行。
10. 低保真结果不能伪装成高保真结果，未知必须可表达为 `ABSTAINED`。
11. Capability 的命名、仓储和查询必须使用 tenant/organization/project/environment 完整 scope，不能退化为
    tenant-wide id；region 作为驻留与执行约束被指纹覆盖。

## 3. 当前基础与理想态差距

Resource Gateway 已有的工业底座应直接复用：

- `ResourceDescriptor`、`ResponseProtocol` 和 `HttpResourceOperator` 已形成 external resource 基础。
- `GraphDraft`、Operator Library、Graph Contract 和 dependency snapshot 已形成 composed graph 与集成契约基础。
- `FixtureBundle`、`FixtureRule` 已支持输入匹配、调用位置、次数、重试、RETURN、THROW、TIMEOUT、REPLAY、SPY 和 DENY。
- operator micro-graph、graph suite、boundary/property/mutation test 已形成多层测试入口。
- `TestRunEvidence` 已包含 node/edge trace、fixture consumption、assertion、语义结果指纹和证据等级。
- payload replay vault 已包含分类、脱敏、保留期、权限、完整性和删除生命周期。
- durable execution、签名、租约、恢复、生产 profile 隔离和 ANEKE workbook/gate 已形成企业基础设施。

成熟度评估不是按代码量，而是按能否闭合业务镜像复利回路：

| 能力域 | 当前成熟度 | 主要缺口 |
|---|---:|---|
| Resource/Graph/Schema | 95% | Capability/Effect/Closure、7 张内置图和 3 张画布示例投影、生命周期仓储、scope-bound closure API、共享 compatibility fixture 与独立离线 verifier 已落地；nested graph exact child closure 进入 Stage 1 MirrorPlan |
| 确定性测试控制 | 80% | 缺镜像来源、匹配可信度和领域状态控制 |
| Evidence/Replay | 75% | 缺 mirror provenance、state trace、fidelity observation 和 outcome lineage |
| 递归 DAG 测试 | 65% | 缺统一镜像编译计划和 contract-mock 展开治理 |
| 日志蒸馏与语料 | 10% | 缺标准事件、准入、分层匹配、漂移和偏差控制 |
| 有状态业务世界 | 5% | 执行 checkpoint 不等于业务实体与事务状态模型 |
| Scenario/Rehearsal | 10% | 缺场景、写效果、处置断言和状态演练协议 |
| Fidelity/Outcome | 5% | 缺保真向量、shadow、权威结果归因和校准闭环 |
| 业务运营工作台 | 10% | Author Canvas 尚未成为案例驱动的镜像运营工作台 |

结论：基础设施准备度约 65%，镜像复利闭环完成度约 10%–15%，完整理想态完成度约 30%。

## 4. 目标架构与系统责任

![Resource Gateway 业务能力镜像目标架构](assets/resource-gateway-capability-mirror-target-architecture.svg)

图源：[`docs/assets/drawio/resource-gateway-capability-mirror-target-architecture.drawio`](assets/drawio/resource-gateway-capability-mirror-target-architecture.drawio)

### 4.1 系统责任边界

| 系统 | 拥有 | 不拥有 |
|---|---|---|
| Resource Gateway | 画布、GraphDraft、Capability projection、MirrorPlan 编译、DAG 执行、external leaf 拦截、session 编排、trace/evidence 导出 | 组织级 registry、最终发布裁决、原始生产 payload |
| ANEKE | 契约与资产治理、场景/断言/write effect 注册、owner 审批、workbook、验证债务、发布门禁、保真策略 | DAG 执行内核、原始 payload、外部真实调用 |
| TEE/数据面 | 日志接入、脱敏、payload vault、镜像 resolver、session state、shadow、outcome 连接器 | 资产治理裁决、画布主数据 |
| 客户外部系统 | API、事件、权威业务结果 | 镜像内部状态与测试控制 |

### 4.2 “脑、手、脚”的工程化解释

- **脑**：ANEKE 保存元数据、版本、审批、策略、债务和发布裁决。
- **手**：Resource Gateway 编译并执行 DAG，产出可审计证据。
- **脚**：TEE/数据面接触业务 payload、语料、shadow 和会话状态。

“control plane 不持有 payload”不等于“系统完全不存 payload”。payload 必须驻留在受治理的数据面，
并通过 opaque reference、fingerprint 和有时限的执行授权被引用。

## 5. 统一能力协议

### 5.1 Capability 是投影协议，不是大一统主模型

`ResourceDescriptor`、Operator Library 和 `GraphDraft` 继续由各自 registry 管理。发布或镜像编译时，
系统生成不可变 `CapabilitySnapshot`：

```ts
type CapabilityKind = "EXTERNAL" | "COMPOSED"

interface CapabilitySnapshot {
  schemaVersion: "resourceGateway.capabilitySnapshot.v1"
  capabilityId: string
  revision: number
  fingerprint: string
  kind: CapabilityKind
  scope: {
    tenantId: string
    organizationId: string
    projectId: string
    environmentId: string
    region: string
  }
  source: {
    sourceKind: "RESOURCE" | "OPERATOR" | "GRAPH"
    sourceRef: string
    sourceFingerprint: string
  }
  contract: CapabilityContract
  runtime: RuntimeBindingSnapshot
  dependencies: CapabilityDependency[]
  ownership: Ownership
  lifecycle: "DRAFT" | "REVIEWED" | "ACTIVE" | "DEPRECATED" | "REVOKED"
  createdAt: Instant
}
```

### 5.2 CapabilityContract

```ts
interface CapabilityContract {
  inputSchema: JsonSchemaRef
  outputSchema: JsonSchemaRef
  errorModel: ErrorContract[]
  effect: EffectContract
  determinism: "DETERMINISTIC" | "CONTROLLED_NONDETERMINISTIC" | "NONDETERMINISTIC"
  idempotency: IdempotencyContract
  stateModelRef?: RevisionRef
  compatibility: CompatibilityPolicy
  security: SecurityContract
  slo: SloContract
}
```

### 5.3 当前 projection 规则（已实现）

| 权威资产 | Capability 边界 | effect 与 runtime 规则 | 失败关闭条件 |
|---|---|---|---|
| `ResourceDescriptor` | 每个 resource 投为 `EXTERNAL` | GET/HEAD/OPTIONS 为 read-only；其他方法保留 external mutation；只有 conformant managed-write 才 runtime ready | unsafe method 缺 managed-write 时 runtime blocked，effect 仍为 CRITICAL write |
| `OperatorDefinition` | external effect、resource-backed、remote/AI/event/webhook 或 nested graph source | ports 组合成形式化 object schema；UNKNOWN effect 即使 executor 可运行也 runtime blocked | PURE 内部 operator 不创建独立 capability；外部身份却声明 PURE 时投为 UNKNOWN |
| `GraphDraft` | graph 投为 `COMPOSED`，dependency 只包含 external/nested child | child effect、error、determinism、security 和 readiness 保守汇总；route condition 投入 conditional effect | 缺 operator snapshot、fingerprint 漂移、child 缺失/未封印/歧义、错误契约冲突、多个 state model、virtual/external 双写世界均拒绝 |

Graph source fingerprint 与现有 Tool Studio draft fingerprint 口径一致：排除非语义 `nodeFixtures`，保留节点、边、
binding、schema、operator snapshot 和内部 PURE 节点变化。也就是说，治理依赖图不会被内部实现节点撑爆，但内部逻辑
变化仍必然推动 graph source fingerprint 和 capability snapshot fingerprint 变化。

### 5.4 EffectContract

composed capability 不是天然纯函数。其 effect 是所有可达依赖的保守汇总：

```ts
interface EffectContract {
  mode: "READ_ONLY" | "VIRTUAL_MUTATION" | "EXTERNAL_MUTATION" | "MIXED"
  readSet: EntityOrResourcePattern[]
  writeSet: EntityOrResourcePattern[]
  conditionalEffects: ConditionalEffect[]
  compensationRef?: RevisionRef
  requiresApproval: boolean
  riskLevel: "LOW" | "MEDIUM" | "HIGH" | "CRITICAL"
  derivation: "DECLARED" | "STATIC_ANALYSIS" | "TRANSITIVE_SUMMARY"
}
```

编译器必须检查递归依赖环、版本漂移、未解析依赖、effect 汇总不完整和高风险声明冲突。任何未知 effect
按最危险语义处理，不允许从 `UNKNOWN` 推断为只读。

### 5.5 递归组合规则

1. 每条依赖必须固定 capability revision 和 fingerprint。
2. 编译时展开完整 invocation inventory，并检测静态环。
3. foreach/重试产生 occurrence 和 attempt 坐标，不改变能力依赖身份。
4. composed capability 的 input/output contract 由显式 graph contract 决定，不能仅从末节点猜测。
5. transitive effect、secret、risk、SLA 和 owner 必须汇总并保留来源。
6. contract mock 只替换完整的子 capability 边界，不允许同时替换其内部部分节点。

## 6. 信任跃迁与资产生命周期

### 6.1 信任阶梯

任何自动采集或归纳产物都必须经过以下阶梯：

| 等级 | 含义 | 可用范围 |
|---|---|---|
| OBSERVED | 原始事件已接收，尚未完成数据治理 | 仅隔离审计 |
| SANITIZED | 已脱敏、分类、租户与目的校验 | 分析与候选生成 |
| ADMITTED | 完整性、schema、来源和时间窗合格 | corpus 匹配候选 |
| CANDIDATE | 已归纳 contract/scenario/assertion/write effect | 工作台预览，不参与 serving |
| APPROVED | owner 已确认并签署 revision | rehearsal serving |
| CERTIFIED | 通过独立 suite、shadow 和门禁 | 可用于 certifiable evidence |
| STALE | 超过新鲜度或发现 drift | 仅探索性运行 |
| REVOKED | 数据权利、正确性或安全失效 | 不可执行，只保留审计 tombstone |

### 6.2 通用 provenance

```ts
interface ArtifactProvenance {
  sourceType: "OWNER" | "RECORDED" | "INFERRED" | "SYNTHESIZED"
  sourceRefs: RevisionRef[]
  tenantId: string
  purpose: string
  sampleWindow?: { from: Instant; to: Instant }
  sampleCount?: number
  confidence?: Confidence
  biasRisks: string[]
  approvedBy?: string
  approvedAt?: Instant
  expiresAt?: Instant
  revocationRef?: string
}
```

所有契约、语料、场景、断言、write effect、contract-mock grant 和 fidelity observation 都复用该结构。

### 6.3 生命周期不变量

- revision 创建后不可修改，只能创建新 revision。
- serving plan 必须固定全部依赖 revision 和 fingerprint。
- stale 不自动等于 revoked，但不能生成发布门禁认可的证据。
- revoke 必须使新执行立即失败关闭；历史证据保持可验证并标记“当时信任状态”。
- 删除 payload 不删除证据；证据保留 payload fingerprint、删除证明和不可恢复状态。

### 6.4 当前生命周期与仓储实现（已实现）

- revision 1 必须为 `DRAFT`；之后只能以连续 revision append，历史 revision 永不更新。
- 非 `DRAFT` revision 只能改变 lifecycle/provenance/time，source、contract、runtime、dependency 和 ownership
  必须保持一致；行为材料变化必须回到新 `DRAFT`。
- `ACTIVE` 要求 runtime ready 且 effect 已解析；`STALE`/`REVOKED` 强制 runtime unavailable；`REVOKED` 终态。
- 仓储主键包含 tenant、organization、project、environment、region、capabilityId 和 revision。读写均重新验封，
  数据库行被篡改时拒绝返回。
- API 不区分“不存在”“跨 scope”“clearance 不足”，统一返回 `RG.MIRROR.SNAPSHOT_NOT_FOUND`，避免形成存在性侧信道。

## 7. 镜像运行模型

### 7.1 MirrorPlan

`MirrorPlan` 是 CapabilitySnapshot、语料、场景和安全策略编译后的唯一运行输入：

```ts
interface MirrorPlan {
  schemaVersion: "resourceGateway.mirrorPlan.v1"
  planId: string
  planFingerprint: string
  rootCapability: RevisionRef
  capabilityClosure: CapabilitySnapshot[]
  executionControlFingerprint: string
  externalBindings: MirrorBinding[]
  scenarioPackRef?: RevisionRef
  stateModelRefs: RevisionRef[]
  executionServices: {
    logicalClock: Instant
    randomSeed: long
    identityFixtureRef?: RevisionRef
    featureFlagFixtureRef?: RevisionRef
  }
  policy: MirrorExecutionPolicy
  expiresAt: Instant
}
```

plan 编译必须是 fail closed 的纯控制面操作。运行时不能临时发现并下载未固定依赖。

`policy.maximumInvocations` 约束实际 operator occurrence，而不是只约束静态 node 数量。编译期必须证明完整递归
inventory 不超过预算；运行期必须在每次 operator resolution 路径中、返回算子和任何 fixture binding 或 operator execution 前原子
扣减。root、nested graph、foreach/loop 重入、streaming 和 compensation 都消耗一次；retry 只增加 attempt。并行
分支只允许至多预算值个 occurrence 穿过门禁。触顶是带签名 evidence 的确定性运行失败，稳定标记为
`INVOCATION_BUDGET_EXHAUSTED`，不能静默截断为成功，也不能把未准入调用落到真实 operator。

### 7.2 external leaf 拦截

BLOGE 执行 composed graph 时保留真实 DSL、decision、transform、foreach 和异常处理。解析到 external invocation 后：

1. 生成稳定 `InvocationSite`、occurrence、attempt 和 correlation coordinate。
2. 查找 plan 中固定的 external binding。
3. 将 canonical request 交给 `MirrorResolver`。
4. resolver 返回结果、错误或 `ABSTAINED`，并附完整 fidelity provenance。
5. 输出仍经过真实 Resource response protocol 和 output schema 校验。
6. InvocationRecorder 记录 request fingerprint、响应来源、匹配层级、状态变更和断言结果。

### 7.3 结果来源

```ts
type MirrorSource =
  | "SESSION_STATE"
  | "OWNER_SPECIFIED"
  | "RECORDED_EXACT"
  | "RECORDED_TRAJECTORY"
  | "RECORDED_CLUSTER"
  | "GOVERNED_REPLAY"
  | "SCHEMA_SYNTHESIZED"
  | "CONTRACT_MOCK"
  | "ABSTAINED"
```

`SCHEMA_SYNTHESIZED` 永远只能产生探索性证据；对写能力、CRITICAL 能力和明确禁止合成的字段必须拒绝。

### 7.4 匹配优先级

匹配顺序固定，不能由业务请求覆盖：

1. session state 与 tombstone；
2. exact tenant + capability revision + business key；
3. exact canonical request fingerprint；
4. owner-approved conditional rule；
5. compatible recorded trajectory；
6. validated request cluster；
7. policy-permitted schema synthesis；
8. `ABSTAINED`。

分簇代表不能直接复用某个实体的完整响应。cluster 响应必须满足以下之一：

- 响应不包含实体身份字段；
- 使用 owner-approved projection/template，并从请求确定性回填身份；
- 返回的是同一业务键的轨迹样本；
- 否则拒绝匹配。

### 7.5 Resolver 置信度与拒答

```ts
interface MirrorResolution {
  schemaVersion: "resourceGateway.mirrorResolution.v1"
  resolutionFingerprint: string
  runId: string
  planFingerprint: string
  capabilityRef: RevisionRef
  invocationSiteId: string
  graphPath: string
  correlationKey: string
  occurrence: number
  attempt: number
  requestFingerprint: string
  status: "RESOLVED" | "ABSTAINED" | "REJECTED"
  source: MirrorSource
  payloadVisibility: "FULL" | "REDACTED" | "HASH_ONLY" | "NONE"
  outputIncluded: boolean
  output: JsonValue | null
  outputFingerprint: string
  error?: MirrorError
  matchedArtifactRefs: RevisionRef[]
  matchedRuleRefs: string[]
  confidence: {
    point: number
    lowerBound: number
    upperBound: number
    method: string
  }
  freshness: number
  limitations: string[]
}
```

`outputIncluded` 用于区分“合法返回 null”和“payload 未进入证据”。`HASH_ONLY` 必须有 output fingerprint 且
不能携带 payload；`ABSTAINED` 不得携带 output、error、match 或非零置信度；`REJECTED` 必须携带 payload-free
错误。低置信度不是 warning 后继续，而是由 capability risk policy 决定降级或拒答。

## 8. 语料蒸馏与数据治理

### 8.1 标准录制事件

```ts
interface CapabilityObservationEnvelope {
  schemaVersion: "resourceGateway.capabilityObservation.v1"
  observationId: string
  tenantId: string
  environmentId: string
  capabilityRef: RevisionRef
  occurredAt: Instant
  traceCoordinates: { traceId: string; spanId: string; sequence: long }
  requestRef: PayloadRef
  responseRef?: PayloadRef
  normalizedError?: ErrorContract
  latencyMs: long
  stateCorrelation?: { entityType: string; businessKeys: object }
  outcomeCorrelationRef?: string
  consentAndPurpose: DataUseGrant
  integrity: SignedEnvelope
}
```

### 8.2 准入管线

```text
接收签名事件
 -> tenant/environment/purpose 校验
 -> 原始字段分类
 -> secret/PII 脱敏或 tokenization
 -> payload 与 metadata 分离
 -> schema 与大小限制
 -> 完整性指纹
 -> 数据权利和保留期
 -> poisoning/异常样本检测
 -> admitted corpus revision
```

任何一步失败都进入隔离队列，不得部分进入 serving corpus。

### 8.3 Schema 归纳

Schema 归纳只创建候选，必须满足：

- 按 capability revision、tenant policy、时间窗和正常/错误分支分层；
- 使用最小样本量、字段出现率置信区间和 holdout 验证；
- `required` 不能用“小样本中 100% 出现”直接决定；
- numeric string、ID、金额、日期不能无语义地归一化；
- enum 默认开放，只有 owner 和契约证据都确认时才能闭集；
- 对 schema 变化执行 backward/forward compatibility 和 impact analysis；
- 输出 sample count、missingness、conflict、unknown field 和 bias risk。

### 8.4 数据权利与隐私

工业上线前必须具备：

1. 采集目的、允许用途、租户、地域和到期时间绑定。
2. redaction 在持久化前完成，禁止先落明文后异步脱敏。
3. tenant 独立密钥、对象前缀、访问策略和审计。
4. legal hold、数据主体删除、客户终止服务和删除证明。
5. 不允许把客户业务语料用于跨租户训练或优化，除非存在独立授权。
6. corpus poisoning 检测、来源签名、重复/突增监控和人工隔离。
7. payload 查看采用 purpose-bound、短时授权和 break-glass 审计。

## 9. 有状态业务世界

### 9.1 SessionStateSpace

```ts
interface SessionStateSpace {
  schemaVersion: "resourceGateway.sessionStateSpace.v1"
  sessionId: string
  tenantId: string
  environmentId: string
  planFingerprint: string
  stateRevision: long
  logicalClock: Instant
  randomSeed: long
  entities: Map<EntityKey, EntitySnapshot>
  tombstones: Set<EntityKey>
  businessKeyIndex: Map<BusinessKey, EntityKey>
  committedEvents: StateTransitionEvent[]
  processedCommands: Map<IdempotencyKey, TransactionReceipt>
  expiresAt: Instant
}
```

### 9.2 第一版事务语义

为避免“看起来支持并发，实际不可预测”，第一版明确限制：

- 每个 session 采用 serializable mutation queue；一次只提交一个 mutation transaction。
- 并行 read 允许，但读取同一 state revision；mutation 提交后后续 read 可见。
- 并行分支写入默认拒绝；只有静态证明 write set 不相交时才允许并行。
- 每个 mutation 必须带 idempotency key；重试返回同一 receipt，不重复生成 ID 或事件。
- 一个 write effect 对多个实体的变更要么全部提交，要么全部回滚。
- deterministic time、UUID、sequence 和 random 由 plan 的 execution services 提供。
- timeout/cancel 在提交前回滚；提交后只返回 committed receipt，不能伪装成取消成功。

后续版本才能引入乐观并发、分区 session 和跨 session 事务；这些不是 v1 能力。

### 9.3 WriteEffectSpec

```ts
interface WriteEffectSpec {
  schemaVersion: "resourceGateway.writeEffectSpec.v1"
  specId: string
  revision: number
  targetCapabilityRef: RevisionRef
  operation: "CREATE" | "UPDATE" | "DELETE" | "UPSERT"
  entityType: string
  identityRule: BoundedExpression
  baselineReadCapabilityRef?: RevisionRef
  preconditions: BoundedExpression[]
  fieldEffects: FieldEffect[]
  businessKeys: BusinessKeyRule[]
  responseProjection: BoundedExpression
  idempotency: IdempotencyContract
  provenance: ArtifactProvenance
}
```

`boundedExpr` 禁止循环、递归、赋值、外部访问、真实时钟和未批准函数。写表达式发生 missing field、
类型错误或除零时，整个 mutation transaction 必须失败，不能“跳过字段继续成功”。

### 9.4 Copy-on-write

update/delete 的基线解析顺序：session entity -> exact business key corpus -> owner-defined baseline fixture。
没有基线时返回 `BASELINE_ABSENT`。禁止从 cluster 或 schema synthesis 创建历史实体基线。

### 9.5 状态驻留与恢复

- session payload state 驻留在 TEE/data plane 的加密 store，不进入 ANEKE 或 Resource Gateway control DB。
- control plane 只保存 session descriptor、state fingerprint、事件摘要和 evidence reference。
- session 支持 TTL、显式销毁、法律保留例外和 cryptographic erasure。
- 长任务必须能够从已签名 state checkpoint 恢复；checkpoint 固定 plan fingerprint 和 committed revision。
- 恢复时发现 capability、state model 或 write effect 漂移必须拒绝，不能热升级运行中的世界。

## 10. 场景、断言与演练

### 10.1 ScenarioPack

```ts
interface ScenarioPack {
  schemaVersion: "resourceGateway.scenarioPack.v1"
  packId: string
  revision: number
  targetCapabilityRef: RevisionRef
  caseRefs: RevisionRef[]
  assertionRefs: RevisionRef[]
  writeEffectRefs: RevisionRef[]
  corpusSnapshotRef?: RevisionRef
  stateModelRefs: RevisionRef[]
  policy: RehearsalPolicy
  provenance: ArtifactProvenance
}
```

场景 case 支持：`golden`、`negative`、`boundary`、`regression`、`fault`、`state-transition`、`what-if`。

### 10.2 CaseHandlingAssertion

断言维度至少包括：

- graph output path/schema；
- node/edge status；
- capability must/must-not occur；
- invocation input 条件；
- error/fallback/compensation；
- state transition 和最终实体不变量；
- side-effect receipt；
- governance expectation；
- latency、retry 和 resource budget。

自动归纳规则只生成 `CANDIDATE`。支持度和 lift 之外，还必须有最小样本量、时间切分验证、置信区间、
混杂风险、代理 outcome 说明和人工确认。任何归纳规则在 outcome 定义改变后自动 stale。

### 10.3 RehearsalRun

一个 rehearsal 必须冻结：

- exact MirrorPlan；
- exact ScenarioPack；
- 初始 state fingerprint；
- logical time/random/identity/feature flag；
- corpus snapshot；
- runtime 和 resolver version；
- contract-mock grant closure。

输出包含每个 case 的 graph trace、external resolution、state transition、assertion、payload reference、
资源消耗和最终 state fingerprint，并可导出 ANEKE workbook seed。

### 10.4 退款域纵向示例

```text
query_order(orderId=O-100)             -> RECORDED_EXACT，copy-in order
create_refund(orderId=O-100, amount=450)
  -> WriteEffectSpec 创建 refund R-1
  -> 更新 order.refundedAmount
  -> 产生 committed transaction receipt
query_refund(refundId=R-1)             -> SESSION_STATE
query_order(orderId=O-100)             -> SESSION_STATE，看到 refundedAmount=450
```

该场景必须证明：真实外部写调用数为 0、重复 create 使用同一 idempotency key 不重复创建、读写一致、
state trace 可重放、删除 session 后 payload 不可恢复而 evidence 仍可验证。

## 11. Fidelity、Shadow 与 Outcome

### 11.1 保真度是向量，不是一个排名分数

```ts
interface DomainFidelityProfile {
  schemaVersion: "resourceGateway.domainFidelityProfile.v1"
  domainId: string
  capabilityScope: RevisionRef[]
  measuredAt: Instant
  contractCoverage: MetricWithConfidence
  requestSpaceCoverage: MetricWithConfidence
  behaviorSimilarity: MetricWithConfidence
  errorDistributionSimilarity: MetricWithConfidence
  stateTransitionFidelity: MetricWithConfidence
  temporalFreshness: MetricWithConfidence
  outcomeAgreement: MetricWithConfidence
  abstentionRatio: MetricWithConfidence
  synthesizedRatio: MetricWithConfidence
  contractMockExpansionDebt: DebtMetric
  limitations: string[]
}
```

单一总分只可用于趋势展示，不能直接作为发布门禁。门禁应消费具体分量、置信度、覆盖范围和 risk policy。

### 11.2 Shadow comparison

shadow 请求必须是明确授权的只读或安全 sandbox 调用。比较维度包括：

- 消费者依赖字段；
- schema、错误类型和状态码；
- 状态转移摘要；
- 分布、尾部和 rare path；
- latency 仅作运行特征，不作为业务正确性。

只比较已声明消费字段会形成盲区，因此必须同时维护 `consumptionAssertionCoverage`、未知字段变化和随机审计样本。

### 11.3 Outcome 校准

Outcome 分为：

- **authoritative**：退款是否结算成功、工单是否闭环、投诉是否成立等独立业务事实；
- **proxy**：FCR、客服纠正、升级人工、重试等近似信号。

校准必须处理延迟、删失、重复归因、策略同时变化和样本选择偏差。proxy 不能提升到 authoritative 等级。
任何“动作导致好结果”的结论都标记为关联性候选，除非有实验、准实验或充分的因果识别设计。

### 11.4 Contract mock 治理

授权必须固定子 capability revision、consumer scope、有效期、最大 mock ratio 和完全展开 suite：

1. 子工具自身 certifiable suite 达标；
2. consumer contract coverage 达标；
3. 架构师和 owner 审批；
4. 定期完全展开运行；
5. 发现 drift 立即 stale，超过阈值 revoke；
6. grant 到期自动关闭，不能默认续期。

## 12. 安全与生产隔离

### 12.1 三层隔离

| 层 | 约束 |
|---|---|
| 协议 | 普通 run API 不接受 test/mirror 字段；独立 endpoint 和 purpose |
| 进程 | mirror runtime 独立 profile、进程或 namespace，生产应用不装载相关 bean |
| 权限与网络 | 独立 workload identity、无生产写 secret、egress allowlist、外部写域名默认拒绝；由外部权威对 exact deployment generation 短期签名证明 |

仓库内已经定义并可离线验证 `resourceGateway.mirrorDeploymentIsolationAttestation.v1`，但
“存在一个合法 JSON”不等于“部署控制真实生效”。生产链必须由 Resource Gateway 之外的 SRE/security
authority 从 scheduler、network policy、service identity 和 image registry 的权威状态生成证明；Resource
Gateway 只接受独立信任通道分发的 key set，并用本机不可变坐标匹配。证明过期、吊销、身份漂移、刷新失败或
运行窗口越界必须在执行前/提交前失败关闭。authority key-set 的服务端可信准入、不可变正文、数据库防回滚
floor 和 current-only 分发已经实现；deployment agent 的 identity-bound pinned mTLS、non-TOFU floor、连续状态机、
denial-first 吊销与 crash-safe atomic cache 也已实现。执行准入与 evidence commit 对同一 cache generation 的双重
绑定仍未实现。

### 12.2 权限

最少权限角色：

- `MIRROR_AUTHOR`：编辑候选规则，不可批准；
- `MIRROR_REVIEWER`：批准 contract/scenario/write effect；
- `MIRROR_RUNNER`：运行已批准 plan；
- `PAYLOAD_VIEWER`：按 purpose 短时查看脱敏 payload；
- `FIDELITY_GOVERNOR`：配置门禁和 contract-mock grant；
- `SECURITY_AUDITOR`：查看不可变审计，不可执行。

高风险 write effect、跨地域数据和 break-glass 采用双人批准。身份、审批和执行者必须进入 evidence。

### 12.3 威胁模型

必须覆盖：fixture 注入生产、漏标写能力、SSRF 绕过、secret 泄漏、跨租户 corpus 命中、语料投毒、
恶意 owner 规则、payload reference 猜测、过期 grant 继续使用、证据重签、删除不彻底、shadow 放大真实流量、
session 重放造成重复状态、运行时依赖漂移。

部署证明专项还必须覆盖：伪造 issuer、复用 evidence signing key、旧 image digest 重放、namespace/service account
漂移、network/credential/allowlist policy 漂移、过期缓存、key 轮换裂脑、吊销传播延迟、签发器时钟偏差、证明签发成功
但控制面应用失败，以及执行跨越证明 expiry。当前协议/离线 verifier 已根治字段漂移和本地盲信，剩余问题必须在
外部签发、可信分发和 runtime admission/commit 双重检查中闭合。

## 13. 协议与 API 表面

所有协议使用独立 schemaVersion、严格 unknown-field policy、bounded collection、canonical fingerprint 和兼容性测试。

| API | 用途 | 幂等语义 | 当前状态 |
|---|---|---|---|
| `PUT /api/integration/capability-snapshots/{capabilityId}/revisions/{revision}` | 导入 exact sealed snapshot | 同 scope/id/revision/fingerprint 幂等 | 已实现 |
| `GET /api/integration/capability-snapshots/{capabilityId}?revision=0` | 读取 latest 或 exact revision | 只读 | 已实现 |
| `POST /api/integration/capability-snapshots/{capabilityId}/lifecycle-transitions` | lifecycle-only append | expectedRevision 乐观栅栏 | 已实现 |
| `POST /api/mirror/capability-snapshots` | 从现有资产生成并持久化不可变投影 | source fingerprint 幂等 | 待 projection API 与持久化编排 |
| `POST /api/mirror/plans` | 权威解析 Graph/Closure/Fixture/Replay 并编译 exact MirrorPlan | scope + planId + complete compile fingerprint 幂等 | 已实现（test/staging + 显式开关） |
| `GET /api/mirror/plans/{planId}` | 读取完整 scope 下的 verified payload-free MirrorPlan | 只读 | 已实现（test/staging + 显式开关） |
| `POST /api/mirror/sessions` | 创建隔离状态世界 | idempotency key | 待实现 |
| `POST /api/mirror/executions` | 同步运行一个 sealed stateless capability generation | full scope + requestId + plan/context fingerprint；并发 lease + epoch fencing | 已实现（test/staging + 显式开关） |
| `POST /api/mirror/rehearsal-jobs` | 提交批量长任务 | job request fingerprint | 待实现 |
| `GET /api/mirror/runs/{runId}` | 查询 verified payload-free 运行摘要 | 只读、完整 scope 隔离 | 已实现（test/staging + 显式开关） |
| `GET /api/mirror/runs/{runId}/evidence` | 导出 independently verified `HASH_ONLY` signed evidence | 只读、完整 scope 隔离 | 已实现（test/staging + 显式开关） |
| `POST /api/mirror/trust/deployment-isolation/authority-key-sets` | 本地信任复验并原子追加 authority key-set generation | full scope + deployment + keySet + generation/fingerprint；同代同指纹幂等 | 已实现（test/staging + `MIRROR_TRUST_ADMIN`） |
| `GET /api/mirror/trust/deployment-isolation/authority-key-sets/{keySetId}/latest` | 重新复验并分发 current durable floor | 只读、完整 scope + `deploymentScopeId` 隔离 | 已实现（test/staging） |
| `GET /api/mirror/trust/deployment-isolation/authority-key-sets/{keySetId}/generations/{generation}` | 按内容地址读取，但仅当地址仍等于 current floor | `generation + publicationFingerprint` exact current match | 已实现（test/staging） |
| `POST /api/mirror/trust/deployment-isolation/attestations` | 复验 current authority 并追加 exact bootstrap/continuous attestation | full scope + deployment + keySet + attestationId/revision；同代同指纹幂等 | 已实现（test/staging + `MIRROR_TRUST_ADMIN`） |
| `GET /api/mirror/trust/deployment-isolation/attestations/{attestationId}/latest` | 分发 atomic attestation/current-status bundle | ACTIVE 同 authority/time 复验；REVOKED denial-only 分发 | 已实现（test/staging） |
| `GET /api/mirror/trust/deployment-isolation/attestations/{attestationId}/revisions/{revision}` | exact current attestation + status 地址读取 | 四坐标都仍等于 current head 才返回 | 已实现（test/staging） |
| `POST /api/mirror/trust/deployment-isolation/attestations/{attestationId}/revocations` | 精确栅栏下不可逆追加 REVOKED 状态 | 同 reason 原 fence/exact fence 幂等；禁止 re-activate | 已实现（test/staging + `MIRROR_TRUST_ADMIN`） |
| `POST /api/mirror/observations` | 接收签名 observation metadata | observationId 幂等 | 待实现 |
| `POST /api/mirror/corpus-candidates` | 触发受治理归纳 | source snapshot 幂等 | 待实现 |
| `POST /api/mirror/shadow-jobs` | 提交 shadow comparison | job fingerprint 幂等 | 待实现 |
| `GET /api/mirror/fidelity/domains/{domainId}` | 查询 fidelity profile | 只读 | 待实现 |
| `POST /api/mirror/outcomes` | 回填 outcome | outcome identity 幂等 | 待实现 |

部署隔离证明与 authority key-set publication 已有 strict artifact protocol、双层 canonical fingerprint、M-of-N
bootstrap-root 验签和独立 test-kit verifier。authority key-set 现在还有 full-scope、append-only、content-addressed
repository/API，并在同一数据库事务中原子追加正文、CAS 推进 `(keySetId, generation,
publicationFingerprint)` durable floor、提交成功 audit；local binding/roots 只来自 operator-owned SPI。服务端只分发
current floor，过期或本地复验失败会停止服务。attestation 现在也有 operator-pinned bootstrap revision、full-scope
append-only body/status repository、durable current head、protected ingest/current/revoke API 和同事务成功 audit；ACTIVE
必须绑定同一 current authority generation，REVOKED 在 authority outage/过期后仍可作为 denial-only 状态分发。
deployment agent 现在经 private-PKI/SPKI-pinned/identity-bound mTLS 拉取 exact vendor/envelope，按 operator-pinned
bootstrap floor 与连续 authority/attestation/status 状态机验真，并通过 fsync + atomic rename 替换 durable read-only
snapshot；旧 ACTIVE 在 hard age 关闭后不可用，REVOKED 不因过期恢复权限。下一增量应让 execution admission 和
evidence commit pin 并复验同一 `cacheGeneration/snapshotFingerprint`。在运行时双重绑定闭合前，不得仅凭 control-plane
分发成功、agent cache 存在或调用方上传 attestation 提升 trust class。

外部集成协议扩展 `ToolStudioResourceGatewayProtocol`，新增能力快照、镜像证据和保真度 feature flags；
旧 GraphDraft/RunEvidence 协议保持兼容，不在 v1 中删除。

## 14. 存储、事件与一致性

### 14.1 Control plane 元数据

```sql
capability_snapshot(capability_id, revision, fingerprint, source_kind, source_ref,
                    source_fingerprint, contract_ref, effect_summary, lifecycle, created_at)
mirror_plan(plan_id, fingerprint, root_capability_ref, dependency_closure_ref,
            policy_ref, expires_at, created_at)
mirror_run_request(scope, request_id, request_fingerprint, context_fingerprint,
                   plan_id, plan_fingerprint, status, lease_owner, lease_epoch,
                   lease_expires_at, run_id, evidence_bundle_fingerprint, retain_until)
mirror_run_evidence(scope, run_id, plan_id, plan_fingerprint,
                    bundle_fingerprint, completed_at, signed_hash_only_json)
mirror_operation_audit(sequence, occurred_at, scope, correlation_id, actor_type, actor_id,
                       operation, outcome, reason, reason_code, request_id, plan_id, run_id,
                       duration_millis)
mirror_isolation_authority_publication(scope, deployment_scope_id, key_set_id, generation,
                       publication_fingerprint, material_fingerprint, signed_public_json)
mirror_isolation_authority_trusted_floor(scope, deployment_scope_id, key_set_id,
                       immutable_deployment_coordinates, floor_generation,
                       floor_publication_fingerprint)
mirror_isolation_attestation(scope, deployment_scope_id, key_set_id, attestation_id,
                       revision, attestation_fingerprint, material_fingerprint,
                       authority_generation, authority_publication_fingerprint, signed_public_json)
mirror_isolation_attestation_status(scope, deployment_scope_id, key_set_id, attestation_id,
                       attestation_revision, attestation_fingerprint, status_revision,
                       status_fingerprint, previous_status_fingerprint, state, reason, effective_at)
mirror_isolation_attestation_head(scope, deployment_scope_id, key_set_id, attestation_id,
                       immutable_deployment_coordinates, floor_revision,
                       floor_attestation_fingerprint, authority_generation,
                       authority_publication_fingerprint, status_revision, status_fingerprint)
artifact_revision(artifact_type, artifact_id, revision, fingerprint, state,
                  provenance_json, payload_ref, created_at)
contract_mock_grant(grant_id, subtool_ref, consumer_scope, max_ratio, state,
                    approved_by, expires_at, last_expansion_run_ref)
fidelity_observation(observation_id, domain_id, scope_fingerprint, metric_vector,
                     confidence_json, source_refs, measured_at)
mirror_run(run_id, plan_fingerprint, scenario_pack_ref, initial_state_fingerprint,
           final_state_fingerprint, status, evidence_ref, created_at, completed_at)
```

### 14.2 Data plane

```text
corpus metadata index       -> relation/search store，tenant/domain/capability 分区
sanitized payload           -> encrypted object vault，content addressed
session state               -> encrypted TTL state store，单 session 线性化
state transition journal    -> append-only，transaction sequence + state fingerprint
shadow/outcome inbox        -> durable event log + idempotent consumer cursor
```

### 14.3 一致性

- metadata 与 payload 使用 prepare/finalize 或 transactional outbox；悬空引用由反熵任务隔离，不能 serving。
- 事件至少一次投递，consumer 按稳定业务 identity 幂等。
- 无状态 mirror run 不跨事务持有数据库锁：短事务 claim lease，进程外执行，最后以 owner+epoch fenced commit；
  evidence insert 与 request terminal transition 必须同事务，任一步失败都不得留下可 serving 的半状态。
- claim、expiry、takeover、release、terminal fencing 和 busy retry delay 必须来自同一个协调数据库时钟；应用副本
  不得提交绝对租约时间，避免跨机房或容器墙钟漂移改变执行 authority。H2 实现必须在获得 authority 行锁后通过
  独立短连接采样，连接池至少能同时提供外层事务连接与时钟连接；容量不足时协调请求失败关闭为 503。
- request coordination 只保存 fingerprint，不保存 context；completed request 与 evidence 的 run/request/plan/context/scope/
  bundle identities 在每次 retry projection 时重新交叉验证。
- protected Plan/Run/Evidence 结果只有在 terminal operation audit 成功后才可发布。Plan create 与 Run terminal
  evidence/request state 的成功审计参与同一事务；任一写失败，业务结果与“成功”事实一起回滚。失败/拒绝审计必须以
  `REQUIRES_NEW` 独立提交，避免外层事务回滚抹掉根因；独立写也失败时以稳定 503 替换原结果。失败审计意味着单次
  请求在外层事务尚占用连接时还需一条短连接，连接池和数据库 admission 必须按并发预算配置。
- operation audit 是 append-only payload-free 事实流，不是业务 evidence 的替代品。生产部署必须定义分区、只读
  导出角色、归档锚点、删除/保留证明、磁盘水位和全盘故障演练；当前应用不提供可绕过治理的删除或公开读取 API。
- isolation attestation 的外部 body 和本地 status 都只追加，head 只是 current 指针。首次 revision 必须等于本地
  admission policy 的 bootstrap floor，后续只接受连续 successor；正文/初始 ACTIVE/head CAS/成功 audit 与
  REVOKED status/head CAS/成功 audit 分别原子提交。ACTIVE 依赖 current authority 正向复验，REVOKED 分发只传播
  deny，不得因 authority outage、换代或证明过期而被阻断。
- corpus 是 append-only revision；删除通过 payload tombstone 和删除证明表达。
- mirror plan 创建后不追随 registry 变化。
- fidelity profile 是派生投影，可重建；其 source closure 必须可追溯。

## 15. 失败语义与降级矩阵

| 故障 | 行为 | 证据等级 |
|---|---|---|
| Corpus index 不可用 | exact session/cache 可继续，否则 ABSTAINED | 探索或失败 |
| Payload vault 不可用 | 不使用 metadata 合成 payload，拒绝该 invocation | 失败 |
| State store 不可用 | stateful run 失败关闭，不退化为 stateless | 失败 |
| Schema synthesis 不可用 | 跳过该层，最终可能 ABSTAINED | 取决于前序命中 |
| WriteEffectSpec 缺失/stale | 拒绝 mutation | 失败 |
| baseline 缺失 | `BASELINE_ABSENT`，不从 cluster 造历史实体 | 失败 |
| boundedExpr 失败 | 回滚 mutation transaction | 失败 |
| contract/mock grant stale | 展开真实子图；无法展开则拒绝 plan | 取决于展开结果 |
| Outcome connector 不可用 | run 可完成，但不产生 outcome-calibrated 结论 | 非校准证据 |
| Shadow source 不可用 | shadow job 可重试，不能报告零 drift | 不产生 observation |
| 依赖 fingerprint 漂移 | plan 编译或恢复失败，要求重新冻结 | 失败 |
| 证据签名不可用 | 拒绝 terminal publication，不保存或交付 unsigned evidence | 失败 |
| operation audit 不可构造或持久化 | 回滚未发布成功结果；失败路径也无法留痕时以脱敏可重试 503 替换原结果 | 失败 |

## 16. NFR、容量与 SLO

以下是首个客户域的默认工程目标，灰度后按实测校准：

| 维度 | 初始目标 |
|---|---|
| 安全 | 真实外部写逃逸次数必须为 0；每个 release 执行主动对抗测试 |
| 确定性 | certifiable case 在相同 plan/state/seed 下语义结果指纹一致率 100% |
| 交互延迟 | exact/owner rule 镜像附加开销 p95 <= 150ms，p99 <= 500ms |
| 状态事务 | 单 session mutation 提交 p95 <= 250ms，不含图本身计算 |
| 可用性 | mirror control API 月可用性 99.9%；数据面 99.95% |
| 批量演练 | 2,000 cases 在 30 分钟内完成，支持租户级并发配额 |
| 恢复 | control metadata RPO <= 5 分钟、RTO <= 30 分钟；payload 依客户策略 |
| 新鲜度 | 高风险能力 24 小时内完成 drift 检测；普通能力 7 天 |
| 隔离 | 跨租户命中、payload 泄漏、未授权引用均为 0 |
| 证据 | terminal run 的 evidence 完整率 100%，签名失败不得冒充完成 |

### 16.1 容量保护

- tenant/domain/capability 四级 quota；
- corpus 每能力最大样本、每响应最大字节和每日 ingest 上限；
- rehearsal 并发、case 数、图节点数、递归深度、foreach 次数和表达式节点数硬限制；
- shadow 按外部系统 budget 限流，带熔断和独立 kill switch；
- session TTL、实体数、事件数和总字节上限；
- 所有队列有 backlog SLO、dead-letter 和 bounded retry。

### 16.2 可观测性

至少输出：

- resolution source、abstention、confidence、freshness 和 latency；
- corpus ingest/admission/quarantine/expiry；
- session entity/event/transaction/rollback；
- contract mock ratio、expansion drift 和 grant expiry；
- shadow mismatch、outcome lag、校准样本和 bias warning；
- tenant quota、queue lag、vault/store/error budget；
- 所有 fail-closed 原因的低基数指标与可追踪审计事件。

第十五增量已落地最小 protected-operation 子集：

| 名称 | 只允许的标签 | 运维用途 |
|---|---|---|
| `resource.gateway.mirror.operations` | `operation`,`outcome` | 成功/拒绝/失败吞吐与错误预算 |
| `resource.gateway.mirror.duration` | `operation`,`outcome` | 各 API 终态耗时，不混入资源身份 |
| `resource.gateway.mirror.failures` | `operation`,`reason` | 封闭失败原因告警和故障域路由 |

上述指标在启动时预注册完整 75 条 series，不能按客户、scope、actor、request、plan、run 或 exception 动态扩张。
`mirror_operation_audit` 才是可追踪的持久事实，指标不是审计替代品。告警分级至少为：任一
`AUDIT_UNAVAILABLE` 立即 page；持续 `UNAVAILABLE`/`UNEXPECTED` page；`CAPACITY` 按预算触发扩容或 admission；
`INVALID_REQUEST`/`FORBIDDEN`/`NOT_FOUND`/`CONFLICT` 的突增进入安全和集成质量队列。告警上下文通过受限审计查询
关联 correlation/resource id，绝不把这些高基数坐标复制到 metric tag。

本增量未实现 active audit-store health probe、审计归档/外部不可变锚、保留期执行器、dashboard 或告警平台适配。
这些由部署环境闭合；数据库空间耗尽会有意导致 protected Mirror API 失败关闭，不能为了可用性丢弃审计后继续
交付结果。上线前必须以容量模型和故障演练证明这一行为受控。

## 17. UX 与 DX

### 17.1 业务工作台

工作台按业务域和 case 组织，而不是按技术资产列表组织：

1. **看**：健康仪表盘、真实脱敏 case、能力拓扑、状态轨迹和保真度限制。
2. **调**：从 case 克隆 what-if，使用表单、滑块、决策表和状态差异编辑，不直接编辑 JSON。
3. **确认**：候选契约、场景、write effect 和断言显示来源样本、置信度、影响面与生效范围。
4. **运行**：预估 case 数、资源预算、mock/real 展开比例和证据等级后提交。
5. **解释**：失败直接定位到 capability、invocation、state transition、assertion 和 owner。

AI 只负责把自然语言翻译成候选规格并回显，不直接批准、不绕过 bounded compiler、不创造不存在的业务事实。

### 17.2 开发者体验

同一 ScenarioPack 支持画布、REST、Java/JUnit、CLI 和 CI：

```java
MirrorSession session = mirror.session("refund-domain")
    .plan("refund-plan", 12)
    .logicalTime(Instant.parse("2026-07-22T00:00:00Z"))
    .seed(42L)
    .create();

MirrorRunResult result = session.runCase("large-refund-fallback");
result.assertCertifiable();
result.exportEvidence(Path.of("target/evidence"));
```

SDK 默认隐藏 payload，异常消息只能包含稳定错误码、引用和脱敏摘要。CI 输出 JUnit XML、JSON evidence reference
和 ANEKE workbook seed，不能把业务 payload 写入构建日志。

## 18. 分阶段演进计划

### 18.1 团队与估算假设

以下按 2 周 sprint、1 名技术负责人、3 名后端、1 名前端、1 名数据工程师、1 名 QA/SRE，安全和业务 owner
兼职投入估算。团队变化时调整工期，不调整阶段门禁。

### Stage 0：语义与协议冻结，2 个 sprint，P0

**交付物**：

- CapabilitySnapshot、CapabilityClosure、CapabilityContract、EffectContract、ArtifactProvenance JSON Schema；
- lifecycle、compatibility、error code 和 fingerprint 规范；
- Resource/Operator/Graph 到 CapabilitySnapshot 的无损 projection；
- 三方责任 RACI、数据驻留和威胁模型；
- capability probe 和 schema compatibility test。

**退出门禁**：现有 7 个 resource graph 和 3 个画布示例可生成稳定 capability closure；重复生成 fingerprint 一致；
effect unknown 和递归环会失败关闭；旧协议无破坏。

### Stage 1：无状态 external mirror，3 个 sprint，P0

**交付物**：

- MirrorPlan compiler 和 external leaf resolver SPI；
- FixtureBundle/Replay adapter，不复制现有测试控制协议；
- 固定匹配优先级、MirrorResolution 和 ABSTAINED；
- mirror node/edge provenance 和 signed evidence vNext；
- 受保护 Run/Summary/Evidence API、payload-free database-clock request lease、epoch fencing 与 evidence 原子提交；
- 生产 profile 架构测试、无写凭据和 egress deny 验证。

**退出门禁**：composed graph 真实执行，external 调用全部被拦截；现有 operator/graph suite 能通过 adapter 运行；
未匹配 external 不会落到真实调用；同 plan/seed 结果稳定。

### Stage 2：受治理语料，4 个 sprint，P0/P1

**交付物**：

- observation envelope、ingest/admission/quarantine 管线；
- 脱敏 payload vault、metadata index、retention/deletion proof；
- exact/trajectory/cluster resolver 和 confidence/abstention；
- schema candidate induction、holdout validation 和 owner review queue；
- corpus drift、poisoning、freshness 和 lineage 指标。

**退出门禁**：退款域至少 1,000 个脱敏样本完成准入；跨租户和未授权目的命中为 0；每个 serving 响应可追溯；
低置信度按 risk policy 拒答；删除后 payload 不可取回且证据可验证。

### Stage 3：有状态退款域纵向切片，4 个 sprint，P0

**交付物**：

- StateModel、WriteEffectSpec、SessionStateSpace 和 transaction journal；
- serializable mutation、idempotency、copy-on-write、tombstone、checkpoint/recovery；
- query/create/update/query 完整退款场景；
- state trace、最终 fingerprint 和可重放 evidence；
- 外部写逃逸主动对抗测试。

**退出门禁**：退款纵向场景全部通过；重复、timeout、cancel、baseline 缺失和 state store 故障行为符合矩阵；
真实写调用为 0；恢复结果与不中断执行语义指纹一致。

### Stage 4：Scenario 与正确性工作簿，3 个 sprint，P1

**交付物**：

- ScenarioPack、CaseHandlingAssertion 和 rehearsal jobs；
- golden/negative/boundary/fault/state-transition/what-if；
- 画布场景编辑、状态 diff、批量运行和失败定位；
- ANEKE workbook seed 和 gate evidence binding；
- 手工规则优先的 owner review 工作流。

**退出门禁**：退款和工单故障两个域均有可维护 suite；业务 owner 可在不编辑 DSL/JSON 的情况下完成候选确认、
case 微调和结果解释；发布门禁能区分执行失败、断言失败、低保真和证据不完整。

### Stage 5：Fidelity、Shadow 与 contract mock，4 个 sprint，P1

**交付物**：

- DomainFidelityProfile 向量、confidence 和 coverage；
- 只读 shadow jobs、typed behavior diff 和 drift debt；
- contract-mock grant、ratio、定期完全展开和自动 revoke；
- domain dashboard、owner route 和 SLO alert。

**退出门禁**：所有保真结论可重建；零样本/数据缺口不会显示为高分；contract mock 漂移能自动失效；
高风险能力在 24 小时 drift 窗口内闭环。

### Stage 6：Outcome 校准与业务运营，4 个 sprint，P1/P2

**交付物**：

- proxy/authoritative outcome connector；
- delayed/censored outcome reconciliation；
- assertion/write-effect candidate induction；
- 业务域“看、调、确认”工作台和运营指标；
- 自然语言到候选规格的受限 AI assistant。

**退出门禁**：归纳候选不经 owner 不能 serving；outcome 定义漂移会使相关规则 stale；能够证明至少一个保真分量
与权威业务结果存在稳定关系，或明确否证并调整方案。

### Stage 7：企业规模化认证，按客户环境触发，P2

**交付物**：多地域、HA/DR、动态密钥、租户配额、成本治理、混沌演练、法律保留、跨组织 delegated authority、
SRE runbook 和生产认证包。

**退出门禁**：通过安全评审、灾备演练、容量压测、数据权利审计和客户生产准入；所有 capability probe 如实反映
当前环境能力，未认证能力保持关闭。

## 19. 工程拆分与依赖

| Epic | 优先级 | 主要模块 | 前置 | 可独立验收 |
|---|---|---|---|---|
| E0 Capability Projection | P0 | integration/visual/resource | 无 | 7 个 graph 快照稳定 |
| E1 Mirror Plan Compiler | P0 | testing/planning + runtime | E0 | frozen closure 与拒绝矩阵 |
| E2 External Resolver SPI | P0 | BLOGE runtime adapter | E1 | external 不逃逸 |
| E3 Mirror Evidence vNext | P0 | testing/domain + test-kit | E1/E2 | 独立 verifier 通过 |
| E4 Observation Admission | P0 | TEE ingest | E0 | quarantine/lineage/retention |
| E5 Corpus Resolver | P1 | data plane | E4 | exact/trajectory/cluster/abstain |
| E6 Stateful World | P0 | data plane + runtime | E1/E2 | 退款读写读 |
| E7 Scenario/Rehearsal | P1 | author/testing | E3/E6 | 两域 suite |
| E8 Fidelity/Shadow | P1 | ANEKE + TEE | E5/E7 | 可重建 profile |
| E9 Outcome Calibration | P1 | ANEKE | E7/E8 | 候选与 stale 闭环 |
| E10 Business Workbench | P1 | author UI | E5/E7 | owner 零 DSL 完成任务 |
| E11 Enterprise Certification | P2 | SRE/security | 全部 | 客户准入包 |

### 19.1 首两个 sprint 的可领取 backlog

| Ticket | 工作项 | 建议代码/文档落点 | 验收条件 |
|---|---|---|---|
| RG-MIR-001 | 冻结 `CapabilitySnapshot` JSON Schema | `docs/schemas/resource-gateway-mirror/`、integration model | strict unknown fields；合法/非法/兼容 fixture 全部通过 |
| RG-MIR-002 | 实现 Resource/Operator/Graph projection | `gateway/integration/mirror` | 内置 7 graph 和 3 visual examples 重复投影 fingerprint 一致 |
| RG-MIR-003 | 实现 transitive EffectContract 汇总 | `gateway/integration/mirror` | read/write/mixed/unknown、递归环和声明冲突测试齐全 |
| RG-MIR-004 | 冻结 provenance 与 lifecycle 状态机 | mirror schema + repository interface | 非法跃迁拒绝；stale/revoke 行为有协议测试 |
| RG-MIR-005 | 增加 capability snapshot API 与 capability probe | integration controller/capability service | scope/identity 校验；功能未闭合时 feature flag 为 false |
| RG-MIR-006 | 建立 `MirrorPlanCompiler` 骨架 | `gateway/testing/planning` | 已完成 compiler/run kernel、exact closure/runtime inventory 对账、external-only 控制、resolver provenance、generation/TTL/scope 准入、静态 + 动态 occurrence budget、payload-free durable store、受保护 Plan/Run/Evidence API、durable request fencing、fail-closed operation observability、deployment-attestation 协议/离线验真、authority key-set trusted distribution/durable floor、attestation ingest/status/revocation/current-only 分发、agent pinned mTLS/non-TOFU/atomic cache，以及 admission/confirmation/transaction commit 的 runtime trust binding；待非 Java v2 固定 fixture 与环境级多副本认证 |
| RG-MIR-007 | 复用 FixtureBundle 的 mirror adapter ADR | `docs/adr/ADR-004-mirror-plan-reuses-fixture-bundle.md` + `compileMirror` | 已完成；不新增平行 fixture 主模型；映射损失和暂不支持项显式报告 |
| RG-MIR-008 | 建立生产隔离架构测试 | production composition tests | bean/profile 双栅栏、普通请求控制字段拒绝及 Plan/Run/Evidence route 在 production/mixed profile 物理不存在已完成；deployment-attestation strict protocol/producer/verifier 已完成；待外部签发/分发、运行时 egress 证明绑定和 pre-materialization ingress 门禁 |
| RG-MIR-009 | 增加 test-kit 协议模型与 compatibility fixtures | `resource-gateway-test-kit` | 已完成 Snapshot/Closure、MirrorEvidence 与 DeploymentIsolationAttestation 独立复验；三份共享 fixture（含两份 signed fixture）；不依赖 server/Spring |
| RG-MIR-010 | 建立退款域资产清单 | `docs/examples/resource-gateway-mirror/refund/` | capability closure、entity、baseline read、write effect、outcome owner 完整 |
| RG-MIR-011 | 增加协议版本与错误码注册表 | mirror protocol docs | 每个拒绝路径有稳定 code、HTTP 语义和重试分类 |
| RG-MIR-012 | 建立 Stage 0 CI 门禁 | Maven/schema/doc verification | projection、schema、compatibility、production isolation 在 PR 必跑 |

推荐领取顺序：001/004/007/010 可并行；随后 002/003/009/011；最后 005/006/008/012。Stage 0 不实现
真实 mirror serving，避免协议尚未冻结时把临时模型固化进运行时。

当前领取状态：RG-MIR-001/002/003/004/005/007/009 已完成通用协议、projection、effect、闭包、生命周期、仓储、
Integration API、诚实 probe、7 张内置 graph 加 3 张 visual example 确定性投影，以及独立 compatibility/离线复验。
这已经满足 Stage 0 的仓库内工程退出门禁。RG-MIR-006 已完成 compiler、resolver chain、逐次 provenance 与内部
run kernel；E3 已完成 payload-free evidence/attestation/bundle 协议、真实运行时投影、服务端签名完整性内核、
external attempt/resolution exact closure、Java test-kit independent verifier、共享 signed fixture 与 full-scope
append-only plan/evidence 仓储、受保护 Plan/Run/Evidence API、payload-free durable request coordination 与
fenced atomic commit、静态/动态 occurrence budget、同事务成功审计、跨回滚失败审计、固定基数指标，以及
deployment-isolation strict protocol、producer integrity、共享 signed fixture、独立 verifier、authority key-set
full-scope append-only trusted distribution API 与 durable floor CAS，以及 full-scope attestation body/status/head
存储、受保护 ingest/current/revoke API、不可逆撤销和当前 authority 读时复验，以及 deployment agent 的 private-PKI/
SPKI-pinned/identity-bound mTLS、strict vendor/envelope、operator-pinned bootstrap floor、连续状态机、denial-first revocation、
hard freshness fence 与 crash-safe atomic cache，以及 certification-required plan 的 pre-claim admission、稳定 decision
幂等绑定、lease-local TrustAttempt、terminal re-observation、v2 evidence 双 snapshot binding、transaction-lifetime commit
permit、v1/v2 双读和独立 test-kit 语义复验；非 Java v2 固定 bundle/snapshot fixture、语言中立数字 canonicalization
和生产部署门禁未闭合，仍在 Stage 1 主链；008/010/011/012 继续补齐生产隔离、退款资产、
错误码注册与持续 CI。
企业客户准入仍必须关闭第 22.2 节的环境级开放决策。

## 20. 测试策略与 Definition of Done

### 20.1 测试层级

| 层 | 目标 |
|---|---|
| L0 Schema/Protocol | unknown field、compatibility、fingerprint、bounded collection |
| L1 Unit | resolver、effect、state transaction、expression、metric 纯逻辑 |
| L2 Component | corpus vault、state store、projection、evidence signer |
| L3 Graph | composed graph 真实执行、external leaf 替换 |
| L4 Stateful Scenario | 读写读、retry、cancel、rollback、recovery |
| L5 Differential | mirror vs shadow、contract mock vs fully expanded |
| L6 Security | 写逃逸、SSRF、跨租户、secret、payload reference、poisoning |
| L7 Operations | HA/DR、容量、backpressure、retention、key rotation、chaos |

### 20.2 全局 DoD

一个 Stage 只有同时满足以下条件才完成：

1. 公共协议有 JSON Schema、Java/test-kit model、compatibility fixture 和 canonical examples。
2. capability probe 只在端到端可用且安全门禁通过时报告 `true`。
3. 所有失败有稳定错误码、HTTP 语义、审计事件和 runbook。
4. payload 不进入应用日志、异常、metric label、CI artifact 或 control DB。
5. 关键状态机有并发、重试、恢复、篡改和过期测试。
6. 文档、启动脚本、demo 数据和浏览器操作路径同步更新。
7. narrow tests、Resource Gateway `clean verify`、test-kit `clean verify` 和必要浏览器回归通过。
8. 未完成的真实 provider、HA、custody 或生产接线必须在 capability 中保持关闭。

## 21. 风险、反模式与停止条件

### 21.1 禁止的捷径

- 在普通 run request 增加 `mirrorMode` 或任意 fixture map；
- 用另一套 MirrorFixture 替换现有 FixtureBundle，而不做 adapter；
- 把 cluster 高频响应直接返回给不同实体；
- 把 schema synthesis 结果标为 recorded；
- 让 state store 故障时静默无状态运行；
- 用生产日志支持度直接生成 blocking assertion；
- 用一个 fidelity 总分驱动发布；
- 在 Resource Gateway 内复制 ANEKE registry 和 publish gate；
- 为追求速度默认 contract mock composed tool；
- 未完成数据权利和删除链就批量导入客户 payload。

### 21.2 停止或转向条件

出现以下任一情况，应暂停扩域并复盘：

- 发现任何真实外部写逃逸；
- 无法证明跨租户隔离或 payload 删除；
- 业务 owner 无法理解或维护候选，长期依赖研发代操作；
- 保真指标与权威 outcome 长期无关系；
- stateful mirror 的维护成本超过其发现问题和演练收益；
- contract mock drift 债务持续超过约定窗口；
- corpus 偏差导致系统性错误且无法通过 abstention/分层采样控制。

## 22. 当前冻结决策与待决问题

### 22.1 建议立即冻结

1. Capability 是跨系统投影协议，不替换现有主数据模型。
2. composed capability 不是天然纯函数，必须有 transitive EffectContract。
3. external mirror 在身份和网络层面失去真实写能力。
4. 归纳产物先候选、后人审；日志不能自证正确性。
5. stateful v1 使用单 session 串行 mutation 和确定性 execution services。
6. fidelity 使用向量和置信度，发布门禁不消费单一总分。
7. Resource Gateway、ANEKE、TEE 按第 4 节分工。
8. 第一条业务纵向切片固定为退款域读写读。

### 22.2 Stage 0 必须关闭的开放问题

| 决策 | Owner | 截止点 |
|---|---|---|
| 客户 payload 的数据使用授权和地域边界 | 安全/法务/客户 owner | Stage 0 结束 |
| CapabilitySnapshot 的跨系统 schema owner | RG + ANEKE 架构组 | Stage 0 第 1 sprint |
| Mirror runtime 进程/namespace 部署形态 | RG Runtime + SRE | Stage 0 结束 |
| TEE state store 与 payload vault 技术选型 | 数据平台 + 安全 | Stage 1 开始前 |
| 退款域 entity/state/write effect owner | 退款业务 owner | Stage 2 开始前 |
| authoritative outcome 定义与延迟窗口 | 业务分析 + owner | Stage 4 开始前 |
| 首批 SLO 和租户容量预算 | SRE + 产品 | Stage 1 结束前 |

### 22.3 已冻结的运行期认证语义

当前实现已经关闭“deployment agent 的可信事实如何绑定到一次完整运行”这一仓库内缺口：认证计划在 durable
claim 前读取 ACTIVE trust；Registration 固定稳定 attestation-bundle decision；每个 lease epoch 固定自己的
agent snapshot attempt；执行结束再次观察同一 decision；v2 evidence 同时签入 admitted/committed snapshot；
commit 读许可保持到 evidence、request terminal state 和成功审计的事务完成。正常 refresh 只增加本地 generation
时不杀死长运行，revocation、successor、rollback、expiry 或 decision drift 均 fail closed。

该能力把“隔离声明”变成了可离线复验的运行事实，但没有把 fixture 自动变成业务真相。客户业务拟合仍必须继续推进
corpus/state/scenario/outcome calibration；ANEKE 仍负责 correctness workbook、owner approval 与 publish gate。
协议、迁移、错误和运维细节见
[Mirror 运行期信任绑定](resource-gateway-mirror-runtime-trust-binding.md)。

---

## 附录 A：核心算法约束

### A.1 Schema required 候选

```text
对每个 capability revision 和时间窗：
  分离 success/error branch
  统计字段出现率 p 和 Wilson lower bound L
  当 n >= MIN_SAMPLE 且 L >= REQUIRED_THRESHOLD 时标记 required candidate
  在 holdout window 验证缺失率和兼容性
  高风险字段必须 owner 确认
```

### A.2 Resolver

```text
resolve(request, session, plan):
  validate tenant/purpose/capability revision
  if session has exact entity or tombstone: return SESSION_STATE
  if exact business key admitted sample exists: return RECORDED_EXACT
  if exact canonical request exists: return RECORDED_EXACT
  if approved conditional rule matches: return OWNER_SPECIFIED
  if compatible trajectory exists: return RECORDED_TRAJECTORY
  if validated cluster can safely project identity: return RECORDED_CLUSTER
  if policy permits synthesis and risk allows: return SCHEMA_SYNTHESIZED exploratory
  return ABSTAINED
```

### A.3 Stateful mutation

```text
executeMutation(command, session, spec):
  assert mirror runtime has no real-write capability
  verify exact plan/spec/state revision
  if command.idempotencyKey committed: return previous receipt
  begin serializable session transaction
  load exact baseline for update/delete; absent -> rollback BASELINE_ABSENT
  evaluate preconditions and effects with deterministic services
  apply all entity/index/tombstone changes
  append ordered StateTransitionEvents
  atomically commit new state revision + receipt
  return projected response + state fingerprint
```

### A.4 Assertion induction

```text
split cases by time into train/holdout
separate authoritative outcome from proxy outcome
generate candidate action/state predicates
filter by minimum support and confidence interval
measure discriminative lift with multiple-testing correction
record confounders, missingness and selection risks
validate on holdout and drift windows
emit CANDIDATE only; owner approval required
```

## 附录 B：决策记录

| ID | 决策 | 原因 |
|---|---|---|
| D1 | 拟合保真度是长期水位，contract test 是发布底线 | 区分长期复利与单次准入 |
| D2 | external/composed 统一投影、不同运行语义 | 统一引用而不抹平副作用差异 |
| D3 | Capability 不替换现有 registry | 控制迁移风险和重复主数据 |
| D4 | external 为默认镜像边界 | 隔离不可控依赖，保留真实编排验证 |
| D5 | stateful v1 串行 mutation | 先保证可解释和确定性，再扩并发 |
| D6 | 归纳只产候选 | 防止历史行为自证和统计偏差进入 serving |
| D7 | Fidelity 为向量 | 避免 Goodhart 和覆盖盲区 |
| D8 | control plane 不持有原始 payload | 缩小泄漏面并明确 TEE 责任 |
| D9 | 低保真允许拒答 | 未知比伪造更安全、更可治理 |
| D10 | 退款读写读作为首个纵向切片 | 同时验证 external、composed、状态和证据主链 |
