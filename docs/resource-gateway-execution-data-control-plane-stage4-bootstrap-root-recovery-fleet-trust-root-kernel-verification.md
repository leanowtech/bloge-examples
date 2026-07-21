# Stage 4 Recovery Fleet 原子双信任根内核验证

## 1. 结论与边界

本子步关闭 recovery-fleet deployment/witness **运行签名密钥**只能随进程配置、两组密钥可能分代
变更、数据库回退后可能重新接受旧代密钥的协议根因。它交付：

1. 一个由 deployment bootstrap-root quorum 与独立 witness bootstrap-root quorum 共同签署的原子
   双运行密钥发布协议。
2. 一个只在完整绑定、双法定人数、时间窗、策略和密钥独立性全部通过后产生不可变
   `VerifiedKeySet` 的配置验证器。
3. 一个 floor-before-visible 的持久单调代际接口及数据库适配器。
4. 一个 Draft 2020-12 严格 JSON Schema 和 Java/Schema 字段一致性门禁。

本子步提交时只是动态刷新链的可信协议内核，**当时不等于 recovery fleet 已经支持免重启轮换**。后续
[dynamic trust-root increment](resource-gateway-execution-data-control-plane-stage4-bootstrap-root-recovery-fleet-dynamic-trust-root-verification.md)
已关闭前两项运行期缺口：

- 已闭合：严格 HTTPS/ETag source、后台刷新、unknown-key 单飞刷新和 hard-age 监管；
- 已闭合：把 `VerifiedKeySet` 接入 dynamic inventory authority，并在 `304` 后按新根代次重验库存；
- 部分闭合：aggregate health 已有；Spring test/staging composition、capability、演示脚本和 staging
  fail-fast 仍未接线；
- 外部 Byzantine/non-equivocation floor、mTLS/pinning、HSM/KMS、production profile、非 H2/DR/chaos 认证。

两组 bootstrap public keys 仍是部署注入的最终 trust anchor。这里轮换的是高频运行签名密钥，不以
运行密钥自签运行密钥，也不宣称 bootstrap root 自身能够无锚热轮换。

## 2. 根因分析

原实现由构造参数分别接收 deployment 与 witness runtime key/threshold。它在启动时安全，但有四个
系统性问题：

| 症状 | 病根 | 内核修复 |
| --- | --- | --- |
| 密钥吊销需要重启所有副本 | 运行密钥被当作静态部署配置 | 将运行密钥变成短期、签名、可排序的版本化发布物 |
| 两组 key 分别更新形成混代 | 没有共同 canonical generation | 双 key set、双 threshold、四 trust domain 进入同一 material |
| 旧数据库备份可恢复旧 key | 没有独立的单调 key-generation floor | 验证成功后先推进 durable floor，再允许本地观察 |
| 健康接口可能泄露根身份或产生矛盾状态 | 没有最小聚合状态类型 | 只输出状态、代次、期限、阈值和活跃计数，并在构造器约束语义一致性 |

“给两个 `Map` 增加 refresh”不能解决这些问题：两个 map 的加载成功不是原子证明，进程内 CAS 也不能
抵抗完整 fleet restart 后的持久化回退。必须先冻结共同签名材料、提交顺序和跨重启单调性，再接网络
刷新与产品装配。

## 3. 版本化协议

| 对象 | 版本 | 责任 |
| --- | --- | --- |
| 双根发布信封 | `bloge.externalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication.v1` | material fingerprint、deployment-root signatures、witness-root signatures |
| 原子双密钥材料 | `bloge.externalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootMaterial.v1` | root-set、sequence/predecessor、scope/fleet/protocol、四 trust domain、双 threshold/key set、policy、时间窗 |
| 持久 floor 候选 | `bloge.externalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootGeneration.v1` | scope、fleet、root-set、sequence、current/previous material fingerprint |
| 聚合状态 | `bloge.externalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootSnapshot.v1` | key-free readiness、sequence、expiry、threshold、active counts、durable floor |

机器契约见
[recovery-fleet trust-root publication Schema](schemas/resource-gateway-testing/external-sequence-anchor-bootstrap-root-recovery-fleet-inventory-trust-root-publication-v1.schema.json)。
`protocolVersion` 固定绑定
`bloge.externalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication.v1`，v1 verifier 不接受含混的
未来版本或降级版本。

### 3.1 Material 不变量

1. `sequence=1` 时 predecessor 必须为空；后继必须携带精确 `sha256` 前代 material fingerprint。
2. deployment-root、witness-root、deployment-runtime、witness-runtime 四个 trust domain 两两不同。
3. deployment 与 witness runtime authority id 和 X.509 Ed25519 公钥材料不得重叠。
4. 每组 key 按 `authorityId/keyId` 规范排序；重复、空集、超过 64 项或非整秒生命周期均拒绝。
5. 每组 threshold 必须可由该组 distinct authority 满足，范围为 `1..32`。
6. policy fingerprint、scope、fleet、root-set、protocol 与本地期望精确相等。
7. material 的 `issuedAt <= notBefore < expiresAt`；验证器进一步把发布总寿命限制为 24 小时，并只允许
   5 分钟签发时钟偏差。

### 3.2 Envelope 不变量

两组 bootstrap signatures 都签署同一个 canonical `materialFingerprint`。每组签名按
`authorityId/keyId` 排序，同一 authority 不能在一组内重复计数，单组最多 32 项。任一根域缺少 M-of-N
都不能产生运行期 key set。

协议只携带公钥和 detached signatures。Schema 与测试禁止私钥、credential、endpoint、ETag、fixture、
context 和业务 payload 等字段；网络来源与运行诊断属于 transport/runtime 层，不进入签名材料。

## 4. 验证与提交顺序

`ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority` 按以下顺序处理不可信
发布物：

1. 严格 JSON 解析，拒绝 duplicate、unknown 和 trailing token。
2. 重算 canonical material fingerprint。
3. 检查 scope、fleet、root-set、protocol、根 trust domain 与 policy allow-list。
4. 检查发布寿命、当前有效期和时钟偏差。
5. 用独立 bootstrap key set 验证 deployment-root M-of-N。
6. 用另一组独立 bootstrap key set 验证 witness-root M-of-N。
7. 解析并重编码验证 runtime Ed25519 X.509 公钥，检查 deployment/witness authority 与公钥独立性。
8. 构造并防御性冻结一个同时携带两域 key、threshold、sequence、policy 与 generation fingerprint 的
   `VerifiedKeySet`。
9. 向 durable floor 提交 exact generation；floor 成功之前，构造器不会向调用方返回 authority。

因此不存在“floor 失败但新 key 已发布”的本地可见窗口。合法的紧急吊销仍可推进 floor；如果吊销使
任一 runtime threshold 不可满足，snapshot 会变为 `*_THRESHOLD_UNAVAILABLE`，`verifiedKeySet()` 立即
关闭，而不会回退使用旧代 key。

## 5. 持久单调 Floor

`ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor` 是窄接口：它只接受已经完成
密码学与本地绑定验证的 generation，并负责原子拒绝以下状态迁移：

- 非 sequence 1 的首次建立；
- sequence rollback；
- 同 sequence 不同 material fork；
- 跳代；
- 后继 predecessor 与当前 material 不一致；
- scope、fleet 或 root-set 交叉复用。

完全相同 generation 可幂等重放。数据库适配器复用已有 transactionally locked、whole-record-
fingerprinted floor 表，并采用长度前缀命名空间：

```text
bootstrap-root-recovery-fleet/<scope-length>:<deployment-scope>/<fleet-id>
```

这避免 `scope=a/b,fleet=c` 与 `scope=a,fleet=b/c` 的字符串拼接碰撞。记录腐化、数据库不可用和并发
竞争均 fail closed；两个同 sequence 的不同后继并发时只能有一个获胜。

当前数据库 floor 的 `durable=true`，但 `externallyAnchored=false`、
`byzantineQuorumAnchored=false`。它能抵抗进程/完整 fleet 重启，不能抵抗数据库与备份同时被回退；外部
顺序锚仍是 production 硬门禁。

## 6. 嵌入方式

内核调用方先准备两组独立 bootstrap public keys、精确本地 binding、允许的 policy fingerprint 和 durable
floor，再严格解析并验证发布物：

```java
var authority =
        ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
                .fromJson(
                        objectMapper,
                        clock,
                        new ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
                                .ExpectedBinding(
                                deploymentScopeId,
                                fleetId,
                                trustRootSetId,
                                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication
                                        .SCHEMA_VERSION,
                                deploymentRootTrustDomain,
                                witnessRootTrustDomain),
                        acceptedPolicyFingerprints,
                        deploymentRootThreshold,
                        deploymentBootstrapPublicKeys,
                        witnessRootThreshold,
                        witnessBootstrapPublicKeys,
                        new DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor(
                                database, objectMapper, deploymentScopeId, fleetId,
                                trustRootSetId),
                        publicationJson);

var status = authority.snapshot();
```

`snapshot()` 适合内部 health/capability projection；后续子步已接入 aggregate Actuator health，但尚未完成
Spring/capability 产品接线。真正消费运行 key 的 `verifiedKeySet()` 保持 package-private，并已由同包
dynamic inventory authority 集成，避免把 key map 扩散为新的公共 API。

## 7. 自动验证

本子步 15 项聚焦测试全部通过，覆盖：

- 真实 Ed25519 独立双法定人数、错误根签名、bootstrap authority/public-key 重叠；
- binding/policy/protocol 漂移、四域重叠、非法 predecessor、非规范 key 顺序；
- 合法 threshold 轮换、签名紧急吊销推进 floor 但关闭 runtime admission；
- strict unknown/trailing JSON、非 durable floor、不可变 key map 和矛盾 snapshot 拒绝；
- 重建后 rollback、fork、gap、broken predecessor、scope/fleet/root-set 隔离；
- whole-record corruption、数据库 outage、路径元组碰撞和并发同代后继线性化；
- Java record/required/properties/Schema const/外部 signature `$ref` 逐字段一致。

四个新增公共类型通过 `javadoc --release 25 -Werror -Xdoclint:all`，0 warnings、0 errors。
完整组合工作区 `clean verify` 最终执行 3533 tests，0 failures、0 errors、2 个条件浏览器跳过，并成功
重打包 Spring Boot 可执行 JAR。其中最近已提交基线为 3493 项，本信任根子步增加 15 项，并行但未纳入
本提交的 recovery-fleet SLO 工作增加 25 项；因此信任根子步独立计数为 3508，组合工作区计数为 3533。
第一次全量执行有一条既有 quarantine tombstone 测试在 1.1 秒数据库时间边界上偶发失败；该精确测试
立即复跑 1/1 通过，未修改其代码，随后第二次完整 `clean verify` 全绿。该时间敏感测试仍应在后续独立
稳定性增量中消除 wall-clock sleep，而不在本次安全协议提交中夹带修改。

## 8. 后续状态与剩余验收条件

动态 source/consumer 子步已经满足第 1-4 项并关闭 embedding API 的运行期能力：

1. strict HTTPS source 具备精确 media/protocol、no redirect、大小/超时上界、ETag/304 和 hard-age。
2. refresh 按 predecessor 连续推进，unknown inventory key 只触发有 cooldown 的 single-flight 根刷新。
3. dynamic inventory 每次验签记录 exact root generation；根代次变化时即使 inventory 返回 `304` 也重验。
4. refresh/root expiry/revocation/floor outage 立即关闭 recovery admission，旧 snapshot 只作诊断。
5. **待完成**：Spring test/staging 只允许完整 managed mode，禁止与 legacy static runtime keys 混配。
6. **部分完成**：health 已只输出 aggregate truth，JDK HTTP/Ed25519 已验证；capability、真实 Spring 与
   H2 产品接线仍待完成。
7. production 声明继续被 external Byzantine floor、mTLS/pinning、HSM/KMS、目标数据库、HA/DR/chaos
   门禁阻断。
