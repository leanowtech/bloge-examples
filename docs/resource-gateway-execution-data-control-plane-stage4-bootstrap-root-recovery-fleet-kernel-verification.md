# Stage 4 bootstrap-root recovery fleet kernel verification

## 1. 本步边界

本步把已闭合的单 root-set recovery service 提升为可嵌入的多 root-set 进程内执行内核，新增：

- service/producer 的 immutable public `ExpectedBinding` 投影；
- `ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory` v1；
- exact service/resolver/runtime-binding lane；
- generation rollback 与 same-generation drift 防护；
- `ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker` 的有界 canonical round-robin；
- per-lane runtime failure isolation、payload-free result 和 aggregate runtime snapshot；
- admitted cycle 与 `close()` 的强 quiescence 边界。

本步不是签名动态 inventory、远端 discovery、后台 scheduler、跨副本 durable cursor/sharding 或生产 fleet
产品。默认 test/staging Spring composition 仍只装配一个 root-set lane。

## 2. 根因

单 lane scheduler 的数据库 fence 能保证多个副本不会并发执行同一 ceremony，却不能解决多 root-set 的
工程问题。直接为 N 个 root-set 手写 N 组 scheduler 会产生四类熵：

1. resolver 与 service 可被配置标签串错，错误 lane 会在取得 durable attempt 后才失败；
2. 每个本地 timer 独立抖动，没有全局有界工作预算，root-set 数量直接放大线程和轮询压力；
3. 固定从字典序头部扫描会让异常前缀长期饿死后续 lane；
4. inventory 更新没有代际语义时，回滚或同代换绑可静默改变 signer runtime。

因此本步先建立强绑定、代际和公平内核，再接签名 inventory 与跨副本控制面；把远端发现直接塞进 worker
只会重新引入无界 I/O、凭据泄漏和不可审计漂移。

## 3. Lane 强绑定

`Lane` 同时携带：

- 完整 public `ExpectedBinding`；
- reviewed runtime closure 的 canonical `sha256:` fingerprint；
- durable ceremony service；
- exact approved-cohort authority resolver。

构造时必须满足 `expectedBinding.equals(service.expectedBinding())`。scope/root-set 相同的 lane 在一个
generation 内只能出现一次，snapshot 按 `(scopeId, rootSetId)` 规范排序。fingerprint 不是运行凭据或
provider endpoint；它是上层 inventory authority 对已审核 runtime closure 的内容身份声明。

## 4. Inventory 代际

`bloge.externalSequenceAnchorBootstrapRootRecoveryFleetInventory.v1` 要求：

- generation 从 1 开始且只增不减；
- 每代最多 256 个 lane，允许空代以支持受控 drain；
- 同 generation 的 descriptor、service object 和 resolver object 必须完全不变；
- add/remove/rebind/runtime replacement 必须发布更高 generation；
- worker 见到 generation rollback 或同代漂移时，在调用任何该代 lane 前 fail closed。

SPI 的 `snapshot()` 必须是已认证、非阻塞、进程内读取。HTTPS 拉取、签名验证、撤销、IAM 和 refresh
属于 inventory publisher/authority，不允许隐藏在 worker poll 的调用栈中。

## 5. 公平与有界性

worker 每个 cycle 固定一个 immutable generation，只访问
`min(maximumLanesPerCycle, inventorySize)` 个不同 lane。cursor 保存最后一个实际尝试的 canonical key，
下一 cycle 从严格大于该 key 的 lane 开始，越过尾部后回绕：

```text
inventory = [A, B, C], budget = 2
cycle 1   = [A, B]
cycle 2   = [C, A]
cycle 3   = [B, C]
```

lane 抛出 RuntimeException 时 cursor 仍推进，结果只记录 `runtimeFailure=true`，不保存 exception text；
后续 lane 继续执行。`Error`、inventory unavailable、rollback 或 invariant failure 终止整个 cycle 并进入
`cycleFailureCount`。每个 lane 内部仍由其 journal 原子决定 no-work、approval wait、lease busy、retry
delay、attempt exhaustion 或 acquired fence，fleet worker 不复制这些权威状态。

## 6. Inventory 变更时的 cursor

新 generation 不把 cursor 重置到字典序头部。worker 对旧 cursor 做 upper-bound 定位：

- cursor lane 仍存在时，从其后继继续；
- cursor lane 已删除时，从第一个更大 key 继续；
- 没有更大 key 时回绕到首 lane；
- 新增到 cursor 之前的 lane 会在本轮回绕后获得机会。

这避免每次 inventory 扩容都让后缀 lane 重新饥饿。该 cursor 目前只在进程内，重启会从首 lane 开始；
跨副本长期公平必须由后续 durable shard/cursor 协议证明。

## 7. 生命周期与观测

`runCycle()` 和 `close()` 共用 admission monitor。已进入的 cycle 完成前 close 不返回；close 提交后所有
新 cycle 在读取 inventory 前拒绝。worker 不拥有 inventory、service、resolver 或 provider transport，
因此不关闭这些 caller-owned 资源。

`RuntimeSnapshot` 只包含 cycle/lane aggregate counters、active/closed、最新成功 generation 和最近 cycle
状态，不包含 scope、root-set、resolver、authority、key、fingerprint、payload、endpoint 或 exception。
`CycleResult` 可向已授权 embedder 返回 bounded lane key 与 recovery/execution enum，用于定位哪条公开
root-set lane 需要治理处理，但不携带 provider diagnostics。

## 8. 嵌入方式

应用先为每个 root-set 独立构造 durable service 与 resolver，再发布 immutable generation：

```java
var lane = new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.Lane(
        service.expectedBinding(), reviewedRuntimeBindingFingerprint, service, resolver);
ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory inventory = () ->
        new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.Snapshot(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.Snapshot.SCHEMA_VERSION,
                inventoryGeneration, List.of(lane));
try (var worker = new ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker(
        inventory, authenticatedWorkerId,
        new ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.Policy(30, 16))) {
    var cycle = worker.runCycle();
}
```

`reviewedRuntimeBindingFingerprint` 和 `inventoryGeneration` 必须来自部署治理权威，不能由 worker 根据
任意运行对象自证。变更 resolver/service 时先发布新 generation，再让 worker 读取；不得复用原 generation。

## 9. 验证

聚焦命令：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTest,ExternalSequenceAnchorBootstrapRootRecoveryFleetWorkerTest \
  test
```

当前新增 14 项测试，覆盖 canonical order、binding mismatch、fingerprint、duplicate lane、schema/generation、
三 cycle 公平、poison lane、rollback、descriptor/runtime 同代漂移、跨代 cursor、acquired status、inventory/
fatal failure、空 generation、幂等 close 和 admitted-cycle quiescence；聚焦门禁为 14 tests，0 failures、
0 errors、0 skips。

将 fleet 两个测试类加入 ceremony/publication 既有门禁后，联合聚焦门禁执行 129 tests，0 failures、
0 errors、0 skips。producer、service、fleet inventory 与 fleet worker 4 个公共类型通过
`javadoc --release 25 -Werror -Xdoclint:all` 独立验证，0 warnings、0 errors；该结论不外推为全模块
JavaDoc 已清零。

冻结源码的完整 Resource Gateway `clean verify` 执行 3358 tests，0 failures、0 errors、2 skips；
Browser DOM 34 项中 32 项及 browser workflow 1 项真实执行，并成功重打包 Spring Boot 可执行 JAR。

## 10. 仍未宣称

- signed dynamic inventory publication、witness、revocation、hard expiry 与 durable generation floor；
- enterprise IAM/PDP 对 lane membership、worker、resolver 和 runtime fingerprint 的授权；
- durable cross-replica cursor、shard ownership、rebalance、priority、fleet-wide fairness 与 rollout jitter；
- fleet scheduler、aggregate health/readiness、Spring composition、capability/Schema/HTTP；
- publication fleet、publisher mTLS/pinning、response-key hot rotation 与 anti-equivocation；
- HSM/KMS custody、provider-confirmed cancellation/process isolation；
- PostgreSQL/MySQL 并发、multi-region HA、DR/chaos/soak 和外部 SLO 认证。

这些缺口是下一增量的输入，不能由本地 round-robin green tests 推导为已完成。
