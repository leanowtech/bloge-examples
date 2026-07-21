# Stage 4 bootstrap-root recovery fleet SLO verification

## 1. 结论与诚实边界

本子步把 recovery fleet 从“能看到当前 ready/failure”推进到“能以稳定协议判断单副本是否持续可靠”。新增：

- `bloge.externalSequenceAnchorBootstrapRootRecoveryFleetSloAssessment.v1`；
- `ExternalSequenceAnchorBootstrapRootRecoveryFleetSloMonitor`；
- `ExternalSequenceAnchorBootstrapRootRecoveryFleetTelemetry`；
- strict `RecoveryFleetSloProperties` 和 test/staging 环境变量；
- Actuator health 与 41 个固定基数 Micrometer meter series。

三个事实面必须分开：

| 事实面 | 回答的问题 | 权威对象 |
| --- | --- | --- |
| Capability | 当前这一刻是否允许新 recovery poll | `recoveryFleet.status/ready` |
| Local SLO | 本副本近期 progress、freshness、failure ratio 是否达标 | SLO assessment v1 |
| Fleet SLO | 多副本是否收敛、区域是否可用、是否需要告警 | 外部监控与治理平台，尚未内建 |

本实现不宣称跨副本 convergence readiness、外部采集器、告警路由、值班升级、长期窗口、目标数据库或
production profile 已完成。它交付的是稳定、可采集、可制定告警的单副本事实协议，不把部署系统责任偷偷
塞进进程内代码。

## 2. 根因分析

原有 health 与 capability 能精确表达最新 inventory、scheduler、cycle 和 lane 状态，但工业运行还会遇到：

1. 最新一次成功会掩盖过去 20 次里持续发生的失败；
2. scheduler 没有报错却长期没有产生一次成功，当前 snapshot 仍可能看似平静；
3. 刚启动的副本和已经失去 progress 的副本都表现为“尚无成功”，无法制定可靠门禁；
4. 直接把 fleet、worker、lane、URI 或异常作为 metric tag 会制造无界 cardinality 与敏感信息泄漏；
5. 在 monitor 中重新读取 inventory、数据库或远端 publication 会让可观测性反向改变业务运行；
6. 只暴露若干 gauge、不冻结状态和 Schema，会让告警规则随着字段重命名静默漂移；
7. 单副本正常不能推出整个 fleet 正常，若不声明边界，局部指标会制造错误的生产保证。

根治方式是：用同一份本地 immutable projection 同时产生版本化 assessment、Actuator health 和固定枚举
metrics；当前失败与历史可靠性分别判定；跨副本聚合留给能看到完整部署拓扑的外部系统。

## 3. 读取一致性与无副作用

monitor 不调用 `inventory.snapshot()`，也不访问 lane、数据库、网络、resolver、provider 或 payload。

当 inventory 实现 externally attested authority 时，它复用 capability 的夹读协议：

```text
authority observation before
  -> authority descriptor
  -> worker immutable runtime snapshot
  -> scheduler immutable snapshot
authority observation after
```

前后 generation/status/source/lane count 不一致时得到 `INCONSISTENT`，snapshot 读取异常得到
`UNAVAILABLE`。assessment 再复核 capability 中的 poll/cycle 计数与本次 worker/scheduler snapshot 相等，
防止把两个时刻的累计值拼成一份证据。

普通 local inventory 没有 external attestation，monitor 仍可读取运行计数，但固定产生
`UNATTESTED_INVENTORY` SLO violation。测试模式可以显式关闭 SLO；staging fleet 不允许关闭，也不允许用
未签名 inventory 自证健康。

## 4. Assessment 协议

权威 Schema：
[external-sequence-anchor-bootstrap-root-recovery-fleet-slo-assessment-v1.schema.json](schemas/resource-gateway-testing/external-sequence-anchor-bootstrap-root-recovery-fleet-slo-assessment-v1.schema.json)。

| 字段组 | 字段 | 语义 |
| --- | --- | --- |
| 协议 | `schemaVersion` | 精确 assessment v1 |
| 结论 | `state`、`violations`、`runtimeStatus` | SLO 状态、canonical violation、当前 capability 状态 |
| 时间 | `observedAt`、`lastPollSuccessAgeMillis` | 本地观察时间和最新可识别成功年龄 |
| inventory | `inventoryGeneration`、`laneCount` | externally attested aggregate identity，不含业务身份 |
| scheduler | `pollCount`、`completedPollCount`、`pollFailureCount`、`pollFailureBasisPoints` | admitted/success/failure 与累计失败率 |
| worker | `cycleCount`、`cycleFailureCount`、`cycleFailureBasisPoints` | cycle 可靠性 |
| lane | `laneAttemptCount`、`laneFailureCount`、`laneFailureBasisPoints` | lane 隔离失败率 |
| policy | `policy` | 生成本次判断所用的完整毫秒/basis-point 阈值 |

观察不可用时，`observedAt=null`、`runtimeStatus=UNAVAILABLE`、所有 value metric 为 `-1`，且 violation
精确为 `OBSERVATION_UNAVAILABLE`。`-1` 表示 unknown，不得解释为零次失败。其余状态必须有观察时间和
非负计数；record 复核 failure 不超过 total，并用 overflow-safe 整数运算重新计算 basis points，伪造 ratio
无法构造合法 Java 协议对象。

## 5. 状态与违反项

| State | Actuator | 语义 |
| --- | --- | --- |
| `HEALTHY` | `UP` | 当前 runtime 与成熟累计可靠性均满足 policy |
| `INITIALIZING` | `UNKNOWN` | 尚无成功，但仍在 startup grace 内 |
| `SLO_VIOLATED` | `OUT_OF_SERVICE` | 当前失败、成功过旧或成熟失败率超限 |
| `CLOSED` | `DOWN` | worker/scheduler 已关闭 |
| `OBSERVATION_UNAVAILABLE` | `DOWN` | 无法形成 coherent local projection |

当前事实违反项：`UNATTESTED_INVENTORY`、`INVENTORY_UNAVAILABLE`、`RUNTIME_CLOSED`、
`SCHEDULER_STALLED`、`SCHEDULER_FAILED`、`CYCLE_FAILED`、`LATEST_LANE_FAILURES`、
`SNAPSHOT_INCONSISTENT`、`OBSERVATION_UNAVAILABLE`。

进度和历史违反项：`POLL_NEVER_SUCCEEDED`、`POLL_SUCCESS_STALE`、
`POLL_FAILURE_RATE_EXCEEDED`、`CYCLE_FAILURE_RATE_EXCEEDED`、
`LANE_FAILURE_RATE_EXCEEDED`。

当前失败不受 minimum-samples 豁免。累计 ratio 只有 denominator 达到 `minimumSamples` 才执行，避免小样本
抖动；阈值为 inclusive，只有 observed ratio 严格大于 threshold 才违反。active cycle 在 scheduler 的
maximum-cycle-duration 内不会因上一成功过旧产生重复误报；超过该 duration 后由 `SCHEDULER_STALLED`
接管。

## 6. 配置与启动门禁

属性前缀：

```text
gateway.testing.external-sequence-anchor.bootstrap-root-recovery-fleet-slo
```

| Environment variable | Default | Invariant |
| --- | ---: | --- |
| `RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_SLO_ENABLED` | `true` | test 可关闭；staging fleet 强制 true |
| `RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_SLO_OBSERVATION_INTERVAL_MS` | `30000` | `1000..3600000` |
| `RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_SLO_STARTUP_GRACE_MS` | `30000` | `1..86400000`，且不小于 initial delay + poll interval |
| `RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_SLO_MAX_SUCCESS_AGE_MS` | `30000` | `1..604800000`，且不小于 2 x poll interval |
| `RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_SLO_MINIMUM_SAMPLES` | `20` | `1..1000000` |
| `RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_SLO_MAX_POLL_FAILURE_BP` | `500` | `0..10000`，默认 5% |
| `RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_SLO_MAX_CYCLE_FAILURE_BP` | `500` | `0..10000`，默认 5% |
| `RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_SLO_MAX_LANE_FAILURE_BP` | `1000` | `0..10000`，默认 10% |

SLO properties 使用 `ignoreUnknownFields=false`。拼错字段、把 webhook/credential 塞进本地策略、负数、
超界 basis points 或与 scheduler cadence 矛盾都会在 coordinator state 和后台工作前失败。

启动与停止仍使用仓库统一脚本：

```bash
./scripts/start-visual-canvas-demo.sh --profile test
curl -s http://localhost:8080/actuator/health
./scripts/visual-canvas-demo.sh status
./scripts/stop-visual-canvas-demo.sh
```

默认只开放 `/actuator/health`。health detail 是否展示、Micrometer registry/exporter、Prometheus/OpenTelemetry
scrape、TLS、认证和网络策略由部署方配置；Resource Gateway 不自动打开管理端口或发送 webhook。

## 7. 固定基数指标

逻辑 Micrometer 前缀为 `resource.gateway.test.bootstrap.root.recovery.fleet.`：

| Meter | Tags | 值 |
| --- | --- | --- |
| `health` | none | `1/0/-1/-2/-3` 对应五个 state |
| `status` | `status=<Capability.Status>` | 完整 enum one-hot |
| `violation` | `code=<Violation>` | 完整 enum one-hot |
| `inventory.generation`、`inventory.lanes` | none | 最新 aggregate 或 `-1` |
| `polls` | `outcome=total/completed/failed` | scheduler 累计值或 `-1` |
| `cycles` | `outcome=total/failed` | worker 累计值或 `-1` |
| `lanes` | `outcome=attempted/failed` | lane 累计值或 `-1` |
| `failure.ratio.basis.points` | `scope=poll/cycle/lane` | `0..10000` 或 `-1` |
| `last.success.age.millis` | none | 非负年龄或 `-1` |

所有 status/code/outcome/scope 都是闭集；无 fleet id、worker id、deployment scope、lane key、URI、
fingerprint、exception、credential 或 payload tag。Prometheus 等 exporter 可能改写点号，告警规则应从实际
registry discovery 生成，不应猜测 exporter 名称。

## 8. 外部告警建议

部署平台至少需要按 `service + environment + region + instance` 的受控基础标签聚合，不能由 Resource
Gateway 动态业务字段生成这些标签。建议规则：

1. `OBSERVATION_UNAVAILABLE` 或 `CLOSED` 连续两个 observation interval：立即 page；
2. `SCHEDULER_STALLED`、`INVENTORY_UNAVAILABLE`：立即关闭 recovery admission 并 page；
3. `POLL_NEVER_SUCCEEDED` 或 `POLL_SUCCESS_STALE`：page，检查 scheduler、database lease 与 authority；
4. 三种成熟 failure ratio violation：ticket 或 page 由业务恢复 RTO 决定；
5. `INITIALIZING` 只展示 rollout 状态，不 page；超过 grace 会自动转为 violation；
6. 只有当 expected replica inventory 与 generation convergence 由外部控制面证明后，才能计算 fleet-wide
   availability。不得用“任一副本 healthy”替代完整 fleet SLO。

告警必须携带 protocol version、state、violation 和 observation timestamp；不得附带 health detail 中没有的
业务身份或原始异常。值班 runbook 应区分 inventory、scheduler、worker、lane 与 observation 五类病因，
而不是统一执行重启。

## 9. 验证矩阵

测试覆盖：

- startup grace、首次成功、成功过旧和 active-cycle 免误报；
- 阈值 inclusive、minimum sample、最新 READY 但历史失败率超限；
- inventory unavailable、runtime closed、scheduler stalled/failed、cycle/lane failure；
- unattested inventory、authority/counter snapshot tear 和 observation exception 脱敏恢复；
- assessment/policy 非法关系与 overflow-safe basis-point 重算；
- 41 个 meter series、完整 one-hot enum、unknown sentinel 和 forbidden tag vocabulary；
- strict Schema 字段/enum/policy bounds/state implications 与敏感词门禁；
- Spring enabled/disabled、staging downgrade fence、cadence cross-check 和 unknown-property failure。

聚焦命令：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ExternalSequenceAnchorBootstrapRootRecoveryFleetSloMonitorTest,\
ExternalSequenceAnchorBootstrapRootRecoveryFleetTelemetryTest,\
ExternalSequenceAnchorBootstrapRootRecoveryFleetSloAssessmentProtocolSchemaTest,\
ExternalSequenceAnchorBootstrapRootRecoveryFleetRuntimeConfigurationTest,\
ExternalSequenceAnchorBootstrapRootRecoveryFleetDynamicInventoryConfigurationTest test
```

本增量聚焦回归执行 46 tests，0 failures、0 errors、0 skips；其中新增 25 项 SLO/telemetry/Schema/Spring
测试。组合工作区随后执行完整 `clean verify`，解析 390 份 Surefire XML 得到 3533 tests、0 failures、
0 errors、2 个条件浏览器跳过，并成功重打包可执行 JAR。组合总量同时包含并行 trust-root 子步新增的
15 项测试，不归入本增量覆盖数。三个新增公共 SLO/telemetry/runtime 类型通过 `javadoc --release 25
-Werror -Xdoclint:all`。

## 10. 剩余门禁

- fleet inventory/expected replica 的外部权威与跨副本 generation convergence SLO；
- deployment-owned metrics backend、recording rules、alert routing、deduplication、silence 与 on-call 演练；
- restart 后连续长期窗口；当前累计计数是 process-local，不是 durable SLI ledger；
- production profile、目标数据库、多区域、rolling upgrade、backup/restore、DR、chaos 和 soak 认证；
- online partition rebalance、priority/fairness 与 fleet-wide rollout jitter；
- mTLS/pinning、enterprise IAM、HSM/KMS custody 和 publisher HA/anti-equivocation。

因此本子步关闭“没有稳定单副本 SLO 事实和可采集 vocabulary”的根因，不关闭“企业生产运维系统已经
端到端完成”的门禁。
