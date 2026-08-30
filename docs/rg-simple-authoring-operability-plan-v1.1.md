# Resource Gateway 简化创作可操作性方案 v1.1

状态：Approved for implementation；J2 已完成，J3 待实施。

日期：2026-08-30。

范围：外部 API 接入、API 资源 Fixture、模拟运行、多 API DAG、可复用工具/方案、工具/方案 Fixture。

本文不替代 [`rg-api-fixture-reusable-flow-authoring-proposal-v1.md`](rg-api-fixture-reusable-flow-authoring-proposal-v1.md)。它是该方案的 v1.1 执行版：保留已冻结的 wire schema，压缩普通作者要看到的对象、页面、动作和术语。

## 1. 当前基线

现有 v1 方案已建立十个 wire schema family。实施台账显示：

| 能力组 | 已证明状态 |
| --- | ---: |
| Wire Schema family | 10 / 10 |
| API Resource 后端权威、复合保存与投影闭包 | 15 / 18 |
| 文档与聚焦门禁 | 5 / 7 |
| 其余运行与 UI 能力 | 0 / 65 |
| 当前完成度 | 30% |

`ApiResourceCommitStore` 已完成 claim、committed read、stage、commit、fail 与 opt-in production wiring 的
聚焦证据；`AuthoringFacade`、新对象页和模拟运行还没有验收。因此 v1.1 的重点不是重开领域模型，而是
在已完成 J2 的持久化边界上继续固化用户主线。

共享工作树中当前存在的 `GraphNodeFixtureControls.tsx` 未提交修改，不属于本方案实施，必须保持隔离。

## 2. 目标状态

普通作者只需要理解四个对象：

| 对象 | 用户动作 | 后端隐藏内容 |
| --- | --- | --- |
| Connection | 填 Base URL、认证和默认 Header | Secret lease、Header 安全策略、网络检查 |
| API 资源 | 定义 method、path、输入、成功判断、输出和样例 | `ApiResourceSpec`、Descriptor、Design Contract、Operator 投影 |
| 工具 / 方案 | 编排 API 资源或已发布流程成 DAG | GraphDraft、DSL lowering、Publication、Flow Version |
| Fixture 集 | 保存输入、依赖行为和期望结果，然后运行 | NodeFixture、Scenario dependency、受控执行、Evidence |

三条标准用户流程是：

1. **接入 API**：新增或选择 Connection，定义一个 API 操作，保存后得到精确 API Resource revision。
2. **设置 Fixture 并模拟**：从样例或表单生成私有 Fixture Case，选择 Case 后运行，查看 output 和每个节点的 REAL/MOCKED 证据。
3. **组合 DAG**：把多个 API 资源或已发布流程连成数据流，保存为 Tool Draft 或 Solution Draft；可整体设置 Fixture、模拟，通过后发布为不可变 Flow Version。

非目标：第一阶段不实现人工任务、长事务、补偿流程、任意表达式编排，也不把共享 Fixture 审核放回普通作者页。

## 3. 核心原则

1. **后端权威，前端只是编辑器。** `ApiResourceSpec`、`ReusableFlowDraft`、`FixtureSet` 和 `SimulationRun` 是服务端对象。前端 Editor Model 只描述未提交表单，不得另存领域身份或投影。
2. **先保存，再模拟。** 模拟只接受 Exact Subject Ref 或精确 Fixture Case。前端不再提交可能过期的整份 GraphDraft。
3. **私有调试和团队治理分离。** 私有 Fixture 可直接保存和模拟；共享 Fixture 独立进入 review queue。模拟结果不得把 `governance: NOT_CHECKED` 改写成「可发布」。
4. **第一版只做数据流 DAG。** 标准模式支持节点、依赖、输入映射和输出；route、dependency、expression 继续留在 Advanced/Legacy。
5. **每个能力都有契约测试。** 新增 UI 前必须先有服务接口、投影、CAS、权限和失败路径的回归。

## 4. 关键 Schema

以下 TypeScript 只表达契约形状，实现仍以 [`docs/schemas/resource-gateway-authoring/`](schemas/resource-gateway-authoring/) 的版本化 JSON Schema 和评审后的文档为准。

### 4.1 Scope 与精确引用

```ts
type Scope = {
  tenantId: string;
  projectId: string;
  environment: 'LOCAL' | 'DEV' | 'STAGING' | 'PRODUCTION';
};

type ExactSubjectRef =
  | { kind: 'API_RESOURCE'; resourceId: string; revision: 1 | number; fingerprint: `sha256:${string}` }
  | { kind: 'FLOW_DRAFT'; draftId: string; revision: 1 | number; fingerprint: `sha256:${string}` }
  | { kind: 'FLOW_VERSION'; publicationId: string; revision: 1 | number; fingerprint: `sha256:${string}` };

type ComposableRef =
  | Extract<ExactSubjectRef, { kind: 'API_RESOURCE' }>
  | Extract<ExactSubjectRef, { kind: 'FLOW_VERSION' }>;
```

规则：

- Scope 由认证上下文派生，客户端不提交。
- `revision` 最小值是 1，`fingerprint` 必须是服务端生成的 `sha256:` 值。
- `FLOW_DRAFT` 可以被 Fixture 或模拟引用，但不能被另一个 Flow 组合。
- 可组合对象只有 API Resource 和不可变 Flow Version。

### 4.2 Connection

```ts
type ConnectionCommand = {
  schemaVersion: 'bloge.connection.command.v1';
  displayName: string;
  baseUrl: string;
  auth:
    | { kind: 'NONE' }
    | { kind: 'BEARER'; secret: SecretWrite }
    | { kind: 'BASIC'; username: string; password: SecretWrite }
    | { kind: 'API_KEY'; headerName: string; secret: SecretWrite };
  defaults?: {
    headers?: Array<{ name: string; value?: string; secretRef?: string }>;
    query?: Record<string, string>;
    timeout?: number;
  };
};
```

规则：

- `Authorization`、`Cookie`、`Set-Cookie`、`Host`、`Content-Length`、`Transfer-Encoding`、`Forwarded`、`X-Forwarded-*` 等保留名称不能由 defaults 或 API Key Header 占用。
- Secret 只能写入 Secret Store；Connection View、Resource View、Fixture、Simulation input 和诊断都不得返回 credential。
- `PUT /api/authoring/connections/{connectionId}:check` 是显式网络检查动作，不在资源保存中隐式调用。

### 4.3 API 资源

```ts
type ApiResourceCommand = {
  schemaVersion: 'bloge.apiResource.command.v1';
  connection:
    | { mode: 'EXISTING'; connectionId: string }
    | { mode: 'CREATE'; connection: ConnectionCommand };
  resource: ApiResourceInput;
  defaultFixture?: FixtureSetInput;
};

type ApiResourceInput = {
  resourceId?: string;
  displayName: string;
  operation: {
    method: 'GET' | 'POST' | 'PUT' | 'DELETE';
    pathTemplate: string;
  };
  inputs: Array<{
    name: string;
    location: 'PATH' | 'QUERY' | 'HEADER' | 'BODY';
    type: 'string' | 'integer' | 'number' | 'boolean' | 'object';
    required: boolean;
    example?: unknown;
  }>;
  success:
    | { mode: 'HTTP_2XX' }
    | { mode: 'HTTP_CODES'; codes: Array<200 | 201 | 202 | 204 | number> }
    | { mode: 'BODY_MATCH'; path: string; values: Array<string | number | boolean> };
  output:
    | { mode: 'INFER_FROM_EXAMPLES'; outputPath?: string }
    | { mode: 'STRUCTURED'; properties: Array<{ name: string; type: string; required: boolean }> }
    | { mode: 'JSON_SCHEMA'; schema: SchemaEnvelope };
  examples: Array<{ name: string; input: unknown; output: unknown }>;
  effect:
    | { kind: 'READ_ONLY' }
    | { kind: 'FIXTURE_ONLY_WRITE' }
    | {
        kind: 'MANAGED_WRITE';
        idempotencyHeader: string;
        receipt: { idPath: string; statusPath: string; succeededValues: string[]; failedValues: string[] };
        reconciliation?: { resource: ExactSubjectRef; receiptIdInputPath: string };
      };
};
```

约束：

- `ApiResourceSpec` 是唯一创作权威。保存成功后由服务端生成 Descriptor、Design Contract、Operator 投影和 fingerprint。
- `GET` 默认 `READ_ONLY`；写方法默认 `FIXTURE_ONLY_WRITE`。真实写调用必须显式进入 `MANAGED_WRITE`，并具备幂等 Header、Receipt 和可选 reconciliation。
- 复合命令只允许一个 CAS：`If-Match` 保护 URL 中的 Resource；`CREATE` Connection 必须不存在；`defaultFixture` 从新 Resource revision 派生新身份。
- Resource View 返回投影状态和 metadata-only Fixture summary，不嵌入 fixture material。

### 4.4 工具与方案

Tool 和 Solution 第一阶段共用运行模型，只在展示与发布分类上区分。

```ts
type ReusableFlowCommand = {
  schemaVersion: 'bloge.reusableFlow.command.v1';
  flow: {
    flowId?: string;
    displayName: string;
    kind: 'TOOL' | 'SOLUTION';
    description?: string;
    contract: FlowContract;
    graph: DataFlowGraph;
    layout: CanvasLayout;
    status: 'DRAFT';
  };
};

type FlowContract = {
  inputSchema: SchemaEnvelope;
  outputSchema: SchemaEnvelope;
};

type DataFlowGraph = {
  nodes: Array<{
    nodeId: string;
    ref: ComposableRef;
    inputBindings: Record<string, InputBinding>;
    output?: OutputProjection;
  }>;
  edges: Array<{ from: FlowPort; to: FlowPort }>;
};

type InputBinding =
  | { kind: 'FLOW_INPUT'; path: string }
  | { kind: 'NODE_OUTPUT'; nodeId: string; path: string }
  | { kind: 'CONSTANT'; value: unknown };
```

约束：

- 标准模式只允许 acyclic data flow；保存时服务端检测重复 `nodeId`、未知 port、类型不兼容和环。
- `FLOW_DRAFT` 不能作为节点依赖；Flow 复用 Flow 必须先 publish 并引用 `FLOW_VERSION`。
- 发布输入的 `source` 必须指向当前 `FLOW_DRAFT` revision 和 fingerprint，产物是新的不可变 `FLOW_VERSION`。
- Draft 修改不修改旧版本；引用旧行为的 Fixture 继续绑定旧 revision。

### 4.5 Fixture 集

```ts
type FixtureSetCommand = {
  schemaVersion: 'bloge.fixtureSet.command.v1';
  displayName: string;
  subject: ExactSubjectRef;
  cases: Array<FixtureCaseInput>;
};

type FixtureCaseInput = {
  caseId?: string;
  displayName: string;
  input: unknown;
  controls?: Array<FixtureControl>;
  expected?: unknown;
};

type FixtureControl =
  | { target: ExactSubjectRef; behavior: { kind: 'RETURN'; output: unknown } }
  | { target: ExactSubjectRef; behavior: { kind: 'APPLY_CASE'; case: unknown } }
  | { target: ExactSubjectRef; behavior: { kind: 'REAL' } };
```

约束：

- Fixture Set 的内容 revision 绑定精确 subject revision 和 fingerprint；subject 升级后旧 Fixture 标记 `STALE`，但不改写内容。
- Fixture status 使用独立的 `statusRevision`，由私有/共享生命周期驱动，不影响内容 CAS。
- Fixture 不得携带 credential、Connection secret、Authorization Header 或业务敏感 material 的明文副本；真实外部读取权限由运行策略和服务端授权决定。
- `REAL` 只表示「不覆盖这个依赖」，不等于允许网络调用。

### 4.6 模拟请求与结果

```ts
type SimulationRequest = {
  schemaVersion: 'bloge.simulationRequest.v1';
  source:
    | {
        kind: 'FIXTURE_CASE';
        fixtureSetId: string;
        revision: number;
        caseId: string;
      }
    | {
        kind: 'AD_HOC';
        subject: ExactSubjectRef;
        input: unknown;
      };
  executionPolicy?: {
    externalReads:
      | { kind: 'DENY' }
      | { kind: 'ALLOW_EXACT'; resources: Array<ApiResourceRef>; justification: string };
    externalWrites: { kind: 'DENY' };
  };
};

type SimulationRun = {
  schemaVersion: 'bloge.simulationRun.v1';
  runId: string;
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'BLOCKED';
  subject: ExactSubjectRef;
  fixtureCase?: { fixtureSetId: string; revision: number; caseId: string };
  output?: unknown;
  nodes: Array<{
    nodeId: string;
    status: 'COMPLETED' | 'FAILED' | 'SKIPPED' | 'BLOCKED';
    execution: 'REAL' | 'MOCKED' | 'NOT_EXECUTED';
    fixtureSource: 'FIXTURE_SET' | 'INLINE' | 'NONE';
    fidelity?: 'OUTPUT_LEVEL' | 'PROTOCOL_DERIVED' | 'TRANSPORT_LEVEL';
    egress: EgressEvidence;
  }>;
  verdicts: {
    execution: 'PASSED_REAL' | 'PASSED_WITH_MOCKS' | 'SIMULATED_ONLY' | 'FAILED' | 'BLOCKED';
    contract: 'PASSED' | 'FAILED' | 'NOT_CHECKED';
    assertions: 'PASSED' | 'FAILED' | 'NOT_CHECKED';
    governance: 'PASSED' | 'FAILED' | 'NOT_CHECKED';
  };
  diagnostics: Array<{ code: string; message: string }>;
  startedAt: string;
  endedAt?: string;
};
```

约束：

- `FIXTURE_CASE` 的 input、controls 和 expected 全部由服务端从精确 Fixture revision 读取；客户端不能重复提交。
- 省略 `executionPolicy` 等价于外部读写全部拒绝。标准 UI 不显示该字段。
- 写操作永远不允许真实执行；`MANAGED_WRITE` 在模拟中只能产生 receipt/reconciliation 语义或被 fixture 覆盖。
- 结果必须保留 REAL/MOCKED、fixture 来源、fidelity 和 egress 证据。整体被 RETURN 或 APPLY_CASE 替代时，只能判定 `SIMULATED_ONLY`。

## 5. 最小 HTTP 面

沿用 v1 定义的接口，不新增第二套协议：

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| `GET` / `PUT` / `POST :check` | `/api/authoring/connections...` | 管理连接、credential 和显式连通性检查 |
| `GET` / `PUT` | `/api/authoring/resources/{resourceId}` | 复合保存与读取 API Resource |
| `POST` | `/api/authoring/resources:preview-openapi` | OpenAPI 发现预览，不保存 |
| `GET` / `PUT` / `POST :publish` | `/api/authoring/flows/{flowId}...` | 保存、读取和发布 Tool/Solution |
| `GET` / `PUT` / `POST :share` | `/api/authoring/fixture-sets/{fixtureSetId}...` | 保存、读取和发起共享 |
| `POST` / `GET` | `/api/authoring/simulations...` | 提交与读取模拟 |

统一并发与幂等规则：

1. 创建用 `If-None-Match: *`；更新用服务端返回的强 `ETag` 作为 `If-Match`。缺失返回 428，失配返回 412。
2. 所有保存和有副作用的 action POST 携带 `Idempotency-Key`；同 key 同 payload 重放返回原 Receipt，不同 payload 返回 409。
3. 错误统一使用 payload-free Problem Detail，包含 `code`、`fieldErrors`、`correlationId` 和 `recoveryActions`。

## 6. 前端信息架构

默认入口只保留四个对象工作台，不把六阶段 Spine 继续暴露给普通作者：

1. **Connections**：连接列表、认证状态、显式 Check。
2. **API Resources**：API 操作设计、样例、Default Fixture、模拟结果。
3. **Flows**：Tool/Solution DAG、依赖目录、节点/整体 Fixture、模拟和发布。
4. **Fixture Sets**：按对象查询私有/共享 Fixture，独立入口查看状态。

三个标准页面动作：

| 页面 | 主线动作 |
| --- | --- |
| API Resource | `Save resource` -> `Save fixture` -> `Run simulation` |
| Flow | `Add node` -> `Connect data` -> `Save flow` -> `Save fixture` -> `Run simulation` -> `Publish version` |
| Fixture Set | `Create from examples` -> `Save private fixture` -> `Run`；共享走 `Share with team` |

高级概念的处理方式：

- 默认隐藏 `ctx`、payloadPath、ResponseProtocol、GraphDraft、NodeFixture。
- 输入映射默认按同名字段生成；只在「高级映射」中暴露嵌套路径。
- Fixture 共享、review、approve、activate 移到 Review Queue。
- OpenAPI 导入只作为生成表单的入口，保存仍然经过同一个 `ApiResourceSaveCommand`。

## 7. Schema 到现有实现投影

新对象不是平行系统，而是服务端权威模型到现有运行对象的受控投影：

| 新权威 | 服务端投影 |
| --- | --- |
| `Connection` | Secret lease + HttpConnection 配置 + auth adapter |
| `ApiResourceSpec` | ResourceDescriptor + ResourceDesignContract + virtual Operator + Catalog entry |
| `ReusableFlowDraft` | GraphDraft + GraphContract + Publication candidate |
| `ReusableFlowVersion` | 不可变 VisualGraphPublication + Catalog entry |
| `FixtureSet` | NodeFixture / Scenario dependency / metadata-only Fixture Asset 引用 |
| `SimulationRequest` | Compiled GraphDraft run + fixture bindings + execution policy |
| `SimulationRun` | Trace、node fidelity、controlled execution evidence、verdict projection |

投影必须同步提交，不能出现 Resource 保存成功但 Operator 或 Contract 缺失的中间可见状态。任一必需投影失败时，新 revision 不进入 ACTIVE/PUBLISHED，也不返回成功 Receipt。

## 8. 状态模型

### API Resource

```text
NEW -> STAGED -> COMMITTED/ACTIVE
      \-> FAILED / CONFLICT / EXPIRED
```

用户可见为 `Draft`、`Saved`、`Projection failed`、`Needs repair`。保存响应必须包含新 revision、fingerprint、三投影 generation 和 metadata-only fixture summary。

### Reusable Flow

```text
DRAFT -> VALIDATION_PASSED -> PUBLISHED_VERSION
DRAFT -> VALIDATION_FAILED
```

Draft 可反复保存；Publish 只产生不可变 version。发布成功不自动把旧 Draft 标记完成。

### Fixture Set

```text
PRIVATE_DRAFT -> SHARING_PENDING -> TEAM_AVAILABLE
TEAM_AVAILABLE -> STALE | REVOKED
```

内容 revision 与 `statusRevision` 分开。Private 可以直接运行；Team fixture 的运行权限由查询和授权策略控制。

### Simulation Run

```text
RUNNING -> SUCCEEDED | FAILED | BLOCKED
```

Run 不可变。前端只能按 runId 读取，不能在结果页修改 verdict。

## 9. 实施切片

### Slice J2：完成 JDBC stage/commit/fail（已完成）

目标：让现有 `ApiResourceCommitStore` contract 在 JDBC adapter 上达到与内存参考一致。

工作项：

1. 实现不可见 staging、原子 commit、fenced fail 和过期 lease takeover。
2. 保证权威 Spec 与三投影在同一个事务和 generation 闭合。
3. 补双连接竞争、崩溃恢复、CAS 失配、projection failure 和 journal replay 测试。
4. 完成 production readiness wiring，但暂不暴露 HTTP。

验收记录：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ApiResourceAuthoringSchemaReadinessTest,ApiResourceAuthoringRuntimeConfigurationTest,JdbcApiResourceCommitStoreMutationTest,JdbcApiResourceCommitStoreClaimTest,ApiResourceCommitStoreContractTest test
Tests run: 66, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

完成判据已满足：V002 `command_id` 闭包允许并发 staging；真实双连接竞争只有一个 winner；DB 时间 lease、
staged invisibility、原子 commit、fenced fail、restart/history replay 和 tamper fail-closed 均有回归。生产
配置默认关闭，缺 migration/compiler/readiness 时启用即 fail startup；真实 PostgreSQL、full clean verify
仍未完成。

### Slice J3：AuthoringFacade 与复合保存

目标：暴露第一个可用 API Resource 保存接口。

工作项：

1. 实现 `ApiResourceModule.save` 的领域校验、Connection/Resource/Default Fixture 复合命令。
2. 实现 Secret write-only lease、Header 安全策略、OpenAPI preview 生成。
3. 实现 `ETag`、`If-Match`、`If-None-Match`、`Idempotency-Key` 和 payload-free Problem Detail。
4. 控制器只做传输转换，不做业务决策。

验收：

- 正常保存返回 Resource + Connection + 三投影 + Default Fixture summary。
- Secret、credential、请求 body 和业务 payload 不出现在错误、日志、Run 或 View。
- 幂等重放、CAS 失配、非法 Header、非法 effect 和投影失败全部有服务测试。

### Slice U1：API Resource 标准页

目标：完成第一条用户纵向路径。

工作项：

1. 新对象页只展示 operation、inputs、success、output、examples、effect 和 Default Fixture。
2. 保存后从响应重建视图，不使用本地缓存充当权威。
3. Default Fixture 从 examples 生成，可编辑后单独保存。
4. Simulation 面板选择 fixture case 或 ad-hoc input，并显示 REAL/MOCKED、output、verdicts 和 diagnostics。

验收：

- 浏览器验收从「新增 API」到「无外部调用的模拟成功」只经过保存、设置 fixture、运行三个用户动作。
- 刷新后 Resource、Fixture 和最新 Run 恢复。
- Mocked 运行显示 `PASSED_WITH_MOCKS` 与 `governance: NOT_CHECKED`，不得显示「全部通过」。

### Slice F1：Flow DAG 与版本

目标：把多 API 组合成 Tool/Solution。

工作项：

1. 实现统一 Catalog：只返回 API Resource 和 Flow Version。
2. 实现数据流节点、输入绑定、acyclic 校验和类型检查。
3. 保存为 `ReusableFlowDraft`，通过现有 GraphDraft lowering 执行。
4. 模拟通过后发布为不可变 Flow Version，并出现在另一个 Flow 的目录中。

验收：

- 至少三个 API Resource 组成 DAG，能保存、模拟、发布和复用。
- 环、未知依赖、不可组合 Flow Draft、scope mismatch 和 CAS 失配全部被拒绝。
- 发布版本可被旧 Fixture 继续引用，Draft 修改不影响旧版本。

### Slice G1：团队 Fixture 与治理分离

目标：让共享数据拥有独立治理，而不阻塞私有调试。

工作项：

1. 实现 Fixture share command 和 `statusRevision`。
2. 普通页只显示共享状态，不显示 approve/activate。
3. Review Queue 使用现有四眼审核、CAS 和 fixture catalog。

验收：

- 私有 fixture 不触发治理；共享 pending 不等于可用；approve 后可被团队对象查询到。
- reviewer != owner、材料闭包、scope closure 和 stale/revoked 均有负向测试。

### Slice M1：迁移与入口切换

目标：保留现有能力，不产生双权威。

工作项：

1. Descriptor + Contract 迁移为 `ApiResourceSpec`，孤儿对象标记 `NEEDS_REPAIR`。
2. GraphDraft 迁移为 `ReusableFlowDraft`；高级 edge 进入 Legacy 模式。
3. GraphDraft NodeFixture 迁移为单 Case Fixture Set。
4. 旧 `/author/?spine=v1` 保留只读深链，新对象页成为默认入口。

验收：迁移覆盖率、失败对象列表、回放和真实用户任务验收均有记录。

## 10. 测试与验收矩阵

| 层级 | 最小测试 |
| --- | --- |
| JSON Schema | required、additionalProperties、enum、fingerprint、revision、互斥 source、credential 拒绝 |
| 领域服务 | 复合保存、投影闭包、CAS、幂等、scope、类型检查、DAG 环 |
| JDBC | claim、stage、commit、fail、takeover、跨实例竞争、crash recovery |
| Controller | ETag/If-Match、412/428、Idempotency-Key、Problem Detail、secret-free diagnostics |
| 前端组件 | Editor Model -> command 映射、保存后重建、错误恢复、只读投影 |
| 浏览器 | API Resource 首路径、Flow DAG 首路径、Fixture 共享、失败边界 |

每个切片的 Definition of Done：

1. 生产或测试代码有 JavaDoc / 前端契约注释。
2. 新增负向测试，不只验证 happy path。
3. 聚焦测试全绿；触碰 controller/operator/frontend 时再运行对应完整门禁。
4. 更新实施台账和最近文档，记录命令、commit、测试计数和残余风险。
5. 只提交本切片拥有文件；共享工作树中的外部未提交修改保持隔离。

## 11. 需要评审确认

| 决策 | v1.1 建议 |
| --- | --- |
| R1：`ApiResourceSpec` 是否唯一权威 | 接受；否则无法消除 Descriptor/Contract 双写 |
| R2：Tool 与 Solution 是否第一阶段共用运行模型 | 接受；若 Solution 必须先支持人工任务/长事务，则重新切片 |
| R3：私有 Fixture 零治理、共享 Fixture 独立审核 | 接受 |
| R4：先保存再模拟，用 Exact Subject 替代 transient GraphDraft | 接受 |
| R5：第一阶段标准模式只支持数据流 DAG | 接受；复杂语义留在 Advanced/Legacy |

J2 已按批准方案完成。下一步进入 Slice J3：实现 `AuthoringFacade` 与 Connection/Secret/Default Fixture
复合保存，再进入 U1 API Resource 标准页；J2 的 H2 聚焦证据不替代真实 PostgreSQL 与 HTTP/UI 验收。
