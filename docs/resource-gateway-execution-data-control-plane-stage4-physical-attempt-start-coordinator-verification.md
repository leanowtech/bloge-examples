# Stage 4 Physical Attempt Start Coordinator Verification

## 1. 目标

start command、provider proof、call supervisor 与 durable journal 分别正确，并不自动保证调用顺序正确。
`TestSuiteStabilityPhysicalAttemptStartCoordinator` 将单次 start 固化为：

```text
scoped find
  -> bounded descriptor
  -> durable prepare
  -> database-time live-fence authorization
  -> bounded idempotent start
  -> verified durable accept
```

coordinator 是 ordering primitive，不是 worker、provider 或 queue state machine。

## 2. Replay and recovery

- retained `CONFIRMED`：直接返回 `REPLAYED`，descriptor/start provider I/O 均为零；
- retained `UNCONFIRMED`：直接返回 `REPLAYED`，不尝试把签名 rejection 升级为 start proof；
- retained `PREPARED`：重新获取 bounded descriptor，必须与 frozen descriptor 精确一致，再由 journal
  复验 reservation、queue lease 与 database time；
- fresh command：descriptor 必须先于 durable prepare，provider start 必须后于 invocation authorization；
- timeout/interrupt/adapter failure/invalid attestation：不宣称 non-start，journal 保持 `PREPARED`。

prepared descriptor drift 由 journal 的 exact replay contract 失败关闭。coordinator 不猜测旧 deployment，也不
自动切换 provider。

## 3. Ownership boundary

coordinator 不关闭 process-scoped supervisor，也不执行以下投影：

- queue `RUNNING` 或 cancellation state；
- global/tenant/local slot；
- process natural terminal；
- timeout/`UNCONFIRMED` orphan lane；
- health、capability 或产品 API。

这些状态不能通过 Java 调用成功与否推导，必须在真实 provider 与 queue/cancellation journal 之间建立新的
database-authoritative linearization。

## 4. Verification

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=TestSuiteStabilityPhysicalAttemptStartCoordinatorTest,\
DatabaseTestSuiteStabilityPhysicalAttemptStartJournalTest test
```

结果为 35 tests，0 failures、0 errors、0 skips：

- 10 项 coordinator 单元测试验证 fresh ordering、confirmed/unconfirmed 零 I/O replay、prepared recovery、
  descriptor drift、timeout、adapter diagnostic 脱敏、accept failure、signed rejection 与 authorization
  failure；
- journal 的 25 项中新增 3 项真实 H2 + Ed25519 + supervisor 组合测试，验证 confirmed start 只调用 provider
  一次、signed rejection 落 `UNCONFIRMED`、timeout 保持 `PREPARED` 且 provider sequence 为零；
- 其余 22 项继续覆盖 journal identity、causality、late receipt、integrity、scope、queue race、rollback 和
  跨实例并发。

完整 physical-attempt/queue 聚焦门禁执行 141 tests，0 failures、0 errors、0 skips。coordinator 公共类型
通过 `javadoc --release 25 -Werror -Xdoclint:all`，0 warnings、0 errors。

### 4.1 Immutable full gate

实现提交 `affcdae1` 的 immutable `git archive` 快照执行完整发布门禁：

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
```

- Maven：3985 tests，0 failures，0 errors，2 skips，`BUILD SUCCESS`；
- structured cross-check：451 份 Surefire XML 合计 3985 tests，0 failures，0 errors，2 skips；
- artifact：Spring Boot executable JAR 成功生成，39,444,287 bytes；
- lifecycle：门禁退出后没有快照 Maven、ChromeDriver 或 Chrome for Testing 残留进程；
- wall time：7 分 22 秒。

该结果证明 coordinator 与完整数据库/API/真实浏览器回归、线程生命周期和可执行打包兼容，不证明尚未
实现的真实 isolated provider、worker dispatch 或 orphan reconciliation 已具备生产能力。

## 5. 尚未开放

本增量没有 Spring bean、HTTP route、Schema、test-kit 或 capability advertisement。尚未实现：

1. 可证明 isolation、envelope custody、network/secret policy 的 process/container/VM provider；
2. provider-signed natural terminal protocol 与 exact process identity closure；
3. start/cancellation/natural-terminal/queue 的双线性化投影；
4. slot 延迟释放、timeout/late/`UNCONFIRMED` bounded reconciliation；
5. 动态 signed provider inventory、retention/tombstone/external anchor 与生产 HA/DR/chaos。

因此现有 `TestSuiteStabilityJobWorker` 继续使用原同步 in-JVM execution path。下一增量应先建立一个明确仅供
test/staging 的 isolated provider composition 和 fake-but-faithful lifecycle harness，再讨论 worker 切换。
