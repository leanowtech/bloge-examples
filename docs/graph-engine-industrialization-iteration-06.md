# Graph Engine 工业化产品化迭代 06：Operations Policy 与恢复请求关联

## 背景判断

第五轮把恢复执行推进到可审计：retry 可以携带 reason、source action、source indicator、actor，并在成功恢复后写入 `CONTROL_ACTION`。同时，Operations snapshot 新增了 dead-letter age 与 suspended age SLO。

但现在还有两个硬缺口：

1. **Age 阈值仍硬编码在 service 实现里**。这会让不同业务域只能接受同一套告警窗口。工业级产品不能把“超过 30 分钟算严重”写死在代码里，因为支付、审批、批处理、客服工单的时效要求完全不同。
2. **恢复请求缺少 request/correlation id**。第五轮已经能记录 who/why/source，但自动化重放、值班系统跳转、外部 ticket 关联仍然缺少稳定 request id。没有 request id，就很难把“告警系统发起的恢复请求”和“审计里成功恢复的动作”可靠连起来。

本轮不做控制台执行 modal，也不做真正的幂等锁。原因很简单：如果 policy 和 request identity 不先固化，UI 只是把硬编码规则包装得更漂亮，不能降低长期熵。

## 本轮要补的工业化缺口

| 缺口 | 当前状态 | 产品化问题 | 本轮处理 |
| --- | --- | --- | --- |
| SLO 阈值来源 | service 硬编码默认秒数 | 业务域无法配置不同恢复窗口 | 新增 `GraphOperationsPolicy` |
| Spring Boot 外部化 | server properties 没有 operations policy | 运维无法通过配置调整阈值 | 新增 `operations` nested properties |
| SLO 响应透明度 | indicator 有 threshold 数值，但来源不可见 | 无法判断是默认策略还是业务策略 | 文档明确 policy 映射 |
| 恢复请求关联 | evidence 只有 reason/source/actor | 无法关联外部 ticket、自动化请求、重复调用 | 增加 `requestId` |
| 审计证据 | `CONTROL_ACTION` 无 request id | 恢复动作复盘仍缺跨系统 join key | audit `inputJson` 记录 request id |

## 本轮目标

1. 新增 `GraphOperationsPolicy`：
   - `deadLetterAgeWarning`
   - `deadLetterAgeCritical`
   - `suspendedInstanceAgeWarning`
   - `suspendedInstanceAgeCritical`
2. `GraphEngineRuntimeSupport` 增加 policy 字段和 builder 方法。
3. `DefaultGraphEngineService` 读取 policy，而不是读取硬编码常量。
4. `GraphEngineServerProperties` 增加 `operations` nested properties，并在 auto-configuration 中注入 runtime support。
5. `RecoveryActionEvidence` 增加 `requestId`：
   - service record 保持空值兼容。
   - REST request DTO 增加字段。
   - audit input JSON 记录 `requestId`。
6. 更新 README 和测试，证明：
   - 默认阈值保持不变。
   - 自定义 policy 会改变 SLO threshold/health。
   - server properties 能把 policy 注入 runtime support。
   - recovery evidence requestId 能从 REST 进入 service，并进入 audit。

## 非目标

- 不做真正 idempotency key 锁定；`requestId` 只是 correlation key。
- 不做 tenant/namespace 级动态 policy store；本轮先做进程级 policy。
- 不做控制台执行确认 modal。
- 不新增审计表。
- 不改底层 BLOGE runtime SPI。

## 实施计划

1. 在 service 模块新增 `GraphOperationsPolicy` record，提供默认值、正值校验和秒数访问。
2. 扩展 `GraphEngineRuntimeSupport`：
   - 新增 `operationsPolicy`
   - builder 接收 policy
   - 默认 `GraphOperationsPolicy.defaultPolicy()`
3. 更新 `DefaultGraphEngineService`：
   - 移除 age SLO 硬编码常量依赖
   - action item 升级和 SLO threshold 使用 policy
   - recovery audit input 写入 `requestId`
4. 更新 REST DTO：
   - `DeadLetterRetryRequest.requestId`
   - `RetryInstanceRequest.requestId`
5. 更新 server properties/autoconfiguration：
   - `spring.bloge.graph-engine.server.operations.dead-letter-age-warning`
   - `spring.bloge.graph-engine.server.operations.dead-letter-age-critical`
   - `spring.bloge.graph-engine.server.operations.suspended-instance-age-warning`
   - `spring.bloge.graph-engine.server.operations.suspended-instance-age-critical`
6. 更新测试：
   - service custom policy test
   - runtime support default/policy test
   - controller evidence mapping test
   - auto-configuration property binding or properties unit test
7. 更新 README 和本轮评估。
8. 顺序运行 service/server 聚焦测试和 `server -am test`。

## 验收标准

1. 未配置 policy 时，默认阈值仍为：
   - dead-letter warning 5m / critical 30m
   - suspended warning 15m / critical 2h
2. 自定义 policy 会改变 `SloIndicator.warningThreshold` / `criticalThreshold` 和 health 判断。
3. server properties 可以构造并注入自定义 operations policy。
4. retry REST payload 中的 `requestId` 能进入 `RecoveryActionEvidence`。
5. `CONTROL_ACTION` audit entry 的 `inputJson` 包含 request id。
6. `mvn -f graph-engine-examples/pom.xml -pl server -am test` 通过。

## 本轮后质量评估

### 已实现内容

1. 新增 `GraphOperationsPolicy`：
   - 默认 dead-letter warning/critical：5m/30m
   - 默认 suspended-instance warning/critical：15m/2h
   - 非正 duration 回落默认值
   - warning 大于 critical 时 fail-fast
2. `GraphEngineRuntimeSupport` 新增 `operationsPolicy`：
   - builder 支持 `.operationsPolicy(...)`
   - 未配置时使用 `GraphOperationsPolicy.defaultPolicy()`
3. `DefaultGraphEngineService` 不再硬编码 age 阈值：
   - `SUSPENDED_INSTANCES_PRESENT` severity 升级使用 policy
   - `DEAD_LETTER_OLDEST_AGE` 和 `SUSPENDED_INSTANCE_OLDEST_AGE` threshold 使用 policy
4. `GraphEngineServerProperties` 新增 `operations` nested properties：
   - `dead-letter-age-warning`
   - `dead-letter-age-critical`
   - `suspended-instance-age-warning`
   - `suspended-instance-age-critical`
5. server auto-configuration 将 `properties.getOperations().toPolicy()` 注入 runtime support。
6. `RecoveryActionEvidence` 新增 `requestId`，并保留旧 4 参数构造兼容。
7. REST retry DTO 新增 `requestId`：
   - `DeadLetterRetryRequest`
   - `RetryInstanceRequest`
8. `CONTROL_ACTION` audit `inputJson` 新增 `requestId`，可关联外部 incident/ticket/automation request。
9. service/server README 已同步 policy 配置项、requestId 语义和 payload 示例。

### 验证结果

已通过：

```bash
git diff --check -- <本轮改动文件>
mvn -f graph-engine-examples/pom.xml -pl service -am -Dtest=DefaultGraphEngineServiceTest,GraphOperationsPolicyTest,MicrometerGraphEngineMetricsObserverTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -f graph-engine-examples/pom.xml -pl server -am -Dtest=GraphEngineServerPropertiesTest,GraphDeadLetterControllerTest,GraphInstanceControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -f graph-engine-examples/pom.xml -pl server -am test
```

全量 `server -am test` 结果：74 个 server reactor 测试通过，0 failure，0 error，0 skipped；model/mybatis/ai/service/server reactor 均成功。

### 本轮质量判断

这轮把 age SLO 从“代码里的固定经验值”推进为“产品级 operations policy”。这不是表面配置化，而是把阈值来源上移到了 runtime support，并让 Spring Boot server 可以外部化绑定。之后无论是 demo、生产环境，还是不同业务域，都不必改 service 代码就能调整恢复窗口。

`requestId` 补上了恢复证据链的跨系统 join key。第五轮能回答“谁因为什么做了恢复”，本轮进一步能回答“这次恢复对应哪个 incident、ticket 或自动化请求”。这仍不是幂等保证，但已经为下一轮幂等锁和失败尝试审计留出了稳定字段。

### 与工业级产品化目标的距离

| 维度 | 本轮前 | 本轮后 | 说明 |
| --- | --- | --- | --- |
| 运营健康入口 | 中高 | 中高 | 入口稳定，规则更可配置 |
| 可观测性合同 | 高- | 高 | SLO threshold 来源可配置 |
| 恢复动作闭环 | 高 | 高 | requestId 强化跨系统关联 |
| 告警到操作链路 | 高 | 高 | action/indicator/request id 都进入 evidence |
| 审计可追踪性 | 中高 | 高- | 成功恢复审计更完整，失败尝试仍缺 |
| 运维可配置性 | 中 | 中高 | age policy 已可配置，仍非 tenant 级 |
| Resource Gateway 迁移叙事 | 中 | 中 | 本轮仍未补迁移例子 |
| 工业级完成度 | 约 89% | 约 91% | 仍未进入 5% 差距以内，需要继续迭代 |

### 仍然存在的差距

1. `requestId` 只是 correlation key，不是幂等锁。
2. 只记录成功恢复动作；失败尝试没有结构化审计。
3. Operations policy 是进程级配置，不是 tenant/namespace/deployment 级策略。
4. 控制台仍没有 reason/revision confirmation modal。
5. Resource Gateway -> Graph Engine durable 迁移样例仍缺，产品化故事还没有被迁移实证闭合。

### 下一轮优先级

下一轮建议做 **恢复尝试时间线与受控执行入口**：

1. 记录 attempted/succeeded/failed 控制面恢复事件，避免失败恢复只散落在 HTTP 错误和日志里。
2. 引入 request id 幂等语义，至少对同一 request id 的重复恢复给出明确结果。
3. 控制台为 dead-letter retry / instance retry 增加 reason + requestId + revision confirmation modal。
4. 开始设计 Resource Gateway 到 Graph Engine durable 的迁移样例，形成从旧基版到新产品态的实证路径。
