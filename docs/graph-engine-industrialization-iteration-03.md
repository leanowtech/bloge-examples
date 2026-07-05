# Graph Engine 工业化产品化迭代 03：SLO 与 Metrics 合同

## 背景判断

前两轮已经补上 operations snapshot API 和控制台运营首页，但它们仍然偏“人看”。工业级系统不能只靠控制台颜色判断健康，还需要把同一套健康规则固化为可被 API、UI、告警系统和运维 runbook 共同消费的合同。

当前最大问题不是缺更多页面，而是缺一层清晰的 **SLO indicator contract**：

- 控制台知道 `CRITICAL`，但监控平台不知道这个判断来自哪些可度量信号。
- action item 告诉人要处理 dead letter，但没有对应稳定 metric name。
- `GraphEngineMetricsObserver` 已有 product metrics 扩展点，但只覆盖控制面事件 counter，不覆盖 operations snapshot 的当前状态 gauge。
- bounded sample 的限制已经写在 snapshot 里，但没有转化成“这个指标是否完整可信”的显式运行信号。

如果继续堆 UI，这个系统会变漂亮，但不会变工业化。第三轮必须把“健康判断”从页面文案提升为后端合同。

## 本轮要补的工业化缺口

| 缺口 | 当前状态 | 产品化问题 | 本轮处理 |
| --- | --- | --- | --- |
| SLO 合同 | 只有 `health` 与 `actionItems` | 无法对接告警、仪表盘、运行评审 | 新增 `sloIndicators` |
| 指标命名 | metrics observer 只有事件 counter | 当前状态无法被 Micrometer 暴露 | 新增 operations gauge 回调 |
| UI 可解释性 | 控制台显示 health 和 count | 用户不知道哪个 metric 驱动健康 | 控制台渲染 SLO indicator |
| 规则一致性 | action item 与 metrics 分离 | UI/告警可能语义漂移 | 后端同源生成 action 与 SLO |
| sample 完整性 | `truncated` 只是字段 | 告警系统看不到 sample 风险 | `SNAPSHOT_COMPLETENESS` indicator |

## 本轮目标

将 Operations Snapshot 扩展为最小可告警合同：

1. `GraphOperationsSnapshot` 新增 `sloIndicators`。
2. 每个 indicator 包含稳定字段：
   - `code`
   - `health`
   - `metricName`
   - `observedValue`
   - `warningThreshold`
   - `criticalThreshold`
   - `unit`
   - `message`
   - `actionCode`
3. 后端生成以下首批 SLO indicators：
   - `DEAD_LETTER_BACKLOG` -> `ge.operations.dead_letters`
   - `FAILED_INSTANCE_BACKLOG` -> `ge.operations.failed_instances`
   - `SUSPENDED_INSTANCE_BACKLOG` -> `ge.operations.suspended_instances`
   - `ACTIVE_DEPLOYMENT_AVAILABLE` -> `ge.operations.active_deployments`
   - `SNAPSHOT_COMPLETENESS` -> `ge.operations.snapshot_truncated`
   - `CONTROL_PLANE_AVAILABLE` -> `ge.operations.control_plane_available`
4. `GraphEngineMetricsObserver` 新增 operations snapshot gauge 回调。
5. `MicrometerGraphEngineMetricsObserver` 暴露低基数 gauge：
   - `ge.operations.health`
   - `ge.operations.dead_letters`
   - `ge.operations.failed_instances`
   - `ge.operations.suspended_instances`
   - `ge.operations.active_deployments`
   - `ge.operations.snapshot_truncated`
   - `ge.operations.control_plane_available`
6. 控制台 Operations 首页显示 SLO indicators，并在 runtime JSON 中保留它们。
7. 文档明确 gauge 刷新语义：由 `queryOperationsSnapshot` 刷新，适合作为 product control-plane state gauge，不替代底层 runtime telemetry。

## 非目标

- 不做一键 retry、批量恢复、runbook 审批。
- 不改变 `/api/v1/operations/snapshot` 的现有字段语义，只做兼容性新增。
- 不把 bounded sample 伪装成全量 metrics backend。
- 不引入 Prometheus scrape 配置、OTel exporter 或外部 dashboard。
- 不解决 Resource Gateway 到 Graph Engine 的迁移工具链；这仍是后续迭代。

## 实施计划

1. 扩展 `GraphOperationsSnapshot` record 和文档注释，新增 `SloIndicator` 嵌套 record。
2. 在 `DefaultGraphEngineService.queryOperationsSnapshot` 中同源生成：
   - action items
   - SLO indicators
   - overall health
   - metrics observer gauge payload
3. 扩展 `GraphEngineMetricsObserver`：
   - 新增 `onOperationsSnapshot(...)`
   - `NOOP` 实现保持零依赖、零副作用
4. 扩展 `MicrometerGraphEngineMetricsObserver`：
   - 使用 `AtomicInteger` gauge holder 保持强引用
   - 用 `tenant`、`namespace` 低基数 tag 标识 scope
   - 将 health 映射为 `OK=0`、`WARNING=1`、`CRITICAL=2`
5. 更新控制台：
   - details/runtime JSON 包含 `sloIndicators`
   - Overview 新增 SLO Indicators section
   - 每行显示 health、metricName、observed/threshold、message
6. 更新测试：
   - service snapshot 测试覆盖 OK、dead letter critical、suspended warning、metrics observer 回调
   - Micrometer 测试覆盖 gauge 注册、刷新和自定义 prefix
   - REST controller JSON 覆盖 `sloIndicators`
   - console asset 测试覆盖 SLO indicator 文案/字段
7. 更新 README：
   - service README 增加 operations gauge 合同
   - server README 增加 actuator/Micrometer 场景说明

## 验收标准

1. `GET /api/v1/operations/snapshot` 返回 `sloIndicators`，且旧字段仍存在。
2. dead letter、failed instance、suspended instance、no active deployment、snapshot truncated、control-plane unavailable 都有稳定 SLO indicator code。
3. Micrometer observer 能看到 `ge.operations.*` gauge，并且重复 snapshot 查询会更新 gauge 值而不是累加 counter。
4. 控制台 Operations 首页能看到 SLO Indicators。
5. `GraphEngineMetricsObserver.NOOP` 对新增方法不抛异常。
6. 聚焦测试和 `server -am test` 通过。

## 本轮后质量评估

### 已实现内容

1. `GraphOperationsSnapshot` 新增 `sloIndicators`，并定义稳定嵌套 record `SloIndicator`：
   - `code`
   - `health`
   - `metricName`
   - `observedValue`
   - `warningThreshold`
   - `criticalThreshold`
   - `unit`
   - `message`
   - `actionCode`
2. `DefaultGraphEngineService.queryOperationsSnapshot` 同源生成：
   - `actionItems`
   - `sloIndicators`
   - top-level `health`
   - metrics observer gauge payload
3. 新增首批 SLO indicators：
   - `DEAD_LETTER_BACKLOG`
   - `FAILED_INSTANCE_BACKLOG`
   - `SUSPENDED_INSTANCE_BACKLOG`
   - `ACTIVE_DEPLOYMENT_AVAILABLE`
   - `SNAPSHOT_COMPLETENESS`
   - `CONTROL_PLANE_AVAILABLE`
4. `GraphEngineMetricsObserver` 新增兼容性 `default` 方法 `onOperationsSnapshot(...)`，避免破坏外部实现。
5. `MicrometerGraphEngineMetricsObserver` 新增 `ge.operations.*` gauge：
   - `ge.operations.health`
   - `ge.operations.dead_letters`
   - `ge.operations.failed_instances`
   - `ge.operations.suspended_instances`
   - `ge.operations.active_deployments`
   - `ge.operations.snapshot_truncated`
   - `ge.operations.control_plane_available`
6. 控制台 Operations 首页新增 `SLO Indicators` section，runtime JSON 同步包含 `sloIndicators`。
7. 修复 graph-engine reactor 测试链路：`bloge-graph-engine-model` 的 test-jar 绑定到 `process-test-classes`，使 `mybatis` 在 `mvn ... test` 的 `testCompile` 阶段能拿到 store contract 测试基类。
8. README 同步：
   - service README 说明 product metrics 与 operations gauge 刷新语义
   - server README 说明 operations snapshot 的 SLO indicator 合同

### 验证结果

已通过：

```bash
node --check graph-engine-examples/server/src/main/resources/static/console/app.js
mvn -f graph-engine-examples/pom.xml -pl service -am -Dtest=DefaultGraphEngineServiceTest,MicrometerGraphEngineMetricsObserverTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -f graph-engine-examples/pom.xml -pl server -am -Dtest=GraphOperationsControllerTest,GraphEngineConsoleControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -f graph-engine-examples/pom.xml -pl server -am test
```

本轮曾暴露并修复一个验证链路问题：`mvn -pl server -am ... test` 在进入 server 之前会卡在 `mybatis` 测试编译，因为 `mybatis` 的 store contract 测试依赖 `model` 的 test-jar，而原 test-jar 默认到 `package` 阶段才产出。将 test-jar 绑定到 `process-test-classes` 后，完整 server reactor test 已通过。

### 本轮质量判断

这轮把 Operations 从“可视化 triage”推进到“可告警合同”。这是工业化的关键台阶：UI、REST API、Micrometer 指标现在共享同一套后端健康规则，dead-letter backlog、failed/suspended instances、active deployment、snapshot completeness、control-plane availability 都有稳定 code 和 metric name。

但这仍不是完成态。原因也很具体：

- `ge.operations.*` gauge 由 snapshot 查询刷新，不是后台定时采样；如果没人查 snapshot，freshness 仍依赖外部调用。
- indicator 只包含当前值与阈值，还没有 runbook URL、owner、SLO window、age distribution。
- suspended instance 只统计 count，未区分“等待合理信号”和“超龄卡死”。
- worker lease expiration、event journal availability 还没进入首批 indicator。
- 控制台能解释 SLO，但不能直接执行恢复动作，也没有审计确认路径。
- Resource Gateway 到 Graph Engine durable 迁移仍缺端到端样例。

### 与工业级产品化目标的距离

| 维度 | 本轮前 | 本轮后 | 说明 |
| --- | --- | --- | --- |
| 运营健康入口 | 中高 | 中高 | 首屏仍可用，本轮主要不是 UI |
| 可观测性合同 | 中 | 中高 | SLO indicators 与 Micrometer gauge 建立同源合同 |
| 告警可接入性 | 低中 | 中 | 有稳定 metric name 和 gauge，但缺 freshness/alert rule 包 |
| 恢复动作闭环 | 中高 | 中高 | 本轮未做 retry/compensation 操作闭环 |
| 验证链路可靠性 | 中 | 中高 | 修复 reactor `test` 阶段 test-jar 顺序问题 |
| Resource Gateway 迁移叙事 | 中 | 中 | 本轮未补迁移例子 |
| 工业级完成度 | 约 79% | 约 83% | 继续推进，仍未进入 5% 差距以内 |

### 下一轮优先级

下一轮建议做 **恢复动作闭环与 runbook 绑定**：

1. 给 `actionItems` / `sloIndicators` 增加 runbook/action affordance，不只是 message。
2. 在 Operations console 中对 dead letter、failed instance、suspended instance 提供受权限控制的跳转与操作入口。
3. 增加恢复动作审计字段：actor、reason、source indicator、before/after snapshot。
4. 增加 suspended age / dead-letter age 指标，避免只看 count。
5. 开始补 Resource Gateway -> Graph Engine durable 迁移样例，把“durable 支持就是 graph engine”从判断推进到示例证据。
