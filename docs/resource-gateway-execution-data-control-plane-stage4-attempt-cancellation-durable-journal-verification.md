# Resource Gateway Stage 4 Attempt Cancellation Durable Journal 验证

## 1. 要关闭的崩溃窗口

proof kernel 能验证一次 provider response，却不能回答跨进程问题：命令发出后 worker crash，successor
不知道该重试、查询还是创建新 challenge；回执验证后进程在写 queue 前 crash，证明丢失；provider 重启后
sequence 回退，同一 attempt 又可能被另一命令接管。

第二增量引入 `TestSuiteStabilityAttemptCancellationJournal`，把 provider RPC 前后的事实分成两个数据库
线性化点。它没有提前修改 queue status，先让 cancellation proof 本身可恢复、可幂等、可审计。

## 2. 状态机

```text
ABSENT --prepare(exact command + descriptor)--> PREPARED
PREPARED --accept(verified TERMINATED/ALREADY_TERMINAL)--> CONFIRMED
PREPARED --accept(verified NOT_FOUND/REJECTED)-----------> UNCONFIRMED
```

- exact `prepare` replay 返回 retained entry，即使原始新调用 deadline 已过；
- 同一 tenant/environment/attempt/lease epoch 只能有一个 command，换 challenge、reason 或 binding 均冲突；
- terminal entry 只能 exact replay，不能用更新 sequence 或另一 receipt 覆盖；
- signature、time、binding 或 sequence 验证失败时整个事务回滚，entry 保持 `PREPARED`；
- `UNCONFIRMED` 是需要 reconciliation 的事实，不会被投影为 provider-confirmed cancellation。

## 3. 数据库权威

`DatabaseTestSuiteStabilityAttemptCancellationJournal` 使用五组 payload-free 结构：

| 结构 | 作用 |
| --- | --- |
| attempt/epoch lock | 跨副本串行 prepare 与 terminal update |
| provider/deployment lock | 跨副本串行 provider sequence |
| command entry | 冻结 command、descriptor、status 与 optional attestation |
| provider sequence journal | append-only sequence/command/attestation commitment |
| provider floor | 当前 deployment 的 exact latest sequence |

`accept` 先锁 exact attempt，再以数据库时间调用 verifier；验证通过后锁 provider scope，校验 floor 与 latest
sequence journal 连续性，追加新 sequence、推进 floor、更新 terminal entry，全部在一个
`REQUIRES_NEW/READ_COMMITTED` 事务内。任何中途异常都不能留下“floor 已推进但 receipt 未落库”或反序状态。

每个 entry、floor 和 sequence row 都有 canonical whole-row fingerprint。terminal entry 每次读取都反查它
自己的 immutable sequence row，并校验当前 floor 与 latest journal 一致；删 sequence、改 status/sequence
或直接回滚 floor 都会 fail closed。该承诺防普通损坏和未同步写，不把数据库管理员可同时重写所有行的能力
误称为外部防篡改；外部 witness/WORM 仍属后续阶段。

## 4. 失败语义

| 场景 | 结果 |
| --- | --- |
| exact prepare/accept replay | `REPLAYED`，不新增 sequence |
| same attempt/epoch, different command | `ATTEMPT_COMMAND_CONFLICT` |
| terminal receipt rewrite | `IDEMPOTENCY_CONFLICT` |
| new receipt sequence <= durable floor | `PROVIDER_SEQUENCE_ROLLBACK` |
| absent command | `COMMAND_NOT_PREPARED` |
| new command database-time expired | `COMMAND_EXPIRED` |
| provider unavailable or declared latency exceeds command window | `PROVIDER_INCOMPATIBLE` |
| invalid signature/binding/time | verifier closed failure，事务回滚 |
| signed `NOT_FOUND/REJECTED` | durable `UNCONFIRMED` |
| row/floor/sequence corruption | integrity exception，拒绝投影 |

## 5. 验证

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DatabaseTestSuiteStabilityAttemptCancellationJournalTest test
```

结果为 13 tests，0 failures、0 errors、0 skips，覆盖：

- prepare/confirmed accept/find 的完整往返和 payload-free sequence 落库；
- exact prepare/terminal replay、deadline 关闭后的 retained replay；
- attempt/epoch 唯一绑定、terminal rewrite 拒绝；
- 两个 attempt 的 sequence advance、rollback 后 PREPARED 可恢复；
- 错签名事务回滚、signed `NOT_FOUND` 只能进入 `UNCONFIRMED`；
- tenant/environment 不可见、expired/incompatible preflight；
- entry/floor/sequence 篡改或缺失失败关闭；
- 两线程 exact prepare 只有一个 creator，另一个得到 replay。

## 6. 尚未完成

本增量没有 Spring bean、HTTP route、JSON Schema、test-kit 类型或 capability advertisement。进入产品 worker
前仍需完成：

1. command/receipt retention、HMAC tombstone、legal hold 与容量/SLO；
2. dynamic signed provider inventory、key revocation、trust-generation floor 与 N/N-1 rollout；
3. queue `CANCEL_REQUESTED` 与 journal `CONFIRMED` 的双线性化状态机；
4. worker slot 只在 confirmed receipt 后释放，unconfirmed/timeout 进入 bounded orphan lane；
5. process/container provider 的 start identity、exit watcher、late receipt 与 orphan reconciliation；
6. crash/DR/非 H2/并发负载认证及外部 tamper-evident anchor；
7. strict Schema、independent test-kit verifier、health/telemetry/readiness/capability 与 test/staging composition。

因此当前准确表述是“provider-confirmed cancellation 已有可持久化证明内核”，不是“Resource Gateway 已能
强杀任意算子”。
