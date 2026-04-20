# bloge-graph-engine-model

> This module is part of the
> [standalone graph-engine project](../README.md) and is built with Java 25
> outside the root bloge reactor.

`bloge-graph-engine-model` is the product-layer metadata module for the AI-native
graph engine design. It does **not** execute graphs itself. Instead, it defines
the governance-facing domain objects and store contracts that sit above the
existing BLOGE runtime substrate.

## What lives here

- `GraphDefinition`, `GraphVersion`, `GraphDeployment`, `GraphInstance`, and
  `GraphTask` product records, including task claim metadata such as
  `candidateUsers`, `candidateGroups`, and `candidateRoles`
- `GraphInstanceContext` snapshots that keep GRAPH node outputs, SESSION phase
  outputs, and STATE_MACHINE state outputs in one stable API shape
- `GraphPendingSignal` projections for GRAPH-mode instances waiting on external
  signals, including per-matcher `optional` status so control-plane callers can
  distinguish required signals from optional ones the runtime may treat as
  non-blocking
- `GraphVersionDiagram` and `GraphInstanceDiagram` DTOs for serving stored
  `visualLayout` payloads as-is, with instance diagrams adding the current
  node-state overlay
- `GraphInstanceEvent` SSE payloads for streaming execution-journal activity on a
  single instance without exposing the raw journal schema directly
- governance records such as `GraphAuditEntry` (whose `retryAttempt` field
  carries failure-count semantics in GRAPH and STATE_MACHINE projections, but
  the 1-based within-phase round ordinal in SESSION projections),
  `GraphTransitionEntry`, and `GraphDeadLetter`, plus the
  `GraphDeadLetterQuery` filter object
- remote-worker control-plane projections such as
  `GraphRemoteWorkerRegistration`, `GraphRemoteWorkerAssignment`, and
  `GraphRemoteWorkerJob`
- supporting metadata such as execution mode, routing, operator plane
  configuration, task definitions, and RBAC policy
- store contracts for definitions, versions, deployments, and instance
  projections
- product-layer status mapping helpers such as `GraphInstanceStatus`, which
  projects durable execution states into the graph-engine control-plane model
- shared node-state projections (`GraphNodeState`) reused for GRAPH nodes,
  SESSION phases, and STATE_MACHINE states
- in-memory store implementations for tests, local development, and service-layer
  composition

## Why it exists

The durable runtime already persists execution state, checkpoints, task inbox
entries, and registry definitions. The graph-engine product layer needs
additional metadata that belongs to governance rather than execution:

- business-facing definition identity (`definitionKey`, display name, labels,
  owner team)
- immutable version snapshots of `.bloge` source and derived metadata
- environment-specific deployment routing
- instance projections that enrich durable execution rows with product metadata
- remote-worker registration and claimed-job views that let the higher-level
  service and REST API describe external worker state without leaking raw store
  rows

Keeping those concepts in a dedicated module lets later service and server
modules build on them without pushing product concerns into `bloge-core` or
`bloge-durable`.

## Store behavior

The in-memory stores in this module follow the same rules as the durable runtime
stores:

- immutable records on read/write boundaries
- optimistic locking via `revision`
- explicit exceptions for not-found / duplicate / version-conflict cases
- `TimeSource`-driven timestamps for deterministic tests
- tenant-aware filtering via the active `TenantContextHolder` scope

The initial slice intentionally keeps this module runtime-agnostic. The JDBC /
MyBatis persistence backend now lives in `bloge-graph-engine-mybatis`, while
the higher-level control-plane service and API modules build on these contracts
in later slices.
