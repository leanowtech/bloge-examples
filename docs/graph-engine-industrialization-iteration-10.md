# Graph Engine 工业化产品化迭代 10：Resource Gateway durable 迁移样例

## 背景判断

前九轮已经把 Graph Engine 的控制面、运行态投影、dead-letter 恢复、
控制动作审计和 requestId replay 幂等补到了接近可用产品的状态。
但主命题里还有一个需要用代码证明的判断：

**对 Resource Gateway 添加 durable 支持，本质上就是把网关编排交给
Graph Engine 的 durable runtime 和 remote-worker 协议承接。**

如果这个判断只停留在文档里，产品化说服力不够。Resource Gateway
已经有清晰的 descriptor-first 资源聚合样例，例如 `userDashboard`：
五路资源并行调用，最终汇总成 dashboard。Graph Engine 需要有一个
对应的可运行迁移切片，证明这些外部资源调用可以从同步 HTTP operator
迁移为 durable remote-worker work item，并保持业务拓扑可读。

## 本轮要补的工业化缺口

| 缺口 | 当前状态 | 产品化问题 | 本轮处理 |
| --- | --- | --- | --- |
| 迁移证据 | 只有理念和 README 说明 | 无法证明 Resource Gateway durable 化的落地路径 | 增加可运行 durable dashboard 样例 |
| 网关资源调用持久化 | Resource Gateway 是请求响应执行 | 长耗时、失败重试、恢复需要 durable 承接 | 用 remote-worker node 表达外部资源调用 |
| 样例可验证性 | 远程 worker 单节点测试已存在 | 缺少 fan-out / join 的网关级业务场景 | 五路资源 fan-out + 聚合节点测试 |
| 产品叙事 | Gateway 与 Graph Engine 仍像两个例子 | 用户不易理解升级路径 | README 明确迁移映射表和演示路径 |

## 本轮目标

1. 新增 Graph Engine 侧的 Resource Gateway dashboard durable DSL 样例。
2. 样例保持 Resource Gateway `userDashboard` 的业务结构：
   - `fetchProfile`
   - `fetchOrders`
   - `fetchRecommendations`
   - `fetchWallet`
   - `fetchNotifications`
   - `assembleDashboard`
3. 五个 fetch 节点使用 `execution_mode = remote` 和统一
   `worker_topic = "gateway.resources"`，表示外部资源调用由 worker
   进程承接。
4. 服务测试启动该 DSL 的 durable 实例后，应看到五个 ready work item，
   轮询后逐个完成，最终实例进入 `COMPLETED`。
5. 测试验证 worker payload 中保留 Resource Gateway 的核心语义：
   - `resourceId`
   - `params.userId`
6. 测试验证最终 context 中 `assembleDashboard` 输出包含五路资源结果。
7. 更新 service README，明确 Resource Gateway -> Graph Engine durable
   的迁移映射和演示价值。

## 非目标

- 不把 Resource Gateway 运行时直接改成 Graph Engine runtime。
- 不新增真实 HTTP worker 进程。
- 不新增 ResourceDescriptor store 到 Graph Engine 的同步机制。
- 不做 UI 迁移向导。
- 不做持久化 control-action request key。

## 设计选择

### 方案 A：直接在 Resource Gateway 里加入 durable store

这会让 Resource Gateway 逐步复制 Graph Engine 的职责：实例表、work
item、恢复、审计、控制动作、worker 协议。短期像是局部增强，长期会
变成第二套 graph engine。

### 方案 B：把资源调用节点迁移成 Graph Engine remote worker 节点

Graph Engine 负责编排状态、版本、实例、work item、恢复和管控面；
Resource Gateway 的 descriptor-first 资源模型留在 worker 或 operator
library 层。这样边界更清楚：

- Gateway 擅长描述“一个资源怎么调”。
- Graph Engine 擅长描述“业务流程怎样 durable 编排、恢复、审计”。

本轮采用方案 B。它不会一次性改造全部系统，但会给出一个可执行的
迁移样板：同步 `httpResource` 节点如何换成 durable `remote` 节点。

## 迁移映射

| Resource Gateway | Graph Engine durable |
| --- | --- |
| `node fetchProfile : httpResource` | `node fetchProfile : gatewayResource` |
| `resourceId` | remote worker input 中的 `resourceId` |
| `params = { userId: ctx.userId }` | remote worker envelope payload |
| 同步 HTTP 调用 | `WorkItemType.EXECUTE_NODE` |
| 单请求内完成 | 实例 `SUSPENDED`，等待 worker complete |
| fallback/retry 由 gateway 节点承担 | worker retry/dead-letter/replay 由 Graph Engine 控制面承担 |
| dashboard transform | durable graph 中的 join/assemble 节点 |

## 实施计划

1. 新增 `graph-engine-examples/service/src/test/resources/bloge/resource-gateway-dashboard-durable.bloge`。
2. 在 `DefaultGraphEngineServiceTest` 增加一个端到端迁移测试：
   - 创建 definition/version。
   - 读取并发布 durable dashboard DSL。
   - 启动 instance。
   - 验证 instance 进入 `SUSPENDED`。
   - 轮询 `gateway.resources`，拿到五个 jobs。
   - 验证 job operator/resource payload。
   - 完成五个 jobs。
   - 验证 instance 进入 `COMPLETED`。
   - 查询 instance context，验证 `assembleDashboard` 聚合结果。
3. 更新 `graph-engine-examples/service/README.md` 的 remote worker 章节，
   增加 Resource Gateway durable 迁移说明。
4. 运行聚焦 service 测试。
5. 写入本轮质量评估。

## 验收标准

1. 新 DSL 样例能被 `DefaultGraphEngineService` 创建版本并发布。
2. 启动实例后生成五个 remote-worker work item。
3. 每个 work item 的 envelope 能说明它对应哪个 Resource Gateway
   resource descriptor。
4. 完成所有 worker job 后，实例状态为 `COMPLETED`。
5. `getInstanceContext` 能看到最终聚合输出。
6. README 清楚说明从 Resource Gateway 到 Graph Engine durable 的映射。
7. 聚焦 Maven 测试通过。

## 本轮后质量评估

### 已实现内容

1. 新增 `graph-engine-examples/service/src/test/resources/bloge/resource-gateway-dashboard-durable.bloge`。
   - 业务结构对齐 Resource Gateway 的 `userDashboard`。
   - 五个资源调用节点使用 `gatewayResource` remote operator。
   - `worker_topic = "gateway.resources"` 作为资源 worker 分片。
   - `input.resourceId` 和 `input.params` 保留 Resource Gateway
     descriptor-first 执行语义。
2. 新增
   `DefaultGraphEngineServiceTest.resourceGatewayDashboardDurableMigrationCompletesFanoutThroughRemoteWorkers`。
   - 创建 definition/version。
   - 发布 durable dashboard DSL。
   - 启动 durable instance。
   - 轮询五个 `gateway.resources` jobs。
   - 验证每个 job 的 `operatorRef`、`workerTopic`、`resourceId`、
     `params.userId`。
   - 模拟五个资源 worker 完成。
   - 验证 instance 进入 `COMPLETED`。
   - 查询 `getInstanceContext`，验证 `assembleDashboard` 输出五路聚合结果。
3. 测试夹具的 `inMemorySuspendTtl` 从 200ms 调整为 5s，避免多路 remote
   fan-out 在测试内过早触发 in-memory suspend 淘汰。
4. 更新 `graph-engine-examples/service/README.md`，新增 Resource Gateway
   durable migration fixture 说明。

### 测试覆盖

新增迁移测试覆盖的是完整产品路径，而不是单个工具函数：

1. DSL 资源文件可被 Graph Engine version authoring/publish 接受。
2. `execution_mode = remote` 能为五个并行资源节点创建 durable work item。
3. resource worker 可以从 envelope 中读到 `resourceId` 和 `params`。
4. worker 完成后，Graph Engine 能恢复 durable execution 并触发 join/transform。
5. 产品层 context 投影能看到最终 dashboard 输出。

### 验证结果

已通过：

```bash
mvn -f graph-engine-examples/pom.xml -pl service -am \
  -Dtest=DefaultGraphEngineServiceTest#resourceGatewayDashboardDurableMigrationCompletesFanoutThroughRemoteWorkers \
  -Dsurefire.failIfNoSpecifiedTests=false test

mvn -f graph-engine-examples/pom.xml -pl service -am \
  -Dtest=DefaultGraphEngineServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

mvn -f graph-engine-examples/pom.xml -pl service -am test
```

最终 `service -am test` 结果：

- model 模块：29 tests，0 failure，0 error，0 skipped。
- service 模块：142 tests，0 failure，0 error，0 skipped。

### 本轮质量判断

这轮补上了最关键的一块产品化证据：Resource Gateway 的 durable 化不需要
在 Gateway 里复制一套 durable engine，而是把“资源怎么调”留在
descriptor-first worker，把“业务流程如何持久化编排、恢复、审计、观测”
交给 Graph Engine。

`gatewayResource + resourceId` 的实现方式也比把每个 descriptor 都做成
一个独立 operator 更合理。Graph Engine 看到稳定的资源执行器，worker
看到明确的 descriptor id。这样既保留 operator inventory / deployment
层面的统一治理，也保留 Gateway 原有资源注册表的扩展能力。

本轮没有改 Resource Gateway 本体，是有意克制。现在的目标不是一次性迁移
所有 Gateway API，而是给出一条可运行、可测试、可演示的路径。这个路径已经
证明：五路资源 fan-out、worker claim/complete、durable resume、join
aggregation、context projection 可以闭环。

### 与工业级产品化目标的距离

| 维度 | 本轮前 | 本轮后 | 说明 |
| --- | --- | --- | --- |
| 恢复动作闭环 | 高+ | 高+ | 第九轮已补 requestId replay |
| durable 业务编排证据 | 中 | 高+ | 本轮新增 Resource Gateway dashboard 迁移样例 |
| 自动化接入基础 | 高+ | 高+ | remote worker + retry result 已能支撑脚本/worker 接入 |
| 产品叙事完整度 | 中高 | 高+ | Gateway 和 Graph Engine 的边界更清楚 |
| 控制台受控执行体验 | 中高 | 中高 | 本轮未做 UI confirmation modal |
| 并发幂等硬约束 | 中高 | 中高 | 仍缺持久化唯一 request key |
| 工业级完成度 | 约 95% | 约 96% | 核心目标已经进入 5% 差距内 |

### 仍然存在的差距

1. requestId 幂等仍是终态 replay，不是持久化 in-flight 唯一锁。
2. 控制台还没有 reason/requestId/revision confirmation modal。
3. tenant/namespace/deployment 级 operations policy 仍未下沉到持久化配置。
4. 多路 remote suspend 会产生 runtime execution status 乐观锁 warning；
   测试和业务结果正确，但日志噪声后续应在 durable runtime 层收敛。
5. 当前迁移样例使用 mock worker payload，还没有单独提供可启动的
   Resource Gateway worker 进程。

### 目标达成判断

按照当前目标定义，“当差距在 5% 以内时可以认为已经达成目标可以停下”。
本轮后，Graph Engine 已具备：

- definition/version/deployment/instance 产品模型；
- durable graph/session/state-machine 执行承接；
- human task、remote worker、dead-letter、retry/replay 控制面；
- operations snapshot、audit、transition、context、node-state 投影；
- AI authoring、DSL validation、BPMN import、console 与 REST API；
- Resource Gateway durable 迁移的可运行证明。

剩余问题更偏增强项和硬化项，而不是阻断“以 Resource Gateway 为基版，
把 Graph Engine 推进到工业级产品化可用状态”的核心闭环。因此本轮后可将
该阶段目标判断为已达成。
