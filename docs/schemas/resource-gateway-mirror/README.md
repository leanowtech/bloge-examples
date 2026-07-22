# Resource Gateway Mirror Protocol Schemas

This directory is the wire-contract authority for the Resource Gateway capability-mirror protocol.
Every protocol envelope is strict (`additionalProperties: false`) and independently versioned; only the nested
business `context` map in an execution command intentionally accepts caller-defined keys. Server protocol
objects have field-closure tests in `resource-gateway-examples`; cross-system compatibility and
offline artifact verification live in the independent `resource-gateway-test-kit`.

| Schema | Java model | Purpose |
|---|---|---|
| `artifact-provenance-v1.schema.json` | `ArtifactProvenance` | Trust level, source lineage, confidence, approval, expiry, and revocation |
| `effect-contract-v1.schema.json` | `EffectContract` | Conservative transitive read/write/effect and risk summary |
| `capability-contract-v1.schema.json` | `CapabilityContract` | Input/output/error/effect/idempotency/security/SLO contract |
| `capability-snapshot-v1.schema.json` | `CapabilitySnapshot` | Immutable Resource/Operator/Graph projection consumed by mirror planning |
| `capability-closure-v1.schema.json` | `CapabilityClosure` | Exact root plus every transitively reachable snapshot for registry-free planning |
| `mirror-plan-v1.schema.json` | `MirrorPlan` | Sealed payload-free execution generation with exact external-edge bindings and isolation policy |
| `mirror-plan-create-request-v1.schema.json` | `MirrorPlanCreateRequest` | Payload-free protected compile command containing only reviewed artifact identities and bounded requested budgets |
| `mirror-execution-request-v1.schema.json` | `MirrorExecutionRequest` | Strict execution command containing only request/plan identity, reviewed plan fingerprint, and business context |
| `mirror-run-summary-v1.schema.json` | `MirrorRunSummary` | Compact payload-free terminal projection derived from verified evidence |
| `mirror-resolution-v1.schema.json` | `MirrorResolution` | Fingerprinted per-attempt source, confidence, freshness, payload visibility, output/error, and abstention provenance |
| `mirror-run-evidence-v1.schema.json` | `MirrorRunEvidence` | Payload-free node, edge, resolution, semantic-result, request-context, and isolation facts for one terminal run |
| `mirror-evidence-attestation-v1.schema.json` | `MirrorEvidenceAttestation` | Domain-separated detached Ed25519 signature over one complete mirror run evidence value |
| `mirror-evidence-bundle-v1.schema.json` | `MirrorEvidenceBundle` | Portable `HASH_ONLY` evidence, attestation, and complete bundle fingerprint closure |
| `capability-lifecycle-transition-v1.schema.json` | `CapabilityLifecycleTransitionRequest` | Optimistically fenced governance transition for one exact revision |
| `capability-mirror-compatibility-v1.schema.json` | `CapabilityMirrorCompatibility` | Minimum protocol/object/feature baseline a mirror consumer can negotiate |

`capability-mirror-stage0-v1.fixture.json` is the authoritative Stage 0 compatibility fixture. The
server capability test and standalone test-kit both consume this exact file, preventing either side
from passing against a separately maintained expectation.

`mirror-evidence-stage1-v1.fixture.json` is the fixed Stage 1 cryptographic compatibility fixture.
It contains a server-produced `HASH_ONLY` bundle and public Ed25519 key, but no private key or
business payload. The server rehydrates and verifies it through its Java protocol model; the
standalone test-kit validates the strict schemas and independently re-derives every nested seal,
closure, aggregate fingerprint, key policy, and signature from the same file.

## Visual Graph Projection Boundary

`POST /api/integration/capability-closures/project` accepts
`resourceGateway.capabilityClosureProjectionRequest.v1`: a portable `bloge.visualGraphDraft.v1`, positive
capability revision, deterministic `createdAt`, and requested data classification. Enterprise scope, purpose,
ownership, region, and lifecycle are deliberately absent from the request and are derived from the authenticated
workload identity. A requested classification above that identity's clearance is rejected.

The projection pins omitted operator definitions from one catalog view, preserves exact saved snapshots, resolves
resource-backed external leaves from the authoritative registry, and seals the root-plus-leaf closure. Missing or
stale operators, duplicate node identities, missing resources, and nested graph boundaries without an exact child
closure fail closed with stable `RG.MIRROR.*` codes. Pure implementation operators remain covered by the graph
source fingerprint without becoming false business capabilities.

## Protected plan compilation boundary

`POST /api/mirror/plans` accepts `resourceGateway.mirrorPlanCreateRequest.v1` only when
`gateway.testing.mirror.enabled=true` and the active profile is `test` or `staging`. The same controller is excluded
when `production` is active, including a mixed `production,test` profile set. `GET /api/mirror/plans/{planId}` reads
the verified result under the same complete scope.

The request contains a stable plan id, registered graph name and reviewed graph fingerprint, one sealed capability
closure, one exact `FIXTURE_BUNDLE` reference, bounded invocation/timeout requests, a certification requirement,
and an exact expiry. It deliberately contains no execution purpose, isolation booleans, clearance, region,
lifecycle allowlist, credential policy, or fixture/replay value. The authenticated service boundary:

1. requires `MIRROR_REHEARSAL`, a test/staging identity, and non-empty tenant/organization/project/environment/region;
2. hides cross-scope closure and plan existence behind `404`;
3. fingerprints the current registered BLOGE graph and compares it with both request and root capability;
4. resolves and independently verifies the exact stored fixture revision and caller clearance;
5. freezes governed replay dependencies through a mirror-only resolution path that does not grant capture or direct payload-read permission;
6. derives deny-real-call, deny-credential, deny-egress, lifecycle, region, purpose and maximum-classification policy on the server;
7. compiles and append-only persists the payload-free plan.

An exact retry reuses the original `compiledAt` and returns the existing fingerprint. A changed graph, closure,
fixture, timeout, budget, certification flag, expiry, scope, or policy under the same `planId` returns an idempotency
conflict. Stage 1 caps timeout at 15 minutes, invocation budget at 100,000, and plan lifetime at 24 hours.

The application decoder recursively rejects unknown fields and bounds the canonical request tree to 16 MiB.
Servlet JSON materialization still occurs before that decoder runs, so this is not an ingress denial-of-service
control. An enterprise deployment must enforce raw-body size, connection, and request-rate limits at the proxy or
container boundary. A deployment-owned pre-materialization limit remains a production/certification release gate;
the current protected test/staging serving surface does not claim that MVC parsing is an ingress DoS boundary.

The underlying testing fixture registry predates full organization/project/region coordinates. Mirror does not
inherit that wider lookup. When the mirror composition is active, fixture registration appends or idempotently
recovers a payload-free `MirrorFixtureScopeBinding`; if the second write is unavailable, the API returns a
retryable failure and the exact registration retry completes it. Plan compilation requires an exact binding
before reading the tenant/environment fixture row. A historical unbound revision is not grandfathered in and must
be re-registered with identical content under the intended full scope. The companion table contains only scope,
fixture identity/fingerprint, timestamp, and actor, never fixture or replay values.

## Protected execution and evidence boundary

The same isolated composition exposes these routes only under an explicit mirror switch and a `test` or `staging`
profile; any active `production` profile physically removes all three mappings:

| Method and path | Response | Semantics |
|---|---|---|
| `POST /api/mirror/executions` | `resourceGateway.mirrorRunSummary.v1` | Execute once or return an identical completed request |
| `GET /api/mirror/runs/{runId}` | `resourceGateway.mirrorRunSummary.v1` | Read a verified payload-free terminal projection |
| `GET /api/mirror/runs/{runId}/evidence` | `resourceGateway.mirrorEvidenceBundle.v1` | Read independently verified signed `HASH_ONLY` evidence |

`POST /api/mirror/executions` accepts exactly `schemaVersion`, `requestId`, `planId`,
`expectedPlanFingerprint`, and `context`. It does not accept scope, purpose, fixture/replay references, resolver
order, credentials, egress, timeout, or policy overrides. The decoder closes top-level fields, requires an object
context, rejects duplicate keys and scalar coercion before admission, and enforces 16 MiB raw/canonical size, depth 64,
and 100,000 JSON-node limits. Spring still buffers the body bytes before decoding, so deployment-owned connection,
streaming body, and rate limits remain required. The application:

1. requires `MIRROR_REHEARSAL`, complete tenant/organization/project/environment/region coordinates, and a
   `test` or `staging` identity;
2. loads the plan only inside that exact scope and compares its fingerprint with the caller-reviewed value;
3. rejects caller-owned `bloge.tenantId`, `bloge.namespace`, and encoded `__nodeOutput:` state, then binds tenant
   and project namespace from authenticated scope;
4. fingerprints the effective context under the same 16 MiB limit used by evidence projection;
5. claims a durable payload-free request lease keyed by full scope and `requestId`;
6. reconstructs the sealed capability closure, resolves the root's exact graph source, re-verifies the full-scope
   fixture binding/envelope and governed replay closure, and recompiles the runtime generation;
7. requires complete equality between the recompiled and stored public plans before scheduling;
8. executes the independent engine and atomically persists signed evidence plus terminal request state.

`mirror_run_requests` stores only scope, request/context/plan fingerprints, status, opaque lease owner, monotonic
lease epoch, lease/retention times, stable failure code, and terminal run/evidence fingerprints. It has no request
JSON, context, fixture, replay, node, edge, input, or output payload column. An identical active retry receives a
retryable `409 RG.MIRROR.RUN_REQUEST_IN_PROGRESS`; a changed request under the same id receives non-retryable
`409 RG.MIRROR.RUN_IDEMPOTENCY_CONFLICT`. The coordination database clock is the sole authority for claim time,
expiry, takeover, release, terminal fencing, and the bounded `retryAfterSeconds`; replicas never supply absolute lease
timestamps, so wall-clock skew cannot steal authority early or delay recovery. Expiry permits epoch-incrementing
takeover. Completion compares owner, epoch, the original expiry, and database coordination time; expiry revokes publication
authority even before another worker takes over. Authority-row locking precedes database-time sampling, so lock wait
cannot carry a stale time sample across the expiry boundary. H2 clock reads use an independent short connection because
`CURRENT_TIMESTAMP` is transaction-scoped; the datasource must support at least the outer transaction plus this clock
connection. Release and takeover also change the fenced row, so an old or
released worker cannot commit. Evidence insert and terminal request update share one database transaction: stale or
expired authority rolls back the insert.
A completed retry loads and cross-checks the stored evidence instead of re-executing. Missing or cross-scope
plan/run/evidence identities are exposed only as `404`.

The summary contains run/request/plan/context/evidence fingerprints, full scope, terminal status/trust class,
timestamps, duration, and node/edge/resolution counts. It cannot carry business context, input/output, fixture, or
replay values. The evidence endpoint remains the authoritative detailed trace. Until a deployment isolation
attestation is bound, evidence is explicitly `EXPLORATORY` with `DEPLOYMENT_EGRESS_NOT_ATTESTED`; protected serving
availability is not equivalent to `CERTIFIABLE` evidence.

Stable execution transport failures are grouped by caller action rather than by internal exception type:

| HTTP | Representative code | Retry | Meaning |
|---:|---|---|---|
| 400 | `RG.MIRROR.EXECUTION_REQUEST_MALFORMED` | No | Unknown/missing field, wrong version/type, or post-parse size/depth/node limit |
| 400 | `RG.MIRROR.CONTEXT_RESERVED_KEY` / `RG.MIRROR.CONTEXT_TOO_LARGE` | No | Caller attempted engine-state injection or effective context cannot be fingerprinted safely |
| 403 | `RG.MIRROR.PURPOSE_REQUIRED` / `RG.MIRROR.ENVIRONMENT_FORBIDDEN` | No | Identity is not authorized for isolated rehearsal |
| 404 | `RG.MIRROR.PLAN_NOT_FOUND` / `RG.MIRROR.RUN_NOT_FOUND` | No | Absent and cross-scope identities are intentionally indistinguishable |
| 409 | `RG.MIRROR.PLAN_FINGERPRINT_CONFLICT` | No | Caller did not execute the exact reviewed plan generation |
| 409 | `RG.MIRROR.RUN_IDEMPOTENCY_CONFLICT` | No | The scoped request id already means different plan/context semantics |
| 409 | `RG.MIRROR.RUN_REQUEST_IN_PROGRESS` | Yes | Identical request owns an unexpired lease; use bounded `retryAfterSeconds` |
| 409 | `RG.MIRROR.RUN_LEASE_LOST` | Yes | Execution finished after expiry, release, or epoch takeover and was not allowed to commit |
| 409 | `RG.MIRROR.RUNTIME_GRAPH_DRIFT` / `RG.MIRROR.RUNTIME_GENERATION_DRIFT` | No | Current authoritative artifacts no longer reproduce the sealed plan |
| 410 | `RG.MIRROR.RUN_EXPIRED` | No | Plan TTL elapsed before a new execution could start |
| 503 | `RG.MIRROR.RUN_COORDINATION_UNAVAILABLE` / `RG.MIRROR.RUN_EVIDENCE_UNAVAILABLE` | Yes | Durable coordination or verified evidence storage is unavailable |
| 503 | `RG.MIRROR.EVIDENCE_SIGNER_UNAVAILABLE` | Yes | No governed signing authority can finalize evidence |

Problem details never contain request context, fixture/replay values, node/edge values, lease owner, or epoch.

## Invariants

- Every executable reference carries a positive revision and canonical `sha256:<hex>` fingerprint.
- Capability ids are resolved only inside their sealed tenant/organization/project/environment scope;
  scope tenant and provenance tenant must be identical.
- `UNKNOWN` effects remain critical, require an unresolved reason, and cannot collapse to read-only.
- Recorded and inferred provenance requires exact source references.
- Statistical confidence is not legal for owner-declared artifacts.
- An external capability cannot have child capability dependencies.
- A composed capability must freeze at least one exact dependency.
- A snapshot fingerprint covers the complete normalized object with only its own fingerprint field blanked.
- A closure contains one composed root, one exact copy of every reachable dependency, a single enterprise
  scope, no cycles, no unreachable snapshots, and no conflicting fingerprints for one capability revision.
  The Java protocol and JSON Schema both cap the root-plus-dependency set at 10001 snapshots; iterative graph
  validation prevents deep dependency chains from consuming the JVM call stack. Its fingerprint covers the
  complete normalized closure.
- A mirror plan embeds one verified closure and binds every external dependency edge exactly once to a unique
  BLOGE invocation site. `executionControlFingerprint` additionally pins the exact frozen BLOGE runtime inventory
  and EffectiveExecutionPlan generation. Resolver sources follow the fixed v1 precedence and end in `ABSTAINED`; real external
  calls, external credentials, network egress, stale/revoked artifacts, unknown effects, incomplete state-model
  closure, cross-purpose/cross-scope material, and plans longer than 24 hours are rejected before sealing.
- A mirror resolution is tied to an exact run, plan, capability, invocation site, occurrence, attempt, and canonical
  request fingerprint. `RESOLVED`, `ABSTAINED`, and `REJECTED` have disjoint payload/error invariants. A resolved
  `null` is represented by `outputIncluded=true`; `HASH_ONLY` never pretends that payload is present; every
  non-abstained result carries exact artifact provenance. Visible output and the complete artifact have separate
  canonical fingerprints, and generic string rendering omits output and error diagnostics.
- A durable mirror request is idempotent over full scope, request id, exact plan, effective-context fingerprint,
  and purpose. Only one unexpired lease epoch may publish terminal evidence, and evidence plus request completion
  are atomic.
- A portable mirror evidence bundle never embeds node input, node output, edge value, or resolver output payloads.
  It binds the request-context, plan, capability closure, execution-control generation, fixture revision, semantic
  result, the exact payload-free external binding inventory, ordered node/edge traces, every sealed external
  resolution, and explicit isolation facts. An independent verifier requires every attempt at an external binding
  site to have exactly one resolution with the same capability, graph path, request hash, and non-empty output hash;
  omitted and invented resolutions both fail closed. A claimed
  deployment egress proof must bind an exact `DEPLOYMENT_ISOLATION_ATTESTATION`; an unproven environment remains
  explicitly limited. A bundle is
  emitted only after its domain-separated Ed25519 signature and complete bundle fingerprint verify immediately.
  Cryptographic provenance does not imply production certification: `CERTIFIABLE` additionally requires proven
  deployment egress isolation and zero declared limitations.
- Revision one must be `DRAFT`; later revisions are contiguous, append-only, and accepted only through the
  lifecycle transition matrix. `REVOKED` is terminal.

## Independent client admission

The test-kit packages the Stage 0 schemas, protected plan/execution commands, payload-free run summary, four Stage 1
evidence schemas, and both shared compatibility fixtures in its JAR. A Stage 0 consumer first
calls `CapabilityMirrorCompatibility.assess(capabilityPayload)` and requires a compatible result.
It then calls `CapabilityMirrorVerifier.verifySnapshot(value)` or `verifyClosure(value)` before
persisting or compiling the artifact. A mirror evidence consumer resolves the attestation key id and calls
`MirrorEvidenceVerifier.verify(bundle, key)` before accepting a run into a correctness workbook or release gate.

The verifiers do not deserialize server Java models. They validate wire JSON and independently re-derive canonical
SHA-256 material. Mirror evidence verification additionally proves trace ordering, external-attempt/resolution
closure, nested resolution seals, evidence and bundle fingerprints, signing time, key policy, and the
domain-separated Ed25519 signature. Results contain only bounded reason codes, ids, and fingerprints. Stable
`RG.MIRROR.CLIENT.*` admission failures contain no business payload. Additional future probe
fields and object versions are accepted, while a missing required version or false required feature
fails closed. Stage 1 deferred features are observational and may move from `false` to `true`
without breaking Stage 0 clients.

The current independently supported consumer is the Java test-kit. Non-Java implementations must first pass the
fixed Stage 1 fixture byte-for-byte; they must not parse and re-emit numeric values through a representation that
collapses producer lexical forms such as `1.0` to `1` before hashing. A language-neutral RFC 8785-or-equivalent
numeric canonicalization profile and N/N-1 consumer conformance matrix remain a production serving gate.

## Projection implementation

`CapabilityProjectionService` is the current Java projection boundary:

- Resource descriptors become sealed external capability snapshots.
- Only external/resource-backed/runtime-bound operators become standalone capabilities; pure internal
  operators remain covered by their parent graph fingerprint.
- Generic `httpResource` nodes with a constant `resourceId` binding close over that exact Resource
  capability. A context/expression-driven `resourceId` remains a generic Operator capability with an
  `UNKNOWN` effect and blocked runtime readiness until a bounded dispatch contract is supplied.
- Graph drafts close over exact sealed external or nested capability snapshots and conservatively inherit
  effects, errors, determinism, security, state-model references, route conditions, and runtime limitations.
- `BuiltInCapabilityClosureService` derives all seven shipped graph closures from the classpath DSL, formal
  graph contracts, current operator catalog, and resource registry. Nested `foreach`/`loop` capability sites
  receive stable structural paths and conditions, while a raw DSL digest protects syntax that is not yet flat
  in the visual draft. No second hand-maintained graph inventory is used.
- The AI streaming graph honestly remains runtime-blocked by current visual runtime readiness. Dynamic
  resource dispatch remains effect-unknown and runtime-blocked; the other five static resource graphs are ready.
- Unknown effects, unresolved child identity, unsealed children, conflicting errors, and ambiguous state
  models fail closed with stable `RG.MIRROR.*` error codes.

## Repository and integration boundary

`DatabaseCapabilitySnapshotRepository` stores sealed snapshots under a compound
tenant/organization/project/environment/region/capability/revision identity. It rejects gaps, mutation,
corrupt rows, illegal lifecycle transitions, and non-identical retries. Exact identical retries are idempotent.

The protected Tool Studio integration surface exposes:

| Method and path | Purpose | Required `X-Purpose` |
|---|---|---|
| `PUT /api/integration/capability-snapshots/{id}/revisions/{revision}` | Append an exact sealed revision | `CAPABILITY_PROJECTION` or `CHANGE_SYNC` |
| `GET /api/integration/capability-snapshots/{id}?revision=0` | Read latest, or set a positive exact revision | `MIRROR_REHEARSAL`, `CHANGE_SYNC`, or `GOVERNANCE_EVIDENCE_INGESTION` |
| `POST /api/integration/capability-snapshots/{id}/lifecycle-transitions` | Append a lifecycle-only revision | `CAPABILITY_GOVERNANCE` |
| `POST /api/mirror/plans` | Compile exact authoritative artifacts into an append-only payload-free plan | `MIRROR_REHEARSAL` |
| `GET /api/mirror/plans/{planId}` | Read one verified plan in the full authenticated scope | `MIRROR_REHEARSAL` |
| `POST /api/mirror/executions` | Execute one exact plan under durable request fencing | `MIRROR_REHEARSAL` |
| `GET /api/mirror/runs/{runId}` | Read one verified payload-free run summary | `MIRROR_REHEARSAL` |
| `GET /api/mirror/runs/{runId}/evidence` | Read one verified signed `HASH_ONLY` bundle | `MIRROR_REHEARSAL` |

All endpoints derive scope, actor, and clearance from the verified workload identity. Absent,
cross-scope, and above-clearance reads deliberately share `404 RG.MIRROR.SNAPSHOT_NOT_FOUND` so the API does
not become an asset-existence oracle.

The Stage 0 baseline verifies all seven shipped resource graphs plus all three frontend visual examples. The
MirrorPlan protocol increment adds nine semantic integrity cases and extends the strict protocol-field test. Its
focused protocol and probe suite passes 32 tests with no failures, errors, or skips. After adding the Stage 1
compiler, internal mirror runtime kernel, and MirrorResolution protocol, the latest complete Resource Gateway gate
passes 4499 tests with no
failures or errors and 3 conditional frontend skips, exercises the real browser workflow, and successfully rebuilds
the executable Spring Boot JAR. The independent test-kit gate passes 254 tests with no failures, errors, or skips,
packages all 17 mirror schemas, and rebuilds its ordinary/shaded JAR plus public Javadocs.

The Stage 1 `MirrorPlan` protocol presence alone does not make mirror execution available. Capability discovery
always reports `mirrorPlanProtocol=true`. It reports `mirrorPlanCompilation` and
`mirrorExternalLeafInterception` only when the protected test/staging plan adapter is physically assembled, and
reports `mirrorServing=true` only when run admission, durable request fencing, exact rehydration, independent
runtime, signed evidence persistence, run/evidence routes, and the signing authority are currently usable. Installed
run/evidence endpoints and protocol objects remain discoverable while a dynamic signer outage makes
`mirrorServing=false`; calls then fail closed with the documented `503` instead of pretending the routes do not exist.
Deployment egress proof controls the evidence certification class, not whether this explicitly isolated exploratory API
is discoverable.

## Stage 1 compiler kernel

`MirrorPlanCompiler` verifies an exact closure, recursively joins direct and nested capability dependency edges to
the frozen BLOGE `InvocationInventory`, and delegates all owner controls to the existing
`ExecutionControlCompiler.compileMirror` adapter. The public plan contains no FixtureBundle values or replay payloads;
its `executionControlFingerprint` binds the exact internal `EffectiveExecutionPlan`. Missing owner rules become
implicit deny plus `ABSTAINED`, and read-only external operators are still mandatory interception sites.

Mirror controls freeze `MIRROR_SOURCE_THEN_SELECTOR`: protocol source order is evaluated before specificity inside
one source. Owner rules therefore precede governed replay even when the replay selector is more specific. Overlap
across those sources is fallback rather than ambiguity; unresolved overlap inside one source remains fail-closed.
The strategy, per-site resolver order, and even an empty mandatory-site set participate in the execution-control
fingerprint. An empty external closure still cannot authorize fixtures for internal business nodes.

The runtime extension boundary is now explicit: one `MirrorResolver` owns one concrete source and returns either a
bounded claim or source-local abstention; `MirrorResolverChain` alone applies the compiled order and emits terminal
`ABSTAINED`. The Stage 1 adapters cover exact owner FixtureRules and governed replay FixtureRules. Missing compiled
sources, duplicate registrations, ordinary-control entry, and same-source runtime ambiguity fail closed. The chain
is now wired only for controls carrying `MIRROR_SOURCE_THEN_SELECTOR`; ordinary tests preserve their existing path.
`MirrorResolutionJournal` fingerprints bounded requests, retains successful outputs as hash-only evidence, binds
owner rules to the exact FixtureBundle and replay rules to both FixtureBundle and ReplayPayload, and seals results
after the shared kernel supplies its run id. Resolved business errors, policy rejection, and terminal abstention stay
distinct. The surrounding planning/runtime package passes 172 tests.

The accepted reuse decision and behavior-loss matrix are recorded in
[`ADR-004`](../../adr/ADR-004-mirror-plan-reuses-fixture-bundle.md). `CompiledMirrorPlan` now retains the exact Graph,
FixtureBundle, governed replay closure, and execution control in process. The internal `MirrorRunService` re-verifies
the public seal, authenticated scope and purpose, TTL, graph/fixture/control generation, external-only coverage, and
the static invocation floor before executing through the independent test engine. It carries the plan's logical
timeout into BLOGE `ExecutionBudget`; an unmatched external remains implicit deny and cannot reach the real binding.

The compiler and execution kernel now have protected service endpoints. The kernel projects every real
node/edge/attempt value to a bounded canonical fingerprint, proves exact closure against resolver provenance,
requires an explicit signer, and returns an immediately verified portable bundle. Durable payload-free
plan/evidence storage, request-id coordination with epoch fencing, atomic terminal commit, and independent test-kit
verification are complete. Deployment egress proof, pre-MVC ingress controls, dynamic occurrence budgeting, and
cross-language numeric canonicalization remain open certification/production gates.
