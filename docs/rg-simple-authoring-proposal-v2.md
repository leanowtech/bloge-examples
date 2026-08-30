# Resource Gateway Simple Authoring Proposal v2

> Status: Proposed for review. This document requests approval before implementation.
>
> Date: 2026-08-30.
>
> Scope: external API onboarding, API-resource fixtures and simulation, multi-resource DAG authoring, reusable Tool/Solution assets, and Tool/Solution fixtures and simulation.
>
> Relationship to v1: `rg-api-fixture-reusable-flow-authoring-proposal-v1.md` remains the detailed governance and contract baseline. This v2 narrows the first deliverable to one simple golden path. v1 advanced controls remain available behind Advanced mode and are not duplicated here.

## 1. Review summary

The product goal is four ordinary user actions:

1. Add an external API.
2. Give that API resource a fixture and simulate it.
3. Compose several API resources into a reusable Tool or Solution DAG.
4. Give the Tool/Solution a fixture and simulate it.

v2 makes four names the product model:

| User object | Meaning | Key invariant |
| --- | --- | --- |
| API Resource | One callable external API operation | One saved spec produces descriptor, contract, operator, and fixture metadata |
| Flow | A reusable DAG named Tool or Solution | Nodes and mappings are one model; Tool and Solution differ only by `kind` |
| Fixture Set | One reusable set of run cases | A case always belongs to one exact subject revision |
| Simulation Run | A saved, replayable simulation | It references subject + fixture revision; it never submits a transient GraphDraft |

The first release must optimize first-task success, not protocol completeness. Therefore:

- The default UI has four pages: APIs, Flows, Fixtures, Runs.
- The default flow is **save -> fixture -> simulate**. There is no inline lifecycle review, expression editor, or descriptor concept.
- Tool and Solution share one execution schema. They differ in label, description, catalog filter, and review profile, not runtime shape.
- Private fixtures can run without governance review. Sharing a fixture to the team enters the existing review flow in a separate Review Queue.
- Simulation denies external reads and writes by default. Real reads require explicit, exact-resource authorization; simulated writes remain denied in v2.

## 2. Target operating loop

```text
External API
  -> API Resource Draft
  -> Default Fixture Set
  -> API Simulation Run

API Resource 1 + API Resource 2 + API Resource N
  -> Flow Draft (Tool or Solution)
  -> Node Fixture Set or Whole-flow Fixture Set
  -> Flow Simulation Run
  -> Published Flow Version
  -> Reuse inside another Flow
```

A user should be able to complete each numbered path without learning these implementation words: `ResourceDescriptor`, `ResourceDesignContract`, `GraphDraft`, `NodeFixture`, `ResponseProtocol`, `ctx`, ` lowering`, ` four-eyes`, or `CAS`. They remain backend concepts.

## 3. Structural decisions

### D1. One API Resource authority

`ApiResourceSpec` is the only write authority for one external API operation. The existing descriptor, design contract, and visual operator become server-side projections compiled from the spec.

This removes the current frontend pattern of:

1. PUT descriptor,
2. PUT design contract,
3. GET operator catalog.

The user clicks one Save button. The backend creates one immutable revision and returns one receipt.

### D2. One Flow model

`ReusableFlowSpec` represents both Tool and Solution:

- `kind: TOOL` means a compact, directly callable logic fragment.
- `kind: SOLUTION` means a larger business flow assembled from tools and API resources.

Both use nodes, mappings, input schema, output schema, fixtures, simulation, and publication. v2 does not create two runtimes.

### D3. Mapping is the edge

A DAG edge is not stored as an independent business object. A mapping like this already defines the dependency:

```json
{
  "targetPath": "orderId",
  "source": {
    "kind": "NODE_OUTPUT",
    "nodeId": "get-order",
    "path": "id"
  }
}
```

The canvas renders a line from `get-order` to the target node because of this mapping. Deleting the mapping deletes the visual edge.

### D4. Fixture Set is uniform

One Fixture Set model covers:

- one API resource,
- one Tool/Solution draft,
- one published Tool/Solution version.

A case contains input, optional controls, and optional expected output. A control can return a value, return an error, simulate timeout, apply another exact fixture case, or replay a saved run.

### D5. Simulation uses saved coordinates

A simulation request contains:

1. exact subject revision,
2. fixture source or ad-hoc input,
3. execution policy.

It does not contain a full frontend-authored graph. This keeps run lineage stable and prevents frontend model drift from becoming backend execution truth.

### D6. Private and team fixtures are different products

| Fixture state | User meaning | Review requirement |
| --- | --- | --- |
| `PRIVATE_DRAFT` | My debugging data | None |
| `SHARING_PENDING` | Requested team sharing | Existing governed review |
| `TEAM_AVAILABLE` | Team may select it | Review approved |
| `STALE` | Subject revision changed | Cannot run until re-bound |
| `REVOKED` | Removed from team catalog | Read-only history |

The author page shows only Save Fixture and Share with Team. Approve, verify, redaction, retention, and material review move to Review Queue.

## 4. Frontend schemas

These TypeScript shapes are editor models. They are not the backend wire format and never contain secrets after submit.

### 4.1 Common display references

```ts
type ExactSubjectRef =
  | { kind: 'API_RESOURCE'; resourceId: string; revision: number }
  | { kind: 'FLOW_DRAFT'; flowId: string; revision: number }
  | { kind: 'FLOW_VERSION'; publicationId: string; revision: number };

interface SchemaEnvelope {
  format: 'json-schema';
  version: '2020-12';
  schema: Record<string, unknown>;
}
```

The UI may display `resourceId@revision`; fingerprint stays backend-owned and is shown only as a short receipt identity.

### 4.2 API Resource editor

```ts
type ApiInputLocation = 'PATH' | 'QUERY' | 'HEADER' | 'BODY';

interface ApiResourceEditor {
  displayName: string;
  description?: string;
  connection:
    | { mode: 'SELECT'; connectionId: string }
    | {
        mode: 'CREATE';
        name: string;
        baseUrl: string;
        auth:
          | { kind: 'NONE' }
          | { kind: 'BEARER'; tokenDraft: string }
          | { kind: 'BASIC'; username: string; passwordDraft: string }
          | { kind: 'API_KEY'; headerName: string; valueDraft: string };
      };
  operation: {
    method: 'GET' | 'POST' | 'PUT' | 'DELETE';
    path: string;
  };
  inputs: Array<{
    name: string;
    location: ApiInputLocation;
    type: 'string' | 'integer' | 'number' | 'boolean' | 'object';
    required: boolean;
  }>;
  success:
    | { mode: 'HTTP_2XX' }
    | { mode: 'HTTP_CODES'; codes: number[] }
    | { mode: 'BODY_MATCH'; path: string; values: Array<string | number | boolean> };
  output:
    | { mode: 'FROM_RESPONSE_EXAMPLE' }
    | { mode: 'FIELDS'; fields: Array<{ name: string; type: string; required: boolean }> }
    | { mode: 'JSON_SCHEMA'; schemaText: string };
  examples: Array<{
    name: string;
    request: unknown;
    response: unknown;
  }>;
  createDefaultFixture: boolean;
}
```

Default rules:

- `GET` is read-only and may be explicitly authorized for real read simulation.
- `POST`, `PUT`, and `DELETE` are fixture-only in simulation.
- `FROM_RESPONSE_EXAMPLE` infers output schema from saved response examples.
- `createDefaultFixture: true` creates one private case from the first saved request/response example.

### 4.3 Flow editor

```ts
type ComposableRef =
  | { kind: 'API_RESOURCE'; resourceId: string; revision: number }
  | { kind: 'FLOW_VERSION'; publicationId: string; revision: number };

type MappingSource =
  | { kind: 'FLOW_INPUT'; path: string }
  | { kind: 'NODE_OUTPUT'; nodeId: string; path: string }
  | { kind: 'CONSTANT'; value: unknown };

interface FlowEditor {
  displayName: string;
  kind: 'TOOL' | 'SOLUTION';
  description?: string;
  inputSchema: SchemaEnvelope;
  outputSchema: SchemaEnvelope;
  nodes: Array<{
    nodeId: string;
    label: string;
    use: ComposableRef;
    mappings: Array<{
      targetPath: string;
      source: MappingSource;
    }>;
    position: { x: number; y: number };
  }>;
  output: {
    nodeId: string;
    path: string;
  };
}
```

Canvas behavior:

- selecting a node opens three sections only: Inputs, Fixture, Output;
- dragging a connection creates or edits one `NODE_OUTPUT` mapping;
- duplicate target paths and dependency cycles are rejected at save;
- `position` affects layout, not content fingerprint.

### 4.4 Fixture Set editor

```ts
type FixtureBehavior =
  | { kind: 'REAL' }
  | { kind: 'RETURN'; output: unknown }
  | { kind: 'ERROR'; code: string; message: string }
  | { kind: 'TIMEOUT'; afterMs: number }
  | { kind: 'APPLY_CASE'; fixtureSetId: string; revision: number; caseId: string }
  | { kind: 'REPLAY'; replayId: string; fingerprint: `sha256:${string}` };

type FixtureFidelity =
  | 'OUTPUT_LEVEL'
  | 'PROTOCOL_DERIVED'
  | 'TRANSPORT_LEVEL';

interface FixtureSetEditor {
  displayName: string;
  subject: ExactSubjectRef;
  cases: Array<{
    caseId: string;
    name: string;
    input: unknown;
    controls: Array<{
      target:
        | { kind: 'SUBJECT' }
        | { kind: 'NODE'; nodeId: string };
      behavior: FixtureBehavior;
      fidelity?: FixtureFidelity;
    }>;
    expectedOutput?: unknown;
  }>;
}
```

Default mode:

- API Resource case: one input plus optional expected output.
- Flow case: flow input plus one control per external or published-flow node.
- Schema-driven forms are default; JSON text editors are Advanced.
- `REAL` is allowed only under explicit simulation policy and never for external writes.

### 4.5 Simulation panel

```ts
type SimulationPanel = {
  source:
    | { kind: 'AD_HOC'; subject: ExactSubjectRef; input: unknown }
    | {
        kind: 'FIXTURE_CASE';
        fixtureSetId: string;
        revision: number;
        caseId: string;
      };
  executionPolicy: {
    externalReads:
      | { kind: 'DENY' }
      | { kind: 'ALLOW_EXACT'; resources: ExactSubjectRef[]; reason: string };
    externalWrites: { kind: 'DENY' };
  };
};
```

The Run button is disabled until the subject and fixture case are valid. The result page always shows:

```text
Result: SUCCEEDED | FAILED | CANCELLED
Nodes: mocked / real / skipped
Output: rendered schema form
Diagnostics: stable codes and safe messages
Run ID: replayable coordinate
```

## 5. Backend wire schemas

### 5.1 Common envelope

All objects use the same coordination fields:

```json
{
  "schemaVersion": "bloge.<object>.v1",
  "tenantId": "tenant-128",
  "projectId": "project-128",
  "environmentId": "local",
  "objectId": "stable-id",
  "revision": 1,
  "fingerprint": "sha256:..."
}
```

Rules:

- `revision` starts at 1 and increases only on accepted save.
- `fingerprint` covers canonical semantic content, not layout, UI state, or audit fields.
- Updates require the expected revision. A mismatch returns stable `CAS_MISMATCH`.
- Scope is mandatory on write and read; cross-scope IDs return `NOT_FOUND`, not existence leakage.

### 5.2 API Resource Spec

The existing Java `ApiResourceSpec` remains the contract base:

```json
{
  "schemaVersion": "bloge.apiResourceSpec.v1",
  "resourceId": "create-order",
  "revision": 1,
  "fingerprint": "sha256:...",
  "displayName": "Create order",
  "connectionId": "sales-api",
  "operation": {
    "method": "POST",
    "path": "/orders",
    "inputs": [
      { "name": "customerId", "location": "BODY", "type": "string", "required": true },
      { "name": "amount", "location": "BODY", "type": "number", "required": true }
    ]
  },
  "contract": {
    "input": { "format": "json-schema", "version": "2020-12", "schema": {} },
    "output": { "format": "json-schema", "version": "2020-12", "schema": {} }
  },
  "response": {
    "success": { "kind": "HTTP_2XX" },
    "outputPath": "$"
  },
  "effect": { "kind": "FIXTURE_ONLY_WRITE" },
  "examples": [
    {
      "name": "standard",
      "request": { "customerId": "c-1", "amount": 10.5 },
      "response": { "id": "o-1", "status": "CREATED" }
    }
  ],
  "status": "DRAFT"
}
```

The derived projections are:

| Projection | Purpose |
| --- | --- |
| Runtime resource descriptor | HTTP transport and operator execution |
| Design contract | Visual palette and graph schema compatibility |
| Operator catalog item | User-facing reusable node |

All three projections persist the same resource revision and fingerprint. A partial projection set is not visible as a usable API resource.

### 5.3 Reusable Flow Spec

```json
{
  "schemaVersion": "bloge.reusableFlowSpec.v1",
  "flowId": "order-intake",
  "revision": 3,
  "fingerprint": "sha256:...",
  "kind": "TOOL",
  "displayName": "Order intake",
  "inputSchema": { "format": "json-schema", "version": "2020-12", "schema": {} },
  "outputSchema": { "format": "json-schema", "version": "2020-12", "schema": {} },
  "nodes": [
    {
      "nodeId": "validate-customer",
      "label": "Validate customer",
      "use": {
        "kind": "API_RESOURCE",
        "resourceId": "get-customer",
        "revision": 2,
        "fingerprint": "sha256:..."
      },
      "mappings": [
        { "targetPath": "customerId", "source": { "kind": "FLOW_INPUT", "path": "customerId" } }
      ]
    },
    {
      "nodeId": "create-order",
      "label": "Create order",
      "use": {
        "kind": "API_RESOURCE",
        "resourceId": "create-order",
        "revision": 1,
        "fingerprint": "sha256:..."
      },
      "mappings": [
        { "targetPath": "customerId", "source": { "kind": "NODE_OUTPUT", "nodeId": "validate-customer", "path": "id" } },
        { "targetPath": "amount", "source": { "kind": "FLOW_INPUT", "path": "amount" } }
      ]
    }
  ],
  "output": { "nodeId": "create-order", "path": "$" },
  "status": "DRAFT"
}
```

Publication creates a separate immutable `FLOW_VERSION`. A published version can be selected by another flow. Drafts cannot be composed directly into another reusable asset.

### 5.4 Fixture Set Spec

```json
{
  "schemaVersion": "bloge.fixtureSetSpec.v1",
  "fixtureSetId": "order-intake-standard",
  "revision": 2,
  "fingerprint": "sha256:...",
  "displayName": "Standard order intake",
  "subject": {
    "kind": "FLOW_DRAFT",
    "flowId": "order-intake",
    "revision": 3,
    "fingerprint": "sha256:..."
  },
  "status": "PRIVATE_DRAFT",
  "cases": [
    {
      "caseId": "normal-customer",
      "name": "Normal customer",
      "input": { "customerId": "c-1", "amount": 10.5 },
      "controls": [
        {
          "target": { "kind": "NODE", "nodeId": "validate-customer" },
          "behavior": { "kind": "RETURN", "output": { "id": "c-1", "valid": true } },
          "fidelity": "PROTOCOL_DERIVED"
        },
        {
          "target": { "kind": "NODE", "nodeId": "create-order" },
          "behavior": { "kind": "RETURN", "output": { "id": "o-1", "status": "CREATED" } },
          "fidelity": "OUTPUT_LEVEL"
        }
      ],
      "expectedOutput": { "id": "o-1", "status": "CREATED" }
    }
  ]
}
```

Fixture invariants:

- subject revision and fingerprint are exact;
- a subject change makes the fixture `STALE`; it is not silently rebound;
- a case ID is unique inside one Fixture Set revision;
- `APPLY_CASE` must resolve to a visible fixture of the same tenant/project/environment;
- protected material and credentials remain server-owned and are never returned to the browser.

### 5.5 Simulation Request and Run

```json
{
  "schemaVersion": "bloge.simulationRequest.v1",
  "source": {
    "kind": "FIXTURE_CASE",
    "fixtureSetId": "order-intake-standard",
    "revision": 2,
    "caseId": "normal-customer"
  },
  "executionPolicy": {
    "externalReads": { "kind": "DENY" },
    "externalWrites": { "kind": "DENY" }
  }
}
```

The response is a run coordinate, not a synchronous payload dump:

```json
{
  "schemaVersion": "bloge.simulationRun.v1",
  "runId": "sim-128",
  "status": "SUCCEEDED",
  "subject": {
    "kind": "FLOW_DRAFT",
    "flowId": "order-intake",
    "revision": 3,
    "fingerprint": "sha256:..."
  },
  "fixtureSet": {
    "fixtureSetId": "order-intake-standard",
    "revision": 2,
    "fingerprint": "sha256:..."
  },
  "summary": {
    "compiled": true,
    "mockedNodes": ["validate-customer", "create-order"],
    "realNodes": [],
    "output": { "id": "o-1", "status": "CREATED" }
  },
  "diagnostics": []
}
```

Diagnostics use one stable shape:

```json
{
  "code": "RG.AUTHORING.SCHEMA_MISMATCH",
  "severity": "ERROR",
  "message": "Node 'create-order' cannot accept the connected output.",
  "subjectPath": "nodes.create-order.mappings.customerId"
}
```

## 6. Minimum HTTP surface

v2 adds a small authoring facade; existing admin and visual APIs remain temporarily compatible.

| Method and path | User action |
| --- | --- |
| `POST /api/authoring/connections` | Create or update connection safely |
| `POST /api/authoring/api-resources/{id}` | Save API Resource and projections |
| `GET /api/authoring/api-resources/{id}` | Load editor |
| `POST /api/authoring/flows/{id}` | Save Flow draft |
| `POST /api/authoring/flows/{id}/publish` | Create Flow Version |
| `GET /api/authoring/composables` | List API Resources and Flow Versions |
| `POST /api/authoring/fixture-sets/{id}` | Save private Fixture Set |
| `POST /api/authoring/fixture-sets/{id}/share` | Request team sharing |
| `POST /api/authoring/simulations` | Start simulation |
| `GET /api/authoring/simulations/{runId}` | Load run result |

Every mutating endpoint returns the same receipt structure:

```json
{
  "objectId": "order-intake",
  "revision": 3,
  "fingerprint": "sha256:...",
  "status": "DRAFT",
  "warnings": []
}
```

## 7. Mapping to the current implementation

| v2 concept | Current implementation | Migration action |
| --- | --- | --- |
| `ApiResourceSpec` | New pure domain module and scoped commit protocol already exist | Complete JDBC persistence and compile three projections server-side |
| External API form | Still writes descriptor and contract separately | Replace transport with one authoring save endpoint |
| `ReusableFlowSpec` | `GraphDraft` plus visual layout and bindings | Compile Flow -> GraphDraft; do not expose GraphDraft as user schema |
| Mapping | Node bindings plus graph edges | Generate edges from mappings during projection |
| Fixture Set | Node fixtures, scenario dependencies, fixture assets, governed material | Compile Fixture Set into the existing runtime forms |
| Simulation | Graph simulation, governed fixture adapter, capture evidence | Compile exact subject into backend execution input; retain trace/evidence IDs |
| Review | Inline fixture lifecycle controls | Move review actions to Review Queue and keep existing service rules |

The current backend API-resource work is useful, but it is not yet user-visible. It must not be wired to the old external API form as a third save path.

## 8. Implementation slices

### Slice A: API Resource golden path

Deliver:

1. production persistence for `ApiResourceSpec`;
2. server-side descriptor, contract, and operator projections;
3. one External API save endpoint;
4. automatic private default fixture from the first response example;
5. API Resource fixture simulation with external calls denied.

Acceptance:

- save succeeds with one user action;
- reload shows the same resource;
- default fixture appears immediately;
- simulate returns output without a real network call;
- descriptor, contract, and operator all report the same revision.

### Slice B: Flow golden path

Deliver:

1. `ReusableFlowSpec` save and load;
2. palette containing API Resources and published Flow Versions;
3. Mapping-to-DAG projection and cycle detection;
4. node fixture editing;
5. Flow simulation;
6. publish and reuse a Flow Version.

Acceptance:

- a two-API Tool can be created, fixture-simulated, published, and reused in a second Tool;
- deleting a mapping removes the edge;
- changing an upstream revision blocks stale composition until the user explicitly reselects it;
- a whole-flow fixture case runs without editing node JSON.

### Slice C: Fixture sharing and review separation

Deliver:

1. private Fixture Set persistence;
2. Share with Team command;
3. Review Queue consuming the existing governed fixture review services;
4. stale-state display when subject revision changes;
5. payload-free team fixture summaries.

Acceptance:

- a private fixture runs without review;
- a shared fixture runs only after existing approval rules pass;
- reviewers never require the author's browser context;
- list and review views do not expose protected material or credentials.

### Slice D: Migration and default entry switch

Deliver:

1. descriptor-plus-contract migration to `ApiResourceSpec`;
2. GraphDraft migration to `ReusableFlowSpec` where the model is expressible;
3. legacy-only graphs marked Advanced and excluded from the default palette;
4. old deep links retained read-only;
5. metrics for first-save success, first-simulation latency, CAS recovery, projection drift, and run outcomes.

Acceptance requires a real browser chain and focused backend suites. A component test or stale green count does not close a slice.

## 9. Test design

### Schema tests

For every wire schema, provide:

- minimal valid;
- complete valid;
- unknown field rejected;
- missing required field rejected;
- wrong discriminator rejected;
- cross-reference and scope mismatch rejected;
- CAS stale rejected.

### Module tests

`ApiResourceModule` proves one save creates one immutable revision and three rebuildable projections. `ReusableFlowModule` proves mapping-only dependencies, cycle rejection, exact-version reuse, and layout-independent fingerprint. `FixtureSetModule` proves subject closure, node targets, case composition, and stale behavior. `SimulationModule` proves deny-by-default, exact fixture selection, mocked/real accounting, replay identity, and safe diagnostics.

### Browser acceptance

One real-browser scenario should cover the whole user loop:

1. add API A and API B;
2. simulate API A with a default fixture;
3. create a Tool that composes A and B;
4. add node fixtures and run it;
5. publish the Tool as version 1;
6. create a Solution that uses Tool v1 plus API B;
7. apply a whole-flow fixture and run it;
8. reload and confirm every object, fixture, and run coordinate is recoverable;
9. rerun the same fixture and prove idempotent usage accounting.

The browser test must use visible controls only. It must not inject state, switch identities in local memory, or bypass governance rules.

## 10. Explicit non-goals for v2

- No cycles, compensation, long-running wait states, or human approval nodes in the default DAG.
- No real external writes during simulation.
- No browser-held secrets or protected fixture material.
- No generic `Artifact` model replacing the four typed objects.
- No separate Tool and Solution runtimes.
- No removal of existing admin or visual APIs before migration evidence exists.
- No new inline governance controls on author pages.

## 11. Approval requested

Please approve or amend these five decisions:

1. **R1:** `ApiResourceSpec` is the only API-resource write authority; descriptor and contract become projections.
2. **R2:** Tool and Solution share one `ReusableFlowSpec` and one runtime in v2.
3. **R3:** Default authoring requires save-before-simulate; simulation references exact saved revisions.
4. **R4:** Private fixtures run without governance; team sharing enters the existing review queue.
5. **R5:** The first golden path is API Resource -> Default Fixture -> Simulation, followed by Flow -> Fixture -> Simulation -> Publish.

If approved, the next implementation step is Slice A only. Slice B does not start until Slice A has backend persistence, UI save, default fixture, simulation, focused tests, and a real-browser acceptance receipt.
