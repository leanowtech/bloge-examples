# BLOGE Resource Gateway

**Turn external APIs into schema-checked building blocks for business workflows.**

Resource Gateway is a Spring Boot example that shows how BLOGE can turn messy
provider integrations into visible, reusable orchestration. Instead of writing a
new Java operator for every upstream endpoint, teams describe resources with
`ResourceDescriptor`, compose them in `.bloge` graphs or the visual canvas, and
let the same schema contract drive validation, simulation, runtime execution,
and reuse.

The interesting part is not "calling HTTP". The interesting part is making API
integration something the business flow can see, reason about, test, and change.

## What You Get

| Capability | Why it matters |
| --- | --- |
| Descriptor-first resources | Add most APIs by changing contracts, not cloning Java operators |
| Graph-level contracts | Every built-in resource graph exposes formal input/output JSON Schema for system integration |
| Schema-aware canvas | Drag, connect, validate, simulate, and publish under server-side schema checks |
| Runtime-backed demos | Local upstreams, real gateway execution, mock simulation, SSE examples, and reusable publications |
| Schema-gated table tests | Run 14 built-in cases across all seven resource graphs with F3 transport fixtures, bounded retry consumption, coverage gates, and fidelity evidence |
| Isolated testing control plane | Test/staging-only graph/operator discovery, immutable fixture registry, caller-driven DAG and operator micro-graph execution, attempt/occurrence-specific doubles, sanitized evidence retention, batch runs, and production control-field guard |
| Governed run controls | Absolute deadline, monotonic remaining-budget propagation, fenced cancel, durable owner lease/epoch, cross-instance commands, and automatic signed evidence recovery after owner failure |
| Auditable external writes | Versioned write contracts, binding/activation conformance, execution-scoped journal, commit receipts, UNKNOWN_COMMIT DAG guard, and signed reconciliation evidence |
| Dynamic workload identity | Atomic JWKS/revocation refresh, zero-restart key rotation, bounded propagation SLO, group/clearance/delegation claims, and explicit 401/503 semantics |
| Managed evidence signing | Non-exportable KMS/HSM provider protocol, atomic public-key generations, locally verified signatures, rotation/revoke semantics, and machine-readable custody health |
| Consistent draft export | Frozen operator/library/binding/activation/test-suite refs, deterministic dependency fingerprints, and retryable 409 conflict on assembly-time drift |
| Governed replay payloads | Payload values detached from immutable evidence, classification ABAC, selective retention, legal hold, bounded expiry, and signed deletion proof |
| Workbook and gate evidence loop | Deterministic sanitized workbook seeds, exact suite/run evidence refs, versioned gate decision basis, stale detection, and transactional gate events |
| Operational controls | Cache, tenant rate limit, circuit breaker, run history, golden cases, and publication history |

## Start The Demo

From the repository root:

```bash
./scripts/start-visual-canvas-demo.sh --open
```

This packages the Spring Boot gateway with the React frontend and starts the
demo on `http://localhost:8080`. The dedicated demo script activates the `test`
profile by default so `/api/testing/**` is available; use `--profile production`
to demonstrate that the testing beans and endpoints are structurally absent.

| Open | Best first move |
| --- | --- |
| `http://localhost:8080/author/` | Build a schema-constrained graph on the visual canvas |
| `http://localhost:8080/showcase/` | Explore guided product scenarios and sample outputs |
| `http://localhost:8080/examples/gateway` | Use the legacy Custom Composer regression surface |
| `http://localhost:8080/api/integration/capabilities` | Verify protocol versions, endpoints, feature flags, identity provider, payload policy, and signer readiness |
| `http://localhost:8080/api/gateway/graphs/contracts` | Inspect resource graph input/output contracts |
| `GET http://localhost:8080/api/testing/targets/graphs/{graphName}` | Freeze the graph/resource target fingerprint before authoring fixtures (test/staging only) |
| `POST http://localhost:8080/api/testing/executions` | Run an isolated inline or governed fixture plan and retain sanitized evidence (test/staging only) |
| `POST http://localhost:8080/api/testing/durable-executions` | Idempotently create an exact graph test at its first unique signal suspension (test/staging only) |
| `GET http://localhost:8080/api/testing/durable-executions/{runId}` | Inspect an integrity-verified, payload-free durable checkpoint view before recovery (test/staging only) |
| `POST http://localhost:8080/api/testing/durable-executions/{runId}/owner-claims` | Re-authorize an exact expired v2 checkpoint and atomically claim its lease; this does not resume BLOGE (test/staging only) |
| `POST http://localhost:8080/api/testing/durable-executions/{runId}/heartbeats` | Renew one exact issued recovery fence under the same authenticated authority (test/staging only) |
| `POST http://localhost:8080/api/testing/durable-executions/{runId}/terminal-recoveries` | Signal one exact claimed suspension and atomically commit only a server-derived terminal result (test/staging only) |
| `GET http://localhost:8080/api/testing/targets/operators/{operatorRef}` | Inspect frozen binding/schema/state fingerprints and executable testability (test/staging only) |
| `POST http://localhost:8080/api/testing/targets/operators/{operatorRef}/executions` | Run the exact synchronous binding as a controlled one-node BLOGE graph (test/staging only) |
| `GET http://localhost:8080/api/integration/test-suites/{suiteId}/revisions/{revision}/semantic-correctness-workbook` | Export a payload-free ANEKE seed for one exact semantic suite and its verified terminal evidence (test/staging only) |
| `POST http://localhost:8080/api/gateway/graphs/contracts/tests/draft` | Generate editable graph mock/table suites from graph and resource schemas |
| `POST http://localhost:8080/api/gateway/graphs/contracts/tests/run` | Run schema-gated mock/table contract suites |
| `POST http://localhost:8080/api/gateway/graphs/contracts/tests/suites/run-all` | Run every stored contract suite with coverage policy checks |
| `POST http://localhost:8080/api/visual/operators/tests/draft` | Generate editable operator mock/table suites from operator schemas |
| `POST http://localhost:8080/api/visual/operators/tests/suites/run-all` | Run every stored operator schema mock/table suite |

Stop it with:

```bash
./scripts/stop-visual-canvas-demo.sh
```

Useful variants:

```bash
./scripts/start-visual-canvas-demo.sh --port 18080
./scripts/start-visual-canvas-demo.sh --no-build
./scripts/start-visual-canvas-demo.sh --run-tests
./scripts/start-visual-canvas-demo.sh --profile staging
./scripts/start-visual-canvas-demo.sh --profile production
./scripts/visual-canvas-demo.sh status
./scripts/visual-canvas-demo.sh restart
```

The start command becomes ready only after the integration capability probe
succeeds. Process output is written to `target/example-logs/visual-canvas-demo.log`;
the PID and selected port are kept under `target/example-pids/`.

The testing API requires `Authorization: Bearer bloge-aneke-demo-token` and a
least-privilege `X-Purpose` (`TEST_EXECUTION`, fixture read/write, or suite read/write) in the local
test profile. Immutable suites use `TEST_SUITE_READ` and `TEST_SUITE_WRITE`; exact suite execution
and suite-run query use `TEST_EXECUTION`. Materialize the seven built-in graph suites into exact
common fixture/TestSuite revisions with:

```bash
curl -sS -X PUT http://localhost:8080/api/testing/catalogs/gateway-graph-contract-v1 \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_SUITE_WRITE'
```

See
[Testing Control Plane API](../docs/resource-gateway-testing-control-plane-api.md)
for the complete target-discovery, fixture-registration, execution, evidence,
and production-isolation workflow. Java/JUnit/CI consumers can use the independent
[Resource Gateway Test Kit](../resource-gateway-test-kit/README.md) for fixture and immutable-suite
builders, typed catalog materialization, exact suite execution, payload-free assertions/XML, and the fail-closed CLI instead of
hand-assembling HTTP requests or interpreting aggregate evidence ad hoc.

### Create a durable graph test

Freeze the target through graph discovery and publish an immutable fixture revision first. Then create
one idempotent graph-contract test from those exact fingerprints:

```bash
curl -sS -X POST \
  http://localhost:8080/api/testing/durable-executions \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_EXECUTION' \
  -H 'Content-Type: application/json' \
  -d '{
    "schemaVersion": "bloge.durableTestExecutionCreateRequest.v1",
    "clientRequestId": "create-approval-flow-20260717-01",
    "target": {
      "kind": "GRAPH",
      "id": "approvalFlow",
      "fingerprint": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    },
    "executionPurpose": "GRAPH_CONTRACT_TEST",
    "context": {"requestId": "REQ-42", "amount": 25000},
    "fixtureBundleRef": {
      "fixtureBundleId": "approval-fixture",
      "revision": 3,
      "fingerprint": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
  }'
```

Creation v1 accepts only exact graph targets and stored fixtures and succeeds only at one unambiguous
persisted signal wait. The response wraps a payload-free suspended execution view containing the
server-minted run id and recovery fence. A committed success or deterministic unsupported-boundary
rejection is replayed for the same authenticated `clientRequestId`; a live concurrent preparation
returns `409` with its run id and lease deadline. It does not support operator creation, inline/latest
dependencies, timers/tasks/streams, terminal fresh runs, or multiple live suspensions.

The server owns the preparation identity and lease through
`gateway.testing.durable.creation.instance-id` and
`gateway.testing.durable.creation.lease-duration-seconds` (default `120`, valid `3..3600`). A
process-local coordinator renews the exact database-fenced `PENDING` reservation while the staged
fresh run is preparing. Set `gateway.testing.durable.creation.heartbeat-interval-seconds` to `0`
(default) to derive one third of the lease, or to a whole-second value from `1` through
`floor(lease / 3)`. Commit and deterministic rejection freeze renewal and use the latest successor
fingerprint; heartbeat failure or service shutdown returns
`409 RG.TEST.DURABLE_CREATE_LEASE_LOST` and discards staged state. This is lease liveness, not forced
in-process cancellation: an uncooperative operator still requires a killable worker boundary.
Complete wire semantics and failure codes are in the
[Testing Control Plane API](../docs/resource-gateway-testing-control-plane-api.md#42d-create-one-durable-graph-execution).

### Inspect a durable test execution

Read the latest integrity-verified control fence before deciding whether to claim or diagnose a
durable run:

```bash
curl -sS \
  http://localhost:8080/api/testing/durable-executions/run-20260716-001 \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_EXECUTION'
```

The `bloge.durableTestExecutionView.v1` response contains the current status, exact owner/epoch/
revision fence, lease deadline, graph/operator and fixture references, plan/provider/fixture-ledger
fingerprints, payload-free engine boundary, and aggregate checkpoint fingerprint. It never contains
business context, fixture values, replay payloads, provider cursors, credentials, or BLOGE checkpoint
bodies. `recoverable` is true only for a current v2 resumable state with restorable providers;
historical v1 rows remain visible as operational facts but return no target and set
`migrationRequired=true`. The view is not a dispatch, authorization token, or proof that the lease
is still live after the response was produced.

Missing and cross-organization/project runs both return `404`; malformed run ids return `400`; a
store outage or any sealed-JSON/index/fingerprint inconsistency returns a payload-free `503`.

### Claim an expired durable test lease

The public owner-claim command is available only under `test` or `staging`. Obtain the current
payload-free checkpoint fence from the durable-run query above, then
submit that exact observation with a caller-stable idempotency key:

```bash
curl -sS -X POST \
  http://localhost:8080/api/testing/durable-executions/run-20260716-001/owner-claims \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_EXECUTION' \
  -H 'Content-Type: application/json' \
  -d '{
    "schemaVersion": "bloge.durableTestOwnerClaimRequest.v1",
    "clientRequestId": "recover-run-20260716-001-attempt-1",
    "expectedFence": {
      "ownerId": "expired-worker-a",
      "leaseEpoch": 3,
      "revision": 7
    },
    "expectedCheckpointFingerprint": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  }'
```

The server, never the caller, chooses the new owner id and lease duration. Configure them with
`RG_TEST_DURABLE_OWNER_INSTANCE_ID` and `RG_TEST_DURABLE_OWNER_LEASE_SECONDS` (default `120`, valid
range `1..3600`). A successful response is `bloge.durableTestOwnerClaimResponse.v1` and contains only
the resulting fence, expiry, checkpoint fingerprint, target locator, and `idempotentReplay` flag.
Retries must reuse the same `clientRequestId` and byte-equivalent intent; reusing the key for another
intent fails closed.

Before mutation, Resource Gateway revalidates the authenticated scope, exact graph/operator target,
fixture revision, replay closure, current identity authority, side-effect policy, execution-service
state, and recompiled effective plan. The authorization audit and fresh lease claim commit in the
same local test-runtime transaction. `404` hides cross-project existence, `409` reports a stale fence,
active lease, migration requirement, or unavailable exact dependency closure, and `503` reports an
authority/store/audit outage. This endpoint moves the checkpoint to `RESUMING`; the claim itself does
not execute BLOGE. Its exact returned fence may be renewed or supplied to the terminal-only recovery
endpoint below.

### Renew a claimed durable recovery lease

The owner-claim response supplies the exact `ownerId`, `leaseEpoch`, `revision`, and
`checkpointFingerprint` required by the heartbeat. Keep those values as one indivisible fence and
send a new caller-stable idempotency key for each renewal:

```bash
curl -sS -X POST \
  http://localhost:8080/api/testing/durable-executions/run-20260716-001/heartbeats \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_EXECUTION' \
  -H 'Content-Type: application/json' \
  -d '{
    "schemaVersion": "bloge.durableTestRecoveryHeartbeatRequest.v1",
    "clientRequestId": "heartbeat-run-20260716-001-1",
    "expectedFence": {
      "ownerId": "server-issued-owner",
      "leaseEpoch": 4,
      "revision": 8
    },
    "expectedCheckpointFingerprint": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
  }'
```

The service resolves the hidden, committed recovery dispatch from that exact fence; callers never
send a dispatch, authorization receipt, owner choice, or lease duration. It requires the same
authenticated tenant, organization, project, environment, region, actor, delegation, purpose,
clearance, and groups that obtained the claim. Correlation id may change across a lost-response
retry. Configure the server-owned renewal with `RG_TEST_DURABLE_HEARTBEAT_LEASE_SECONDS` (default
`120`, valid range `3..3600` while synchronous terminal recovery is enabled). The terminal-recovery
worker derives a one-third heartbeat interval by default. Override it with
`RG_TEST_DURABLE_RECOVERY_HEARTBEAT_INTERVAL_SECONDS`; it must be at least one second and no greater
than one third of the lease.

A successful `bloge.durableTestRecoveryHeartbeatResponse.v1` returns the successor revision,
database-authority expiry, checkpoint fingerprint, and `idempotentReplay`; use that successor fence
for the next heartbeat. Retry an ambiguous response with the same key and identical intent. Reusing
an old fence under a new key, changing authority, using an expired lease, or reusing a key for a
different intent fails closed. The authorization audit and first heartbeat commit atomically. This
endpoint renews ownership only: it does not poll work, run BLOGE, cancel execution, or produce
terminal evidence.

### Complete one terminal recovery

Use the latest indivisible owner/epoch/revision/checkpoint fence from owner claim or heartbeat to
apply one signal to the exact persisted suspension. The caller supplies intent only; it cannot
provide an outcome, engine state, fixture cursor, provider state, receipt, or evidence label:

```bash
curl -sS -X POST \
  http://localhost:8080/api/testing/durable-executions/run-20260716-001/terminal-recoveries \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_EXECUTION' \
  -H 'Content-Type: application/json' \
  -d '{
    "schemaVersion": "bloge.durableTestTerminalRecoveryRequest.v1",
    "clientRequestId": "terminal-run-20260716-001-1",
    "expectedFence": {
      "ownerId": "server-issued-owner",
      "leaseEpoch": 4,
      "revision": 9
    },
    "expectedCheckpointFingerprint": "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
    "signal": {"nodeId": "approval-wait", "data": {"approved": true}}
  }'
```

The signal is canonicalized under a `256 KiB` limit and exists only in the isolated in-memory
invocation. It is not copied into audit, response, checkpoint, or terminal receipt. Before execution,
the service re-resolves the hidden issued dispatch, requires the original authenticated principal,
loads its exact live `RESUMING` checkpoint, and reproduces the complete authorization receipt. The
process-local recovery coordinator synchronously rotates that dispatch before BLOGE starts, keeps
rotating exact successors while the staged recovery runs, then freezes renewal and supplies the
latest successor to the terminal CAS. Any heartbeat conflict, store failure, or coordinator shutdown
closes the stage without committing and returns payload-free
`RG.TEST.DURABLE_RECOVERY_LEASE_LOST`. The runtime restores the same fixture cursor and deterministic
provider state and signals one cold recovery.

Success requires BLOGE to reach `COMPLETED`, `FAILED`, `FAILED_RECOVERY`, `CANCELLED`, or
`TERMINATED`. The staged BLOGE mutation, terminal checkpoint, payload-free receipt, idempotency
record, and semantic audit commit in one local transaction. If the graph suspends again, the stage is
discarded and the endpoint returns `409`; it does not silently orchestrate another signal. Retry a
lost response with the same key and identical signal to receive the immutable result without running
the engine again. The v1 response is always `EVIDENCE_INCOMPLETE` with explicit
`PRE_CHECKPOINT_TRACE_UNAVAILABLE` and `RECOVERY_SIGNAL_PAYLOAD_OMITTED` gaps, so it blocks promotion
and is not a signed correctness-evidence bundle.

Batch-migrate existing `.bloge` files after the service is running:

```bash
./scripts/bloge-dsl-batch-import.sh report \
  --base-url http://localhost:8080 \
  --operator-library risk-policy \
  --dsl-dir resource-gateway-examples/src/main/resources/bloge/gateway \
  --out target/dsl-batch-report.json
```

Use `commit --commit-policy renderable|fully-projected|rewrite-allowed` with the
same inputs to save eligible projections as governed visual drafts. The script
does not write source `.bloge` files; source replacement still belongs behind
rewrite gate evidence and a reviewed VCS/source-writer flow.

## Notice The Product Loop

1. **Import or use a resource contract**: seeded resources such as
   `user-service.getProfile`, `order-service.listOrders`, and
   `credit-provider.primary` all run through `HttpResourceOperator`.
2. **Compose the business flow**: connect descriptors, transforms, decisions,
   subgraphs, and design-only operators under JSON Schema constraints.
3. **Prove and promote it**: validate, simulate with mock or real evidence,
   run real-DAG graph contract suites and operator schema-contract suites, publish reusable graph products, and
   protect them with golden cases.

The showcase covers dashboard aggregation, product enrichment, enriched orders,
credit fallback, loan policy, and SSE search. The full endpoint catalog lives in
[REFERENCE.md](REFERENCE.md).

## Architecture At A Glance

![BLOGE visual canvas architecture](../docs/assets/bloge-visual-canvas-architecture.svg)

| Layer | Responsibility |
| --- | --- |
| Serving | Spring MVC gateway APIs, visual APIs, admin APIs, browser entry points |
| Orchestration | BLOGE DSL graphs schedule dependencies, fan-out, joins, transforms, decisions, and streams |
| Provider | `HttpResourceOperator` resolves descriptors, maps parameters, calls upstreams, validates response protocol, and extracts payload |
| Visual product surface | Catalog import/export, schema checks, draft validation, simulation, publication, golden cases, and run history |
| Controls | Durable run-control state, owner lease/epoch fencing, pre-run evidence reservation, side-effect claim/reconciliation, crash recovery sweeper/outbox, response cache, tenant rate limiting, circuit breaking, and tenant context |

Managed runs reserve `100 ms` by default for terminal-state and evidence finalization. BLOGE propagates the resulting
work budget through `OperatorContext`, scheduler admission, resilience timeout/retry, common HTTP calls, Resource Gateway
resources, and remote-worker envelopes. Override the reserve with
`resource-gateway.run-control.finalization-reserve-ms`; size it from measured evidence-finalization latency rather than
treating it as an operator timeout.

External-write operators declare `bloge.sideEffectProtocol.v1` and call `OperatorContext.beginSideEffect(...)` before
crossing the provider boundary. Missing contracts are DESIGN-only; a managed WRITE that returns without a journal attempt
is rejected. Descriptor-backed POST/PUT/PATCH/DELETE calls additionally require
`resourceGateway.externalWriteContract.v1`, an idempotency key, an evidence-safe lookup reference, and a provider receipt.
The Author palette shows `managed write` or `write protocol required` before a node is used. Provider-specific status
adapters implement `SideEffectReconciler`; Resource Gateway keeps original evidence immutable and appends a separately
signed reconciliation record. See the [product guide](../docs/bloge-visual-canvas-product-and-system-guide.md),
[conformance chain](../docs/assets/resource-gateway-side-effect-conformance-chain.svg), and
[reconciliation lifecycle](../docs/assets/resource-gateway-side-effect-reconciliation-lifecycle.svg).

Enterprise integration credentials can be verified from a live JWKS and versioned revocation feed. Resource Gateway
publishes key and revocation changes as one immutable snapshot, throttles unknown-`kid` refreshes, exposes refresh health
and propagation SLO through `/api/integration/capabilities`, and records organization/delegation facts without storing raw
tokens or group names. Authority outages return retryable 503; deterministically invalid credentials return 401. See the
[dynamic trust lifecycle](../docs/assets/resource-gateway-dynamic-jwks-trust-lifecycle.svg) and
[identity setup guide](../docs/bloge-visual-canvas-product-and-system-guide.md#31-调用-integration-api-前先建立受信身份).

Production evidence signatures can use a private-network KMS/HSM sidecar instead of the demo H2 key store. Resource
Gateway sends only the canonical evidence fingerprint and expected key version, rejects private material in provider
responses, and verifies the returned Ed25519 signature locally before persistence. Key discovery retains `VERIFY_ONLY`
history while distinguishing `DISABLED` and `REVOKED`; malformed trust material fails immediately, and transport outages
can use cached public keys only until the authority-declared expiry. Enable it with
`RG_EVIDENCE_SIGNING_MANAGED_ENABLED=true` and `RG_EVIDENCE_SIGNING_MANAGED_BASE_URI=https://...`. See the
[custody lifecycle](../docs/assets/resource-gateway-managed-evidence-signing-custody.svg) and
[managed signing setup](../docs/bloge-visual-canvas-product-and-system-guide.md#32-为运行证据启用-kmshsm-托管签名).

Offline release gates can now fetch one signed atomic policy from `GET /api/integration/evidence-keys`
instead of racing individual key reads. Managed key discovery v2 carries `notBefore/notAfter`,
`COMPLETE/CURRENT_STATE_ONLY`, and ordered activation/retirement/disable/revocation/compromise facts.
The independent test-kit requires an externally supplied snapshot fingerprint, validates the key-set
attestation and lifecycle invariants, and applies prospective or retroactive revocation at the evidence
signing time. The fingerprint returned in that same response is not a trust root. See the
[key lifecycle verification record](../docs/resource-gateway-execution-data-control-plane-stage3-key-lifecycle-verification.md),
[public key-set schema](../docs/schemas/tool-studio-resource-gateway/evidence-verification-key-set-v1.schema.json),
and [managed v2 schema](../docs/schemas/tool-studio-resource-gateway/managed-evidence-signing-keys-v2.schema.json).

Tool Studio draft export now reads one relevant-only dependency snapshot containing operator library revision,
runtime binding and activation state, contract-suite revision, schema fingerprints, and a normalized readiness result.
The service checks the draft and dependency fingerprint again after assembly. A concurrent relevant change returns
`409 RG.INTEGRATION.DRAFT_SNAPSHOT_CHANGED` instead of publishing a half-old, half-new bundle; unchanged revisions remain
byte-stable for idempotent consumers. Capability discovery exposes `graphDraftConsistentDependencySnapshot` and
`graphDraftStructuredDependencyRefs`. Scope-mismatched operators export only the draft-owned historical snapshot, never
the current restricted schema, owner, binding, activation, or suite. See the
[snapshot protocol](../docs/assets/resource-gateway-graph-draft-consistent-dependency-snapshot.svg),
[profile v2 schema](../docs/schemas/tool-studio-resource-gateway/graph-draft-dependency-profile-v2.schema.json), and
[product usage guide](../docs/bloge-visual-canvas-product-and-system-guide.md#33-导出可系统化导入的-graphdraft-一致依赖快照).

Replay payloads now have a lifecycle independent from immutable run evidence. New run records contain only shape facts,
a versioned policy descriptor, payload reference, and digest; sanitized values live in an expirable vault. Reads require
tenant/environment scope, purpose, classification clearance, and every policy-required group. `RESTRICTED` defaults to
no retention, expired or purged payloads return 410, legal hold freezes deletion, and each hold/release/purge transition
extends a signed hash chain. See the [lifecycle diagram](../docs/assets/resource-gateway-governed-payload-lifecycle.svg),
[payload replay v2 schema](../docs/schemas/tool-studio-resource-gateway/payload-replay-bundle-v2.schema.json), and
[usage guide](../docs/bloge-visual-canvas-product-and-system-guide.md#35-用-recorded-replay-重算正确性断言).

ANEKE workbook integration has two non-interchangeable protocols. `CorrectnessWorkbookBundle.v1` remains the frozen
draft/dependency projection for visual operator-contract tables. The exact-suite endpoint exports
`SemanticCorrectnessWorkbookBundle.v1` only for `bloge.testSuite.v2`, carrying typed semantic requirements and verified
terminal `bloge.testSuiteRunEvidence.v2` refs without case input, fixture payload, or free-text diagnostics. Structural v1
is rejected instead of being presented as empty semantic coverage; verification-authority outage is distinct from no
retained evidence. ANEKE must fetch each referenced portable bundle and verify it against an independently pinned key set.

ANEKE remains the workbook and publish-gate authority. Historical `GovernanceGateResult.v2` stays readable, while a
semantic `PASSED` decision uses `GovernanceGateResult.v3`: it records the exact suite target, reconstructable ordered
evidence closure, semantic bundle manifest facts, policy version, and every required check. Resource Gateway rebuilds the
original bundle from exact run ids, recompiles the exact GraphDraft to bind graph target fingerprints, and requires at least
one gate-ready graph suite. Stale or incomplete bases fail closed; a temporarily unavailable verification authority is shown
as `UNVERIFIABLE`; accepted gate results and their change events commit atomically. See the
[gate v3 schema](../docs/schemas/tool-studio-resource-gateway/governance-gate-result-v3.schema.json),
[evidence loop](../docs/assets/resource-gateway-workbook-gate-evidence-loop.svg),
[workbook schema](../docs/schemas/tool-studio-resource-gateway/correctness-workbook-bundle-v1.schema.json),
[semantic workbook schema](../docs/schemas/tool-studio-resource-gateway/semantic-correctness-workbook-bundle-v1.schema.json),
[semantic workbook verification](../docs/resource-gateway-execution-data-control-plane-stage3-aneke-semantic-workbook-verification.md),
[semantic gate verification](../docs/resource-gateway-execution-data-control-plane-stage3-semantic-gate-basis-verification.md), and
[usage guide](../docs/bloge-visual-canvas-product-and-system-guide.md#351-把-contract-suite-和-run-evidence-交给-aneke-workbook).

## Extend It

To add an external API:

1. Register a `ResourceDescriptor` through bootstrap config or the admin API.
2. Define parameter mapping, response protocol, and payload schema.
3. Use it as a `resource:<resourceId>` visual operator or lower it to
   `httpResource`.
4. Compose it in a `.bloge` graph or on the visual canvas.

When you add a new built-in `.bloge` graph under `src/main/resources/bloge/gateway`,
also add a `GatewayGraphContract` entry. This is not optional: `GatewayGraphService`
fails startup when a loaded graph has no contract, and
`GatewayGraphContractCatalogTest` scans every gateway `.bloge` file to catch
schema drift in CI. Runtime execution validates each context against the graph
`inputSchema`, and public gateway endpoints resolve terminal output through the
contract `outputNodes` before validating it against `outputSchema`;
`/api/gateway/graphs/contracts` exposes both schemas; and
`/api/gateway/examples/scenarios` mirrors the same schemas into showcase
metadata so the browser can show each example's Graph Contract;
`/api/gateway/graphs/contracts/tests/draft` generates editable mock rows from
the graph input schema and resource response schemas before
`/api/gateway/graphs/contracts/tests/run` executes table-driven suites against
the real graph through a fresh, run-scoped test engine with downstream APIs
replaced by deterministic, schema-gated fixture rules. The adapter now delegates
to the shared Execution Data Control Plane kernel: selector preflight rejects
zero-match and ambiguous plans, external effects fail closed, required fixtures
must be consumed, and node/edge/assertion evidence is captured without sharing
application interceptors, caches, quotas, circuit breakers, or durable stores.
Stored suites are available under
`/api/gateway/graphs/contracts/tests/suites`; each suite can carry a coverage
policy so batch runs fail when they lack enough cases, schema validations,
mocked calls, assertions, or required output-node coverage.
The built-in catalog covers all seven example graphs with 14 cases, 28
controlled resource-call observations, and 37 business assertions. Resource
rows use explicit F3 transport fixtures, so request mapping, URL rendering,
descriptor response protocol, and payload extraction stay real. Retry cases
declare `minUses/maxUses`, making credit-provider, wallet, and notification
attempt counts part of the pass/fail result. The testing kernel now recursively
freezes synchronous nested graphs and uses BLOGE run-scoped operator resolution
to control foreach, loop, subgraph, and compensation sites. Node evidence is
addressed by structural invocation site, runtime correlation, site occurrence,
and containing-graph occurrence, with retries retained as attempt facts. Fixture rules use the same
one-based attempt/occurrence coordinates to script retry recovery or nested graph re-entry; edge
evidence carries the same graph coordinates. `enrichOrderList` now certifies a
two-item parallel foreach case with independently controlled shipping and
invoice calls. Streaming/suspendable nested execution remains fail closed for
certification.

For the detailed contract-test design, request format, verification evidence,
and remaining industrialization gaps, see
[Resource Graph Schema Mock Table Testing](../docs/bloge-resource-graph-schema-mock-table-testing.md).
The industrial direction is defined by the
[Execution Data Control Plane and testability evolution plan](../docs/resource-gateway-industrial-testability-evolution-plan.md).
Stage 1 of its
[Execution Data Control Plane v1 blueprint](../docs/resource-gateway-industrial-testability-evolution-plan-1.0.md)
is implemented: it separates schema-only operator checks from executable micro-graph
tests, supplies `REAL/RETURN/THROW/DENY/SPY`, supports F2 protocol-derived and F3
transport-level HTTP fixtures, and records fingerprinted evidence. All seven built-in
graphs now dogfood that adapter under `clean verify`; the Spring integration proof points
descriptors at an unreachable address and still requires every suite to pass, catching any
root resource call that escapes its fixture. The generic public
testing API, persistent test-run store, independent JUnit test kit,
production-profile endpoint isolation, deterministic `DELAY/TIMEOUT`, and
synchronous nested invocation control are now available as Stage 2 increments.
Public operator execution, occurrence-level nested evidence, the Author Canvas executable
operator-suite adapter, and idempotent immutable-suite execution are available. The suite runner
supports graph/operator cases, `COLLECT_ALL`/`FAIL_FAST`, durable per-case checkpoints, structural
node/edge coverage, typed branch/decision/retry/fallback/timeout/compensation semantic coverage,
promotion eligibility, signed child-run evidence, and signed suite
checkpoint/terminal attestations at `POST /api/testing/suites/{suiteId}/executions`. Terminal runs
can be exported as payload-free generation-matched `bloge.testSuiteEvidenceBundle.v1/v2` values and independently verified
with the Java test-kit against a signed, externally pinned Ed25519 key-set. Governed exact-reference
REPLAY and dynamic attempt/occurrence selectors are available. Evidence key-set trust publications
now provide externally authorized M-of-N pin distribution, bounded append-only consistency pages,
durable consumer checkpoints, and rollback/fork/revoked-pin detection. Streaming/suspendable control,
real ANEKE N/N-1 conformance, independent witness gossip, and physical test-runtime deployment
isolation remain in progress and are not advertised as complete. Configuration and consumer flow are
documented in [Stage 3 evidence trust transparency verification](../docs/resource-gateway-execution-data-control-plane-stage3-evidence-trust-transparency-verification.md).

Stage 4 now also provides a profile-gated, content-addressed durable-test checkpoint repository in
the isolated test-runtime database. It binds the exact plan and fixture revision, fixture-consumption
cursors, deterministic provider state, engine-state closure, and owner/lease/revision fence. Current
`bloge.durableTestExecutionCheckpoint.v2` additionally binds the exact graph/operator kind, stable id,
and target fingerprint needed for recovery-time target resolution. Its database projections are
cross-checked against the sealed JSON. Historical v1 rows remain canonically readable with nullable
target columns but are not eligible for future public recovery until an independently verified target
mapping is migrated; the runtime never guesses from a fingerprint. Engine
store writes can join the same local transaction, and a losing CAS rolls them back. Staged BLOGE
`ExecutionStore`, `ExecutionCheckpointStore`, `WaitStore`, and `WorkItemStore` implementations are
combined under the `bloge.testDurableStateMutation.v3` aggregate fingerprint, so lifecycle/lease
state, node/loop/sequential-foreach checkpoints, signal/timer/task/retry waits, and
queued/claimed/retried/dead-lettered work commit or roll back together. Global timer/correlation and
ready/expired-work scans expose committed rows only; the active execution retains read-your-writes.
Ready work, expired work-item claims, and expired execution leases are selected, ordered, and bounded
in SQL through global and tenant-scoped recovery indexes before authoritative JSON is decoded. Each
returned candidate's tenant, namespace, type/status, shard, priority, lease times, and stable identity
projection is then compared with that JSON; drift fails closed. A scan defaults to 100 rows and is
capped at 10,000, preventing an accidental unbounded worker poll. This is a persistence primitive,
not a public dispatcher or cross-process worker supervisor.
Wait identity must match the lifecycle identity, and committed wait/work-item ids cannot migrate to
another execution. Work-item batches validate atomically, and claim, retry, terminal, and dead-letter
transitions reuse BLOGE's reference state machine. An internal database-clock lease claim can fence an
expired `ACTIVE`, `SUSPENDED`, or `RESUMING` owner by exact scope, old owner/epoch/revision, and
checkpoint fingerprint. It increments epoch and revision, enters `RESUMING`, and cannot alter the
recovery closure. A profile-gated durable command repository atomically binds a scoped caller key and
complete claim intent to that lease CAS and an immutable result snapshot, so an ambiguous retry returns
the original result while same-key different intent and stored-result tampering fail closed. A separate
command-record fingerprint detects indexed intent drift before classifying caller conflict. The
independent durable session attaches only this aggregate. The caller assigns
the engine execution id outside business context and supplies the complete frozen `ExecutionOptions`,
so operator fixture resolution and
deterministic providers survive unchanged. Missing stages, cross-execution writes, engine-state/id
mismatches, another datasource, post-close mutation, or checkpoint failures all fail closed; a
transient transaction rollback can replay the same content-addressed mutation. The run-scoped
`InvocationRecorder` now captures and restores rule-use and hashed site/graph occurrence cursors
only at a quiescent invocation boundary, and atomically enforces fixture `maxUses`. Cursor hashes omit
raw correlation values but are pseudonymous identifiers, not a confidentiality boundary. BLOGE's
fresh execution-to-durable-boundary API now feeds a strict initial-boundary policy: creation v1 may
prepare only one persisted `WAIT_SIGNAL` under a `SUSPENDED` execution. The session captures that
boundary, the fixture cursor, and the four-store aggregate as one immutable `PreparedRun`; terminal,
paused, timer/work-item/stream, and parallel multi-suspension outcomes discard the stage. A database-time
`bloge.durableTestCreationCommandRecord.v1` reservation now supplies scoped caller idempotency,
server-minted run/engine identities, owner fencing, lease-expiry takeover, immutable rejection/result
replay, and one atomic commit decision for the initial control checkpoint, four-store mutation, and
local audit. It stores fingerprints and payload-free locators only. The authenticated public creator
re-authorizes the exact graph, fixture, replay/authority/provider closure and plan, executes in that
stage, and returns or replays only the payload-free initial suspended view. BLOGE's synchronous
cold-start signal API is consumed by an internal `RecoverySession`: only a current
v2 `RESUMING` checkpoint with exact target and restorable provider state can restore fixture cursors,
open the staged aggregate, signal a real committed suspension, and prepare its next terminal or
single-suspension boundary for the same fenced transaction. Closing without prepare restores the
original committed wait and lifecycle; no detached recovery thread remains active. The public
owner-claim endpoint now binds exact dependency re-authorization to its `RESUMING` fence and a
payload-free worker dispatch. The authenticated public heartbeat resolves that hidden issued
dispatch from an exact predecessor fence, requires the original authorization principal, and rotates
the live revision/lease/successor dispatch atomically. The public terminal-recovery adapter consumes
one still-live dispatch, reconstructs the same executable authorization closure, runs one cold signal,
and commits the server-derived final BLOGE mutation, terminal checkpoint, immutable result, audit,
and payload-free receipt in one transaction; retries never reapply the engine mutation. Because durable
state still lacks complete pre-checkpoint node/edge/attempt trace, receipt v1 is always
`EVIDENCE_INCOMPLETE`, requires explicit gap codes, and blocks promotion. BLOGE streaming
offset/checkpoint state, complete historical evidence, operator-target durable creation, authenticated
worker poll/dispatch and multi-boundary orchestration, dispatcher consumption, cross-process worker
supervision, and a killable worker deadline are not wired yet. These internal primitives are
not a product claim that durable test
resume is complete. See
[Stage 4 durable checkpoint verification](../docs/resource-gateway-execution-data-control-plane-stage4-durable-checkpoint-verification.md).

Create a provider-specific Java operator only when the provider behavior cannot
be expressed cleanly as a descriptor-backed resource.

To add a user-supplied visual operator library:

1. Open `/author/`.
2. Paste a `bloge.visualOperatorLibrary.v1` JSON or YAML document that follows
   the [operator library schema guide](../docs/bloge-visual-operator-library-schema.md)
   and [machine schema](../docs/schemas/bloge-visual-operator-library.schema.json).
3. Validate and import it.
4. Use `/api/visual/operators/tests/draft` to generate editable operator mock
   rows from each operator's input/config/output schemas, then save or batch-run
   schema-contract checks through `/api/visual/operators/tests/suites`. This
   current mode validates fixture and schema consistency; it does not execute a
   real operator runtime binding. Results expose `mode=SCHEMA_CONTRACT`.
5. Double-click a canvas node and use `Executable Operator Suite` to run one case or the whole
   table through the isolated testing control plane. `Run Case` / `Run Exploratory` use inline
   fixtures for fast `EXPLORATORY` feedback. For governed evidence, choose a
   `Golden`/`Negative`/`Boundary`/`Regression` intent and use `Publish Case + Run` or
   `Publish Suite + Run`. The canvas registers content-addressed immutable fixtures with
   `TEST_FIXTURE_WRITE`, publishes one dependency-closed `bloge.testSuite.v1` revision with
   `TEST_SUITE_WRITE`, validates the complete returned identity, then executes that exact revision
   through `/api/testing/suites/{suiteId}/executions` with `TEST_EXECUTION`. It displays payload-free
   child run links plus aggregate execution, coverage, and promotion eligibility only after the
   stored suite, child evidence, assertions, coverage, promotion, and aggregate status pass
   fail-closed consistency checks. The table is read-only in flight; a later exploratory run clears
   the stale publication banner. Native operators
   run real code under a `SPY`; resource-backed visual operators
   lower to `httpResource` and replace only transport I/O, using the editable `Transport response`
   as the raw protocol fixture. Unsupported and `OPAQUE_RUNTIME` targets fail closed before
   execution. Stored provenance is necessary but not sufficient for `CERTIFIABLE` evidence: target
   composability, strict schema checks and fixture fidelity must also qualify. A
   stateless/read-only declaration is not enough for certification: non-resource bindings also need
   a versioned, fingerprinted `OperatorComposabilityManifestProvider` declaration.
   `httpResource` can earn `EXECUTABLE_UNIT` only with a transport-boundary fixture, so
   request mapping, URL rendering, response protocol, and payload extraction really execute.
6. Drag operators, wire schemas, simulate, and export.

The `/author/` built-in canvas examples also carry their own graph-level
input/output schemas, and exported drafts include the current `inputSchema` so
the design can be integrated instead of remaining a diagram-only artifact.

## Build And Verify

This is a standalone Maven project. BLOGE artifacts must already exist in the
local Maven repository.

```bash
mvn -f resource-gateway-examples/pom.xml \
    -Dtest=GatewayGraphContractTestServiceTest,ExecutionControlCompilerTest,TestRunServiceTest,ResourceFixtureRuntimeTest,OperatorMicroGraphRunnerTest,GraphArtifactFingerprintTest test
mvn -f resource-gateway-examples/pom.xml clean verify
mvn -f resource-gateway-test-kit/pom.xml clean verify
mvn -f resource-gateway-examples/pom.xml -Pfrontend package
mvn -f resource-gateway-examples/pom.xml spring-boot:run
```

If BLOGE core artifacts are missing, install them from the main BLOGE repo:

```bash
mvn -pl bloge-core,bloge-dsl,bloge-common-operators,bloge-spring,bloge-test \
    -am install -DskipTests -Dspotbugs.skip=true
```

Java 25 preview flags are already configured.

## Know The Boundary

- Runtime visual execution is request-response; streaming and durable operators
  are visible in catalog readiness but blocked from direct request-response runs.
- Remote worker, AI tool, event source, message handler, and webhook operators
  can be modeled; this example does not ship their production runtime plane.
- Multi-user collaboration and the complete enterprise IAM policy lifecycle remain outside this example. Dynamic
  JWKS/revocation, group/clearance/delegation claims, purpose authorization and tenant isolation are implemented; customer
  IdP certification, customer policy-engine conformance, group lifecycle/orphan ownership and emergency-access governance
  still require deployment-specific integration. The built-in payload policy is fail-closed and replaceable, not a
  substitute for the customer's authoritative classification registry.
- Managed evidence signing is implemented as a vendor-neutral KMS/HSM sidecar protocol and provider SPI. Production
  deployments still need a customer-specific provider identity/policy, authoritative key-use audit export, historical
  public-key retention, multi-region disaster recovery, and vendor conformance evidence; the default H2 signer remains
  demo-only.
- The visual core still lives inside `resource-gateway-examples`; it is shaped
  for future extraction, but is not yet a standalone artifact.

For full endpoint catalogs, implementation notes, tests, and historical detail,
use [REFERENCE.md](REFERENCE.md). For the visual canvas product guide, use
[docs/bloge-visual-canvas-product-and-system-guide.md](../docs/bloge-visual-canvas-product-and-system-guide.md).
