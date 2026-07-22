# Resource Gateway Stage 4 Physical Attempt Terminal Projection Verification

## 1. 增量目标

orphan reconciler 已能找回 terminal positive observation，slot fence 也会阻止不确定 physical side
effect 被重复 dispatch；但此前两者之间没有可信 closure transaction。即使 provider 已签名证明 attempt
终止，queue job、retry winner、parent terminal 与 capacity slot 仍不会收敛。

本增量新增一个深模块：调用方只提交内容寻址 terminal-projection command，模块内部完成 source-chain
复验、queue winner 决策、immutable projection append 与 slot release。它不接受 timeout、
`NOT_OBSERVED`、`INDETERMINATE` 或 quarantine 作为 terminal proof。

## 2. Exact source chain

projection command 精确绑定下列 durable facts：

1. physical-attempt reservation 的 whole-row fingerprint；
2. provider I/O 前 retained start command 与 start-entry fingerprint；
3. accepted `TERMINAL` observation entry 与 positive-state floor fingerprint；
4. `CANCELLED` 时 exact `CONFIRMED` cancellation entry；
5. `SUCCEEDED` 时预期 signed parent run/evidence identity。

command id 由全部字段 canonical SHA-256 派生。数据库 adapter 先通过各 journal 的完整性读取验证语义，
再在 queue transaction 内用 exact fingerprint 重验原始 source rows。任何 source 缺失、变化、跨 scope、
跨 attempt、跨 lease epoch、process/runtime identity 冲突都会 fail closed，且不会写 projection 或释放 slot。

## 3. Queue winner truth table

| terminal disposition | 额外证明 | queue winner |
| --- | --- | --- |
| `SUCCEEDED` | parent authority 必须重算并验证 exact signed parent evidence | `SUCCEEDED` |
| `CANCELLED` | queue 已有 durable cancellation intent，且 provider cancellation entry 为 `CONFIRMED`，process/runtime terminal fingerprint 与 observation 一致 | `CANCELLED`；若 signed parent 已先完成则 parent winner 优先 |
| `TIMED_OUT` | provider positive terminal observation | `EXPIRED` |
| `FAILED` | provider positive terminal observation | deadline 未到且 retry budget 可用时 `QUEUED`，否则 `FAILED/EXPIRED` |
| `PROVIDER_ABORTED` | provider positive terminal observation | 与 `FAILED` 相同，但保留独立 failure code |

非 success disposition 不能覆盖已经 linearize 的 `COMMITTING` publication。失败重排队保留旧 lease epoch，
下一次 claim 才原子递增为新 epoch；旧 attempt 的 start fence 只有在 terminal projection 与 queue update
同事务提交后才不再阻挡新 claim。

## 4. Atomicity 与 replay

每个 environment 仍由既有 database lock 串行化。首次 projection 在一个 transaction 内：

1. 重验 active queue policy；
2. 检查 projection id 与 attempt/epoch 唯一 closure；
3. 重验 raw source fingerprints 和 queue row integrity；
4. 调用 parent-first stop 或 signed-parent proof；
5. CAS 更新 exact queue row；
6. 写入 immutable terminal projection。

第 4-6 步任一失败会回滚 queue/projection；slot fence 继续保守持有 capacity。parent stop 可能使用独立
durable transaction，因此最坏只会留下可重放 stop tombstone，不会出现“queue 已释放但 parent 可继续”。

projection 自身保存 payload-free queue result 和 whole-row fingerprint。已提交 command 的 exact replay
优先读取 projection，不依赖 reservation/start/observation 的后续独立 retention；改变 projection decision、
source refs、queue result 或 record fingerprint 会在读取时失败。

## 5. 已执行验证

真实 H2、Ed25519 reservation/start/observation/cancellation journals、queue repository 与双副本组合：

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DatabaseTestSuiteStabilityPhysicalAttemptObservationJournalTest test

Tests run: 50, Failures: 0, Errors: 0, Skipped: 0
```

新增 9 条 terminal-projection 场景覆盖：failure retry、source retention 后 exact replay、新 epoch claim、
retry exhaustion、provider abort、cancel intent + confirmed receipt、signed parent success rollback/recovery、
source tamper、queue fence change、跨副本单 winner、projection whole-row tamper，以及 non-terminal positive
state 拒绝。部分测试在一个场景中同时覆盖多个不变量。

完整 physical-attempt/cancellation/queue 聚合门禁：

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest='*PhysicalAttempt*,*AttemptCancellation*,DatabaseTestSuiteStabilityJobRepositoryTest' test

Tests run: 231, Failures: 0, Errors: 0, Skipped: 0
```

四个相关公共类型使用 Maven 完整 compile classpath 通过：

```text
javadoc --release 25 -Werror -Xdoclint:all
0 warnings, 0 errors
```

实现提交 `a87f6780` 已从 `git archive` 解包到 immutable source snapshot
`/tmp/bloge-examples-verify-a87f6780.KoKMJM` 并执行完整门禁：

```text
mvn -f /tmp/bloge-examples-verify-a87f6780.KoKMJM/resource-gateway-examples/pom.xml \
  clean verify

Tests run: 4065, Failures: 0, Errors: 0, Skipped: 2
BUILD SUCCESS
Total time: 09:50 min
```

455 份 Surefire XML 独立解析汇总为同一 `4065/0/0/2` 结果。重打包后的可执行 JAR 为
39,632,117 bytes，包含 10 个 `PhysicalAttemptTerminalProjection` 匹配 class entry。门禁退出后，
snapshot Java/Maven 进程和 ChromeDriver/Chrome for Testing 进程残留均为零。

## 6. 尚未闭合

- projector 尚未进入 Spring worker、orphan reconciler completion hook、scheduler、health/readiness 或
  capability；产品能力继续关闭；
- projection 没有独立 retention/tombstone、legal hold、backup erasure、external WORM anchor 或人工 repair；
- capacity SQL 对 projection row 使用数据库信任，不是逐行重算 fingerprint 的外部 witnessed proof；
- cancellation/start/observation 仍没有跨 family 的统一 provider sequence ledger；
- 没有真实 process/container/VM provider、生产数据库、HA、crash-point/partition/chaos 认证；
- retry 仍是 queue policy 级决策，没有按 operator risk/idempotency 单独限制；
- 没有 projection backlog/SLO、失败分类 telemetry 或治理 evidence export。

因此本增量关闭的是 test-core 的可信 slot closure，不等于生产 runtime 已开放。下一增量应先把 projector
接入 orphan reconciler completion 与 bounded worker，再开放健康度和 capability truth。
