# Resource Gateway Stage 4 Physical Attempt Observation Reconciler Verification

## 1. 增量目标

前一增量已经固化单次 lifecycle observation 的 durable coordinator，但没有组件会在 worker 崩溃、调用
超时、回执丢失或 queue lease 丢失后自动找回可能仍存在的物理 side effect。本增量建立 database-clock
bounded reconciliation core：

- 以 immutable start journal 作为 orphan target 的事实来源，不依赖调用方额外 enqueue；
- 以 bounded page 幂等物化 reconciliation target，snapshot 单独报告尚未物化的 source 数；
- 以 tenant/environment scope durable cursor 做跨 scope 轮转，并在 scope 内按到期时间和 attempt id 排序；
- 以 owner、opaque token、positive epoch、database claim time 和 lease deadline 围栏一次 worker step；
- provider I/O 继续只经过 observation coordinator，且始终发生在数据库事务之外；
- 区分 provider uncertainty 与 local backpressure，使用独立指数退避 streak；
- 用 uncertainty budget 和 maximum horizon 收敛无限重试，但 quarantine 不冒充 remote terminal；
- completion response loss 可精确重放，lease takeover 会拒绝旧 owner 的迟到 completion。

本增量只推进 observation reconciliation target。它不更新 stability job/queue、slot permit、cancellation
journal 或 natural terminal。

## 2. 为什么 Start Journal 是 Source

物理 start 的最危险窗口是：provider 已经创建进程，但调用方在收到或持久化 start receipt 前崩溃。如果
reconciliation 依赖 start coordinator 在调用之后再 enqueue，这个窗口里的 attempt 会永久不可见。

现有 start coordinator 在任何 provider I/O 前已经执行 durable `startJournal.prepare`。因此 reconciler
从 `rg_test_stability_attempt_start_entries` 发现 source：

```text
durable start prepare
  -> provider start may succeed / timeout / lose response
  -> bounded source discovery
  -> reconciliation target
  -> database-clock lease
  -> lifecycle observation coordinator
```

source discovery 每次最多处理 policy page size，且用 start command id 唯一投影；两个副本并发发现同一
source 时只有一份 target。旧库中已经存在的 start entry 也可逐页收敛，不需要启动期无界 migration。

## 3. Durable Target State Machine

| 状态 | 含义 | 可迁移到 |
| --- | --- | --- |
| `READY` | 等待 database `nextAttemptAt` | `LEASED`、`QUARANTINED` |
| `LEASED` | 一个 exact owner/token/epoch 持有短租约 | `READY`、`TERMINAL`、`QUARANTINED`、过期 takeover |
| `TERMINAL` | verified positive observation 已证明 provider terminal | 无自动迁移 |
| `QUARANTINED` | policy/integrity 无法自动收敛 | 仅后续治理协议可处理，本增量无人工改写 API |

target 保存 payload-free start command、provider/deployment binding、provider-facing attempt count、连续
uncertainty、连续 local failure、最近 observation command、closed outcome、时间与 whole-row fingerprint。
lease completion 的 result fingerprint 绑定 exact lease fence 和语义结果；同结果 response-loss replay 返回
`REPLAYED`，同 lease 改报结果返回 `RESULT_CONFLICT`。

## 4. 两类失败预算

| 结果 | provider attempt | uncertainty streak | local-failure streak | 处理 |
| --- | ---: | ---: | ---: | --- |
| `POSITIVE_ACTIVE` | +1 | reset | reset | steady poll delay |
| `POSITIVE_TERMINAL` | +1 | reset | reset | target terminal |
| `RETAINED_TERMINAL` | 不增加 | reset | reset | 零 provider I/O terminal recovery |
| `NON_CONFIRMING` | +1 | +1 | reset | exponential retry |
| `REMOTE_UNCERTAIN` | +1 | +1 | reset | exponential retry |
| `LOCAL_BACKPRESSURE` | 不增加 | 不变 | +1 | 独立 exponential retry |
| `PERMANENT_FAILURE` | 由 command evidence 决定 | 不伪造 | reset | quarantine |

descriptor timeout、resolver outage、supervisor saturation/closed 均为 local backpressure。observation timeout、
interrupt 或 adapter failure 可能已经触发远端读取，记为 remote uncertainty。invalid attestation 发生在
provider call 之后，因此携带 observation command evidence、计入 provider attempt，再 fail closed quarantine。

uncertainty budget 耗尽或 maximum horizon 到达时只进入 `QUARANTINED`；这不是 non-start、cancelled 或
terminal proof。local outage 不消耗业务 uncertainty budget，但仍受全局 horizon 约束，避免永久占用。

## 5. Fair Claim 与恢复

每个 tenant/environment scope 有独立 fingerprinted scheduling row。claim 先选择最久未成功 claim 的 due
scope，再锁定该 scope 内最早 due target。horizon cleanup 也推进 scope cursor，避免一个大租户的历史
过期前缀持续压住其他 scope。

provider observation 完成后，positive fact 先由 observation journal 独立提交，再尝试完成 reconciliation
lease。若 completion 前 lease 丢失：

1. 旧 worker 的 completion 被 `LEASE_LOST` 拒绝；
2. 新 worker takeover 后先读取 latest positive floor；
3. 已有 `TERMINAL` floor 时以 `RETAINED_TERMINAL` 零 provider I/O 关闭 target；
4. 非终态 floor 则以最新 process/revision fence 创建新 challenge-bound command。

因此 reconciliation queue 的短暂不一致不会抹掉 provider fact，也不会要求重复 terminal query。

## 6. 已执行验证

真实 H2、Ed25519 verifier、call supervisor、coordinator 和 reconciliation worker 组合测试：

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DatabaseTestSuiteStabilityPhysicalAttemptObservationJournalTest test

Tests run: 40, Failures: 0, Errors: 0, Skipped: 0
```

新增 18 条场景覆盖 bounded discovery/undiscovered snapshot、database lease、exact completion replay、
changed-result rejection、local backpressure 独立退避、uncertainty budget、positive reset、terminal target、
lease takeover、跨 scope rotation、target tamper、真实 running/non-confirming/timeout/invalid-attestation、
resolver outage、窗口/lease fail-fast、跨副本单 claim，以及“terminal fact 已提交但 completion 丢失”恢复。

完整 physical-attempt、start、observation、cancellation、reservation 与 journal 聚焦链：

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest='*PhysicalAttempt*,*AttemptCancellation*' test

Tests run: 180, Failures: 0, Errors: 0, Skipped: 0
```

三个新增公共类型使用 Maven 完整 compile classpath 通过：

```text
javadoc --release 25 -Werror -Xdoclint:all
0 warnings, 0 errors
```

实现提交 `8b91eb9b` 的 immutable snapshot
`/private/tmp/bloge-examples-verify-8b91eb9b.dEAIM8` 已执行完整门禁：

```text
mvn -f resource-gateway-examples/pom.xml clean verify

Tests run: 4055, Failures: 0, Errors: 0, Skipped: 2
BUILD SUCCESS
Total time: 07:22 min
```

独立读取 455 份 Surefire XML 得到相同的 `4055/0/0/2` 汇总。重打包后的 Spring Boot 可执行 JAR
为 39,595,599 bytes，包含 reconciler、reconciliation journal 接口、数据库实现及其 23 个匹配的
主类/嵌套类 entry。Maven 退出后再次检查快照路径关联的 Maven/Surefire/Java 进程与测试启动的
ChromeDriver/Chrome for Testing 进程，两类残留数均为 0。

## 7. 尚未闭合

- 没有 Spring composition、周期 scheduler、health/readiness/capability 或外部告警；
- reconciliation snapshot 是数据库运营视图，不是签名 evidence，也不替代逐 target integrity validation；
- 没有 reconciliation target retention/tombstone、legal hold、external anchor 或人工 repair 协议；
- start/cancel/observation 仍没有跨 family 的统一 provider fact sequence ledger；
- positive `TERMINAL` 没有与 queue parent、slot permit 和 accepted cancellation receipt 原子投影；
- `CANCELLED` disposition 没有强制链接 exact cancellation receipt；
- 没有真实 process/container/VM provider、动态 signed resolver inventory 或生产数据库方言/HA/chaos 认证。

因此 product capability 继续关闭。下一增量应设计 terminal projection transaction：在保留 observation fact
独立真实性的同时，原子推进 queue parent、slot ownership 和 cancellation/natural-terminal closure；在此之前
不得因 reconciliation target 为 `TERMINAL` 就释放生产资源配额或宣称 stability job 已结束。
