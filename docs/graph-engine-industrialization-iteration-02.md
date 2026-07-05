# Graph Engine 工业化产品化迭代 02：控制台运营首页

## 背景判断

上一轮已经补上 `GET /api/v1/operations/snapshot`，但这还只是 API 层能力。工业级产品化不能停在“接口存在”：演示用户、平台管理员和值班人员打开控制台时，必须先看到系统是否健康、风险集中在哪里、应该点哪里继续处理。

当前控制台默认进入 Graphs 列表。这个入口适合开发者浏览定义，不适合运维人员判断运行面状态。结果是 durable/control-plane 能力虽然存在，但产品感知仍然弱：用户需要知道多个 API 才能拼出运营判断。

## 本轮要补的工业化缺口

| 缺口 | 当前状态 | 产品化问题 | 本轮处理 |
| --- | --- | --- | --- |
| 首屏运营判断 | 控制台默认 Graphs | 用户看不到健康、死信、悬挂实例 | 改为 Operations 首页 |
| 风险聚合可视化 | snapshot API 已有 | JSON 需要人工理解 | 渲染 health、关键计数、action items |
| 恢复路径 | dead-letter/task/instance API 已有 | 缺从风险到处理入口的跳转 | 提供跳转 Queues / Instances / Deployments |
| 演示路径 | API curl 可演示 | 产品感弱 | 控制台首屏即可演示 |
| 工业级闭环 | 有控制面数据 | 缺“观察 -> 判断 -> 处理”的第一环 | 本轮打通第一环 |

## 本轮目标

把 Graph Engine Console 的默认首屏改造成 **Operations Overview**：

1. 新增 `Operations` tab，并作为默认 view。
2. `Operations` view 调用 `/api/v1/operations/snapshot`。
3. 首屏显示：
   - health：`OK` / `WARNING` / `CRITICAL`
   - sampled instances、active instances、terminal instances
   - deployments、active deployments
   - dead letters
   - truncated 标记
4. 左侧列表显示 action items 与最近 dead-letter samples，作为运营待办队列。
5. 主面板提供跳转：
   - Queues：处理 dead letters / tasks / workers
   - Instances：检查 suspended / failed instances
   - Deployments：检查 active deployment
6. `/console/operations` 路由转发到静态控制台。
7. 更新控制台测试，确保 packaged assets 暴露 operations snapshot 入口。

## 验收标准

1. 打开 `/console` 默认进入 Operations。
2. 打开 `/console/operations` 能转发到静态控制台。
3. `app.js` 调用 `/api/v1/operations/snapshot` 并渲染 Overview。
4. 控制台仍保留 Graphs / Instances / Deployments / Operators / Authoring / Queues。
5. 静态资源测试能证明 operations 入口和 snapshot API 被打包。
6. 不引入前端构建链，不改变已有 REST runtime 语义。

## 实施计划

1. 修改 `index.html`：新增 Operations tab，并设为默认 active。
2. 修改 `GraphEngineConsoleController`：新增 `/console/operations` clean route。
3. 修改 `app.js`：
   - 默认 view 改为 `operations`
   - 从 URL path 推导初始 view
   - 新增 `loadOperationsOverview`
   - 新增 health metric/action/dead-letter rendering
   - 保留原有 tab/list/detail 工作流
4. 修改 `styles.css`：补充 operations overview 样式，保持现有控制台视觉体系。
5. 修改 `GraphEngineConsoleControllerTest`：覆盖 operations route 与静态资源引用。
6. 更新 server/graph-engine README 中的 console 说明。
7. 运行 server 聚焦测试与必要的 `server -am test`。

## 本轮后质量评估

### 已实现内容

1. 控制台默认 view 从 `Graphs` 调整为 `Operations`。
2. 新增 `/console/operations` clean route。
3. `app.js` 新增 Operations Overview：
   - 调用 `/api/v1/operations/snapshot`
   - 渲染 health、6 个关键 metric、action items、recent dead letters
   - 左侧运营队列可点击查看详情
   - 主操作区可跳转 Queues / Instances / Deployments
4. `GraphOperationsController` 补入 `GraphEngineServerAutoConfiguration.WebApiConfiguration`，真实 Spring Boot 启动路径可暴露 endpoint。
5. `server` 模块启用 `spring-boot:repackage`，产出可执行 boot jar。
6. `scripts/start-examples.sh graph-engine` 改为先 package Graph Engine reactor，再用可执行 jar 启动，避免依赖本地 Maven 仓库里的旧模块。
7. README 同步：
   - Graph Engine 控制台默认运营首页
   - 推荐脚本启动
   - 手动 package + jar 启动方式

### 验证结果

已通过：

```bash
node --check graph-engine-examples/server/src/main/resources/static/console/app.js
mvn -f graph-engine-examples/pom.xml -pl server -am -Dtest=GraphEngineConsoleControllerTest,GraphOperationsControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -f graph-engine-examples/pom.xml -pl server -am test
mvn -f graph-engine-examples/pom.xml -pl server -am package -DskipTests
GRAPH_ENGINE_PORT=18080 ./scripts/start-examples.sh graph-engine
java -jar graph-engine-examples/server/target/bloge-graph-engine-server-1.0.0.jar --server.port=18080
curl -fsS http://localhost:18080/console
curl -fsS http://localhost:18080/console/operations
curl -fsS http://localhost:18080/api/v1/operations/snapshot
```

浏览器渲染检查结果：

```json
{
  "activeTab": "Operations",
  "detailsHasHealth": true,
  "health": "OK",
  "kicker": "Operations · OK",
  "metricCount": 6,
  "sectionTitles": ["Action Items", "Recent Dead Letters"],
  "title": "default / default"
}
```

### 本轮发现并修复的问题

1. **真实启动路径漏 controller wiring。** 上一轮 standalone controller test 能过，但 `GraphEngineServerAutoConfiguration` 没 import `GraphOperationsController`，导致 boot app 中 `/api/v1/operations/snapshot` 返回 404。本轮已补 import，并在 console 测试中锁住该配置。
2. **演示启动路径依赖旧本地模块。** `mvn -pl server spring-boot:run` 不会自动构建 reactor 依赖，容易拿到本地仓库旧的 `model` jar。本轮改为 boot jar + script package，降低演示不确定性。

### 本轮质量判断

这是一次产品化质量明显提升的迭代。上一轮只是把运营聚合能力放到了 API；本轮把它放到用户打开控制台后的第一屏，并且修掉了真实启动路径和演示路径里的断点。工业化不是“功能写完”，而是功能能从干净环境启动、能被人看到、能把人带到下一步处理动作。

但这仍未达到工业级完成态。原因很具体：

- Operations 首页还是只读 triage，不支持一键 retry、批量处理、SLO drill-down。
- snapshot 仍是 bounded sample，不是 metrics backend。
- action item 还没有绑定 runbook、审计确认、权限差异化。
- Resource Gateway 到 Graph Engine 的 durable 迁移路径仍主要停留在叙事和入口层，还缺迁移示例/转换器。

### 与工业级产品化目标的距离

| 维度 | 本轮前 | 本轮后 | 说明 |
| --- | --- | --- | --- |
| 运营健康入口 | 中 | 中高 | 控制台首屏可见，API 不再只藏在 curl |
| 演示可用性 | 中 | 中高 | 可执行 jar 与启动脚本降低了演示失败概率 |
| 恢复动作闭环 | 中高 | 中高 | 入口更清楚，但还不能直接复合恢复 |
| 可观测性合同 | 中 | 中 | 本轮未接 metrics/SLO |
| Resource Gateway 迁移叙事 | 中 | 中 | 本轮未补迁移例子 |
| 工业级完成度 | 约 74% | 约 79% | 仍未进入 5% 差距以内 |

### 下一轮优先级

下一轮建议做 **SLO/metrics 绑定**：

1. 将 operations snapshot 的 health/action rules 与 `GraphEngineMetricsObserver` 指标命名对齐。
2. 给出最小 SLO 合同：dead-letter backlog、failed instance count、suspended age、worker lease expiration、event journal availability。
3. 在控制台显示指标来源与 freshness，让 health 不只是 UI 判断，而是可以接告警系统的运行合同。

如果继续先做 UI，只会把当前只读入口打磨得更漂亮；真正的工业化缺口已经转向“可报警、可追责、可解释”的观测合同。
