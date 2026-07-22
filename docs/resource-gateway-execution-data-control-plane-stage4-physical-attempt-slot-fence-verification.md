# Resource Gateway Stage 4 Physical Attempt Slot Fence Verification

## 1. 增量目标

physical-attempt start journal 已经保证 provider I/O 前先写 durable `PREPARED`，observation reconciler
也能找回 orphan；但原 queue recovery 在 `RUNNING` lease 过期后仍会直接重排队。两者之间存在一个危险
窗口：第一个 provider side effect 可能已经发生，第二个 worker 却获得更高 lease epoch 并再次启动。

本增量只关闭 duplicate dispatch 根因：

- queue repository 增加显式 opt-in physical-attempt fencing；
- 任意 retained start command 都按“provider 可能已被调用”处理；
- expired `RUNNING`、`CANCEL_REQUESTED` 不再自动 retry/terminal；
- expired `COMMITTING` 不再成为 publication takeover candidate；
- 被围栏行清空瞬态 owner/lease、保留原 lease epoch 和公开 v1 status，并写入有界 failure code；
- unresolved physical slot 继续计入 environment/tenant running capacity；
- cancellation/deadline 先 durable stop parent，但在物理终态证明前不释放 slot；
- 同一 database transaction 更新 queue fence，跨副本不会产生第二个 claim winner。

## 2. 为什么不能相信 `REJECTED`

start journal 的三种状态都必须保守处理：

| start 状态 | 可证明事实 | queue lease 过期后 |
| --- | --- | --- |
| `PREPARED` | provider call 可能已发生，response 未知 | 持有 physical slot |
| `CONFIRMED` | exact physical runtime 已启动 | 持有 physical slot |
| `UNCONFIRMED` | provider 签名了 `REJECTED`，但协议不证明 non-start | 持有 physical slot |

只有后续 verified positive terminal observation 与 queue terminal projection 的原子 closure 才能释放。
本增量没有把 `NOT_OBSERVED`、`INDETERMINATE`、timeout 或 quarantine 当作 terminal/non-start proof。

## 3. 协议兼容与公平性

没有向 `TestSuiteStabilityJobRecord.Status` 或 testing-control-plane v1 Schema 增加新枚举。严格 consumer
仍只看到原有 `RUNNING/CANCEL_REQUESTED/COMMITTING`；`expiredLiveLeases` 会暴露积压，failure code 为
`RG.TEST.STABILITY_JOB_PHYSICAL_ATTEMPT_RECONCILING`、cancel 或 deadline 原因。

不能只在 stale page 中跳过 orphan。那会让最老的 1,000 条 unresolved row 永久压住后续普通恢复。
实现会在第一次 database-clock stale scan 中清空 owner/lease 并推进 `updatedAt`；之后该行不再进入普通
lease-expiry page，但 capacity/claim SQL 仍通过 exact tenant/environment/job/lease epoch 与 physical
reservation + start journal 的关联识别它。`COMMITTING` candidate SQL 同样排除这种关联。

旧构造器保持 queue-only 行为。启用 fencing 的新构造器要求 physical registry、start journal 和 queue
共享 datasource，并在首次 queue mutation 前完成 schema 初始化；缺表/数据库故障不会降级为“没有
physical side effect”。产品 composition/capability 仍关闭，不能把 test-core opt-in 解释为已上线。

## 4. 已执行验证

真实 H2、physical reservation、Ed25519 start verifier/journal、queue repository 和两个独立 repository
实例的组合测试：

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DatabaseTestSuiteStabilityPhysicalAttemptObservationJournalTest test

Tests run: 41, Failures: 0, Errors: 0, Skipped: 0
```

新增场景同时建立三个 lease epoch 1 attempt，分别保持 `PREPARED`、`CONFIRMED` 和签名
`REJECTED -> UNCONFIRMED`；其中一个进入 `COMMITTING`。5 秒 database lease 过期后，两个 repository
副本并发 claim 均返回 `NO_WORK`，三个 job 的最大 lease epoch 仍为 1，第四个 job 保持 `QUEUED`，三条
expired live lease 持续占满 running capacity。其中一个 job 在首次围栏后才自然越过 deadline；下一次
database-clock reconcile 只记录 `RG.TEST.STABILITY_JOB_DEADLINE_EXCEEDED` 并 stop parent，不释放 slot，
也不会重新 dispatch。对另一个 orphan 发出 cancellation 后只进入 `CANCEL_REQUESTED`，另一副本仍不能
claim。

默认 queue-only 构造路径完整回归：

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DatabaseTestSuiteStabilityJobRepositoryTest test

Tests run: 41, Failures: 0, Errors: 0, Skipped: 0
```

完整 physical-attempt/cancellation 链与默认 queue 回归的聚合门禁：

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest='*PhysicalAttempt*,*AttemptCancellation*,DatabaseTestSuiteStabilityJobRepositoryTest' test

Tests run: 222, Failures: 0, Errors: 0, Skipped: 0
```

改动的公共 repository 类型使用 Maven 完整 compile classpath 通过：

```text
javadoc --release 25 -Werror -Xdoclint:all
0 warnings, 0 errors
```

实现提交 `e70a7c05` 的 immutable snapshot
`/private/tmp/bloge-examples-verify-e70a7c05.ogLD7Q` 已执行完整门禁：

```text
mvn -f resource-gateway-examples/pom.xml clean verify

Tests run: 4056, Failures: 0, Errors: 0, Skipped: 2
BUILD SUCCESS
Total time: 07:57 min
```

独立读取 455 份 Surefire XML 得到相同的 `4056/0/0/2` 汇总。重打包后的 Spring Boot 可执行 JAR
为 39,596,599 bytes，并包含 7 个 `DatabaseTestSuiteStabilityJobRepository` 主类/嵌套类 entry。
Maven 退出后再次检查快照路径关联的 Java/Maven 进程，以及测试启动的 ChromeDriver/Chrome for
Testing 进程，两类残留数均为 0。

## 5. 尚未闭合

- 没有 terminal projection table/API，verified terminal observation 尚不能原子释放 slot；
- 没有把 cancellation `CONFIRMED` receipt 与 terminal disposition `CANCELLED` 强制链接；
- 没有按 terminal disposition 原子决定 retry、parent success/failure/cancel/deadline 与 slot closure；
- fencing 尚未进入 Spring composition、scheduler、health/readiness/capability 和外部告警；
- 没有独立 physical-slot backlog snapshot、retention/tombstone、人工 repair 或 external anchor；
- start/cancel/observation 仍没有跨 family 的统一 provider sequence ledger；
- 没有真实 process/container/VM provider 与生产数据库/HA/chaos 认证。

因此本增量选择安全的 availability loss：一旦 provider I/O 可能发生，slot 在可信 closure 前持续占用。
下一增量必须实现 terminal projection transaction，而不是增加 timeout 后强制释放开关。
