# Resource Gateway Stage 4 Attempt Cancellation Durable Journal 验证

## 1. 要关闭的崩溃窗口

proof kernel 能验证一次 provider response，却不能回答跨进程问题：命令发出后 worker crash，successor
不知道该重试、查询还是创建新 challenge；回执验证后进程在写 queue 前 crash，证明丢失；provider 重启后
sequence 回退，同一 attempt 又可能被另一命令接管。

第二增量引入 `TestSuiteStabilityAttemptCancellationJournal`，把 provider RPC 前后的事实分成两个数据库
线性化点。它没有提前修改 queue status，先让 cancellation proof 本身可恢复、可幂等、可审计。

## 2. 状态机

```text
ABSENT --prepare(exact command + descriptor)--> PREPARED
PREPARED --accept(verified TERMINATED/ALREADY_TERMINAL)--> CONFIRMED
PREPARED --accept(verified NOT_FOUND/REJECTED)-----------> UNCONFIRMED
```

- exact `prepare` replay 返回 retained entry，即使原始新调用 deadline 已过；
- 同一 tenant/environment/attempt/lease epoch 只能有一个 command，换 challenge、reason 或 binding 均冲突；
- terminal entry 只能 exact replay，不能用更新 sequence 或另一 receipt 覆盖；
- signature、time、binding 或 sequence 验证失败时整个事务回滚，entry 保持 `PREPARED`；
- `UNCONFIRMED` 是需要 reconciliation 的事实，不会被投影为 provider-confirmed cancellation。

## 3. 数据库权威

`DatabaseTestSuiteStabilityAttemptCancellationJournal` 使用五组 payload-free 结构：

| 结构 | 作用 |
| --- | --- |
| attempt/epoch lock | 跨副本串行 prepare 与 terminal update |
| provider/deployment lock | 跨副本串行 provider sequence |
| command entry | 冻结 command、descriptor、status 与 optional attestation |
| provider sequence journal | append-only sequence/command/attestation commitment |
| provider floor | 当前 deployment 的 exact latest sequence |

`accept` 先锁 exact attempt，再以数据库时间调用 verifier；验证通过后锁 provider scope，校验 floor 与 latest
sequence journal 连续性，追加新 sequence、推进 floor、更新 terminal entry，全部在一个
`REQUIRES_NEW/READ_COMMITTED` 事务内。任何中途异常都不能留下“floor 已推进但 receipt 未落库”或反序状态。

sequence floor 的作用域是 exact `providerId + deploymentId`；同一 deployment 内必须严格递增，provider
换代后由新的 deployment scope 建立自己的 floor。两个独立 journal 实例共享同一数据库时，provider lock
仍把 floor 推进线性化，不依赖 JVM 内锁。

每个 entry、floor 和 sequence row 都有 canonical whole-row fingerprint。terminal entry 每次读取都反查它
自己的 immutable sequence row，并校验当前 floor 与 latest journal 一致；删 sequence、改 status/sequence
或直接回滚 floor 都会 fail closed。该承诺防普通损坏和未同步写，不把数据库管理员可同时重写所有行的能力
误称为外部防篡改；外部 witness/WORM 仍属后续阶段。

这里还必须区分“接受时可信”和“读取时仍可信”：`accept` 使用数据库时间、冻结 descriptor 和当时配置的
trust key 完整复验 attestation；`find` 校验的是已接受记录、immutable sequence 与当前 floor 的存储连续性，
不会按调用时的新 trust inventory 重新验签。历史密钥到期不应自动抹掉当时的证明，但 compromise/revocation
是否追溯否定历史 receipt 必须由后续带生效时间和 trust generation 的策略显式定义，不能由普通读取暗中改变。

实时接受还采用严格到达时限：数据库时间到达 `confirmationDeadlineAt` 后拒绝新 receipt，即使 provider
声称更早已完成终止。这样关闭离线迟到回执无限改写状态的窗口；需要接纳延迟证明时，应进入独立、受审计的
reconciliation 协议，而不是放宽主 journal。

调用前 `authorizeInvocation` 会再次使用数据库时间检查 `PREPARED` 与剩余 provider latency window，避免
明显过期的 retained command 仍触发副作用。但授权事务在 provider I/O 前结束，二者仍有 TOCTOU：长暂停可在
授权后耗尽窗口。`accept` 的严格到达时限只能阻止迟到 receipt 改写 journal，不能撤销已经发生的 provider
副作用；durable single-use permit 与 provider-side expiry fence 仍是后续必做项。

## 4. 失败语义

| 场景 | 结果 |
| --- | --- |
| exact prepare/accept replay | `REPLAYED`，不新增 sequence |
| same attempt/epoch, different command | `ATTEMPT_COMMAND_CONFLICT` |
| terminal receipt rewrite | `IDEMPOTENCY_CONFLICT` |
| new receipt sequence <= durable floor | `PROVIDER_SEQUENCE_ROLLBACK` |
| absent command | `COMMAND_NOT_PREPARED` |
| new command database-time expired | `COMMAND_EXPIRED` |
| provider unavailable or declared latency exceeds command window | `PROVIDER_INCOMPATIBLE` |
| invalid signature/binding/time | verifier closed failure，事务回滚 |
| signed `NOT_FOUND/REJECTED` | durable `UNCONFIRMED` |
| row/floor/sequence corruption | integrity exception，拒绝投影 |

## 5. 验证

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DatabaseTestSuiteStabilityAttemptCancellationJournalTest test
```

journal core 与 invocation re-authorization 结果为 16 tests，0 failures、0 errors、0 skips；加入
coordinator 后该测试类为 19 tests，其中新增 3 项真实组合门禁。journal core 覆盖：

- prepare/confirmed accept/find 的完整往返和 payload-free sequence 落库；
- exact prepare/terminal replay、deadline 关闭后的 retained replay；
- attempt/epoch 唯一绑定、terminal rewrite 拒绝；
- 两个 attempt 的 sequence advance、rollback 后 PREPARED 可恢复；
- 错签名事务回滚、signed `NOT_FOUND` 只能进入 `UNCONFIRMED`；
- tenant/environment 不可见、expired/incompatible preflight；
- provider 调用前以数据库时间拒绝已过期或剩余窗口小于 frozen descriptor 最大延迟的 side effect；
- entry/floor/sequence 篡改或缺失失败关闭；
- 两线程 exact prepare 只有一个 creator，另一个得到 replay；
- 两个独立 journal 实例竞争同一 provider sequence 时只有一个提交，loser 保持 `PREPARED` 并可用更高
  sequence 恢复；
- barrier、future 与 daemon executor 都有 caller-owned deadline，参与者缺失和 future 永不完成的故障注入
  会在固定时间内失败，不把竞态断言变成无限挂起。

两个新增并发门禁和 JavaDoc 修订完成后，两个公共 journal 类型通过
`javadoc --release 25 -Werror -Xdoclint:all`，0 warnings、0 errors。下述 `3893` 全量数字对应此前冻结的
`3c08bb7c`，用于保留 journal 初始增量的历史证据。后续 coordinator、调用前数据库时间栅栏与浏览器
生命周期修复提交 `21c7cef5` 的 detached worktree 全量基线已前移为 3921 tests、0 failures、0 errors、
2 个条件浏览器跳过，并成功重打包 Spring Boot 可执行 JAR。

coordinator 的 10 项单元测试与 3 项真实 journal 组合测试见
[attempt cancellation coordinator verification](resource-gateway-execution-data-control-plane-stage4-attempt-cancellation-coordinator-verification.md)。

最终隔离全量门禁：

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
```

当前结果为 3921 tests，0 failures、0 errors、2 个条件浏览器跳过，Spring Boot JAR 重打包成功；总耗时
7 分 40 秒。独立解析 446 份 Surefire XML 得到相同汇总，制品大小为 39,339,813 字节，构建退出后没有
ChromeDriver、headless Chrome 或进程夹具残留。该结果证明 journal、coordinator、invocation authorization
与浏览器生命周期修复兼容完整 Resource Gateway 行为面，不把聚焦测试替代成全量回归结论。

## 6. 尚未完成

本增量没有 Spring bean、HTTP route、JSON Schema、test-kit 类型或 capability advertisement。进入产品 worker
前仍需完成：

1. command/receipt retention、HMAC tombstone、legal hold 与容量/SLO；
2. dynamic signed provider inventory、key revocation、trust-generation floor 与 N/N-1 rollout；
3. 已有 coordinator 与 queue `CANCEL_REQUESTED` / journal `CONFIRMED` 的双线性化状态机接线；
4. worker slot 只在 confirmed receipt 后释放，unconfirmed/timeout 进入 bounded orphan lane；
5. process/container provider 的 start identity、exit watcher、late receipt 与 orphan reconciliation；
6. crash/DR/非 H2/并发负载认证及外部 tamper-evident anchor；
7. strict Schema、independent test-kit verifier、health/telemetry/readiness/capability 与 test/staging composition。

因此当前准确表述是“provider-confirmed cancellation 已有可持久化证明内核”，不是“Resource Gateway 已能
强杀任意算子”。
