# External Notary Trust Rotation Verification

## 1. 结论

本增量关闭 external sequence anchor 的“notary 公钥只能随进程启动加载”断点。suite-stability
与 test-secret 两条业务链现在共享同一套严格协议实现，但保留独立 Spring 端口、scope、持久化
floor、health 与 capability。staging 强制使用 bootstrap-quorum-signed managed trust publication；
静态 notary 公钥只保留为 `test` profile 兼容模式。

这项能力证明的是：在 bootstrap roots 仍可信且 durable floor 未丢失的前提下，notary receipt key
可以免重启轮换、撤销和恢复，旧 trust publication 不能通过应用或数据库回滚复活。它不证明
bootstrap root 自身的在线轮换 ceremony、HSM 托管、notary 服务 HA/DR 或跨组织运营认证。

## 2. 原问题与病根

旧实现把 receipt verification keys 直接放在
`external-anchor.authority-keys-json`。它能严格验证 receipt，但存在三个结构性问题：

1. key A 到 key B 的发布必须重启所有 Resource Gateway 实例，轮换速度受发布窗口约束；
2. deployment rollback 会把进程配置一并回滚到旧 key set，撤销不能形成单调事实；
3. 多副本在滚动发布中可能长期观察不同 key set，unknown key 被误判为 notary 故障。

根治手段不是增加缓存，而是把“receipt trust”本身提升为版本化、签名、可刷新、可持久化防
回滚的控制面对象。

## 3. 协议对象

权威 wire shape 是
[`external-sequence-anchor-trust-publication-v1.schema.json`](schemas/resource-gateway-testing/external-sequence-anchor-trust-publication-v1.schema.json)。

envelope `bloge.externalSequenceAnchorTrustPublication.v1` 包含：

- canonical `material` 与其 `sha256:` fingerprint；
- 按 authority/key 排序、authority 不重复的 bootstrap signatures；
- 不包含 endpoint、ETag、private key、业务 payload、fixture 或 sequence head。

material `bloge.externalSequenceAnchorTrustMaterial.v1` 精确绑定：

- `trustRootSetId + sequence + previousMaterialFingerprint`；
- Resource Gateway `scopeId` 与 external `anchorSetId`；
- 相互独立的 `notaryTrustDomain` 与 `bootstrapTrustDomain`；
- receipt `signatureThreshold`、`maximumFaults` 与完整 notary key lifecycle；
- rotation `policyFingerprint`；
- whole-second `issuedAt / notBefore / expiresAt`。

notary key 只允许 X.509-encoded Ed25519 public material，并携带 `notBefore`、`expiresAt`、
`enabled`、`revoked`。同一 authority 可在交接窗口同时发布 A/B 两把 key，但 authority/key identity
不得重复。

## 4. 不可交换的验证顺序

`ConfiguredExternalSequenceAnchorReceiptTrustStore` 按以下顺序接纳候选 publication：

1. strict JSON、canonical fingerprint 与 exact scope/root/anchor/domain/quorum binding；
2. policy allowlist、whole-second freshness、最大 publication lifetime 与最小剩余有效期；
3. distinct bootstrap authority M-of-N Ed25519 signatures；
4. bootstrap signer 与 notary signer 的 authority id、public key material 双重独立性；
5. 当前时刻至少有 receipt threshold 个 authority 具备 active key；
6. durable floor 原子接纳 sequence、current fingerprint 与 predecessor fingerprint；
7. 完整 immutable key snapshot 才能对 receipt verifier 可见。

顺序不能颠倒。尤其 durable floor 必须位于外部签名和本地 binding 验证之后、publication 可见之前；
否则攻击者可以用无效候选污染 floor，或让未持久化的 generation 短暂参与 receipt 验证。

## 5. 动态刷新与免重启轮换

`DynamicExternalSequenceAnchorReceiptTrustStore` 在构造时同步 bootstrap。成功后只有一个带随机
初始延迟的 daemon lane 执行 refresh：

- HTTPS GET；只允许显式本地测试启用 loopback HTTP；staging 的 shell 与 Java 门禁均拒绝该开关；
- exact media type 与 `X-BLOGE-External-Sequence-Anchor-Trust-Protocol`；
- redirect never、最大 256 KiB、strict duplicate/unknown/trailing JSON；
- ETag conditional request；`304` 更新 source-contact age，但重新执行 signed freshness、active quorum
  与 floor 验证；
- exact idempotent generation 或 `sequence+1` 且 predecessor 精确匹配。

receipt 使用未知 `keyId` 时，调用线程可以触发一次同步 refresh。全 store 共用 refresh lock 与
`unknown-key-refresh-interval`，并在拿锁后重新读取 immutable state，因此并发 unknown-key burst
只产生一次网络请求。若新 publication 合法，当前调用立即用 B key 重试，无需重启；若刷新失败，
所有 receipt verification 立即 fail closed。

旧 snapshot 只保留用于 successor 比较和 aggregate diagnostics，不是 stale-acceptance fallback。
transport、media、parser、signature、binding、lifecycle、floor、sequence 或 active quorum 任一失败，
状态成为 `REFRESH_FAILED`。source age 超界是 `STALE`，signed expiry 或 key lifecycle 不可用是
`EXPIRED`。后续 exact valid successor 可以恢复。

## 6. 跨重启单调 floor

managed publication 复用成熟的 database-backed trust-root floor 存储模型，通过
`ExternalSequenceAnchorTrustPublicationFloor` 做类型隔离适配。floor identity 包含 scope、
trust-root-set、sequence、material fingerprint 与 predecessor fingerprint，并原子拒绝：

- rollback；
- same-sequence fork；
- sequence gap；
- predecessor mismatch。

因此完整进程重启后仍不能接受旧 generation。该声明以 test-runtime database 的持久性与完整性为
前提；如果数据库及其备份可被同一攻击者任意回滚，仍需要外部 WORM/consistency witness。

## 7. Receipt 验证边界

`HttpTestSuiteStabilityExternalSequenceAnchor` 在发起任何 notary 请求前要求 managed trust snapshot
可用，并要求 active authority count 不低于 receipt threshold。endpoint authority set 必须被当前
trust publication 精确覆盖。每份 receipt 的签名和完整 `issuedAt..expiresAt` 窗口由同一 immutable
trust generation 验证；key 只在 enabled、未 revoked 且覆盖完整 receipt lifetime 时有效。

这避免“receipt 签发时 key 有效、过期时间已跨越 key 撤销/过期边界”的半有效状态。

## 8. Spring、启动脚本与配置

配置根分别为：

```text
gateway.testing.stability-jobs.authority.http.jwks.cohort
  .signed-inventory.remote.external-anchor.managed-trust

gateway.testing.test-secrets.authority.http.jwks.cohort
  .signed-inventory.remote.external-anchor.managed-trust
```

关键配置是 `publication-uri`、`trust-root-set-id`、`bootstrap-trust-domain`、accepted policy、
bootstrap threshold/public keys、refresh/timeout/unknown-key cooldown、maximum source age、maximum
publication lifetime、clock skew 与 minimum remaining validity。

staging 不论属性覆盖都要求 managed mode，禁止 static `authority-keys-json`，并拒绝 notary 或 trust
publication 的 insecure loopback。`scripts/visual-canvas-demo.sh` 在 Maven build 和 Java 启动前执行
同构的基础校验；Java composition root 是不可绕过的最终权威。若 trust store bootstrap 后 endpoint
装配失败，composition root 会关闭 owned scheduler，避免失败启动遗留后台线程。

## 9. Capability、Health 与隐私

capability probe 每次从本地 anchor snapshot 重算，不发网络请求。suite-stability 暴露：

- `managedSuiteStabilityExternalNotaryTrust`；
- `restartFreeSuiteStabilityExternalNotaryKeyRotation`；
- `durableSuiteStabilityExternalNotaryTrustFloor`；
- `suiteStabilityExternalNotaryTrustReady`。

test-secret 暴露四个对应的 domain-isolated feature。Actuator health 只增加 trust status、publication
sequence、authority/active authority count、last successful refresh time 与 process-local success/failure
count。URI、ETag、root-set、authority/key id、公钥、签名、policy 和 material fingerprint 均不投影。

## 10. 故障语义矩阵

| 故障 | 结果 | 恢复条件 |
| --- | --- | --- |
| bootstrap source 不可用 | bean 创建失败 | source 与完整 publication 恢复 |
| media/header downgrade 或 redirect | bootstrap/refresh 失败 | exact protocol response |
| bootstrap quorum 不足/签名错 | publication 不可见 | 新的合法签名 envelope |
| bootstrap 与 notary 身份或 key 重叠 | publication 拒绝 | 独立治理域重新发布 |
| active authority 少于 receipt threshold | publication 拒绝 | 恢复足够 active keys |
| rollback/fork/gap/predecessor 错 | durable floor 拒绝 | exact current/idempotent 或合法 successor |
| unknown receipt key | cooldown single-flight refresh | 新 generation 含 active key |
| refresh 失败 | 旧 snapshot 立即不可用于验证 | 后续合法 refresh |
| `304` 但 signed expiry/key lifecycle 超界 | fail closed | 返回新的有效 successor |
| close/shutdown | `CLOSED` | 创建新进程实例 |

## 11. 验证覆盖

聚焦测试覆盖 bootstrap quorum、wrong root、binding mismatch、bootstrap/notary overlap、active quorum、
receipt full-lifetime、rollback/fork/gap across reconstruction、A-to-B unknown-key rotation、12 并发调用
single refresh、invalid successor immediate close/recovery、`304` 不延长签名有效期、strict JSON、真实 HTTP
media/header/ETag/no-redirect、Spring staging static-key rejection、shell preflight、capability 动态重算和
Schema/Java 序列化同构。

完整 Resource Gateway `clean verify` 执行 3202 tests，0 failures、0 errors、2 个条件浏览器跳过，
并成功生成 Spring Boot 可执行 JAR。独立 test-kit `clean verify` 执行 230 tests，0 failures、
0 errors、0 skips，并通过 12 份 testing Schema 打包、普通/shaded JAR 与 public JavaDoc 门禁。

## 12. 明确未宣称

- bootstrap root 的 restart-free 轮换与双控制人 ceremony；
- bootstrap private key 的 HSM/KMS 生产托管；
- notary endpoint mTLS 身份、证书轮换与 PKI 撤销认证；
- notary 服务跨区域 HA、灾备、独立 SLO 与 chaos 认证；
- 多 Resource Gateway 数据库或完整备份回滚下的外部 consistency witness；
- production profile 开放 testing control plane。

下一步不应再扩充本地缓存，而应设计 bootstrap-root ceremony 与独立 consistency witness，使
“谁可以发布下一代 trust”本身也形成双人控制、可审计、可回放的单调工程协议。
