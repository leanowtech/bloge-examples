# Stage 4 bootstrap-root recovery fleet dynamic inventory verification

## 1. 结论与能力边界

本子步把 witnessed recovery-fleet publication 从“可生成、可验签、可落 floor 的协议内核”推进为可嵌入
Resource Gateway 的运行期 authority：

- bounded HTTPS `200/304` refresh，禁止 redirect；
- exact vendor media type + protocol header 协商，拒绝 generic JSON 降级；
- deployment/inventory 与独立 witness 两个信任域的 Ed25519 M-of-N 验证；
- nested inventory、部署 scope、artifact、fleet topology、policy 和完整 lane descriptor 复验；
- publication/witness 双前驱链和 durable database floor；
- `ACTIVE` publication 才解析 caller-reviewed local runtime lane；
- `REVOKED` publication 不依赖已被移除的 runtime lane，仍可推进 floor 并即时关闭 admission；
- floor-before-publish 原子本地可见性、maximum snapshot age 与 refresh failure hard fence；
- aggregate-only descriptor、refresh telemetry 和 Actuator health 真值；
- 复用既有 worker 的 lane 前后、heartbeat 后、cursor commit 前 generation/availability 围栏。
- test/staging strict Spring properties 自动装配、默认 durable database floor 和唯一 reviewed resolver；
- staging 强制 certified dynamic mode，production 物理隔离。

核心实现是 `DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority`；
`ExternalSequenceAnchorBootstrapRootRecoveryFleetDynamicInventoryConfiguration` 提供可选的 test/staging
composition。调用方仍负责提供 public trust keys、唯一 reviewed lane resolver 和固定 deployment binding；
可以接受默认 database floor，也可以替换为唯一 custom durable floor。本步不宣称 production profile、动态
trust-root 轮换、publisher mTLS/pinning、外部 transparency、跨区 Byzantine anchor、非 H2 数据库、
HA/DR/chaos 或容量认证。

## 2. 根因与不变量

static signed inventory 的根本问题不是“少一个定时器”，而是系统没有一个可证明的运行时提交协议。若把
下载成功直接等价为换代，会留下五条高风险旁路：

1. ETag、HTTP 状态或本地缓存可能被误当作治理代际；
2. 有效但更旧的签名 inventory 可在进程或数据库回滚后重新接管；
3. 治理侧已经撤销 lane，但 runtime lane 已先删除，撤销反而因解析失败无法生效；
4. refresh 线程失败后，last-known-good 会无限期继续接单；
5. observation、health、worker 和 cursor commit 可能分别看到不同 generation。

因此实现冻结以下提交顺序：

```text
bounded fetch
  -> strict protocol parse
  -> publication signature and time
  -> independent witness signature and time
  -> predecessor and nested inventory monotonicity
  -> exact inventory signature/binding
  -> ACTIVE-only local runtime reverse binding
  -> durable publication floor accept
  -> atomic in-memory publication
  -> worker generation/availability fences
```

任何一步失败都不得更新本地可用 generation。已验证对象可以保留用于有界诊断，但 refresh state 立即变为
`UNAVAILABLE`，`snapshot()`、worker 新 lane 和 cursor commit 均不可继续使用它。

## 3. Transport 与缓存协议

远端端点必须返回：

- `Content-Type: application/vnd.bloge.bootstrap-root-recovery-fleet-inventory.v1+json`；
- `X-BLOGE-Recovery-Fleet-Inventory-Protocol: bloge.externalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication.v1`；
- 3..258 字节的 strong quoted ETag；
- 最多 512 KiB 的完整 publication JSON。

客户端只接受 HTTPS；`allowInsecureLoopback=true` 仅允许测试中的 localhost HTTP。URI 不得携带 user-info、
query 或 fragment。连接和请求共享有界 timeout，redirect 永远不跟随。`200` 必须有精确协议头、strong
ETag 和有界 body；`304` 也必须重新提供协议头，且不能改变 ETag。

`304` 只复用缓存 bytes 对应的对象，随后仍重新执行时间窗、签名、绑定、runtime descriptor 和 durable
floor 检查。因此 CDN/cache 不能把 ETag 变成 unsigned generation，也不能掩盖本地 runtime drift、key
lifecycle 到期或数据库 floor 腐化。

响应的 media、protocol 与 ETag header 都必须唯一，`304` 不得携带 body。测试 fetch seam 同样强制
`304` ETag 与缓存一致；`200` 若复用旧 ETag，只能返回完全相同的 publication，不能在同一 strong
validator 下替换治理事实。

## 4. 密码学、撤销与 floor

inventory 与 publication 使用固定 public-only deployment key set；witness 使用 authority id 和 public-key
material 都不重叠的独立 key set，不能只换 authority 标签复用同一私钥。两边分别执行 canonical
authority/key ordering、distinct authority threshold、key lifecycle、
signed-at 和 hard validity 验证。publication 最长 24 小时，允许最多 5 分钟 clock skew；witness 不得早于
publication 发行时间超过该 skew，并且必须覆盖 publication 的有效窗口。

每次 candidate 同时执行进程内 predecessor 检查和数据库 floor 检查。后者以
`(deploymentScopeId, fleetId)` 线性化 sequence、publication head、witness head、nested inventory
generation/fingerprint 和 `ACTIVE/REVOKED` state，拒绝 rollback、fork、gap、断链、同代 inventory 漂移、
撤销后同 inventory 重激活和腐化行。旧 v1 row 只有在精确回放已验签双 head 时才能水合为 v2，不能直接
跳到 successor。

`ACTIVE` 先把 signed lane key 映射到 caller-owned reviewed catalog，并反向比对 expected binding、service
binding 与 runtime closure fingerprint；全部匹配后才允许 floor 和本地发布。`REVOKED` 不解析 runtime
lane：治理撤销不应依赖待撤对象仍然存在，但仍必须通过全部密码学、链和 floor 验证。构造时若首份事实为
合法 `REVOKED`，floor 会记录该治理 head，authority 随后因无可用 snapshot 而拒绝启动。

## 5. 刷新、原子可见性与运行期围栏

构造器同步完成首次 refresh，只有可用 `ACTIVE` publication 才启动单线程 daemon scheduler。后台使用
fixed-delay + 半周期到一周期的启动 jitter，避免多副本同时撞击 authority。时间配置必须满足：

| Setting | Bound | Purpose |
| --- | --- | --- |
| `refreshInterval` | 1 秒..1 小时 | 固定延迟刷新周期 |
| `requestTimeout` | 100 毫秒..30 秒 | 单次远端调用上界 |
| `maximumSnapshotAge` | 2 秒..24 小时，且不小于 interval + timeout | 本地 source freshness 硬围栏 |

refresh 成功后使用单次 volatile state replacement 发布 publication、verified inventory、runtime snapshot、
ETag 和 telemetry。读取 `observation()`、`descriptor()`、`snapshot()`、`refreshSnapshot()` 都不做网络或
数据库 I/O；每个聚合读模型都从一次捕获的 state 与 clock instant 派生，不能把旧 inventory generation
和新 publication sequence 拼成 torn view。任何一次 refresh 失败立即把 local state 置为
`UNAVAILABLE`，而不是等待 maximum age；
maximum age 是 scheduler 静默停滞时的第二道硬围栏。

worker 已有的 exact-generation protocol 会在每个 lane 前后、heartbeat 停止后和 cursor commit 前重新读取
authority。于是运行中撤销、换代、refresh 失败或 source 过期最多允许当前受控调用返回，不允许继续下一
lane，也不允许旧 generation 推进 durable cursor；durable cycle 会 abandon 最新 lease。

## 6. 健康与可观测性

authority descriptor 只输出固定基数、无 endpoint/key/policy/fingerprint 的事实，包括 source type、协议、
是否自动刷新、是否支持 signed revocation、publication state/sequence、refresh state、阈值和 floor 能力。
`RefreshSnapshot` 额外给出成功/失败计数、最后成功时间与稳定 failure code，不暴露异常或业务 payload。

inventory health 读取 observation 后再读取 descriptor，并要求 availability、status、generation、lane count 和
source type 完全一致。refresh 恰好发生在两次读取之间时，本次 health 返回 `DOWN/UNAVAILABLE`；这是
刻意的 fail-closed torn-read 防线，不把两个 generation 拼成一个看似健康的视图。下次稳定采样会恢复。

当前 failure code 只区分 `REMOTE_AUTHORITY_UNAVAILABLE`、`REMOTE_DOCUMENT_INVALID` 和
`REMOTE_REFRESH_FAILED`。它足以避免泄密和高基数，但生产接线前仍应增加外部指标/告警路由、SLO、
连续失败窗口和 authority-side correlation，而不是把异常文本暴露到 Actuator。

## 7. Spring 自动装配与 Embedding 用法

推荐的 test/staging 路径只注册一个 caller-owned `LaneResolver`，然后显式启用 dynamic inventory：

```bash
export RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_ENABLED=true
export RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_ID=bootstrap-root-recovery-v1
export RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_PARTITIONS=8
export RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_WORKER_ID="${HOSTNAME}"

export RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_ENABLED=true
export RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_REQUIRED=true
export RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_DEPLOYMENT_SCOPE_ID=tenant-a/staging
export RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_ARTIFACT_FINGERPRINT=sha256:<64-lowercase-hex>
export RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_DOMAIN=tenant-a-recovery-fleet
export RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_ACCEPTED_POLICY_FINGERPRINTS=sha256:<64-lowercase-hex>
export RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_SIGNATURE_THRESHOLD=2
export RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_AUTHORITY_KEYS_JSON='<public-key-array>'
export RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_PUBLICATION_URI=https://governance.example/recovery-fleet/publication
export RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_WITNESS_DOMAIN=tenant-a-recovery-fleet-witness
export RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_WITNESS_SIGNATURE_THRESHOLD=2
export RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_WITNESS_AUTHORITY_KEYS_JSON='<public-key-array>'
```

`application-staging.yml` 默认 `REQUIRED=true`，且启动 preflight 不允许环境变量把它降为 false；test 默认
`REQUIRED=false`，便于 isolated fallback 测试。`allow-insecure-loopback=true` 只允许 test profile 的
localhost HTTP，staging 即使显式配置也拒绝。unknown、duplicate、trailing、private-key-like 字段以及半配置
的 disabled source 都在 floor DDL 和网络调用前失败。系统要求唯一 inventory、resolver 和 floor 候选；
不会用 bean 顺序静默选择或回退。

默认 floor 使用共享 `TestRuntimeDatabase`。需要自定义存储时，只能提供一个
`ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor`，且 `durable()` 必须为 true。
Spring 拥有 dynamic authority 的 refresh scheduler 并在 context 关闭时调用 `close()`；resolver 和 database
继续由 embedding application 持有。

保留的低层 embedding API 适合已有 composition root 的宿主。调用方构造并注册唯一 inventory bean：

```java
@Bean(destroyMethod = "close")
DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority recoveryFleetInventory(
        ObjectMapper mapper,
        TestRuntimeDatabase database,
        ReviewedRecoveryLaneCatalog lanes) {
    var floor = new DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor(
            database.jdbc(), mapper, "tenant-a/staging", "bootstrap-root-recovery-v1",
            database.transactionManager());
    floor.init();
    var binding = new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.VerifiedBinding(
            "tenant-a/staging", "bootstrap-root-recovery-v1",
            "sha256:<64-lowercase-hex-artifact-fingerprint>", 8);
    var settings = new DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.Settings(
            URI.create("https://governance.example/recovery-fleet/publication"),
            Duration.ofSeconds(30), Duration.ofSeconds(3), Duration.ofMinutes(2), false);
    return DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.fromJson(
            mapper, "tenant-a-recovery-fleet", "sha256:<accepted-policy>", 2,
            deploymentPublicKeysJson(), binding, lanes::resolve, floor,
            "tenant-a-recovery-fleet-witness", 2, witnessPublicKeysJson(), settings);
}
```

`fleetId` 与 partition count 必须和 signed binding 精确相同。dynamic authority、resolver 和 database 都由
embedding application 持有；Spring fleet composition 持有 coordinator、worker、scheduler 和 health。
必须为 authority 声明 `destroyMethod="close"` 或等价 lifecycle，避免 application context 关闭后刷新线程
继续运行。

## 8. 验证矩阵

dynamic authority 的 13 项测试使用真实 Ed25519 和真实 JDK `HttpServer` transport，覆盖：

- `ACTIVE` bootstrap、runtime exact resolution、dynamic descriptor、health 和 floor；
- strong ETag、conditional request、严格 `304` 缓存重验、304 换 ETag 与同 ETag 换内容拒绝；
- signed `REVOKED` 不解析 runtime 并立即关闭 snapshot；
- successor inventory generation、运行期 lane drift 和 floor failure；
- refresh failure 即时 fail closed、有界 telemetry、close 幂等；
- publication gap、同代 inventory drift、坏 deployment signature、过早 witness、witness authority/public-key 重叠；
- unknown/duplicate/trailing JSON、unsafe URI、weak ETag、oversize body；
- media/protocol downgrade rejection，以及 refresh 失败后合法 successor 原子恢复。

新增 Spring composition 测试覆盖：真实签名 HTTP bootstrap、默认 database floor、worker/scheduler/health、
context close；disabled fallback；production 物理隔离；staging required 与 insecure-loopback fence；未知/半配置、
缺失或重复 resolver、non-durable custom floor、重复 inventory 候选均在网络或 recovery state 前失败；
inventory health 在两种 configuration 注册顺序下都只装配一个。

recovery-fleet 的 protocol、Schema、floor v2/legacy、worker、coordinator、scheduler、health、Spring
composition、dynamic authority 与后续 capability protocol 共 18 类联合门禁执行 142 tests，0 failures、
0 errors、0 skips。动态
authority、authority SPI、health 与 configured verifier 四个公共类型通过
`javadoc --release 25 -Werror -Xdoclint:all`；新增 configuration 也纳入相同严格门禁，0 warnings、
0 errors；新增 capability 公共类型也独立通过同一严格门禁。`mvn -f resource-gateway-examples/pom.xml clean verify`
全量门禁执行 3493 tests，0 failures、
0 errors、2 条环境条件跳过，并成功生成 Spring Boot 可执行 JAR。

## 9. 剩余工业化门禁

1. capability truth 已由后续子步通过既有 integration endpoint 闭合；运维配置 metadata、外部告警/SLO 和
   跨副本 convergence readiness 仍未完成；
2. deployment/witness trust roots 固定在构造期，未实现 restart-free 双根发布、撤销和 durable key floor；
3. publication floor 只在本地数据库持久化，`externallyAnchored=false`、`byzantineQuorumAnchored=false`；
4. HTTPS 未声明 client mTLS、certificate pinning、代理策略、DNS rebinding 防护和 response-key 热轮换；
5. H2 证明不替代 PostgreSQL/MySQL 的 DDL、锁超时、隔离级别、backup/restore 和 rolling upgrade 认证；
6. 未完成 publisher/witness HA、跨区 gossip/transparency、equivocation 检测、chaos/soak 和外部 SLO；
7. 未实现在线 partition rebalance，signed topology 变化仍需新 fleet identity 或受治理迁移协议；
8. production profile 继续物理缺席，不能因本页通过而解除生产发布门禁。
