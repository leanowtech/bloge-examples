# Resource Gateway Stage 4 Attempt Cancellation Proof Kernel 验证

## 1. 为什么 cooperative cancellation 不够

现有 suite-stability queue 已能把 `RUNNING` 变为 `CANCEL_REQUESTED`，heartbeat/checkpoint 也会阻止陈旧
worker 提交结果。这个围栏回答的是“结果还能不能进入证据链”，不是“外部执行单元是否已经停止”。当算子
阻塞、忽略 interrupt 或已经发起外部写时，控制面提前展示 `CANCELLED` 仍可能伴随后台进程继续运行、
资源槽长期占用和取消后的副作用。

病根不是少一个布尔字段，而是缺少三个独立事实：取消命令是否精确指向当前 attempt、谁有资格证明物理
执行单元已经终止、这个证明能否被重放或跨部署冒用。本增量先冻结证明协议内核，不提前改写现有 queue
状态机。

## 2. 冻结的协议

### 2.1 Content-addressed command

`TestSuiteStabilityAttemptCancellationCommand` 将以下字段纳入 canonical SHA-256：

- tenant、test/staging environment、durable job 与独立 attempt id；
- worker owner、monotonic lease epoch 与 immutable runtime binding fingerprint；
- `CANCELLED`、`DEADLINE_EXCEEDED`、`LEASE_LOST` 或 `WORKER_SHUTDOWN`；
- millisecond-exact request/deadline，窗口限制为 100 ms 到 5 min；
- 32-byte base64url challenge。

`commandId` 必须由 fingerprint 派生。任一字段变化都会形成新命令；相同命令可以由 provider 做精确幂等。
命令不携带 fixture、业务 payload、credential 或 OS process id。

### 2.2 Physical termination receipt

`TestSuiteStabilityAttemptCancellationReceipt` 只允许 `PROCESS`、`CONTAINER`、`VM` 三种隔离证明。
process/container identity 与 terminal state 都以 opaque fingerprint 表达，不向日志或协议传播原始句柄。

| Outcome | 证明力 | 合法 termination mode |
| --- | --- | --- |
| `TERMINATED` | 已终止 | 与隔离边界严格对应：process 只允许 graceful/process kill，container 只允许 container termination，VM 只允许 VM termination |
| `ALREADY_TERMINAL` | 已终止 | only `ALREADY_EXITED` |
| `NOT_FOUND` | 未确认 | only `NONE` |
| `REJECTED` | 未确认 | only `NONE` |

所以“provider 签了字”并不自动等于“attempt 已终止”。`NOT_FOUND` 可能源于错误路由、过期 inventory 或
orphan，必须进入 reconciliation，不能作为成功取消。

### 2.3 Challenge-bound trust

receipt 由 `bloge.testSuiteStabilityAttemptCancellationAttestation.v1` 包装并使用 Ed25519 detached
signature。verifier 在返回 receipt 前重新执行：

1. 重算 command fingerprint 与派生 id；
2. 回绑 command id/fingerprint、attempt id 和 lease epoch；
3. 回绑 descriptor 的 provider、deployment、key 与 isolation mode；
4. 检查 caller deadline、provider maximum latency、future skew 与 trust-key validity；
5. 以 canonical JSON 验证 detached signature。

验证失败只暴露闭集 reason，不保留 provider/crypto exception。静态 trust inventory 是本阶段刻意的
bootstrap 边界；动态 signed inventory、revocation 与跨副本 convergence 属于后续产品接线。
`providerSequence` 在本阶段只具备结构和签名约束，尚无 durable floor，因此不能据此宣称跨重启的
anti-rollback、fork detection 或 exactly-once receipt consumption。

## 3. Provider 调用活性

`TestSuiteStabilityAttemptCancellationCallSupervisor` 使用 1..32 个固定 daemon platform thread 和
`SynchronousQueue`：调用要么立即获得 worker，要么 `SATURATED`，不会形成无界 handoff backlog。
descriptor deadline 为 100 ms..30 s，cancel deadline 为 100 ms..5 min。

timeout/caller interrupt 只请求本地 `Future.cancel(true)`。adapter 若忽略 interrupt，调用仍占用原槽并在
snapshot 中计为 `lingeringCalls`，直到 provider 方法实际返回。该计数避免系统把本地 future 已取消误报为
远端 process 已退出。异常消息、provider id、container id 与命令行不会进入 `InvocationException`。

## 4. 已验证场景

聚焦门禁：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest='TestSuiteStabilityAttemptCancellationVerifierTest,TestSuiteStabilityAttemptCancellationCallSupervisorTest' \
  test
```

结果为 17 tests，0 failures、0 errors、0 skips，覆盖：

- 合法 challenge-bound process kill 与 signed `NOT_FOUND` 的不同证明力；
- 跨 attempt replay、command fingerprint 篡改、lease/provider/deployment/isolation 漂移；
- confirmation deadline、provider latency、future skew、key validity 与伪签名；
- receipt outcome/isolation/termination-mode 真值表和 challenge 长度；
- descriptor/cancel 正常调用、固定槽饱和、adapter 忽略 interrupt 后的 lingering；
- provider failure 脱敏、caller interrupt 恢复、关闭后拒绝与 policy 边界。

最终执行 `mvn -f resource-gateway-examples/pom.xml clean verify`，结果为 3879 tests、0 failures、
0 errors、2 个既有条件浏览器跳过，并成功重打包 Spring Boot 可执行 JAR。相对 3855 基线新增的
24 项正好由 7 项 browser session supervision 内核测试和 17 项 cancellation proof-kernel 测试构成。

五个新增 public protocol 类型另以 `javadoc --release 25 -Werror -Xdoclint:all` 独立验证，
0 warnings、0 errors；该结论不外推为全模块既有 public API 的 JavaDoc 已全部清零。

## 5. 尚未开放的产品能力

本增量没有新增 HTTP route、Schema 或 capability advertisement，也没有装配 Spring bean。下列工作完成前，
Resource Gateway 仍只能诚实声明 cooperative control fence，不能声明 provider-confirmed hard cancellation：

1. 将 attempt identity、command、attestation 与确认状态持久化，建立 provider sequence monotonic floor、
   retention/tombstone/audit；
2. 把 `CANCEL_REQUESTED` 拆成 control-plane stop 与 provider termination 两个线性化点；
3. worker 在释放 local/fleet slot 前获得 confirmed receipt，未确认 attempt 进入 bounded orphan lane；
4. 实现 authenticated HTTPS provider、dynamic signed inventory、key revocation 和 exact deployment routing；
5. 提供真实 process/container runtime，以独立 watcher 观察 exit，而不是相信被取消进程自报；
6. 完成 crash recovery、idempotent replay、late receipt、provider split-brain、timeout 与 orphan reconciliation；
7. 增加 Schema、test-kit independent verifier、health/SLO/capability、test/staging composition 与生产隔离认证。

下一增量应先做 durable receipt authority 与 queue 状态机，而不是先开放 UI 按钮。否则按钮只会把
cooperative cancellation 换一个更有欺骗性的名字。
