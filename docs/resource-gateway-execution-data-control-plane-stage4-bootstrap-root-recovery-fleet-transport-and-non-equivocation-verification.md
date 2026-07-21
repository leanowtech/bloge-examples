# Stage 4 Recovery Fleet 传输认证与外部非等价锚验证

## 1. 结论

本增量关闭 recovery-fleet test/staging 产品路径中的两个独立断点：

1. inventory publication 与 managed trust-root publication 不再只依赖 JVM trust store；staging
   必须分别使用 PKIX、hostname verification、SHA-256 SPKI pinning 和 mTLS client identity。
2. inventory publication/witness composite head 与 atomic dual-root head 不再只写本地数据库 floor；
   staging 必须先获得 challenge-bound external quorum receipt，再提交本地 durable floor。

两项能力共同降低 MITM、错误 CA、跨源身份复用、本地数据库回滚和多副本 split-view 风险，但不等于
production certification。notary endpoint、notary trust publication 和 bootstrap-root bundle 的专用
mTLS/pinning，HSM/KMS custody，目标数据库、跨区 HA/DR、故障注入和容量认证仍是部署门禁。

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
- inventory 与 trust-root source 必须使用不同的 client-keystore path + credential-reference 组合。
- test 可显式使用 system-trust compatibility adapter；staging 同时要求 `enabled=true` 和
  `required=true`，禁止静默降级。

### 3.2 能力事实

`bloge.externalSequenceAnchorBootstrapRootRecoveryFleetCapability.v3` 为两个 source 分别公开：

- `SystemTrustStore`
- `PrivateTrustStore`
- `ServerSpkiPinned`
- `MutualTls`

这些字段只陈述当前进程使用的聚合策略，不公开 path、pin、certificate subject、secret reference 或
credential。v1/v2 Schema 与 Java 构造兼容面保持冻结。

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

- [dynamic inventory v2 Schema](schemas/resource-gateway-testing/external-sequence-anchor-bootstrap-root-recovery-fleet-dynamic-inventory-configuration-v2.schema.json)
- [external anchor v1 Schema](schemas/resource-gateway-testing/external-sequence-anchor-bootstrap-root-recovery-fleet-external-anchor-configuration-v1.schema.json)
- [capability v3 Schema](schemas/resource-gateway-testing/external-sequence-anchor-bootstrap-root-recovery-fleet-capability-v3.schema.json)

`application-test.yml` 默认关闭强能力，便于迁移测试；`application-staging.yml` 把 dynamic inventory、
managed roots、两个 pinned mTLS transport、external anchor、managed notary trust 和 managed
bootstrap roots 标记为 required，但仍要求部署者显式提供所有身份和公开信任材料。

`scripts/visual-canvas-demo.sh` 在 Maven build 之前执行同方向门禁：

- 两个 transport 均启用且 required；keystore 可读、reference 合法且 secret 可取得；pin 合法；
- 两个 source client identity 不复用；
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

## 6. 失败语义

| 故障 | 结果 |
| --- | --- |
| PKIX、hostname、pin 或 client certificate 失败 | source refresh 失败，旧 snapshot 仅保留诊断价值并受 hard age fence |
| credential ref 缺失或 resolver 多候选 | stateful source 创建前 fail closed |
| inventory/root source 复用同一 client identity 配置 | preflight 失败 |
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
- `RecoveryFleetPublicationTransportPropertiesTest`：disabled/required/partial policy、source identity
  隔离和重复 pin。
- `ExternallyAnchoredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryFloorTest`：external-first、
  predecessor continuity、stream isolation、外部失败零本地写、unsafe adapter 和 Byzantine truth。
- `ExternalSequenceAnchorBootstrapRootRecoveryFleetDynamicInventoryConfigurationTest`：两个独立 CA、server
  certificate 和 client identity 的真实双源 mTLS handshake；同一 durable database 上的两次 Spring
  重建；descriptor/capability/health 真值；共享 client identity 在建表和联网前拒绝；以及双 floor
  persistence、自定义 Byzantine anchor、crash-only 拒绝和 profile 隔离。
- Schema/capability/integration tests：record/schema 字段同构、Spring metadata、v1/v2 冻结、v3
  no-sensitive vocabulary 与 Tool Studio feature projection。
- `VisualCanvasDemoScriptTest`：Bash 语法、staging transport downgrade 和跨 source 共享 client identity
  均在 build 前拒绝，错误输出不包含 secret 值。

标准门禁：

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
```

## 8. 尚未闭合

1. external notary、notary trust publication 和 bootstrap-root bundle 尚未复用本增量的 pinned mTLS
   port；目前仍是严格 HTTPS、内容签名和 challenge receipt，不应宣称 transport pinning 完整闭环。
2. `env:` resolver 适合 demo，不是企业 secret manager；正式部署需提供 Vault/KMS/workload identity
   resolver，并证明 secret rotation、lease、审计和不可回显。
3. client identity “独立”当前按配置引用判定；证书主体、SAN、EKU、issuer policy 与硬件 key custody
   仍需部署级 verifier。
4. external anchor 证明消费者观察一致，不替代 publisher/notary 自身 HA、anti-equivocation、gossip、
   WORM audit 和跨区灾备认证。
5. production profile 仍物理排除该 testing composition；目标数据库方言、容量、长稳、DR 和 chaos
   未通过前不得解除。
