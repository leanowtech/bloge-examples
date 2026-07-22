# Resource Gateway Stage 4 动态 Physical Provider Inventory 验证记录

## 1. 结论

Stage 4 physical-attempt 第十九增量已经把“进程内静态签名 provider inventory”推进为可在
`test`/`staging` 运行的动态 fleet admission 协议：

- 严格 HTTPS/ETag `ACTIVE`/`REVOKED` publication；
- deployment authority 与独立 witness 的双单调签名链；
- publication/witness 的数据库持久化防回滚 floor；
- 由签名 publication 唯一决定期望副本集合的数据库时钟 cohort；
- publication generation 围栏、刷新失败关闭和合法后继无重启恢复；
- 默认关闭且在任何 `production` profile 下物理缺席的 Spring composition；
- 不泄漏 provider、replica、key、fingerprint 或 URI 身份的 Tool Studio capability 与 Actuator health。

这不是 production-ready 声明。数据库 floor 无法抵抗数据库与应用快照同时回滚，静态 bootstrap
trust roots 仍需重启更新，也尚无经过认证的真实 process/container provider、N/N-1 backfill、完整
retention/evidence lifecycle 和生产环境认证。相对两份工业级计划的剩余实质差距估计为 **约 17%**，
仍未进入正负 8% 完成区间。

## 2. 根因与治理手段

此前的静态 inventory 只能证明“某一时刻配置过什么”，不能证明运行时整个 Resource Gateway fleet
正在使用同一份、未撤销且未回滚的 provider 集合。继续在本地增加配置项会形成多个互相竞争的真相源，
尤其会让单副本通过缩小 `expectedReplicaIds` 伪造局部收敛。

| 根因 | 失败表现 | 本增量的根治手段 |
| --- | --- | --- |
| inventory 没有在线 lifecycle | 撤销必须重启，旧 resolver 可继续调用 | 签名 `ACTIVE/REVOKED` publication、自动刷新、完整 generation fence |
| 单签名域可单方面改写历史 | publication 顺序缺少独立见证 | deployment 与 witness 分离的 key/domain/quorum/predecessor chain |
| 只在内存保存最新版本 | 进程重启可接受旧 publication | 先提交数据库 publication/witness floor，再原子发布本地 snapshot |
| 每个副本可自报期望 fleet | 子集可伪装成全集 READY | `expectedReplicaIds` 只来自 deployment 签名 material，本地无覆盖项 |
| replicaId 不能区分并发进程 | 滚动发布重叠或重复启动被覆盖 | `(scope, cohort, replicaId, startupId)` 进程级租约主键 |
| 应用时钟存在偏移 | lease 生死判断跨副本不一致 | heartbeat、read、expiry 和 purge 全部使用数据库时钟 |
| capability 读取可能触发外部 I/O | 控制面探测拖垮 provider/authority | capability、health、cohort 和 descriptor observation 只读本地快照/数据库 |
| 聚合健康可能泄漏部署身份 | 运维接口成为 inventory 枚举面 | 只输出闭集状态、计数、时序和布尔事实 |

## 3. 协议冻结

### 3.1 Publication envelope

`TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.v1` 包含：

- 完整、独立签名的 nested provider inventory；
- scope、cohort、inventory fingerprint 和精确有序 `expectedReplicaIds`；
- monotonic sequence、前驱 fingerprint、有效时间窗和 `ACTIVE/REVOKED` 状态；
- deployment M-of-N Ed25519 signatures；
- 绑定同一 sequence 与 publication fingerprint 的独立 witness checkpoint；
- witness 自己的 predecessor chain 与 M-of-N Ed25519 signatures。

序列 1 必须是无前驱 genesis；后续只能接受当前 head 的精确 successor。排序、去重、状态与 reason
shape、scope/cohort cross-link、fingerprint 和签名 authority 都经过 canonical 校验。

对应严格 Schema：

- [publication v1](schemas/resource-gateway-testing/physical-attempt-provider-inventory-publication-v1.schema.json)
- [durable generation v1](schemas/resource-gateway-testing/physical-attempt-provider-inventory-publication-generation-v1.schema.json)
- [private cohort binding v1](schemas/resource-gateway-testing/physical-attempt-provider-inventory-cohort-binding-v1.schema.json)

三个 Schema 都关闭 `additionalProperties`，限制集合和字符串上界，并由 Java 序列化字段测试锁定。

### 3.2 Transport

Publication endpoint 必须满足：

- `Content-Type: application/vnd.bloge.physical-attempt-provider-inventory-publication.v1+json`；
- `X-BLOGE-Physical-Provider-Inventory-Protocol:`
  `bloge.testSuiteStabilityPhysicalAttemptProviderInventoryPublication.v1`；
- 支持 `ETag`/`If-None-Match` 和无歧义的 `304 Not Modified`；
- 默认只允许 HTTPS，HTTP 仅可通过 test-only loopback escape hatch 使用；
- request timeout、refresh interval、最大 snapshot age 和 1 MiB body 上界固定；
- strict JSON 拒绝未知字段、重复字段和 trailing content。

任意网络、媒体类型、协议头、JSON、签名、时效、fork、gap、rollback、前驱或 floor 错误都会立即把
本地 authority 标记为 unavailable。合法 successor 完成全部验证和 floor 提交后才原子替换 snapshot。

### 3.3 Generation fence

Resolver wrapper 绑定完整 publication generation，而不只绑定 nested inventory fingerprint。因此：

- successor 即使仍包含同一 provider，也会使旧 wrapper 失效；
- `REVOKED` publication 在 provider I/O 前使已解析 wrapper 失效；
- refresh ambiguity、hard age 或 runtime close 同样在下一次 descriptor/observe 前关闭；
- capability、cohort 和 resolver 读取本身不触发远程刷新或 provider I/O。

## 4. Durable Floor

`DatabaseTestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor` 在稳定 scope 内原子保存：

- sequence；
- publication material fingerprint；
- witness material fingerprint；
- 两条 predecessor fingerprint；
- 完整 generation record fingerprint。

它只接受 sequence 1 genesis、当前 generation 的精确幂等重放或当前 head 的精确 successor；拒绝缺失
floor、gap、rollback、fork、错误前驱和篡改行。并发竞争 successor 通过数据库事务线性化为一个 winner。

该 floor 可防止普通进程/整 fleet 重启后的协议回退，但**不能**防止业务数据库及其 floor 一起从旧快照
恢复。这是下一增量必须用外部 append-only/notary anchor 解决的根因，不应由更多本地状态掩盖。

## 5. Exact Database Cohort

`DatabaseTestSuiteStabilityPhysicalAttemptProviderInventoryCohortRepository` 每次 heartbeat/read 都重新从
已验证动态 authority 取得 scope、cohort、expected replicas、source sequence 和 private generation。
本地只拥有 replica id、startup id、artifact fingerprint、provider protocol 和 lease/retention policy。

READY 必须同时满足：

- live row 与签名 expected replica set 精确相等；
- 每个 replica 恰好一个 live process start；
- 所有成员观察同一 expected-set fingerprint、source sequence 和 generation fingerprint；
- artifact、provider protocol 与 inventory availability 一致；
- 没有 missing、unexpected、duplicate、drift、expired 或 corrupt row。

每行保存 canonical record fingerprint。被篡改的行不会参与 READY，并以聚合 corruption blocker 报告。
`withdraw()` 删除当前进程的精确 startup row，即使 publication 已经切换 cohort，也不会遗留 shutdown row。
过期行在有界 retention 后由 heartbeat 事务按数据库时钟清理。

`TestSuiteStabilityPhysicalAttemptProviderInventoryCohortMonitor` 启动时立即注册进程、按固定间隔续约，
close 时撤销精确进程行；heartbeat 必须至少为 250 ms 且不超过 lease 的一半。

## 6. Spring 与产品接线

配置前缀是：

```text
gateway.testing.stability-physical-attempt.provider-inventory
```

完整环境变量模板位于 `application-test.yml` 和 `application-staging.yml`。运行时约束如下：

1. 默认 `enabled=false`，disabled 时不创建任何 inventory/floor/cohort/monitor/health bean。
2. `@Profile("!production & (test | staging)")` 保证任何含 `production` 的 profile 都物理缺席。
3. enabled 时必须恰好存在一个 `TestSuiteStabilityPhysicalAttemptRuntimeAdapterCatalog`。
4. 配置 binding 使用 `ignoreUnknownFields=false`；`expected-replica-ids` 等本地旁路会使启动失败。
5. bootstrap 必须成功取得并验证签名 ACTIVE publication，否则 Spring context 启动失败。
6. floor 与 cohort 必须使用隔离 test-runtime database 和 database transaction manager。
7. health 以 refresh 前后两次 generation observation 夹住 cohort read，避免跨刷新拼接 READY。

要让 Tool Studio 的 `physicalAttemptRuntime` 最终投影为 `READY`，还必须同时启用并满足 terminal
projection 与 observation reconciliation 两条 runtime health。动态 inventory/cohort 自身健康不是对真实
执行链可用性的替代证明。

## 7. 测试证据

本增量新增 36 项测试：

| 测试面 | 新增数 | 关键证明 |
| --- | ---: | --- |
| publication protocol | 5 | canonical envelope、完整副本集、状态 shape、cross-link、签名 canonicality |
| dynamic authority | 9 | bootstrap、ETag、revocation、失败恢复、fork/gap/rollback、hard age、strict JSON、独立 witness、真实 HTTP 协议 |
| durable floor | 6 | 重建、缺失/形状、rollback/fork/gap、篡改、scope 隔离、并发 successor |
| database cohort | 6 | 精确收敛、签名集合扩张、duplicate/unexpected、generation/artifact/protocol drift、篡改、重建 |
| cohort monitor | 2 | immediate heartbeat/renew/withdraw、interval/process identity guard |
| Spring runtime | 6 | disabled、production absent、真实组装、exact catalog、严格安全配置、health 脱敏 |
| Schema contract | 2 | publication/generation/cohort Java 字段一致性、严格边界与 test-kit 打包根 |

聚焦门禁已经通过：

- publication/floor/dynamic/static regression：30 tests，0 failures，0 errors，0 skips；
- cohort/monitor/capability/runtime aggregate：26 tests，0 failures，0 errors，0 skips；
- Schema/publication/Spring runtime：17 tests，0 failures，0 errors，0 skips。

最终不可变 snapshot 证据：

- physical-attempt 聚合门禁：327 tests，0 failures，0 errors，0 skips；
- `resource-gateway-examples clean verify`：4254 tests，0 failures，0 errors，2 skips，耗时 7:40；
- 476 份 Surefire XML 独立汇总与 Maven 一致；49 项 Browser tests 中 47 项执行、2 项条件跳过；
- 39,935,561 bytes 可执行 JAR 包含 42 个 physical provider-inventory class entries，其中包括 runtime
  composition、aggregate health 和 adapter catalog SPI；
- `resource-gateway-test-kit clean verify`：231 tests，0 failures，0 errors，0 skips，25 份 XML，耗时
  11.493s；
- publication、generation floor、cohort binding 三份新 Schema 同时存在于普通 test-kit JAR 和 CLI JAR；
- `git diff --check` 与三个新 JSON Schema 的 `jq empty` 通过。

Maven 仍输出两个既有 BLOGE 本地 artifact POM 警告：`bloge-durable` 与 `bloge-test` 的发布 POM 未给
`bloge-execution-control` 声明版本。依赖已由当前项目解析，未阻断编译或测试，但应在 BLOGE 主仓修复，
避免干净制品仓环境出现传递依赖缺失。

## 8. JavaDoc 状态

新增公共协议、SPI、authority、floor、cohort、monitor、health 和 Spring properties 均带有面向约束的
JavaDoc。项目级 `mvn -DskipTests javadoc:javadoc` 当前仍被 16 个既有、与本增量无关的 JavaDoc 错误
阻断，包括旧 heading sequence、未转义 `<` 和错误 `@param`；新增类型未出现在错误诊断中。该基线债务
必须独立治理，不能通过降低 doclint 或把本轮实现错误归零来掩盖。

## 9. 剩余差距与下一根治增量

| 缺口 | 根因 | 下一步验收条件 |
| --- | --- | --- |
| 外部防回滚锚 | floor 与业务数据库共享故障域 | external-first compare-and-append；本地提交失败可精确重试；数据库快照回滚仍 fail closed |
| managed trust-root 热轮换 | deployment/witness public roots 仍是启动配置 | 双 root-set publication、complete-chain 验证、独立 durable floor、unknown-key bounded refresh |
| N/N-1 backfill | generation fence 保证安全但不保证平滑升级 | 双读/单写阶段、旧 generation 有界 drain、全副本 readiness fence 和 rollback 演练 |
| retention/evidence lifecycle | publication/cohort 只解决准入时真相 | cancellation/observation/projection 证据保留、tombstone、legal hold、备份擦除证明 |
| 真实 provider | 当前只有协议适配和 mock/stub 证明 | process/container adapter、强取消、资源隔离、超时、崩溃恢复和 orphan reconciliation |
| production certification | test/staging 组合不代表企业生产拓扑 | 目标数据库、HA/DR、网络分区、证书轮换、容量、长稳、混沌与审计验收报告 |

优先级上，下一步应先做外部 anti-rollback anchor 和 managed trust-root rotation。它们解决的是“控制面
历史能否被同一故障域整体改写”这一病根；先扩展更多 provider 或 UI 只会把不可证明的运行面做得更大。
