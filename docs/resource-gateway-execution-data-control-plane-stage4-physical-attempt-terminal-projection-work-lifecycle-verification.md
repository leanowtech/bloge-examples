# Resource Gateway Stage 4 Physical Attempt Terminal Projection Work Lifecycle Verification

## 1. 增量目标

第十二增量保证 terminal observation reconciliation 与 projection work registration 原子提交，但 work row
仍只能停留在 `READY`。本增量补齐数据库权威的执行状态机，使多个进程能够安全地 claim、过期接管、完成、
重试或隔离同一条 work，同时保持 coordinator/proof/provider I/O 在行锁事务之外。

本增量关闭的是 durable worker authority，不包含自主 scheduler、proof resolver 产品实现或 Spring capability。

## 2. Lease 与跨副本接管

`claimNext(ownerId)` 使用数据库时间选择到期 `READY` 或 lease 已过期的 `LEASED` row，并在短事务中：

1. `FOR UPDATE` 锁定稳定排序的最早 actionable row；
2. 重算 trigger 与 whole-row fingerprint；
3. 增加 monotonic lease epoch；
4. 生成 opaque UUID token；
5. 以 database claim time 和 exclusive deadline 构造完整 fence fingerprint；
6. CAS 更新 whole-row fingerprint 后返回 payload-free `Claim`。

live lease 不可被第二副本同时 claim。lease 过期后新 owner 获得更高 epoch，旧 owner 的 completion 返回稳定
`LEASE_LOST`。调用方篡改 work/attempt/owner/token/epoch/time 任一 fence 字段会在数据库写入前返回
`INTEGRITY_FAILURE`。

claim transaction 不包含 terminal projection coordinator 调用。慢 proof resolution 或 queue projection 不会长期
占用 work row lock；调用完成后必须使用原 lease 进入新的短 completion transaction。

## 3. Result 与 completion truth table

`bloge.testSuiteStabilityPhysicalAttemptTerminalProjectionWorkResult.v1` 是 coordinator `Attempt` 的 payload-free
持久化投影，保留：

- closed `PROJECTED / REPLAYED / PROOF_PENDING / UNAVAILABLE / PERMANENT_CONFLICT`；
- fixed-cardinality coordinator failure reason；
- proof pending/conflict 的精确 proof reason；
- projection conflict 的精确 journal reason；
- 成功 projection id 与 projection record fingerprint。

结果以 lease fence 和 canonical result material 共同生成 fingerprint，用于 response-loss exact replay。状态转换为：

| coordinator result | work successor | retry/counter |
| --- | --- | --- |
| `PROJECTED` / `REPLAYED` | `COMPLETED` | 清零 streak，保存 exact projection id |
| `PROOF_PENDING` | `READY` | 独立 proof streak，按数据库时间指数退避 |
| `UNAVAILABLE` | `READY` | 独立 unavailable streak，按数据库时间指数退避 |
| `PERMANENT_CONFLICT` | `QUARANTINED` | 不构造 projection，不自动重试 |

proof pending 和 infrastructure unavailable 不会因为重复次数多而被改写成永久业务冲突。两条 retry lane 使用
独立 initial delay，共享显式 maximum delay；切换 lane 时清零另一条 streak。所有 policy duration 必须是
millisecond-exact 且落在硬边界内，不做静默 clamping。

同 lease/same result 的重复 completion 返回 `REPLAYED`；同 lease/different result 返回
`RESULT_CONFLICT`；已被更高 epoch 接管则返回 `LEASE_LOST`。Entry、Result、Completion 和 Snapshot 均有
独立 truth table，拒绝 result/lifecycle/counter/projection 自相矛盾的值。

## 4. Aggregate observation

`snapshot()` 在 repeatable-read transaction 中使用数据库时间返回固定基数：`READY`、`LEASED`、
`COMPLETED`、`QUARANTINED`、当前 due-ready、expired-lease 和 oldest-actionable time。它不包含 tenant、
attempt、owner、token 或业务 payload，可作为后续 SLO/health 的数据源，但本增量尚未发布这些产品视图。

## 5. 已执行验证

work lifecycle 聚焦门禁：

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DatabaseTestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournalTest test

Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
```

覆盖 initial claim/snapshot、live lease 排斥、proof pending reschedule/exact replay、proof/unavailable streak
隔离、success completion/changed-result conflict、permanent quarantine、expired takeover/stale owner、双副本
并发 claim、altered lease、row tamper，以及 coordinator proof/projection conflict detail projection。

registration + lifecycle 组合门禁：

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DatabaseTestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournalTest,\
DatabaseTestSuiteStabilityPhysicalAttemptObservationJournalTest test

Tests run: 63, Failures: 0, Errors: 0, Skipped: 0
```

完整 physical-attempt/cancellation/queue 聚合门禁：

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest='*PhysicalAttempt*,*AttemptCancellation*,DatabaseTestSuiteStabilityJobRepositoryTest' test

Tests run: 262, Failures: 0, Errors: 0, Skipped: 0
```

扩展后的 public work contract 与数据库适配器使用 Maven 完整 compile classpath 通过
`javadoc --release 25 -Werror -Xdoclint:all`，0 warnings、0 errors。

## 6. 尚未闭合

- 没有把 claim、terminal projection coordinator 和 complete 组合成 bounded one-shot worker；
- proof resolver 尚无 cancellation-by-attempt lookup 与 parent-success 产品 adapter；
- lease 没有 heartbeat；下一层 worker 必须保证 coordinator deadline 小于 lease，或实现 fenced renewal；
- 全局最早 due 排序尚无 tenant/environment fair scope cursor，大租户 backlog 可能造成跨租户饥饿；
- replica policy fingerprint/cohort readiness 尚未持久化，不同配置副本虽然保持 safety，但可能产生不同 retry SLO；
- work row 只保留 fixed failure、projection id 和 exact result fingerprint，尚无 immutable per-attempt result history；
- N/N-1 旧版本已经 terminal 但没有 work row 的 target 尚无有界 backfill；
- 没有 scheduler、retention fence、telemetry、health/readiness、capability 或 Spring test/staging composition；
- 尚未执行本提交 immutable snapshot 的完整 `clean verify`、JAR 内容和残留进程核验；
- 生产数据库、断电 crash point、真实 process/container provider 与 HA/partition/chaos 认证仍未完成。

下一增量应先建立 bounded one-shot worker，把 claim trigger 的 exact scope/attempt 传给 coordinator，将
coordinator Attempt 严格投影为 work Result，并在所有异常路径释放或重排 work；随后补 proof product adapter、
fairness/policy cohort 和 Spring lifecycle。
