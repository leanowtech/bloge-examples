# Resource Gateway Stage 4 Physical Attempt Terminal Projection Coordinator Verification

## 1. 增量目标

第十增量已经提供 exact-source terminal projection transaction，但调用侧仍需自行拼接 reservation、start、
terminal observation、positive-state、cancellation 与 parent evidence。不同 worker 若各自解释“证明未到”、
“证明冲突”和“存储不可用”，容易把暂时缺证误判为永久失败，或把完整性冲突误判为可重试故障。

本增量新增 proof-aware coordinator core：调用方只提供 exact tenant/environment/attempt 与 queue policy；
coordinator 读取完整 durable source chain、解析必要的第二证明、构造内容寻址 command，再调用既有原子
projection transaction。它不做 provider I/O，不读取业务 payload，也不自行改变 queue 或 slot。

## 2. Proof resolver contract

`TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver` 只处理两种第二证明：

| terminal disposition | proof kind | 允许的解析结果 |
| --- | --- | --- |
| `CANCELLED` | exact `CONFIRMED` cancellation journal entry | `READY` / `CANCELLATION_NOT_CONFIRMED` / permanent conflict |
| `SUCCEEDED` | expected parent run + signed evidence fingerprint | `READY` / `PARENT_NOT_CONFIRMED` / permanent conflict |
| `FAILED` / `TIMED_OUT` / `PROVIDER_ABORTED` | 无 | coordinator 不调用 resolver |

`READY` 必须携带且只携带一种 shape-valid proof；`PENDING` 只允许 not-yet-confirmed reason；`CONFLICT`
只允许 ambiguous/contradictory reason。resolver 的运行时异常由 coordinator 投影为
`PROOF_RESOLUTION_UNAVAILABLE`，不会伪装成 proof pending。

proof 只是候选，不是最终信任。projection transaction 仍会在同一数据库事务中重新读取、验证并绑定
cancellation source；success 仍由 parent authority 重算验证 signed evidence。

## 3. Exact source assembly

`TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator` 按固定顺序读取：

1. exact scoped physical-attempt reservation；
2. per-attempt latest positive state floor；
3. floor 指向的 exact observation command entry；
4. floor 指向的 original start command entry；
5. 仅对 cancellation/success 解析附加 proof；
6. 使用既有 `TerminalProjectionCommand.create` 复验整条引用链；
7. 调用 database-authoritative terminal projection journal。

source 缺失、非 terminal floor、跨 scope/attempt/command 的 incoherent chain 都不会触发 projection。
projection journal 的 closed conflict reason 被原样保留，不能降级为 infrastructure retry。

## 4. Closed outcomes

coordinator 返回无 payload 的固定分类：

| stage | 含义 | 自动重试建议 |
| --- | --- | --- |
| `PROJECTED` | 首次原子提交成功 | 否 |
| `REPLAYED` | exact command 已提交并完整重放 | 否 |
| `PROOF_PENDING` | cancellation/parent proof 尚未权威化 | 按 durable worker policy 重试 |
| `PERMANENT_CONFLICT` | source/proof/projection authority 永久拒绝 | 隔离并治理 |
| `UNAVAILABLE` | source/proof/projection infrastructure 或 adapter contract 暂不可用 | 退避重试 |

result 同时保留 fixed-cardinality `FailureReason`。proof conflict 额外保留 resolver reason，transactional
conflict 额外保留 journal `ConflictReason`；成功才携带 exact projection。构造器拒绝混合或矛盾状态，避免
telemetry、worker policy 与治理 UI 对同一次结果作不同解释。

## 5. 已执行验证

协调器与 proof value contract 聚焦门禁：

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinatorTest test

Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
```

覆盖 failed/timed-out/provider-aborted 无附加证明、cancellation/success proof pending、两类 proof command
binding、wrong-kind/ambiguous proof、source missing、non-terminal floor、三类 infrastructure outage、journal
exact conflict、projected/replayed 分离、adapter contract violation 以及 proof value truth table。

完整 physical-attempt/cancellation/queue 聚合门禁：

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest='*PhysicalAttempt*,*AttemptCancellation*,DatabaseTestSuiteStabilityJobRepositoryTest' test

Tests run: 249, Failures: 0, Errors: 0, Skipped: 0
```

两个新增公共类型使用 Maven 完整 compile classpath 通过
`javadoc --release 25 -Werror -Xdoclint:all`，0 warnings、0 errors。

实现提交 `c9608454` 已从 `git archive` 解包到 immutable source snapshot
`/tmp/bloge-examples-verify-c9608454.a5AgTm` 并执行完整门禁：

```text
mvn -f resource-gateway-examples/pom.xml clean verify

Tests run: 4083, Failures: 0, Errors: 0, Skipped: 2
BUILD SUCCESS
Total time: 09:23 min
```

456 份 Surefire XML 独立解析汇总为同一 `4083/0/0/2` 结果。重打包可执行 JAR 为 39,655,585 bytes，
包含 12 个 coordinator/proof-resolver 匹配 class entry。门禁退出后，snapshot Java/Maven 进程和
ChromeDriver/Chrome for Testing 进程残留均为零。

## 6. 尚未闭合

- coordinator 尚未由 durable projection work journal 驱动；进程在 terminal observation completion 与
  projection 之间崩溃时，仍缺少独立可 claim/takeover 的工作事实；
- observation reconciler 当前 terminal target lifecycle 与 queue projection lifecycle 仍是两个未接线状态机；
- proof resolver 只有协议边界，尚无 cancellation-by-attempt lookup 和 parent-success 产品实现；
- 没有 Spring worker/scheduler、database-clock retry policy、backlog telemetry、health/readiness 或 capability；
- source retention 必须晚于 projection work/command retention；当前尚无跨 family retention fence；
- projection conflict 中 `PARENT_CONFLICT` 仍未细分“parent 尚未完成”与“parent evidence 永久不一致”；
- 没有 crash-point、跨副本 takeover、生产数据库、真实 process/container provider 与 HA/partition/chaos 认证。

因此本增量关闭的是“统一且可测试的 exact command assembly 与失败分类”，不是可自动运行的产品 worker。
下一增量应先建立独立 durable projection work journal，把 provider-terminal-fact-known 与
queue-terminal-projection-committed 明确拆为两个可重放状态，再接入 reconciler 与 Spring lifecycle。
