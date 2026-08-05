# Resource Gateway Stage 2：统一任务状态与 Evidence 信任

> 状态：Implemented / E2 verified
>
> 日期：2026-08-05
>
> 范围：Author Workspace v2、Graph / Operator Contract Scenario、Evidence、Rehearsals
>
> 对应计划：UXA-S2-01 至 UXA-S2-06

## 1. 解决了什么

Stage 1 让示例成为可运行 Workspace，但同一个 Operator Scenario 仍可能出现“顶部不能运行、底部
可以运行”的矛盾；运行后的 Evidence 还可能错误引用另一个 Graph run，或在页面显示 current、
后端却因端口 Contract 校验方式错误而标记失败。

Stage 2 不增加新的运行入口，而是建立四条产品不变量：

1. 同一用户任务只有一个 canonical state 和一个 command availability；
2. Graph 与 Operator 分别拥有不可串用的 target coordinate、run response 和 Evidence；
3. 可执行、证据当前、证据可治理是三个独立判断；
4. 端口 Contract 在 fixture、运行和 terminal output 三个边界采用同一结构语义。

## 2. 用户现在能感知到的变化

### 2.1 一个任务，一个运行状态

Author 顶部任务条与 Contract Scenario 工作区不再各自推导 Run 是否可用。二者共同消费
`TaskStateProjection`：

| 投影字段 | 用户含义 |
|---|---|
| `canonicalState` | 当前处于空白、准备中、阻塞、可运行、运行中、证据当前或证据过期 |
| `primaryCommand` | 此刻最重要且允许执行的动作 |
| `blockingReasons` | 为什么不能运行 |
| `remediationActions` | 应回到 Compose、Contract 或 Scenarios 做什么 |
| `currentness` | 结果是否对应当前可见内容 |
| `proofStrength` | 结果只能探索、已经持久，还是满足治理闭环 |

禁用命令不再只有灰色状态。它同时提供稳定 reason code、面向用户的解释和直接修复动作。

### 2.2 Graph 与 Operator Evidence 不串线

每次 Scenario run 都记录：

- exact target kind：`GRAPH` 或 `OPERATOR`；
- target id、revision 和 fingerprint；
- Contract fingerprint；
- Scenario set id、revision 和 fingerprint；
- operator closure fingerprint；
- 该目标自己的完整 `SimulationResponse`。

切换 Graph / Operator Contract 时，Evidence 面板只读取当前目标的 response、comparison、coordinate
和 trust state。Graph 已通过不会让尚未运行的 Operator 显示绿色，Operator 失败也不会污染 Graph。

### 2.3 绿色执行不再冒充可发布证据

状态条把五个问题分开：

| 维度 | 回答的问题 |
|---|---|
| Draft | 资产是否已持久化 |
| Contract | 输入输出约束是否经过检查 |
| Runnable | 当前状态能否试跑 |
| Evidence | 是否运行过，结果是否仍对应当前内容 |
| Gate | 是否具备发布判定所需的完整治理信息 |

Evidence proof strength 采用三档：

| 强度 | 含义 | 可用于 |
|---|---|---|
| `EXPLORATORY` | immutable sandbox snapshot，但没有 durable target revision | 本地探索和理解行为 |
| `DURABLE` | 绑定已保存 target revision 和完整 fingerprint closure | 可回放测试和团队协作 |
| `GOVERNED` | durable、current 且治理检查通过 | 发布门禁输入 |

因此“执行通过”可以与“Gate 尚未评估”同时成立。这不是矛盾，而是对证据用途的诚实表达。

### 2.4 Rehearsal 不再混淆完成率和通过率

Rehearsals 现在分别显示：

- `Completion`：多少条已经得到终态；
- `Pass rate`：得到业务通过结论的比例；
- `Failed`：确定失败数；
- `Indeterminate`：证据不足、无法得出结论的数量；
- `Gate`：治理门禁是否允许发布。

例如 `6/6 complete`、`67% pass rate`、`Gate Blocked` 可以同时出现。用户不会再把“批次跑完了”
误解为“业务验证通过了”。

## 3. 核心工程设计

### 3.1 Canonical Task State

实现入口：

- `author/task/taskStateProjection.ts`
- `author/shell/AuthorCommandBar.tsx`
- `contract-scenario/ContractScenarioWorkspace.tsx`

`projectAuthorTaskState` 只接收领域事实，不读取 DOM，也不依赖具体按钮。所有命令表面读取同一个
projection。阻塞优先级为：空 Graph、输入无效、交互级 blocker、缺 Scenario、coordinate 未准备完成
或 Scenario stale。

`CommandAvailability` 是稳定协议：

```text
commandId
state = READY | RUNNING | BLOCKED
enabled
label
reasonCode
message
remediation { label, mode }
```

Matrix 的批量运行与单 Case Run 是不同命令，因为它们的 selection、预算和 baseline 语义不同；
但它们必须共享 exact coordinate、编译 proof 和 Evidence currentness 规则。

### 3.2 编译与 Evidence closure

Scenario compiler 现在生成并验证 source-bound proof。执行前必须确认可见 Scenario、编译产物、
run request 与 Evidence 引用同一组 fingerprint。任何一个坐标缺失或改变都 fail closed，并给出
`SCENARIO_STALE` 或 `COORDINATE_PREPARING`，不能先运行再把结果标成 stale。

在 ephemeral 模式中，系统对当前可见内容生成 immutable snapshot fingerprint。它可以建立
`CURRENT` 结论，但 proof strength 只能是 `EXPLORATORY`。

### 3.3 Operator fixture 的端口语义

真实 resource operator 常见输入 Contract 是：

```json
{
  "params": {
    "type": "object",
    "properties": { "applicantId": { "type": "string" } },
    "required": ["applicantId"]
  }
}
```

Operator Case 的 Given 应写完整端口对象：

```json
{
  "params": { "applicantId": "applicant-1001" }
}
```

生成 synthetic single-node Graph 时，系统将整个 `ctx.params` 绑定到节点 `params` 端口；它不会错误
地再追加 `targetPath=params`。无包装的单端口 Contract 仍兼容把字段映射到唯一端口；多端口 Contract
则逐端口映射。实现位于 `author/contract/operatorScenarioGraphDraft.ts`。

节点 fixture 校验使用完整端口结果 schema，而不是剥掉第一层端口后的 payload schema。这样用户
在 Dependencies 中输入 `{ "payload": ... }` 时，前端与运行时看到的是同一个结构。

### 3.4 Terminal output 的端口级校验

后端原行为会拿整个 terminal output 去匹配第一个 output port 的内部 schema，导致合法的
`{ "payload": {...} }` 被判定为 terminal Contract failure。

`VisualGraphSimulationService` 现在按端口验证：

- 零输出端口：直接通过；
- 单输出端口：优先验证 `output[portName]`，同时保留历史 unwrapped output 兼容；
- 多输出端口：逐端口验证，并要求所有 required port 存在；
- 类型或 required 字段不符合：保留准确的 `/output/<port>` 诊断路径。

这让 Operator fixture、节点执行结果和 Graph terminal output 使用同一 Contract 边界。

## 4. 最短体验路径

1. 运行 `./scripts/start-visual-canvas-demo.sh --scenario-batch`。
2. 打开 `http://localhost:8080/author/`。
3. 选择 **Load example -> Loan policy fallback**。
4. 双击 **Fetch applicant**，进入 **Contract -> Open Contract Workspace**。
5. 运行默认 Operator Scenario。
6. 在 Review result / Evidence 中确认 Execution、Assertions 和 Contract 均为 `PASSED`，Evidence 为
   `CURRENT`，proof 为 `EXPLORATORY`，Gate 为 `NOT EVALUATED`。
7. 返回 Graph Scenario 再运行，切换 Graph / Operator Evidence，确认两份结果互不替换。
8. 打开 `http://localhost:8080/rehearsals/`，选择 **Grounding policy regression**，对照 Completion、
   Pass rate、Indeterminate 和 Gate 四类信息。

停止服务：`./scripts/stop-visual-canvas-demo.sh`。

## 5. 验证证据

### 5.1 自动化

| 范围 | 验证 |
|---|---|
| Task projection | 状态、blocker、remediation、proof strength、currentness |
| Command bar | primary command、阻塞原因和修复动作一致 |
| Scenario compiler | exact coordinate 与 compilation proof，漂移 fail closed |
| Operator draft adapter | wrapped、unwrapped、multi-port 三类输入绑定 |
| Contract workspace | 把确切 response 回传给当前目标 Evidence |
| Author integration | wrapped-port Operator Scenario 真实请求、运行结果和 Evidence 隔离 |
| Rehearsal projection | completion、pass、failed、indeterminate、gate 分离 |
| Backend simulation | wrapped single-output port 按端口校验，错误类型仍拒绝 |

前端回归：67 files / 516 tests 全绿，production build 通过。Resource Gateway 后端
`mvn -q clean verify` 全量门禁通过：5,898 tests、0 failures、0 errors、13 skipped。

### 5.2 真实浏览器

使用完整 Spring Boot jar 与 in-app Chromium 验证：

- Loan policy / Fetch applicant / Operator Contract Scenario 可完整通过；
- terminal output 显示真实 wrapped `payload`，不是测试替身拼出的 UI 状态；
- 当前 Operator Evidence 不再显示 Graph response，也不再出现 `No Scenario run yet`；
- Rehearsal 示例明确显示 `100% completion`、`67% pass rate` 和 `Blocked gate`；
- `390 x 844` 复杂 Graph 页面 `scrollWidth == innerWidth == 390`，无控件重叠；
- 浏览器 console 无错误。

## 6. 保留边界与下一步

Stage 2 解决“状态和证据能不能信”，没有解决“1000 条 Case 能不能高效读、找、改”。当前 Matrix
仍把过多协议列平铺在默认视图中，这是剩余唯一 P0。Stage 3 必须以用户判断路径重做 Matrix：
默认只保留 Case、类型、结果、耗时、当前性和数据摘要，把 Expected / Actual / Diff 与协议坐标放进
详情，并补齐 result-first filtering、统一 Case Editor 和大数据虚拟化。

不得用压小字号或继续增加横向滚动来完成 Stage 3；那会重新制造已识别的视觉和任务债务。
