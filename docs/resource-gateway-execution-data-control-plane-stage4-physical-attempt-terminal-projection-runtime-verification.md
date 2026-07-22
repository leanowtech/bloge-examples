# Resource Gateway Stage 4 Physical Attempt Terminal Projection Runtime Verification

## 1. 增量目标

前十五个增量已经具备 exact-source transaction、durable work lifecycle、bounded worker 和产品 proof
resolver，但它们仍是不会自行运行的内核。病根不是再补一个 service method，而是缺少唯一生命周期所有者：
没有 scheduler 就没有活性；没有 Spring composition 就无法证明实际运行的是同一组 authority；没有 health
与 telemetry，启动成功也无法判断 worker 是否已关闭、阻塞、积压或永久隔离。

本增量建立以下 test/staging 产品运行链：

```text
database terminal work
  -> fixed-delay scheduler (1..32 local lanes)
  -> one-shot worker
  -> zero-queue call supervisor
  -> exact-source coordinator
  -> authoritative proof resolver
  -> database terminal projection transaction
  -> fenced work completion
```

Spring 是 supervisor 与 scheduler 的唯一进程内 owner。数据库 lease/fence 仍是跨副本 authority，Java
线程、interrupt 和本地计数都不拥有业务提交权。

## 2. 装配与信任边界

`TestSuiteStabilityPhysicalAttemptTerminalProjectionRuntimeConfiguration` 同时受以下条件约束：

| 条件 | 结果 |
| --- | --- |
| 任意 active profile 含 `production` | 整个 composition 物理缺席 |
| `test`/`staging`，`enabled=false` 或未配置 | 所有 terminal projection runtime bean 缺席 |
| 启用但缺任一 start/observation/cancellation verifier | Spring 启动失败 |
| 启用但 queue 不是 `DatabaseTestSuiteStabilityJobRepository` | 启动失败 |
| 启用且依赖、policy 全部合法 | 创建 journals、resolver、coordinator、worker、scheduler、telemetry、health |

配置层没有生成临时密钥，也没有把空 trust inventory 当作本地开发默认值。embedder 必须显式提供三类
pinned provider attestation verifier。queue 必须是隔离 test-runtime datasource 上的 database 实现，确保
queue terminal winner、projection append 与 physical slot release 仍由同一 transaction authority 决定。
同一个 terminal-projection switch 会让基础 queue 通过 fence-aware 构造器开启 physical-attempt fencing，
composition 随后读取 `physicalAttemptFencingEnabled()` 二次验证；即使类型正确，只要仍是 legacy
fence-off queue 也拒绝启动。这避免 expired queue lease 在 provider side effect 未确认终态时重新释放
capacity。

默认 database adapter 包含 reservation、start、observation、cancellation、terminal-work 和 projection
journal。自定义 adapter 可以替换接口 bean，但 queue transaction authority 不允许替换成泛化 remote adapter。

## 3. Policy 交叉不变量

strict `@ConfigurationProperties(ignoreUnknownFields=false)` 在创建线程前验证所有局部和跨组件约束：

- poller 为 1..32，且不超过 zero-queue coordinator call capacity；
- poll、deadline、drain、lease 与 retry 均为有限且 millisecond-exact 的 duration；
- `maximumProjectionTimeout + completionReserve < workLeaseDuration`；
- poll interval 不大于 actionable-work readiness SLO；
- worker id 仅在启用时必填并满足固定 identifier grammar；
- claim inspection、quarantine threshold 和 retry ceiling 有硬上界；
- 未知 key、部分配置和不安全组合统一 fail startup，异常不回显 worker identity 或 trust material。

test/staging YAML 只提供默认关闭的环境变量入口。standalone demo 不自动启用，因为当前 demo 还没有受治理的
provider trust inventory bean；让它 fail closed 比生成本地伪信任更诚实。

## 4. Scheduler 与关闭语义

每个 lane 使用 `scheduleWithFixedDelay`，因此同一 lane 永远只有一个同步 `processNext`。scheduler 不维护
业务队列，不复制 attempt identity，也不在进程内重试一次调用。worker 的 bounded result 驱动下一次固定
delay；throw 或 null result 被记录为 unexpected poll，但 lane 继续存活。

关闭顺序是：

1. scheduler 禁止新 poll 并取消 delayed future；
2. 在配置的 drain deadline 内等待当前 one-shot worker；
3. 超时后请求 interrupt；
4. scheduler 销毁完成后，Spring 再关闭其依赖的 call supervisor；
5. 迟到调用仍由 supervisor lingering occupancy 与 database lease fence 约束。

interrupt 不是 terminal proof。无法及时停止的 coordinator 不能绕过 content-addressed projection replay 和
数据库 completion fence。

## 5. Telemetry 与 readiness

Micrometer 只注册两组闭合标签：worker `Outcome` 与 `LocalDisposition`。configured、active、closed 是
无标签 gauge，unexpected 是无标签 counter。禁止 tenant、environment、attempt、job、lease、owner、provider、
fingerprint、exception 或 message 进入指标。metric backend 故障只记录一次无诊断 warning，不杀 worker lane。

Actuator health 每次读取三份无 payload 快照：database work aggregate、scheduler、call supervisor。状态闭集为：

| status | 判定 |
| --- | --- |
| `READY` | 生命周期开放，最新 poll 有界，容量与 backlog 在 SLO 内 |
| `CLOSED` | scheduler 或 supervisor 已关闭 |
| `SCHEDULER_FAILED` | 最新 poll throw 或返回 null |
| `COORDINATOR_CAPACITY_EXHAUSTED` | 所有 fixed slots 都被 lingering call 占据 |
| `QUARANTINE_SLO_VIOLATED` | quarantined work 超过显式阈值 |
| `ACTIONABLE_AGE_SLO_VIOLATED` | oldest due/expired work 的数据库时钟年龄超限 |
| `UNAVAILABLE` | 任一 aggregate snapshot 读取或校验失败 |

actionable age 使用 work journal 同一 snapshot 的 `observedAt - oldestActionableAt`，不受 JVM clock drift
影响。health details 只有固定计数、枚举、容量和数据库 observation time；snapshot 异常文本不外泄。

## 6. 已执行验证

新增 23 项测试：7 项 scheduler、8 项 health、8 项 Spring composition。覆盖 bounded lanes、异常恢复、null
result、graceful drain、policy bounds、固定 metric cardinality、telemetry outage 隔离、六类 down state、
snapshot non-disclosure、profile/property 缺席、完整装配、缺 verifier、错 queue authority、未知配置、不安全
lease budget、legacy fence-off database queue、基础 queue 开关传播和关闭后 poller thread 清零。

聚焦内核与装配门禁：

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest='TestSuiteStabilityPhysicalAttemptTerminalProjectionRuntimeConfigurationTest,\
TestSuiteStabilityPhysicalAttemptTerminalProjectionSchedulerTest,\
TestSuiteStabilityPhysicalAttemptTerminalProjectionHealthTest,\
TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkerTest,\
TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisorTest' test

Tests run: 44, Failures: 0, Errors: 0, Skipped: 0
```

完整 physical-attempt/cancellation/queue 聚合门禁：

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest='*PhysicalAttempt*,*AttemptCancellation*,DatabaseTestSuiteStabilityJobRepositoryTest' test

Tests run: 331, Failures: 0, Errors: 0, Skipped: 0
```

四个新增 public 类型及 database queue fence accessor 使用 Maven compile classpath、空 sourcepath 通过：

```text
javadoc --release 25 -Werror -Xdoclint:all
0 warnings, 0 errors
```

完整 immutable snapshot `clean verify` 结果将在提交冻结后补录。

## 7. 能力边界与下一病根

本增量使“已注册 terminal work 的自主消费”成为真实 test/staging 产品路径，但没有宣告整个 physical-attempt
产品可用。observation reconciliation 目前只有 bounded reconciler core，没有 Spring-owned scheduler；因此
它还不能持续发现 immutable start orphan、获取 provider terminal observation，并在同一 completion transaction
自动注册 terminal projection work。capability 继续关闭，避免把局部 worker 活性误报成端到端活性。

本增量局部质量评估为 **92/100**。生命周期、资源上界、信任依赖、关闭顺序、可观测性和 fail-fast 已闭合；
扣分来自静态 verifier 仍由 embedder 注入、没有动态 signed trust inventory/cohort，health 只有本地实例与共享
backlog 视角，且 upstream observation reconciliation 尚未产品装配。

相对两份工业级可测试性计划的完整初始目标，当前估计仍有 **约 26% 的实质差距**，明显未进入允许完成的
正负 8% 区间。剩余项主要是：

- observation reconciliation autonomous scheduler、atomic work-registration 产品 wiring 与 N/N-1 backfill；
- capability truth、policy fingerprint/cohort readiness、跨副本 fairness 和 immutable attempt history；
- cancellation/observation/projection retention、tombstone、WORM、external anchor 与 operator runbook；
- dynamic signed provider inventory、真实 process/container provider、secret/HSM/KMS custody；
- production database、crash-point、HA/partition/chaos、容量与 DR 认证；
- 完整计划中的 streaming/suspendable evidence、真实 ANEKE conformance 与生产信任治理。

下一步应优先把 observation reconciliation 做成同样的 Spring-owned bounded runtime，并让 terminal work
registration 从 retained start 到 projection scheduler 端到端自动发生；随后 capability 才有资格陈述真值。
