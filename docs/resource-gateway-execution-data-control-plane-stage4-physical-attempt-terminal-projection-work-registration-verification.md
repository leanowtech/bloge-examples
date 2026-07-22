# Resource Gateway Stage 4 Physical Attempt Terminal Projection Work Registration Verification

## 1. 增量目标

第十一增量已经能够从 exact durable source chain 协调一次 terminal projection，但 observation
reconciliation 的 `TERMINAL` 与 queue projection 仍是两个生命周期。如果前者提交后进程立即退出，依赖
进程内 tail call 会永久丢失后者。本增量先关闭这个 crash window：在同一个本地数据库事务中提交 terminal
reconciliation target 和一条独立、可长期认领的 projection work fact。

本增量只建立 work registration authority 与完整的持久化状态形状。它不执行 projection，不调用 provider，
也不声称 claim/retry/takeover worker、Spring scheduler 或产品 capability 已经可用。

## 2. Durable work contract

`TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal` 冻结两个版本化值对象：

| 值 | 约束 |
| --- | --- |
| `Trigger` | 内容寻址绑定 tenant、environment、physical attempt、terminal observation command 和 reconciliation result fingerprint |
| `Entry` | 保存 lifecycle、database-clock due time、lease fence、执行/连续失败计数、固定结果分类、projection id、时间和 whole-row fingerprint |

生命周期预留为 `READY / LEASED / COMPLETED / QUARANTINED`；worker 结果预留为
`NONE / PROJECTED / REPLAYED / PROOF_PENDING / UNAVAILABLE / PERMANENT_CONFLICT`。尚未实现的 worker
不能伪造这些转换。`Trigger` 与整行都在读取时重算指纹；同 attempt 的 exact replay 是 no-op，改变任何 source
reference 都返回稳定的 `IDEMPOTENCY_CONFLICT`，畸形或被篡改行返回 `INTEGRITY_FAILURE`。

表中不保存业务 payload、provider 诊断、用户凭据或 proof 内容，只保存不可变 source reference、固定基数
分类和调度 fence。初始注册使用数据库时间，状态只能为 `READY`，执行次数和 lease epoch 均为零。

## 3. Atomic registration boundary

数据库适配器的 `boundRegister(trigger)` 不开启独立事务，而是返回
`TestRuntimeTransactionMutation`。observation reconciliation journal 在其已经持有的 transaction-bound
`JdbcTemplate` 上按以下顺序执行：

1. 锁定并完整验证 exact reconciliation target；
2. 验证 lease、database-clock deadline 与 result fingerprint；
3. 计算 terminal successor 并更新 target；
4. 仅当 successor 为 `TERMINAL` 时创建 content-addressed work trigger；
5. 在同一连接和同一事务注册 `READY` work row；
6. 一起 commit，任一写失败则一起 rollback。

`READY`、`QUARANTINED` 等非 terminal successor 不产生 projection work。exact completion response-loss
replay 不重复注册，因为原始 target transition 与 work row 已经在一个 commit 中完成。旧版本已经提交但没有
work row 的 `TERMINAL` target 不由 replay 路径猜测修复，必须由后续独立、有界、版本感知的 backfill lane
处理。

## 4. 已执行验证

真实 H2、database transaction、Ed25519 source chain 聚焦门禁：

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DatabaseTestSuiteStabilityPhysicalAttemptObservationJournalTest test

Tests run: 53, Failures: 0, Errors: 0, Skipped: 0
```

新增场景证明：terminal completion 注册 exact `READY` work；completion replay 仍只有一行；非 terminal
completion 不注册；注入 registration failure 后 target 保持 `LEASED` 且 work 不存在；同 attempt 改变 trigger
被永久拒绝；篡改 lifecycle 列被 whole-row/shape validation 拒绝。

完整 physical-attempt/cancellation/queue 聚合门禁：

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest='*PhysicalAttempt*,*AttemptCancellation*,DatabaseTestSuiteStabilityJobRepositoryTest' test

Tests run: 252, Failures: 0, Errors: 0, Skipped: 0
```

新增 work contract、数据库适配器和修改后的 reconciliation journal 使用 Maven 完整 compile classpath
通过 `javadoc --release 25 -Werror -Xdoclint:all`，0 warnings、0 errors。

## 5. 尚未闭合

- work journal 尚未实现 database-clock claim、lease renewal、expired takeover、fenced completion 与 retry；
- observation reconciliation 只负责新终态的原子注册，N/N-1 升级前已存在的 orphan terminal target 尚无 backfill；
- coordinator 的 cancellation-by-attempt 与 parent-success proof resolver 尚无产品实现；
- 没有 bounded worker、scheduler、retention fence、backlog SLO、telemetry、health/readiness 或 capability；
- 没有 Spring test/staging composition，产品能力继续关闭；
- 尚未执行本提交 immutable snapshot 的完整 `clean verify`、JAR 内容和残留进程核验；
- 生产数据库、跨副本 contention、断电 crash point、真实 process/container provider 与 HA/partition/chaos
  认证仍未完成。

因此，本增量关闭的是“终态 reconciliation 与未来 projection work 注册之间不能被进程崩溃撕裂”，不是
“终态投影已经自动运行”。下一增量必须在该持久化 authority 上实现有界 claim/retry/takeover 状态机，随后
再接 coordinator 和产品生命周期。
