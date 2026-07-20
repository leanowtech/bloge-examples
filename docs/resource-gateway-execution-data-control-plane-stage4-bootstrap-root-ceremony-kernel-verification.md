# Stage 4 external-anchor bootstrap-root ceremony kernel verification

## 1. 本步边界

本步建立 external sequence-anchor bootstrap-root 的 ceremony 协议、完整链重放验证内核、专用
durable floor、动态远端刷新、managed notary 组合、Spring/staging 双域接线，以及不接触私钥的
ceremony producer kernel。producer kernel 可以生成并自验 sequence 1 或在完整已验链上追加一代；它
不是带持久化状态机、maker/checker 审批、HSM/KMS 适配和 publisher HA 的完整 ceremony service。
所有消费路径必须使用同一原子 root snapshot，不能另造一个只验证最新 root snapshot 的旁路。

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
6. successor `notBefore` 不得晚于 preceding material expiry 与第 M 个最晚 active-authority key expiry
   的较早者，确保切换不存在必然信任空窗；
7. preceding roots 的 distinct-authority authorization quorum 有效；
8. incoming roots 的 distinct-authority proof-of-possession quorum 有效；
9. current head 处于 hard validity window 且 active roots 仍达到 threshold；
10. 只把完整验证后的 head 提交 durable floor；
11. 之后才允许该 immutable root snapshot 验证 managed notary publication。

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

## 8. Managed notary 组合

`ConfiguredExternalSequenceAnchorReceiptTrustStore` 不再持有第二份 bootstrap-root 密钥和验签实现，
而是只依赖 `ExternalSequenceAnchorBootstrapRootTrustStore` 端口。构造 notary snapshot 和每次验 receipt
都会重检上游 root 可用性与 publication 签名，因此 root 过期、撤销、分叉或刷新失败会立即传播为
`ROOT_UNAVAILABLE`，不能继续使用仍在本地有效期内的旧 notary snapshot。

`DynamicExternalSequenceAnchorReceiptTrustStore` 拥有并关闭动态 root store；notary successor 出现未知
root key 时，由 root store 的全局 cooldown single-flight 路径拉取并完整重放新 root chain，再验证
notary successor。保留的 `StaticExternalSequenceAnchorBootstrapRootTrustStore` 只用于旧 test 配置兼容，
明确不具备完整链、durable root floor 或免重启轮换能力，不能作为 staging/production 降级路径。

## 9. Schema

权威 Schema：

`docs/schemas/resource-gateway-testing/external-sequence-anchor-bootstrap-root-bundle-v1.schema.json`

部署只注入 genesis 时使用独立入口：

`docs/schemas/resource-gateway-testing/external-sequence-anchor-bootstrap-root-genesis-v1.schema.json`

producer command、opaque signer descriptor/request/response、payload-free signer attempt 和 outcome 使用：

`docs/schemas/resource-gateway-testing/external-sequence-anchor-bootstrap-root-ceremony-v1.schema.json`

Schema 仅包含公钥、签名和治理元数据，拒绝 additional properties，并限制 transitions、keys、signatures
和 attempts 基数；不包含私钥、credential、provider endpoint 或异常文本。

## 10. 测试证据

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
- 真实 HTTP media/version/ETag 与 no-redirect；
- notary successor 驱动 root generation 免重启推进；
- root same-sequence fork 使下游 receipt trust 立即 `ROOT_UNAVAILABLE`；
- receipt store 关闭时按 ownership 关闭 root store。

`DatabaseExternalSequenceAnchorBootstrapRootPublicationFloorTest` 覆盖首次 head N、跨多代 catch-up、
reconstruction idempotency、rollback/fork/forked ancestry、identity isolation、record corruption 和并发
first-head linearization。

`ExternalSequenceAnchorBootstrapRootProtocolSchemaTest` 锁定 Java record/Schema 字段同构、协议常量、
128 代上限、`additionalProperties: false` 和 public-only 约束。新增
`ExternalSequenceAnchorBootstrapRootCeremonyProducerTest` 的 12 项真实 Ed25519 测试覆盖：

- sequence 1 与 sequence 2 的旧根授权、新根 possession proof 和最终整链反向验收；
- 相同 command 的 byte-identical bundle 与 deterministic signer request id；
- `f=1` 下一个 unavailable signer 和一个 invalid-signature signer 的 quorum 容错与 attempt 分类；
- 旧根 quorum 不足时不调用 incoming signer；
- stale predecessor、未接受 policy、过长 lifecycle、时钟越界和 signer/key mismatch 在签名前失败；
- 每个 role 最多 32 个 signer 的 Java/Schema 上限与超限零签名副作用；
- 被篡改的 current bundle 不能驱动下一代 ceremony；
- future `notBefore` successor 在 activation instant 可完整验收，但不能晚于旧 root 的 M-of-N quorum
  horizon，从而禁止密码学合法却必然中断服务的信任空窗；
- outcome 不包含 private key、credential 或 provider endpoint。

producer/Schema 聚焦门禁当前执行 18 tests 全绿；加入 consumer quorum-horizon 回归后，三类核心门禁
执行 32 tests 全绿。此前 ceremony/Schema/database floor 共
执行 20 tests；新增 Spring runtime composition 执行 4 tests。两组配置、capability、health、script 和
兼容性聚焦门禁合计执行 79 次测试调用（包含共享测试类）；跨角色 domain 隔离补强后再执行 17 次
聚焦调用，均为 0 failures、0 errors、0 skips。前一子步完整 Resource Gateway `clean verify` 执行
3231 tests；本 producer 增量最终完整 `clean verify` 执行 3247 tests，0 failures、0 errors、2 skips，
并成功重打包可执行 JAR；本次环境还实际执行了 34 项浏览器回归，而不是把全部浏览器用例条件跳过。

## 11. 双域部署接线

Spring 组合根现已在 suite-stability 与 test-secret 两个域分别创建：

1. strict `genesis-json`；
2. 独立 `DatabaseExternalSequenceAnchorBootstrapRootPublicationFloor`；
3. 独立 `DynamicExternalSequenceAnchorBootstrapRootTrustStore`；
4. 拥有该 root store 的 `DynamicExternalSequenceAnchorReceiptTrustStore`；
5. 域专用 external-anchor port 与 Health contributor。

staging 强制 `managed-trust.bootstrap-roots.enabled=true`，并拒绝 legacy
`bootstrap-signature-threshold/bootstrap-authority-keys-json`。genesis 的 scope/root-set/domain 必须与
notary publication binding 精确一致、root 与 notary trust domain 必须不同、root genesis 必须声明
`f>=1`。两个产品域同时启用时，组合根会拒绝任意 notary/root trust domain 的同角色或跨角色复用，
也拒绝相同 `(scopeId, rootSetId)` floor identity；因此不能因配置复制而共享信任命名空间或 rollback
floor。

`bootstrap-roots` 配置组还包括 accepted ceremony policies、strict bundle URI、refresh/timeout/
unknown-key cooldown、hard source age、maximum root lifetime、clock skew、minimum remaining validity
和 maximum transitions。`test` 可显式保留 static root adapter；staging 没有该降级路径。

Actuator 只投影 root status、head sequence、transition/authority counts、head expiry 和 refresh
counters，不投影 URI、ETag、domain、set/key id、public key、signature、policy 或 fingerprint。
Tool Studio capability 对两个域分别公开 managed chain、restart-free rotation、complete genesis
replay、durable floor 和 current readiness；root readiness 为 false 时，整体 notary trust readiness
由新增的 `...ExternalNotaryTrustChainReady` 明确变为 false。既有 notary-only readiness 保留原语义，
避免破坏 test 兼容消费者。所有读取均来自 immutable local snapshot，不触发远程请求。

`scripts/visual-canvas-demo.sh` 在 build/Java startup 前检查 pinned genesis、accepted policies、HTTPS
bundle、legacy fallback、timing bounds、public-only shape 和跨域 alias。Java 组合根再次执行完整严格
解析、密码学、binding、quorum 与 floor 校验；shell 只是更早反馈，不是安全边界。

## 12. Producer kernel 使用与边界

`ExternalSequenceAnchorBootstrapRootCeremonyProducer` 是可嵌入 Java 组件，不新增 HTTP endpoint、后台
线程或常驻进程，因此现有 `scripts/visual-canvas-demo.sh start|stop` 无需增加 ceremony 服务。典型调用
顺序如下：

```java
var producer = new ExternalSequenceAnchorBootstrapRootCeremonyProducer(
        objectMapper, clock, binding, acceptedPolicies, pinnedGenesis);

var first = producer.begin(rotationRequest, genesisAuthorities, incomingAuthorities);
var successor = producer.append(
        first.bundle(), nextRotationRequest, incomingAuthorities, nextAuthorities);
```

`RotationRequest.expectedPreviousMaterialFingerprint` 是显式 CAS；`issuedAt` 必须在本地 clock-skew 窗口
内。producer 在调用 signer 前验证 current chain、policy、生命周期、active quorum、chain bound 和 signer
public identity，再对每个 signer 生成 content-addressed request id。每个返回签名会以对应 public key 本地
验签；两个 role 都达到 threshold 后，candidate bundle 还必须通过既有 consumer verifier 完整重放。

signer 失败按 `UNAVAILABLE / INVALID_RESPONSE / INVALID_SIGNATURE` 聚合，provider 异常文本不会进入
outcome。允许在 `3f+1 / 2f+1` 策略内带一个坏 signer 成功，但任一 quorum 不足时不返回 partial bundle。
这只是“协议产物原子性”：已被 HSM 执行的签名不能由 Java 回滚。完整服务必须以 `ceremonyId` 持久化
command/outcome，确保重试读取既有结果而不是在 clock-skew 窗口外伪造 backdated ceremony。

聚焦运行命令：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ExternalSequenceAnchorBootstrapRootCeremonyProducerTest,ExternalSequenceAnchorBootstrapRootProtocolSchemaTest \
  test
```

## 13. 仍未宣称

当前实现闭合的是消费侧 restart-free chain 和可嵌入 producer kernel，不等于以下部署认证：

- durable ceremony service、maker/checker 双人审批、幂等账本、超时恢复和离线 break-glass；
- HSM/KMS adapter、不可导出 private-key custody、mTLS、key attestation 与 provider HA；
- root bundle publisher 的 mTLS/pinning、跨区域 HA、独立 consistency witness 与 anti-equivocation；
- 本地数据库 floor 与 root publisher 同时回退时的外部不可回退证明；
- PostgreSQL/MySQL 等目标数据库并发、备份恢复、跨区 DR 与长期 chaos/SLO 认证。

这些项目不能由本地 green tests 推导为已完成，仍是进入生产前的独立交付门禁。
