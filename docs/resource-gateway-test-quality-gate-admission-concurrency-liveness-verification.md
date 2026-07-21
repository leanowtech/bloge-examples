# Resource Gateway Admission Concurrency Test Liveness 验证

## 1. 观测到的失败模式

完整 Resource Gateway 门禁曾在
`DatabaseTestRuntimeAdmissionControlTest.competingReplicasCannotBothConsumeTheLastTenantPermit`
永久等待。线程快照显示主测试线程停在无期限 `ready.await()`；参与线程因同一 worktree 中另一个
`mvn clean` 删除已编译 class 而在到达 barrier 前异常退出。

并发 `clean` 不是产品运行时故障，但它揭示了测试门禁本身的真实缺陷：参与者失败时，barrier、future
和 `ExecutorService.close()` 都没有 caller-owned deadline，原本应当可诊断的失败被放大成无限挂起。
该次被并发清理污染的 Maven 运行不计作有效全量验证证据。

## 2. 收紧后的不变量

`DatabaseTestRuntimeAdmissionControlTest` 的三个数据库竞态场景现在统一遵守：

1. start/ready barrier 最多等待 5 秒，参与者缺失时抛出 `TimeoutException` 或断言失败；
2. 每个 `Future` 最多等待 5 秒，不再使用无期限 `get()`；
3. 竞态 worker 使用命名 daemon platform thread，异常路径不会以非 daemon 线程阻止 fork 退出；
4. `finally` 始终释放 start barrier、请求中断，并有界等待 executor 终止；
5. 故障注入用例让 barrier 参与者永不到达、future 永不完成，直接验证两条 deadline。

这些约束不改变 admission CAS、数据库事务或竞争结果，只改变测试基础设施在异常环境中的收敛方式。

## 3. 验证

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DatabaseTestRuntimeAdmissionControlTest test
```

结果为 8 tests，0 failures、0 errors、0 skips；其中 7 项覆盖 admission 行为，1 项专门覆盖 barrier 与
future timeout 故障路径。`git diff --check` 同时通过。

## 4. 不外推的结论

本增量只关闭已实际观测的 admission-control test hang：

- 没有为同一 worktree 的并发 Maven lifecycle 提供跨进程 build lock；正式全量门禁仍需独占 workspace；
- 没有把全仓历史测试中的无界 `await/get` 全部迁移，扫描仍能发现其他并发测试债务；
- daemon test worker 与本地 interrupt 不等于产品运行时的 process/container hard cancellation；
- 本次尚未形成新的独占 `clean verify` 基线。

后续质量门禁应抽取统一的 bounded concurrency test harness，并分批迁移剩余并发测试；每次全量基线只
接受源码冻结、单 Maven writer、可追溯 HEAD 上的独占运行。
