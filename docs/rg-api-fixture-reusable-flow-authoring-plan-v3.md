# Resource Gateway API、Fixture、Tool / Solution 可操作创作方案 v3

状态：Proposed，等待用户审批。

日期：2026-08-30。

范围：外部 API 接入、单 API Resource 的 Fixture 与模拟、多个 API Resource 组成 Tool / Solution、
Tool / Solution 的整体或节点级 Fixture 与模拟，以及发布后复用。

本文是产品可操作性与前后端主契约的审阅入口。
完整字段约束继续以
[`docs/schemas/resource-gateway-authoring/`](schemas/resource-gateway-authoring) 中的 v1 JSON Schema
为准。历史推导可参考同目录的 v2 工作稿；该稿不是实施前提。
本文通过后，按本文的产品边界和精简规则修订权威 JSON Schema。

本文获得审批前，不实施生产代码、UI、Controller、Repository 或迁移。

## 1. 结论

不再继续打磨旧六阶段创作 Spine，也不再让普通作者理解 `ResourceDescriptor`、`ResourceDesignContract`、
`GraphDraft`、`NodeFixture`、Operator 投影和治理证据的内部协议。

普通作者只面对四个稳定对象和四条任务：

| 用户对象 | 作者完成的事情 | 服务端隐藏的事情 |
| --- | --- | --- |
| Connection | 填 Base URL、认证、默认 Header、超时 | Secret 只写、Header 安全、网络检查 |
| API Resource | 定义 Method、Path、输入输出、成功判断、样例 | `ApiResourceSpec`、Descriptor、Design Contract、Operator 投影 |
| Tool / Solution | 把 API Resource 或已发布 Flow 组成有向无环图 | GraphDraft lowering、Mapping、发布、Flow Version |
| Fixture Set | 保存输入、节点控制、期望输出，并运行 Case | NodeFixture、Scenario dependency、Evidence、治理状态 |

| 任务 | 用户动作 | 成功结果 |
| --- | --- | --- |
| 接入外部 API | 选 Connection、填 Operation、确认 Schema、保存 | API Resource `ACTIVE`，三投影 `READY` |
| 单 API 模拟 | 从 Example 生成 Fixture、保存、Run | 得到不可变 `SimulationRun` 和真实 / Mocked 证据 |
| 组合 Tool / Solution | 添加节点、连线映射、保存、发布 | 得到不可变 `FLOW_VERSION` 并进入目录 |
| 整体流程模拟 | 选择 Flow Draft、保存 Fixture Case、Run | 得到节点级来源、整体输出和四维 Verdict |

降低复杂度的五个决定：

1. **对象只有五个。** Connection、API Resource、Reusable Flow、Fixture Set、Simulation Run。
2. **Subject 统一。** API Resource、Flow Draft、Flow Version 都能作为 Fixture Subject；前端不再为两类对象写两套 Fixture 模型。
3. **入口只有三个。** Authoring Catalog、Fixture Workspace、Simulation Workspace；向导只是入口上的临时状态。
4. **保存后再模拟。** 模拟只引用精确对象和 Fixture Case，不接收整份 transient `GraphDraft`。
5. **默认不触网。** 外部读默认拒绝，外部写永远拒绝；真实读必须显式列出精确 API Resource revision。

## 2. 非目标

第一版标准模式不做以下事情：

- 不支持真实外部写模拟。
- 不把 Route、分支引擎、人工任务、长事务、任意表达式和 Provider 特殊 Operator 纳入标准 DAG。
- 不允许普通作者在 Resource 编辑页执行治理审批。
- 不把模拟成功解释为治理通过或可发布。
- 不创建第二个表达式语言、类型系统或通用 Artifact 模型。
- 不把高级旧图作为迁移阻塞项；无法表达时保留为 Legacy-only。

## 3. 总体架构

```text
Connection
    │  credential resolution, header policy, timeout
    ▼
ApiResourceSpec ── projection ──▶ Descriptor + Design Contract + Operator
    │                                              │
    │ composable ref                               │ execution adapter
    ▼                                              ▼
ReusableFlowDraft ── publish ──▶ ReusableFlowVersion ──▶ parent Flow
    │                                              ▲
    │ exact subject                                │ runtime lowering
    ▼                                              │
FixtureSet ──── POST /simulations ────▶ SimulationRun
```

五个深模块只暴露领域命令，不把内部协议透给 HTTP 或前端：

| Module | 唯一职责 |
| --- | --- |
| `ConnectionModule` | Connection 权威、Secret staging / activation、Header 和 SSRF 策略 |
| `ApiResourceModule` | API Resource 权威和三投影原子提交 |
| `ReusableFlowModule` | Flow Mapping、DAG 校验、Draft / Version 权威 |
| `FixtureSetModule` | Subject 闭包、Case、节点控制、私有 / 团队状态 |
| `SimulationModule` | 执行策略、受控运行、Evidence、Schema / Assertion / Governance 结论 |

## 4. 前端 Schema

前端 Editor Schema 只保存当前表单和界面状态。所有 `id`、`revision`、`fingerprint`、`etag` 和运行结果
都来自服务端 Receipt 或 View，不得由前端推算或写回领域对象。

### 4.0 共享前端类型

```ts
type JsonPointer = `/${string}`;

type AuthoringProblemDetail = {
  type: string;
  title: string;
  status: number;
  detail: string;
  code: string;
  correlationId: string;
  fieldErrors: Array<{
    field: string;
    code: string;
    message: string;
  }>;
  recoveryActions: Array<{
    code: string;
    title: string;
    method: 'GET' | 'PUT' | 'POST';
    path?: string;
  }>;
};

type FixtureMaterial =
  | { kind: 'INLINE'; value: unknown }
  | {
      kind: 'FIXTURE_ASSET';
      fixtureAssetId: string;
      revision: number;
      schemaFingerprint: string;
    };
```

### 4.1 API Resource Wizard Model

向导是 API Resource 对象页前的临时编辑器；保存时折叠成唯一的 `ApiResourceSaveCommand`。

```ts
type ApiResourceWizardModel = {
  mode: 'CREATE' | 'EDIT';
  target?: {
    resourceId: string;
    revision: number;
    etag: string;
  };
  connection:
    | {
        choice: 'EXISTING';
        connectionId: string;
      }
    | {
        choice: 'CREATE';
        displayName: string;
        baseUrl: string;
        auth: 'NONE' | 'BEARER' | 'BASIC' | 'API_KEY';
        credential?: unknown;
        apiKeyHeader?: string;
        timeoutMs?: number;
      };
  operation: {
    displayName: string;
    method: 'GET' | 'POST' | 'PUT' | 'DELETE';
    path: string;
    bindings: Array<{
      from: string;
      location: 'PATH' | 'QUERY' | 'HEADER' | 'BODY';
      name: string;
    }>;
  };
  contract: {
    input: SchemaEnvelope;
    output: SchemaEnvelope;
  };
  response: {
    success:
      | { kind: 'HTTP_STATUS'; codes: number[] }
      | { kind: 'BODY_MATCH'; path: string; values: unknown[] };
    outputPath?: string;
  };
  effect: {
    classified: boolean;
    kind: 'READ_ONLY' | 'FIXTURE_ONLY_WRITE' | 'MANAGED_WRITE';
    idempotencyHeader?: string;
  };
  examples: Array<{
    name: string;
    input: unknown;
    output: unknown;
  }>;
  createDefaultFixture: boolean;
  ui: {
    step: 'CONNECTION' | 'OPERATION' | 'CONTRACT' | 'EXAMPLE';
    dirty: boolean;
    submitting: boolean;
    serverErrors: AuthoringProblemDetail[];
  };
};
```

约束：

- `MANAGED_WRITE` 必须填写幂等 Header，否则不能进入下一步。
- Example 是普通输入 / 输出样例，不能携带 Authorization、Cookie 或保留 Header。
- `createDefaultFixture` 只是保存命令中的一个布尔意图，Fixture 是否生成以 Receipt 为准。

### 4.2 Flow Editor Model

`nodes[].inputs` 是唯一业务 Mapping。画布 Edge、坐标和布局都是投影，不进入业务指纹。

```ts
type FlowEditorModel = {
  target?: {
    flowId: string;
    draftId: string;
    revision: number;
    etag: string;
  };
  identity: {
    displayName: string;
    kind: 'TOOL' | 'SOLUTION';
    description: string;
  };
  contract: {
    input: SchemaEnvelope;
    output: SchemaEnvelope;
  };
  nodes: Array<{
    nodeId: string;
    label: string;
    use:
      | {
          kind: 'API_RESOURCE';
          resourceId: string;
          revision: number;
          fingerprint: string;
        }
      | {
          kind: 'FLOW_VERSION';
          publicationId: string;
          revision: number;
          fingerprint: string;
        };
    inputs: Array<{
      to: string;
      from:
        | { kind: 'FLOW_INPUT'; path: string }
        | { kind: 'NODE_OUTPUT'; nodeId: string; path: string }
        | { kind: 'CONSTANT'; value: unknown };
    }>;
  }>;
  output: {
    nodeId: string;
    path: string;
  };
  layout: Record<string, { x: number; y: number }>;
  ui: {
    selectedNodeId?: string;
    dirty: boolean;
    submitting: boolean;
    serverErrors: AuthoringProblemDetail[];
  };
};
```

约束：

- 只允许引用 `ACTIVE` API Resource 或 `AVAILABLE` Flow Version。
- 保存前服务端校验重复节点 ID、未知 port、环、输入 Schema 兼容和输出闭包。
- 删除连线必须删除对应 Mapping；不得留下孤立业务 Edge。
- `TOOL` 和 `SOLUTION` 第一阶段共用运行模型，差异只体现在目录、权限语境和产品文案。

### 4.3 Fixture Editor Model

API Resource 和 Tool / Solution 使用同一个 Fixture Editor。差异只来自 Subject 类型：

- Subject 是 API Resource 时，默认只需 Case 输入，可选 `SUBJECT` 级 `RETURN`。
- Subject 是 Flow Draft 时，除整体输入外，还可为任意节点设置控制。

```ts
type FixtureEditorModel = {
  target?: {
    fixtureSetId: string;
    revision: number;
    statusRevision: number;
    etag: string;
  };
  subject:
    | {
        kind: 'API_RESOURCE';
        resourceId: string;
        revision: number;
        fingerprint: string;
      }
    | {
        kind: 'FLOW_DRAFT';
        draftId: string;
        revision: number;
        fingerprint: string;
      };
  cases: Array<{
    caseId?: string;
    name: string;
    input: unknown;
    expect?: {
      output: unknown;
    };
    controls: Array<{
      target:
        | { kind: 'SUBJECT' }
        | { kind: 'NODE'; nodeId: string };
      behavior:
        | { kind: 'REAL' }
        | { kind: 'RETURN'; material: FixtureMaterial }
        | {
            kind: 'APPLY_CASE';
            fixtureSetId: string;
            revision: number;
            caseId: string;
          }
        | { kind: 'ERROR'; code: string; message: string }
        | { kind: 'TIMEOUT'; afterMs: number }
        | { kind: 'REPLAY'; replayId: string; fingerprint: string };
      fidelity?: 'OUTPUT_LEVEL' | 'PROTOCOL_DERIVED' | 'TRANSPORT_LEVEL';
    }>;
  }>;
  ui: {
    selectedCaseId?: string;
    dirty: boolean;
    submitting: boolean;
    serverErrors: AuthoringProblemDetail[];
  };
};
```

约束：

- `RETURN` 展示为 Mocked，不展示为 Success。
- `REAL` 只表示不覆盖该依赖；能否真实访问仍由 `SimulationRequest.executionPolicy` 决定。
- 私有 Fixture 可以直接保存和模拟；共享 Fixture 必须进入独立评审队列。
- 团队 Fixture 的 Subject fingerprint 变化后显示 `STALE`，不能继续作为可复用团队资产。

### 4.4 Simulation Panel Model

模拟页只渲染服务端返回的不可变 `SimulationRun`。

```ts
type SimulationPanelModel = {
  runId?: string;
  status?: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'REJECTED' | 'TIMEOUT';
  executionPolicy: {
    externalReads: 'DENY' | 'ALLOW_EXACT';
    externalWrites: 'DENY';
  };
  nodes: Array<{
    nodeId: string;
    status: string;
    evidence: 'REAL' | 'MOCKED' | 'FIXTURE_ONLY' | 'NOT_APPLICABLE';
    output?: unknown;
    diagnostics: AuthoringProblemDetail[];
  }>;
  output?: unknown;
  verdicts: {
    execution: 'NOT_CHECKED' | 'PASSED' | 'FAILED';
    contract: 'NOT_CHECKED' | 'VALID' | 'INVALID';
    assertions: 'NOT_CHECKED' | 'PASSED' | 'FAILED';
    governance: 'NOT_CHECKED' | 'PASSED' | 'FAILED';
  };
};
```

四维结论相互独立。Execution `PASSED` 不能改写 Contract、Assertions 或 Governance。

## 5. 后端 Wire Schema

本节是跨前后端的关键契约摘要。字段级 JSON Schema 以 v1 文件为准。

### 5.1 公共 Schema Envelope

```ts
type SchemaEnvelope = {
  format: 'json-schema';
  version: '2020-12';
  schema: Record<string, unknown>;
};
```

### 5.2 精确 Subject 引用

```ts
type ExactSubjectRef =
  | {
      kind: 'API_RESOURCE';
      resourceId: string;
      revision: number;
      fingerprint: `sha256:${string}`;
    }
  | {
      kind: 'FLOW_DRAFT';
      draftId: string;
      revision: number;
      fingerprint: `sha256:${string}`;
    }
  | {
      kind: 'FLOW_VERSION';
      publicationId: string;
      revision: number;
      fingerprint: `sha256:${string}`;
    };

type ComposableRef = Extract<
  ExactSubjectRef,
  { kind: 'API_RESOURCE' | 'FLOW_VERSION' }
>;
```

规则：

- `revision >= 1`；`fingerprint` 由服务端计算并持久化。
- Scope 从认证身份派生，不由前端提交。
- `FLOW_DRAFT` 可以有 Fixture 和模拟，但不能被父 Flow 引用。
- UI 默认显示名称和 `rN`；完整 fingerprint 只出现在版本详情和 Receipt。

### 5.3 Connection

```ts
type ConnectionCommand = {
  schemaVersion: 'bloge.apiConnectionCommand.v1';
  displayName: string;
  baseUrl: string;
  auth:
    | { kind: 'NONE' }
    | { kind: 'BEARER'; token: SecretWrite }
    | { kind: 'BASIC'; username: string; password: SecretWrite }
    | { kind: 'API_KEY'; headerName: string; value: SecretWrite };
  defaults?: {
    headers?: Record<string, string>;
    timeoutMs?: number;
  };
};

type SecretWrite =
  | { mode: 'VALUE'; value: string }
  | { mode: 'SECRET_REF'; ref: `vault://${string}` }
  | { mode: 'KEEP_EXISTING' };

type ConnectionView = {
  schemaVersion: 'bloge.apiConnectionView.v1';
  connectionId: string;
  revision: number;
  displayName: string;
  baseUrl: string;
  auth: {
    kind: 'NONE' | 'BEARER' | 'BASIC' | 'API_KEY';
    configured: boolean;
    headerName?: string;
  };
  defaults?: {
    headers?: Record<string, string>;
    timeoutMs?: number;
  };
};
```

Secret 只写不读；View、Receipt、错误和日志不得返回 credential。
`authorization`、`cookie`、`host`、`content-length`、`x-forwarded-*` 等保留 Header 不能被占用。

### 5.4 API Resource

```ts
type ApiResourceSaveCommand = {
  schemaVersion: 'bloge.apiResourceSaveCommand.v1';
  connection:
    | { mode: 'EXISTING'; connectionId: string }
    | { mode: 'CREATE'; command: ConnectionCommand };
  resource: {
    displayName: string;
    description?: string;
    operation: {
      method: 'GET' | 'POST' | 'PUT' | 'DELETE';
      path: string;
      bindings: Array<{
        from: string;
        to:
          | { location: 'PATH'; name: string }
          | { location: 'QUERY'; name: string }
          | { location: 'HEADER'; name: string }
          | { location: 'BODY'; name: string };
      }>;
    };
    contract: {
      input: SchemaEnvelope;
      output: SchemaEnvelope;
    };
    response: {
      success:
        | { kind: 'HTTP_STATUS'; codes: number[] }
        | { kind: 'BODY_MATCH'; path: string; values: unknown[] };
      outputPath?: string;
    };
    effect:
      | { kind: 'READ_ONLY' }
      | { kind: 'FIXTURE_ONLY_WRITE' }
      | {
          kind: 'MANAGED_WRITE';
          idempotencyHeader: string;
          receipt: {
            idPath: string;
            statusPath: string;
            succeededValues: unknown[];
            failedValues: unknown[];
          };
        };
    examples: Array<{
      name: string;
      input: unknown;
      output: unknown;
    }>;
  };
  defaultFixture?:
    | { kind: 'NONE' }
    | { kind: 'FROM_EXAMPLES'; displayName: string; exampleNames: string[] };
};
```

服务端把该命令原子展开成：

1. `ApiResourceSpec` 权威；
2. `ResourceDescriptor` 投影；
3. `ResourceDesignContract` 投影；
4. Operator Catalog 投影；
5. 可选 Default Fixture Set。

Receipt 必须绑定同一个权威 `resourceId + revision + fingerprint`，并证明三投影都 `READY`。
任一投影失败时不显示成功，不发布半成品 Catalog。

```ts
type ApiResourceSpec = {
  schemaVersion: 'bloge.apiResourceSpec.v1';
  resourceId: string;
  revision: number;
  fingerprint: `sha256:${string}`;
  displayName: string;
  connectionId: string;
  operation: ApiResourceSaveCommand['resource']['operation'];
  contract: ApiResourceSaveCommand['resource']['contract'];
  response: ApiResourceSaveCommand['resource']['response'];
  effect: ApiResourceSaveCommand['resource']['effect'];
  examples: ApiResourceSaveCommand['resource']['examples'];
  status: 'DRAFT' | 'ACTIVE' | 'DEPRECATED' | 'DISABLED';
};
```

### 5.5 Reusable Flow

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
    layout: {
      nodes: Record<string, { x: number; y: number }>;
    };
  };
};

type MappingSource =
  | { kind: 'FLOW_INPUT'; path: JsonPointer }
  | { kind: 'NODE_OUTPUT'; nodeId: string; path: JsonPointer }
  | { kind: 'CONSTANT'; value: unknown };

type ReusableFlowDraft = {
  schemaVersion: 'bloge.reusableFlowDraft.v1';
  flowId: string;
  draftId: string;
  revision: number;
  fingerprint: `sha256:${string}`;
  flow: ReusableFlowSaveCommand['flow'];
  status: 'DRAFT';
};

type ReusableFlowVersion = {
  schemaVersion: 'bloge.reusableFlowVersion.v1';
  publicationId: string;
  revision: number;
  fingerprint: `sha256:${string}`;
  source: {
    flowId: string;
    draftId: string;
    revision: number;
    fingerprint: `sha256:${string}`;
  };
  flow: ReusableFlowSaveCommand['flow'];
  status: 'AVAILABLE' | 'DEPRECATED' | 'DISABLED';
};
```

发布命令的 `source` 必须指向当前 Draft 的精确 revision / fingerprint。
Draft 后续变化不影响旧 Version，也不影响已绑定旧 Version 的 Fixture。

### 5.6 Fixture Set

```ts
type FixtureSetCommand = {
  schemaVersion: 'bloge.fixtureSetCommand.v1';
  displayName: string;
  subject: ExactSubjectRef;
  cases: Array<{
    caseId?: string;
    name: string;
    input: unknown;
    controls: FixtureControl[];
    expect?: {
      output: unknown;
    };
  }>;
};

type FixtureMaterial =
  | { kind: 'INLINE'; value: unknown }
  | {
      kind: 'FIXTURE_ASSET';
      fixtureAssetId: string;
      revision: number;
      schemaFingerprint: `sha256:${string}`;
    };

type FixtureControl = {
  target:
    | { kind: 'SUBJECT' }
    | { kind: 'NODE'; nodeId: string };
  behavior:
    | { kind: 'REAL' }
    | { kind: 'RETURN'; material: FixtureMaterial }
    | {
        kind: 'APPLY_CASE';
        fixtureSetId: string;
        revision: number;
        caseId: string;
      }
    | { kind: 'ERROR'; code: string; message: string }
    | { kind: 'TIMEOUT'; afterMs: number }
    | { kind: 'REPLAY'; replayId: string; fingerprint: `sha256:${string}` };
  fidelity?: 'OUTPUT_LEVEL' | 'PROTOCOL_DERIVED' | 'TRANSPORT_LEVEL';
};
```

私有 Fixture 的 `INLINE` material 允许保存在当前 Scope。共享前必须扫描并转换为受保护
`FIXTURE_ASSET`；Share Receipt 不返回业务 Payload。

### 5.7 Simulation

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
        controls?: FixtureControl[];
      };
  executionPolicy: {
    externalReads:
      | { kind: 'DENY' }
      | {
          kind: 'ALLOW_EXACT';
          resources: Array<Extract<ExactSubjectRef, { kind: 'API_RESOURCE' }>>;
          justification: string;
        };
    externalWrites: { kind: 'DENY' };
  };
};

type SimulationRun = {
  schemaVersion: 'bloge.simulationRun.v1';
  runId: string;
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'REJECTED' | 'TIMEOUT';
  subject: ExactSubjectRef;
  fixtureCase?: {
    fixtureSetId: string;
    revision: number;
    caseId: string;
  };
  nodes: Array<{
    nodeId: string;
    status: 'NOT_STARTED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED';
    evidence: 'REAL' | 'MOCKED' | 'FIXTURE_ONLY' | 'NOT_APPLICABLE';
    input?: unknown;
    output?: unknown;
    diagnostics?: unknown[];
  }>;
  output?: unknown;
  verdicts: {
    execution: 'NOT_CHECKED' | 'PASSED' | 'FAILED';
    contract: 'NOT_CHECKED' | 'VALID' | 'INVALID';
    assertions: 'NOT_CHECKED' | 'PASSED' | 'FAILED';
    governance: 'NOT_CHECKED' | 'PASSED' | 'FAILED';
  };
  diagnostics: unknown[];
  startedAt: string;
  endedAt?: string;
};
```

Run 不可变；重跑产生新 `runId`。每个节点都必须回答真实执行或受控执行；
缺少 Evidence 时显示未知或失败，不得默认成功。

## 6. HTTP 面

标准作者只依赖以下端点：

| 操作 | Method | Path |
| --- | --- | --- |
| 创建 / 更新 Connection | `PUT` | `/api/authoring/connections/{connectionId}` |
| 显式网络 / 认证检查 | `POST` | `/api/authoring/connections/{connectionId}:check` |
| OpenAPI Operation 预览 | `POST` | `/api/authoring/resources:preview-openapi` |
| 原子保存 API Resource | `PUT` | `/api/authoring/resources/{resourceId}` |
| 统一可组合目录 | `GET` | `/api/authoring/catalog?kind=API_RESOURCE\|FLOW_VERSION` |
| 保存 Flow Draft | `PUT` | `/api/authoring/flows/{flowId}` |
| 发布 Flow Version | `POST` | `/api/authoring/flows/{flowId}:publish` |
| 保存 Fixture Set | `PUT` | `/api/authoring/fixture-sets/{fixtureSetId}` |
| 发起 Fixture 共享 | `POST` | `/api/authoring/fixture-sets/{fixtureSetId}:share` |
| 启动模拟 | `POST` | `/api/authoring/simulations` |
| 读取模拟结果 | `GET` | `/api/authoring/simulations/{runId}` |

通用规则：

- 创建用 `If-None-Match: *`；更新用服务端强 `ETag` 加 `If-Match`。
- 缺失条件返回 `428`，失配返回 `412`。
- 写操作、Connection Check、Publish、Share 和 Simulation 都要求 `Idempotency-Key`。
- 同 Key 同 Payload 返回原 Receipt；同 Key 不同 Payload 返回 `409`。
- 错误统一使用 payload-free Problem Detail，包含稳定 `code`、`correlationId` 和恢复动作。

## 7. 标准任务流

### 7.1 接入外部 API

1. 用户选择已有 Connection，或填写新 Connection。
2. 手工填写 Method + Path，或粘贴 OpenAPI 选择 Operation。
3. UI 从 Schema / Example 推导 PATH、QUERY、HEADER、BODY 绑定；用户确认。
4. 用户配置输入输出 Schema、成功判断、输出路径和写安全分类。
5. 保存至少一个 Example，可选勾选「用 Example 建默认 Fixture」。
6. 点击保存，服务端原子返回 Resource Receipt 和三投影 `READY`。
7. 页面进入 API Resource 对象页，只显示 Fixture、模拟、组合到 Flow 三个下一步。

用户全程不看见 Descriptor、Design Contract、Operator、DSL 或 Catalog 刷新顺序。

### 7.2 API Resource Fixture 与模拟

1. 对象页进入 Fixture Workspace。
2. 从 Example 生成，或新增空 Case。
3. 修改输入；可选把 Subject 行为设为 `RETURN` 并提供输出。
4. 保存私有 Fixture，得到 `fixtureSetId + revision + caseId`。
5. 点击 Run；前端只提交 `FIXTURE_CASE` 引用。
6. 页面显示节点 Evidence、真实 / Mocked 来源、输出、diagnostics 和四维 Verdict。
7. 配置了 expected output 时额外显示 Assertions 结论；Governance 默认保持 `NOT_CHECKED`。

### 7.3 组合 Tool / Solution

1. 用户选择新建 Tool 或 Solution。
2. 填写整体输入 / 输出 Schema。
3. 从统一 Catalog 添加 `API_RESOURCE` 或已发布 `FLOW_VERSION`。
4. 通过画布或 Mapping 表连接 `FLOW_INPUT -> node input -> NODE_OUTPUT -> flow output`。
5. 保存 Draft；服务端返回 Draft revision / fingerprint 和校验结果。
6. 通过后发布，得到不可变 `FLOW_VERSION`。
7. 新 Version 立即出现在父 Flow 的统一 Catalog。

### 7.4 Tool / Solution Fixture 与模拟

1. Flow Draft 对象页进入 Fixture Workspace。
2. Subject 固定为当前 `FLOW_DRAFT` 精确 revision / fingerprint。
3. 保存整体输入和期望输出。
4. 可为任意节点设置 `RETURN`、`ERROR`、`TIMEOUT`、`REPLAY` 或引用另一个 Case。
5. 从 Case 运行。
6. 每个节点显示真实执行或受控执行来源；整体输出先校验 Flow output Schema，再执行 Assertion。
7. 治理结论仍由独立评审和策略提供，不由模拟成功自动生成。

同一 Draft 可以保存不同 Fixture Case，分别表达正常、失败、超时和重放路径。

## 8. 执行推导

### 8.1 API Resource 执行

`ApiResourceSpec` 服务端 lowering 后生成唯一的 `httpResource` 调用边界：

- URL 由 Connection Base URL 和 Operation Path 组成。
- 输入按 PATH、QUERY、HEADER、BODY 绑定展开。
- Secret 由服务端 Connection resolver 注入；前端和内核不接收 credential。
- 成功判断和输出提取按 `response` 定义执行。

### 8.2 Flow 执行

`ReusableFlowDraft` 编译为有界同步数据流 DAG：

1. 校验节点引用、端口、环和 Schema 兼容。
2. 将 Mapping lowering 成节点输入。
3. 对 `API_RESOURCE` 节点执行 Resource lowering。
4. 对 `FLOW_VERSION` 节点内联展开其不可变 graph，但保留节点坐标和 Evidence 边界。
5. 汇总节点结果，再校验整体 output Schema。

### 8.3 Fixture 编译

`FixtureSetModule.compile` 是唯一合并入口：

1. 验证 Subject 精确存在且 fingerprint 一致。
2. 验证 `NODE` 控制的 `nodeId` 在当前 Subject 中唯一存在。
3. 验证 `APPLY_CASE` 的目标 Fixture 在同一 Scope 可见。
4. 将控制转换为执行内核的受控行为。
5. 为每个节点记录 `REAL`、`MOCKED`、`FIXTURE_ONLY` 或 `NOT_APPLICABLE`。

前端、Scenario Workspace 和 Graph Author 都不得自行维护第二套合并顺序。

### 8.4 Evidence 与治理

`SimulationRun` 是唯一用户可见结果。旧内核响应中的 `statusMap`、`results`、`mockedNodeIds`、
`nodeFidelity` 和 diagnostics 由 SimulationModule 转换为稳定 Node Trace。

四维结论含义固定：

| Verdict | 回答的问题 |
| --- | --- |
| Execution | 请求是否按策略执行完成 |
| Contract | 输入输出是否符合 Subject Schema |
| Assertions | 输出是否符合期望 |
| Governance | 共享 Fixture 或策略是否通过独立治理 |

Governance 不会被 Execution、Contract 或 Assertions 自动覆盖。

## 9. 状态与并发

| 对象 | 内容状态 | 说明 |
| --- | --- | --- |
| Connection | revision + ETag | Secret 状态独立，但 View 不返回 Secret |
| API Resource | `DRAFT` / `ACTIVE` / `DEPRECATED` / `DISABLED` | 只有三投影 READY 的 revision 对外可用 |
| Reusable Flow Draft | `DRAFT` | 可修改；fingerprint 随业务内容变化 |
| Reusable Flow Version | `AVAILABLE` / `DEPRECATED` / `DISABLED` | 不可变 |
| Fixture Set 内容 | revision + fingerprint + ETag | Case 与控制变化递增内容 revision |
| Fixture Set 治理 | `PRIVATE_DRAFT` / `SHARING_PENDING` / `TEAM_AVAILABLE` / `STALE` / `REVOKED` | 只递增 `statusRevision` |
| Simulation Run | 不可变 | 重跑创建新 run |

Scope 固定为认证身份派生的 tenant、project、environment。跨 Scope 对象不可见；
引用不可见对象时统一返回 Not Found，不泄露存在性。

## 10. 兼容与迁移

新对象页只写新 Module，不与旧创作接口双写。

| 新对象 | 现有实现 | 迁移策略 |
| --- | --- | --- |
| Connection | API Connection authority、schema readiness、commit seam、secret provider seam | 继续完成 Store、Secret 激活和 Facade |
| API Resource | API Resource authority、commit store、projection compiler | 旧 Descriptor + Contract 只保留为投影 |
| Reusable Flow | GraphDraft、bindings、edges、publication | 可表达图服务端 lowering；高级图保留 Legacy-only |
| Fixture Set | NodeFixture、Scenario dependency、Fixture Asset、Governance | 按可运行 Case 迁移；受保护 material 复用现有 Catalog |
| Simulation Run | Visual simulation、kernel、capture evidence、correctness evidence | 新 Facade 编译到既有内核，不重写执行内核 |

迁移规则：

- 旧对象保持可读、可运行，不做破坏性删除。
- 孤儿 Descriptor / Contract / Draft 标记 `NEEDS_REPAIR`，不静默补全。
- 高级图无法 lowering 时保留原始字节和 `legacyOnly` 标记。
- 普通作者入口迁移完成前，旧入口保持可用但不再新增能力。

## 11. 实施切片

以下切片只在本文审批后开始。

### Slice A：Authoring Facade 与 API Resource 闭环

范围：

1. 完成 Authoring Facade 的强 ETag、Idempotency、Scope 和统一 Problem Detail。
2. 打通 Connection + API Resource + Default Fixture 的原子保存。
3. 暴露统一 Catalog 的 API Resource 视图。
4. 提供新 API Resource 对象页和向导。

完成定义：

- 新建 Connection + Resource + Example Fixture 能在一次可见保存后进入对象页。
- H2 PostgreSQL mode 通过；如共享环境可用，再跑真实 PostgreSQL lane。
- Secret、保留 Header、投影失败、CAS 失配和 Scope 隔离都有负例。

### Slice B：单 API Resource Fixture 与模拟

范围：

1. 实现 Fixture Set 保存、按 Subject 查询和私有 Case 运行。
2. 实现 `/api/authoring/simulations` 到既有执行内核的 lowering。
3. 返回不可变 `SimulationRun` 和节点 Evidence。
4. 完成前端 Fixture / Simulation Workspace。

完成定义：

- 不配置真实外部系统即可完成首次模拟。
- 默认外部读 / 写拒绝，显式授权读和拒绝路径都有测试。
- 页面重载通过精确 Fixture summary 恢复，不把 material 写入 localStorage。

### Slice C：Reusable Flow、发布与复用

范围：

1. 实现 Flow Draft 保存、Mapping lowering、DAG 校验和统一 Catalog。
2. 实现 Flow Fixture 的整体输入和节点控制。
3. 实现 Flow 模拟。
4. 实现发布和父 Flow 复用。

完成定义：

- 两个 API Resource 组成 Tool / Solution，发布后在父 Flow 中复用并再次模拟。
- Draft 变更不影响旧 Version 和旧 Fixture。
- 环、未知节点、未知 port、重复 ID 和 Schema 不兼容都有负例。

### Slice D：Fixture 共享与治理分离

范围：

1. 实现 Share 命令、敏感值扫描、受保护 material 转换。
2. 接入独立 Review Queue 的 Verify、Approve、Activate、Revoke。
3. 作者页只显示只读治理状态。

完成定义：

- 作者和评审者使用不同身份访问同一精确 revision。
- 内容 revision 和状态 revision 并发变化都有 CAS 负例。
- Subject fingerprint 漂移后团队 Fixture 显示 `STALE`。

### Slice E：旧入口迁移与默认切换

范围：

1. 迁移 Descriptor + Contract 成对数据。
2. 迁移可表达的 GraphDraft。
3. 迁移 NodeFixture 和 governed reference。
4. 新对象页成为普通作者默认入口。

完成定义：

- 迁移覆盖率、失败隔离、幂等重放和旧对象继续运行都有证据。
- 普通作者四条任务全部由新 HTTP 面完成。

## 12. 测试设计

### 契约测试

每个 JSON Schema 至少覆盖最小合法、完整合法、缺字段、未知字段、非法枚举、超长和版本不支持样例。
公共 Subject、Header denylist、Problem Detail 和 fingerprint 规则放入共享矩阵。

### Module 测试

| Module | 必测行为 |
| --- | --- |
| Connection | HTTPS、Header 冲突、Secret 只写、lease / activate / fail、CAS、跨 Scope 不可见 |
| ApiResource | 复合保存、三投影 READY、指纹绑定、并发 CAS、失败清理、保留 Header 拒绝 |
| ReusableFlow | Mapping lowering、环、未知节点、Schema 兼容、layout 不参与业务指纹、Publish CAS |
| FixtureSet | Subject 闭包、Node / Subject 控制、精确 Case 匹配、material 保护、内容 / 状态 CAS |
| Simulation | 默认拒绝、显式授权、真实 / Mocked Evidence、Schema 校验、Assertions、Governance 不越权 |

### 前端与浏览器测试

前端组件测试只断言用户可见状态和提交 wire，不测内部领域字段。
保存成功必须由真实 transport Receipt 驱动。

浏览器验收按 1280 宽度连续执行：

1. 新建或选择 Connection；
2. 保存 API Resource；
3. 从 Example 保存私有 Fixture；
4. 模拟并断言 Evidence；
5. 新建 Tool 或 Solution；
6. 添加两个 API Resource，或一个已发布 Flow；
7. 保存 Mapping 和 DAG；
8. 设置整体或节点 Fixture；
9. 模拟并断言节点来源和四维 Verdict；
10. 发布 Flow Version；
11. 在新父 Flow 中复用；
12. 再次模拟并断言精确 Draft、Run 和 usage 证据。

验收不允许注入 WebDriver state、伪造 cookie、手写 repository 数据或跳过可见保存 / 审批动作。

## 13. 审批决策

请逐项确认：

1. 接受五个对象和三个工作区作为普通作者的固定心智模型。
2. 接受 `ApiResourceSpec` 为唯一 API Resource 权威，Descriptor、Design Contract 和 Operator 只作为投影。
3. 接受 Tool 与 Solution 第一阶段共用 `ReusableFlowDraft`，第一版只支持有界同步数据流 DAG。
4. 接受 API Resource 和 Flow 共用 `FixtureSet`，以 `ExactSubjectRef` 区分层级。
5. 接受保存后再模拟，模拟请求不携带 transient `GraphDraft`。
6. 接受默认外部读拒绝、外部写永远拒绝。
7. 接受私有 Fixture 直接保存模拟，团队 Fixture 走独立治理队列。
8. 接受 Slice A 作为审批后的第一个实施切片。

未获得明确审批前，本方案不授权生产代码变更。
