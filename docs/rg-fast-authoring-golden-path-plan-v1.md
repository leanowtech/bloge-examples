# Resource Gateway Fast Authoring Golden Path 技术方案 v1

状态：Proposed，等待审批。

日期：2026-08-30。

## 1. 结论

本方案不是第三套领域模型，而是对
[`rg-api-fixture-reusable-flow-authoring-plan-v2.md`](rg-api-fixture-reusable-flow-authoring-plan-v2.md)
的首批交付裁剪。完整 wire 契约继续以
[`docs/schemas/resource-gateway-authoring/`](schemas/resource-gateway-authoring) 为准。方案批准后，本文件
定义第一阶段的实施顺序和验收边界；v2 中未列入 Golden Path 的团队共享、OpenAPI 高级推导、治理面板和
高级编排延后实施。

普通作者只接触四个对象：

| 对象 | 作者任务 | 隐藏的实现 |
| --- | --- | --- |
| Connection | 保存 Base URL、认证和超时 | Secret Store、认证适配、Header 安全 |
| API Resource | 定义 Method、Path、输入输出、成功判断和样例 | `ApiResourceSpec`、Descriptor、Design Contract、Operator |
| Tool / Solution | 把 API 或已发布流程组成 DAG | GraphDraft lowering、Mapping、Publication、Flow Version |
| Fixture / Simulation | 保存输入、受控行为和期望输出，并运行 | NodeFixture 编译、Evidence、Trace、不可变 Run |

首批只交付一条可操作主线：

```text
Connection -> API Resource -> API Fixture -> API Simulation
           -> Tool / Solution DAG -> Flow Fixture -> Flow Simulation
           -> Flow Version -> 父 Flow 复用
```

## 2. 首批裁剪

| 能力 | Golden Path v1 | 延后原因 |
| --- | --- | --- |
| API 接入 | 手工定义 + 保存样例 | OpenAPI 推导有价值，但不能阻塞最小闭环 |
| 模拟 | 默认不触网；API 或节点返回静态 fixture | 先保证设计时可验证，再讨论真实读授权 |
| DAG | 有界同步数据流；Mapping 是唯一业务事实 | Route、补偿、长事务和任意表达式会扩大模型 |
| Tool / Solution | 第一阶段共用 Flow 模型，仅 `kind` 不同 | 运行语义尚未真实分化 |
| Fixture | 私有 fixture 直接保存和模拟 | 团队共享必须独立扫描和评审 |
| 发布 | 保存后的 Draft 发布不可变 Version | Version 是复用和回滚的最小边界 |
| 治理 | 结果只显示实现事实，不在作者页审批 | 避免把模拟成功误读为治理通过 |

显式不做：

- 模拟期间执行真实外部写。
- 前端组装 transient `GraphDraft`。
- UI 暴露 Descriptor、Design Contract、Operator 或 DSL。
- 把模拟 `PASSED` 自动解释为可发布或治理通过。

## 3. 核心 schema

以下 TypeScript 是扫读视图；字段约束以 v2 JSON Schema 为准。

### 3.1 精确引用

```ts
type ExactSubjectRef =
  | { kind: 'API_RESOURCE'; resourceId: string; revision: number; fingerprint: string }
  | { kind: 'FLOW_DRAFT'; draftId: string; revision: number; fingerprint: string }
  | { kind: 'FLOW_VERSION'; publicationId: string; revision: number; fingerprint: string };

type ComposableRef = Extract<ExactSubjectRef, { kind: 'API_RESOURCE' | 'FLOW_VERSION' }>;
```

规则：

- 所有 Fixture、模拟、发布和子流程复用都绑定精确 revision 和 fingerprint。
- `FLOW_DRAFT` 可以作为 Fixture Subject，但不能被另一个 Flow 节点引用。
- Scope 从认证身份派生，不由前端提交。

### 3.2 API Resource 保存

```ts
type ApiResourceSaveCommand = {
  schemaVersion: 'bloge.apiResourceSaveCommand.v1';
  connection:
    | { mode: 'EXISTING'; connectionId: string }
    | { mode: 'CREATE'; command: ConnectionCommand };
  resource: {
    displayName: string;
    method: 'GET' | 'POST' | 'PUT' | 'DELETE';
    path: string;
    input: SchemaEnvelope;
    output: SchemaEnvelope;
    bindings: Array<{
      inputPath: string;
      transport: 'PATH' | 'QUERY' | 'HEADER' | 'BODY';
      externalName: string;
    }>;
    success: {
      kind: 'HTTP_STATUS' | 'BODY_MATCH';
      codes?: number[];
      path?: string;
      values?: unknown[];
    };
    effect: 'READ_ONLY' | 'FIXTURE_ONLY_WRITE' | 'MANAGED_WRITE';
    examples: Array<{ name: string; input: unknown; output: unknown }>;
  };
  defaultFixture?:
    | { kind: 'NONE' }
    | { kind: 'FROM_EXAMPLES'; displayName: string; exampleNames: string[] };
};
```

服务端在同一个保存边界内完成：

1. 保存权威 `ApiResourceSpec`。
2. 派生 Descriptor、Design Contract 和 Operator。
3. 验证三份投影 READY。
4. 可选生成私有 Default Fixture。
5. 返回绑定精确 revision / fingerprint 的 Receipt。

任一投影失败时，不返回成功 Receipt，也不把半成品暴露为可组合 API。

### 3.3 Tool / Solution DAG

```ts
type ReusableFlowSaveCommand = {
  schemaVersion: 'bloge.reusableFlowSaveCommand.v1';
  flow: {
    displayName: string;
    kind: 'TOOL' | 'SOLUTION';
    description: string;
    contract: {
      input: SchemaEnvelope;
      output: SchemaEnvelope;
    };
    graph: {
      nodes: Array<{
        nodeId: string;
        label: string;
        use: ComposableRef;
        inputs: Array<{
          to: string;
          from: MappingSource;
        }>;
      }>;
      output: {
        nodeId: string;
        path: string;
      };
    };
    layout: Record<string, { x: number; y: number }>;
  };
};

type MappingSource =
  | { kind: 'FLOW_INPUT'; path: string }
  | { kind: 'NODE_OUTPUT'; nodeId: string; path: string }
  | { kind: 'CONSTANT'; value: unknown };
```

约束：

- `graph.nodes[].inputs` 是唯一业务 Mapping。画布中的边只是 `NODE_OUTPUT` 的可视化投影。
- `layout` 保存界面坐标，但不参与业务 fingerprint。
- 标准校验包括：无环、节点引用可见、Mapping 路径存在、Schema 兼容、无重复 ID、唯一 Flow output。
- 无法 lowering 的旧图保留在 Legacy / Advanced，不进入新对象页。

### 3.4 Fixture Set

```ts
type FixtureSetCommand = {
  schemaVersion: 'bloge.fixtureSetCommand.v1';
  displayName: string;
  subject: ExactSubjectRef;
  cases: Array<{
    caseId?: string;
    name: string;
    input: unknown;
    overrides: Array<FixtureOverride>;
    expect?: { output: unknown };
  }>;
};

type FixtureOverride =
  | {
      target: { kind: 'SUBJECT' } | { kind: 'NODE'; nodeId: string };
      behavior: 'STATIC';
      output: unknown;
      fidelity?: 'OUTPUT_LEVEL' | 'PROTOCOL_DERIVED' | 'TRANSPORT_LEVEL';
    }
  | {
      target: { kind: 'SUBJECT' } | { kind: 'NODE'; nodeId: string };
      behavior: 'ERROR';
      code: string;
      message: string;
    }
  | {
      target: { kind: 'SUBJECT' } | { kind: 'NODE'; nodeId: string };
      behavior: 'TIMEOUT';
      afterMs: number;
    };
```

Golden Path 只开放 `STATIC`、`ERROR` 和 `TIMEOUT`。`RETURN` 的语义在 wire 层保留为受控静态返回；
UI 统一显示「Mocked」。`REPLAY`、`APPLY_CASE` 和受保护团队资产延后。

私有 Fixture 可以直接保存和模拟。Subject revision 或 fingerprint 变化后，旧 Fixture 显示 `STALE`，
服务端拒绝按旧引用运行。

### 3.5 Simulation

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
        overrides?: FixtureOverride[];
      };
  executionPolicy: {
    externalReads: { kind: 'DENY' };
    externalWrites: { kind: 'DENY' };
  };
};

type SimulationRun = {
  schemaVersion: 'bloge.simulationRun.v1';
  runId: string;
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'REJECTED' | 'TIMEOUT';
  subject: ExactSubjectRef;
  nodes: Array<{
    nodeId: string;
    status: 'NOT_STARTED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED';
    evidence: 'MOCKED' | 'FIXTURE_ONLY' | 'NOT_APPLICABLE' | 'UNKNOWN';
    input?: unknown;
    output?: unknown;
    diagnostics?: unknown[];
  }>;
  output?: unknown;
  verdicts: {
    execution: 'NOT_CHECKED' | 'PASSED' | 'FAILED';
    contract: 'NOT_CHECKED' | 'VALID' | 'INVALID';
    assertions: 'NOT_CHECKED' | 'PASSED' | 'FAILED';
    governance: 'NOT_CHECKED';
  };
  startedAt: string;
  endedAt?: string;
};
```

Golden Path v1 的模拟总是不触网。`REAL` evidence 和 `ALLOW_EXACT` 外部读授权不属于首批用户界面；
后端策略仍保留扩展位。缺少节点证据时必须显示 `UNKNOWN` 并判定失败，不得默认成功。

## 4. HTTP 主线

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` / `PUT` | `/api/authoring/connections/{connectionId}` | 读取 payload-free Connection；Secret 只写 |
| `GET` | `/api/authoring/resources/{resourceId}` | 读取最新或精确 API Resource |
| `PUT` | `/api/authoring/resources/{resourceId}` | 原子保存 Connection、Resource 和可选 Default Fixture |
| `GET` | `/api/authoring/catalog?kind=API_RESOURCE\|FLOW_VERSION` | Flow 节点统一选择目录 |
| `GET` / `PUT` | `/api/authoring/flows/{flowId}` | 读取或保存 Tool / Solution Draft |
| `POST` | `/api/authoring/flows/{flowId}:publish` | 发布不可变 Flow Version |
| `GET` / `PUT` | `/api/authoring/fixture-sets/{fixtureSetId}` | 读取或保存私有 Fixture Set |
| `POST` | `/api/authoring/simulations` | 从 Fixture Case 或 Ad-hoc Subject 运行 |
| `GET` | `/api/authoring/simulations/{runId}` | 读取不可变 Simulation Run |

传输规则：

- 创建使用 `If-None-Match: *`；更新使用强 `ETag` 的 `If-Match`。
- 写操作、发布和模拟携带 `Idempotency-Key`。
- 条件头缺失返回 `428`，失配返回 `412`。
- 错误统一使用 payload-free Problem Detail，不返回 Secret、Fixture payload 或外部响应原文。

## 5. 用户路径

### 5.1 接入 API

1. 选择「新建 API Resource」。
2. 选择已有 Connection，或填写 Base URL、认证、超时并保存。
3. 填写 Method、Path、输入 Schema、输出 Schema。
4. 用绑定表确认 `PATH`、`QUERY`、`HEADER`、`BODY`。
5. 设置成功判断、写效果分类和至少一个 Example。
6. 勾选「从 Example 建默认 Fixture」。
7. 保存。页面显示 Resource READY 和三份投影 READY。

### 5.2 模拟 API Resource

1. 打开 API Resource 的 Fixture 页。
2. 修改 Default Case 的输入和 Mocked output。
3. 保存私有 Fixture。
4. 点击 Run。
5. 查看输出、contract verdict 和 assertion verdict。

成功标准：不配置真实外部服务也能完成首次模拟。

### 5.3 组合 Tool / Solution

1. 新建 Tool 或 Solution。
2. 填写整体输入 / 输出 Schema。
3. 从 Catalog 添加两个 API Resource，或添加一个已发布 Flow Version。
4. 用 Mapping 表连接 Flow input、节点输出和 Flow output。
5. 保存 Draft。服务端返回精确 Draft revision / fingerprint。

画布可以由 Mapping 派生，但 Mapping 删除后画布边必须同步消失。

### 5.4 模拟和复用 Tool / Solution

1. 为 Flow Draft 保存整体输入和期望输出。
2. 需要时为单个节点设置 Mocked output、ERROR 或 TIMEOUT。
3. 从 Fixture Case 运行，检查每个节点的 evidence。
4. 执行全部节点后显示整体输出和 assertion verdict。
5. 发布得到不可变 Flow Version。
6. 新建父 Flow，从 Catalog 选择该 Version 并复用。
7. 再次保存父 Flow Fixture 并模拟。

成功标准：旧 Draft 继续修改不影响旧 Version；父 Flow、旧 Version 和 Fixture 引用都可精确回放。

## 6. 前端结构

新增四个一级入口，旧 Visual Authoring 保留：

| 页面 | 主区域 |
| --- | --- |
| API Resource | Design、Fixture、Simulation |
| Tool / Solution | Contract、Graph、Fixture、Simulation、Version |
| Fixture | Subject 摘要、Cases、Overrides、Run |
| Simulation | Input source、节点 Trace、Output、Verdicts |

组件边界：

- API 表单只产生 `ApiResourceSaveCommand`。
- Flow 画布把用户连线转换成 `NODE_OUTPUT` Mapping；不独立保存 Edge。
- Fixture 表单只产生 `FixtureSetCommand`。
- Run 面板只渲染服务端 `SimulationRun`，不从本地执行结果推断 verdict。
- 保存成功只能由服务端 Receipt 驱动；不能用本地 state 伪造 READY。

## 7. 后端模块边界

| 模块 | 职责 | 禁止事项 |
| --- | --- | --- |
| Connection Module | 保存 metadata、Secret lease、payload-free View | 返回 credential 或隐式触网 |
| ApiResource Module | 原子保存权威对象、重建三投影 | 异步投影冒充成功 |
| ReusableFlow Module | 校验 DAG、保存 Mapping、发布 Version | 前端提交 Edge 业务事实 |
| FixtureSet Module | 保存 Subject 闭包、校验 Override、处理 STALE | 允许跨 Scope 或漂移 Subject 运行 |
| Simulation Module | 编译 Fixture、调用既有内核、写不可变 Run | 默认触网或伪造 evidence |

新增 HTTP 层调用这些模块；不把校验、CAS 或投影规则复制到 Controller 和前端。

## 8. 实施顺序

### Slice A — Contract lock

1. 冻结本文件列出的四个命令和两个读取模型。
2. 为 JSON Schema 补最小、完整、缺字段、未知字段、非法枚举和版本不支持样例。
3. 增加 Contract 回归，确认前端 transport 只能产出批准的 wire。

完成定义：Schema 契约测试全绿；文档与 schema 无字段冲突。

### Slice B — API Resource 闭环

1. 完成 Connection 与 API Resource 的 Authoring Facade。
2. 原子保存权威对象和三投影。
3. 从 Example 生成私有 Default Fixture。
4. 实现不触网 API Simulation。

完成定义：HTTP 测试覆盖 Scope、CAS、幂等、428/412、投影失败和 Secret-free 错误。

### Slice C — Flow DAG 闭环

1. 保存 Mapping-only Flow Draft。
2. 从 Catalog 添加 API Resource 和 Flow Version。
3. 校验环、路径、Schema 和 output。
4. 保存 Flow Fixture 并模拟。
5. 发布不可变 Flow Version。

完成定义：`API -> Flow -> Publish -> 父 Flow -> Simulation` 集成测试通过。

### Slice D — Golden Path UI

1. 实现四个对象页。
2. 完成 Mapping 表和画布投影。
3. 完成私有 Fixture 和 Run 面板。
4. 暴露统一 Catalog。

完成定义：1280 宽度浏览器验收完成第 5 节完整链路，且不调用旧创作协议。

### Slice E — 收尾门禁

1. 运行 focused backend、frontend、contract 和 browser tests。
2. 运行 H2 PostgreSQL-mode migration tests。
3. 运行真实 PostgreSQL certification lane。
4. 运行 Resource Gateway `clean verify`。
5. 更新 v2 ledger、README 和本方案状态。

只有全部证据为绿且文档同步后，才能把 Golden Path v1 标记为 Accepted。

## 9. 测试矩阵

| 层 | 必测负例 |
| --- | --- |
| Schema | 缺字段、未知字段、坏版本、非法枚举、超长字段、非法 fingerprint |
| Connection | Secret 泄漏、保留 Header、弱 URL、CAS 失配、Secret lease 失败 |
| API Resource | 投影失败、并发保存、跨 Scope、非法 Binding、Example 携带认证 Header |
| Flow | 环、未知节点、未知 port、Schema 不兼容、重复 ID、Layout 改变 fingerprint |
| Fixture | Subject 漂移、跨 Scope、未知 nodeId、非法 Override、内容与状态 CAS 分离 |
| Simulation | 默认拒绝网络、UNKNOWN evidence、失败传播、Run 不可变、重跑新 runId |
| HTTP | 428、412、幂等重放、同 Key 不同 Payload、Problem Detail 无 payload |
| Browser | 完整 API / Fixture / DAG / Run / Publish / Reuse 链路 |

## 10. 审批清单

请确认以下六点：

1. Golden Path v1 只先交付手工 API 接入和不触网模拟，OpenAPI 推导延后。
2. Tool 与 Solution 第一阶段共用 `ReusableFlowDraft`，只通过 `kind` 区分产品语义。
3. Mapping 是 DAG 唯一业务事实，画布 Edge 只是投影。
4. 私有 Fixture 可直接保存和模拟；团队共享与治理延后。
5. Golden Path 模拟一律拒绝外部读和外部写。
6. 实施顺序按 Slice A -> B -> C -> D -> E，完整 `clean verify` 和 PostgreSQL lane 放在收尾门禁。

明确批准前，不新增生产 Controller、Repository、UI 页面或数据库迁移。
