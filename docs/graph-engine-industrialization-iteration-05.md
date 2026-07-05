# Graph Engine 工业化产品化迭代 05：恢复执行证据链与年龄型 SLO

## 背景判断

前四轮已经把 Graph Engine 从“能跑 durable 编排”推进到“有运营首屏、有 SLO 指标、有 runbook/recovery action 合同”：

1. 迭代 01：`/api/v1/operations/snapshot` 聚合实例、部署、死信和 action items。
2. 迭代 02：控制台默认进入 Operations 首页。
3. 迭代 03：`sloIndicators` 与 `ge.operations.*` Micrometer gauge 建立同源健康合同。
4. 迭代 04：action item 绑定 runbook 与 machine-readable recovery actions。

现在剩下的关键缺口不是“再多一个按钮”，而是 **恢复动作能否留下可追溯证据**。如果用户从 `DEAD_LETTERS_PRESENT` 跳到 retry API，系统必须知道这次 retry 为什么发生、来自哪个 action、对应哪个 SLO indicator、恢复了几条 work item。否则告警、操作和结果仍然断裂，出了事故只能靠人工回忆。

第二个缺口是 SLO 仍偏静态 count。`deadLetterCount = 1` 可能是刚刚发生，也可能已经卡了 6 小时；`suspendedInstances = 1` 可能是合法等待，也可能是无回调的僵尸执行。工业级运营入口必须同时看 backlog 和 age。

## 本轮要补的工业化缺口

| 缺口 | 当前状态 | 产品化问题 | 本轮处理 |
| --- | --- | --- | --- |
| 恢复请求 evidence | retry API 只有目标和 revision | 无法关联告警来源、runbook、操作原因 | 增加 `reason` / `sourceActionCode` / `sourceIndicatorCode` / `actor` |
| 恢复动作审计 | retry restore 后无控制面审计 | 事故复盘无法证明谁因何执行了恢复 | 写入 `AuditEventType.CONTROL_ACTION` |
| 死信年龄 SLO | 只看 dead-letter count | 新旧死信严重性无法区分 | 增加 `DEAD_LETTER_OLDEST_AGE` |
| 挂起年龄 SLO | 只看 suspended count | 合法等待和卡死无法区分 | 增加 `SUSPENDED_INSTANCE_OLDEST_AGE` |
| 指标绑定 | Micrometer 只有 count gauge | 告警系统无法对 age 建阈值 | 增加 age seconds gauges |
| API 文档一致性 | action contract 要求 reason，但 API 未接收 | 控制台/自动化无法按合同执行 | REST DTO 与 README 同步 |

## 本轮目标

1. 新增恢复 evidence value object：
   - `reason`
   - `sourceActionCode`
   - `sourceIndicatorCode`
   - `actor`
2. 扩展恢复 API：
   - `POST /api/v1/dead-letters/{itemId}/retry` 可选请求体接收 evidence。
   - `POST /api/v1/instances/{instanceId}/retry` 在原 `nodeIds` / `expectedRevision` 基础上接收 evidence。
   - 保持老客户端兼容：未传 body 或 evidence 字段时仍可执行。
3. 服务层恢复执行后写审计：
   - dead-letter retry 写一条 `CONTROL_ACTION`，nodeId 使用稳定控制节点名。
   - instance retry 写一条 `CONTROL_ACTION`，记录 restored item count 和 node filter。
   - evidence 通过 audit entry 的 `inputJson` 保存，结果通过 `outputJson` 保存。
4. Operations snapshot 增加 age-based SLO：
   - `DEAD_LETTER_OLDEST_AGE`
   - `SUSPENDED_INSTANCE_OLDEST_AGE`
5. Micrometer observer 增加：
   - `ge.operations.dead_letter_oldest_age_seconds`
   - `ge.operations.suspended_oldest_age_seconds`
6. 文档和测试同步，确保新能力不是“接口上看得到、产品上讲不清”。

## 非目标

- 不做 Operations 首页直接执行 retry。
- 不新增独立审计表；先复用 runtime `AuditJournalStore` 的 `CONTROL_ACTION`。
- 不修改底层 BLOGE runtime SPI。
- 不做批量死信恢复。
- 不把 age 阈值做成配置中心；本轮先沉淀稳定默认阈值和指标合同。

## 默认阈值

| Indicator | Warning | Critical | 说明 |
| --- | --- | --- | --- |
| `DEAD_LETTER_OLDEST_AGE` | 300s | 1800s | 死信应被快速确认；超过 30 分钟视为严重积压 |
| `SUSPENDED_INSTANCE_OLDEST_AGE` | 900s | 7200s | 挂起可能是正常等待；超过 2 小时需要升级排查 |

这些阈值是产品默认值，不是假装适配所有业务。后续工业化应把它们提升为 tenant/namespace 或 deployment policy。

## 实施计划

1. 新增 `RecoveryActionEvidence` service record，并提供空 evidence、JSON 安全序列化输入。
2. 扩展 `GraphEngineService`：
   - 保留旧 retry 方法。
   - 新增带 evidence 的 default overload，老实现不破坏。
3. 更新 `DefaultGraphEngineService`：
   - dead-letter retry 和 instance retry 走带 evidence 的实现。
   - 成功 restore 后写 `CONTROL_ACTION` audit entry。
   - 计算 oldest dead-letter age / oldest suspended age。
   - SLO indicators 和 metrics observer 传递 age seconds。
4. 更新 REST DTO/controller：
   - 新增 dead-letter retry request DTO。
   - 扩展 instance retry request DTO。
5. 更新 Micrometer observer 与测试。
6. 更新 service/controller 测试：
   - evidence 被传入 service。
   - retry 后能在 audit log 看到 `CONTROL_ACTION`。
   - age SLO 和 age metrics 正确。
7. 更新 README：
   - service README 描述恢复 evidence 与 age SLO。
   - server README 描述 retry payload。
8. 顺序运行验证命令，避免并行 Maven reactor 污染测试 jar。

## 验收标准

1. 不带 body 的 dead-letter retry 仍兼容。
2. 带 evidence 的 dead-letter retry 会记录 `CONTROL_ACTION` audit entry。
3. 带 evidence 的 instance retry 会记录 restored count 与 node filter。
4. Operations snapshot 返回两个 age-based SLO indicators。
5. Micrometer 注册并刷新两个 age seconds gauges。
6. `mvn -f graph-engine-examples/pom.xml -pl server -am test` 通过。

## 本轮后质量评估

### 已实现内容

1. 新增 `RecoveryActionEvidence`：
   - `reason`
   - `sourceActionCode`
   - `sourceIndicatorCode`
   - `actor`
2. `GraphEngineService` 保留旧 retry 方法，并新增带 evidence 的 default overload：
   - `retryDeadLetter(String itemId, RecoveryActionEvidence evidence)`
   - `retryInstance(String instanceId, Set<String> nodeIds, long expectedRevision, RecoveryActionEvidence evidence)`
3. `DefaultGraphEngineService` 在恢复成功后写入 `AuditEventType.CONTROL_ACTION`：
   - dead-letter retry 使用 `__control_retry_dead_letter__`
   - instance retry 使用 `__control_retry_instance__`
   - `inputJson` 保存 action/evidence/target
   - `outputJson` 保存 restored item IDs/count
4. REST 层补齐 recovery evidence：
   - 新增 `DeadLetterRetryRequest`
   - 扩展 `RetryInstanceRequest`
   - dead-letter retry body 仍保持 optional，旧客户端不受影响
5. Operations snapshot 增加年龄型 SLO：
   - `DEAD_LETTER_OLDEST_AGE`
   - `SUSPENDED_INSTANCE_OLDEST_AGE`
6. Suspended instance oldest age 超过 critical 阈值时，`SUSPENDED_INSTANCES_PRESENT` action item 从 `WARNING` 升级为 `CRITICAL`。
7. Micrometer observer 增加 age gauges：
   - `ge.operations.dead_letter_oldest_age_seconds`
   - `ge.operations.suspended_oldest_age_seconds`
8. service/server README 已同步 recovery evidence、CONTROL_ACTION audit、age SLO 和 retry payload 示例。

### 验证结果

已通过：

```bash
git diff --check -- <本轮改动文件>
mvn -f graph-engine-examples/pom.xml -pl service -am -Dtest=DefaultGraphEngineServiceTest,MicrometerGraphEngineMetricsObserverTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -f graph-engine-examples/pom.xml -pl server -am -Dtest=GraphDeadLetterControllerTest,GraphInstanceControllerTest,GraphOperationsControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -f graph-engine-examples/pom.xml -pl server -am test
```

全量 `server -am test` 结果：71 个 server reactor 测试通过，0 failure，0 error，0 skipped；上游 model/mybatis/ai/service/server reactor 均成功。

### 本轮质量判断

这轮把恢复链路从“有 action contract”推进到了“执行后可追溯”。现在一个 dead-letter 或 instance retry 可以带着来自 operations snapshot 的 action code、SLO indicator、reason、actor 进入 audit journal。复盘时不再只能看到节点失败和后续恢复结果，而是能看到控制面动作本身。

Age-based SLO 也补上了一个重要盲区：系统不再只知道“有一个死信 / 有一个挂起实例”，还能知道“最老的已经卡了多久”。这对告警优先级、值班判断和产品控制台排序都更接近工业级要求。

### 与工业级产品化目标的距离

| 维度 | 本轮前 | 本轮后 | 说明 |
| --- | --- | --- | --- |
| 运营健康入口 | 中高 | 中高 | 首屏仍稳定 |
| 可观测性合同 | 中高 | 高- | count + age SLO 同源进入 snapshot/metrics |
| 恢复动作闭环 | 高- | 高 | action contract 已可关联真实执行审计 |
| 告警到操作链路 | 中高 | 高 | source action / source indicator 进入 evidence |
| 审计可追踪性 | 中 | 中高 | 成功恢复动作可审计，失败尝试尚未结构化 |
| Resource Gateway 迁移叙事 | 中 | 中 | 本轮未补迁移例子 |
| 工业级完成度 | 约 86% | 约 89% | 仍未进入 5% 差距以内，需要继续迭代 |

### 仍然存在的差距

1. Age 阈值仍是硬编码默认值，尚未进入 tenant/namespace/deployment policy。
2. 只记录成功恢复动作；失败尝试没有结构化 `CONTROL_ACTION_FAILED` 或 equivalent 证据。
3. 控制台仍是导航和合同展示，没有受控 reason/revision confirmation 执行流。
4. 没有恢复动作幂等键或 request id，自动化重复调用时的操作证据还不够强。
5. Resource Gateway -> Graph Engine durable 迁移样例仍缺，产品化叙事还没被可运行迁移路径闭合。

### 下一轮优先级

下一轮建议做 **恢复执行确认体验与策略化阈值**：

1. 给控制台补 reason/revision confirmation modal，先支持单条 dead-letter retry 和 instance retry。
2. 引入 operations policy，至少让 age warning/critical 阈值可配置。
3. 给恢复请求增加 request id / idempotency key。
4. 记录失败的恢复尝试，形成 attempted/succeeded/failed 控制面时间线。
5. 开始 Resource Gateway -> Graph Engine durable 迁移样例，把架构判断压到可运行证据。
