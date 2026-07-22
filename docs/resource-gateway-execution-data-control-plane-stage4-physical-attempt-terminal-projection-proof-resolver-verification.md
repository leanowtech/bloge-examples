# Resource Gateway Stage 4 Physical Attempt Terminal Projection Proof Resolver Verification

## 1. 增量目标

terminal projection coordinator 已冻结第二证明接口，但此前没有产品实现。`CANCELLED` 无法从 physical
attempt 身份反查 provider-confirmed cancellation receipt，`SUCCEEDED` 也无法从 durable queue job 找到并
独立验证 signed parent evidence。直接启用 scheduler 只会稳定制造 `PROOF_PENDING`。

本增量实现组合权威 resolver，建立两条无 payload 的证明链：

```text
CANCELLED
physical attempt identity
  -> exact tenant/environment/attempt/lease lookup
  -> cancellation command + provider/deployment + verified receipt
  -> cancellation proof candidate

SUCCEEDED
physical attempt identity
  -> exact durable queue job
  -> deterministic parent run identity
  -> retained signed parent record
  -> independent parent authority verification
  -> parent-success proof candidate
```

本增量关闭 proof selection/verification 内核，不包含 scheduler、Spring test/staging composition、健康探针
或 capability 开放。

## 2. Exact cancellation lookup

`TestSuiteStabilityAttemptCancellationJournal.findByAttempt` 新增闭合结果：

| lookup status | reason | 含义 | resolver 动作 |
| --- | --- | --- | --- |
| `FOUND` | `NONE` | 唯一且完整验证的 retained entry | 继续校验完整 attempt identity |
| `ABSENT` | `NOT_RETAINED` | 精确 scope/fence 尚无记录 | `PENDING / CANCELLATION_NOT_CONFIRMED` |
| `CONFLICT` | `AMBIGUOUS` | 多条记录争用同一 attempt fence | `CONFLICT / AMBIGUOUS_PROOF` |
| `CONFLICT` | `INTEGRITY_CONFLICT` | retained row 或 provider continuity 损坏 | `CONFLICT / PROOF_CONFLICT` |

数据库实现使用 `(tenant_id, environment_id, attempt_id, lease_epoch)` 唯一约束支持的精确查询，不扫描、
不按时间猜测、也不接受调用方补充 command id。tenant/environment 越权和 fence 不匹配统一返回 absent，
避免存在性泄漏。

`validateEntry` 现在显式让 `DataAccessException` 逃逸。否则 provider-sequence 表不可访问会被错误包装为
integrity conflict，永久隔离一个本可恢复的 work。保留内容篡改仍返回 typed conflict，基础设施故障仍由
coordinator 映射为 `PROOF_RESOLUTION_UNAVAILABLE`。

## 3. Cancellation proof truth table

resolver 对 found entry 重新绑定以下坐标：

- tenant、environment、job、attempt、owner、lease epoch；
- runtime binding fingerprint；
- provider id、deployment id、descriptor/attestation key id、isolation mode；
- receipt 的 command id/fingerprint、provider、attempt、epoch 和 confirmed termination。

结果语义为：

| journal state | identity/proof | resolution |
| --- | --- | --- |
| `PREPARED` | 完整匹配 | `PENDING`，provider receipt 仍可能到达 |
| `UNCONFIRMED` | signed `NOT_FOUND/REJECTED` | `CONFLICT`，该 terminal journal 不可改写为 confirmed |
| `CONFIRMED` | 完整匹配、termination confirmed | `READY / CANCELLATION` |
| 任意 | scope/fence/provider/isolation 不匹配 | `CONFLICT / PROOF_CONFLICT` |

`UNCONFIRMED` 不是“暂时没确认”。它是不可变的 provider terminal answer；将它继续重试会形成永不收敛的
假活性。

## 4. Parent-success proof truth table

resolver 首先按 physical identity 的 tenant/environment/job id 读取 integrity-verified queue job，并要求
job id、scope 与 request fingerprint 精确一致。parent run id 只由
`TestSuiteStabilityExecutionIdentity` 从 retained job 推导，不接收外部引用。

| retained facts | resolution |
| --- | --- |
| queue job 不存在或身份矛盾 | `CONFLICT`，attempt 不应脱离其先存 durable parent |
| active job，无 parent run、无 stop tombstone | `PENDING / PARENT_NOT_CONFIRMED` |
| 无 parent run，但 parent stop 已提交 | `CONFLICT`，success 已不可能成为 winner |
| terminal non-success job 又出现 parent success | `CONFLICT` |
| exact parent record 存在 | 调用 `TestSuiteStabilityJobParentAuthority.requireCompleted` 重算 evidence fingerprint 并验证 detached signature |
| authority 返回错 run/fingerprint、null 或签名 conflict | `CONFLICT / PROOF_CONFLICT` |
| repository/trust authority outage | 异常上抛，映射为 `PROOF_RESOLUTION_UNAVAILABLE` |
| exact signed parent 完整验证 | `READY / PARENT_SUCCESS` |

proof resolver 是选择与预验证 authority，不替代最终事务。projection journal 仍会在 queue mutation 同一
事务中重新验证 source fingerprints、cancellation row 和 signed parent winner，因此两次读取之间发生
retention、篡改或 winner 变化时不会错误释放 physical slot。

## 5. 已执行验证

聚焦 resolver、真实 H2 cancellation journal 与现有 cancellation coordinator：

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=AuthoritativeTestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolverTest,\
DatabaseTestSuiteStabilityAttemptCancellationJournalTest,\
TestSuiteStabilityAttemptCancellationCoordinatorTest test

Tests run: 52, Failures: 0, Errors: 0, Skipped: 0
```

其中新增 23 项测试：18 项 resolver truth table，5 项真实 H2 exact-attempt lookup/产品组合链。覆盖 absent、
prepared、unconfirmed、confirmed、scope/fence 隔离、ambiguity、retained tamper、存储故障不误分类、lease
owner 不匹配、queue job 丢失、parent pending、stop winner、non-success terminal、exact verified success、
签名冲突、错形 authority result 和 repository outage。

完整 physical-attempt/cancellation/queue 聚合门禁：

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest='*PhysicalAttempt*,*AttemptCancellation*,DatabaseTestSuiteStabilityJobRepositoryTest' test

Tests run: 308, Failures: 0, Errors: 0, Skipped: 0
```

三个涉及的 public contract 使用 Maven 完整 compile classpath、空 sourcepath 通过：

```text
javadoc --release 25 -Werror -Xdoclint:all
0 warnings, 0 errors
```

实现提交 `30f7a93a` 已通过 `git archive` 解包到 immutable source snapshot
`/tmp/bloge-examples-verify-30f7a93a` 并执行完整门禁：

```text
mvn -f resource-gateway-examples/pom.xml clean verify

Tests run: 4142, Failures: 0, Errors: 0, Skipped: 2
BUILD SUCCESS
Total time: 07:45 min
```

460 份 Surefire XML 使用独立 XML parser 重算为同一 `4142/0/0/2`。重打包 Spring Boot JAR 为
39,743,350 bytes，包含 5 个新增 resolver/attempt-lookup class entry。门禁退出后，snapshot Java/Maven
进程和 ChromeDriver/Chrome for Testing 残留进程均为零。

## 6. 本轮质量与总体差距

本增量局部质量评估为 **93/100**。优点是 proof 来源不可由调用方伪造，取消查询有唯一 fence，
pending/conflict/unavailable 三类故障不再混淆，父成功必须经过独立签名验证，且真实 H2 覆盖了篡改与
availability 分界。扣分来自 parent candidate discovery 与独立 authority verification 仍是两次读取，虽然
最终 projection transaction 会再次复验；另外 proof lookup 尚未具备 retention SLO 和历史 backfill。

相对两份工业级可测试性演进计划的完整初始目标，当前估计仍有 **约 29% 的实质差距**，明显未进入
允许完成的正负 8% 区间。主要剩余项为：

- autonomous scheduler、Spring test/staging composition、graceful shutdown 和启动 fail-fast；
- worker/proof telemetry、database-clock backlog SLO、health/readiness 与 capability truth；
- tenant/environment fairness、policy fingerprint/cohort readiness 和 immutable work-attempt history；
- N/N-1 orphan terminal backfill、cancellation/observation/projection retention、tombstone/WORM/外部锚；
- 真实 process/container provider、生产数据库、crash point、HA/partition/chaos 认证；
- 完整计划中尚未闭合的 streaming/suspendable evidence、真实 ANEKE conformance 与生产信任治理。

下一步应把 resolver、coordinator、work journal、supervisor 和 worker 组成唯一 Spring-owned runtime，先
建立 test/staging fail-fast 与 lifecycle，再开放 scheduler；否则仍存在“内核正确但产品根本不会运行”的
交付断层。
