# Stage 4 Physical Attempt Start Durable Journal Verification

## 1. 根问题

start proof kernel 只能判断一份 provider attestation 是否可信，不能保证外部调用前已经留下可恢复事实。
若 worker 直接调用 provider，会存在两个不可区分的崩溃窗口：

1. provider 启动成功、控制面尚未持久化回执；
2. 控制面调用超时、provider 随后完成启动。

两者都不能自动重试，否则同一逻辑 job 可能拥有两个物理进程。本增量建立 database-authoritative start
journal，把外部调用收敛为 `durable prepare -> live invocation authorization -> provider I/O -> verified
accept`。它仍是 correctness core，不开放 worker 或产品 capability。

## 2. 状态与真值

`TestSuiteStabilityPhysicalAttemptStartJournal` 定义三态：

| 状态 | 已知事实 | 禁止推断 |
| --- | --- | --- |
| `PREPARED` | exact command/provider binding 已持久化 | 不知道 provider 是否收到调用，也不知道进程是否存在 |
| `CONFIRMED` | `STARTED/ALREADY_STARTED` 签名回执已验证并原子接受 | 不表示进程仍然存活或 job 最终成功 |
| `UNCONFIRMED` | provider 签名了 `REJECTED` | 不证明 side effect 从未发生 |

本地 timeout、interrupt、adapter exception 或进程崩溃都不改写 `PREPARED`。`PREPARED` 必须进入后续
bounded reconciliation，不能由普通 worker 盲目重放 start。

## 3. 事务边界

### 3.1 Prepare

首次 `prepare` 在一个事务内：

1. 独立重算 nested physical identity 与 start command；
2. 锁定 `(tenant, environment, attempt, positive lease epoch)`；
3. 拒绝同一 fence 改绑 execution envelope 或 provider descriptor；
4. `FOR UPDATE` 锁定 exact queue row；
5. 复用 queue repository 验证完整 job，并独立验证 reservation 的全部列、JSON 与 record fingerprint；
6. 以 database time 验证 `RUNNING`、request fingerprint、owner、epoch、lease expiry 与 job deadline；
7. 冻结 exact command/descriptor 和 `PREPARED` whole-row commitment。

exact replay 即使随后失租或过期仍可读取 retained fact，但不能借 replay 绕过下一次 invocation
authorization。

### 3.2 Invocation authorization

`authorizeInvocation` 只接受 exact `PREPARED` entry，再次锁 queue row，并重新验证 reservation、job
integrity、owner/epoch/lease/deadline 和 descriptor 剩余最大延迟。它的线性化点允许“先授权、后取消”的
竞态产生一个合法 start；因此取消路径必须继续追踪该 attempt，而不能把 queue 状态当作 non-start proof。

### 3.3 Acceptance

`accept` 不要求 queue lease 仍然 active。dispatch 之后若 lease 变化，真实签名回执仍必须进入控制面，
否则会制造不可见 orphan。acceptance 在同一事务内：

- 使用 database observation time 运行 pinned Ed25519 verifier；
- 拒绝 `confirmedAt < preparedAt`，保证 durable prepare 的因果先序；
- 接受 deadline 前由 provider 确认但因网络延迟在 deadline 后到达的回执；
- 锁定 provider/deployment scope，追加 immutable provider sequence；
- 拒绝 sequence rollback，推进 whole-row protected deployment floor；
- 原子写入 `CONFIRMED/UNCONFIRMED` 与 exact attestation。

terminal exact replay 不推进 sequence；不同 attestation 不得改写 terminal fact。

## 4. 存储与完整性

`DatabaseTestSuiteStabilityPhysicalAttemptStartJournal` 建立五组 payload-free 表：

- attempt/lease lock；
- command journal entry；
- provider/deployment lock；
- monotonic provider floor；
- append-only provider sequence。

表内只包含 protocol JSON、opaque envelope commitment、identity、时间和 fingerprint，不包含 fixture、业务
payload、credential、PID 或 provider diagnostic。读取 terminal entry 时必须同时找到它的 immutable
sequence，并验证当前 floor 与最新 sequence 一致。普通 SHA-256 whole-row commitment 用于发现非原子写入、
应用 bug 和意外篡改，不宣称抵抗能够重算全部哈希的恶意数据库管理员；外部锚与 retention/tombstone 仍待
后续增量。

## 5. Verification

聚焦 journal 门禁：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DatabaseTestSuiteStabilityPhysicalAttemptStartJournalTest test
```

结果为 22 tests，0 failures、0 errors、0 skips，覆盖：

- prepare/authorize/confirmed accept、exact prepare/terminal replay；
- lease loss 后 replay 可读、invocation 被拒，但真实 start receipt 仍可接受；
- command mutation、同 attempt 改绑 envelope、terminal rewrite；
- invalid signature rollback、signed rejection 只能进入 `UNCONFIRMED`；
- 本地 unknown 保持 `PREPARED`、missing reservation、scope hiding；
- expired/incompatible descriptor 与剩余 provider window；
- 按时确认但迟到的 receipt、早于 durable prepare 的 receipt；
- reservation、entry、provider floor、provider sequence 篡改或缺失；
- exact prepare 并发、prepare/retry 竞态线性化、跨 replica provider-sequence 竞争与恢复。

跨 queue/runtime 边界联合门禁：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DatabaseTestSuiteStabilityPhysicalAttemptStartJournalTest,\
DatabaseTestSuiteStabilityPhysicalAttemptRegistryTest,\
TestSuiteStabilityPhysicalAttemptStartVerifierTest,\
TestSuiteStabilityPhysicalAttemptStartCallSupervisorTest,\
DatabaseTestSuiteStabilityJobRepositoryTest,\
TestSuiteStabilityJobExecutionCoordinatorTest,\
TestSuiteStabilityJobWorkerTest,\
TestSuiteStabilityJobSchedulerTest,\
TestSuiteStabilityJobServiceTest test
```

结果为 127 tests，0 failures、0 errors、0 skips。两个新增 public 类型通过
`javadoc --release 25 -Werror -Xdoclint:all`，0 warnings、0 errors。

### 5.1 Immutable full gate

实现提交 `4142f0a9` 的 immutable `git archive` 快照执行：

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
```

- Maven：3972 tests，0 failures，0 errors，2 skips，`BUILD SUCCESS`；
- structured cross-check：450 份 Surefire XML 合计 3972 tests，0 failures，0 errors，2 skips；
- artifact：Spring Boot executable JAR 成功生成，39,441,632 bytes；
- lifecycle：门禁退出后没有快照 Maven、ChromeDriver 或 Chrome for Testing 残留进程；
- wall time：7 分 30 秒。

该结果证明 journal 与完整数据库/API/真实浏览器回归、线程生命周期和可执行打包兼容，不证明尚未实现的
真实 provider、worker dispatch、跨主机 cancellation 或 orphan reconciliation 已具备生产能力。

## 6. 尚未开放

本增量没有 Spring bean、HTTP route、Schema、test-kit 或 capability advertisement。以下事项仍是启用
physical attempt runtime 的硬前置：

1. start coordinator 的 Spring/runtime composition 与 provider inventory binding；
2. 真实 process/container/VM provider 与 execution-envelope vault；
3. queue `RUNNING`、start journal、cancellation journal 和 natural terminal 的双线性化投影；
4. timeout、`UNCONFIRMED`、late receipt、deployment drift 的 bounded reconciliation；
5. provider-signed natural terminal receipt 与 exact process identity closure；
6. slot 在 provider-confirmed terminal 或显式 orphan occupancy 前不得释放；
7. retention/tombstone/external anchor、动态 signed provider inventory、health/SLO 与生产数据库/HA/DR/chaos。

start coordinator 及其真实 journal/supervisor 组合门禁已经落地，见
[physical attempt start coordinator verification](resource-gateway-execution-data-control-plane-stage4-physical-attempt-start-coordinator-verification.md)。
下一增量应引入明确限定 test/staging 的 isolated provider composition 和 faithful lifecycle harness，但仍不
应在缺少 queue/cancellation/slot 投影前直接改造 worker。
