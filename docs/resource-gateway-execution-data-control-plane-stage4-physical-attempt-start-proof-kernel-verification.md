# Stage 4 Physical Attempt Start Proof-Kernel Verification

## 1. 为什么 reservation 之后还需要 start proof

physical-attempt reservation 只说明一个 live queue fence 被允许绑定到某个 provider deployment。它不能
回答 provider 是否收到请求、隔离进程是否已经启动、本地 timeout 后远端是否仍完成了副作用。若 worker
把 Java 返回对象或 `Future.cancel(true)` 当作事实，会产生两种危险误判：

- false running：provider 只接受了请求，系统却释放 queue 控制并宣称进程已运行；
- false non-start：调用方超时，provider 稍后启动进程，系统却重试并制造第二个物理 attempt。

本增量建立 cryptographic proof kernel，不开放产品接线。

## 2. Start protocol

`TestSuiteStabilityPhysicalAttemptStartCommand` 内容寻址以下材料：

- 完整 `TestSuiteStabilityPhysicalAttemptIdentity` 及其 fingerprint/attempt id；
- opaque `executionEnvelopeRef` 与 encrypted envelope fingerprint；
- caller request time、confirmation deadline 与 32-byte challenge。

command 不携带 fixture、业务 payload、credential、PID 或 provider diagnostic。provider 只能通过 opaque
ref 在隔离 runtime 内解析执行 envelope。

`TestSuiteStabilityPhysicalAttemptStartReceipt` 回绑 command、provider/deployment、attempt、identity
fingerprint、positive lease epoch、provider sequence、isolation mode、process identity commitment、runtime
state commitment 与 confirmation time。真值表为：

| outcome | process/runtime commitment | 是否证明启动 |
| --- | --- | --- |
| `STARTED` | 必须完整 | 是 |
| `ALREADY_STARTED` | 必须完整，代表 exact idempotent replay | 是 |
| `REJECTED` | 必须为空 | 否 |

`REJECTED` 只表示 provider 签名了拒绝决定，不能证明 side effect 从未发生。

## 3. Trust verification

`TestSuiteStabilityPhysicalAttemptStartVerifier` 使用 pinned provider/deployment/key Ed25519 trust：

1. 独立重算 nested physical identity fingerprint 和 attempt id；
2. 独立重算 start command fingerprint 和 command id；
3. 精确回绑 descriptor、provider、deployment、key、isolation、attempt、identity 与 lease epoch；
4. 验证 caller observation、provider latency、confirmation deadline、future skew 与 key validity；
5. 最后验证 detached Ed25519 signature。

任何失败只暴露 closed `FailureReason`，不保留 provider、crypto 或 envelope diagnostic。

## 4. Bounded provider boundary

`TestSuiteStabilityPhysicalAttemptStartCallSupervisor` 使用固定 1..32 platform daemon workers 和
`SynchronousQueue`：调用要么立即获得 slot，要么 `SATURATED`，不存在隐式无界排队。descriptor 与 start
分别有 100 ms..30 s 和 100 ms..5 min deadline。

本地 `TIMED_OUT`、`CALLER_INTERRUPTED`、`UNAVAILABLE`、`CLOSED` 均不是远端 non-start proof。adapter
忽略 interrupt 时，`activeCalls` 和 `lingeringCalls` 持续占用 slot，直到 adapter 真正返回；snapshot 仅含
固定基数计数。

测试还发现并修复了 cancellation supervisor 的既有计数 bug：null provider result 过去先增加
`completedCalls`，随后再增加 `failedCalls`，破坏 `terminal outcomes <= accepted`。现在 start/cancellation
两种 supervisor 都先验证 non-null，再提交 completed counter；null 只计一次 failed。

## 5. Verification

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=TestSuiteStabilityPhysicalAttemptStartVerifierTest,\
TestSuiteStabilityPhysicalAttemptStartCallSupervisorTest,\
TestSuiteStabilityAttemptCancellationCallSupervisorTest test
```

结果：24 tests，0 failures，0 errors，0 skips。其中新增覆盖为 17 项 start tests 和 1 项 cancellation
regression：

- real Ed25519 start/duplicate-start/rejection verification；
- cross-attempt replay、nested identity mutation、command mutation；
- provider/deployment/isolation/availability drift；
- late/future/slow confirmation、expired trust 与 invalid signature；
- contradictory receipt/challenge shapes；
- normal descriptor/start、zero-queue saturation、timeout + lingering、null/exception collapse；
- caller interrupt restoration、close behavior、policy/snapshot invariants。

五个新增 public types 通过 `javadoc --release 25 -Werror -Xdoclint:all`，0 warnings、0 errors。

## 6. 尚未开放

当前没有 Spring bean、HTTP route、Schema、test-kit 或 capability advertisement。下列事项完成前不得让
worker 调用 start authority：

1. database-authoritative start journal：先 durable prepare，再 provider I/O，再 verified accept；
2. attempt/lease 唯一 start command、immutable provider sequence 与 deployment anti-rollback floor；
3. timeout/unknown/rejected 的 bounded reconciliation，能路由到原 deployment 而不是当前 deployment；
4. provider-signed natural terminal receipt，与 cancellation receipt 共享 exact process identity；
5. queue state、global/tenant/local slot 和 terminal winner 的原子投影；
6. 真实 process/container/VM adapter、execution-envelope vault、network/secret isolation；
7. retention/tombstone/external anchor、health/SLO/capability 与生产数据库/HA/DR/chaos 认证。

下一增量应先做 durable start journal。否则 provider 调用成功但数据库提交失败时，系统仍无法区分
“运行中但失联”与“从未启动”，也无法安全决定重试或释放容量。
