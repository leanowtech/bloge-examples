# BLOGE Graph Engine Service

> This module is part of the
> [standalone graph-engine project](../README.md) and is built with Java 25
> outside the root bloge reactor.

`bloge-graph-engine-service` is the product-layer service facade that turns the lower-level BLOGE runtime pieces into control-plane operations for:

- graph definition governance
- version authoring, validation, publish, deprecate, and diff
- deployment routing
- instance start/signal projection
- instance cancel/terminate lifecycle actions
- unified instance context snapshots across graph / session / state-machine modes
- graph pending-signal projections for suspended GRAPH executions
- instance audit-log and transition-history queries
- human task claim/complete/reassign/cancel, with task projections that expose
  `candidateUsers`, `candidateGroups`, and `candidateRoles`
- dead-letter inspection and replay
- remote worker registration, poll-with-claim, heartbeat, complete, and fail
- operator inventory with metadata, schema, and usage queries

## What this module adds

This module does **not** build a second execution engine. Instead, it composes:

- `bloge-graph-engine-model` for product metadata stores
- `bloge-runtime-spi` / `bloge-durable` for durable execution, registry, task, and work-item lifecycles
- `bloge-dsl`, `bloge-session-ext`, and `bloge-state-ext` for `.bloge`, `session`, and `state_machine` compilation
- `bloge-lint` for authoring-time diagnostics
- durable `RemoteWorkerOperatorFactory` wiring so product version compilation can materialize remote-worker nodes from `execution_mode = remote`

Graph-mode starts use `DurableGraphEngine.executeStreaming(...)` so product APIs can return immediately for long-lived or human-task flows instead of blocking on the runtime's in-memory suspend retention window.

## Governance and lifecycle operations

The service facade exposes governance APIs on top of the existing durable runtime
substrate rather than introducing a separate orchestration engine:

- `deprecateVersion` transitions a published version to `DEPRECATED` so new
  starts stop routing to it
- `diffVersions` compares two versions of the same definition, returning a
  source-level unified diff, content-hash equality, and a structural metadata
  comparison covering execution mode, operators, schema-compatibility
  summaries, task definitions, and per-side validation summary counts
- `cancelInstance` / `terminateInstance` update the durable execution status,
  cancel outstanding timers and waits, bulk-cancel active work items, and close
  open human tasks
- `queryInstanceAuditLog` projects `AuditJournalStore.queryByExecution(...)`
  entries into `GraphAuditEntry` records for GRAPH and STATE_MACHINE instances.
  SESSION instances project completed round history from the freshest available
  active snapshot or durable session checkpoint, mapping each phase round to a
  `NODE_COMPLETE` audit entry with `nodeId = phaseId`. In SESSION mode,
  `retryAttempt` carries the 1-based round ordinal within the phase (iteration
  semantics), not a failure count
- `queryInstanceTransitions` projects durable execution transition log rows into
  `GraphTransitionEntry` records. SESSION queries use a control-plane-first
  pagination strategy: when `page=0` returns control-plane rows, later pages
  stay on that authoritative source. Checkpoint synthesis is only a first-page
  fallback when the control plane is absent or its first page is empty, and it
  is not repeated for later pages because the synthesized chain is a single
  full-history projection. Within synthesized chains, each
  `SUSPENDED -> RUNNING` timestamp uses the previous round completion time as
  the earliest observable start of the next round
- `queryPendingSignals` returns the currently waiting external signals for one
  suspended `GRAPH` instance by combining `EventMatcherStore.query(...)`
  (`WAITING` matchers, capped at 10,000 results) with
  `WaitStore.findByExecution(...)` (`WAIT_SIGNAL` / `WAIT_EVENT` waits). The
  projection includes matcher event/correlation data, an `optional` flag that
  indicates whether the runtime treats the matcher as non-blocking, wait
  timeout metadata, and the compiled node `signalSchema` when the stored
  definition/version can still be compiled; missing or uncompilable artifacts
  degrade to `signalSchema = null` instead of failing the API
- `getInstanceContext` returns a business-safe snapshot of the current instance
  context. GRAPH instances expose start variables plus decoded durable
  `NODE_OUTPUT` checkpoints. SESSION instances expose start variables plus the
  freshest available shared state / phase outputs from the active
  `DurableSessionManager` snapshot or durable session checkpoint; when a live
  snapshot is present it is returned immediately without querying the durable
  checkpoint. STATE_MACHINE instances expose start variables plus shared context
  / state outputs from the durable state-machine checkpoint. Missing
  checkpoints degrade to empty maps; per-node checkpoint decode failures are
  skipped so one bad payload does not fail the entire GRAPH context response
- `queryDeadLetters` maps `ControlPlaneService.queryDeadLetters(...)` into
  tenant-scoped `GraphDeadLetter` records
- `retryDeadLetter` restores one dead-lettered work item back to `READY` and,
  when the durable runtime facade is configured, records `CONTROL_ACTION`
  attempt/success/failure audit entries with optional recovery evidence and
  triggers a dispatch cycle
- `queryInstanceNodes` infers the execution status of every node in a running
  instance by combining the compiled artifact with durable runtime state.
  GRAPH instances project DAG nodes from checkpoints, waits, and work items.
  SESSION instances project phase status from the freshest active snapshot or
  durable session checkpoint, with live snapshots short-circuiting checkpoint
  I/O when present. STATE_MACHINE instances project state status from the
  durable state-machine checkpoint, including administrative `TERMINATED`
  checkpoints as `CANCELLED` for the current state. The same `GraphNodeState` DTO is reused
  across all three execution modes, with `operatorRef` / `waitType` populated
  only for GRAPH-mode nodes. The paged overload returns
  `PagedResult<GraphNodeState>` and supports in-memory status filtering plus
  offset/limit pagination, so polling clients can request only active nodes
  such as `RUNNING` / `WAITING` without downloading every projected node. For
  GRAPH-mode projections, work-item loading is bounded to
  `graph.nodes().size() * 3` instead of `Integer.MAX_VALUE`, which keeps the
  query proportional to graph size even when retries accumulate.
- `getVersionDiagram` returns a minimal layout-only DTO backed directly by
  `GraphVersion.visualLayout()` without reparsing the stored string payload
- `getInstanceDiagram` combines the stored `visualLayout` string with
  `queryInstanceNodes(...)` so the diagram overlay always matches the node view
- `retryInstance` restores all dead-lettered work items for an instance
  (optionally filtered to specific node IDs) back to `READY` and triggers a
  dispatch cycle. Requires admin RBAC and an optimistic-lock revision guard.
  The overload that accepts `RecoveryActionEvidence` records the source action,
  source indicator, reason, actor, request id, restored item count, and failure
  details in the instance audit log as `CONTROL_ACTION` entries.
- `queryInstanceControlActions` filters instance audit history to control-plane
  actions and projects `inputJson` / `outputJson` into `GraphControlActionEntry`
  fields such as action code, request id, attempt status, restored item IDs, and
  failure details. Malformed legacy payloads degrade to `attemptStatus = UNKNOWN`
  while preserving raw JSON for review.

Session-mode lifecycle actions (start, signal, terminate) are handled through
`DurableSessionManager`, which the service lazily initializes from the durable
stores already available in `GraphEngineRuntimeSupport`. The service encodes
cancel reasons with a `__graph_engine_cancel__:` prefix and stores terminate
reasons under the session termination marker so later projections can
distinguish `CANCELLED` from `TERMINATED`. Session-mode audit queries project
round history from the freshest available active or durable checkpoint.
Session-mode transition queries prefer durable control-plane rows on the first
page and only fall back to checkpoint synthesis when that first page is empty
or the control plane is unavailable. Later pages return the control-plane
result as-is instead of re-synthesizing checkpoint history, which keeps the
paginated contract stable. Synthesized entries are tagged with
`transitionSource = "session-checkpoint-synthesis"`. Resume transitions in the
synthesized chain use the previous round's completion time so the projected
`RUNNING` window remains non-zero when history includes later rounds.

## Compile-result caching

`VersionCompiler` caches `VersionCompileResult` objects in-process so repeated
node or diagram queries do not re-run parse, lint, and compilation work for the
same immutable stored version. The cache is keyed by `(versionId, contentHash)`
to preserve version-scoped runtime naming while still using the source hash as
the content fingerprint, and it defaults to 1,000 entries with a 60-minute
expire-after-access policy.

Embedded callers can override or disable the cache through
`GraphEngineRuntimeSupport.builder().versionCompilerCacheSettings(...)`.
`DefaultGraphEngineService` also clears one version's cached entry after
publish/deprecate transitions and flushes the cache on shutdown so lifecycle
tests can force clean recompilation when needed.

## Representative BPMN execution coverage

The production ride-hailing BPMN is large enough that a literal execution test
would require hundreds of stub operators and obscure the engine behavior we
actually care about. Phase 4 therefore distils the topology into the readable
fixture at `src/test/resources/bloge/ride-hailing-subset.bloge`.

`RideHailingSubsetExecutionTest` runs that DSL through six focused scenarios so
the standalone service keeps regression coverage for:

| Test | Pattern proved |
|---|---|
| `testSixWayBranch_...` | one selected lane completes, five siblings are skipped, and their terminal followers are cancelled |
| `testNestedGateway_depth5_...` | five layers of conditional routing keep only the selected path active |
| `testCallActivity_sameSubgraph3Times_...` | the same `subgraph("ride-auth-leg")` executes three times with isolated child context |
| `testDColNode_...` | a suspendable dispatch-choice node resumes from a real `engine.signal(...)` and routes to the correct branch |
| `testTextClassify_...` | `textClassify` intent routing selects the complaint handler and skips the alternatives |
| `testTerminalChain_...` | the `answer -> zrg -> end` chain completes with one active terminal outcome and nine skipped alternatives |

All operators in the fixture are lightweight stubs so failures point at engine
behavior instead of business logic. The fixture opts its `GraphLoader` into the
compiler-scoped `ComplexityLimits.IMPORT` profile because the BPMN-derived graph
intentionally exceeds the default authoring limits. The call-activity scenario complements
`bloge-core`'s `SubGraphContextIsolationTest`: the core test proves the minimal
isolation contract, while the service test re-validates the same guarantee
inside a larger multi-pattern graph.

## Operator inventory

`queryOperatorInventory(OperatorInventoryQuery)` builds a product-layer view of
registered operators that combines:

- **registry metadata** — input/output types and schema descriptors from
  `OperatorRegistry.metadata()`
- **annotation details** — human-facing `@OperatorMeta` and `@BlogeOperator`
  attributes (description, owner, tags, usage example, constraints) extracted
  via the shared `OperatorAnnotationIntrospector` in `bloge-core`
- **usage statistics** — cross-definition counts and per-version references
  derived from `GraphVersionMetadata.operatorRefs()` across all visible graph
  definitions in the caller's tenant/namespace scope

Results are filtered by a glob-style pattern (defaulting to `*`) and returned
as an immutable `List<OperatorInventoryEntry>`.

## Remote worker control plane

The service facade also exposes the server side of the remote-worker protocol
described in the AI-native graph-engine design:

- `registerRemoteWorker` is a stateless discovery call that resolves active
  deployment bindings whose `RemoteWorkerBinding` matches the caller's
  `workerId` or `workerTopic`
- `pollRemoteWorkerJobs` queries
  `WorkItemStore.pollReady(EXECUTE_NODE, workerTopic, limit)` and then claims
  each ready item with optimistic locking so competing workers can safely race
- `heartbeatRemoteWorkerJob` extends the lease on one claimed item and returns
  the refreshed `GraphRemoteWorkerJob` projection
- `completeRemoteWorkerJob` validates the lease token and revision, signals the
  durable execution with the worker output, and then marks the item `DONE`
- `failRemoteWorkerJob` validates the lease and transitions the item to
  `RETRY_WAIT` using the envelope's retry policy, or to `DEAD_LETTER` when the
  retry budget has been exhausted

## Runtime naming strategy

The service derives deterministic runtime artifact names from stable product identifiers instead of publishing raw DSL root names directly. This prevents collisions between different product definitions that may happen to reuse the same top-level DSL name while keeping graph versions evolution-compatible inside the shared runtime registry. Session and state-machine runtime renames preserve the compiler-provided `contentHash`, so durable checkpoint compatibility checks continue to compare the authored orchestration structure instead of the product-layer runtime name.

Graph registry publication uses `GraphEngineDslCodecs.graphDefinitionCodec(...)` for DSL graph definitions. This keeps BLOGE's registry payload format while repairing retry-enabled AST round-trips introduced by the upgraded `retryOnCategories` metadata, so graphs with `retry = { ... }` remain decodable during schema-evolution checks and durable runtime publication.

## Typical wiring

The service is created from two aggregates:

1. `GraphEngineStores` — product metadata stores (`ge_definition`, `ge_version`, `ge_deployment`, `ge_instance`)
2. `GraphEngineRuntimeSupport` — durable runtime collaborators such as `DurableGraphEngine`, `GraphRegistryStore`, `ExecutionStore`, `ExecutionCheckpointStore`, `TaskInboxStore`, and governance-facing collaborators like `AuditJournalStore`, `ControlPlaneService`, `WorkItemStore`, `WaitStore`, and `TimerService`. Session orchestration is handled internally by `DefaultGraphEngineService` via a lazily-initialized `DurableSessionManager` — no `SessionStore` or `SessionExecutor` fields are held in this aggregate.

When `GraphEngineRuntimeSupport` includes a `WorkItemStore`, `VersionCompiler` automatically wires a durable `RemoteWorkerOperatorFactory` into the DSL compiler. Versions containing `execution_mode = remote` nodes compile into embedded `RemoteWorkerOperator` bridges, and the business `operatorRef` is preserved on `NodeSpec` so external workers can still identify the intended operator.

In embedded deployments, the service can sit directly on top of the durable Spring auto-configuration. In standalone deployments, `bloge-graph-engine-server` exposes the same facade over REST.

## Operations action contract

`queryOperationsSnapshot` returns the current tenant/namespace operations
projection. Besides health counts and SLO indicators, each non-OK action item
now carries a runbook and recovery-action contract:

- `runbookCode`, `runbookTitle`, `runbookHref` identify the operating procedure.
- `recoveryActions` list stable action codes, labels, API path templates,
  console routes, risk level, and whether execution requires a reason or
  optimistic-lock revision.
- The contract is intentionally descriptive. It lets consoles, alerts, and
  automation route operators to the right recovery surface, while the actual
  mutation still goes through dedicated service methods such as
  `retryDeadLetter`, `retryInstance`, deployment update, cancel, or terminate.

This keeps operations guidance close to the health rules without bypassing RBAC,
validation, or recovery-action audit capture at the execution endpoint.

Recovery mutations can attach `RecoveryActionEvidence`:

- `reason` explains why the operator or automation believes the retry is safe.
- `sourceActionCode` links the execution back to a recovery action such as
  `RETRY_DEAD_LETTER`.
- `sourceIndicatorCode` links the execution back to an SLO indicator such as
  `DEAD_LETTER_OLDEST_AGE`.
- `actor` identifies the human operator or automation identity.
- `requestId` carries an external ticket, incident, or automation request id
  for cross-system correlation. It is not an idempotency lock.

When an `AuditJournalStore` is configured, dead-letter and instance retry write
`AuditEventType.CONTROL_ACTION` entries as a control-action timeline. The audit
entry `inputJson` carries the evidence and target. The `outputJson` always
includes `attemptStatus`:

- `ATTEMPTED`: target has been resolved and admin permission has passed.
- `SUCCEEDED`: restore and dispatch completed; `status` remains `RESTORED` and
  restored item IDs/counts are included.
- `FAILED`: restore, dispatch, or projection refresh failed; failure phase,
  class, message, and any already-restored item IDs/counts are included.

`queryInstanceControlActions` exposes the same data without requiring callers to
parse raw JSON. It returns only `CONTROL_ACTION` entries and promotes
`requestId`, `actionCode`, `sourceIndicatorCode`, `attemptStatus`,
candidate/restored item details, and failure fields into stable DTO properties.

Age-based operations thresholds are controlled by `GraphOperationsPolicy` on
`GraphEngineRuntimeSupport`. Defaults remain dead-letter warning/critical at
5m/30m and suspended-instance warning/critical at 15m/2h, but embedded callers
can provide a process-level policy when those windows do not match their
business SLO.

## Product-layer metrics

`DefaultGraphEngineService` calls an optional `GraphEngineMetricsObserver`
after successful control-plane actions. The observer SPI itself has no external
dependencies (all method parameters are low-cardinality strings), and this
module now ships the default Micrometer implementation in
`com.leanowtech.bloge.graphengine.service.metrics`.

| Action | Observer method | Metric |
|---|---|---|
| Publish version | `onVersionPublished` | `ge.version.published` |
| Start instance | `onInstanceStarted` | `ge.instance.started` |
| Cancel / terminate / complete instance | `onInstanceCompleted` | `ge.instance.completed` |
| Claim task | `onTaskClaimed` | `ge.task.claimed` |
| Complete task | `onTaskCompleted` | `ge.task.completed` |
| Query operations snapshot | `onOperationsSnapshot` | `ge.operations.*` gauges |

Operations snapshot gauges are refreshed when `queryOperationsSnapshot` runs.
They expose product control-plane state for the same tenant/namespace scope that
the console renders:

- `ge.operations.health` (`OK=0`, `WARNING=1`, `CRITICAL=2`)
- `ge.operations.dead_letters`
- `ge.operations.failed_instances`
- `ge.operations.suspended_instances`
- `ge.operations.active_deployments`
- `ge.operations.snapshot_truncated`
- `ge.operations.control_plane_available`
- `ge.operations.dead_letter_oldest_age_seconds`
- `ge.operations.suspended_oldest_age_seconds`

When no observer is configured, `GraphEngineMetricsObserver.NOOP` is used (zero
overhead). The Micrometer implementation lives in this module as
`com.leanowtech.bloge.graphengine.service.metrics.MicrometerGraphEngineMetricsObserver`.
Add `micrometer-core` to the classpath when wiring it manually through
`GraphEngineRuntimeSupport.builder().metricsObserver(observer)`.

## RBAC enforcement

The service enforces role-based access control at the service layer using the
owning definition's `RbacPolicy`.  Enforcement is additive and non-invasive:

| Policy field | Gated operations |
|---|---|
| `viewRoles` | `getDefinition`, `getDefinitionByKey`, `queryDefinitions`, `getVersion`, `queryVersions`, `getDeployment`, `queryDeployments`, `getInstance`, `queryInstances`, `getTask`, `queryTasks`, pending-signal/audit/transition queries, `queryDeadLetters`, `diffVersions`, operator inventory usage, `queryInstanceNodes` |
| `startRoles` | `startInstance`, `signalInstance` |
| `deployRoles` | `createVersion`, `validateVersion`, `publishVersion`, `deprecateVersion`, deployment create/update/activate |
| `adminRoles` | `updateDefinition`, `archiveDefinition`, `cancelInstance`, `terminateInstance`, `retryDeadLetter`, `retryInstance`, task claim/complete/reassign/cancel |

**Empty role-sets are unrestricted.** When a policy field is empty (or the
definition has no `RbacPolicy`), access is granted to all callers.

**System/internal calls bypass RBAC.** When no `CallerContext` is bound on the
current thread (the default for embedded non-HTTP usage), all operations are
allowed. HTTP entry-points populate the holder via a servlet filter.

**Collection queries are filtered, not rejected.** List/query operations drop
definitions, versions, deployments, instances, tasks, and dead letters the
caller cannot view instead of failing the entire query. Direct lookups and
mutations still raise `ACCESS_DENIED`.

### Key classes

- `CallerContext` — immutable record of the caller's granted roles
- `CallerContextHolder` — thread-local holder; `null` = system/internal call
- `RbacEnforcer` — stateless helper that checks `CallerContext` against the
  definition's `RbacPolicy` and throws `ACCESS_DENIED` on mismatch
