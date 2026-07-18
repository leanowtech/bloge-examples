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
| Isolated testing control plane | Test/staging-only graph/operator discovery, validator-proven boundary-case planning, reviewed plan-to-suite materialization, immutable fixture registry, caller-driven DAG and operator micro-graph execution, attempt/occurrence-specific doubles, sanitized evidence retention, batch runs, and production control-field guard |
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
| `GET http://localhost:8080/api/testing/targets/graphs/{graphName}/boundary-cases` | Generate bounded, validator-proven graph input candidates and explicit coverage gaps (test/staging only) |
| `GET http://localhost:8080/api/testing/targets/graphs/{graphName}/property-cases?seed=...` | Generate reproducible, bounded graph trials with validator-proven shrink paths; this is an authoring plan, not execution evidence (test/staging only) |
| `GET http://localhost:8080/api/testing/targets/graphs/{graphName}/mutation-cases?maxMutants=...` | Plan bounded, independently compiling pure-DSL graph mutants without changing external operators; planning is not execution, evidence, or a score (test/staging only) |
| `POST http://localhost:8080/api/testing/targets/graphs/{graphName}/mutation-suites` | Freeze an exact reviewed mutation plan, baseline fingerprints, oracle suite, complete matrix, and score policy as immutable V5 (test/staging only) |
| `POST http://localhost:8080/api/testing/targets/graphs/{graphName}/boundary-suites` | Materialize an explicitly selected, fingerprint-locked boundary-plan subset as an immutable schema-admission suite (test/staging only) |
| `POST http://localhost:8080/api/testing/targets/graphs/{graphName}/property-suites` | Freeze one exact property plan's complete root/shrink closure against an existing assertion-bearing fixture (test/staging only) |
| `POST http://localhost:8080/api/testing/suites/{suiteId}/executions` | Execute an exact immutable V1-V4 suite revision, including bounded property root/shrink closures, and emit signed aggregate evidence (test/staging only) |
| `POST http://localhost:8080/api/testing/suites/{suiteId}/mutation-executions` | Execute an exact V5 suite baseline-first, classify every regenerated mutant, and emit signed mutation-score evidence (test/staging only) |
| `POST http://localhost:8080/api/testing/suites/{suiteId}/stability-executions` | Execute one exact V1/V2/V4 suite 3..20 times and retain signed payload-free stability evidence (test/staging only) |
| `GET http://localhost:8080/api/testing/stability-executions/{stabilityRunId}` | Read one retained stability analysis with its exact ordered source-run closure and detached signature (test/staging only) |
| `POST http://localhost:8080/api/testing/executions` | Run an isolated inline or governed fixture plan and retain sanitized evidence (test/staging only) |
| `POST http://localhost:8080/api/testing/durable-executions` | Idempotently create an exact graph test at its first unique signal suspension (test/staging only) |
| `POST http://localhost:8080/api/testing/durable-executions/operators/{operatorRef}` | Idempotently freeze an exact operator test at its server-owned start gate (test/staging only) |
| `GET http://localhost:8080/api/testing/durable-executions/{runId}` | Inspect an integrity-verified, payload-free durable checkpoint view before recovery (test/staging only) |
| `POST http://localhost:8080/api/testing/durable-executions/worker-acquisitions` | Pull at most one authorized expired execution through an atomic payload-free worker assignment (test/staging only) |
| `POST http://localhost:8080/api/testing/durable-executions/{runId}/owner-claims` | Re-authorize an exact expired v2 checkpoint and atomically claim its lease; this does not resume BLOGE (test/staging only) |
| `POST http://localhost:8080/api/testing/durable-executions/{runId}/heartbeats` | Renew one exact issued recovery fence under the same authenticated authority (test/staging only) |
| `POST http://localhost:8080/api/testing/durable-executions/{runId}/recovery-steps` | Signal one exact claimed suspension and atomically commit the next suspended or terminal boundary (test/staging only) |
| `POST http://localhost:8080/api/testing/durable-executions/{runId}/recovery-sequences` | Automatically consume a reserved sequence of up to 16 signals across freshly claimed suspension boundaries (test/staging only) |
| `POST http://localhost:8080/api/testing/durable-executions/{runId}/terminal-recoveries` | Signal one exact claimed suspension and atomically commit only a server-derived terminal result (test/staging only) |
| `GET http://localhost:8080/api/testing/targets/operators/{operatorRef}` | Inspect frozen binding/schema/state fingerprints and executable testability (test/staging only) |
| `GET http://localhost:8080/api/testing/targets/operators/{operatorRef}/boundary-cases` | Project an operator input schema and generate bounded, validator-proven candidates (test/staging only) |
| `GET http://localhost:8080/api/testing/targets/operators/{operatorRef}/property-cases?seed=...` | Generate reproducible, bounded operator trials while disclosing schema-projection and generation gaps (test/staging only) |
| `POST http://localhost:8080/api/testing/targets/operators/{operatorRef}/boundary-suites` | Materialize selected operator boundary candidates under suite-write authority (test/staging only) |
| `POST http://localhost:8080/api/testing/targets/operators/{operatorRef}/property-suites` | Materialize the complete reviewed operator property plan as immutable executable `bloge.testSuite.v4` (test/staging only) |
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
./scripts/start-visual-canvas-demo.sh --profile production
./scripts/visual-canvas-demo.sh status
./scripts/visual-canvas-demo.sh restart
```

`staging` requires two independent deployment-secret key rings before `--profile staging`:
`RG_TEST_WORKER_QUARANTINE_TOKEN_ACTIVE_KEY_ID` plus
`RG_TEST_WORKER_QUARANTINE_TOKEN_KEY_RING` protect claim replay/control credentials, while
`RG_TEST_WORKER_QUARANTINE_REQUEST_KEY_ACTIVE_KEY_ID` plus
`RG_TEST_WORKER_QUARANTINE_REQUEST_KEY_RING` protect low-entropy request tombstone indexes. Ring
values use `keyId=base64-encoded-32-byte-key[,oldKeyId=...]`.
`RG_TEST_WORKER_QUARANTINE_REQUEST_INDEX_WRITE_MODE` is also required and must be
`LEGACY_READ_WRITE`, `DUAL_READ_KEYED_WRITE`, or `KEYED_ONLY`. The launcher fails early when any
value is absent or the mode is invalid. Staging also fails closed unless the independent external
discard-authorization trust is complete: set
`RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_TRUST_DOMAIN`,
`RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_POLICY_FINGERPRINTS`,
`RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_SIGNATURE_THRESHOLD`, and
`RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_AUTHORITY_KEYS_JSON`. The last value is a JSON array of
external authority IDs, key IDs, validity windows, state, and X.509-encoded Ed25519 public keys;
private keys never belong in Resource Gateway. The public capability probe reports quorum readiness
and counts without key material. The `test` profile defaults to unavailable external trust, so
discard approval returns `503` until all four values are supplied. Its committed local symmetric
keys are demonstration-only and must not be reused.
Worker-quarantine detailed replay defaults to 30 days, token-free history and request tombstones to
365 days, and leased cleanup to 100 rows per category every hour. Override these with
`RG_TEST_WORKER_QUARANTINE_COMMAND_RETENTION_DAYS`,
`RG_TEST_WORKER_QUARANTINE_HISTORY_RETENTION_DAYS`,
`RG_TEST_WORKER_QUARANTINE_TOMBSTONE_RETENTION_DAYS`,
`RG_TEST_WORKER_QUARANTINE_RETENTION_PAGE_SIZE`, and
`RG_TEST_WORKER_QUARANTINE_RETENTION_INTERVAL_MS` before startup.

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
builders, typed catalog materialization, exact suite execution, signed bounded stability analysis,
pinned-key-set offline verification, payload-free assertions/XML, and the fail-closed CLI instead of
hand-assembling HTTP requests or interpreting aggregate evidence ad hoc. The stability protocol's
v2 evidence keeps behavioral stability separate from release eligibility: every verified source
suite promotion verdict is signed into the attempt closure, so `STABLE + BLOCKED` remains visible
when behavior is repeatable but source certification is insufficient. Historical v1 evidence stays
auditable but cannot enter a release gate. The invariants and deliberately unclaimed statistical
guarantees are recorded in
[Stage 5 suite-stability verification](../docs/resource-gateway-execution-data-control-plane-stage5-suite-stability-verification.md).

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

The graph creation v1 contract accepts only exact graph targets and stored fixtures and succeeds only at one unambiguous
persisted signal wait. The response wraps a payload-free suspended execution view containing the
server-minted run id and recovery fence. A committed success or deterministic unsupported-boundary
rejection is replayed for the same authenticated `clientRequestId`; a live concurrent preparation
returns `409` with its run id and lease deadline. This graph endpoint does not accept operator
targets, inline/latest dependencies, timers/tasks/streams, terminal fresh runs, or multiple live
suspensions.

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

### Create a durable operator test

Discover the exact operator binding and publish a stored fixture revision first. The operator
contract is versioned separately from graph creation:

```bash
curl -sS -X POST \
  http://localhost:8080/api/testing/durable-executions/operators/creditScore \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_EXECUTION' \
  -H 'Content-Type: application/json' \
  -d '{
    "schemaVersion": "bloge.durableOperatorTestExecutionCreateRequest.v1",
    "clientRequestId": "create-credit-score-20260717-01",
    "target": {
      "kind": "OPERATOR",
      "id": "creditScore",
      "fingerprint": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    },
    "executionPurpose": "OPERATOR_UNIT_TEST",
    "input": {"customerId": "C-42", "annualIncome": 180000},
    "fixtureBundleRef": {
      "fixtureBundleId": "credit-score-fixture",
      "revision": 4,
      "fingerprint": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
  }'
```

Resource Gateway validates and converts the formal input, then persists a canonical two-node graph:
the server-owned `durable-operator-start` gate followed by the exact `subject` binding. Revision zero
commits while the gate is suspended, before the business operator is invoked. Claim the run and send
a terminal-recovery signal to that gate to execute the subject exactly once from the frozen input,
fixture cursor, provider state, and authorization closure. Signal data is ignored by the gate and
cannot replace business input. Responses, commands, and audits remain payload-free.

Graph and operator creation share the same idempotency namespace, four-dimensional admission,
database-time preparation lease, staged four-store aggregate, atomic checkpoint/audit commit, query,
owner claim, heartbeat, and terminal recovery. The internal start gate also occupies a conservative
operator admission slot. Worker polling, multi-boundary orchestration, hard cancellation, and
complete pre-checkpoint trace evidence remain outside this increment. Full semantics are in the
[Testing Control Plane API](../docs/resource-gateway-testing-control-plane-api.md#42e-create-one-durable-operator-execution).

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

### Pull one durable worker assignment

A recovery worker that does not already know a `runId` can ask for at most one assignment in its
verified tenant, organization, project, and environment scope:

```bash
curl -sS -X POST \
  http://localhost:8080/api/testing/durable-executions/worker-acquisitions \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_EXECUTION' \
  -H 'Content-Type: application/json' \
  -d '{
    "schemaVersion": "bloge.durableTestWorkerAcquisitionRequest.v1",
    "clientRequestId": "worker-poll-20260717-001"
  }'
```

The caller cannot send a run selector, queue scope, owner, lease, priority, or candidate limit. The
server scans expired candidates from a persisted cyclic keyset position within the authenticated
scope, up to
`RG_TEST_DURABLE_WORKER_CANDIDATE_LIMIT` (default `32`, valid `1..1000`), re-authorizes each exact
dependency closure, and atomically commits either `ACQUIRED` or `NO_WORK`. `ACQUIRED` contains the
same payload-free owner/epoch/revision/checkpoint fence used by heartbeat and terminal recovery;
the authorization-bound dispatch remains internal. `NO_WORK` means that the bounded scan produced
no claimable assignment, not that a global queue is empty.

Both outcomes are immutable under `clientRequestId`, including `NO_WORK`. Retry a lost response with
the same key; use a new key for a later poll. Lease CAS, hidden dispatch, command result, and semantic
audit share one transaction with progress through the last examined candidate. The database cursor
wraps from the ordered tail to the head, so a full ineligible prefix cannot permanently hide later
work; cursor state and scope projections are fingerprint-verified, and stale concurrent progress
cannot move it backward.

Legacy checkpoints and exact authorization `403`/`409` outcomes create a database-timed negative
scheduling cache for that checkpoint fingerprint. An active record skips the authority call but not
scan progress; a due repeat doubles from `RG_TEST_DURABLE_WORKER_INITIAL_BACKOFF_SECONDS` (default
`5`) to `RG_TEST_DURABLE_WORKER_MAXIMUM_BACKOFF_SECONDS` (default `300`). Infrastructure failures do
not create a deferral or advance the cursor. After
`RG_TEST_DURABLE_WORKER_QUARANTINE_THRESHOLD` consecutive same-reason failures (default `32`), the
winning cursor transaction converts the exact checkpoint from temporary backoff to permanent worker
quarantine. Quarantined candidates still advance the cyclic scan but are never re-authorized or
claimed by worker pull merely because time passed. Checkpoint replacement or an explicit successful
state transition clears the old fingerprint's scheduling state.

This is non-blocking pull control with automatic exact-checkpoint isolation, not runtime-state
delivery or a complete scheduler: it does not transfer BLOGE runtime state to the caller, reserve an
execution-capacity permit while idle, or provide tenant weighting/priority/aging. A separate
`TEST_RUNTIME_MAINTENANCE` protocol now provides scoped, payload-free quarantine list/history,
server-fenced claims, idempotent `RELEASE`, and database-authoritative two-person `DISCARD`. A
separate approver group creates a token-free, short-lived approval for the exact live maker claim;
the maker then proves its secret fence and atomically consumes that approval. New direct legacy
`DISCARD` commands are rejected. Exact claim-response replay is encrypted with a rotation-aware
AES-256-GCM key ring, while the live control keeps only a domain-separated HMAC-SHA-256 verifier;
`staging` requires that claim-token root-key ring to be injected explicitly. A database-leased,
bounded retention loop later replaces detailed claim/resolution/approval/discard replay rows with
request-key tombstones that contain neither the raw request ID nor claim token. New tombstones use an
independent, domain-separated HMAC-SHA-256 request index with bounded online key rotation; live rows
whose key is unavailable block readiness, old-key/legacy rows lazily re-key on exact access, and
expired rows remain purgeable. A three-stage legacy/dual/keyed-only write-mode protocol now keeps
mixed N/N-1 deployment on old-readable rows until the deployment authority proves every serving
instance is N; each replica publishes its exact mode and rejects incompatible live generations at
readiness. An authenticated rollout endpoint also issues a short-lived Ed25519 proof binding an
external challenge to this process start, deployment-supplied instance and artifact identities,
exact mode, protocol version, and DB-clock live-generation inventory. This is a per-process fact,
not service discovery: the deployment platform must still supply and exhaustively verify the exact
serving inventory. The loop independently purges token-free history and permits request-ID
reuse only after the tombstone window. Exact semantics are documented in
[Stage 4 worker candidate backoff verification](../docs/resource-gateway-execution-data-control-plane-stage4-worker-candidate-backoff-verification.md),
the [worker quarantine maintenance verification](../docs/resource-gateway-execution-data-control-plane-stage4-worker-quarantine-maintenance-verification.md),
the [two-person discard verification](../docs/resource-gateway-execution-data-control-plane-stage4-worker-quarantine-two-person-discard-verification.md),
the [claim-token protection verification](../docs/resource-gateway-execution-data-control-plane-stage4-worker-quarantine-claim-token-protection-verification.md),
the [bounded retention verification](../docs/resource-gateway-execution-data-control-plane-stage4-worker-quarantine-retention-verification.md),
the [request-index protection verification](../docs/resource-gateway-execution-data-control-plane-stage4-worker-quarantine-request-index-protection-verification.md),
the [request-index rolling-upgrade verification](../docs/resource-gateway-execution-data-control-plane-stage4-worker-quarantine-request-index-upgrade-verification.md),
and the [signed replica-proof verification](../docs/resource-gateway-execution-data-control-plane-stage4-worker-quarantine-request-index-replica-proof-verification.md).

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

### Advance one durable recovery step

For a graph that can suspend more than once, use the latest exact claim/heartbeat fence with the
one-step protocol:

```bash
curl -sS -X POST \
  http://localhost:8080/api/testing/durable-executions/run-20260716-001/recovery-steps \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_EXECUTION' \
  -H 'Content-Type: application/json' \
  -d '{
    "schemaVersion": "bloge.durableTestRecoveryStepRequest.v1",
    "clientRequestId": "step-run-20260716-001-1",
    "expectedFence": {
      "ownerId": "server-issued-owner",
      "leaseEpoch": 4,
      "revision": 9
    },
    "expectedCheckpointFingerprint": "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
    "signal": {"nodeId": "approval-wait", "data": {"approved": true}}
  }'
```

The response is payload-free and has `outcome=SUSPENDED` with `terminal: null`, or one of five
terminal outcomes with a promotion-blocking receipt projection. A suspended commit releases the
consumed lease at database time. Acquire the new checkpoint again before sending the next signal;
the old dispatch is intentionally unusable. Reuse the same key and identical intent only to recover
an ambiguous response. Caller-owned outcome, engine/provider/fixture state, lease, evidence, or
dispatch fields are rejected.

### Advance a bounded recovery sequence

When the complete ordered signal fixture is already known, use the sequence protocol to avoid
manually claim/step chaining every suspension:

```bash
curl -sS -X POST \
  http://localhost:8080/api/testing/durable-executions/run-20260716-001/recovery-sequences \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_EXECUTION' \
  -H 'Content-Type: application/json' \
  -d '{
    "schemaVersion": "bloge.durableTestRecoverySequenceRequest.v1",
    "clientRequestId": "sequence-run-20260716-001-1",
    "expectedFence": {
      "ownerId": "server-issued-owner",
      "leaseEpoch": 4,
      "revision": 9
    },
    "expectedCheckpointFingerprint": "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
    "signals": [
      {"nodeId": "risk-approval", "data": {"approved": true}},
      {"nodeId": "finance-approval", "data": {"approved": true}}
    ]
  }'
```

The server reserves a fingerprint of the complete sequence before the first signal runs, then
derives stable child keys and performs a fresh authorized owner claim after every committed
suspension. A retry of the unchanged outer request replays the committed prefix and continues at
the first unfinished step. The response reports ordered payload-free `steps`, consumed/provided
counts, and either `stopReason=TERMINAL` or `SIGNALS_EXHAUSTED`. Limits are 16 signals, 256 KiB per
signal, and 1 MiB for the sequence.

Detailed outer and server-derived child commands remain exactly replayable for 30 days from the
first reservation by default. A request accepted before that absolute deadline advances an
integrity-protected database activity fence for one more command window, so retention cannot race
its in-flight child writes; the fence does not extend the replay deadline. A database-leased,
bounded retention page then verifies every row, atomically replaces the outer request with a scoped
keyed-HMAC tombstone, and erases its derived steps, intermediate claims, and automatic heartbeats.
After the absolute deadline and during the default 365-day tombstone window, an exact retry returns
`409 RG.TEST.DURABLE_RECOVERY_SEQUENCE_REPLAY_WINDOW_EXPIRED`; changed intent under the same key
remains an idempotency conflict. Only tombstone expiry permits key reuse. Staging deployments must
set `RG_TEST_DURABLE_RECOVERY_SEQUENCE_RETENTION_INSTANCE_ID`,
`RG_TEST_DURABLE_RECOVERY_SEQUENCE_REQUEST_KEY_ACTIVE_ID`, and
`RG_TEST_DURABLE_RECOVERY_SEQUENCE_REQUEST_KEY_RING`. Roll out a new verification key to every
replica before making it active, and keep an old key until all tombstones written with it have
expired; a restarted replica fails closed when a referenced generation is missing.

The `test` and `staging` profiles also install a fail-closed Actuator health component for this
lifecycle. Every 30 seconds by default it reads one repeatable-read, database-clock snapshot and
checks last successful retention age, ready-to-delete sequence count/oldest eligible age, and
expired tombstone count/oldest expiry age. Store failure is `DOWN`, policy violation is
`OUT_OF_SERVICE`, and first-start grace is `UNKNOWN`; health details contain only stable violation
codes, aggregate counts, and ages. Capability discovery exposes
`durableRecoverySequenceRetentionSloHealth`. Tune the `RG_TEST_DURABLE_RECOVERY_SEQUENCE_SLO_*`
limits before using the health aggregate as a deployment readiness gate.

This synchronous helper is not a background queue, remote runtime-state dispatcher, cross-process
supervisor, hard-cancellation mechanism, backup-erasure protocol, or external evidence archive.

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

Reviewed schema-boundary plans can now be materialized as immutable `bloge.testSuite.v3` assets and executed without
calling the graph or operator. A successful run emits generation-matched
`testSuiteExecutionResponse.v4` / `testSuiteRunEvidence.v3` / `testSuiteRunAttestation.v3`, with typed validator
observations, exact plan/schema/generator provenance, and a signed empty business-child closure. Structural coverage stays
`NOT_EVALUATED` and promotion stays `BLOCKED`; this proves schema admission, not business correctness. The capability probe
advertises `schemaAdmissionSuiteExecution` only with the isolated testing runtime. See the
[testing API guide](../docs/resource-gateway-testing-control-plane-api.md#418-execute-and-verify-a-schema-admission-suite)
and [standalone test-kit guide](../resource-gateway-test-kit/README.md).

Property testing has a separate, deliberately bounded lifecycle. A seeded
`bloge.testPropertyCasePlan.v1` can be materialized as `bloge.testSuite.v4` only after the service
regenerates and matches the target, input-schema, and plan fingerprints. V4 freezes every root and
shrink input in order, binds one existing assertion-bearing fixture revision, and keeps
`BOUNDED_SAMPLED` plus `exhaustive=false` as canonical facts. Raw V4 registration and `PROPERTY`
cases in V1-V3 are rejected. The exact V4 revision is executable when the isolated testing runtime
is enabled. Each root and its frozen shrink path runs through the ordinary authorized child runner;
the aggregate emits generation-matched `testSuiteExecutionResponse.v5`,
`testSuiteRunEvidence.v4`, `testSuiteRunAttestation.v4`, and `testSuiteEvidenceBundle.v4`.
`FAIL_FAST` finishes the already-started root's shrink path before stopping, so the observed
counterexample is reproducible. It is minimal only within that precomputed path and always carries
`globallyMinimal=false`. Durable checkpoints, idempotent replay, lease loss, terminal persistence
failure, and abandoned-run reconciliation remain fail-closed without re-executing business input.
Capability discovery reports both `propertySuiteMaterialization=true` and
`propertySuiteExecution=true` only when the execution endpoint is available. See the
[property materialization API](../docs/resource-gateway-testing-control-plane-api.md#415-materialize-a-reviewed-property-plan)
and [property execution verification](../docs/resource-gateway-execution-data-control-plane-stage5-property-execution-verification.md).

Pure-DSL mutation testing has a bounded, evidence-bearing lifecycle. Planning validates recoverable
`bloge-dsl.ast.v1` against the current graph and frozen dependencies, and returns only independently
compiling orchestration mutations with content fingerprints. V5 materialization then regenerates the
reviewed plan, binds exact baseline/source/artifact/target fingerprints, an executable V1/V2/V4 oracle
suite and every fixture, and freezes at most 16 mutants x 16 cases under one score policy. Callers
cannot upload mutated source or trim the matrix.

The dedicated mutation runner executes the full baseline first, regenerates each mutant server-side,
and reuses the baseline-bound inputs and fixtures in the isolated test engine. Only signed assertion
failure kills a mutant; runtime, fixture, timeout, control, target, or evidence failures remain
inconclusive. `STOP_AFTER_KILL` stops only the current mutant and never skips later mutants. Durable
idempotency, lease checkpoints, V5 terminal signatures, portable bundles, and abandoned-run
reconciliation remain fail closed without re-executing completed children. Capability discovery
advertises planning, materialization, execution, and score evidence independently and enables all four
only with the isolated testing runtime. See the
[mutation lifecycle API](../docs/resource-gateway-testing-control-plane-api.md#419-materialize-an-exact-mutation-matrix)
and [Stage 5 mutation execution verification](../docs/resource-gateway-execution-data-control-plane-stage5-mutation-execution-verification.md).

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
An independent profile-gated anti-entropy loop walks both authority tables by primary-key cursor,
so status, shard, time, or even tenant projection corruption cannot hide a row from inspection. It
defaults to 100 rows per table every 60 seconds and `REPAIR_DERIVED`: safe derived columns are rebuilt
from the authoritative JSON with an identity/scope/payload compare-and-set. Primary key, work-item
execution ownership, tenant, and namespace drift and unreadable JSON are reported but never moved or
guessed. Configure
`gateway.testing.durable.projection-reconciliation-mode=AUDIT_ONLY` for observation-only rollout,
`gateway.testing.durable.projection-reconciliation-page-size` for a `1..1000` page, and
`gateway.testing.durable.projection-reconciliation-interval-ms` for the fixed delay. Set
`gateway.testing.durable.projection-reconciliation.instance-id` to a stable replica identity and
`gateway.testing.durable.projection-reconciliation.lease-duration-seconds` to the database-clock
sweep lease (`1..3600`, default `120`). A generated process identity is used when no instance ID is
configured.

The two keyset cursors, sweep lease/epoch/token, and payload-free finding lifecycle are durable.
Only one replica may sweep a page. Projection repair, finding upsert/consistent-recheck resolution,
and cursor checkpointing commit together in one test-runtime database transaction. A crash rolls that page back,
while the separately committed lease eventually expires for takeover. Unrepairable or raced findings
enter an internal owner queue. Claims use a server-minted token, version, owner, and database-clock
lease; manual resolution rejects stale, forged, and expired fences. Stored findings contain internal
row IDs, column names, classifications, and counters, never authority JSON or business values. Logs
still contain aggregate counts only.

The owner queue now has a profile-gated authenticated operations protocol:

```bash
RG_INTEGRATION_GROUPS=resource-gateway-test-runtime-operators \
RG_INTEGRATION_CLEARANCE=RESTRICTED \
./scripts/start-visual-canvas-demo.sh --profile test

curl -sS 'http://localhost:8080/api/testing/durable-state/projection-findings?actionableOnly=true&limit=100' \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_RUNTIME_MAINTENANCE'
```

`POST .../claims` derives owner from verified identity and returns the server-minted token only in
the successful claim response. `POST .../resolutions` accepts the exact key/token/version/deadline
fence and only `MANUALLY_REPAIRED` or `QUARANTINED`. Both commands require a caller-stable
`clientRequestId`; an exact retry returns the original receipt, while same-key fact drift returns
409. First claim/resolution state and its token-free semantic action event commit in one
test-runtime transaction. Rejected and replay attempts are appended separately. Configure the
deployment-owned role with `RG_TEST_PROJECTION_FINDING_REQUIRED_GROUP` and minimum clearance with
`RG_TEST_PROJECTION_FINDING_REQUIRED_CLEARANCE`; defaults are
`resource-gateway-test-runtime-operators` and `RESTRICTED`. The endpoint is vetoed by `production`.

Resolved finding lifecycles now move through a second database-leased control loop. The active queue
retains a resolved row for 30 days by default; one bounded transaction then copies its token-free
classification, timestamps, counters, resolution, source revision, and canonical fingerprint into
`rg_test_bloge_projection_finding_archive` and deletes the exact source row. Claim owner/token,
idempotency request IDs/fingerprints, resolution owner, authority JSON, and field values are never
copied. Archive reads recompute the whole-record fingerprint and fail closed on drift. An independent
365-day archive retention deletes at most one configured page per tick. Archive insert, exact source
delete, archive purge, and cumulative counters commit or roll back together; a database-clock lease
prevents multiple replicas from applying the page concurrently.

Configure `RG_TEST_PROJECTION_FINDING_RESOLVED_RETENTION_DAYS` (`1` to `3650`, default `30`),
`RG_TEST_PROJECTION_FINDING_ARCHIVE_RETENTION_DAYS` (`1` to `3650`, default `365`),
`RG_TEST_PROJECTION_FINDING_RETENTION_PAGE_SIZE` (`1..1000`, default `100`), and
`RG_TEST_PROJECTION_FINDING_RETENTION_INTERVAL_MS` (default one hour). The resolved retention API
also supports a one-hour lower bound, while day-based Spring configuration deliberately rounds the
operator-facing policy to whole days. Retention uses the same stable
`gateway.testing.durable.projection-reconciliation.instance-id` identity and
`gateway.testing.durable.projection-reconciliation.lease-duration-seconds` database-clock lease as projection
anti-entropy, but owns an independent lease row and cannot block the scan cursor. After active retention elapses, the ordinary finding endpoint
no longer returns that historical lifecycle; the archive is currently an internal persistence and
readiness surface, not a public evidence API. Its unkeyed fingerprint catches ordinary row drift but
does not prove external immutability.

The test/staging profile also installs a database-clock SLO health indicator and fixed-cardinality
Micrometer meters for these two control loops. One transactionally consistent snapshot measures
open/live-claimed/expired-claim/resolved findings, overdue active/archive retention, and both durable
last-success timestamps without exporting row IDs, tokens, exception text, or payloads. Health is
`UP`, `UNKNOWN`, `OUT_OF_SERVICE`, or `DOWN` for `HEALTHY`, startup `INITIALIZING`, stable SLO
violations, or store unavailability. Defaults allow 180 seconds for startup and reconciliation,
three hours for retention, one hour for the oldest unresolved finding, and zero unresolved or
overdue rows. Configure these under `gateway.testing.durable.projection-slo.*` or the corresponding
`RG_TEST_PROJECTION_SLO_*` variables. `/actuator/health` is the only default Actuator web exposure;
metric export remains a deployment-owned registry/exporter decision, and health details stay hidden
unless management security explicitly permits them.

The same isolated profile now installs a second, global `testRuntimeSloMonitor`. In one read-only,
repeatable-read transaction it uses the database clock to observe recent child/suite evidence
completeness, running suite ownership, pending durable creation, resumable durable execution,
dispatchable/expired-claim work, and expired or terminal storage backlog. Business assertion,
negative-case, and product-under-test failures remain outcome metrics and never make the runtime
unhealthy. Only incomplete evidence, excessive/old queues, expired ownership, retention backlog, or
store unavailability produce stable SLO violations. Fixed-vocabulary meters live under
`resource.gateway.test.runtime.*`; tags are limited to closed `status`, `queue`, `scope`, and `kind`
values. Deterministic worker deferrals add only the closed `reason` tag plus untagged retry-due,
maximum-failure, and oldest-age gauges. Worker quarantines add the same closed `reason` vocabulary,
fixed `AVAILABLE`/`CLAIMED` maintenance-state gauges, and untagged maximum-failure, oldest-age,
expired-claim, retained-history, live/expired discard-approval, and approved-discard-history gauges.
Expired claims and expired unconsumed approvals have separate stable health violations.
Configure thresholds through
`gateway.testing.runtime-slo.*` / `RG_TEST_RUNTIME_SLO_*`; the full table is in the testing
control-plane guide. Lifecycle/time indexes support these aggregate reads, and the outcome lookback
is hard-capped at 365 days.

The isolated profile now also enforces database-authoritative admission before any test engine starts.
Tenant, suite, recursively reachable operator, and conservative external-dependency claims are acquired
all-or-nothing with database-clock renewable leases. Direct graph/operator runs and sequential batch
children acquire their own permits; an immutable suite acquires its complete closure once so its child
cases cannot self-deadlock; fresh durable creation and terminal recovery hold capacity through their
first committed boundary. Saturation returns stable `429` problems plus `Retry-After`, policy drift and
store loss fail closed, raw subject names are hashed before persistence, expired leases are reclaimed in
bounded pages, and application shutdown releases local permits. Configure limits and lease behavior
under `gateway.testing.admission.*` / `RG_TEST_ADMISSION_*`; the full table and rollout rules are in the
[testing control-plane guide](../docs/resource-gateway-testing-control-plane-api.md#4212-database-authoritative-runtime-admission).

This is immediate admission backpressure, not a queued scheduler. Priority/fairness queues,
runtime-state delivery to a remote worker, cross-process supervision, adaptive autoscaling, hard
cancellation and wall-clock worker deadlines, external alert routing, non-H2 dialect certification,
tamper-evident external anchoring, and production-load certification remain future work.

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
offset/checkpoint state, complete historical evidence, runtime-state worker dispatch and
multi-boundary orchestration, dispatcher consumption, cross-process worker
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
