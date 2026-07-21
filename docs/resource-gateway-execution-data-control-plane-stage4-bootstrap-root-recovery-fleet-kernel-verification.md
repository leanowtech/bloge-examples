# Stage 4 bootstrap-root recovery fleet kernel verification

## 1. 本步边界

本步把已闭合的单 root-set recovery service 提升为可嵌入的多 root-set 执行内核，并在兼容进程内
模式之外增加可选的持久化跨副本协调面，新增：

- service/producer 的 immutable public `ExpectedBinding` 投影；
- `ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory` v1；
- exact service/resolver/runtime-binding lane；
- generation rollback 与 same-generation drift 防护；
- `ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker` 的有界 canonical round-robin；
- per-lane runtime failure isolation、payload-free result 和 aggregate runtime snapshot；
- admitted cycle 与 `close()` 的强 quiescence 边界；
- 单 daemon lane、fixed-delay、手工/后台互斥的 fleet scheduler；
- timer/active-cycle 停滞判定与 aggregate-only Actuator health；
- `ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator` 固定分区租约协议；
- 数据库时钟、整行指纹、fleet/lease 双 epoch 与持久化 per-partition cursor；
- active acquire command 重试去重、精确 renewal revision、complete/abandon 栅栏；
- 慢 lane 执行期间的独立 heartbeat，以及跨副本/重建后公平续跑；
- deployment-signed inventory attestation、M-of-N Ed25519 static authority 与 exact runtime catalog
  reverse binding；
- signed `fleetId/partitionCount` 构造 fence、cycle 内 hard-expiry/generation fence 与 aggregate-only
  inventory health。

本步已有可选 static signed inventory 入口，但不是签名动态 publication、远端 refresh/discovery、撤销链、
durable inventory floor、动态 rebalance 或生产 fleet 产品。默认关闭的 test/staging Spring composition 已能
消费 caller-owned local inventory 并装配 authority/coordinator/worker/scheduler/health；它尚未形成
dynamic inventory 自动装配、capability/HTTP 或 production 交付面。

## 2. 根因

单 lane scheduler 的数据库 fence 能保证多个副本不会并发执行同一 ceremony，却不能解决多 root-set 的
工程问题。直接为 N 个 root-set 手写 N 组 scheduler 会产生四类熵：

1. resolver 与 service 可被配置标签串错，错误 lane 会在取得 durable attempt 后才失败；
2. 每个本地 timer 独立抖动，没有全局有界工作预算，root-set 数量直接放大线程和轮询压力；
3. 固定从字典序头部扫描会让异常前缀长期饿死后续 lane；
4. inventory 更新没有代际语义时，回滚或同代换绑可静默改变 signer runtime。
5. 进程内 cursor 在重启后归零，多副本各自轮转会重复扫描热分区并长期遗漏冷分区；
6. acquire 响应丢失若没有 active-command 重试身份，调用方重试可能再占一个分区；
7. 只在 lane 之间续租会让单个慢 lane 穿透外层 lease，随后以过期 revision 错误推进 cursor。
8. replica-local lane 集合仍可被受损副本缩小后自证，无法证明哪一代 inventory 来自部署治理权威；
9. inventory 与 fleet topology 分开配置会把正确 lane 集接到错误 `fleetId/partitionCount`；
10. 没有 hard expiry 与 cycle 内代际复核时，旧授权可永久运行或在换代中继续推进旧 cursor。

因此本步先建立强绑定、代际和公平内核，再接签名 inventory 与跨副本控制面；把远端发现直接塞进 worker
只会重新引入无界 I/O、凭据泄漏和不可审计漂移。

## 3. Lane 强绑定

`Lane` 同时携带：

- 完整 public `ExpectedBinding`；
- reviewed runtime closure 的 canonical `sha256:` fingerprint；
- durable ceremony service；
- exact approved-cohort authority resolver。

构造时必须满足 `expectedBinding.equals(service.expectedBinding())`。scope/root-set 相同的 lane 在一个
generation 内只能出现一次，snapshot 按 `(scopeId, rootSetId)` 规范排序。fingerprint 不是运行凭据或
provider endpoint；它是上层 inventory authority 对已审核 runtime closure 的内容身份声明。

## 4. Inventory 代际

`bloge.externalSequenceAnchorBootstrapRootRecoveryFleetInventory.v1` 要求：

- generation 从 1 开始且只增不减；
- 每代最多 256 个 lane，允许空代以支持受控 drain；
- 同 generation 的 descriptor、service object 和 resolver object 必须完全不变；
- add/remove/rebind/runtime replacement 必须发布更高 generation；
- worker 见到 generation rollback 或同代漂移时，在调用任何该代 lane 前 fail closed。

SPI 的 `snapshot()` 必须是已认证、非阻塞、进程内读取。HTTPS 拉取、签名验证、撤销、IAM 和 refresh
属于 inventory publisher/authority，不允许隐藏在 worker poll 的调用栈中。

### 4.1 Static signed inventory authority

`bloge.externalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation.v1` 以 canonical material
同时绑定 deployment scope、artifact fingerprint、`fleetId`、partition count、generation、0..256 个完整
sorted `LaneDescriptor`、policy 和 whole-second validity window。distinct authority 使用 Ed25519 M-of-N
签名；错误/撤销 key、重复 authority、阈值不足、material fingerprint 漂移、非 canonical JSON 与超过
30 天 lifetime 均 fail closed。

验签完成后只按 signed lane key 从 caller-owned non-blocking local catalog 解析 `Lane`，并把解析结果的
完整 descriptor 与签名值反向比较。authority 每次 snapshot 重检 hard expiry；worker 构造时绑定 signed
topology，并在 lane 前后和 durable cursor commit 前复核 exact signed generation。详细协议、嵌入方式和
反例见 [signed inventory verification](resource-gateway-execution-data-control-plane-stage4-bootstrap-root-recovery-fleet-signed-inventory-verification.md)。

## 5. 公平与有界性

worker 每个 cycle 固定一个 immutable generation，只访问
`min(maximumLanesPerCycle, inventorySize)` 个不同 lane。cursor 保存最后一个实际尝试的 canonical key，
下一 cycle 从严格大于该 key 的 lane 开始，越过尾部后回绕：

```text
inventory = [A, B, C], budget = 2
cycle 1   = [A, B]
cycle 2   = [C, A]
cycle 3   = [B, C]
```

lane 抛出 RuntimeException 时 cursor 仍推进，结果只记录 `runtimeFailure=true`，不保存 exception text；
后续 lane 继续执行。`Error`、inventory unavailable、rollback 或 invariant failure 终止整个 cycle 并进入
`cycleFailureCount`。每个 lane 内部仍由其 journal 原子决定 no-work、approval wait、lease busy、retry
delay、attempt exhaustion 或 acquired fence，fleet worker 不复制这些权威状态。

durable 模式把 canonical lane key 以 length-framed SHA-256 稳定映射到 1..64 个固定分区。数据库按
`lastPartitionId` 循环选择可用分区，每个分区最多一个未过期 owner；不同副本可以并行处理不同分区，
但同一分区只发布一个 scheduling lease。该外层 lease 只控制扫描归属，不授权业务写入；即使租约过期
造成 at-least-once 轮询，lane ceremony journal 的 attempt token 和 write fence 仍是唯一写权限。

## 6. Inventory 变更时的 cursor

新 generation 不把 cursor 重置到字典序头部。worker 对旧 cursor 做 upper-bound 定位：

- cursor lane 仍存在时，从其后继继续；
- cursor lane 已删除时，从第一个更大 key 继续；
- 没有更大 key 时回绕到首 lane；
- 新增到 cursor 之前的 lane 会在本轮回绕后获得机会。

这避免每次 inventory 扩容都让后缀 lane 重新饥饿。兼容 local worker 的 cursor 仍只在进程内；durable
worker 则在每个固定分区保存最后实际尝试的 lane，完成与释放在同一数据库事务提交，重建 coordinator
或 worker 后从 strict successor 继续。更高 inventory generation 会提升 fleet epoch、立即清除旧 owner
并保留 cursor；回滚、同 generation fingerprint 漂移和同 `fleetId` 改 partition count 均 fail closed。
partition count 会改变所有 lane 的映射，因此拓扑迁移必须使用新 `fleetId` 并执行显式切换，不能滚动
修改原 fleet。

## 7. 生命周期、调度与观测

`runCycle()` 和 `close()` 共用 admission monitor。已进入的 cycle 完成前 close 不返回；close 提交后所有
新 cycle 在读取 inventory 前拒绝。worker 不拥有 inventory、service、resolver 或 provider transport，
因此不关闭这些 caller-owned 资源。

`RuntimeSnapshot` 只包含 cycle/lane aggregate counters、active/closed、最新成功 generation 和最近 cycle
状态，不包含 scope、root-set、resolver、authority、key、fingerprint、payload、endpoint 或 exception。
`CycleResult` v2 可向已授权 embedder 返回 bounded lane key、recovery/execution enum 和
`COMPLETED/COORDINATOR_BUSY` disposition；因此“空 inventory 正常完成”和“所有分区正在被其他副本
处理”不会混成同一个空结果。结果不携带 provider diagnostics。

durable worker 为 acquisition 生成 UUID-derived 32-hex command id。相同 active command 的歧义重试返回同一个 exact
lease；owner、duration 或 inventory 漂移的重放被拒绝。独立 daemon heartbeat 以 lease duration 的
约三分之一周期续租，单个慢 lane 不会阻塞心跳；worker 在提交 cursor 前先停止并等待 heartbeat，再用
最新 expiry revision 完成。renewal、generation advance 或 takeover 导致的 stale revision 一律 fenced，
整个 cycle 失败且不发布未提交进度。fatal/invariant failure 会尝试 `abandon` 最新 revision，以尽快释放
分区但不推进 cursor；清理失败作为 suppressed failure 保留，不覆盖原始失败。

`ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler` 只有一条 fixed-delay daemon lane，显式
`runOnce()` 与后台 poll 使用同一个 admission monitor，因此不会在本进程重叠调用 worker。RuntimeException
被计数后允许下一轮恢复；`Error` 在发布 bounded failure snapshot 后终止 periodic future。scheduler 不拥有
worker，关闭顺序必须是 scheduler、worker、各 lane service/resolver。并发 close caller 等待同一个
completion monitor；等待 admitted cycle 时不持有 close monitor，cycle 内 reentrant close 会在进入协调前
被拒绝，避免 close-monitor/cycle-monitor 锁反转。

scheduler 把“线程存在但已不推进”建模为一等状态：active cycle 超过 `maximumCycleDuration`，或 idle
timer 错过 next due 后又超过一个完整 poll interval，`overdue=true`。这是 readiness fence，不冒充远端
provider cancel；真正的 signer deadline、数据库 lease/fence 和 process isolation 仍由下层负责。wall clock
回拨时完成时间夹紧到本轮开始时间，active poll 则合法保留上一 poll 的 completion timestamp。

`ExternalSequenceAnchorBootstrapRootRecoveryFleetHealth` 只读 worker/scheduler immutable snapshot。关闭、
停滞、cycle-wide inventory/invariant failure 和最新 lane failure 均 DOWN；空 inventory、未到 first due、
no-work 和干净 cycle 均 UP。details 只有固定基数计数、状态和容量参数，不包含 lane key、scope、root-set、
worker、resolver、fingerprint、endpoint、payload 或 exception。

`ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryHealth` 独立读取 signed authority 的 immutable
observation，使清单在尚无下一次 scheduler poll 时也能因 expiry 立即 DOWN。它只输出 generation、lane
count、signature count 和真假 capability，不输出 fleet/lane/policy/fingerprint/key/expiry identity。

## 8. 嵌入方式

应用先为每个 root-set 独立构造 durable service 与 resolver，再发布 immutable generation：

```java
var lane = new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.Lane(
        service.expectedBinding(), reviewedRuntimeBindingFingerprint, service, resolver);
ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory inventory = () ->
        new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.Snapshot(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.Snapshot.SCHEMA_VERSION,
                inventoryGeneration, List.of(lane));
try (var worker = new ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker(
        inventory, authenticatedWorkerId,
        new ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.Policy(30, 16));
     var scheduler = new ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler(
             worker,
             new ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler.SchedulePolicy(
                     Duration.ofSeconds(5), Duration.ofSeconds(5),
                     Duration.ofMinutes(10), Duration.ofSeconds(5)))) {
    var firstCycle = scheduler.runOnce();
    var health = new ExternalSequenceAnchorBootstrapRootRecoveryFleetHealth(
            worker, scheduler).health();
}
```

`reviewedRuntimeBindingFingerprint` 和 `inventoryGeneration` 必须来自部署治理权威，不能由 worker 根据
任意运行对象自证。变更 resolver/service 时先发布新 generation，再让 worker 读取；不得复用原 generation。

多个副本共享同一数据库时，可显式增加 durable coordinator：

```java
var coordinator =
        new DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator(
                jdbcTemplate, objectMapper, transactionManager);
coordinator.init();
try (var worker = new ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker(
        inventory, authenticatedReplicaWorkerId,
        new ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.Policy(30, 16),
        coordinator, "bootstrap-root-recovery-v1", 8)) {
    var cycle = worker.runCycle();
}
```

所有副本必须使用同一 `fleetId`、partition count 和 inventory generation/fingerprint。协调表只存公开
lane cursor、owner、命令/租约标识和时间，不存 resolver、credential、provider payload 或异常文本。
若启用 signed authority，worker 构造还会把这两个 topology 值与签名 material 精确比较；完整 JSON
配置示例见 signed inventory verification。

test/staging 应用可注册一个 local inventory bean，并通过
`RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_ENABLED=true` 启用严格 Spring composition。配置会在建表和启动
scheduler 前完成 inventory/topology preflight，与单 root-set scheduler 互斥，且任何 active
`production` profile 都会物理移除这些 beans。完整环境变量、ownership、关闭顺序和 H2 context-rebuild
证明见
[runtime composition verification](resource-gateway-execution-data-control-plane-stage4-bootstrap-root-recovery-fleet-runtime-composition-verification.md)。

## 9. 验证

聚焦命令：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTest,ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinatorTest,DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinatorTest,ExternalSequenceAnchorBootstrapRootRecoveryFleetWorkerTest,ExternalSequenceAnchorBootstrapRootRecoveryFleetDurableIntegrationTest,ExternalSequenceAnchorBootstrapRootRecoveryFleetSchedulerTest,ExternalSequenceAnchorBootstrapRootRecoveryFleetHealthTest \
  test
```

当前新增 14 项测试，覆盖 canonical order、binding mismatch、fingerprint、duplicate lane、schema/generation、
三 cycle 公平、poison lane、rollback、descriptor/runtime 同代漂移、跨代 cursor、acquired status、inventory/
fatal failure、空 generation、幂等 close 和 admitted-cycle quiescence；聚焦门禁为 14 tests，0 failures、
0 errors、0 skips。

runtime 子步新增 scheduler 13 项与 health 6 项测试，覆盖自动失败后续跑、fatal future 终止、显式 poll
互斥、close quiescence/非所有权/关门后无等待拒绝、reentrant close 与并发 close 锁反转、timer/active 两类 overdue、
wall-clock 回拨、active poll timestamp、policy/snapshot 反例、六类 readiness 和 diagnostics 脱敏。fleet
四类聚焦门禁现执行 33 tests，ceremony/publication/fleet 联合聚焦门禁执行 148 tests，均为 0 failures、
0 errors、0 skips。fleet inventory、
worker、scheduler 与 health 4 个公共类型通过 `javadoc --release 25 -Werror -Xdoclint:all` 独立验证，
0 warnings、0 errors；该结论不外推为全模块 JavaDoc 已清零。

durable fleet 子步新增 21 项测试；fleet 七类聚焦门禁现执行 54 tests，覆盖 fingerprint/partition
canonicality、固定拓扑、active-command 重试去重、数据库循环分配、全部 busy、精确 renewal/stale fence、generation
advance/rollback/drift、过期 takeover、并发单赢家、cursor/整行损坏 fail closed、abandon 不推进 cursor、
慢 lane 后台 heartbeat、fatal cleanup，以及共享数据库下多副本与重建后 per-partition cursor 续跑，均为
0 failures、0 errors、0 skips。inventory、coordinator、database coordinator、worker、scheduler 与 health
六个公共类型通过 `javadoc --release 25 -Werror -Xdoclint:all` 独立验证，0 warnings、0 errors；该结论
不外推为全模块 JavaDoc 已清零。

联合门禁在真实时钟跨过旧 fixture 的固定绝对时间后，暴露 ceremony service 与 database journal 测试把
JVM 固定时钟和数据库 `CURRENT_TIMESTAMP` 混用，导致截止时间随日历失效。两组 fixture 现均在每个
test 的隔离数据库建立后读取 `CURRENT_TIMESTAMP`，规整为协议允许的整秒 canonical 基准，再据此构造
key lifecycle、proposal、approval、preflight 与 outcome；原 15 项 service 和 27 项 journal 测试因此可在
任意日期重复执行，生产数据库仍是 lease/deadline 唯一时间权威。

第十六子步冻结源码的完整 Resource Gateway `clean verify` 执行 3398 tests，0 failures、0 errors、2 skips；
Browser DOM 34 项中 32 项及 browser workflow 1 项真实执行，并成功重打包 Spring Boot 可执行 JAR。

signed inventory 子步新增 authority 10 项、inventory health 4 项、strict Schema 3 项与 worker 4 项测试，
共 21 项；signed-inventory/worker 四类聚焦门禁执行 37 tests，0 failures、0 errors、0 skips。新增四个
public inventory-authority 类型与修改后的 worker 进入严格 JavaDoc 门禁。

runtime composition 子步新增 11 项 Spring/H2 测试，覆盖 profile/default isolation、strict properties、
mutual exclusion、state-before-table preflight、durable context rebuild、signed authority health 和 close
ownership。fleet 与既有 single-lane configuration 的联合门禁执行 95 tests；其中 fleet 范围 86 tests，
均为 0 failures、0 errors、0 skips。11 个相关公共类型通过严格 JavaDoc 门禁，0 warnings、0 errors。
本增量最终完整 Resource Gateway `clean verify` 执行 3430 tests，0 failures、0 errors、2 skips；Browser DOM
34 项中 32 项及 browser workflow 1 项真实执行，并成功重打包 Spring Boot 可执行 JAR。

## 10. 仍未宣称

- signed dynamic inventory publication、witness、revocation、refresh convergence 与 durable generation floor
  （static signed attestation 与 hard expiry 已闭合）；
- enterprise IAM/PDP 对 lane membership、worker、resolver 和 runtime fingerprint 的授权；
- 在线 partition-count 变更、自动 rebalance、priority、带权 fleet-wide fairness 与 rollout jitter；
- dynamic inventory 自动装配、capability/HTTP 和 production profile（test/staging composition 已闭合）；
- publication fleet、publisher mTLS/pinning、response-key hot rotation 与 anti-equivocation；
- HSM/KMS custody、provider-confirmed cancellation/process isolation；
- PostgreSQL/MySQL 并发、multi-region HA、DR/chaos/soak 和外部 SLO 认证。

这些缺口是下一增量的输入，不能由固定分区协调器的 green tests 推导为已完成。当前数据库实现只在本仓
H2 test-runtime 上验证，尚未获得 PostgreSQL/MySQL 方言、锁等待/statement timeout、连接池耗尽、跨 AZ
时延和 failover 认证；部署方在此之前不得把它标成 production-ready fleet service。
