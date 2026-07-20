# Stage 4 external-anchor bootstrap-root ceremony durable workflow verification

## 1. 本步边界

本步建立 external sequence-anchor bootstrap-root 的 ceremony 协议、完整链重放验证内核、专用
durable floor、动态远端刷新、managed notary 组合、Spring/staging 双域接线、不接触私钥的 producer，
以及可嵌入的数据库权威 maker/checker workflow。新 workflow 已闭合不可变提案、独立审批、数据库
时间、自动续租与单调后继围栏、崩溃接管、精确 signer/heartbeat request 重放、终态幂等、N-1 指纹
迁移、整行完整性校验，以及 signer resolver/descriptor/signature 的本地 wall-clock deadline、固定容量零队列和
lingering-call 观测；`PRODUCED` 与 content-addressed publication outbox 已在同一数据库事务提交，并具备
顺序 claim、退避/attempt budget、旧行回填和 receipt fence；strict HTTPS + Ed25519 signed-response
publisher adapter、固定容量调用监督、数据库驱动 publication service、单 lane scheduler 和冲突永久
quarantine 亦已闭合。它仍不等于带企业 IAM、HSM/KMS、默认部署级 worker、root publisher HA 和外部
审计留存的生产 ceremony 产品。
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

maker/checker proposal、approval、execution claim、heartbeat command/result、durable snapshot 和
bounded execution result 使用：

`docs/schemas/resource-gateway-testing/external-sequence-anchor-bootstrap-root-ceremony-journal-v2.schema.json`

原 v1 Schema 保持原样归档；heartbeat 字段会改变 strict object shape，因此 snapshot 协议明确升为 v2，
禁止在 v1 名义下追加必填字段。

Schema 仅包含公钥、签名和治理元数据，拒绝 additional properties，并限制 transitions、keys、signatures
和 attempts 基数；不包含私钥、credential、provider endpoint、claim token 或异常文本。journal Schema
把 proposal 与 approval timeout 分成不同终态，不能用一个笼统的 `EXPIRED` 掩盖责任边界。

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
128 代上限、`additionalProperties: false` 和 public-only 约束。
`ExternalSequenceAnchorBootstrapRootCeremonyProducerTest` 的 14 项真实 Ed25519 测试覆盖：

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

`DatabaseExternalSequenceAnchorBootstrapRootCeremonyJournalTest` 的 23 项真实数据库测试进一步覆盖：

- 相同 proposal 精确重放、同 ceremony id 内容冲突和同 root-set 单 active workflow；
- proposal timeout 与 approval timeout 的独立终态和后续 workflow 解锁；
- maker/checker 分离、approval request 精确幂等与冲突；
- live lease 排他、过期 takeover、claim version/attempt 单调递增和旧 fence 拒绝；
- heartbeat 后继 fence、最近一次 command 精确重放、同 id 异 intent 冲突和旧 claim 立即失效；
- approval hard deadline 不可续命、新 attempt 清空 heartbeat replay slot，并独立重置有界计数；
- live failure 释放后重试，以及审批已过期时只落确定性 expiry、不接受 post-fence failure 归因；
- terminal outcome 精确重放、changed outcome 冲突和 latest-produced-head 强制连续；
- 精确 v1 row fingerprint 到 v2 的 N-1 迁移、整行离线腐化 fail closed，以及两个并发首提案线性化为
  一个 winner；
- recovery 对无 active、待审批、live lease 的精确分类，两个副本原子竞争只签发一个 fence；
- 数据库时间失败退避、durable automatic-attempt budget、跨副本 policy fingerprint 漂移与离线篡改
  fail closed；
- `PRODUCED` 与 complete-chain publication request 的同事务提交，outbox 冲突时 ceremony 终态整体回滚；
- content-addressed publication id、跨副本 claim 排他、严格 sequence 顺序、exact receipt replay、数据库
  退避/attempt budget、旧 `PRODUCED` 行回填、policy 漂移及 outbox 离线腐化 fail closed。

`ExternalSequenceAnchorBootstrapRootCeremonyServiceTest` 的 14 项端到端测试覆盖非法自动续租 lease
在数据库写入前失败、正常提交与重启重放、
远端实际签名后进程崩溃再接管、审批后 signer cohort 漂移零签名失败，以及临时 quorum outage 后按
同一 approved cohort 重试。慢 signer 测试证明超过原始 lease 后竞争 worker 仍为 `BUSY`，提交使用最新
后继 fence；提交后心跳响应丢失只产生一次 durable renewal，畸形 successor 则丢弃已生成 outcome。
崩溃测试证明每个 content-addressed signer request id 只产生一次真实签名，恢复调用只取得签名端的
精确幂等回放。
审批剩余时间短于配置 lease 时，初始 claim 会直接夹紧到 approval hard horizon；对应测试证明 guard
不会发送必然无法延长的 heartbeat，截止前完成仍可提交，而截止后的终态 CAS 仍由数据库时钟拒绝。
新增的两项 signer deadline 集成测试证明：一个超时旧根按 `UNAVAILABLE` 记录，剩余三根仍可在本地
deadline 内完成 quorum 并提交；两个旧根超时导致 quorum 不足时，incoming signer 完全不调用，workflow
回到 `APPROVED` 且没有 partial outcome。
新增 recovery 测试证明 resolver 在审批和原子 acquire 前零调用，approved cohort 可自动提交；resolver
异常文本不会持久化，resolver timeout 在零签名副作用下释放 fence 并进入数据库退避；真实 fixed-delay
scheduler 自动运行一条 lane，关闭后拒绝新 poll，snapshot 不含 ceremony/authority/key identity。

`ExternalSequenceAnchorBootstrapRootSignerCallSupervisorTest` 的 8 项测试独立覆盖正常 descriptor/signature、
provider 异常脱敏、协作式 interrupt、忽略 interrupt 后的 active/lingering 占位、零队列 saturation、caller
interrupt flag 保留、close 不等待且拒绝新调用、1000 次并发快调用和四路同时 timeout 跃迁中快照不出现
不可能计数，以及 policy 上下界。它验证的是进程内容量与调用方返回边界，不把
`Future.cancel(true)` 解释为远端 HSM 已取消。

producer/Schema 聚焦门禁当前执行 18 tests 全绿；加入 consumer quorum-horizon 回归后，三类核心门禁
执行 32 tests 全绿。此前 ceremony/Schema/database floor 共
执行 20 tests；新增 Spring runtime composition 执行 4 tests。两组配置、capability、health、script 和
兼容性聚焦门禁合计执行 79 次测试调用（包含共享测试类）；跨角色 domain 隔离补强后再执行 17 次
聚焦调用，均为 0 failures、0 errors、0 skips。前一子步完整 Resource Gateway `clean verify` 执行
3231 tests；本 producer 增量最终完整 `clean verify` 执行 3247 tests，0 failures、0 errors、2 skips，
并成功重打包可执行 JAR；本次环境还实际执行了 34 项浏览器回归，而不是把全部浏览器用例条件跳过。
本 durable workflow 增量的 producer/protocol-schema/journal/service 聚焦门禁执行 34 tests，0 failures、
0 errors、0 skips；最终 Resource Gateway `clean verify` 执行 3263 tests，0 failures、0 errors、2 skips，
其中浏览器测试类共 34 项、32 项真实执行，并成功重打包 Spring Boot 可执行 JAR。本次新增及修改的
5 个公共 ceremony 类型另以 `javadoc -Werror -Xdoclint:all` 独立验证，0 warnings、0 errors；全模块
JavaDoc 仍受未触碰旧类型的 16 个既有 doclint errors 阻断，不能误报为全仓 JavaDoc 已清零。

自动 heartbeat/freeze 子步把同组聚焦门禁扩展到 43 tests，0 failures、0 errors、0 skips；最终
Resource Gateway `clean verify` 执行 3272 tests，0 failures、0 errors、2 skips，其中浏览器测试类
共 34 项、32 项真实执行，并成功重打包 Spring Boot 可执行 JAR。本子步涉及的 journal、lease
coordinator、service 与数据库 journal 4 个公共类型以 `javadoc -Werror -Xdoclint:all` 独立验证，
0 warnings、0 errors；该结论仍不外推为全模块 JavaDoc 已清零。

本地 signer call supervision 子步把同组聚焦门禁扩展到 53 tests，0 failures、0 errors、0 skips。
supervisor 与 service 两个公共类型以 `javadoc -Werror -Xdoclint:all` 独立验证，0 warnings、0 errors；
最终 Resource Gateway `clean verify` 执行 3282 tests，0 failures、0 errors、2 skips；Browser DOM
测试类 34 项中 32 项真实执行，browser workflow 1 项真实执行，并成功重打包 Spring Boot 可执行 JAR。

数据库权威后台恢复子步把同组聚焦门禁扩展到 62 tests，0 failures、0 errors、0 skips。新增/修改的
journal、database journal、resolver、service、scheduler 与 supervisor 6 个公共类型以
`javadoc -Werror -Xdoclint:all` 独立验证，0 warnings、0 errors。最终 Resource Gateway `clean verify`
执行 3291 tests，0 failures、0 errors、2 skips；Browser DOM 34 项中 32 项及 browser workflow 1 项
真实执行，并成功重打包 Spring Boot 可执行 JAR。该局部结论不外推为目标数据库、DR 或生产部署认证。

原子 publication outbox 子步把同组聚焦门禁扩展到 68 tests，0 failures、0 errors、0 skips。新增
`ExternalSequenceAnchorBootstrapRootPublicationOutbox` 及 database journal 两个公共类型以
`javadoc -Werror -Xdoclint:all` 独立验证，0 warnings、0 errors。最终 Resource Gateway `clean verify`
执行 3297 tests，0 failures、0 errors、2 skips；Browser DOM 34 项中 32 项及 browser workflow 1 项
真实执行，并成功重打包 Spring Boot 可执行 JAR。该结论证明待发布事实不会跨本地 crash gap 丢失，
不证明远端 publisher transport 已认证。

认证 publication consumer 子步新增严格 machine Schema、静态 Ed25519 响应信任、HTTP
content-addressed idempotency/predecessor conditional、短时签名响应、固定容量零队列调用监督、数据库
fence service、单 lane scheduler 和 durable `QUARANTINED`。本子步与既有 ceremony 组联合聚焦门禁执行
85 tests，0 failures、0 errors、0 skips；完整 Resource Gateway `clean verify` 执行 3314 tests，
0 failures、0 errors、2 skips，Browser DOM 34 项中 32 项及 browser workflow 1 项真实执行，并成功
重打包 Spring Boot 可执行 JAR。边界明确不包含 publisher mTLS/client identity、certificate pinning、
response-key 热轮换、跨 root-set worker platform 或 publisher HA/anti-equivocation。

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

## 12. Producer 与 durable workflow 使用

producer、journal、service、可选 recovery scheduler、publication service/scheduler 都是可嵌入 Java
组件，不新增 Resource Gateway HTTP endpoint 或独立进程；publisher adapter 调用外部 bundle service，
因此现有 `scripts/visual-canvas-demo.sh start|stop` 无需增加本地 ceremony 进程。ceremony service 自有
一个 daemon heartbeat scheduler 和一个固定容量 daemon signer pool；recovery scheduler 另有一条
fixed-delay daemon lane。publication service 自有固定容量零队列 publisher pool，publication scheduler
另有一条 fixed-delay lane。关闭顺序必须是各 scheduler 在前、对应 service 在后。durable 审批场景必须显式配置
`maximumExecutionDelay`，不能拿面向同步调用的 clock-skew 默认值冒充审批窗口：

```java
var producer = new ExternalSequenceAnchorBootstrapRootCeremonyProducer(
        objectMapper, clock, binding, acceptedPolicies, pinnedGenesis,
        Duration.ofMinutes(10));

var recoveryPolicy = new RecoveryPolicy(
        RecoveryPolicy.SCHEMA_VERSION, 5, 300, 20);
var publicationPolicy = new PublicationPolicy(
        PublicationPolicy.SCHEMA_VERSION, 5, 300, 20);
var journal = new DatabaseExternalSequenceAnchorBootstrapRootCeremonyJournal(
        jdbcTemplate, objectMapper, scopeId, rootSetId, transactionManager,
        recoveryPolicy, publicationPolicy);
journal.init();
var signerCalls = new ExternalSequenceAnchorBootstrapRootSignerCallSupervisor.Policy(
        Duration.ofSeconds(5), Duration.ofSeconds(5),
        Duration.ofSeconds(30), 8);
try (var ceremonies = new ExternalSequenceAnchorBootstrapRootCeremonyService(
        producer, journal, signerCalls)) {
    ceremonies.propose(rotationRequest, makerId, 300,
            genesisAuthorities, incomingAuthorities);
    ceremonies.approve(new ApprovalCommand(
            ApprovalCommand.SCHEMA_VERSION, ceremonyId, approvalRequestId,
            checkerId, 300));
    var resolver = (ExternalSequenceAnchorBootstrapRootAuthorityResolver) proposal ->
            new AuthoritySet(resolveOldRootPorts(proposal),
                    resolveIncomingRootPorts(proposal));
    try (var recovery =
            new ExternalSequenceAnchorBootstrapRootCeremonyRecoveryScheduler(
                    ceremonies, workerId, 30, resolver)) {
        var firstPoll = recovery.runOnce();
    }
}

var publisher = HttpExternalSequenceAnchorBootstrapRootPublisher.fromBase64(
        objectMapper, publisherTrustDomain, publisherId, publisherKeyId,
        publisherPublicKeyBase64, publisherKeyNotBefore, publisherKeyExpiresAt,
        publisherUri,
        new HttpExternalSequenceAnchorBootstrapRootPublisher.Settings(
                Duration.ofSeconds(10), Duration.ofSeconds(5),
                Duration.ofSeconds(30), false));
var publisherCalls = new ExternalSequenceAnchorBootstrapRootPublisherCallSupervisor.Policy(
        Duration.ofSeconds(15), 2);
try (var publications = new ExternalSequenceAnchorBootstrapRootPublicationService(
        journal, publisher, publisherCalls)) {
    // 15 s deadline requires at least 17 s; use 30 s for operational margin.
    try (var delivery = new ExternalSequenceAnchorBootstrapRootPublicationScheduler(
            publications, publisherWorkerId, 30)) {
        var firstDelivery = delivery.runOnce();
    }
}
```

`propose` 只做 side-effect-free preflight，并冻结 material fingerprint、sequence、public-only signer cohort
和 exclusive `executionDeadline`。数据库把 proposal/approval deadline 都夹紧到该截止时间；审批者必须
与 maker 不同。`execute` 接受 3..300 秒 lease，取得数据库时间租约后重算完整 preflight，任何 signer
集合或配置漂移都会在签名前失败。signer 调用发生在事务外；进程内 guard 以 lease 三分之一为间隔，
用完整前驱 claim 换取数据库签发的后继 claim。complete/release 前必须 `freeze()` 调度、等待在途
heartbeat 并提交最新 owner/version/until/proposal fence；失去 fence 的调用方看不到刚生成但未提交的
artifact。人工审批后的显式执行继续使用 `execute(ceremonyId, ...)`；无人值守路径使用 root-set-scoped
`recover(workerId, ...)` 或 scheduler，不能自行先 scan 再调用普通 `execute`。

`acquireRecovery` 在一个数据库事务内识别该 journal 唯一 active workflow，持久化过期终态，检查 live
lease、失败退避与 automatic-attempt budget，再签发新 fence。默认失败退避为 5 秒起始、指数增长到
300 秒封顶，最多 20 次自动 acquisition；所有 acquisition（包括人工执行）都会推进 durable attempt
count，因此人工干预不会重置自动预算。policy 的 canonical fingerprint 首次绑定到 root-set lock row；
不同副本配置不一致会 fail startup，变更策略需要显式维护迁移，不能滚动时静默覆盖。

resolver 只在 acquisition 和 heartbeat guard 建立后获得 public-only approved proposal。它返回 opaque
authority ports；service 随后重算完整 preflight 并 exact compare approved cohort，漂移时零签名失败。
resolver 不得返回 credential 或 provider diagnostics；resolution 自身也经过 signer supervisor 的独立
deadline 和零队列容量边界。scheduler 只有一条本地 lane，多副本正确性仍完全依赖数据库 fence，而非
进程内 timer。`Snapshot` 只含 aggregate counters/status，不是 wire protocol，也不是治理 evidence。

`complete` 取得 root-set lock 后，在同一事务内先提交 `PRODUCED` ceremony，再建立 immutable
`PublicationRequest`；任一 outbox 冲突或完整性错误会回滚整个事务。request 的 `publicationId` 由完整
bundle fingerprint 内容寻址，绑定 scope/root-set/ceremony/sequence/predecessor/complete bundle/head。
`acquirePublication` 每次先从所有 `PRODUCED` 行回填缺失 outbox，再完整校验 source/outbox 一致性，只给
最老未发布 sequence 签发数据库 lease；live claim、失败退避、20 次默认自动预算和 policy fingerprint
均由数据库裁决。远端成功后必须以相同 publication id、sequence、bundle/head fingerprint 构造 receipt，
`completePublication` 才能推进 `PUBLISHED`；远端成功、本地提交前崩溃时，下次领取必须向具备 exact
idempotency 的 publisher 重放同一 request。receipt 的 `PUBLISHED/IDEMPOTENT_REPLAY` 是 transport
结果，不参与终态等价；publisher 必须原样返回首次 `publishedAt`，时间变化仍按 receipt conflict 拒绝。
内置 HTTP adapter 把 `publicationId` 放入 `Idempotency-Key`，把 predecessor 放入 `If-Match`，并要求
exact media/protocol/status、strict bounded JSON、canonical request/material fingerprint、publisher/
key/trust binding、短时 freshness 和 Ed25519 signature。`200` 只接受 `PUBLISHED/IDEMPOTENT_REPLAY`；
`409` 只有在签名和 meaningful observed-head conflict 全部成立后才成为
`AUTHENTICATED_CONFLICT`。错误签名、过期、未知字段或不匹配的 `409` 只能进入 `RESPONSE_INVALID` 退避，
无权改变安全状态。权威 wire 定义见
[`external-sequence-anchor-bootstrap-root-publication-v1.schema.json`](schemas/resource-gateway-testing/external-sequence-anchor-bootstrap-root-publication-v1.schema.json)。

publication service 要求 database lease 至少比本地 publisher deadline 长 2 秒；远端成功但 receipt commit
时 fence 已失效，只返回 `FENCE_REJECTED`，由后继 worker 精确重放。publisher timeout 为
100 ms..240 s、固定容量 1..16；默认 30 s/2 slots，使用 `SynchronousQueue`，不建立隐藏 backlog。
忽略 interrupt 的 adapter 会持续占用固定槽位并出现在 lingering counters 中，不能被描述为远端已取消。
普通 unavailable/invalid response 写入数据库时间退避；只有认证 conflict 原子进入 `QUARANTINED`，且
最老 sequence 会永久阻塞后继，直到未来受治理的人工 repair 协议处理。当前没有该 repair 命令，运维方
不得直接改表“解锁”。

resolver、descriptor、signature timeout 与最大并发分别硬限制为 100 ms..300 s、100 ms..300 s、
100 ms..300 s 和 1..32；默认值为 5 s、5 s、30 s、8。signer pool 使用 `SynchronousQueue`，所以所有槽位被占用时立即
`SATURATED`，不会排队。`signerCallSnapshot()` 返回 payload-free policy/counter/occupancy，可用于本地
SLO 和饱和告警；该进程内 projection 没有导出为 wire protocol。调用总时长不是单一 signer timeout：
producer 目前顺序遍历 signer，最坏耗时是 descriptor/signature deadline 的有界和，外层仍由数据库
approval/execution fence 裁决能否提交。

心跳不会推进 attempt count，也不会越过 checker approval 或 proposal `executionDeadline`。每个 attempt
最多提交 10000 次 heartbeat；只保留最近一次 request id 的精确重放槽，以同 id 和不同 intent 重试会
冲突。模糊数据库响应由 coordinator 用相同 command 重试一次；journal 若返回非连续版本、错误 worker、
不匹配 snapshot 或未延长 lease，guard 立即永久失效。record fingerprint 从 v1 升为 v2 时，初始化只
迁移 heartbeat 字段为空、业务内容指纹正确且 legacy fingerprint 精确匹配的旧行，不能借升级修复未知
损坏。

`RotationRequest.expectedPreviousMaterialFingerprint` 是显式 CAS；`issuedAt` 必须在本地 clock-skew 窗口
的未来边界内，历史年龄则受显式 `maximumExecutionDelay` 限制。producer 在调用 signer 前验证 current
chain、policy、生命周期、active quorum、chain bound 和 signer public identity，再对每个 signer 生成
content-addressed request id。每个返回签名会以对应 public key 本地验签；两个 role 都达到 threshold
后，candidate bundle 还必须通过既有 consumer verifier 完整重放。

signer 失败按 `UNAVAILABLE / INVALID_RESPONSE / INVALID_SIGNATURE` 聚合，provider 异常文本不会进入
outcome。允许在 `3f+1 / 2f+1` 策略内带一个坏 signer 成功，但任一 quorum 不足时不返回 partial bundle。
descriptor 的 timeout/failure 仍映射 `SIGNER_BINDING_INVALID`；signature 的 timeout、saturation、close
和 adapter failure 仍映射该 authority 的 `UNAVAILABLE`。这保持 ceremony v1 与 journal v2 strict
Schema 不变，本地细分类只存在 supervisor snapshot。
已被签名端执行的签名仍不能由 Java 回滚，因此 signer adapter 必须把 request id 与完整请求内容一起
持久化：同 id 同内容精确回放，同 id 异内容拒绝。journal 只允许最新 `PRODUCED` outcome 的 exact bundle
作为下一代 predecessor；这防止从仍密码学有效但已陈旧的分支继续演进。

聚焦运行命令：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ExternalSequenceAnchorBootstrapRootSignerCallSupervisorTest,ExternalSequenceAnchorBootstrapRootPublisherCallSupervisorTest,HttpExternalSequenceAnchorBootstrapRootPublisherTest,ExternalSequenceAnchorBootstrapRootCeremonyProducerTest,ExternalSequenceAnchorBootstrapRootProtocolSchemaTest,DatabaseExternalSequenceAnchorBootstrapRootCeremonyJournalTest,ExternalSequenceAnchorBootstrapRootCeremonyServiceTest \
  test
```

## 13. 仍未宣称

当前实现闭合的是消费侧 restart-free chain 与可嵌入 durable workflow kernel，不等于以下部署认证：

- 企业 IAM/PDP 对 maker/checker/worker 的认证授权、职责分离策略、审批撤回和离线 break-glass；
- HSM/KMS adapter、不可导出 private-key custody、mTLS、key attestation、provider HA 和 signer
  idempotency capability attestation；
- provider-confirmed cancellation receipt、独立 worker 进程/container kill、跨进程强制 supervision、
  orphan/provider-side reconciliation 和 provider 级重试预算；当前本地 timeout 只中断 adapter，
  忽略 interrupt 的调用会占用一个固定槽位直到真实返回，但不能形成无界线程/队列，也不能提交过期
  artifact；
- recovery scheduler 接入默认 Spring composition root、跨 root-set 发现/分片、fleet rollout jitter、
  policy 维护迁移、SLO/告警和目标数据库多副本认证；当前完成的是每 root-set 可嵌入的一条 daemon lane，
  不能被描述为部署级 worker platform；
- publisher mTLS/client identity、certificate pinning、静态 response key 热轮换、默认 Spring lifecycle、
  跨 root-set 发现/分片与 fleet SLO；当前 adapter 依赖 JVM HTTPS server trust 并额外验证 Ed25519 响应，
  不等于双向 TLS 或证书 pinning；
- publisher 侧 exact-idempotency conformance 认证、受治理 quarantine repair/abandon、跨区域 HA、独立
  consistency witness、anti-equivocation、journal/outbox retention 与 legal hold；
- transaction-bound security audit、外部 WORM/evidence；当前 whole-record SHA-256 用于发现偶发腐化，
  不是能抵抗拥有数据库写权限攻击者的 keyed 或外部 tamper evidence；
- 本地数据库 floor 与 root publisher 同时回退时的外部不可回退证明；
- 超过 N-1 的正式 migration 编排、PostgreSQL/MySQL 等目标数据库并发、备份恢复、跨区 DR 与长期
  chaos/SLO 认证；当前 DDL/锁语义只由内嵌 H2 测试证明。

这些项目不能由本地 green tests 推导为已完成，仍是进入生产前的独立交付门禁。
