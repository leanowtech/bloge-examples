# Stage 4 Physical Attempt Lifecycle Observation Proof Kernel Verification

## 1. 本增量解决的根问题

physical-attempt start coordinator 只能得到三种本地事实：`CONFIRMED`、`UNCONFIRMED`、`PREPARED`。
后两者都不能回答 provider 是否已经接受命令、是否创建了进程、进程是否仍在运行或是否已经自然结束。
本地 timeout、线程 interrupt、adapter exception、签名 `REJECTED` 和 provider `NOT_FOUND` 都不是
non-start proof。若直接重试 start，会制造重复物理执行；若直接释放 slot 或取消 queue parent，又可能让仍在
运行的业务逻辑脱离治理。

本增量新增 challenge-bound、provider-signed lifecycle observation proof kernel，使后续 durable
reconciliation 能依据经过验证的外部事实收敛，而不是依据本地异常猜测远端状态。

## 2. 协议组成

| 类型 | 职责 | 关键约束 |
| --- | --- | --- |
| `TestSuiteStabilityPhysicalAttemptObservationCommand` | 内容寻址的状态查询命令 | 嵌入 exact start command；绑定可选已知 process fingerprint、minimum attempt revision、deadline、32-byte challenge |
| `TestSuiteStabilityPhysicalAttemptObservationReceipt` | payload-free provider 状态事实 | 回绑 observation/start/identity/lease epoch/provider/deployment/isolation；携带 provider sequence 与 attempt revision |
| `TestSuiteStabilityPhysicalAttemptObservationAuthority` | provider observation SPI | descriptor 声明 exact deployment/key、支持的物理边界、最大延迟和最小状态保留期 |
| `TestSuiteStabilityPhysicalAttemptObservationVerifier` | 独立信任与语义验证 | 重算三层内容身份，验证 binding/time/revision/process fence/Ed25519 trust，不信任 Java 对象来源 |

command 与 receipt 都不包含 fixture、业务 input/output、credential、原始 PID、container id、VM id 或 provider
诊断。process、runtime state 和 terminal evidence 均以 `sha256:` commitment 表示。

## 3. 封闭状态语义

| state | provider 可以证明什么 | 不可以推导什么 | receipt shape |
| --- | --- | --- | --- |
| `START_PENDING` | exact start 已被 provider 持久接受 | 具体进程已经存在 | positive attempt revision + runtime-state fingerprint；无 process fingerprint；继续 reconciliation |
| `RUNNING` | exact isolation boundary 中的 exact process 正在运行 | queue parent 可以结束或 slot 可以释放 | positive revision + process/runtime fingerprints |
| `TERMINAL` | exact process 已经终止且存在终态证据 manifest | queue 已经原子投影为 terminal | positive revision + process/runtime/evidence fingerprints + closed disposition |
| `NOT_OBSERVED` | 本次查询时 provider 没有可返回的 retained fact | 没有启动过、已经终止、可以安全重试 | revision `0`，无 process/runtime/evidence fingerprint |
| `INDETERMINATE` | retained facts 无法形成一致的正向状态 | 任意 start/terminal 结论 | revision `0`，无 process/runtime/evidence fingerprint |

`TERMINAL` disposition 是封闭集合：`SUCCEEDED`、`FAILED`、`CANCELLED`、`TIMED_OUT`、
`PROVIDER_ABORTED`。非 terminal state 只能使用 `NONE`。所有 terminal outcome 都必须绑定 evidence
manifest fingerprint，避免“只有退出码、没有可审计事实”的伪终态。

## 4. 回退与进程替换防线

observation command 可以携带两道调用方已知事实：

1. `expectedProcessIdentityFingerprint`：一旦 start receipt 或历史 observation 已确认 process，后续正向
   observation 必须继续指向同一 process；不同 process 以 `PROCESS_BINDING_INVALID` 失败关闭。
2. `minimumAttemptRevision`：provider 的 positive attempt revision 不得低于调用方已接受的 revision；回退以
   `STATE_ROLLBACK` 失败关闭。

已知 process 后返回 `START_PENDING` 是语义回退，同样失败关闭。返回签名 `NOT_OBSERVED` 或
`INDETERMINATE` 则仍可被验证，但它们只能进入 reconciliation，不能覆盖已知 process/revision。这一区分
用于容纳 provider cache eviction、部署切换或短暂分区，同时不允许负向查询结果抹掉正向物理事实。

## 5. 时间与 retention 语义

- observation command 的确认窗口限定为 `100 ms..5 min`，所有时间均须 millisecond exact；
- receipt `confirmedAt` 必须位于 command 窗口和 descriptor latency 内，且不得超过 caller observation 加
  future skew；
- positive state 的 `stateEffectiveAt` 可以早于查询，但不得早于原始 start request；
- `NOT_OBSERVED/INDETERMINATE` 必须是本次 command 发出后的 contemporaneous observation，不能重放历史
  missing 结果；
- authority descriptor 明示 `1 min..30 days` 的 `minimumStateRetention`，为下一步 reconciler 的 retry
  horizon、告警和 orphan escalation 提供机器可读下界。

descriptor 的 retention 声明目前仍是 provider contract，不是外部 WORM 证明。后续 production provider
认证必须以故障注入验证 retention，并由 durable observation journal 记录超出 retention horizon 的
orphan 风险。

## 6. 信任与密码边界

verifier 按以下顺序失败关闭：

1. 重算 physical identity fingerprint 和 attempt id；
2. 重算原始 start command fingerprint 和 command id；
3. 重算 observation command fingerprint 和 command id；
4. 回绑 receipt 的 observation/start/attempt/identity/positive lease epoch；
5. 回绑 descriptor/provider/deployment/key/isolation mode；
6. 检查 expected process、minimum revision 和 time window；
7. 检查 pinned provider/deployment/key trust validity；
8. 验证完整 canonical receipt 的 detached Ed25519 signature。

失败只暴露封闭 `FailureReason`，不传播 provider、密码库或 payload 诊断。Java object provenance、mTLS
channel 和“来自同一进程”均不替代 detached attestation。

## 7. 已执行验证

聚焦门禁：

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=TestSuiteStabilityPhysicalAttemptObservationVerifierTest test

Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
```

覆盖：

- exact `RUNNING` process proof 与五种 terminal disposition；
- `START_PENDING` 不虚构 process identity；
- `NOT_OBSERVED/INDETERMINATE` 保持 non-confirming；
- 已知 process 的同身份 replay、进程替换和 pending downgrade；
- attempt revision rollback；
- cross-attempt、cross-start replay；
- nested start 与 observation command 篡改；
- provider availability、deployment/isolation drift；
- late/future/slow confirmation 与 stale non-confirming observation；
- expired trust、错误 Ed25519 signature、矛盾 state shape、非法 retention/window/challenge。

四个公共类型通过：

```text
javadoc --release 25 -Werror -Xdoclint:all
0 warnings, 0 errors
```

start、observation、cancellation、reservation 和 durable journal 的跨链路聚焦门禁执行 123 tests，
0 failures、0 errors、0 skips。

实现提交 `023d1a5a` 的 immutable snapshot
`/private/tmp/bloge-examples-verify-023d1a5a.ImPOMS` 执行：

```text
mvn -f resource-gateway-examples/pom.xml clean verify
Tests run: 3998, Failures: 0, Errors: 0, Skipped: 2
BUILD SUCCESS
Total time: 07:13 min
```

- 结构化解析 452 份 `TEST-*.xml`，汇总仍为 3998/0/0/2；
- repackaged executable JAR 为 39,472,644 bytes，并包含四个 observation 公共类型及其 nested types；
- 门禁退出后 snapshot Maven/Surefire 进程为 0，Chrome for Testing/ChromeDriver 进程为 0。

验证使用提交快照而非共享 worktree，避免后续编辑、并发 Maven 或浏览器进程污染验收对象。

## 8. 诚实边界与下一步

本增量是 proof kernel，不是可启用的 physical-attempt runtime：

- 没有 database-authoritative observation journal、provider sequence floor 或 attempt revision floor；
- 没有按 retention horizon 有界调度的 `PREPARED/UNCONFIRMED` reconciler；
- 没有把 start、observation、cancellation、natural terminal 与 queue parent 双线性化；
- 没有在 terminal proof 前延迟释放 local/global execution slot；
- 没有真实 process/container/VM provider，也没有 execution-envelope resolver；
- 没有 Spring/HTTP/Schema/test-kit/capability wiring；production capability 必须继续关闭。

下一增量应实现 durable observation journal 与 coordinator：调用前冻结 exact command/descriptor，使用
database time 授权 provider I/O，原子推进 provider sequence 与 per-attempt revision floor，并保留
non-confirming observation 而不覆盖已知 positive fact。随后才能实现 bounded reconciler、自然终态投影、
cancellation closure 和 slot 延迟释放。真实 provider 必须在独立 OS process/container/VM 中执行；进程内
thread harness 只能作为 test/staging contract fixture，不能宣称 `PROCESS` isolation。
