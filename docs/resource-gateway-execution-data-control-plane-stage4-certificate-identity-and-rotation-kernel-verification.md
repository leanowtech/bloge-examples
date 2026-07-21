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
稳定 target id 通过统一 runtime 恢复 active/pending generation。它仍没有 CA event watcher、逐副本
确认或 fleet convergence proof，因此 capability 明确保持 `replicaConvergenceProven=false`、
`productionReady=false`，不能被解释为企业 PKI 或生产证书轮换已经开放。

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
“live 已切换但 floor 未落盘”的危险窗口，却不能把“数据库已接受”解释为“所有副本已切换”：当前没有
副本清单、逐副本 stage/activate acknowledgement、quorum activation fence 或 convergence SLO。

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
- `ControlPlaneCertificateRotationHealth`：只投影 bounded local readiness、durable state 和目标计数；
  replica convergence 与 production readiness 永远不由本地事实推导。
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
- certificate rotation floor snapshot v1 严格 Schema：仅投影代次、opaque material id、事件 identity、
  fingerprint 与时间，不携带 TLS material location 或 credential。
- certificate rotation configuration v1 严格 Schema：冻结 authority、timing、baseline inventory 与
  material catalog 的 Spring 边界，不允许私钥或 resolved password。
- health/capability/Tool Studio：投影 `certificateIdentityBound`、signed rotation、durable local readiness
  和固定计数，不返回 Subject、SAN、issuer pin、fingerprint、路径或 secret reference；fleet convergence
  和 production readiness 固定为 false。
- `RecoveryFleetPublicationTlsFixture`：真实 CA、server/client 证书、双向 TLS server 和 client identity
  轮换材料。

## 6. 测试证据

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ControlPlaneCertificateIdentityPolicyTest,\
RecoveryFleetPublicationTransportTest,\
RotatingControlPlaneHttpTransportTest test
```

验收结果由本轮 56 项 rotation 聚焦门禁覆盖，0 failures、0 errors、0 skips。其核心覆盖：

- 真实 mTLS 下精确 client/server workload identity 成功；
- client Subject、URI SAN、额外 workload URI、issuer、`clientAuth` 缺失在 transport 创建期失败；
- server URI SAN 与 issuer 错误在 HTTP handler 前失败；
- credential 字符在成功和失败路径擦除，descriptor 不泄漏路径/ref/pin；
- 连续 generation 在同一稳定 client 上按时切换 client principal；
- rollback、超前激活、第二 pending 在 secret resolution 前失败；
- 非法候选不扰动 active generation；
- 候选 credential 加载被阻塞时旧代真实 TLS 请求仍成功；
- active identity 过期后请求在 handler 前 fail closed。

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

durable floor 另执行 6 项数据库测试，0 failures、0 errors、0 skips；覆盖跨实例精确重建、旧启动清单
回退/跳代/分叉拒绝、future staged 与 database-time 到期晋升、exact replay、event-id reuse、scope/
predecessor 漂移、floor/event journal 篡改失败关闭，以及同一 target/generation 双副本竞争只有一个赢家。

此前复用 transport 的 79 项联合协议回归和产品接线的 87 项聚焦测试亦全绿；本轮 56 项覆盖 typed
properties、Spring 组装、真实 TLS、durable restart、Schema 冻结、health/capability、Tool Studio
projection 与 demo preflight。

最终全量门禁：

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
```

验收结果：3619 tests，0 failures、0 errors、2 skips；并成功生成 Spring Boot
可执行产物 `bloge-examples-resource-gateway-1.0.0.jar`。

## 7. 下一门禁

下一门禁从“本地 durable 正确”推进到“fleet 可证明收敛”：引入受治理的 replica inventory、事件分发
cursor、逐副本 `STAGED/ACTIVE/FAILED` acknowledgement、staging quorum/all-replica policy、激活 fence、
离线副本隔离与 convergence SLO；只有独立 verifier 证明 exact target/generation/material cohort 收敛后，
`replicaConvergenceProven` 才能为 true。其后仍需受控 switch-forward recovery。尚未闭合的外部生产责任
包括企业 CA 签发/吊销事件源、OCSP/CRL、HSM 私钥 custody、secret-manager lease、生产数据库迁移与
备份恢复、HA/DR/chaos 和长周期证书生命周期认证。
