# Graph Engine 工业化产品化迭代 04：恢复动作与 Runbook 合同

## 背景判断

前三轮把 Graph Engine 的运营入口推进到了“可看、可判断、可告警”：

1. 迭代 01：`/api/v1/operations/snapshot` 聚合实例、部署、死信和 action items。
2. 迭代 02：控制台默认进入 Operations 首页。
3. 迭代 03：`sloIndicators` 与 `ge.operations.*` Micrometer gauge 建立同源健康合同。

现在剩下的硬缺口是 **从判断到恢复动作**。系统已经能告诉用户 `DEAD_LETTERS_PRESENT`，也已经有 `/api/v1/dead-letters/{itemId}/retry`、`/api/v1/instances/{instanceId}/retry` 等恢复 API，但 operations snapshot 没有把这两者绑定起来。结果是：

- action item 是人类文案，不是机器可消费的恢复合同。
- 控制台只能让用户自己“猜下一步该点哪里”。
- 告警系统可以看到 metric，却不知道 runbook code、API endpoint、操作风险等级。
- 审计闭环以后要补时缺少稳定的 action id / source indicator。

工业级产品不能停在“我知道有问题”。至少要做到：**每个运营风险都能带出下一步恢复路径、runbook code、API affordance、风险提示与执行前置条件**。

## 本轮要补的工业化缺口

| 缺口 | 当前状态 | 产品化问题 | 本轮处理 |
| --- | --- | --- | --- |
| Runbook 绑定 | action item 只有 message | 告警和控制台无法关联 SOP | 新增 `runbookCode`/`runbookTitle`/`runbookHref` |
| 恢复动作合同 | API 存在但 snapshot 不暴露 | 用户要自己找 API | 新增 `recoveryActions` |
| 操作风险 | retry/cancel 等动作风险不同 | 控制台无法提示执行代价 | action 标明 `riskLevel` 与 `requiresReason` |
| 目标路由 | 只有 targetType/targetId | 无法表达列表页、过滤器、API endpoint | action 同时包含 `consoleHref` 与 `apiHref` |
| 后续审计 | 没有稳定 source action id | 未来审计难以关联告警来源 | action code 与 SLO/action item 绑定 |

## 本轮目标

把 `GraphOperationsSnapshot.ActionItem` 从“建议文案”升级为“可执行恢复合同入口”：

1. `ActionItem` 新增字段：
   - `runbookCode`
   - `runbookTitle`
   - `runbookHref`
   - `recoveryActions`
2. 新增 `RecoveryAction` record：
   - `code`
   - `label`
   - `description`
   - `method`
   - `apiHref`
   - `consoleHref`
   - `riskLevel`
   - `requiresReason`
   - `requiresRevision`
3. 为首批 action items 绑定 runbook 与 action：
   - `DEAD_LETTERS_PRESENT`
   - `FAILED_INSTANCES_PRESENT`
   - `SUSPENDED_INSTANCES_PRESENT`
   - `NO_ACTIVE_DEPLOYMENT`
   - `SNAPSHOT_TRUNCATED`
   - `CONTROL_PLANE_UNAVAILABLE`
4. 控制台 Operations 首页在 action item 行展示 runbook/action affordance。
5. `recentDeadLetters` 保持样本语义，单条 retry 仍走 Dead Letter 页面或 API；本轮不在 Operations 首页直接执行危险动作。
6. 文档明确边界：这是恢复动作合同和导航，不是无确认执行。

## 非目标

- 不做 Operations 首页的一键 retry/cancel/terminate。
- 不引入新的权限模型；仍沿用 service 层 RBAC。
- 不新增审计表。审计字段会作为下一轮真实执行动作的输入。
- 不做批量恢复。
- 不解决 Resource Gateway 迁移样例；但本轮会继续推进 Graph Engine 运维产品化。

## 实施计划

1. 扩展 `GraphOperationsSnapshot.ActionItem`：
   - 增加 runbook 字段
   - 增加 `List<RecoveryAction>`
   - 保持字段非空默认，避免 JSON null 噪音
2. 在 `DefaultGraphEngineService.operationsActionItems` 中为每个 action item 绑定 runbook 与 recovery actions。
3. 控制台 `app.js`：
   - action item rows 展示 runbook code/title
   - action item details 支持 recovery actions
   - recovery action 点击优先导航 `consoleHref`，没有 consoleHref 时展示 API contract
4. 更新测试：
   - service snapshot 测试断言 action item 的 runbook/action contract
   - REST controller JSON 测试断言 recovery action 字段
   - console asset 测试断言 runbook/recovery action 支持
5. 更新 README：
   - server README 描述 operations snapshot 包含 runbook/recovery actions
   - service README 描述 action contract 边界
6. 运行 `server -am test`。

## 验收标准

1. `GET /api/v1/operations/snapshot` 的 action item 返回 runbook 和 recovery actions。
2. `DEAD_LETTERS_PRESENT` 至少包含：
   - runbook code
   - dead-letter list console route
   - dead-letter retry API pattern
3. `FAILED_INSTANCES_PRESENT` 至少包含 instance filter route 和 instance retry API pattern。
4. 控制台 Operations 首页能展示 action 的 runbook 与 recovery actions。
5. 所有新增字段为兼容性新增，不删除旧 action item 字段。
6. `mvn -f graph-engine-examples/pom.xml -pl server -am test` 通过。

## 本轮后质量评估

### 已实现内容

1. `GraphOperationsSnapshot.ActionItem` 新增 runbook 与恢复动作合同：
   - `runbookCode`
   - `runbookTitle`
   - `runbookHref`
   - `recoveryActions`
2. 新增 `GraphOperationsSnapshot.RecoveryAction`：
   - `code`
   - `label`
   - `description`
   - `method`
   - `apiHref`
   - `consoleHref`
   - `riskLevel`
   - `requiresReason`
   - `requiresRevision`
3. 新增 `RiskLevel`：`LOW` / `MEDIUM` / `HIGH`。
4. 保留旧 5 字段 `ActionItem` 构造函数，避免外部测试/调用方被兼容性新增字段打断。
5. `DefaultGraphEngineService.operationsActionItems` 为首批 action item 绑定 runbook/action：
   - `CONTROL_PLANE_UNAVAILABLE`
   - `DEAD_LETTERS_PRESENT`
   - `FAILED_INSTANCES_PRESENT`
   - `SUSPENDED_INSTANCES_PRESENT`
   - `NO_ACTIVE_DEPLOYMENT`
   - `SNAPSHOT_TRUNCATED`
6. 控制台 Operations 首页新增 `Recovery Actions` section：
   - action item 行展示 runbook code/title
   - recovery action 行展示 method、risk、reason/revision 要求、API path
   - 有 `consoleHref` 的 action 点击后导航到对应控制台入口
7. README 同步：
   - server README 描述 snapshot 的 runbook/recovery action 合同
   - service README 增加 operations action contract 小节
8. 测试同步：
   - service snapshot 测试断言 runbook/action 合同
   - REST controller JSON 测试断言 recovery action 字段
   - console asset 测试断言 `Recovery Actions` / `recoveryActions` / `runbookCode`

### 验证结果

已通过：

```bash
node --check graph-engine-examples/server/src/main/resources/static/console/app.js
mvn -f graph-engine-examples/pom.xml -pl service -am -Dtest=DefaultGraphEngineServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -f graph-engine-examples/pom.xml -pl server -am clean -Dtest=GraphOperationsControllerTest,GraphEngineConsoleControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -f graph-engine-examples/pom.xml -pl server -am test
```

验证过程里有一个操作层注意点：不要并行运行两个覆盖同一 reactor 的 Maven 命令。并行跑 service/server 聚焦测试时，两个构建同时写 `graph-engine-examples/model/target/bloge-graph-engine-model-1.0.0-tests.jar`，导致短暂空 zip。顺序 `clean` 后重跑通过。这不是源码缺陷，但说明本仓库的 Maven reactor 验证仍应按单进程顺序执行。

### 本轮质量判断

这轮把 Operations 从“告诉你有风险”推进到“告诉你该按哪条 SOP、到哪个页面、调用哪个 API、动作风险是什么”。这是恢复闭环的前置合同，价值比单纯加按钮更大：它让 UI、告警系统、自动化脚本和未来审计都能围绕稳定 action code 工作。

但这仍不是完整恢复闭环。原因如下：

- Operations 首页只提供导航与合同，不执行危险动作。
- `requiresReason` 和 `requiresRevision` 已经进入合同，但实际执行请求还没有把 source indicator/action code 写入审计事件。
- runbook 目前是稳定逻辑引用 `runbook://graph-engine/...`，还没有真实 runbook 页面或文档路由。
- dead-letter age、suspended age、worker lease expiration 仍未进入 SLO/action 规则。
- Resource Gateway -> Graph Engine durable 迁移样例仍缺。

### 与工业级产品化目标的距离

| 维度 | 本轮前 | 本轮后 | 说明 |
| --- | --- | --- | --- |
| 运营健康入口 | 中高 | 中高 | 首屏仍可用 |
| 可观测性合同 | 中高 | 中高 | 本轮未扩指标面 |
| 恢复动作闭环 | 中高 | 高- | action item 已有 runbook/action 合同，但未直接执行 |
| 告警到操作链路 | 中 | 中高 | metric/action/runbook 可关联 |
| 审计可追踪性 | 中 | 中 | 有 source code 基础，但执行审计尚未绑定 |
| Resource Gateway 迁移叙事 | 中 | 中 | 本轮未补迁移例子 |
| 工业级完成度 | 约 83% | 约 86% | 继续推进，仍未进入 5% 差距以内 |

### 下一轮优先级

下一轮建议做 **恢复执行审计与 age-based SLO**：

1. 给 dead-letter retry / instance retry 请求增加可选 `reason`、`sourceActionCode`、`sourceIndicatorCode`。
2. 在 service 层把恢复动作写入审计事件，形成“告警 -> 操作 -> 结果”的证据链。
3. 增加 dead-letter age / suspended age indicator，不只看 count。
4. 控制台对单条 dead-letter 或 instance 提供受控执行入口，执行前要求 reason/revision。
5. 开始 Resource Gateway -> Graph Engine durable 迁移样例，让架构判断进入可运行证据。
