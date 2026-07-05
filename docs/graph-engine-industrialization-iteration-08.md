# Graph Engine 工业化产品化迭代 08：控制动作时间线查询模型

## 背景判断

第七轮把恢复动作写成了 `ATTEMPTED -> SUCCEEDED/FAILED` 审计时间线，方向正确，但仍有一个产品化硬伤：**时间线只存在于 raw `GraphAuditEntry.inputJson/outputJson` 里**。

这意味着控制台、告警系统、自动化脚本和未来幂等逻辑都要重复解析 JSON 字符串。这个设计会快速腐烂：

1. UI 要知道 `attemptStatus` 藏在 `outputJson`。
2. 自动化要自己从 `inputJson` 抠 `requestId/actionCode/target`。
3. 失败复盘要自己判断 `failurePhase/failureClass/failureMessage`。
4. 后续 request id 幂等无法复用稳定投影，只能散落在 service 代码里。

工业级控制面不能把核心语义藏在字符串 payload 里。本轮补一等查询模型，让恢复证据从“可存储”变成“可消费”。

## 本轮要补的工业化缺口

| 缺口 | 当前状态 | 产品化问题 | 本轮处理 |
| --- | --- | --- | --- |
| 控制动作查询 | 只有 raw audit endpoint | UI/自动化重复解析 JSON | 新增 `GraphControlActionEntry` DTO |
| 阶段语义 | `attemptStatus` 在 `outputJson` | 消费者无法稳定过滤阶段 | DTO 显式暴露 `attemptStatus` |
| 失败复盘 | failure 信息在 JSON | 控制台不能直接展示失败原因 | DTO 显式暴露 failure fields |
| 目标对象 | item/node/instance 分散在 payload | 不能直接展示影响范围 | DTO 显式暴露 target/restored/candidate 字段 |
| 后续幂等基础 | request id 只是 payload 字段 | 幂等实现缺统一读取路径 | DTO 显式暴露 `requestId` |

## 本轮目标

1. 在 model 模块新增 `GraphControlActionEntry`：
   - 基础范围：instance/definition/version/tenant/namespace/node/recordedAt
   - 证据：actionCode/sourceActionCode/sourceIndicatorCode/reason/actor/requestId
   - 阶段：attemptStatus/status
   - 目标：itemId/itemType/targetNodeId/waitId/taskId/instanceId/requestedNodeIds
   - 结果：candidate/restored item count/id/node
   - 失败：failurePhase/failureClass/failureMessage
   - 原始 payload：rawInputJson/rawOutputJson
2. `GraphEngineService` 新增 `queryInstanceControlActions(instanceId, page, size)`。
3. `DefaultGraphEngineService` 从 `AuditJournalStore` 查询 `CONTROL_ACTION`，先过滤再分页，再投影 DTO。
4. JSON 投影必须降级：
   - malformed input/output 不能让整个 API 失败。
   - 缺字段时返回 `UNKNOWN` 或空值。
5. server 新增 endpoint：
   - `GET /api/v1/instances/{instanceId}/control-actions`
6. 更新 controller stub/test。
7. 更新 service/server README 和本轮评估。

## 非目标

- 不做 request id 幂等锁。
- 不改第七轮 audit 写入格式。
- 不新增数据库表或 audit journal store 查询索引。
- 不做控制台 modal。
- 不做 Resource Gateway 迁移样例。

## 设计选择

### 方案 A：让前端继续用 `/audit`，只在文档里说明 JSON 字段

这个方案成本最低，但会把 `attemptStatus`、`requestId`、失败字段的解析逻辑复制到每个消费者。长期看这是明显的熵源。

### 方案 B：新增一等控制动作 DTO 和 endpoint

这个方案增加少量 model/service/server 代码，但能把控制动作语义集中在 service 层，给 UI、告警系统、自动化、未来幂等判断提供同一读取路径。

本轮采用方案 B。理由：第七轮解决“写证据”，第八轮必须解决“消费证据”。没有一等查询模型，后续幂等和控制台确认都会建立在字符串解析之上。

## API 合同草案

```http
GET /api/v1/instances/{instanceId}/control-actions?page=0&size=50
```

返回：

```json
[
  {
    "instanceId": "exec-1",
    "actionCode": "RETRY_DEAD_LETTER",
    "sourceActionCode": "RETRY_DEAD_LETTER",
    "sourceIndicatorCode": "DEAD_LETTER_OLDEST_AGE",
    "reason": "validated replay",
    "actor": "ops-alice",
    "requestId": "INC-123",
    "attemptStatus": "SUCCEEDED",
    "status": "RESTORED",
    "itemId": "dead-1",
    "targetNodeId": "approval",
    "restoredItemCount": 1,
    "restoredItemIds": ["dead-1"],
    "failurePhase": null,
    "recordedAt": "2026-07-06T00:00:00Z"
  }
]
```

## 实施计划

1. 新增 model record `GraphControlActionEntry`，构造器做列表归一化。
2. `GraphEngineService` 增加查询方法。
3. `DefaultGraphEngineService` 增加：
   - raw audit -> control action filter
   - safe JSON object decode
   - string/int/list field extraction
   - malformed payload 降级
4. `GraphInstanceController` 增加 `/control-actions` endpoint。
5. 更新 `AbstractGraphControllerTest` stub 和 `GraphInstanceControllerTest`。
6. 在 `DefaultGraphEngineServiceTest` 增加：
   - 成功 retry 的一等 DTO 投影
   - 失败 retry 的 failure fields 投影
   - malformed control action payload 不炸 API
7. 更新 README 和本轮评估。
8. 运行聚焦 service/server 测试，再跑 `mvn -f graph-engine-examples/pom.xml -pl server -am test`。

## 验收标准

1. 控制动作 API 只返回 `CONTROL_ACTION`，不混入普通 node audit。
2. `ATTEMPTED/SUCCEEDED/FAILED` 显式出现在 DTO 字段中。
3. requestId/actionCode/sourceIndicatorCode/actor/reason 显式出现在 DTO 字段中。
4. 失败 action 显式返回 failure phase/class/message。
5. 部分恢复 action 显式返回 candidate/restored item 信息。
6. malformed raw JSON 不导致 API 500。
7. `mvn -f graph-engine-examples/pom.xml -pl server -am test` 通过。

## 本轮后质量评估

### 已实现内容

1. model 模块新增 `GraphControlActionEntry`：
   - 显式暴露 action/evidence 字段：`actionCode`、`sourceActionCode`、`sourceIndicatorCode`、`reason`、`actor`、`requestId`
   - 显式暴露阶段字段：`attemptStatus`、`status`
   - 显式暴露目标字段：`itemId`、`itemType`、`targetNodeId`、`waitId`、`taskId`、`targetInstanceId`、`expectedRevision`、`requestedNodeIds`
   - 显式暴露结果字段：candidate/restored item count/id/node
   - 显式暴露失败字段：`failurePhase`、`failureClass`、`failureMessage`
   - 保留 `rawInputJson` / `rawOutputJson` 供排障和兼容旧数据
2. `GraphEngineService` 新增 `queryInstanceControlActions(instanceId, page, size)`。
3. `DefaultGraphEngineService` 新增控制动作投影：
   - 先过滤 `AuditEventType.CONTROL_ACTION` 再分页
   - 将 raw audit input/output JSON 解码为 DTO 字段
   - malformed JSON 降级为 `AttemptStatus.UNKNOWN`，不让整个查询失败
4. server 新增 endpoint：
   - `GET /api/v1/instances/{instanceId}/control-actions`
5. service/server README 已同步新查询模型和 endpoint。

### 测试覆盖

新增/调整测试覆盖：

1. `DefaultGraphEngineServiceTest`：
   - dead-letter retry 成功后，`queryInstanceControlActions` 返回 `ATTEMPTED` + `SUCCEEDED`
   - dead-letter retry 失败后，DTO 暴露 requestId、failure phase/class/message、restored count
   - malformed control action payload 不导致查询失败，并返回 `UNKNOWN`
   - instance retry 成功后，DTO 暴露 requested node、requestId、restored item
   - instance retry 部分失败后，DTO 暴露 candidate/restored item 信息
2. `GraphInstanceControllerTest`：
   - `/api/v1/instances/{id}/control-actions` 返回结构化字段并传递分页参数

### 验证结果

已通过：

```bash
git diff --check -- docs/graph-engine-industrialization-iteration-08.md graph-engine-examples/model/src/main/java/com/leanowtech/bloge/graphengine/model/GraphControlActionEntry.java graph-engine-examples/service/src/main/java/com/leanowtech/bloge/graphengine/service/GraphEngineService.java graph-engine-examples/service/src/main/java/com/leanowtech/bloge/graphengine/service/DefaultGraphEngineService.java graph-engine-examples/service/src/test/java/com/leanowtech/bloge/graphengine/service/DefaultGraphEngineServiceTest.java graph-engine-examples/server/src/main/java/com/leanowtech/bloge/graphengine/server/rest/GraphInstanceController.java graph-engine-examples/server/src/test/java/com/leanowtech/bloge/graphengine/server/rest/AbstractGraphControllerTest.java graph-engine-examples/server/src/test/java/com/leanowtech/bloge/graphengine/server/rest/GraphInstanceControllerTest.java graph-engine-examples/service/README.md graph-engine-examples/server/README.md
mvn -f graph-engine-examples/pom.xml -pl service -am -Dtest=DefaultGraphEngineServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -f graph-engine-examples/pom.xml -pl server -am -Dtest=GraphInstanceControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -f graph-engine-examples/pom.xml -pl server -am test
```

最终 `server -am test` 结果：75 个 server reactor 测试通过，0 failure，0 error，0 skipped；model/mybatis/ai/service/server reactor 均成功。

### 本轮质量判断

这轮把第七轮的“可记录”推进成了“可消费”。控制动作不再只是 audit payload 里的字符串，而是有了一等 DTO 和 REST endpoint。这个改动很关键：控制台、自动化、告警系统、未来 request id 幂等判断，都可以依赖同一套 service 投影，而不是复制 JSON 解析规则。

降级策略也补对了。malformed legacy payload 不会把 API 打成 500，而是保留 raw payload 并返回 `attemptStatus = UNKNOWN`。这让系统能带着历史数据向前演进，不要求一次性清洗所有旧审计。

### 与工业级产品化目标的距离

| 维度 | 本轮前 | 本轮后 | 说明 |
| --- | --- | --- | --- |
| 恢复动作闭环 | 高+ | 高+ | 动作时间线已能被 API 消费 |
| 审计可追踪性 | 高 | 高+ | raw audit 升级为结构化 DTO |
| 值班复盘能力 | 高 | 高+ | failure/candidate/restored 字段可直接展示 |
| 自动化接入基础 | 高- | 高 | 自动化无需解析 raw JSON，但仍无幂等锁 |
| 控制台受控执行体验 | 中 | 中高 | 有查询地基，仍缺确认 modal |
| 运维可配置性 | 中高 | 中高 | 本轮未做 tenant/namespace policy |
| Resource Gateway 迁移叙事 | 中 | 中 | 仍缺 durable migration 样例 |
| 工业级完成度 | 约 93% | 约 94% | 仍未进入 5% 差距以内，需要继续迭代 |

### 仍然存在的差距

1. `requestId` 仍然不是幂等锁；重复调用不会自动复用已有控制动作结果。
2. 控制台还没有 reason/requestId/revision confirmation modal。
3. Operations policy 仍是进程级，不支持 tenant/namespace/deployment 级策略。
4. 控制动作 timeline 现在是实例内查询，还没有全局/tenant 级检索视图。
5. Resource Gateway -> Graph Engine durable 迁移样例仍缺，产品化故事还没被迁移实证闭合。

### 下一轮优先级

下一轮建议做 **requestId 幂等与受控执行确认**：

1. 基于 `GraphControlActionEntry` 建立最小 request id 幂等语义：
   - 同一 action/target/requestId 的重复调用能识别已有 `SUCCEEDED` 或 `FAILED` 结果。
   - 没有 requestId 的请求保持当前非幂等行为。
2. 控制台为 dead-letter retry / instance retry 增加 reason + requestId + revision confirmation modal。
3. 若 UI 范围过大，先在 API 层返回明确 duplicate/idempotent outcome，再接控制台。
4. 开始补 Resource Gateway 到 Graph Engine durable 的迁移样例，收束整体产品化叙事。
