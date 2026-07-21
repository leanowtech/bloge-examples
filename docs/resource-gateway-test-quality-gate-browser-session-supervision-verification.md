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

`BoundedBrowserSessionLauncher` 为浏览器测试冻结以下不变量：

1. 启动 deadline 只能是 100 ms 到 60 s 的毫秒整数，调用方 deadline 是返回边界的权威。
2. session factory 只运行在单个 daemon platform thread 上，不在 JUnit 线程执行，也不形成等待队列。
3. session 所有权只有一次原子转移；deadline、caller interrupt 或 factory failure 后不允许迟到转移。
4. timeout、caller interrupt 与 factory failure 只暴露闭集 disposition，不复制浏览器命令、路径或
   provider exception。
5. abort 与 cleanup 各自运行在独立 daemon 边界；任一 hook 不合作都不能延长 JUnit caller deadline，
   也不能阻止另一项回收尝试。
6. 与 timeout 竞态产生的 session 只能被 cleanup 一次，不能被测试继续使用。
7. caller interrupt 必须恢复，不能被测试工具吞掉。
8. 正常 `driver.quit()` teardown 也必须经过同一 15 秒边界；teardown 卡死时用例快速失败，不能拖死
   Surefire JVM。

## 3. 实现边界

实现位于测试源码，不改变 Resource Gateway 产品协议：

- `BoundedBrowserSessionLauncher` 实现 daemon launch、原子 ownership、异步 abort/cleanup 和闭集失败；
- `VisualAuthoringBrowserDomTest` 用它监督 ChromeDriver 构造、setup 失败回收和每个用例的 teardown；
- ChromeDriver service 在 session 成功后由测试实例持有，teardown timeout 时作为独立 abort hook；
- 类级 JUnit timeout 保留为整个业务流程的第二层上限，不再承担 driver handshake 的唯一活性保证。

这里没有把 best-effort abort 伪装成进程级取消证明。daemon 边界保证 Maven/JUnit 可继续收敛，
`service.stop()` 与 `driver.quit()` 是独立回收尝试；外部 OS 进程是否已被强杀仍需独立 process handle、
container/cgroup 或 CI runner 级监督来证明。

## 4. 测试证据

聚焦门禁执行 8 tests，0 failures、0 errors、0 skips：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest='BoundedBrowserSessionLauncherTest,VisualAuthoringBrowserDomTest#composerShowsComplexImportedOperatorSchemaOutlineInRealBrowser' \
  test
```

其中 7 个内核测试覆盖：

- 正常 session 唯一所有权转移；
- factory 忽略 interrupt 时 caller 仍按 100 ms deadline 返回；
- timeout 后迟到 session 的唯一 cleanup；
- caller interrupt 传播与 abort；
- factory diagnostics 脱敏；
- abort hook 自身永久阻塞时 caller deadline 不漂移；
- 非法、非毫秒整数和无界 timeout 拒绝。

真实 Chrome 用例继续完成 operator library 导入、画布拖入、schema outline 与移动端 overflow 断言，
并在受监督 teardown 下退出。Chrome 150 对 Selenium CDP 149 的兼容性告警仍存在，但不改变本次断言。

## 5. 未完成边界

本增量关闭的是“浏览器测试 JVM 可被第三方 session handshake 永久拖死”的质量门禁活性缺口，
不是以下能力：

- OS 级 Chrome/ChromeDriver process-tree 强杀确认与 orphan 扫描；
- CI runner/container 的 CPU、内存、进程数和总墙钟预算；
- browser crash dump、视频与网络日志的受治理保留；
- Selenium/Chrome 精确 CDP 版本收敛；
- Resource Gateway 业务运行时的 provider-confirmed cancellation、跨进程 worker supervision 或物理
  attempt isolation。

下一步仍应回到 Stage 4 运行时：先定义 provider-confirmed cancellation receipt 与独立进程 fence，
再接 runtime-state dispatch、orphan reconciliation 和进程级强制终止。没有这些证据前，不能把测试
基础设施的 daemon 线程策略外推为生产 hard cancellation。
