# Stage 4 Physical Attempt Lifecycle Observation Durable Journal Verification

## 1. 为什么 proof kernel 之后仍需要 durable journal

lifecycle observation verifier 只能判断一份 provider attestation 在当前调用中是否可信，不能解决多副本、
进程崩溃、迟到回执和并发查询下的历史连续性。若每个 worker 只在内存中记住“上次看到 RUNNING”，一次
`NOT_OBSERVED`、replica 切换或旧回执晚到都可能错误覆盖真实进程，继而触发重复 start、提前释放 slot 或
错误结束 queue parent。

本增量新增 database-authoritative observation journal，把以下三条事实链分开持久化并在事务中协调：

1. 每条 observation command 的 immutable `PREPARED -> POSITIVE/NON_CONFIRMING` 生命周期；
2. provider/deployment 范围的 monotonic observation sequence；
3. 每个 physical attempt 独立的 latest positive lifecycle state floor。

负向 observation 只进入第一、二条链，永远不能更新第三条链。

## 2. 公共接口

`TestSuiteStabilityPhysicalAttemptObservationJournal` 提供：

| API | 语义 |
| --- | --- |
| `prepare(command, descriptor)` | provider I/O 前冻结 exact observation/start/provider/process/revision binding |
| `authorizeInvocation(commandId)` | 立即调用 provider 前以 database time 重验 command window、descriptor 与最新 positive floor |
| `accept(commandId, attestation)` | 验证 Ed25519 receipt，并原子推进 command、provider sequence 和可选 positive floor |
| `find(tenant, environment, commandId)` | scope 隐藏且完整性验证的 command projection |
| `latestPositive(tenant, environment, attemptId)` | 不受后续 non-confirming receipt 覆盖的 attempt 正向事实 |

单条 command 的 `Status` 是 `PREPARED/POSITIVE/NON_CONFIRMING`。attempt 级 `PositiveState` 是独立
projection，不复用 command status；这避免把“本次查询无事实”误写成“attempt 不存在”。

## 3. 数据库线性化模型

实现新增六张 payload-free 表：

| 表 | 职责 |
| --- | --- |
| `rg_test_stability_attempt_observation_locks` | attempt 范围 prepare/state transition 串行化 |
| `rg_test_stability_attempt_observation_provider_locks` | provider/deployment sequence 串行化 |
| `rg_test_stability_attempt_observation_entries` | immutable command、descriptor、attestation 与 whole-row commitment |
| `rg_test_stability_attempt_observation_state_floors` | latest coherent positive state，链接 establishing command/attestation |
| `rg_test_stability_attempt_observation_provider_sequences` | append-only accepted observation sequence |
| `rg_test_stability_attempt_observation_provider_floors` | exact latest sequence 与 append row continuity |

`accept` 的事务顺序是：

```text
lock attempt
  -> validate prepared command + retained start
  -> verify signed observation using database observedAt
  -> lock provider/deployment
  -> verify provider sequence ledger/floor
  -> verify attempt positive state transition
  -> append provider sequence + advance provider floor
  -> optionally advance positive state floor
  -> transition command entry
commit
```

任意 signature、time、sequence、state、process 或 integrity 校验失败都会回滚整个事务，command 保持
`PREPARED`，所有 floor 不推进。

## 4. queue lease 与 start retention

start dispatch 必须持有 live queue lease；observation 则有意允许 lease loss 后继续。它的目的就是回答
“本地已经失去执行控制，但远端物理副作用是否存在”。因此 `prepare/authorizeInvocation` 不要求 queue
仍为 `RUNNING`，但必须通过 start journal 的 scope 与完整性验证，证明 exact original start command 仍被
保留。

这形成明确 retention 约束：start command、observation entry、positive floor 和 provider sequence 的清理
必须按依赖顺序协调。原始 start row 先被删除时，observation prepare/find 会以
`START_COMMAND_NOT_RETAINED` 或完整性失败关闭，不会把失联解释为 non-start。

## 5. in-flight 与迟到回执

同一 attempt 同时只允许一条未过期 `PREPARED` observation，防止故障时形成 provider query storm。旧命令
过期后允许创建 recovery command；旧 command 本身仍保留，以便合法迟到 receipt 到达时完成验证。

迟到 receipt 必须满足两层约束：

- provider `confirmedAt` 位于原 command deadline 和 descriptor latency 内；本地收到时间可以更晚；
- 接受时仍须高于 provider sequence floor，且不得回退 attempt revision、替换 process、回退 lifecycle 或
  重写 terminal fact。

这一区分保留真实 provider fact，同时阻止过期 replica 用旧事实覆盖新状态。

## 6. positive state 单调规则

| 当前 floor | 新 receipt | 结果 |
| --- | --- | --- |
| absent | 任意 positive state | 建立 floor |
| `START_PENDING(r)` | `START_PENDING(r+1)` / `RUNNING(r+1)` / `TERMINAL(r+1)` | 可推进 |
| `RUNNING(r)` | 同 process 的 `RUNNING(r+1)` / `TERMINAL(r+1)` | 可推进 |
| 任意 positive | same revision + exact same state fact | 接受 command，保留原 floor |
| 任意 positive | lower revision | `ATTEMPT_REVISION_ROLLBACK` |
| 已知 process | 不同 process | `PROCESS_IDENTITY_CONFLICT` |
| higher revision + lower state rank/effective time | `LIFECYCLE_STATE_ROLLBACK` |
| `TERMINAL` | 任意不同 positive fact | `TERMINAL_STATE_CONFLICT` |
| 任意 positive | `NOT_OBSERVED/INDETERMINATE` | 接受 non-confirming command，positive floor 不变 |

state-fact fingerprint 排除 observation command id、provider observation sequence 和 receipt confirmation
time，只承诺 start/attempt/process/runtime/terminal/evidence/state-effective 语义。由此不同查询可以证明同一
状态，而不会伪造一次新的 lifecycle transition。

## 7. 完整性与信任边界

- command entry、positive floor、provider sequence 与 provider floor 均有独立 canonical fingerprint；
- positive floor 必须反向解析其 establishing command，验证 entry、attestation、provider sequence 与 start
  continuity；
- provider floor 必须与 append-only sequence 中的 latest row 完全一致；
- historical read 验证存储连续性，不按当前 trust inventory 重解释历史签名；
- 普通 SHA-256 row commitment 检测意外或局部篡改，不防御能重算全部哈希的恶意数据库管理员；外部
  append-only/WORM anchor 尚未接入。

当前 provider sequence 是 observation command family 内的独立 sequence domain。start、cancellation、
observation 三个 family 尚未合并为 provider 全局 signed-fact ledger；跨 family rollback/equivocation 需要
后续统一 provider lifecycle ledger 或外部 witness 才能关闭。

## 8. 已执行验证

durable journal 聚焦测试：

```text
Tests run: 19, Failures: 0, Errors: 0, Skipped: 0
```

覆盖：

- prepare/authorize/positive accept 与 exact replay；
- non-confirming receipt 不建立、不覆盖 positive floor；
- 同一 state fact 的重复签名不虚构新 lifecycle transition；
- `START_PENDING -> RUNNING -> TERMINAL` 单调演进；
- queue lease loss 后 orphan observation；
- active in-flight 限流与 expired recovery；
- 合法迟到 receipt 的 revision rollback 和 process replacement；
- higher-revision late receipt 仍不得把 `RUNNING` 回退为 `START_PENDING`；
- terminal rewrite、stale command state fence；
- 跨 attempt provider sequence rollback 与更高 sequence 恢复；
- invalid signature 全事务回滚；
- missing original start、confirmation-before-prepare；
- entry/provider floor/state floor/sequence 篡改与 scope hiding；
- 两个独立 journal 实例并发竞争同一 provider sequence，只有一个提交。

start、observation、cancellation、reservation 和三类 durable journal 的聚焦链执行 142 tests，
0 failures、0 errors、0 skips。两个新增公共类型和修改后的 receipt 通过：

```text
javadoc --release 25 -Werror -Xdoclint:all
0 warnings, 0 errors
```

完整 `clean verify` 将在本增量实现 commit 的 immutable snapshot 上执行并回填。

## 9. 尚未闭合

- 没有 observation authority call supervisor 与 coordinator；调用顺序尚未形成可复用组合根；
- 没有按 retention/deadline/backoff/attempt budget claim orphan 的 durable reconciler；
- positive `TERMINAL` 尚未与 queue parent、cancellation journal、slot permit 双线性化；
- `CANCELLED` terminal disposition 尚未强制链接 exact cancellation receipt；
- 没有 start/cancel/observation 统一 provider fact ledger；
- 没有 retention/tombstone、外部 anchor、dynamic trust historical verification；
- 没有真实 process/container/VM provider、Spring/HTTP/Schema/test-kit/capability wiring。

因此 product capability 继续关闭。下一增量应先实现 fixed-capacity/zero-queue observation call supervisor
与 coordinator，再实现 database-clock bounded reconciler；在自然终态与 slot release 双线性化完成前，
现有 synchronous worker 不应切换到 physical provider。
