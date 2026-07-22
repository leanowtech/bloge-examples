# Resource Gateway Stage 4 Physical Attempt Terminal Projection Worker Verification

## 1. 增量目标

前一增量已经把 terminal projection work 建模为数据库权威的 `READY / LEASED / COMPLETED /
QUARANTINED` 状态机，但调用方仍需手工拼接 claim、coordinator 和 completion。本增量新增 bounded
one-shot worker，令一次调用最多处理一条到期 work，并建立以下完整控制顺序：

```text
database claim -> local lease budget -> supervised coordinator -> fenced completion
```

本增量关闭的是可复用执行内核，不包含自主 scheduler、Spring 产品装配、proof resolver 产品 adapter 或
capability 开放。

## 2. 双时钟租约预算

worker 不使用本机 wall clock 与数据库 `leaseUntil` 直接比较。这样做会把数据库与副本的时钟漂移引入
safety 判断。实现改为在调用 `claimNext` 前记录进程单调时钟，并按下式计算调用预算：

```text
availableCallBudget = durableLeaseDuration
                    - monotonicElapsedSinceClaimCallStarted
                    - completionReserve
effectiveCallTimeout = min(availableCallBudget, configuredMaximumCallTimeout)
```

claim 的全部本地往返时间都被保守扣除；不足 100 ms 时不启动 coordinator，而是尝试在当前 lease 下提交
`UNAVAILABLE / PROJECTION_UNAVAILABLE`。构造期强制：

```text
maximumCallTimeout + completionReserve < durableLeaseDuration
```

等号也失败，所有 duration 必须 millisecond-exact。即使 JVM 调度暂停或数据库 completion 超过 reserve，
journal 仍会以数据库时间返回 `LEASE_LOST`，旧 worker 不能提交到新 epoch。

## 3. 固定容量调用边界

`TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor` 使用 daemon platform thread、固定 worker
数和 `SynchronousQueue`：

- 调用只能立即开始或以 `SATURATED` 失败，不产生无界排队；
- 每次调用接受 worker 算出的动态 timeout，且不得超过配置上限；
- timeout 和 caller interrupt 都请求取消，但不宣称 projection 未提交；
- 忽略中断的 coordinator 继续占用原固定 slot，并进入 `lingeringCalls`；
- adapter exception、null 和 cancellation 折叠为 payload-free `UNAVAILABLE`，不透传 endpoint、tenant、
  attempt 或异常文本；
- close 立即关闭新准入并请求取消，不无界等待不合作调用。

迟到 coordinator 可能最终提交 queue projection。worker 在 timeout 后只持久化 retryable unavailable；下一次
执行重新生成同一 content-addressed projection command，并通过 projection journal 的 exact replay 收敛。

## 4. One-shot worker 状态语义

`TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.processNext()` 不循环、不 sleep，每次最多 claim 一条：

| 边界结果 | durable 动作 | caller outcome |
| --- | --- | --- |
| 无到期 work | 不调用 coordinator | `NO_WORK` |
| coordinator `PROJECTED / REPLAYED` | fenced complete | `COMPLETED` |
| coordinator `PROOF_PENDING / UNAVAILABLE` | 数据库时间退避 | `RESCHEDULED` |
| coordinator permanent conflict | fenced quarantine | `QUARANTINED` |
| supervisor timeout/saturation/closed/unavailable | 持久化 retryable unavailable | 通常 `RESCHEDULED`，保留 local disposition |
| claim/completion authority outage | 不猜测数据库状态 | `WORK_UNAVAILABLE` |
| completion lease loss | 不重试旧 fence | `LEASE_LOST` |
| changed result/integrity conflict | 不降级为 outage | `WORK_CONFLICT` |
| caller interrupt | 恢复 interrupt，不再做 completion I/O | `CALLER_INTERRUPTED` |

`Execution` 同时保留 durable outcome、固定基数 local disposition 和可选 work conflict reason，不携带
tenant、environment、attempt、owner、token 或业务 payload。`Result.temporarilyUnavailable` 只能构造
现有 retryable failure reason，无法伪造成功或永久业务冲突。

## 5. 事务与恢复边界

coordinator 始终在 claim transaction 与 completion transaction 之间运行。work row lock 不覆盖 proof
resolution、source journal 组合读取或 queue projection。worker 不缓存 lease 外的可变数据库状态；所有提交
重新进入 work journal，并使用 owner/token/epoch/time/fingerprint 完整 fence。

本设计没有用“调用超时”推导“事务未发生”。可能出现的迟到提交、completion 响应丢失和 replica takeover
分别由 projection exact replay、work result fingerprint replay 和 lease epoch 解决。

## 6. 已执行验证

supervisor、worker 和真实 H2 work-journal 组合门禁：

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisorTest,\
TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkerTest,\
DatabaseTestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournalTest test

Tests run: 33, Failures: 0, Errors: 0, Skipped: 0
```

其中新增 23 项测试覆盖动态 deadline、zero-queue saturation、interrupt-ignoring lingering call、异常/null
脱敏、caller interrupt、close、policy/snapshot truth table、无 work、claim outage、四类 durable completion、
proof detail、claim latency budget exhaustion、timeout reschedule、lease loss、changed-result conflict、
malformed coordinator result，以及两条真实 H2 worker retry/quarantine/timeout completion 组合链。

完整 physical-attempt/cancellation/queue 聚合门禁：

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest='*PhysicalAttempt*,*AttemptCancellation*,DatabaseTestSuiteStabilityJobRepositoryTest' test

Tests run: 285, Failures: 0, Errors: 0, Skipped: 0
```

三个涉及的 public contract 使用 Maven 完整 compile classpath、空 sourcepath 通过：

```text
javadoc --release 25 -Werror -Xdoclint:all
0 warnings, 0 errors
```

完整 `clean verify` 将在实现提交冻结后从 immutable `git archive` snapshot 执行，避免工作区或后续改动
污染证据。

## 7. 本轮质量与总体差距

本增量局部质量评估为 **91/100**：database authority、单调预算、固定容量、迟到调用、fenced completion、
中断语义和真实 H2 组合证据已经闭合；扣分来自 claim/complete JDBC deadline 仍依赖外层 datasource 配置，
以及 supervisor capacity 尚未在 claim 前做 reservation，持续 lingering 时会产生有限但不必要的
claim/reschedule churn。

相对两份工业级可测试性演进计划的完整初始目标，当前估计仍有 **约 31% 的实质差距**，明显未进入
允许完成的正负 8% 区间。主要差距不是本 worker 的局部代码，而是：

- cancellation-by-attempt 与 parent-success 的产品 proof resolver；
- scheduler、Spring test/staging composition、telemetry、SLO、health/readiness 和 capability truth；
- tenant/environment fairness、policy fingerprint/cohort readiness 与 immutable attempt history；
- N/N-1 orphan terminal backfill、retention/tombstone/WORM 和外部锚；
- 真实 process/container provider、生产数据库、断电 crash point、跨副本 HA/partition/chaos 认证；
- 整体计划中仍未闭合的 streaming/suspendable evidence、真实 ANEKE conformance 和生产信任治理。

下一增量优先实现产品 proof resolver。没有 proof adapter，`CANCELLED/SUCCEEDED` 即使 worker 可持续推进，
也只能稳定停留在 `PROOF_PENDING`；直接先上 scheduler 只会把缺失的 authority 放大成重试流量。
