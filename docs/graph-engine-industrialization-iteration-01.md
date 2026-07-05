# Graph Engine 工业化产品化迭代 01：运营健康快照

## 背景判断

Resource Gateway 如果继续补 durable、恢复、人工任务、远程 worker、死信重试，本质上会重新长成 Graph Engine。正确方向不是把 Resource Gateway 变成第二套 durable runtime，而是以 Resource Gateway 的资源/算子编排体验为入口，把 Graph Engine 打磨成可产品化承接长流程业务编排的工业级控制面。

当前代码已经具备很多底座能力：definition/version/deployment/instance/task/remote-worker/dead-letter/transition/node-state/context/audit/SSE。差距不在“有没有 durable 概念”，而在产品化闭环还不够：值班人员、演示用户、平台管理员很难一眼判断当前 tenant/namespace 下系统是否健康、风险集中在哪里、下一步该处理什么。

## 工业级目标拆解

工业级 Graph Engine 至少需要以下能力闭环：

1. **可治理的发布链路**：定义、版本、发布、部署、灰度、弃用、回滚都有状态、审计和兼容性约束。
2. **可恢复的执行链路**：实例、节点、等待、信号、任务、远程 worker、死信、重试都有明确生命周期。
3. **可运营的控制面**：不是只提供列表 API，而是能聚合出健康、风险、堵点、恢复建议。
4. **可观测的运行面**：事件、transition、audit、metrics、SSE、节点状态一致，且能解释推导来源。
5. **可迁移的产品入口**：Resource Gateway 的同步资源编排能自然升级为 Graph Engine 的 durable 编排，不产生第二套语义。
6. **可验证的质量门槛**：store contract、service tests、server tests、migration tests、控制台 smoke tests 都覆盖关键场景。

## 当前缺口评估

| 维度 | 当前状态 | 缺口 | 本轮是否处理 |
| --- | --- | --- | --- |
| Durable 执行 | 已有 durable runtime、instance、transition、dead-letter、retry | 语义存在，但入口分散 | 部分处理 |
| 资源编排迁移 | Resource Gateway 与 Graph Engine 概念仍分散 | 缺“Resource Gateway durable = Graph Engine”的统一叙事和迁移路径 | 文档层处理 |
| 运营健康 | 有实例、死信、部署等列表 API | 缺一个产品级健康快照 API | 本轮核心 |
| 恢复动作 | 有 dead-letter retry、instance retry、signal/task/worker API | 缺聚合后的 action item 提示 | 本轮核心 |
| 控制台 | 已有 console | 是否暴露运营健康视图待确认 | 后续 |
| 工业级完整度 | 约 70% | 主要缺 product-facing ops、SLO、回滚/灰度故事、压测与灾备 | 持续迭代 |

## 本轮目标

本轮只做一个高杠杆切片：**Graph Engine Operations Snapshot**。

新增一个 tenant/namespace 维度的运营健康快照，让平台操作者能直接看到：

- 当前实例按状态分布。
- 当前实例按执行模式分布。
- 活跃部署数量。
- 死信数量及最新死信样本。
- 风险等级：`OK` / `WARNING` / `CRITICAL`。
- 面向恢复的 action items，例如“先处理死信”“检查悬挂实例”“确认是否缺部署”。

这不是完整监控系统，也不伪装成精确全局指标。它是控制面 API：基于当前 scope 的第一页运营样本和关键风险信号，先把“系统是否可操作”这件事产品化。

## 验收标准

1. `GraphEngineService` 暴露运营健康快照查询方法。
2. service 层能从现有 metadata/runtime projections 聚合：
   - instance status counts
   - execution mode counts
   - deployment count / active deployment count
   - dead-letter count
   - recent dead-letter samples
   - risk level
   - action items
3. server 暴露 `GET /api/v1/operations/snapshot`。
4. 至少覆盖：
   - 无风险时返回 `OK`。
   - 有死信时返回 `CRITICAL` 并给出 dead-letter action item。
   - 有 suspended/running backlog 时返回 `WARNING`。
   - controller 能按当前 scope 返回快照。
5. 不改 Resource Gateway 现有行为，不引入第二套 durable 语义。

## 实施计划

1. 在 `model` 模块新增运营快照 DTO。
2. 在 `service` 模块新增 `queryOperationsSnapshot(...)` 聚合逻辑。
3. 在 `server` 模块新增 Operations controller。
4. 补 service/controller 测试。
5. 更新 `graph-engine-examples/README.md`，把运营快照纳入产品控制面说明。
6. 本轮后做差距评估，明确下一轮最该补什么。

## 本轮后质量评估

### 已实现内容

1. 新增 `GraphOperationsSnapshot` 作为产品级运营快照 DTO。
2. `GraphEngineService` 新增 `queryOperationsSnapshot(tenantId, namespace)`。
3. `DefaultGraphEngineService` 聚合当前 scope 下的 bounded samples：
   - instance lifecycle status counts
   - execution mode counts
   - deployment / active deployment counts
   - dead-letter count
   - recent dead-letter samples
   - `OK` / `WARNING` / `CRITICAL` health
   - recovery-oriented action items
4. `GraphOperationsController` 暴露 `GET /api/v1/operations/snapshot`。
5. service tests 覆盖：
   - empty scope -> `OK`
   - dead letters -> `CRITICAL`
   - suspended instances -> `WARNING`
6. controller test 覆盖 endpoint scope mapping 和 JSON serialization。
7. `graph-engine-examples/README.md` 与 `server/README.md` 已同步新入口。

### 本轮质量判断

本轮不是 durable 内核增强，而是把已有 durable/control-plane 能力做成了一个产品可用的运营入口。这个方向是对的：工业级系统不能只提供底层列表 API，必须让平台管理员在一分钟内判断“系统是否可恢复、先处理哪里”。

但这还没有让 Graph Engine 达到工业级完成态。当前快照是 bounded sample，不是全量统计；没有接入 metrics backend；没有控制台首屏；也没有 SLO、告警规则、容量阈值、tenant-level error budget、灾备演练和灰度回滚 runbook。因此只能说补上了一个关键切面。

### 与工业级产品化目标的距离

| 维度 | 本轮前 | 本轮后 | 说明 |
| --- | --- | --- | --- |
| 运营健康入口 | 低 | 中 | 已有聚合 API，但未进控制台和告警系统 |
| 恢复动作可见性 | 中 | 中高 | action item 能指向死信/悬挂/部署问题，但还不能一键执行复合恢复 |
| Durable 语义完整度 | 中高 | 中高 | 本轮未改执行内核 |
| Resource Gateway 迁移叙事 | 中低 | 中 | 文档明确方向，但未提供自动迁移/示例 |
| 工业级完成度 | 约 70% | 约 74% | 还远未进入 5% 差距以内 |

### 下一轮优先级

下一轮最该补的是 **控制台运营首页** 或 **SLO/metrics 绑定**，二选一：

1. **控制台运营首页**：把 operations snapshot 接到 `/console` 首屏，让演示用户和平台管理员立即看到健康、死信、悬挂实例和恢复入口。
2. **SLO/metrics 绑定**：把 snapshot 的健康规则和 `GraphEngineMetricsObserver` 对齐，形成可报警的指标合同。

推荐先做控制台运营首页。理由很简单：目标是“产品化可用”，不是只有 API；如果用户看不到，工业化感知仍然弱。
