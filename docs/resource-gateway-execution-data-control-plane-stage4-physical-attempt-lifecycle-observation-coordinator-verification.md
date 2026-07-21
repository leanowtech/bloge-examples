# Resource Gateway Stage 4 Physical Attempt Lifecycle Observation Coordinator Verification

## 1. 增量目标

前一增量已经建立 database-authoritative observation journal，但调用方仍可能绕过固定容量、超时和
durable prepare 顺序直接访问 provider。本增量关闭这个组合缺口：

- 所有 descriptor/observation 调用经过 fixed-capacity、zero-queue supervisor；
- coordinator 固化 durable command、database-time fence、provider I/O 与 verified acceptance 的唯一顺序；
- exact accepted command replay 不再访问 provider；
- timeout、interrupt、adapter failure 和 invalid attestation 保持 `PREPARED`，不虚构 lifecycle fact；
- 真实 H2 journal、Ed25519 verifier 与 supervisor 组合证明 timeout recovery 和 queue-lease-loss observation。

本增量不是 orphan reconciler，也不投影 queue、slot、cancellation 或 natural terminal。

## 2. 固化调用顺序

`TestSuiteStabilityPhysicalAttemptObservationCoordinator.observe(...)` 只允许以下顺序：

```text
scoped journal.find
  -> terminal exact replay OR
bounded provider.descriptor
  -> durable journal.prepare(command, descriptor)
  -> database-time journal.authorizeInvocation(commandId)
  -> bounded idempotent provider.observe(command)
  -> journal.accept(commandId, detached attestation)
```

关键约束：

1. `POSITIVE/NON_CONFIRMING` replay 在 `find` 后直接返回，descriptor/observe I/O 均为 0；
2. retained `PREPARED` 必须重新获取 current descriptor，并由 journal 比对 frozen descriptor；
3. provider I/O 前必须再次经过 database time、retained start、deadline、process/revision floor 校验；
4. coordinator 不自行验签，也不自行分类 receipt，唯一 acceptance authority 是 durable journal；
5. journal 返回的 command/descriptor projection 必须与调用输入精确相等，否则视为内部 contract violation；
6. supervisor 生命周期由 composition root 管理，coordinator 不擅自关闭共享资源。

## 3. Observation Call Supervisor

`TestSuiteStabilityPhysicalAttemptObservationCallSupervisor` 使用固定大小 platform daemon worker 和
`SynchronousQueue`：

| 语义 | 约束 |
| --- | --- |
| 容量 | 1..32 fixed workers |
| 排队 | 0；无空闲 worker 时立即 `SATURATED` |
| descriptor timeout | 100 ms..30 s，毫秒精确 |
| observation timeout | 100 ms..5 min，毫秒精确 |
| adapter diagnostics | 不进入 exception message/cause |
| caller interrupt | 恢复 interrupt flag，返回 `CALLER_INTERRUPTED` |
| non-cooperative adapter | 继续占用原 slot，并进入 `lingeringCalls` |
| close | 拒绝新调用，请求 interrupt，不无界等待 adapter |

`TIMED_OUT/CALLER_INTERRUPTED/UNAVAILABLE` 只是本地等待结果。它们不证明 provider 未执行，也不证明
attempt 处于 `START_PENDING/RUNNING/TERMINAL/NOT_OBSERVED/INDETERMINATE` 中的任何状态。

snapshot 仅包含 policy、单调 outcome counters、active/lingering occupancy 和 closed flag，不包含 command、
provider diagnostics、业务 payload 或 credential。

## 4. Durable Failure And Replay Semantics

| 位置 | 失败结果 | durable truth |
| --- | --- | --- |
| descriptor 前 | `CLOSED/SATURATED/TIMED_OUT/...` | command 尚未 prepare |
| prepare | journal conflict | 无 provider observation I/O |
| authorize | database-time/state conflict | command 保持 `PREPARED` |
| observe | timeout/interrupt/adapter failure | command 保持 `PREPARED`，远端结果未知 |
| accept | signature/time/sequence/state conflict | acceptance 回滚，command 保持 `PREPARED` |
| accepted replay | `REPLAYED` | exact retained terminal fact，provider I/O 为 0 |

`NON_CONFIRMING` 是 immutable command 的终态，不是 attempt non-start 或 termination proof。后续 reconciliation
必须生成新的 content-addressed command，并受 one-active-query、provider sequence 和 positive state floor 约束。

## 5. 真实持久化组合验证

在 `DatabaseTestSuiteStabilityPhysicalAttemptObservationJournalTest` 中新增三条组合用例：

1. fresh coordinator path 接受真实 Ed25519 `RUNNING` receipt、写入 positive floor；同命令重放不再调用
   descriptor 或 observation；
2. supervisor 的 100 ms 本地 timeout 只留下 `PREPARED`；在 provider 声明的 1 s lifecycle-fact latency
   窗口内，用同一 command 和 descriptor 重试后可验签接受；
3. confirmed start 的 queue lease 已丢失时，coordinator 仍可 observation 并接受 positive fact，证明
   observation lane 不错误依赖 live worker lease。

测试夹具刻意让 provider 只在 durable prepare/authorize 后生成 `confirmedAt`。预先签发的 receipt 会被
journal 以 `OBSERVATION_PRECEDES_PREPARATION` 拒绝；超过 descriptor latency commitment 的 receipt 会被
verifier 以 `TIME_INVALID` 拒绝。这两个失败均在开发验证中实际触发并保留生产校验不变。

## 6. 已执行验证

Supervisor 单元测试：

```text
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
```

Coordinator 单元测试：

```text
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
```

Database journal 行为、并发、篡改与新增 coordinator 组合测试：

```text
Tests run: 22, Failures: 0, Errors: 0, Skipped: 0
```

完整 physical-attempt、observation、start、cancellation、reservation 与 durable journal 聚焦链：

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest='*PhysicalAttempt*,*AttemptCancellation*' test

Tests run: 162, Failures: 0, Errors: 0, Skipped: 0
```

两个新增公共类型使用 Maven 完整 compile classpath 通过：

```text
javadoc --release 25 -Werror -Xdoclint:all
0 warnings, 0 errors
```

完整项目门禁在实现提交 `aa0e08b5` 的 immutable source snapshot 上执行：

```text
snapshot: /private/tmp/bloge-examples-verify-aa0e08b5.k3LSYg
command: mvn -f resource-gateway-examples/pom.xml clean verify

Tests run: 4037, Failures: 0, Errors: 0, Skipped: 2
Total time: 07:28 min
Surefire XML: 455 files, tests=4037, failures=0, errors=0, skipped=2
Executable JAR: 39,536,280 bytes
Snapshot build/test processes after completion: 0
Chrome for Testing/ChromeDriver processes after completion: 0
```

JAR entry inspection 确认 `TestSuiteStabilityPhysicalAttemptObservationCallSupervisor` 及其 nested types 与
`TestSuiteStabilityPhysicalAttemptObservationCoordinator` 均进入 `BOOT-INF/classes`。因此 Maven 汇总、结构化
XML、发布制品和进程收敛四个观察面一致；该结论只覆盖此 immutable snapshot，不以共享 worktree 的缓存或
进程状态替代证据。

## 7. 尚未闭合

- 没有按 database time、retention、backoff、attempt budget 和 fairness claim orphan 的 durable reconciler；
- 没有 start/cancel/observation 跨 family 的统一 provider fact sequence ledger；
- positive `TERMINAL` 尚未与 queue parent、slot permit 和 cancellation receipt 双线性化；
- `CANCELLED` terminal disposition 尚未强制链接 exact accepted cancellation receipt；
- command/journal 没有 retention tombstone、external anchor 和 historical dynamic trust verification；
- 没有真实 process/container/VM provider、Spring/HTTP/Schema/test-kit/capability wiring。

因此 product capability 继续关闭。下一增量应实现 database-clock bounded orphan reconciler；在终态投影与
slot release 双线性化完成前，reconciler 只可推进 observation journal，不得宣称 queue 或资源配额已经收敛。
