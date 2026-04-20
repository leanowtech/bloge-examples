# bloge-graph-engine-server

> This module is part of the
> [standalone graph-engine project](../README.md) and is built with Java 25
> outside the root bloge reactor.

Spring Boot control-plane HTTP server for the BLOGE graph-engine product layer. Wraps `bloge-graph-engine-service` with REST endpoints for graph definitions, versions, deployments, instances, human tasks, and remote workers.

## How It Boots

`GraphEngineServerApplication` is the standalone entry point. It uses `@SpringBootConfiguration` + `@EnableAutoConfiguration` (no component scanning) so the same controller/service wiring works both standalone and as an embedded library.

Boot's default `FlywayAutoConfiguration` is **excluded** because the BLOGE runtime (`bd_*` tables) and graph-engine (`ge_*` tables) modules manage their own Flyway migrations internally.

```bash
# From bloge-examples-graph-engine/
mvn -pl server spring-boot:run
```

The standalone server ships with `spring.bloge.durable.mode=local` in its default
`application.yml`, so local runs boot against an in-memory H2 durable store unless you override
the standard `spring.bloge.durable.*` properties. That preset is intended for local development
and integration testing only.

## REST Endpoint Groups

All endpoints are under `/api/v1`. Tenant and namespace scope are resolved per-request via `TenantContextHolder` (see `bloge-spring` tenant configuration).

| Prefix | Controller | Purpose |
|---|---|---|
| `/api/v1/graphs` | `GraphDefinitionController` | CRUD and archive for graph definitions |
| `/api/v1/graphs/{key}/versions` | `GraphVersionController` | Create draft versions, validate, publish into the runtime, deprecate published versions, and compare versions via diff |
| `/api/v1/deployments` | `GraphDeploymentController` | Create and update deployment bindings (environment, routing policy) |
| `/api/v1/graphs/{key}/instances` | `GraphInstanceController` | Start new instances for a definition |
| `/api/v1/instances` | `GraphInstanceController` | Query, signal, cancel, terminate, audit, inspect transition history, and list pending GRAPH signals for instances |
| `/api/v1/tasks` | `GraphTaskController` | Query, claim, complete, reassign, and cancel human tasks, including candidate user/group/role claim metadata |
| `/api/v1/dead-letters` | `GraphDeadLetterController` | Query tenant-scoped dead letters and retry individual work items |
| `/api/v1/remote-workers` | `GraphRemoteWorkerController` | Register workers, poll and claim jobs, renew leases, and report completion or failure for remote-worker items |
| `/api/v1/operators` | `GraphOperatorInventoryController` | Query registered operators with metadata, schema, and cross-definition usage statistics |
| `/api/v1/ai` | `GraphAuthoringController` | Validate raw DSL and generate draft graphs from natural language via the AI authoring pipeline |
| `/api/v1/import/bpmn` | `BpmnImportController` | Translate BPMN 2.0 XML or JSON into BLOGE DSL source via the single-direction import pipeline |

### Governance and lifecycle

The governance endpoints expose runtime lifecycle control and observability on
top of the durable BLOGE substrate.

**Cancel vs. terminate.** Both instance actions require an optimistic-lock
`expectedRevision` plus a human-readable `reason` in the
`LifecycleActionRequest` payload. For graph and state-machine executions, the
service updates the durable execution status, cancels active timers, deletes
pending waits, bulk-cancels active work items, and closes open human tasks
before refreshing the product-layer instance projection.

For **session-mode** instances, both actions delegate to
`DurableSessionManager.terminate(...)`. The product layer preserves the semantic
difference by encoding cancel reasons with a `__graph_engine_cancel__:` prefix
inside the stored session termination marker so later reads can project
`CANCELLED` instead of `TERMINATED`.

### Version diff

`GET /api/v1/graphs/{key}/versions/{left}/diff/{right}` compares two semantic
versions of the same definition and returns a `GraphVersionDiff` containing:

- identifying metadata for both sides (`VersionSummary`)
- validation summary fields for both sides (`valid`, `errorCount`,
  `warningCount`)
- a `sourceEqual` flag derived from the content hashes
- a line-oriented unified diff of the DSL source when sources differ
- a `MetadataDiff` summarising structural changes to execution mode, operator
  references, operator fingerprints, input/output schema compatibility, and
  task definitions

**Audit and transition history.** `GET /api/v1/instances/{id}/audit` returns
`GraphAuditEntry` records projected from the durable audit journal for GRAPH and
STATE_MACHINE instances, and from SESSION round history for session-mode
instances. Each completed session round is exposed as a `NODE_COMPLETE` audit
entry with the phase identifier as `nodeId`; in SESSION mode the
`retryAttempt` field carries the 1-based within-phase round ordinal rather than
retry-failure count semantics. `GET /api/v1/instances/{id}/transitions`
returns `GraphTransitionEntry` records projected from the durable execution
transition log. Session-mode transition queries use a control-plane-first
strategy: when control-plane rows exist for `page=0`, subsequent pages stay on
that authoritative source. Checkpoint synthesis is only a first-page fallback;
later pages return empty instead of re-synthesizing a duplicate full-history
projection. Within synthesized chains, each `SUSPENDED -> RUNNING` resume entry
uses the previous round's completion time as the earliest observable next-round
start. Synthesized entries are labelled with
`transitionSource = "session-checkpoint-synthesis"`.

**Pending signals.** `GET /api/v1/instances/{id}/pending-signals` returns
`GraphPendingSignal` entries for suspended `GRAPH` instances by joining durable
event matchers (capped at 10,000 per query) with signal waits. Each entry
includes the waiting node, event name, correlation key/value, an `optional`
flag indicating whether the runtime treats the matcher as non-blocking, matcher
creation timestamp, optional timeout, and the compiled await-node
`signalSchema` when the stored definition/version can still be compiled.
Non-suspended graph instances return an empty list; session and state-machine
instances return `UNSUPPORTED_EXECUTION_MODE`.

**Instance context.** `GET /api/v1/instances/{id}/context` returns a
`GraphInstanceContext` snapshot for all execution modes. The response always
includes the original start variables. GRAPH instances also expose decoded
durable node outputs; SESSION instances expose shared state and phase outputs
from the freshest active or durable session snapshot, and an active in-memory
snapshot short-circuits the durable checkpoint query when present.
STATE_MACHINE instances expose shared context and state outputs from the
durable state-machine checkpoint. Missing checkpoints degrade to empty maps,
and one malformed graph node-output checkpoint is skipped instead of failing
the entire response.

**Dead-letter replay.** `POST /api/v1/dead-letters/{itemId}/retry` restores one
dead-lettered work item back to `READY` through `WorkItemStore.restoreDeadLetter(...)`.
When the durable runtime facade is configured, the server then triggers a
dispatch cycle so the item re-enters the normal ready → claim → execute flow.

**Node execution view.** `GET /api/v1/instances/{id}/nodes` returns the inferred
execution state of every execution node in a running instance. GRAPH instances
project DAG nodes from durable checkpoints, waits, and work-item status. SESSION
instances project phases from the freshest active or durable session snapshot,
with active snapshots short-circuiting checkpoint I/O when present.
STATE_MACHINE instances project states from the durable state-machine
checkpoint. The shared `GraphNodeState` DTO keeps one response shape across all
three execution modes, while leaving `operatorRef` and `waitType` empty for the
non-GRAPH projections.

The endpoint accepts optional status filtering and pagination query parameters:

| Parameter | Default | Description |
|---|---|---|
| `status` | *(all)* | Comma-separated `GraphNodeStatus` values such as `RUNNING,WAITING` |
| `page` | `0` | Zero-based page index |
| `size` | `50` | Page size |

Responses are wrapped in `PagedResult<GraphNodeState>` (`items`, `page`, `size`,
`total`). Projection still derives the full node set in memory, then applies
status filtering and offset/limit truncation before serialization. For
GRAPH-mode instances, work-item loading is bounded to `graph.nodes().size() * 3`
so retry-heavy executions do not trigger unbounded work-item scans.

**Diagram APIs.**

- `GET /api/v1/graphs/{key}/versions/{version}/diagram` returns the stored
  `visualLayout` payload for one semantic version exactly as persisted (no JSON
  reparsing or normalization)
- `GET /api/v1/instances/{id}/diagram` returns that same `visualLayout` string
  plus the current `nodeStates` overlay, reusing the exact projection exposed by
  `GET /api/v1/instances/{id}/nodes`

**Instance event stream.** `GET /api/v1/instances/{id}/events` streams
`GraphInstanceEvent` records over `text/event-stream`. The endpoint replays the
current backlog immediately, resumes from the standard `Last-Event-ID` header
without replaying the last delivered sequence, polls the execution journal
every two seconds, and enforces a per-tenant connection limit. For
tenant-scoped or namespace-scoped instances, the stream rejects journal events
whose tenant or namespace metadata is missing or mismatched. It requires
`spring.bloge.event-journal.enabled=true`; when the event journal is disabled
the endpoint fails with `503 RUNTIME_UNAVAILABLE`.

The stream auto-completes when replay or polling observes `GRAPH_COMPLETED`.
That closes the Flux cleanly, releases the SSE connection slot, and prevents
completed instances from consuming the per-tenant
`GraphSseConnectionLimiter` budget indefinitely.

**Instance retry.** `POST /api/v1/instances/{id}/retry` restores all
dead-lettered work items for an instance back to `READY` and triggers a dispatch
cycle. The request body accepts an optional `nodeIds` set to restrict retries to
specific nodes and a required `expectedRevision` for optimistic-lock guarding.
Requires admin RBAC on the owning definition.

### Operator inventory

`GET /api/v1/operators` returns a product-layer view of registered operators.
Each entry includes the operator's registration name, annotation-derived
metadata (description, owner, tags, usage example, constraints), input/output
type names, serialized schema descriptors, and a usage summary showing how many
graph definitions and versions reference the operator in the current scope.

Query parameters:

| Parameter | Default | Description |
|---|---|---|
| `pattern` | `*` | Glob-style operator-name filter (e.g., `payment-*`) |

Example:

```bash
curl http://localhost:8080/api/v1/operators?pattern=validate*
```

### Error Handling

`GlobalExceptionHandler` maps service and store exceptions to structured JSON error responses with stable error codes, HTTP status, timestamp, request path, and optional validation details.

| Error code | HTTP status |
|---|---|
| `NOT_FOUND` | 404 |
| `VALIDATION_FAILED` | 400 |
| `INVALID_STATE` / `DUPLICATE_BUSINESS_KEY` / `CONFLICT` | 409 |
| `RUNTIME_UNAVAILABLE` | 503 |
| `UNSUPPORTED_EXECUTION_MODE` | 501 |
| `ACCESS_DENIED` | 403 |

### RBAC enforcement

The server ships a `CallerContextFilter` servlet filter that populates the
`CallerContextHolder` on every `/api/*` request so the graph-engine service can
enforce RBAC policies.

**Resolution strategy (priority order):**

1. **Spring Security** — extracts role names from the authenticated principal's
   `GrantedAuthority` entries.  `ROLE_` prefixes are stripped automatically.
2. **Request header** — reads comma-separated role names from the
   `X-Graph-Engine-Roles` header (useful for gateway pre-auth or development).
3. **Anonymous** — when neither source yields roles, the filter binds an
   anonymous caller context with no granted roles.

The filter is registered by `GraphEngineServerAutoConfiguration.WebApiConfiguration`
and clears the thread-local in a `finally` block to prevent leaks.

List/query endpoints use that caller context to filter out inaccessible
definitions, versions, deployments, instances, tasks, and dead letters. Direct
lookup or mutation requests still fail fast with `403 ACCESS_DENIED` when the
caller lacks the required role.

### Remote worker protocol

Five endpoints let external workers participate in durable graph execution:

- `POST /api/v1/remote-workers/register` performs stateless discovery and
  returns the active deployment bindings whose `RemoteWorkerBinding` matches the
  worker's `workerId` or `workerTopic`
- `POST /api/v1/remote-workers/{workerTopic}/poll` claims ready `EXECUTE_NODE`
  work items sharded by `workerTopic` and returns the claimed
  `GraphRemoteWorkerJob` payloads with lease token, expiry, and revision
- `POST /api/v1/remote-workers/items/{itemId}/heartbeat` extends the active
  claim lease for one job
- `POST /api/v1/remote-workers/items/{itemId}/complete` resumes the suspended
  graph node with the worker output and returns `204 No Content`
- `POST /api/v1/remote-workers/items/{itemId}/fail` records an error and moves
  the item into `RETRY_WAIT` or `DEAD_LETTER` depending on the remaining retry
  budget, then returns `204 No Content`

### AI authoring endpoints

Two endpoints expose the `bloge-graph-engine-ai` validation and generation pipeline.
Both are draft-only: they return results inline and do not persist any graph artifacts.

- `POST /api/v1/ai/validate` accepts a `ValidateDslRequest` (`dslSource: String`)
  and returns a `DslValidationResult` with parse/lint/compile diagnostics and a
  quality score. No LLM provider is required.
- `POST /api/v1/ai/generate` accepts a `GenerateGraphDraftRequest`
  (`naturalLanguageRequest`, `model`, optional `fewShotExampleCount`,
  `maxRepairRounds`, `temperature`, `maxTokens`) and returns a
  `GraphAuthoringResult` with the final DSL candidate, per-attempt diagnostics,
  and token usage. Requires an `LlmProvider` bean; when none is configured the
  endpoint returns `RUNTIME_UNAVAILABLE`.

The `DslValidationPipeline` bean is always created. The `GraphAuthoringService`
bean is conditional on `@ConditionalOnBean(LlmProvider.class)` — the controller
accepts a `@Nullable` reference and fails fast on `/generate` when absent.

Example draft-generation request:

```bash
curl -X POST http://localhost:8080/api/v1/ai/generate \
  -H 'Content-Type: application/json' \
  -d '{
    "naturalLanguageRequest": "Create a remote image-processing workflow with one review step",
    "model": "gpt-4.1",
    "fewShotExampleCount": 3,
    "maxRepairRounds": 2
  }'
```

### BPMN import

`POST /api/v1/import/bpmn` translates BPMN 2.0 XML **or** a vendor-specific JSON BPMN document into BLOGE DSL source using the `bloge-bpmn-transformer` pipeline. This is a single-direction, stateless migration endpoint — it does not persist any graph artifacts. The generated DSL can be fed directly into `POST /api/v1/graphs/{key}/versions` to create a versioned graph definition.

The endpoint is available when `bloge-bpmn-transformer` is on the classpath (auto-detected via `@ConditionalOnClass`).

Request body (`ImportBpmnRequest`) — exactly one of `bpmnXml` or `bpmnJson` must be provided:

| Field | Required | Default | Description |
|---|---|---|---|
| `bpmnXml` | one of | — | Raw BPMN 2.0 XML content |
| `bpmnJson` | one of | — | Raw JSON BPMN content (vendor-specific interchange format) |
| `strictMode` | no | `false` | Promote warnings to hard errors |
| `generateSourceComments` | no | `true` | Include source-mapping comments in generated DSL |
| `generateDocComments` | no | `true` | Include documentation comments in generated DSL |
| `operatorMappings` | no | `[]` | Explicit operator mapping rules binding BPMN task selectors to BLOGE operators |
| `defaultMappings` | no | `{}` | Fallback mapping rules keyed by BPMN task type |

**JSON-specific behavior.** When `bpmnJson` is supplied, the server automatically loads a built-in default mapping configuration (`bpmn-json-import-defaults.json`) that covers the domain-specific node types commonly found in JSON BPMN exports:

| Source node type | Default operator mapping |
|---|---|
| `systemUserTask` | `serviceCall` |
| `AnswerNode` | `scriptedResponse` |
| `ModelNode` | `textClassify` |
| generic `serviceTask` | `${taskDefinitionKey}Operator` |
| generic `userTask` | `HumanTaskOperator` |
| `callActivity` | `subgraph:${calledElement}` |

Any `operatorMappings` or `defaultMappings` supplied in the request are merged on top of these defaults, with request-supplied rules taking priority.

Requests that omit both payload fields or provide both `bpmnXml` and `bpmnJson` fail validation before translation starts,
so callers can treat the endpoint as an exact-one-of contract instead of format auto-detection.

Response body (`ImportBpmnResponse`):

| Field | Description |
|---|---|
| `dslSource` | Generated BLOGE DSL source |
| `success` | `true` when translation completed without errors |
| `diagnostics` | Array of diagnostic entries with `severity`, `code`, `elementId`, `location`, `message`, `suggestion` |

Example (XML):

```bash
curl -X POST http://localhost:8080/api/v1/import/bpmn \
  -H 'Content-Type: application/json' \
  -d '{
    "bpmnXml": "<?xml version=\"1.0\"?><definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\"><process id=\"hello\"><startEvent id=\"s\"/><endEvent id=\"e\"/><sequenceFlow sourceRef=\"s\" targetRef=\"e\"/></process></definitions>",
    "operatorMappings": [
      {
        "taskDefinitionKey": "fetchCustomer",
        "type": "serviceTask",
        "operatorRef": "FetchCustomerOp",
        "inputMapping": {"customerId": "ctx.customerId"}
      }
    ]
  }'
```

Example (JSON):

```bash
curl -X POST http://localhost:8080/api/v1/import/bpmn \
  -H 'Content-Type: application/json' \
  -d '{
    "bpmnJson": "{\"elements\":[{\"stencil\":\"StartNoneEvent\",\"resourceId\":\"s\"},{\"stencil\":\"EndNoneEvent\",\"resourceId\":\"e\"},{\"stencil\":\"SequenceFlow\",\"resourceId\":\"f1\",\"source\":{\"resourceId\":\"s\"},\"target\":{\"resourceId\":\"e\"}}]}"
  }'
```

## Configuration

Properties are bound under `spring.bloge.graph-engine.server`:

| Property | Default | Description |
|---|---|---|
| `spring.bloge.graph-engine.server.migrate-schema` | `true` | Apply the shared durable (`bd_*`) and graph-engine (`ge_*`) Flyway migrations through the combined `GraphEngineStoreFactory` startup path |
| `spring.bloge.graph-engine.server.default-environment` | `production` | Default deployment environment used when a start-instance request omits one |
| `spring.bloge.graph-engine.server.compile-cache.enabled` | `true` | Enable the in-process `VersionCompiler` result cache used by node and diagram projections |
| `spring.bloge.graph-engine.server.compile-cache.max-size` | `1000` | Maximum number of cached `VersionCompileResult` entries retained in memory |
| `spring.bloge.graph-engine.server.compile-cache.ttl` | `60m` | Expire-after-access TTL for cached compile results (`Duration` syntax) |

The server also inherits all `spring.bloge.*` properties from `bloge-spring` (DSL locations, tenant resolution, durable stores, recovery, audit, dispatch, etc.).

## Dependencies

- **compile-scope**: `bloge-graph-engine-service`, `bloge-graph-engine-mybatis`, `bloge-spring`, `bloge-durable-codec`, Spring Boot Web / Validation / Actuator / JDBC starters
- **optional**: `bloge-metrics-otel` (enables graph/node observability listeners, tracing, and logging integrations), `bloge-bpmn-transformer` (enables BPMN import endpoint when present)
- **test-scope**: JUnit 5, Spring Boot Test, H2

## Auto-Configuration

`GraphEngineServerAutoConfiguration` activates after `BlogeAutoConfiguration` and wires:

- `GraphEngineStores` — product metadata store aggregate (MyBatis-backed)
- `GraphEngineRuntimeSupport` — durable runtime collaborator bundle
- `GraphEngineService` — product-layer service facade (`DefaultGraphEngineService`)
- `DslValidationPipeline` — parse→lint→compile pipeline used by the AI authoring controller
- `GraphAuthoringService` — AI generation/repair loop (conditional on `LlmProvider` bean)
- `GraphEngineMetricsObserver` — Micrometer-backed product-layer metrics (conditional on `MeterRegistry` bean)
- `BpmnImportController` — BPMN 2.0 XML import endpoint (conditional on `bloge-bpmn-transformer` on classpath)
- REST controllers (including governance / dead-letter endpoints) and `GlobalExceptionHandler` (servlet web only)
- Jackson mix-ins for `VersionRoutingPolicy`, `SchemaCompatibility`, and `SchemaDescriptor` serialization
