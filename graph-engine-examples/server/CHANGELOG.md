# Changelog

All notable Graph Engine Server and product-layer changes after
`c2102e4a8367e983cc4fa26383472c10edf700df`.

## 2026-04-06

### Implementation status

- The REST and product-layer surface described in
  `docs/implements-plan/ai-native-graph-engine-arch-design.md` is present on
  the current branch.
- The implemented scope covers the architecture plan's product slices through
  Phase 3: model, persistence, service facade, server APIs, governance,
  remote-worker execution, AI authoring, BPMN import, observability, and RBAC.
- Phase 4 roadmap items that remain deferred are roadmap items rather than
  missing regressions in the implemented server surface.

### Added

#### Core product modules

- Introduced `bloge-graph-engine-model` with the product domain model for graph
  definitions, versions, deployments, instances, tasks, audit entries,
  transition history, dead letters, node-state projections, RBAC policy, and
  remote-worker records.
- Introduced `bloge-graph-engine-service` with `GraphEngineService`,
  `DefaultGraphEngineService`, version compilation/diff helpers, caller-context
  support, metrics hooks, RBAC enforcement, and retry/result DTOs.
- Introduced `bloge-graph-engine-server` with Spring Boot auto-configuration,
  request-scope caller resolution, Jackson support, a global exception handler,
  and the full REST control plane.
- Introduced `bloge-graph-engine-mybatis` for graph-engine metadata
  persistence and migrations.
- Introduced `bloge-graph-engine-ai` for AI-native graph authoring, repair, and
  validation workflows.

#### Definition, version, and deployment APIs

- Added definition CRUD endpoints under `/api/v1/graphs` for create, list, get,
  update, and archive operations.
- Added version lifecycle endpoints for create, list, get, validate, publish,
  deprecate, and diff operations.
- Added deployment endpoints under `/api/v1/deployments` for create/update,
  list, get, and routing-policy updates.
- Added support for latest, pinned, and canary routing policies during instance
  start resolution.

#### Instance execution and human-task APIs

- Added instance start/query/get/signal/cancel/terminate APIs.
- Added instance audit and transition-history APIs.
- Added task query/get/claim/complete/reassign/cancel APIs.
- Added `GET /api/v1/instances/{id}/nodes` to project node execution state from
  graph topology plus durable checkpoints, waits, and work items.
- Added `POST /api/v1/instances/{id}/retry` to restore dead-lettered work items
  for an instance, optionally filtered by node ID, and re-dispatch ready work.

#### Governance, recovery, and migration APIs

- Added dead-letter list and retry APIs.
- Added `GET /api/v1/operators` to expose operator inventory, metadata, and
  usage summaries across definitions and versions.
- Added version diff support with source-level diff text, metadata deltas, and
  enriched validation summaries.
- Added `POST /api/v1/import/bpmn` to translate BPMN XML into `.bloge` DSL plus
  translation diagnostics.

#### Remote-worker execution plane

- Added DSL and runtime support for `execution_mode = remote` plus
  `worker_topic`.
- Added remote-worker control-plane APIs for worker registration, polling,
  heartbeats, completion, and failure reporting.
- Added durable remote-work-item orchestration so remote nodes suspend, resume,
  retry, and dead-letter through the existing runtime stores.

#### AI-native authoring

- Added `POST /api/v1/ai/validate` for parse/lint/compile validation.
- Added `POST /api/v1/ai/generate` for prompt-driven draft generation with
  validation and repair loops.
- Added prompt-context building, operator-catalog extraction, few-shot example
  selection, DSL normalization, and quality scoring in `bloge-graph-engine-ai`.

#### Security, governance, and observability

- Added request-scope caller resolution from Spring Security and request
  headers.
- Added RBAC enforcement for definition, version, deployment, instance, task,
  dead-letter, and retry operations using `RbacPolicy`.
- Added RBAC-aware filtering for collection queries so unauthorized definitions,
  versions, deployments, instances, tasks, and dead letters are not returned.
- Added product-layer `ge.*` metrics through the observer SPI and Micrometer
  bridge for version publication, instance starts/completions, and task
  lifecycle events.

#### Tooling and documentation

- Added and updated READMEs across the graph-engine modules to document the
  service/server APIs, remote-worker semantics, AI authoring, and instance
  node-state projection behavior.
- Synced remote-execution semantics into `bloge-lang`, `bloge-lsp`, and
  `bloge-studio` so editor tooling matches the Java DSL/runtime behavior.

### Changed

- Node-state projection now prefers explicit work-item lifecycle state over a
  generic active wait so remote-worker nodes report actionable statuses such as
  `PENDING`, `RUNNING`, `RETRYING`, and `DEAD_LETTERED` instead of collapsing to
  `WAITING`.
- Public JavaDoc was added on the new model and service-facing types for node
  projection, retry behavior, and REST request/response contracts.
- Instance retry uses the work-item store directly to restore dead-lettered
  items and then triggers redispatch.

### Tests

- Added controller tests for the server REST surface, including the new
  instance node-view and retry endpoints.

## 2026-04-17

### Added

- Added SESSION and STATE_MACHINE support to
  `GET /api/v1/instances/{id}/nodes`.
- Added `GET /api/v1/graphs/{key}/versions/{version}/diagram` for layout-only
  version diagrams.
- Added `GET /api/v1/instances/{id}/diagram` for layout plus node-state
  overlays.
- Added `GET /api/v1/instances/{id}/events` for instance-scoped execution-event
  SSE with backlog replay, `Last-Event-ID` resume, and per-tenant connection
  limiting.

### Changed

- Session and state-machine node projections now reuse `GraphNodeState`
  directly, treating `nodeId` as the phase ID or state ID and keeping
  `visualLayout` as the stored raw `String`.
- Added service-level regression coverage for user-task node projection,
  remote-worker node-state precedence, and filtered instance retry behavior.
- Added focused HTTP integration coverage for the node-state endpoint in
  `GraphInstanceNodesApiIT`.
- Added broader service/server tests for operator inventory, RBAC behavior,
  source diffing, version compilation, BPMN import, AI authoring, and the
  remote-worker control plane.

### Verification notes

- Current re-check confirmed that all REST endpoints listed in section 5.2 of
  `docs/implements-plan/ai-native-graph-engine-arch-design.md` are implemented
  on the current branch.
- Focused verification on the current branch succeeded for:
  `DefaultGraphEngineServiceTest`, `GraphInstanceControllerTest`,
  `GraphVersionControllerTest`, `GraphInstanceEventFeedTest`, and
  `GraphInstanceEventControllerTest`.
- Broader full-reactor verification still encounters unrelated baseline
  build-health issues outside this slice in some SpotBugs/Javadoc/test-compile
  paths.
