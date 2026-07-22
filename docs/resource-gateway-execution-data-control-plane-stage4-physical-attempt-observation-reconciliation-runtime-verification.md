# Resource Gateway Stage 4 Physical Attempt Observation Reconciliation Runtime Verification

## 1. 增量目标

前十六个 physical-attempt 增量已经具备 durable start/observation facts、bounded reconciler、terminal
projection transaction、durable projection work 和自主 terminal worker，但链路中间仍需要人工触发
reconciler。病根不是缺少另一个查询接口，而是 retained start 没有产品级生命周期所有者：provider 已经
启动但响应丢失、进程重启或 completion 丢失时，系统不能证明这些 source 会持续被发现并最终进入 terminal
projection lane。

本增量建立第二个独立 opt-in 的 test/staging runtime：

```text
retained database start
  -> bounded fair discovery
  -> database reconciliation lease/fence
  -> fixed-delay scheduler (1..32 local lanes)
  -> exact provider/deployment authority resolver
  -> zero-queue descriptor/observation supervisor
  -> verified observation coordinator
  -> database reconciliation completion
  -> atomic terminal-projection work registration
  -> existing terminal-projection runtime
```

scheduler 只负责唤醒，不复制 durable queue、retry 或 business identity。数据库 journal 仍是 discovery、claim、
retry、quarantine、terminal completion 和下游 work registration 的唯一 authority。

## 2. 独立开关与装配边界

`TestSuiteStabilityPhysicalAttemptObservationReconciliationRuntimeConfiguration` 同时受 profile、独立 property
和已有 terminal runtime 约束：

| 条件 | 结果 |
| --- | --- |
| 任意 active profile 含 `production` | observation reconciliation composition 物理缺席 |
| `test`/`staging`，独立 `enabled=false` 或未配置 | 所有 reconciliation runtime bean 缺席 |
| reconciliation 开启但 terminal projection 关闭 | 缺 terminal-work journal，启动失败 |
| 缺 exact `AuthorityResolver` 或存在歧义 | 启动失败，不生成 permissive fallback |
| start、observation 或 terminal-work journal 不是默认 database adapter | 启动失败 |
| 全部依赖与 policy 合法 | 创建 database journal、supervisor、coordinator、reconciler、scheduler、telemetry、health |

第二开关避免 terminal lane 的既有部署在升级后突然开始 provider I/O。embedder 必须显式提供 exact
provider/deployment generation resolver；Resource Gateway 不猜测当前 provider，也不创建本地临时信任。
默认 composition 只接受三个 database journal，确保 verified terminal transition 与 projection-work
registration 能进入同一个 transaction manager。当前类型门禁依赖默认 composition 使用同一个
`TestRuntimeDatabase`，自定义 datasource 组合尚未形成可证明的 transaction-domain descriptor，因此不开放。

## 3. Policy 交叉不变量

strict `@ConfigurationProperties(ignoreUnknownFields=false)` 在创建线程或 provider call 前验证：

- poller 为 1..32，且不超过 zero-queue provider call capacity；
- descriptor timeout 与 observation timeout 之和严格小于 challenge confirmation window；
- confirmation window 与 lease safety margin 之和不超过 database reconciliation lease；
- poll interval 不大于 maximum actionable age readiness SLO；
- initial delay、poll、drain、call、lease、retry、horizon 和 SLO 均为有限、millisecond-exact 的 duration；
- discovery page、uncertainty budget、quarantine 和 undiscovered-source 阈值都有硬上界；
- worker identity 只在启用时要求，并使用固定 identifier grammar；
- 未知 key、缺依赖和不安全组合统一 fail startup，错误不回显 worker、provider 或业务 identity。

test/staging YAML 提供完整环境变量入口，默认关闭。standalone demo 没有受治理的 resolver 和真实 provider，
因此不会自动打开 terminal 或 reconciliation runtime。

## 4. Scheduler、原子性与恢复

每个 fixed-delay lane 同步执行一次 `reconcileNext(workerId)`。throw 或 null result 记录为 unexpected poll，
但不杀死后续 lane；metric backend 故障只记录一次固定 warning。scheduler close 先禁止新 poll，等待有界
drain，再请求 interrupt。interrupt 不证明 provider call 已取消，迟到 completion 仍必须通过数据库 lease
epoch/token/fingerprint fence。

最关键的提交边界位于
`DatabaseTestSuiteStabilityPhysicalAttemptObservationReconciliationJournal`：verified terminal completion 与
terminal projection work registration 通过 transaction-bound mutation 同事务执行。任一写入失败全部回滚；
exact completion replay 不重复注册；active、non-confirming、uncertain 和 local-backpressure 结果不会创建
terminal work。若 worker 在 observation fact 提交后失租，下一 owner 读取 retained positive floor，并以零
provider I/O 的 `RETAINED_TERMINAL` 路径完成 target 和注册 work。

## 5. Telemetry 与 Readiness

Micrometer 仅使用 reconciler `Stage` 闭集标签；configured、active、closed 和 unexpected 为无 identity 的
counter/gauge。tenant、environment、attempt、worker、lease、provider、deployment、fingerprint、exception、
message 和 payload 均不会成为 label。

Actuator health 组合 database snapshot、scheduler snapshot 和 provider-call supervisor snapshot，状态闭集为：

| status | 判定 |
| --- | --- |
| `READY` | 生命周期开放，最新 poll 有界，discovery/backlog/capacity 均在 SLO 内 |
| `CLOSED` | scheduler 或 provider-call supervisor 已关闭 |
| `SCHEDULER_FAILED` | 最新 poll throw 或返回 null |
| `PROVIDER_CAPACITY_EXHAUSTED` | 所有 fixed slot 都被 interruption-ignoring call 占据 |
| `QUARANTINE_SLO_VIOLATED` | quarantined target 超过显式阈值 |
| `UNDISCOVERED_SOURCE_SLO_VIOLATED` | retained start 尚未物化 target 的数量超限 |
| `ACTIONABLE_AGE_SLO_VIOLATED` | oldest due/expired target 的数据库时钟年龄超限 |
| `UNAVAILABLE` | 任一 aggregate snapshot 无法安全读取或校验 |

actionable age 使用同一 journal snapshot 中的 database time。health details 只有固定枚举、聚合计数、容量和
数据库 observation time；异常文本和 target identity 不外泄。

## 6. 已执行验证

新增 24 项测试：7 项 scheduler、9 项 health、8 项 Spring composition。覆盖 bounded lane、错峰调度、
throw/null 恢复、graceful drain、telemetry outage 隔离、全部 down state、database-clock age、snapshot
non-disclosure、profile/property 物理缺席、完整装配、缺 resolver、缺 terminal chain、非 database adapter、
未知配置、不安全 deadline/lease budget 和关闭后线程清零。

新增运行时聚焦门禁：

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest='TestSuiteStabilityPhysicalAttemptObservationReconciliationRuntimeConfigurationTest,\
TestSuiteStabilityPhysicalAttemptObservationReconciliationSchedulerTest,\
TestSuiteStabilityPhysicalAttemptObservationReconciliationHealthTest' test

Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
```

observation、terminal projection 与 database queue 相邻聚合门禁：

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest='*PhysicalAttemptObservation*,*PhysicalAttemptTerminalProjection*,\
DatabaseTestSuiteStabilityJobRepositoryTest' test

Tests run: 240, Failures: 0, Errors: 0, Skipped: 0
```

四个新增 public 类型使用 Maven compile classpath 与空 sourcepath 通过：

```text
javadoc --release 25 -Werror -Xdoclint:all
0 warnings, 0 errors
```

`application-test.yml` 与 `application-staging.yml` 均通过 Ruby Psych 完整解析，`git diff --check` 无错误。
提交冻结后的全量 immutable snapshot 门禁将在下一份 evidence 更新中记录，不能用当前 working tree 的局部
测试冒充提交级证据。

## 7. 质量评估与下一病根

本增量局部质量评估为 **92/100**。自主发现、生命周期 ownership、资源上界、fail-fast 配置、关闭语义、
聚合可观测性和 terminal-work 原子注册已闭合。扣分来自 resolver 仍由 embedder 静态注入，database adapter
同域只由默认 composition 保证而非显式 transaction-domain proof，health 尚无 fleet cohort 视角，且没有
真实 provider 与 production database 认证。

相对两份工业级可测试性计划的完整目标，当前估计仍有 **约 23% 的实质差距**，明显未进入允许完成的正负
8% 区间。剩余病根主要是：

- capability truth、signed dynamic resolver inventory、policy fingerprint 和 exact fleet cohort readiness；
- 旧版本 source 的 N/N-1 backfill、immutable attempt history、跨 family provider fact ledger；
- start/cancellation/observation/projection retention、tombstone、legal hold、WORM 与 external anchor；
- 真实 process/container provider、secret/HSM/KMS custody、provider isolation 与 hard cancellation；
- production database dialect、crash-point、HA/partition/chaos、容量、升级回滚和 DR 认证；
- 完整计划中的 streaming/suspendable evidence、ANEKE N/N-1 conformance 与生产信任治理。

下一增量应先完成 capability truth 和动态签名 resolver inventory/cohort，让系统只在整条 physical-attempt
链路及其当前信任代际真实 ready 时对外声明能力；随后推进 N/N-1 backfill 与 retention/evidence lifecycle。
