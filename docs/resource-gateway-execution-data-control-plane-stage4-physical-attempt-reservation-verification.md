# Stage 4 Physical Attempt Reservation Verification

## 1. 本增量解决的问题

此前 durable queue lease 与 attempt-cancellation command 之间缺少一个权威的物理执行身份：worker
知道自己领取了哪个 job，却没有一份耐久事实回答“这个 lease epoch 被允许派发到哪个 provider
deployment、哪个 runtime binding、哪种隔离边界”。直接把 `jobId` 或线程身份当作 attempt 会导致：

1. 同一 lease 被两个副本派发到不同运行时代际；
2. 取消已提交，但旧 worker 仍在 provider 调用窗口继续派发；
3. cancellation receipt 的 `attemptId` 无法反向证明来源；
4. 首个 queue lease epoch 为 `0`，与既有 cancellation protocol 的 positive epoch 约束不兼容。

本增量只建立派发前的 identity reservation，不伪造 provider start 或 terminal 事实。

## 2. 新增协议

`TestSuiteStabilityPhysicalAttemptIdentity` 是 payload-free、内容寻址的不可变绑定，覆盖：

- tenant、environment、job 与 request fingerprint；
- worker owner 与 positive lease epoch；
- executable runtime binding fingerprint；
- provider、deployment 与 `PROCESS / CONTAINER / VM` isolation mode。

`attemptId = stability-attempt-{sha256(canonical material)}`。任何字段变化都会生成不同身份；registry
会重新计算 canonical fingerprint，不信任 Java 对象自带的 id/fingerprint。

`TestSuiteStabilityPhysicalAttemptRegistry` 提供两个写侧动作：

- `reserve(identity)`：冻结一个 live queue fence 到一个 physical identity，exact replay 幂等；
- `authorizeDispatch(attemptId)`：在外部 provider side effect 前重新验证 queue fence。

reservation 不是运行证明。当前 entry 只含 identity、database `reservedAt` 与 whole-row fingerprint，
没有 PID、fixture、业务 payload、credential 或 provider diagnostic。

## 3. 数据库线性化

`DatabaseTestSuiteStabilityPhysicalAttemptRegistry` 的事务顺序为：

1. 重算 identity fingerprint；
2. 在 tenant/environment/job 精确 scope 内 `FOR UPDATE` 锁定 queue row；
3. 调用 queue repository 验证完整 job row fingerprint；
4. 使用 database time 验证 `RUNNING`、deadline、request fingerprint、owner、lease epoch 与 expiry；
5. 锁定 `(tenant, environment, job, leaseEpoch)` fence；
6. exact identity replay，或插入唯一 reservation。

数据库唯一约束保证一个 job lease epoch 只能绑定一个 attempt。`find` 对跨 tenant/environment 查询
统一返回 empty，并在投影前复验 identity JSON、冗余索引列和 whole-row fingerprint。

queue 未领取记录现在从 epoch `0` 开始，首个 claim 得到 `1`；历史 `-1/0` 未领取行在 claim 时同样
归一到 `1`。所有对外 `TestSuiteStabilityJobLease` 必须为 positive epoch，与 physical identity、
cancellation command 和 cancellation receipt 使用同一不变量。

## 4. 已验证行为

聚焦门禁：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DatabaseTestSuiteStabilityPhysicalAttemptRegistryTest,\
DatabaseTestSuiteStabilityJobRepositoryTest,TestSuiteStabilityJobWorkerTest,\
TestSuiteStabilityJobExecutionCoordinatorTest test
```

结果：69 tests，0 failures，0 errors，0 skips。其中 11 项 registry 测试覆盖：

- fresh reservation、exact replay 与 dispatch authorization；
- identity deterministic derivation 与 canonical mutation rejection；
- 两副本 exact reservation 竞争只插入一次；
- 同 lease epoch 改绑另一 runtime generation 只有一个 winner；
- cancellation/retry 后 reservation 与 dispatch authorization 失败关闭；
- queued job 伪造 lease 不得创建 physical attempt；
- scope non-disclosure 与 retained-column tamper detection。

三个新增公共类型已通过 `javadoc --release 25 -Werror -Xdoclint:all`，0 warnings、0 errors。

最终代码提交 `286fe62f` 从 `git archive` 导出到不可变源码快照执行完整
`mvn -f resource-gateway-examples/pom.xml clean verify`：3932 tests、0 failures、0 errors、2 个条件浏览器
跳过，总耗时 7 分 11 秒，Spring Boot 可执行 JAR 重打包成功。独立使用结构化 XML parser 汇总 447 份
Surefire XML 得到相同数字；JAR 为 39,364,254 bytes。Maven/Surefire 正常退出，随后未发现该快照、
ChromeDriver 或 Chrome 测试进程残留。

## 5. 明确未完成

当前不得据此发布 physical runtime 或 hard-cancellation capability。仍缺少：

1. provider-signed start attestation 与 anti-rollback start sequence；
2. 真实 process/container/VM dispatch adapter 和独立资源域；
3. worker 从同步 JVM 调用迁移到 isolated provider；
4. natural terminal receipt 与 cancellation journal 的 queue 原子投影；
5. provider 确认终止前禁止释放 global/tenant/local slot；
6. `PREPARED/UNCONFIRMED/timeout` 的 bounded orphan reconciliation；
7. retention/tombstone、external anchor、Schema/test-kit/capability/health 与生产认证。

特别地，`authorizeDispatch` 缩小 reservation 与 provider call 的竞态窗口，但不能替代 provider 对
exact attempt/fence 的验签，也不能证明远端副作用未在超时后发生。下一增量必须用签名 start receipt
关闭这段不确定性，而不是用线程 interrupt、future cancellation 或 queue 状态推断物理运行时事实。
