# Stage 4 Control-plane 证书身份产品接线与轮换内核验收

## 1. 结论

本增量关闭两个容易被混为一谈的根因，但对两者做不同成熟度声明：

1. mTLS 握手成功不等于连接的是预期 workload。新增策略在 PKIX、hostname 和 server SPKI pin
   之外，约束 client Subject、双端 URI SAN、双端 EKU/KeyUsage 和独立 issuer SPKI pin。
2. 把新证书写到原路径不等于安全热轮换。新增 rotation kernel 先在旧代之外加载并验真完整候选，
   再按连续 generation 和受限 activation time 原子切换；任何请求只使用完整旧代或完整新代。

精确静态证书身份已接入 publisher 写侧、dynamic inventory、managed trust-root 与
九条 external-anchor 读侧，共 12 组 control-plane transport；并已进入 typed properties、
test/staging 配置、demo preflight、严格 Schema、固定基数 health/capability 与 Tool Studio
feature projection。证书轮换在原子 TLS 内核之上形成了 test/staging 产品状态机：严格事件
协议、独立 M-of-N Ed25519 信任、deployment/target/predecessor/candidate 精确绑定、材料
解析隔离、并发幂等和状态漂移封闭；database-clock durable floor 在 live staging 前线性化
generation、持久化 exact event journal、防止重启回退/同代分叉并自动晋升到期 successor；12 条
稳定 target id 通过统一 runtime 恢复 active/pending generation。后续 convergence kernel 已冻结精确
replica inventory、process-start lease、`STAGED/ACTIVE/FAILED` ACK、all-replica/fenced-quorum 阈值与
aggregate-only proof；数据库实现以单 active fleet、外部 inventory revision floor、严格 sequence 和
whole-record fingerprint 阻断重复副本、回退、分叉、篡改与 authority downgrade。现在它已通过同一
`ControlPlaneCertificateRotationConvergenceMonitor` 接入 runtime heartbeat、durable activation fence 与
live serving fence：数据库时间到达签名时刻且全副本 `STAGED` 后，严格按 durable floor 先、live transport
后晋级；新代必须等全副本 `ACTIVE` 才能出站。capability 可如实报告当前 convergence proof，但
`productionReady=false` 仍保持关闭，因为 CA event watcher、吊销状态持久化/逐请求准入、企业 custody
与生产 HA/DR 尚未闭合。吊销链的第一子步已冻结
`bloge.controlPlaneCertificateStatusPublication.v1`：外部 adapter 把已验证的 CA event、OCSP 或 CRL
归一化为完整、签名、硬过期、cursor 连续的无载荷快照，并由独立 M-of-N Ed25519 trust store 校验。
第二子步已新增 `DatabaseControlPlaneCertificateStatusFloor`：在同一事务内使用数据库时间重新验签，
校验 deployment-pinned baseline、连续 cursor、前序指纹、发布 ID 唯一性和完整 target inventory，并原子
替换 status head；同一 target generation/settings 下证书 identity 不得漂移，`REVOKED` 不得复活为
`GOOD/UNKNOWN`。head、target 与当前 journal 均带 canonical whole-record fingerprint。该持久化内核
仍不等于运行时吊销已开放；watcher、硬过期 cache 和 request gate 仍是后继门禁。
CA 事件分发的 page-chain/cursor 子步也已冻结：每页绑定 scope、连续 sequence、previous page
fingerprint 和最多 12 个不同 target 的独立签名事件；每个 stable serving slot 以数据库
`stage -> apply -> commit` 游标防止部分处理后误推进，并对 exact replay、gap、fork、baseline drift 与
whole-record mutation 失败关闭。该子步尚未接入 HTTP watcher，因此不改变 `productionReady=false`。

## 2. 根因模型

仅有 `PKIX + hostname + leaf SPKI pin + mTLS` 仍存在四类盲区：

| 盲区 | 失败结果 | 根治手段 |
| --- | --- | --- |
| 同一 CA 签出错误 workload | TLS 成功但越权访问错误服务 | 精确 Subject/URI SAN + role EKU |
| client keystore 含多个 key entry | JDK alias 选择可能漂移 | 启动期要求唯一 private-key identity |
| trust store 含多个企业 root | PKIX 可能落到非预期 issuer | 先按 issuer SPKI pin 缩窄 trust anchor 再做 PKIX |
| 原地覆盖 keystore | trust/key/pin 非原子、请求阻塞、失败后难回退 | generation 化 immutable transport + 两阶段候选发布 |

## 3. 身份不变量

`ControlPlaneCertificateIdentityPolicy` 只接受两种形态：全空兼容策略，或字段完整的绑定策略。绑定
策略强制：

- client keystore 恰好一个 key entry，证书链至少两层，并以命中独立 issuer pin 的 CA
  作为 `TrustAnchor` 在激活时刻完成 PKIX path validation；
- client leaf 在激活时刻有效，包含 `clientAuth` EKU 和 digital-signature KeyUsage；
- client Subject DN 与配置精确匹配，client 只能携带一个且必须匹配的 workload URI SAN；
- server PKIX trust anchor 先按 server issuer pin 收窄；
- server leaf 包含 `serverAuth` EKU、digital-signature KeyUsage 和精确 server URI SAN；
- policy、异常和 descriptor 不投影 keystore 密码、私钥、路径或实际证书值。

兼容策略保留原有 TLS 行为，且 `certificateIdentityBound=false`，防止旧调用方在未配置身份约束时
获得虚假的安全能力声明。

## 4. 轮换不变量

`RotatingControlPlaneHttpTransport` 的状态只有一个 active generation 和至多一个
pending successor：

1. 初始 generation 必须为正数，且初始证书在最小重叠窗口后仍有效。
2. successor 必须是 `current + 1`；rollback、跳代、第二 pending 和超前窗口越界先于 secret
   resolution 失败。
3. 候选 keystore、trust store、pins 和 identity policy 在状态锁外完整加载；慢 secret manager 或磁盘
   I/O 不阻塞旧代请求。
4. 加载后以第二次锁内比较确认 active 未变化、pending 仍为空且激活时刻未越过，才发布候选。
5. successor 在激活时刻必须有效，且新旧证书在激活后继续满足最小重叠窗口。
6. 稳定 `HttpClient` proxy 每个请求只选择一次 immutable generation；已开始的旧代请求不混入新代
   SSLContext，新请求在激活后获得新代 client。
7. active 已过期且没有可激活 successor 时，在网络 I/O 前 fail closed。

`ControlPlaneCertificateRotationController` 只接受
`ControlPlaneCertificateRotationEvent`：外部 authority 必须对 canonical material fingerprint
形成独立 M-of-N Ed25519 quorum，事件同时绑定 deployment scope、target、前代 settings
fingerprint、连续 generation、候选 settings fingerprint、policy 与时间窗。`materialId` 只能是
不含 URI/path 分隔符的 opaque identifier；路径、secret reference、密码和证书内容留在
`ControlPlaneCertificateRotationMaterialSource` 后方。解析结果的 fingerprint 与签名值不一致时
不会调用 transport。

同一 target 的同一事件并发只执行一次解析和 stage，等待者得到 `REPLAYED`；相同 event id
承载不同 fingerprint、同代冲突、跳代、回退或已有 pending 均 fail closed。激活后控制器把
pending settings fingerprint 提升为新的 predecessor；live target 若在控制器外发生代次或
pending 漂移，则进入 `STATE_OUT_OF_SYNC`。结果协议不携带 material id、settings/policy
fingerprint、异常、路径或 secret reference。

兼容构造器仍保留 process-local 接受状态；产品构造器把
`DatabaseControlPlaneCertificateRotationFloor` 作为持久化授权后置门禁。它以 deployment/target
稳定锁、数据库时钟和同事务 floor/event journal 保证 exact replay
幂等、event-id 与 target-generation 双重唯一、连续前代校验、future successor 单 pending、到期原子
晋升，以及 whole-record fingerprint 篡改失败关闭。启动 baseline 可建立新 floor，或通过完整事件祖先链
重建更高 active head；跳代、同代不同材料和祖先链断裂均拒绝。该 floor 不存放路径、secret ref、密码或
证书内容。

产品状态机先完成签名和候选 fingerprint 验证，再 durable accept，最后把同一 generation/material
stage 到本副本 live target；durable accept 后本地 staging 失败时，同一签名事件可重放修复。重启会先
验证 baseline ancestry，再从受控 catalog 恢复 durable active/pending material。这个顺序关闭了
“live 已切换但 floor 未落盘”的危险窗口，却不能把“数据库已接受”解释为“所有副本已切换”。

`ControlPlaneCertificateRotationFleetPolicy` 与
`DatabaseControlPlaneCertificateRotationConvergenceRepository` 已建立下一层数据库权威事实面：精确清单
不是 discovery 结果；同一 scope 只有一个 live fleet；每个 serving slot/process-start/target 以严格 sequence
报告 exact generation/event/settings/activation identity；重复 startup 不能制造 quorum；外部 inventory
attestation 有 hard expiry、revision floor 与 local downgrade fence。`activationPermitted` 只表示无 live
冲突且 staged/active 唯一 slot 达到 `ALL_REPLICAS` 阈值，`converged` 则必须是清单内每个 slot 恰有一个
exact `ACTIVE`。runtime 为每个受控 target 发布/续租 ACK，并把数据库剩余 lease 截断为最多两个本地
heartbeat 周期的 cached decision；请求路径和 floor 只读本地 gate，不在状态锁中访问数据库。due 事件先由
floor 消费 cached activation proof，monitor 确认 durable active 后才允许 live transport 晋级；重启恢复的
signed active head 也必须重新形成全副本 ACTIVE proof。`FENCED_QUORUM` 在外部 traffic fence 接入前由
配置和 monitor 构造器直接拒绝，而不是作为可误用的运行选项。

## 5. 代码证据

- `ControlPlaneCertificateIdentityPolicy`：证书 profile、Subject/SAN、issuer 与激活时刻校验。
- `PinnedMutualTlsRecoveryFleetPublicationTransport`：受 issuer policy 约束的 trust manager、绑定身份
  descriptor 和可供轮换判定的 client certificate lifetime。
- `RotatingControlPlaneHttpTransport`：两阶段 stage、连续 generation、重叠窗口、
  request-level 原子选择和过期 fail-closed。
- `ControlPlaneCertificateRotationEvent`：严格、payload-free、deployment/target/material-bound
  签名命令；`materialId` 不能成为 URI、路径或 secret reference。
- `ConfiguredControlPlaneCertificateRotationTrustStore`：独立 public-key-only M-of-N Ed25519
  policy、key lifecycle、时间窗与 canonical fingerprint 校验。
- `ControlPlaneCertificateRotationController`：授权结果二次绑定、前代/候选指纹校验、并发幂等、
  event-id 冲突、floor-first durable accept、失败重放修复、激活推进与状态漂移封闭。
- `ControlPlaneCertificateRotationMaterialSource`：把不受信事件与 deployment-owned
  certificate/secret material 隔离。
- `DatabaseControlPlaneCertificateRotationFloor`：数据库时钟、稳定 target 锁、exact event journal、
  whole-record fingerprint、baseline ancestry 验证、重启防回退与同代分叉拒绝。
- `ControlPlaneCertificateRotationRuntime`：统一管理 12 个 stable target id，构造 durable floor，恢复
  active/pending material，并注册 floor-bound controller。
- `ControlPlaneCertificateRotationFleetPolicy`：冻结精确 replica inventory、immutable artifact、协议、
  all-replica/fenced-quorum 阈值、数据库租约和外部 inventory attestation。
- `DatabaseControlPlaneCertificateRotationConvergenceRepository`：database-clock process-start ACK、单 active
  fleet、唯一 slot 计数、严格 sequence、inventory revision/downgrade floor、篡改检测，以及
  activation/convergence 双判定。
- `ControlPlaneCertificateRotationConvergenceMonitor`：自动 ACK/续租、local decision expiry、
  durable-before-live activation、restart ACTIVE re-proof 与 request serving fence。
- `ControlPlaneCertificateRotationActivationAuthority`：floor 事务内只消费本地 cached proof，禁止网络或
  数据库 provider I/O。
- `ControlPlaneCertificateRotationEventPage` 与
  `DatabaseControlPlaneCertificateRotationEventCursor`：连续 page chain、稳定 serving-slot baseline、
  durable staged successor、全部事件成功后的显式 commit，以及 crash/replay/fork 封闭。
- `ControlPlaneCertificateRotationHealth`：投影 bounded local readiness、durable state、convergence
  integration/availability/proof 与 serving readiness；enterprise `productionReady` 继续保持 false。
- `RecoveryFleetPublicationTransportProperties`：对六个身份字段执行全有或全无校验，
  disabled residual、partial binding 和 required-without-binding 均在 secret resolution 前失败。
- `TestRuntimeConfiguration` 与
  `ExternalSequenceAnchorBootstrapRootPublicationRuntimeConfiguration`：将同一 typed policy 接入
  12 组产品 transport，不由各 domain 自行解析证书选择器。
- `application-staging.yml`：对每个已启用 transport 自动要求精确证书身份；
  `application-test.yml` 仅保留显式兼容路径。
- dynamic inventory v3、external anchor v2 与 capability v4 严格 Schema：新版本接收身份
  配置或布尔真值，v2/v1/v3 旧 Schema 保持冻结。
- certificate rotation event v1 与 apply result v1 严格 Schema：字段与 Java protocol 对齐，
  apply result 不投影 TLS material 或 resolver failure details。
- certificate status publication v1 严格 Schema：冻结完整 target inventory、连续 cursor、前序指纹、
  client/server 两类状态，以及 CA event/OCSP/CRL 的 payload-free fingerprint 与 freshness commitment；
  禁止证书正文、responder URL、原始 OCSP/CRL、credential 和 provider exception。
- certificate status floor snapshot v1 严格 Schema：冻结 deployment baseline、durable cursor、数据库接收
  时刻和完整 status head；初始未接收与 live publication 采用互斥形态。
- certificate rotation floor snapshot v1 严格 Schema：仅投影代次、opaque material id、事件 identity、
  fingerprint 与时间，不携带 TLS material location 或 credential。
- certificate rotation configuration v1 严格 Schema：冻结 authority、timing、baseline inventory 与
  material catalog 的 Spring 边界，不允许私钥或 resolved password。
- certificate rotation replica acknowledgement v1 与 convergence snapshot v1 严格 Schema：前者是绑定
  exact rotation identity 的私有 ACK，后者只输出固定基数 aggregate counts/blockers，不携带副本清单、
  TLS material location 或 provider diagnostics。
- convergence configuration v1、monitor descriptor v1 与 runtime descriptor v2 严格 Schema：冻结
  all-replica、外部 inventory、lease bounds、aggregate readiness，并强制 production readiness 为 false。
- event page v1 与 cursor snapshot v1 严格 Schema：冻结 source ordering 和两阶段消费位置；cursor
  projection 不携带 event body、TLS material、secret 或 provider diagnostics。
- health/capability/Tool Studio：投影 `certificateIdentityBound`、signed rotation、durable local readiness
  和固定计数，不返回 Subject、SAN、issuer pin、fingerprint、路径或 secret reference；fleet convergence
  由真实 monitor 状态驱动，production readiness 固定为 false。
- `RecoveryFleetPublicationTlsFixture`：真实 CA、server/client 证书、双向 TLS server 和 client identity
  轮换材料。

## 6. 测试证据

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ControlPlaneCertificateIdentityPolicyTest,\
RecoveryFleetPublicationTransportTest,\
RotatingControlPlaneHttpTransportTest test
```

验收门禁覆盖 controller、transport、durable floor、fleet repository、monitor、strict properties/Schema、
health 与 Tool Studio capability。其核心覆盖：

- 真实 mTLS 下精确 client/server workload identity 成功；
- client Subject、URI SAN、额外 workload URI、issuer、`clientAuth` 缺失在 transport 创建期失败；
- server URI SAN 与 issuer 错误在 HTTP handler 前失败；
- credential 字符在成功和失败路径擦除，descriptor 不泄漏路径/ref/pin；
- 连续 generation 在同一稳定 client 上按时切换 client principal；
- rollback、超前激活、第二 pending 在 secret resolution 前失败；
- 非法候选不扰动 active generation；
- 候选 credential 加载被阻塞时旧代真实 TLS 请求仍成功；
- active identity 过期后请求在 handler 前 fail closed；
- database time 未到、全副本 staged 未满足、durable floor 未晋级时 live generation 均不能切换；
- 新代未形成全副本 ACTIVE proof、restart proof 或 cached lease 已过期时，请求 admission 失败关闭；
- multi-replica local inventory 与无 traffic fence 的 `FENCED_QUORUM` 启动即拒绝；
- ACK 失败、进程关闭、重复 startup、rotation/artifact/policy/protocol 漂移不产生虚假 convergence。

签名轮换控制模块与真实 mTLS rotating transport 进一步覆盖：

- 2-of-N 独立 authority quorum、unknown/revoked key、wrong policy/scope/target、签名和
  canonical fingerprint 篡改、严格 not-before/expiry/lifetime；
- opaque material id 拒绝 URI/path，public trust descriptor 与 apply result 不泄漏 material；
- exact concurrent replay 只解析/stage 一次，event-id/generation 冲突不穿透；
- resolver/stage 失败保持旧代可重试，错误文本不进入结果；
- 激活后 predecessor fingerprint 随 generation 推进，live target 漂移后 fail closed；
- durable accept 严格位于材料验真之后、live stage 之前；floor 拒绝/故障绝不触碰 live target；
- durable accept 后 local stage 失败可由 exact replay 修复；到期 successor 可安全 reconcile；
- 真实数据库重启从受验证祖先链恢复 active generation 和对应 mTLS client identity。

durable floor 另执行 7 项数据库测试；覆盖跨实例精确重建、旧启动清单
回退/跳代/分叉拒绝、future staged 与 database-time 到期晋升、exact replay、event-id reuse、scope/
predecessor 漂移、floor/event journal 篡改失败关闭、同一 target/generation 双副本竞争只有一个赢家，
以及没有 cached fleet admission 时 database time 不能独自推进 floor。

convergence repository 执行 9 项真实数据库测试，monitor 另覆盖 lease expiry、durable-before-live、
restart proof 和 serving gate。覆盖 all-replica 与 fenced-quorum 协议判定、重复 process-start 不制造阈值、
严格 ACK sequence/状态推进、database-time 提前激活拒绝、单 active fleet 并发竞争、外部 inventory
revision 回退/同代分叉/过期/篡改/local downgrade、租约过期、whole-record corruption 与 bounded cleanup。

此前复用 transport 的 79 项联合协议回归和产品接线的 87 项聚焦测试亦全绿；当前轮换聚焦门禁共
74 项，覆盖 typed properties、Spring 组装、真实 TLS、durable restart、Schema 冻结、
health/capability、Tool Studio projection、fleet convergence 与 serving fence。另有 12 项 demo
preflight 验证启停配置和外部 fleet proof 约束。

最终全量门禁：

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
```

验收结果：3700 tests，0 failures、0 errors、2 skips；并成功生成 Spring Boot
可执行产物 `bloge-examples-resource-gateway-1.0.0.jar`。

独立协议客户端门禁 `mvn -f resource-gateway-test-kit/pom.xml clean verify` 同轮执行 230 tests，
0 failures、0 errors、0 skips，并通过普通/shaded JAR、权威 Schema 打包与 public JavaDoc 验证。

## 7. 下一门禁

下一门禁不再重复定义轮换/吊销 JSON 或数据库 cursor，而是把已冻结的 rotation event cursor 接入
CA event watcher，并把 status publication/floor 接入硬过期缓存和逐请求 admission，
随后补齐 convergence SLO/alert、
受控 switch-forward recovery 与独立运维演练。`FENCED_QUORUM` 只有在部署权威能证明缺失副本已
无法继续服务旧 generation 后才可开放。尚未闭合的外部生产责任
包括企业 CA 签发/吊销事件源、OCSP/CRL、HSM 私钥 custody、secret-manager lease、生产数据库迁移与
备份恢复、HA/DR/chaos 和长周期证书生命周期认证。
