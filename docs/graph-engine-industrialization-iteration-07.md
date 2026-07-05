# Graph Engine 工业化产品化迭代 07：恢复尝试时间线

## 背景判断

第六轮已经把恢复证据从“谁因为什么执行恢复”推进到“这次恢复对应哪个外部 request/ticket”。但控制面仍有一个不能回避的缺口：**失败的恢复尝试没有结构化时间线**。

现在的 `retryDeadLetter` / `retryInstance` 只在成功恢复后写入 `CONTROL_ACTION`。一旦 restore、dispatch 或后续投影刷新失败，值班人员只能从 HTTP 错误和日志里推断发生过什么。工业级系统不能把失败控制动作丢在日志里；恢复动作本身也必须是可审计事件。

本轮优先补这个缺口。原因很简单：在做真正幂等锁、控制台确认 modal 或 Resource Gateway 迁移样例之前，控制面必须先能回答三件事：

1. 谁尝试恢复了什么？
2. 这次尝试是否成功？
3. 如果失败，失败发生在哪个阶段、恢复了多少对象、错误类型是什么？

## 本轮要补的工业化缺口

| 缺口 | 当前状态 | 产品化问题 | 本轮处理 |
| --- | --- | --- | --- |
| 恢复尝试可见性 | 只有成功 `CONTROL_ACTION` | 失败恢复没有业务审计证据 | 增加 `ATTEMPTED` audit entry |
| 恢复结果时间线 | 成功输出 `RESTORED` | 无法区分尝试、成功、失败阶段 | 输出增加 `attemptStatus` |
| 失败复盘 | restore/dispatch 失败只靠异常 | 值班复盘缺 failure class/message/phase | 增加 `FAILED` audit entry |
| 部分恢复 | instance retry 循环中途失败不可见 | 可能已恢复部分 work item，但审计看不到 | 失败输出记录已恢复 count/id |
| requestId 价值 | 只是成功审计 join key | 失败请求也应可关联 incident/ticket | attempt/failure/success 都携带 evidence |

## 本轮目标

1. `retryDeadLetter` 在执行 restore 前记录 `ATTEMPTED` 控制面审计。
2. `retryDeadLetter` 在 restore/dispatch 成功后记录 `SUCCEEDED` 控制面审计。
3. `retryDeadLetter` 在 restore/dispatch 失败时记录 `FAILED` 控制面审计并重新抛出异常。
4. `retryInstance` 在批量恢复前记录 `ATTEMPTED` 控制面审计。
5. `retryInstance` 成功时记录 `SUCCEEDED`，并保留 restored item count/id/node 信息。
6. `retryInstance` 中途失败时记录 `FAILED`，并记录已恢复的 item count/id/node、failure phase、failure class 和 message。
7. 保持现有 API 兼容：
   - 不新增 REST 路径。
   - 不改变 `RecoveryActionEvidence` 字段。
   - 不改变成功返回 DTO。
8. 更新 service/server README，说明 audit 时间线语义。
9. 补充单测证明成功路径有 attempt+success，失败路径有 attempt+failure。

## 非目标

- 不实现真正 request id 幂等锁。本轮仍只把 `requestId` 作为 correlation key。
- 不新增独立 control action table；继续复用 `AuditJournalStore` 与 `AuditEventType.CONTROL_ACTION`。
- 不改变底层 `AuditEventType` 枚举，避免破坏现有消费者。
- 不做控制台 reason/requestId/revision confirmation modal。
- 不做 Resource Gateway -> Graph Engine durable 迁移样例。
- 不做批量 dead-letter retry。

## 设计选择

### 方案 A：新增 `CONTROL_ACTION_ATTEMPTED` / `CONTROL_ACTION_FAILED` 事件类型

优点是语义更显式；缺点是会扩展底层审计枚举，影响存储、API、前端和已有消费者。对于本轮来说，范围过大。

### 方案 B：继续使用 `CONTROL_ACTION`，在 `outputJson` 中记录 `attemptStatus`

优点是兼容现有审计查询和事件类型；缺点是消费者需要读取 payload 字段判断阶段。

本轮采用方案 B。理由：当前目标是先补完整控制面证据链，不应该为了事件类型洁癖扩大协议破坏面。`CONTROL_ACTION` 表示“控制面动作事件”，`attemptStatus` 表示动作阶段，语义足够清楚。

## 审计输出约定

### attempted

`inputJson`：

- `actionCode`
- `reason`
- `sourceActionCode`
- `sourceIndicatorCode`
- `actor`
- `requestId`
- target fields，例如 `itemId` / `instanceId` / `requestedNodeIds`

`outputJson`：

- `attemptStatus`: `ATTEMPTED`
- `status`: `ATTEMPTED`
- target preview fields，例如 `candidateItemCount`

### succeeded

`outputJson`：

- `attemptStatus`: `SUCCEEDED`
- `status`: `RESTORED`
- `restoredItemCount`
- `restoredItemIds`
- `restoredNodeIds` when available

### failed

`outputJson`：

- `attemptStatus`: `FAILED`
- `status`: `FAILED`
- `failurePhase`
- `failureClass`
- `failureMessage`
- `restoredItemCount`
- `restoredItemIds`
- `restoredNodeIds` when available

## 实施计划

1. 为 `DefaultGraphEngineService` 增加恢复 audit stage helper：
   - `recordDeadLetterRetryAttempt`
   - `recordDeadLetterRetrySuccess`
   - `recordDeadLetterRetryFailure`
   - `recordInstanceRetryAttempt`
   - `recordInstanceRetrySuccess`
   - `recordInstanceRetryFailure`
2. 调整 `retryDeadLetter`：
   - 查询目标并通过权限校验后记录 attempt。
   - restore + dispatch 放入 try/catch。
   - 成功记录 success；失败记录 failure 后重新抛出。
3. 调整 `retryInstance`：
   - 过滤目标后记录 attempt。
   - restore 循环维护 `restoredItems`。
   - dispatch / refresh 失败也进入 failure audit。
   - 成功记录 success 并返回刷新后的实例。
4. 更新单测：
   - 成功 dead-letter retry 出现 attempt + success。
   - restore 失败 dead-letter retry 出现 attempt + failure。
   - 成功 instance retry 出现 attempt + success。
   - instance retry 中途失败记录已恢复数量。
5. 更新 README：
   - service README 描述 `attemptStatus` 时间线。
   - server README 说明 retry payload 会进入 attempt/success/failure audit。
6. 运行聚焦 service test，再运行 `mvn -f graph-engine-examples/pom.xml -pl server -am test`。

## 验收标准

1. 每次目标已解析且权限通过的 retry 至少写入一条 `ATTEMPTED` audit entry。
2. 成功 retry 写入 `ATTEMPTED` + `SUCCEEDED` 两条 `CONTROL_ACTION`。
3. 失败 retry 写入 `ATTEMPTED` + `FAILED` 两条 `CONTROL_ACTION`。
4. 失败 audit 包含 failure class/message/phase。
5. instance retry 失败 audit 包含已恢复 item count/id/node。
6. 旧 REST payload 和 service overload 继续可用。
7. `mvn -f graph-engine-examples/pom.xml -pl server -am test` 通过。

## 本轮后质量评估

### 已实现内容

1. `retryDeadLetter` 现在在目标解析和 admin 校验通过后写入 `ATTEMPTED` 控制面审计。
2. `retryDeadLetter` 成功恢复并完成 dispatch 后写入 `SUCCEEDED` 控制面审计：
   - `attemptStatus = SUCCEEDED`
   - `status = RESTORED`
   - `restoredItemCount = 1`
   - `restoredItemIds`
   - `restoredNodeIds`
3. `retryDeadLetter` 在 restore 或 dispatch 抛异常时写入 `FAILED` 控制面审计：
   - `failurePhase`
   - `failureClass`
   - `failureMessage`
   - 已恢复 item count/id
4. `retryInstance` 现在在批量恢复前写入 `ATTEMPTED`：
   - `candidateItemCount`
   - `candidateItemIds`
   - `candidateNodeIds`
5. `retryInstance` 成功时写入 `SUCCEEDED`，并保留 restored item count/id/node。
6. `retryInstance` restore 循环中途失败、dispatch 失败或 projection refresh 失败时写入 `FAILED`，并记录候选 item 与已恢复 item。
7. 继续复用 `AuditEventType.CONTROL_ACTION`，通过 `outputJson.attemptStatus` 表达时间线阶段，避免破坏现有审计消费者。
8. service/server README 已同步恢复审计时间线语义。

### 测试覆盖

新增/调整 `DefaultGraphEngineServiceTest` 覆盖：

1. dead-letter retry 成功路径产生 `ATTEMPTED` + `SUCCEEDED` 两条 `CONTROL_ACTION`。
2. dead-letter retry restore 失败路径产生 `ATTEMPTED` + `FAILED`，并包含 failure phase/class/message。
3. instance retry 成功路径产生 `ATTEMPTED` + `SUCCEEDED`。
4. instance retry 中途失败时，`FAILED` audit 包含候选数量、已恢复数量和已恢复 item id。

### 验证结果

已通过：

```bash
git diff --check -- docs/graph-engine-industrialization-iteration-07.md graph-engine-examples/service/src/main/java/com/leanowtech/bloge/graphengine/service/DefaultGraphEngineService.java graph-engine-examples/service/src/test/java/com/leanowtech/bloge/graphengine/service/DefaultGraphEngineServiceTest.java graph-engine-examples/service/README.md graph-engine-examples/server/README.md
mvn -f graph-engine-examples/pom.xml -pl service -am -Dtest=DefaultGraphEngineServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -f graph-engine-examples/pom.xml -pl server -am test
```

最终 `server -am test` 结果：74 个 server reactor 测试通过，0 failure，0 error，0 skipped；model/mybatis/ai/service/server reactor 均成功。

### 本轮质量判断

这轮补上的不是一个“多写一条日志”的小修，而是恢复控制面的证据模型升级。现在恢复动作从单点成功审计变成了时间线：attempted -> succeeded/failed。值班人员、自动化系统和后续控制台都能围绕同一个 `requestId` / `actionCode` / `sourceIndicatorCode` 追踪一次恢复动作的完整命运。

最关键的是失败路径不再隐身。restore 失败、dispatch 失败、projection refresh 失败都能进入 `CONTROL_ACTION`，并且 instance retry 的部分恢复会留下已恢复 item count/id。这避免了一个生产上很讨厌的问题：用户看见 500，但不知道系统是否已经恢复了一部分对象。

### 与工业级产品化目标的距离

| 维度 | 本轮前 | 本轮后 | 说明 |
| --- | --- | --- | --- |
| 恢复动作闭环 | 高 | 高+ | 恢复动作已有 attempt/success/failure 时间线 |
| 审计可追踪性 | 高- | 高 | 失败尝试、部分恢复、requestId 都进入审计 |
| 值班复盘能力 | 中高 | 高 | 能解释失败发生阶段与已恢复对象 |
| 自动化接入基础 | 中高 | 高- | requestId 可贯穿失败路径，但仍无幂等锁 |
| 运维可配置性 | 中高 | 中高 | 本轮未做 tenant/namespace 级 policy |
| 控制台受控执行体验 | 中 | 中 | 本轮未做 confirmation modal |
| Resource Gateway 迁移叙事 | 中 | 中 | 仍缺 durable migration 样例 |
| 工业级完成度 | 约 91% | 约 93% | 仍未进入 5% 差距以内，需要继续迭代 |

### 仍然存在的差距

1. `requestId` 仍然只是 correlation key，不是幂等锁；重复 retry 可能产生多组 attempt/success/failure。
2. `attemptStatus` 目前藏在 `outputJson`，没有一等 query model；控制台和告警系统要解析 payload。
3. Operations policy 仍是进程级，不支持 tenant/namespace/deployment 级策略。
4. 控制台还没有 reason/requestId/revision confirmation modal，危险动作仍主要通过 API 执行。
5. Resource Gateway -> Graph Engine durable 迁移样例仍缺，产品化故事还没有被迁移实证闭合。

### 下一轮优先级

下一轮建议做 **恢复请求幂等与控制台确认入口**：

1. 给 `requestId` 建立最小幂等语义：同一 action/target/requestId 的重复调用能返回明确重复结果，而不是盲目再恢复。
2. 将 control action timeline 投影成查询友好的 DTO，减少前端解析 raw JSON 的需要。
3. 在 Operations console 为 dead-letter retry / instance retry 增加 reason + requestId + revision confirmation modal。
4. 开始补 Resource Gateway 到 Graph Engine durable 的迁移样例，证明“Resource Gateway durable = Graph Engine 产品化”的主线。
