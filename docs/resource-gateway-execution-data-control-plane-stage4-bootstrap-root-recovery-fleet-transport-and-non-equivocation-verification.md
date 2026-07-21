# Stage 4 Recovery Fleet 传输认证与外部非等价锚验证

## 1. 结论

本增量关闭 recovery-fleet test/staging 产品路径中的两个独立断点：

1. inventory publication 与 managed trust-root publication 不再只依赖 JVM trust store；staging
   必须分别使用 PKIX、hostname verification、SHA-256 SPKI pinning 和 mTLS client identity。
2. inventory publication/witness composite head 与 atomic dual-root head 不再只写本地数据库 floor；
   staging 必须先获得 challenge-bound external quorum receipt，再提交本地 durable floor。
3. recovery fleet、test-secret 和 suite-stability 三个 external-anchor domain 的 notary、managed
   receipt-trust publication、complete bootstrap-root bundle 共九条读链路使用同一认证 transport；
   staging 禁止 system-trust fallback，并要求每条链路使用独立 client identity 配置。

两项能力共同降低 MITM、错误 CA、跨源身份复用、本地数据库回滚和多副本 split-view 风险，但不等于
production certification。企业 PKI 签发、吊销与自动轮换，HSM/KMS custody，目标数据库、跨区
HA/DR、故障注入和容量认证仍是部署门禁。

## 2. 根因与边界

旧路径分别存在两个根因：

- 签名保护内容，不自动证明“从正确的服务端、以正确的客户端身份”取得内容；系统 CA 被误签、代理
  配置错误或跨源 client certificate 复用仍可能扩大攻击面。
- 本地 durable floor 只能阻止同一数据库历史回退，不能证明多个数据库副本、恢复点或恶意 publisher
  没有观察到不同 successor。

本增量没有把两者混成一个开关。传输认证由 `RecoveryFleetPublicationTransport` 管理，内容和代次验证
继续由原有 authority 完成，跨故障域顺序由
`ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalSequenceAnchor` 管理。本地 floor 仍保留，形成
external-first + local-durable 的双层提交，而不是把外部服务变成唯一状态源。

## 3. 传输协议

### 3.1 固定不变量

- JDK `HttpClient` 保持 hostname verification，禁止 redirect。
- server certificate chain 必须先通过 PKIX，再至少命中一个 canonical
  `sha256:<64 lowercase hex>` SPKI pin；pin 不能替代 PKIX。
- client identity 必须来自可读的绝对路径 PKCS#12 keystore。
- private trust store 可选；一旦配置，路径与 opaque password reference 必须同时存在。
- 密码不进入 Spring properties、descriptor、health、capability 或日志，只接受 opaque reference。
- 默认 demo resolver 只解析 `env:VARIABLE_NAME`，返回的 `char[]` 在 TLS context 初始化完成或失败后清零。
- publisher、inventory、trust-root 以及九条 external-anchor 读链路中的所有活跃 source 必须使用
  不同的 client-keystore path + credential-reference 组合。
- 每个已启用 transport 必须同时提供精确 client Subject DN、唯一 client URI SAN、
  client issuer SPKI pins、精确 server URI SAN 和 server issuer SPKI pins；六个字段全有或全无。
- client 必须只有一个 private-key identity，并满足 `clientAuth` EKU 与 digital-signature
  KeyUsage；server 必须满足 `serverAuth` EKU 与同等 KeyUsage。
- test 可显式使用 system-trust compatibility adapter；staging 同时要求 `enabled=true` 和
  `required=true`，且从 `enabled` 派生 `certificate-identity-required=true`，禁止静默降级。

### 3.2 能力事实

`bloge.externalSequenceAnchorBootstrapRootRecoveryFleetCapability.v4` 为两个 source 分别公开：

- `SystemTrustStore`
- `PrivateTrustStore`
- `ServerSpkiPinned`
- `MutualTls`
- `CertificateIdentityBound`

这些字段只陈述当前进程使用的聚合策略，不公开 path、pin、certificate subject、secret reference 或
credential。v1/v2/v3 Schema 与 Java 构造兼容面保持冻结。

## 4. 外部顺序协议

### 4.1 两条独立 stream

外部 anchor 使用 domain-separated SHA-256 stream id：

- publication stream 绑定 deployment scope 与 fleet；外部 material 是 deployment publication
  fingerprint 和 witness fingerprint 的 canonical composite。
- trust-root stream 额外绑定 root-set；外部 material 是同一 atomic dual-root generation 的完整
  exact material。

stream id 不泄露 scope、fleet 或 root-set 原值，且不同 fleet/root-set 不共享 predecessor chain。

### 4.2 提交顺序

每次 advance 固定执行：

1. 校验本地 floor durable，读取外部 descriptor。
2. 要求 external anchor available、externally durable、challenge-bound；staging 还要求 Byzantine
   quorum。
3. 以 exact predecessor 向外部 quorum 提交新 head。
4. 只有外部 receipt 成功后才提交本地数据库 floor。

外部失败时本地 floor 写入次数必须为零。外部成功、本地失败会形成可重试的“外部已前进、本地未前进”
状态；重试必须使用相同 exact successor，不允许改写 material。该取舍优先防止本地先提交后外部拒绝
造成不可见 split-view。

### 4.3 Quorum 与动态 trust

staging 要求 `maximumFaults >= 1`，notary 组合满足外部 anchor kernel 的 `3f+1 / 2f+1` 约束。
receipt keys 不允许静态驻留：managed notary trust 必须开启，并从签名 publication 热刷新；其
bootstrap roots 又必须从 pinned genesis 完整重放 root chain。三个 product domain 的 trust domain 和
durable root floor 必须隔离。

## 5. Spring 与启停门禁

权威配置契约：

- [dynamic inventory v3 Schema](schemas/resource-gateway-testing/external-sequence-anchor-bootstrap-root-recovery-fleet-dynamic-inventory-configuration-v3.schema.json)
- [external anchor v2 Schema](schemas/resource-gateway-testing/external-sequence-anchor-bootstrap-root-recovery-fleet-external-anchor-configuration-v2.schema.json)
- [capability v4 Schema](schemas/resource-gateway-testing/external-sequence-anchor-bootstrap-root-recovery-fleet-capability-v4.schema.json)

上述是当前权威版本。dynamic inventory v2、external anchor v1 与 capability v3 继续作为
已发布历史协议冻结，不原地追加身份字段。

`application-test.yml` 默认关闭强能力，便于迁移测试；`application-staging.yml` 把 dynamic inventory、
managed roots、两个 publication transport、external anchor、managed notary trust、managed
bootstrap roots 及其三条读侧 transport 标记为 required，但仍要求部署者显式提供所有身份和公开
信任材料。该规则分别应用于 recovery fleet、test-secret 和 suite-stability domain。

`scripts/visual-canvas-demo.sh` 在 Maven build 之前执行同方向门禁：

- 所有活跃 transport 均启用且 required；keystore 可读、reference 合法且 secret 可取得；pin 合法；
- 所有 control-plane source client identity 全局不复用；
- external anchor、managed trust 与 bootstrap roots 均启用且 required；
- quorum、timing、URI、public-only material 与 insecure-loopback 策略合法。

Spring 是最终权威；脚本是更早、更可读的部署反馈，不替代 record、authority 和 protocol verifier。

### 5.1 Staging publication transport 配置

两条 source 使用相同字段、不同前缀：

| Source | 环境变量前缀 |
| --- | --- |
| inventory publication | `RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRANSPORT_` |
| managed trust-root publication | `RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOT_TRANSPORT_` |

| 后缀 | Staging 约束 |
| --- | --- |
| `ENABLED` | 必须为 `true` |
| `REQUIRED` | 必须为 `true`，防止回退到 system trust |
| `TRUST_STORE_PATH` | 可选；配置时必须是绝对、可读的 PKCS#12 路径 |
| `TRUST_STORE_PASSWORD_REF` | 与 trust store path 同时出现；demo 只接受 `env:VARIABLE` |
| `CLIENT_KEY_STORE_PATH` | 必须是绝对、可读的 PKCS#12 client identity 路径 |
| `CLIENT_KEY_STORE_PASSWORD_REF` | 必须是可解析的 `env:VARIABLE`，不能填写密码原值 |
| `SERVER_SPKI_PINS` | 1..16 个逗号分隔的 canonical `sha256:<64 lowercase hex>` pin |
| `EXPECTED_CLIENT_SUBJECT_DN` | 必须与唯一 client leaf certificate Subject DN 精确一致 |
| `EXPECTED_CLIENT_URI_SAN` | 必须是唯一的 workload URI SAN |
| `CLIENT_ISSUER_SPKI_PINS` | 1..16 个 client issuer canonical SPKI pin，用于缩小 PKIX trust anchor |
| `EXPECTED_SERVER_URI_SAN` | 必须与 server leaf 的 workload URI SAN 精确一致 |
| `SERVER_ISSUER_SPKI_PINS` | 1..16 个 server issuer canonical SPKI pin，用于缩小 PKIX trust anchor |
| `CERTIFICATE_IDENTITY_REQUIRED` | staging 从 `ENABLED` 派生为 `true`；test 仅显式兼容路径可为 `false` |

两个 source 的 `CLIENT_KEY_STORE_PATH + CLIENT_KEY_STORE_PASSWORD_REF` 组合必须不同。部署脚本还要求
引用的环境变量确实存在，但不会打印其值。完整变量清单可通过以下命令查看：

```bash
./scripts/start-visual-canvas-demo.sh --help
```

示意配置只展示引用关系；密码值应由部署 secret 注入机制写入被引用的环境变量，不能进入参数、YAML、
日志或版本库：

```bash
export RG_RECOVERY_INVENTORY_CLIENT_PASSWORD='injected-by-secret-manager'
export RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRANSPORT_CLIENT_KEY_STORE_PASSWORD_REF=env:RG_RECOVERY_INVENTORY_CLIENT_PASSWORD

export RG_RECOVERY_ROOT_CLIENT_PASSWORD='injected-by-secret-manager'
export RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOT_TRANSPORT_CLIENT_KEY_STORE_PASSWORD_REF=env:RG_RECOVERY_ROOT_CLIENT_PASSWORD
```

### 5.2 External-anchor 读侧 transport 配置

三个 domain 使用相同的三组后缀：

| Domain base prefix | 用途 |
| --- | --- |
| `RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_EXTERNAL_ANCHOR` | recovery-fleet 外部顺序锚 |
| `RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR` | test-secret inventory 外部顺序锚 |
| `RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR` | suite-stability inventory 外部顺序锚 |

| Transport suffix group | 远端调用 |
| --- | --- |
| `_TRANSPORT_` | challenge-bound notary append 请求 |
| `_TRUST_TRANSPORT_` | managed receipt-key publication 读取 |
| `_BOOTSTRAP_ROOT_TRANSPORT_` | complete bootstrap-root bundle 读取 |

每组都使用 5.1 表中的 13 个字段。staging 要求三组同时 `ENABLED=true`、`REQUIRED=true`；任一组
缺失、启用 HTTP loopback、复用其他 source 身份，或 secret resolver 候选不唯一，都会在数据库 floor
创建和远端调用前失败。`ExternalSequenceAnchorTransportSecurity` 在 descriptor 与 health 中只投影
`systemTrustStore`、`privateTrustStore`、`serverSpkiPinned`、`mutualTls`、
`certificateIdentityBound` 及两个 source-configured 布尔值。

## 6. 失败语义

| 故障 | 结果 |
| --- | --- |
| PKIX、hostname、pin 或 client certificate 失败 | source refresh 失败，旧 snapshot 仅保留诊断价值并受 hard age fence |
| Subject、URI SAN、issuer、EKU、KeyUsage 或唯一 key 不满足 | transport 创建或 TLS handler 前 fail closed |
| credential ref 缺失或 resolver 多候选 | stateful source 创建前 fail closed |
| 任意两个活跃 control-plane source 复用同一 client identity 配置 | preflight 失败 |
| external anchor 不可用或不持久 | floor 创建前失败 |
| staging anchor 只有 crash-fault tolerance | floor DDL 和 source network 前失败 |
| external advance 失败 | 本地 publication/root floor 不写入 |
| local floor 在 external success 后失败 | admission 不发布新代次；相同 successor 可重试 |
| capability/health 读取异常 | 输出 aggregate unavailable/down，不泄露配置材料 |

## 7. 验证证据

核心测试覆盖：

- `RecoveryFleetPublicationTransportTest`：真实 TLS handshake、正确双向认证、错误 pin、错误 client
  identity、同一 CA 下以显式双 pin 重叠窗口完成 server certificate 轮换、credential character erase、
  无界 timeout 与配置拒绝。
- `RecoveryFleetPublicationTransportPropertiesTest`：disabled residual、required/partial identity policy、
  完整策略降低、source identity 隔离和格式错误在 secret resolution 前失败。
- `ExternallyAnchoredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryFloorTest`：external-first、
  predecessor continuity、stream isolation、外部失败零本地写、unsafe adapter 和 Byzantine truth。
- `ExternalSequenceAnchorBootstrapRootRecoveryFleetDynamicInventoryConfigurationTest`：两个独立 CA、server
  certificate 和 client identity 的真实双源 mTLS handshake；同一 durable database 上的两次 Spring
  重建；descriptor/capability/health 真值；共享 client identity 在建表和联网前拒绝；双 floor
  persistence、自定义 Byzantine anchor、crash-only 拒绝和 profile 隔离；以及 external-anchor 三段
  transport 的 Spring 绑定、staging downgrade fence 和 domain 隔离。
- `HttpTestSuiteStabilityExternalSequenceAnchorTest`、`ExternalSequenceAnchorManagedTrustTest` 与
  `ExternalSequenceAnchorBootstrapRootCeremonyTest`：三个读侧 source 的真实 mTLS client principal、错误
  pin、匿名/system-trust client 在 handler 前失败，以及 payload-free transport projection。
- `VisualCanvasDemoScriptTest`：构建前拒绝 transport downgrade 与 recovery source 身份复用，且错误
  输出不包含解析后的 credential；`TestSecretAuthorityExternalNonEquivocationConfigurationTest` 另证明
  跨 publisher/domain 身份复用会在 secret resolution 和数据库访问前失败。
- Schema/capability/integration tests：record/schema 字段同构、Spring metadata、历史版本冻结、
  capability v4 no-sensitive vocabulary 与 Tool Studio feature projection。

标准门禁：

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
```

验收结果：3619 tests，0 failures、0 errors、2 个条件浏览器跳过，并成功重打包
Spring Boot 可执行 JAR。

## 8. 尚未闭合

1. bootstrap-root publisher 写侧与 external-anchor 三条读侧均已复用 control-plane transport；但这只
   关闭应用层配置和握手闭环，不等于企业 PKI 生命周期认证。写侧验证见
   [publisher transport verification](resource-gateway-execution-data-control-plane-stage4-bootstrap-root-publisher-transport-verification.md)。
2. `env:` resolver 适合 demo，不是企业 secret manager；正式部署需提供 Vault/KMS/workload identity
   resolver，并证明 secret rotation、lease、审计和不可回显。
3. 证书 Subject、URI SAN、EKU/KeyUsage、issuer policy 和唯一 key 已由产品 transport verifier
   强制；但自动签发/吊销、OCSP/CRL、HSM key custody、受信轮换事件与跨副本原子激活
   尚未接入产品协议。
4. external anchor 证明消费者观察一致，不替代 publisher/notary 自身 HA、anti-equivocation、gossip、
   WORM audit 和跨区灾备认证。
5. production profile 仍物理排除该 testing composition；目标数据库方言、容量、长稳、DR 和 chaos
   未通过前不得解除。
