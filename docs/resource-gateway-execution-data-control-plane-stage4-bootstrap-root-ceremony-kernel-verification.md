# Stage 4 external-anchor bootstrap-root ceremony kernel verification

## 1. 本步边界

本步只建立 external sequence-anchor bootstrap-root 的 ceremony 协议、完整链重放验证内核和专用
durable floor 契约，不宣称动态远端刷新、Spring/staging 接线、health/capability 或生产 HSM/KMS
托管已经完成。后续步骤必须消费本内核，不能另造一个只验证最新 root snapshot 的旁路。

## 2. 根因

`bloge.externalSequenceAnchorTrustPublication.v1` 已支持 notary receipt key 的免重启轮换，但其
bootstrap roots 仍由进程配置静态注入。结果是：

1. routine notary key 可热轮换，上游 bootstrap root 本身仍需重启；
2. 新副本无法仅凭最新 root snapshot 判断它是否由原 genesis 合法演进；
3. 只让新 root 自签会形成信任接管漏洞，只让旧 root 签名则无法证明新私钥确实受控；
4. 只保存本地最新 key cache 会把完整性依赖重新落回可回滚数据库或文件系统。

## 3. 协议决策

采用有限信任锚和完整交叉签名链：

- deployment 只固定 `bloge.externalSequenceAnchorBootstrapRootGenesis.v1`；
- 每个 successor 使用
  `bloge.externalSequenceAnchorBootstrapRootTransition.v1`；
- preceding root quorum 通过 `authorizingRootSignatures` 授权 successor；
- incoming root quorum 通过 `incomingRootSignatures` 证明新私钥持有；
- 两个 quorum 签署同一个 canonical material fingerprint；
- source 返回从 sequence 1 到 head 的完整
  `bloge.externalSequenceAnchorBootstrapRootBundle.v1`；
- wire history 硬限制为 128 代，达到上限必须执行显式 genesis rollover，禁止静默截断历史。

该方案不制造无限向上的“root of root”。genesis 变更仍是带外 re-bootstrap；当前 root quorum
完全丢失时，软件协议不能凭空恢复信任。

## 4. Canonical 绑定

genesis 精确绑定：

- `scopeId`；
- `rootSetId`；
- `trustDomain`；
- `signatureThreshold` 与 `maximumFaults`；
- 完整排序 root public keys 与 lifecycle；
- genesis ceremony policy fingerprint。

每个 transition 进一步绑定：

- contiguous `sequence`；
- sequence 1 的 genesis fingerprint 或后续 exact predecessor fingerprint；
- 完整 successor key set；
- accepted ceremony policy；
- `issuedAt / notBefore / expiresAt` hard window。

Java 构造边界要求 authority/key 列表规范排序、identity 唯一、Ed25519 public material 有界，且满足
`N >= 3f+1`、`M >= 2f+1`。

## 5. 验证顺序

`ConfiguredExternalSequenceAnchorBootstrapRootTrustStore` 固定执行：

1. genesis 与本地 expected binding 精确相等；
2. bundle genesis fingerprint 与本地 canonical genesis 相等；
3. transition count 不超过本地和 wire 双重上限；
4. 每代 sequence/predecessor/binding/policy/fingerprint/lifecycle 完整；
5. successor 必须在 preceding generation 仍有效时签发；
6. preceding roots 的 distinct-authority authorization quorum 有效；
7. incoming roots 的 distinct-authority proof-of-possession quorum 有效；
8. current head 处于 hard validity window 且 active roots 仍达到 threshold；
9. 只把完整验证后的 head 提交 durable floor；
10. 之后才允许该 immutable root snapshot 验证 managed notary publication。

notary publication 验证再次检查 canonical fingerprint、scope/root-set/bootstrap-domain 绑定和签名
时刻 key lifecycle。未知 root key 与无效签名使用不同的内部 reason；只有前者可以触发受全局
cooldown 约束的同步完整链刷新。

## 6. Durable floor 语义校正

bootstrap-root floor 与旧 serving-inventory floor 的 genesis 语义不同：一个新副本可能首次看到
sequence N，但它已经从 pinned genesis 重放了完整 1..N 链。因此空 floor 允许提交任意已完整验证的
head。floor 非空后要求数据库中的 current head 必须是新 `VerifiedChain` 的 exact ancestor；副本可一次
追赶多个连续 successor，同时仍拒绝 rollback、same-sequence fork 和任意历史祖先分叉。

`DatabaseExternalSequenceAnchorBootstrapRootPublicationFloor` 使用 composite identity lock、
`REQUIRES_NEW`、数据库时钟和 independent whole-record fingerprint 原子提交 head。直接复用“空 floor
只能从 sequence 1 开始”或“每次只能推进一代”的旧实现都是错误的，已经明确禁止。

## 7. 动态完整链刷新

`DynamicExternalSequenceAnchorBootstrapRootTrustStore` 提供：

- strict HTTPS，只有显式 local-test loopback 可使用 HTTP；
- exact media type 与 protocol header；
- no redirect、4 MiB hard body limit、strict duplicate/unknown/trailing parser；
- ETag/`If-None-Match` 与 `304`；
- randomized single daemon refresh lane；
- unknown root key 的 global cooldown single-flight refresh；
- hard source snapshot age 和 signed head expiry 双门禁；
- 任一 transport、protocol、chain、signature、lifecycle 或 floor 失败后立即 fail closed；
- aggregate-only descriptor/snapshot/log，不包含 URI、ETag、root/key id、公钥、签名、policy 或 fingerprint。

`304` 只更新 source contact freshness，不延长 signed head expiry、key lifecycle 或 active quorum。

## 8. Schema

权威 Schema：

`docs/schemas/resource-gateway-testing/external-sequence-anchor-bootstrap-root-bundle-v1.schema.json`

Schema 包含 bundle、transition、material、genesis 和 root-key definitions，仅包含公钥与治理元数据，
拒绝 additional properties，并限制 transitions、keys 和 signatures 基数。

## 9. 测试证据

`ExternalSequenceAnchorBootstrapRootCeremonyTest` 使用真实 Ed25519 key pairs 覆盖：

- 两代旧根授权 + 新根持有证明完整重放；
- head root 对 managed notary publication 的真实验签；
- 错误旧根授权与错误 incoming proof；
- predecessor 断裂和上一代过期后签 successor；
- current head expiry 与 active quorum 下降；
- 跨 reconstruction rollback/fork；
- unknown root key 与 invalid signature 分类；
- root/notary authority 和 public-key overlap；
- strict JSON unknown field 与 wrong pinned genesis；
- 12 并发 unknown-key 请求只产生一次 root bundle refresh；
- invalid signature 不触发 refresh；
- fork refresh 立即 fail closed，随后 exact chain 恢复；
- `304` 不延长 expired signed head；
- 真实 HTTP media/version/ETag 与 no-redirect。

`DatabaseExternalSequenceAnchorBootstrapRootPublicationFloorTest` 覆盖首次 head N、跨多代 catch-up、
reconstruction idempotency、rollback/fork/forked ancestry、identity isolation、record corruption 和并发
first-head linearization。

`ExternalSequenceAnchorBootstrapRootProtocolSchemaTest` 锁定 Java record/Schema 字段同构、协议常量、
128 代上限、`additionalProperties: false` 和 public-only 约束。本步聚焦执行 17 tests，0 failures、
0 errors、0 skips。

## 10. 下一步硬门禁

下一步只有同时完成以下条件才可宣称 restart-free bootstrap-root rotation：

- managed notary trust verifier 消费同一原子 root snapshot；
- suite-stability/test-secret 双域隔离的 Spring/staging/health/capability/script 接线；
- notary/root 双层 refresh 顺序、close ownership 和 failure propagation 测试；
- 配置 downgrade、跨域误注入、staging static-root fallback 测试。
