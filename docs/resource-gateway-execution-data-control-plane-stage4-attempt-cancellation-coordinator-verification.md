# Resource Gateway Stage 4 Attempt Cancellation Coordinator 验证

## 1. 要关闭的顺序缺口

proof kernel 只验证 provider attestation，durable journal 只提供 `prepare/accept/find` 线性化点。如果调用者
可以自行排列三者，就可能先调用 provider 再落 command、绕过 supervisor、把 `NOT_FOUND` 当成功，或在
terminal replay 时再次触发外部终止。

`TestSuiteStabilityAttemptCancellationCoordinator` 冻结以下调用顺序：

```text
find exact command
  -> terminal: exact replay, no provider I/O
  -> absent/PREPARED: bounded descriptor
       -> durable prepare exact command + descriptor
       -> terminal race: exact replay
       -> PREPARED: database-time invocation authorization
          -> bounded idempotent cancel
          -> journal accept + verifier + provider floor
```

它只编排 cancellation proof，不修改 queue/job/worker 状态，也不拥有 process-scoped call supervisor 的关闭。

## 2. 成功与失败语义

| 场景 | coordinator 结果 | durable 结果 |
| --- | --- | --- |
| 新命令 + verified termination | `CONFIRMED` | entry、sequence、floor 原子提交 |
| exact `CONFIRMED` replay | `REPLAYED`，零 provider I/O | 原记录不变 |
| signed `NOT_FOUND/REJECTED` | `UNCONFIRMED` | 只证明 provider 回答，不能释放 slot |
| exact `UNCONFIRMED` replay | `REPLAYED`，零 provider I/O | 仍是 `UNCONFIRMED`，不得升级语义 |
| provider timeout/unavailable | supervisor closed failure | command 保持 `PREPARED` |
| command expired / remaining provider window insufficient | journal conflict，零 cancel I/O | `PREPARED` 保留待 reconciliation |
| invalid attestation | verifier/journal closed failure | command 保持 `PREPARED`，floor 不推进 |
| prepared descriptor drift | journal conflict，零 cancel I/O | 冻结 binding 不改写 |
| journal 返回错 command/descriptor | contract violation | 拒绝继续投影 |

`UNCONFIRMED` 是当前 immutable command 的终态，不是 physical attempt 的终止证明。coordinator 不会自动重试
它；prepared command 的旧 deployment 也不会由当前 authority 猜测解析。两者必须进入后续有界
reconciliation/orphan lane，由动态 provider inventory、旧 generation resolver 和人工升级策略处理。

`authorizeInvocation` 只是一道调用前的数据库时钟栅栏：它锁定 `PREPARED` entry，确认当前 database time
尚未越过 command deadline，并要求剩余窗口至少覆盖 frozen descriptor 的最大 provider latency。该事务会在
provider I/O 前结束，因此它不是 durable invocation permit，也没有把外部终止副作用与数据库提交原子化。
若进程在授权后发生长时间 stop-the-world、调度冻结或网络栈停顿，provider 调用仍可能跨过 deadline；后续
`accept` 会拒绝迟到 receipt 并保留 `PREPARED`，但外部副作用已经可能发生。根治需要 provider 可消费的
single-use fence/lease、到期自拒绝语义或受事务消息驱动的调用代理，并由 reconciliation 处理未知结果。

## 3. 验证证据

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest='TestSuiteStabilityAttemptCancellationCoordinatorTest,DatabaseTestSuiteStabilityAttemptCancellationJournalTest' \
  test
```

结果为 29 tests，0 failures、0 errors、0 skips：

- 10 项 coordinator 单元行为覆盖 fresh ordering、confirmed/unconfirmed replay、prepared recovery、descriptor
  drift、timeout、provider diagnostics 脱敏、accept failure、数据库时钟 invocation 拒绝与 `NOT_FOUND`；
- durable journal 的 19 项中有 3 项使用真实 Ed25519 verifier、H2 transaction 和 provider sequence floor，
  覆盖 verified termination + 零 I/O replay、verified `NOT_FOUND`、timeout 后 durable `PREPARED`；
- 其余 16 项继续覆盖 journal identity、integrity、scope、deadline、剩余 provider window、rollback 和
  跨实例并发。

coordinator 公共类型通过：

```bash
javadoc --release 25 -Werror -Xdoclint:all
```

结果为 0 warnings、0 errors。

提交 `e73ed61d` 的 detached worktree 执行完整
`mvn -f resource-gateway-examples/pom.xml clean verify`，结果为 3921 tests、0 failures、0 errors、2 个条件
浏览器跳过，Spring Boot 可执行 JAR 重打包成功，总耗时 8 分 04 秒。独立解析 446 份 Surefire XML 得到
相同汇总，制品大小为 39,339,813 字节，构建退出后没有 ChromeDriver、headless Chrome 或进程夹具残留。
该全量基线同时包含 coordinator、数据库时间 invocation authorization 以及浏览器质量门生命周期修复。

## 4. 没有完成的产品闭环

本增量仍未实现：

1. queue `CANCEL_REQUESTED -> CANCEL_CONFIRMED/ORPHANED` 与 journal 的双线性化投影；
2. worker slot/fleet permit 只在 exact confirmed receipt 后释放；
3. late receipt、`UNCONFIRMED`、descriptor drift 与 timeout 的 durable bounded reconciliation；
4. 动态 signed provider inventory、旧 deployment 定址、trust revocation 追溯策略；
5. Spring bean、HTTP/Schema/test-kit/capability、retention/tombstone、telemetry/readiness；
6. 真实 process/container/VM provider、OS process-tree 终止证明和 crash/DR/HA/chaos 认证。
7. database authorization 与 provider side effect 之间的 durable single-use invocation permit/expiry fence。

因此准确结论是“provider cancellation 已有可组合、可恢复的调用顺序内核”，不是“Resource Gateway 已具备
可启用的强制取消产品能力”。
