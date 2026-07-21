# Resource Gateway 浏览器质量门禁会话监督验证

## 1. 问题与根因

Resource Gateway 的真实浏览器回归原先直接在 JUnit 线程中执行 `new ChromeDriver(...)`。一次完整
`clean verify` 真实暴露了以下失效链：ChromeDriver 子进程在 session handshake 期间退出，Selenium
JDK HTTP client 仍阻塞在 `CompletableFuture.get()`；15 秒 connection/read timeout 没有终止等待，类级
90 秒 JUnit timeout 也无法从同一线程打断构造器。结果不是一条用例失败，而是整条质量门禁永久失去
活性。

这不是普通 flaky case。根因是测试基础设施把第三方进程握手放在了没有独立所有权与强制返回边界的
JUnit 线程中，并且把 `service.stop()`、迟到 session cleanup 和正常 teardown 都默认成永不阻塞。
只提高 JUnit timeout 或重试用例会掩盖问题，不会根治。

## 2. 冻结的不变量

`BoundedBrowserSessionLauncher` 与 `BoundedBrowserSessionCloser` 为浏览器测试冻结以下不变量：

1. 启动 deadline 只能是 100 ms 到 60 s 的毫秒整数，调用方 deadline 是返回边界的权威。
2. session factory 只运行在单个 daemon platform thread 上，不在 JUnit 线程执行，也不形成等待队列。
3. session 所有权只有一次原子转移；deadline、caller interrupt 或 factory failure 后不允许迟到转移。
4. timeout、caller interrupt 与 factory failure 只暴露闭集 disposition，不复制浏览器命令、路径或
   provider exception。
5. abort 与 cleanup 各自运行在独立 daemon 边界；任一 hook 不合作都不能延长 JUnit caller deadline，
   也不能阻止另一项回收尝试。
6. 与 timeout 竞态产生的 session 只能被 cleanup 一次，不能被测试继续使用。
7. caller interrupt 必须恢复，不能被测试工具吞掉。
8. 正常 `driver.quit()` 必须经过独立 15 秒 graceful-close 边界；失败或超时后不得直接覆盖已经完成的
   业务断言，而应进入独立的 force-close 阶段。
9. force-close 使用另一个 daemon platform thread 和完整的 15 秒边界；它成功时 teardown 收敛为
   `FORCED`，只有它超时、失败或 caller 被中断时才产生闭集基础设施错误。
10. graceful/force provider exception 不得进入构建摘要；caller interrupt 必须恢复，且在 graceful
    阶段被中断后只在独立 daemon 上异步发起 force-close，立即恢复 interrupt 并返回闭集错误。
11. ChromeDriver capture 必须以 executable 与 frozen port argument 唯一定位，并钉住 PID、start instant、
    command 以及当时可见的后代；graceful success 需证明这些身份都已退出，force-close 需先复验身份再
    终止，不能仅凭可复用 PID 杀进程。

## 3. 实现边界

实现位于测试源码，不改变 Resource Gateway 产品协议：

- `BoundedBrowserSessionLauncher` 实现 daemon launch、原子 ownership、异步 abort/cleanup 和闭集失败；
- `BoundedBrowserSessionCloser` 实现相互独立的 graceful/force deadline、closed disposition、调用方
  interrupt 传播和 provider diagnostics 隔离；
- `ScopedProcessTree` 在 session 建立后冻结 ChromeDriver 与可见后代身份，正常退出验证整组句柄，
  force-close 终止已捕获及根仍存活时新发现的后代；
- `VisualAuthoringBrowserDomTest` 用它监督 ChromeDriver 构造、setup 失败回收和每个用例的 teardown；
- ChromeDriver service 在 session 成功后由测试实例持有；`driver.quit()` 卡住或失败时，force-close
  直接终止冻结的 OS 进程树，绕过 `DriverService.stop()` 可能与 `quit()` 争用同一内部锁的路径；
- 类级 JUnit timeout 保留为整个业务流程的第二层上限，不再承担 driver handshake 的唯一活性保证。

这里没有把测试侧 process-handle 监督伪装成生产进程级取消证明。daemon 边界保证 Maven/JUnit 可继续
收敛，`driver.quit()` 与 process-tree force-close 是独立、有界的回收尝试；`ScopedProcessTree` 能证明已捕获身份
退出，但不是原子的 OS process domain，两个快照之间新生并立即脱离 root 的后代仍需 container/cgroup、
Windows Job Object 或 CI runner 级监督才能完整封闭。

## 4. 测试证据

聚焦门禁在独立源码快照执行 20 tests，0 failures、0 errors、0 skips：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest='ScopedProcessTreeTest,BoundedBrowserSessionCloserTest,BoundedBrowserSessionLauncherTest,VisualAuthoringBrowserDomTest#composerConnectabilityHandlesLargeTargetWindowInRealBrowser' \
  test
```

其中 7 个 launch 内核测试覆盖：

- 正常 session 唯一所有权转移；
- factory 忽略 interrupt 时 caller 仍按 100 ms deadline 返回；
- timeout 后迟到 session 的唯一 cleanup；
- caller interrupt 传播与 abort；
- factory diagnostics 脱敏；
- abort hook 自身永久阻塞时 caller deadline 不漂移；
- 非法、非毫秒整数和无界 timeout 拒绝。

另有 7 个 close 内核测试覆盖 graceful success、graceful timeout 后 force success、两阶段 timeout、
graceful failure 补偿、force failure 闭集分类与脱敏、caller interruption/异步 force-close，以及 timeout
边界校验。真实 Chrome 用例继续完成大型 target window 的 connectability 操作，并在双阶段受监督
teardown 下退出。Chrome 150 对 Selenium CDP 149 的兼容性告警仍存在，但不改变本次断言。

5 个 process-scope 测试使用真实 OS 子进程覆盖根与后代强制退出、根先退出时不得掩盖存活后代、dead
process 拒绝、零匹配拒绝，以及 executable + exact argument 唯一 capture/退出验证。共享 worktree 曾在
Surefire 运行中被另一 Maven 编译重写 `target/test-classes` 并产生伪 `NoClassDefFoundError`，因此上述
证据来自不共享 `target` 的独立源码快照。

故障修复前，精确提交 `560706e4` 的隔离全量运行执行 3909 tests，业务断言为 0 failures，但
`composerConnectabilityHandlesLargeTargetWindowInRealBrowser` 的 `@AfterEach` 在 15 秒后报
`Browser session launch was timed_out`，最终为 1 error；构建退出十余分钟后，目标 ChromeDriver 已成为
`PPID=1` 且 headless Chrome 仍存活。这证明旧 `service.stop()` fallback 既误报了可补偿 teardown，也没有
形成进程退出证据。修复后的同一真实用例再次耗时约 40 秒，实际越过 graceful 边界后仍成功收敛，20 项
联合门禁全绿，随后 OS 扫描没有 ChromeDriver、headless Chrome 或夹具进程残留。

修复提交 `21c7cef5` 又创建不共享 `target` 的 detached worktree 并执行完整门禁：

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
```

结果为 3921 tests，0 failures、0 errors、2 个条件浏览器跳过，Spring Boot 可执行 JAR 重打包成功，总耗时
7 分 40 秒。其中 `VisualAuthoringBrowserDomTest` 为 34 tests、0 failures、0 errors、2 skips；launcher、
closer 与 process-scope 19 项内核测试全部执行且全绿。独立解析 446 份 Surefire XML 得到相同汇总，
构建退出后没有受监督进程残留。该结果是本次生命周期修复的最终全量基线。

## 5. 未完成边界

本增量关闭的是“浏览器测试 JVM 可被第三方 session handshake 永久拖死”的质量门禁活性缺口，
不是以下能力：

- cgroup/Job Object 级原子 process-domain capture、快照间新生后代封闭与 orphan 扫描；
- CI runner/container 的 CPU、内存、进程数和总墙钟预算；
- browser crash dump、视频与网络日志的受治理保留；
- Selenium/Chrome 精确 CDP 版本收敛；
- Resource Gateway 业务运行时的 provider-confirmed cancellation、跨进程 worker supervision 或物理
  attempt isolation。

下一步仍应回到 Stage 4 运行时：先定义 provider-confirmed cancellation receipt 与独立进程 fence，
再接 runtime-state dispatch、orphan reconciliation 和进程级强制终止。没有这些证据前，不能把测试
基础设施的 daemon 线程策略外推为生产 hard cancellation。
