# Graph Engine 工业化产品化迭代 09：恢复请求 requestId 幂等 replay

## 背景判断

第六轮把 `requestId` 放进恢复证据，第七轮补了
`ATTEMPTED -> SUCCEEDED/FAILED` 控制动作时间线，第八轮把时间线投影成
`GraphControlActionEntry`。现在剩下的硬缺口已经非常明确：

**requestId 仍然只是 correlation key，不是幂等语义。**

这在工业控制面里不能接受。恢复操作不是普通查询；重复点击、告警系统重试、网络超时后的客户端重放，都可能让同一个恢复意图被执行多次。即使底层 work item store 能挡住一部分重复 restore，产品层仍然无法给调用方一个清晰答案：这次到底是执行了恢复，还是复用了之前的结果？

本轮补最小但真实的幂等闭环：当调用方提供 `requestId`，并且同一 action/target/requestId 已经存在终态控制动作 `SUCCEEDED` 或 `FAILED`，service/API 不再重新执行恢复，而是返回一个明确的 idempotent replay 结果。

## 本轮要补的工业化缺口

| 缺口 | 当前状态 | 产品化问题 | 本轮处理 |
| --- | --- | --- | --- |
| requestId 语义 | 仅用于审计关联 | 重复请求仍可能重新执行恢复 | 终态控制动作 replay 幂等 |
| API 反馈 | dead-letter retry 返回 204 | 调用方看不出是否重复请求 | 新增结果型 dead-letter retry 响应 |
| revision 重放 | instance retry 依赖 expectedRevision | 第一次成功后重复请求可能被 revision 拦截 | 幂等命中优先于 revision guard |
| 失败复用 | 失败 retry 只能靠日志/审计查 | 相同 requestId 可能再次触发部分恢复 | FAILED 终态也作为幂等结果返回 |
| 控制动作消费 | 已有 DTO 但未用于决策 | 查询模型没有闭环到执行路径 | service 内部复用 `GraphControlActionEntry` |

## 本轮目标

1. 新增 `RetryDeadLetterResult`，让单个 dead-letter retry 返回：
   - `itemId`
   - `instanceId`
   - `retriedItemCount`
   - `idempotentReplay`
   - `attemptStatus`
   - `status`
   - `requestId`
   - failure fields
   - `recordedAt`
2. 扩展 `RetryInstanceResult`，保留原构造器兼容，同时新增：
   - `idempotentReplay`
   - `attemptStatus`
   - `status`
   - `requestId`
   - failure fields
   - `recordedAt`
3. `GraphEngineService` 新增结果型方法：
   - `retryDeadLetterWithResult(itemId, evidence)`
   - 旧 `retryDeadLetter(...)` 继续可用，内部忽略结果。
4. `DefaultGraphEngineService` 在执行恢复前做 requestId replay 检查：
   - 只有 `requestId` 非空时启用。
   - actionCode 必须匹配。
   - target 必须匹配：dead-letter 使用 `itemId`，instance retry 使用 `instanceId + requestedNodeIds`。
   - 只复用 `SUCCEEDED` / `FAILED` 终态，不复用 `ATTEMPTED`。
5. dead-letter retry 支持成功后重复请求：
   - 即使 item 已经不在 dead-letter 查询结果里，也能通过 `WorkItemStore.get(itemId)` 反查 executionId，再读取该实例控制动作时间线。
6. instance retry 幂等命中必须发生在 `expectedRevision` 校验之前：
   - 第一次成功后 revision 变化，重复请求仍应返回 replay 结果。
7. REST 层将 dead-letter retry 从 204 升级为结果型 JSON 响应。
8. 更新 service/server README 和本轮评估。

## 非目标

- 不做分布式 in-flight 幂等锁。
- 不新增 control action table 或唯一索引。
- 不解决两个完全并发请求同时进入 service 时的竞态；这需要持久化唯一键。
- 不做控制台 confirmation modal。
- 不做 tenant/namespace/deployment 级 operations policy。
- 不做 Resource Gateway -> Graph Engine durable 迁移样例。

## 设计选择

### 方案 A：只在文档中要求客户端先查 `/control-actions`

这不是真正产品化。它把幂等责任推给每个调用方，重复请求仍然可能在服务端执行。自动化系统、控制台和脚本会各写一套判断。

### 方案 B：在 service 执行路径复用 `GraphControlActionEntry`

这能用第八轮已经建立的一等投影模型，把“可查询”推进成“可决策”。缺点是仍然基于 audit journal 扫描，没有唯一索引，所以它只能防止已有终态后的重放，不能防止并发双写。

本轮采用方案 B。理由：当前差距不是缺一张更漂亮的表，而是恢复执行路径没有使用已建立的控制动作语义。先把产品行为闭合，再在后续迭代把它下沉成持久化唯一约束。

## 幂等匹配规则

### Dead-letter retry

幂等键：

```text
actionCode = RETRY_DEAD_LETTER
itemId
requestId
```

当找到终态控制动作：

- `SUCCEEDED`：返回 `idempotentReplay = true`，不再 restore。
- `FAILED`：返回 `idempotentReplay = true`，不再 restore，并返回 failure fields。
- `ATTEMPTED`：忽略，继续走正常恢复路径。

### Instance retry

幂等键：

```text
actionCode = RETRY_INSTANCE_DEAD_LETTERS
instanceId
requestedNodeIds
requestId
```

`requestedNodeIds` 使用集合语义，`null` / empty 都表示全实例 retry。
`expectedRevision` 不参与幂等键，否则第一次成功后 revision 变化会破坏重放幂等。

## API 草案

```http
POST /api/v1/dead-letters/{itemId}/retry
```

返回：

```json
{
  "itemId": "dead-1",
  "instanceId": "exec-1",
  "retriedItemCount": 1,
  "idempotentReplay": true,
  "attemptStatus": "SUCCEEDED",
  "status": "RESTORED",
  "requestId": "INC-123",
  "recordedAt": "2026-07-06T00:00:00Z"
}
```

`POST /api/v1/instances/{instanceId}/retry` 继续返回 `RetryInstanceResult`，但响应体新增同样的 replay fields。

## 实施计划

1. 新增 `RetryDeadLetterResult` record。
2. 扩展 `RetryInstanceResult` record，并保留旧双参数构造器。
3. `GraphEngineService` 增加 `retryDeadLetterWithResult` default 方法。
4. `DefaultGraphEngineService` 增加控制动作终态查找 helper：
   - safe requestId 检查
   - terminal status 过滤
   - action/target 匹配
   - 最新终态优先
5. 调整 `retryDeadLetter`：
   - 旧方法调用结果型方法。
   - 结果型方法先尝试 idempotent replay。
   - 正常成功返回 `RetryDeadLetterResult`。
6. 调整 `retryInstance`：
   - admin 校验后、revision 校验前尝试 idempotent replay。
   - 正常成功返回扩展后的 `RetryInstanceResult`。
7. 调整 `GraphDeadLetterController` 返回 JSON 结果。
8. 更新 controller stub/test。
9. 更新 service/server README。
10. 运行聚焦 service/server 测试与 `server -am test`。

## 验收标准

1. 同一 `RETRY_DEAD_LETTER + itemId + requestId` 已有 `SUCCEEDED` 后，重复请求不新增 audit，不再次 restore，返回 `idempotentReplay = true`。
2. 同一 `RETRY_INSTANCE_DEAD_LETTERS + instanceId + requestedNodeIds + requestId` 已有 `SUCCEEDED` 后，重复请求即使 expectedRevision 已过期，也返回 replay 结果。
3. 已有 `FAILED` 终态时，重复请求不再次 restore，返回 failure fields。
4. 未提供 requestId 时，保持现有非幂等行为。
5. dead-letter retry REST 返回结果 JSON，包含 `idempotentReplay` 和 `attemptStatus`。
6. 旧 service `retryDeadLetter(itemId, evidence)` 调用仍可编译运行。
7. `mvn -f graph-engine-examples/pom.xml -pl server -am test` 通过。

## 本轮后质量评估

### 已实现内容

1. 新增 `RetryDeadLetterResult`：
   - 暴露 `itemId`、`instanceId`、`retriedItemCount`
   - 暴露 `idempotentReplay`
   - 暴露 `attemptStatus`、`status`
   - 暴露 `requestId`
   - 暴露 failure phase/class/message
   - 暴露 `recordedAt`
2. 扩展 `RetryInstanceResult`：
   - 保留原 `RetryInstanceResult(instance, retriedItemCount)` 构造器，避免现有测试和调用方破坏。
   - 新增 replay/result 字段：`idempotentReplay`、`attemptStatus`、`status`、`requestId`、failure fields、`recordedAt`。
3. `GraphEngineService` 新增 `retryDeadLetterWithResult(itemId, evidence)` 默认方法。
   - 旧 `retryDeadLetter(itemId)` 与 `retryDeadLetter(itemId, evidence)` 仍可用。
   - 默认实现保持旧实现兼容，不强迫第三方实现立即理解新结果模型。
4. `DefaultGraphEngineService` 新增终态控制动作 replay 检查：
   - 只在 `requestId` 非空且 `AuditJournalStore` 可用时启用。
   - 只复用 `SUCCEEDED` / `FAILED`，忽略 `ATTEMPTED`。
   - dead-letter retry 匹配 `RETRY_DEAD_LETTER + itemId + requestId`。
   - instance retry 匹配 `RETRY_INSTANCE_DEAD_LETTERS + instanceId + requestedNodeIds + requestId`。
   - requested node 使用集合语义，避免节点顺序造成重复恢复。
5. dead-letter retry 成功后重复请求可以通过 `WorkItemStore.get(itemId)` 反查 executionId，再读取实例控制动作时间线。
6. instance retry 在 admin 校验后、revision 校验前做 replay 检查，避免第一次成功后重复请求被旧 `expectedRevision` 误伤。
7. `GraphDeadLetterController` 将
   `POST /api/v1/dead-letters/{itemId}/retry` 从 204 空响应升级为
   `RetryDeadLetterResult` JSON 响应。
8. service/server README 已同步新的 replay 语义和响应合同。

### 测试覆盖

新增/调整覆盖：

1. `DefaultGraphEngineServiceTest`：
   - dead-letter retry 首次成功返回 `idempotentReplay = false`。
   - 同一 `itemId + requestId` 第二次 retry 返回 `idempotentReplay = true`，不新增 audit。
   - dead-letter retry 首次失败记录 `FAILED` 后，同一 requestId 第二次返回 replayed failure fields，不再次 restore。
   - instance retry 首次成功后，同一 requestId 即使用过期 revision 也返回 replay。
   - instance retry 部分失败后，同一 requestId 返回 replayed failure，不改变 work item 状态，不新增 audit。
2. `GraphDeadLetterControllerTest`：
   - dead-letter retry 返回 JSON result。
   - recovery evidence 仍正确映射到 service，并返回 replay/result fields。
3. `GraphInstanceControllerTest`：
   - instance retry 响应包含 `idempotentReplay`、`attemptStatus`、`status`。

### 验证结果

已通过：

```bash
mvn -f graph-engine-examples/pom.xml -pl service -am -Dtest=DefaultGraphEngineServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -f graph-engine-examples/pom.xml -pl server -am -Dtest=GraphDeadLetterControllerTest,GraphInstanceControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -f graph-engine-examples/pom.xml -pl server -am test
```

最终 `server -am test` 结果：75 个 server reactor 测试通过，0 failure，0 error，0 skipped；model/mybatis/ai/service/server reactor 均成功。

### 本轮质量判断

这轮把 requestId 从“可关联”推进成了“已有终态后的可重放幂等”。这不是完整分布式幂等，但它已经解决了生产里最常见、也最危险的一类重复调用：客户端超时后重试、告警系统重复投递、控制台重复提交。只要第一次请求已经留下 `SUCCEEDED` 或 `FAILED` 控制动作，第二次请求就不会再恢复 work item，而是返回 replay result。

更关键的是 instance retry 的 replay 发生在 revision guard 之前。第一次 retry 成功后，实例投影很可能已经变化；如果重复请求还被旧 revision 拦住，那就不是幂等，而是把幂等问题转化成冲突噪声。本轮修掉了这个产品层毛刺。

同时，本轮没有假装自己解决了并发双写。当前实现仍基于 audit journal 查询；如果两个相同 requestId 请求完全同时进入，二者都可能在终态 audit 写入前通过检查。要解决这个问题，下一阶段必须引入持久化 control-action request key 或独立唯一索引。

### 与工业级产品化目标的距离

| 维度 | 本轮前 | 本轮后 | 说明 |
| --- | --- | --- | --- |
| 恢复动作闭环 | 高+ | 高+ | replay 已阻止已完成终态后的重复恢复 |
| 审计可追踪性 | 高+ | 高+ | 控制动作 DTO 同时服务查询和执行决策 |
| 自动化接入基础 | 高 | 高+ | requestId 可用于安全重复调用，但仍非并发唯一键 |
| 控制台受控执行体验 | 中高 | 中高 | API 有明确结果，UI confirmation modal 仍缺 |
| 运维可配置性 | 中高 | 中高 | 本轮未做 tenant/namespace policy |
| Resource Gateway 迁移叙事 | 中 | 中 | 仍缺 durable migration 样例 |
| 工业级完成度 | 约 94% | 约 95% | 接近 5% 差距，但仍需至少补一个强证据面 |

### 仍然存在的差距

1. 幂等仍是 replay 幂等，不是持久化 in-flight 唯一锁。
2. dead-letter retry 结果型 REST 响应是产品合同升级，仍需要在控制台使用它做 confirmation/duplicate feedback。
3. 控制台还没有 reason/requestId/revision confirmation modal。
4. Operations policy 仍是进程级配置，不支持 tenant/namespace/deployment 级策略。
5. Resource Gateway -> Graph Engine durable 迁移样例仍缺，主线判断还缺可运行迁移证据。

### 下一轮优先级

下一轮建议二选一，优先顺序如下：

1. **Resource Gateway -> Graph Engine durable 迁移样例**：用一个可运行例子证明“Resource Gateway durable 支持就是 Graph Engine 产品化承接”，补齐最核心的产品叙事证据。
2. **控制台受控执行 modal**：把 reason/requestId/revision confirmation 接到 retry API result，让值班人员在 UI 里看到 executed vs idempotent replay。
3. **持久化 control-action request key**：把当前 replay 幂等下沉到 store 唯一约束，解决并发 in-flight 重复请求。
