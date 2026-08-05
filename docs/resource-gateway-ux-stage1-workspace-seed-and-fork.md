# Resource Gateway UX Stage 1：可运行 Workspace Seed 与原子 Fork

> 状态：已实现（2026-08-05）  
> 对应：`UXA-S1-01`、`UXA-S1-02`、`UXA-S1-04`、`UXA-S1-05`、`UXA-S1-06`  
> 后续依赖：`UXA-S1-03` 的 Evidence currentness 语义在 Stage 2 统一收口

## 1. 这次解决什么问题

此前“加载完整示例”实际只把 Graph 模板放进浏览器。用户首次保存时，系统先单独创建 Graph，
Graph 获得新 UUID 和 revision 后，尚未同步保存的 Scenario 仍引用旧坐标，因此立即进入 stale / rebase。

现在完整示例被视为一个 `WorkspaceSeedBundle.v1`：

```text
GraphDraft
+ primary ScenarioDraftSet
+ inline Graph / Scenario fixtures
+ runtime and mock profile
+ capability and proof-strength preview
```

首次保存调用一个聚合命令，服务端在同一事务内完成 Graph 持久化、Contract 投影、Scenario
坐标重绑、Scenario baseline 建立和 receipt 写入。成功响应返回的所有坐标天然 current，不再要求
用户在第一次体验中理解 compatibility 或 rebase。

## 2. 用户怎么体验

1. 使用 `scripts/visual-canvas-demo.sh start --profile test` 启动演示。
2. 打开 Author Workspace v2，点击 `导入` -> `加载示例`。
3. 在示例卡片中先查看 Graph/Contract 规模、可运行 Case 数、Case 类型、mock 算子数和证据强度。
4. 选择“Loan policy fallback”。Matrix 会得到 `GOLDEN`、`NEGATIVE`、`BOUNDARY` 三条业务用例。
5. 不保存时可以在沙盒中试跑；这类结果明确属于 `EXPLORATORY`，不能冒充治理证据。
6. 第一次点击保存时，前端调用 Workspace Fork；成功后 Graph 与 Scenario 都是 revision 1 且保持
   `current`，无需 rebase。
7. 后续修改并保存既有 Graph 时仍使用正常 revision 更新；只有真实 Contract 漂移才进入兼容性流程。

三个 Graph 示例都包含可读的业务 fixture：

| 示例 | Golden | Negative | Boundary |
|---|---|---|---|
| Loan policy | Prime approval | Policy decline | Score 680 manual review |
| Order fulfillment | Multi-order fast lane | Single-order standard lane | 2 orders / 2-day SLA |
| Personalized dashboard | High-value dashboard | Fallback defaults | Zero balance / zero unread |

Library Workbench 继续提供 Customer Support、Order Fulfillment、Risk Policy 三个完整算子库示例；
每个示例都包含强类型 operator、built-in function 和 test fixture refs。

## 3. HTTP 协议

### 3.1 请求

```http
POST /api/authoring/workspace-forks
Authorization: Bearer <test-or-staging-workload-token>
X-Purpose: TEST_SUITE_WRITE
Idempotency-Key: canvas:loan-policy-fallback:<request-id>
Content-Type: application/json
```

请求体为 `WorkspaceForkCommand.v1`。权威 JSON Schema：

- `docs/schemas/bloge-workspace-fork-command-v1.schema.json`
- `docs/schemas/bloge-workspace-seed-bundle-v1.schema.json`
- `docs/schemas/bloge-workspace-fork-receipt-v1.schema.json`

同一个 enterprise scope 内，相同 `Idempotency-Key` 与相同请求返回原 receipt，并设置
`replayed=true`；同一个 key 携带不同请求会被拒绝，避免静默复用错误资产。

### 3.2 Receipt

Receipt 不复制 Graph、Scenario 或业务 payload，只返回可回读的闭包坐标：

```text
workspaceId
graphCoordinate            draftId + revision + fingerprint
contractCoordinate         exact target + contract fingerprint
scenarioSuiteCoordinates   id + revision + fingerprint
fixtureCoordinates         inline fixture inventory
sourceTemplateFingerprint
forkedWorkspaceFingerprint
runtimeProfile
proofStrength
warnings
replayed
```

前端随后按 receipt 回读 Graph 和 Scenario，禁止用保存前的浏览器对象猜测服务端 fingerprint。

## 4. 原子性和失败语义

`WorkspaceForkService` 使用 Spring 事务覆盖 Graph、Scenario、Contract baseline 和 receipt 写入。
执行顺序是：

1. 校验身份、profile、协议版本、幂等键和 seed closure；
2. 对 Graph 补齐当前 operator snapshot 并执行严格 Graph validation；
3. 保存 Graph，使用已保存 revision 计算 canonical graph fingerprint；
4. 投影 exact Contract；
5. 把 Scenario scope、target 和 contract fingerprint 重绑到权威坐标；
6. 通过既有 Scenario authoring service 校验并保存；
7. 最后写入 payload-free receipt。

任一步失败都抛出错误并回滚。轻量级非 Spring host 还会主动删除已创建的可见 Graph；因此调用方
不会看到“Graph 已保存但 Scenario 丢失”的半成品 Workspace。

v1 故意只允许一个 primary Scenario suite。多套件不是简单放宽 `maxItems`：它需要套件删除补偿、
分区锁和跨资产恢复日志。在这些机制实现前拒绝请求，比宣称虚假的原子性更可靠。

## 5. 安全与部署约束

- 端点只在 `test` 或 `staging` Spring profile 暴露，并拒绝 production identity environment；
- scope 来自认证 workload identity，不信任 seed 自带的 tenant / environment；
- Scenario classification 继续使用既有 clearance gate；
- Graph 与 Scenario 都执行 raw-secret guard；fixture 只保存 secret ref，不保存凭据；
- JDBC receipt 以 tenant、organization、project、environment、region、idempotency key 联合隔离；
- 单实例用同步 single-writer 防重复创建；多副本部署必须把命令路由到 Graph authoring 的同一
  single-writer partition。分布式 claim/lease 将作为工业化部署增强继续实施。

## 6. 测试覆盖

后端：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=WorkspaceForkServiceTest,WorkspaceForkProtocolSchemaTest test
```

覆盖原子重绑、幂等重试、失败清理、三类用例和 Schema/Java record 字段一致性。

前端：

```bash
cd resource-gateway-examples/src/main/frontend
npm test -- --run src/canvasExamples.test.ts \
  src/author/workspace/workspaceSeed.test.ts src/api.test.ts
npm run build
```

覆盖完整 seed closure、认证与幂等请求头、三个示例的 golden/negative/boundary 数据以及生产构建。

Stage 1 最终回归基线（2026-08-05）：

| 验证层 | 命令 | 结果 |
|---|---|---|
| 后端完整回归 | `mvn -f resource-gateway-examples/pom.xml clean verify` | 5,897 tests，0 failures，0 errors |
| 前端完整回归 | `npm test -- --run` | 64 files，498 tests 全绿 |
| 前端生产构建 | `npm run build` | 通过；仅保留既有的大 chunk 提示 |

完整后端套件首次运行曾在高并发测试负载下出现一次调用饱和波动；对应测试隔离重跑通过，完整
套件再次执行也全绿。事务内 Graph 读取的旧断言则被修正为标准语义：当前事务可以读取自己的写入，
回滚后数据库与提交缓存均不可见。这个断言同时保护原子 Fork 与缓存提交边界。

### 6.1 真实浏览器验收

2026-08-05 使用打包后的 Spring Boot 服务、文件型 H2 和真实浏览器完成以下验收，而非只依赖
组件测试：

| 路径 | 结果 | 可见证据 |
|---|---|---|
| 中文开始页 -> 示例能力预览 | 通过 | Loan 示例显示 5 节点、12 连线、1 入/7 出、3 类可运行 Case、3 个 mock 和 `EXPLORATORY` |
| 加载 Loan 示例 | 通过 | 画布实际渲染 5 节点、12 连线，无页面级横向溢出 |
| 首次原子保存 | 通过 | Graph 从 r0 到 r1、Contract 同步到 revision 1、Scenario 立即可加载/运行，无 stale/rebase |
| 故障后同幂等键重试 | 通过 | 第一次失败未留下 receipt；修复后原请求重试成功，没有重复可见资产 |
| 运行完整 Scenario suite | 通过 | Golden、Negative、Boundary 均为 `SUCCESS / PASSED / CURRENT / MOCK`，失败数 0 |
| 390 x 844 窄屏 | 通过 Stage 1 基线 | 四个任务模式与主操作仍可达，页面无横向溢出；信息层级问题进入 Stage 3/5 |

浏览器验收首次发现数据库版 Graph repository 在事务内违反 read-your-writes：`save()` 将缓存更新
延迟到 after-commit，而同一事务内的 Scenario currentness 校验只通过 `find()` 读取缓存，导致真实
首次保存返回 500。修复后，`find()` 在活动事务中读取数据库当前行，事务外继续使用提交后缓存；
因此既保留缓存性能与 stale 拒绝语义，也保证聚合命令能读取自己的写入。对应回归测试使用 H2、
`DataSourceTransactionManager` 和真实数据库仓库执行，不再由 in-memory repository 掩盖问题。

## 7. Stage 1 剩余边界

“未保存即可运行”已经具备模拟执行基础，但 `EXPLORATORY` Evidence 的 fingerprint closure、顶部与
底部 Run 的统一门禁仍属于 Stage 2。Stage 1 不通过临时放开按钮伪造完成度；下一阶段会以统一
`TaskStateProjection` 和 `CommandAvailability` 根治入口状态分裂。

桌面 Matrix 的原始断言列过长、窄屏 Case 语义被压缩、持久化 UUID 抢占业务标题层级，是本次
浏览器视觉验收确认的剩余硬伤，分别进入 Stage 3 和 Stage 5，不在原子性阶段混入表格重构。
