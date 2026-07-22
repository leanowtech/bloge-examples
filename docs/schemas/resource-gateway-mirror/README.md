# Resource Gateway Mirror Protocol Schemas

This directory is the wire-contract authority for the Resource Gateway capability-mirror protocol.
Every schema is strict (`additionalProperties: false`) and independently versioned. Server protocol
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
| `mirror-resolution-v1.schema.json` | `MirrorResolution` | Fingerprinted per-attempt source, confidence, freshness, payload visibility, output/error, and abstention provenance |
| `capability-lifecycle-transition-v1.schema.json` | `CapabilityLifecycleTransitionRequest` | Optimistically fenced governance transition for one exact revision |
| `capability-mirror-compatibility-v1.schema.json` | `CapabilityMirrorCompatibility` | Minimum protocol/object/feature baseline a mirror consumer can negotiate |

`capability-mirror-stage0-v1.fixture.json` is the authoritative Stage 0 compatibility fixture. The
server capability test and standalone test-kit both consume this exact file, preventing either side
from passing against a separately maintained expectation.

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
- Revision one must be `DRAFT`; later revisions are contiguous, append-only, and accepted only through the
  lifecycle transition matrix. `REVOKED` is terminal.

## Independent client admission

The test-kit currently packages the seven Stage 0 schemas and compatibility fixture in its JAR. MirrorPlan and
MirrorResolution are Stage 1 schemas; their independent client verifiers are intentionally not advertised until
runtime provenance integration and offline verification are complete. A Stage 0 consumer first
calls `CapabilityMirrorCompatibility.assess(capabilityPayload)` and requires a compatible result.
It then calls `CapabilityMirrorVerifier.verifySnapshot(value)` or `verifyClosure(value)` before
persisting or compiling the artifact.

The verifier does not deserialize server Java models. It validates wire JSON, re-derives the same
canonical SHA-256 material, and checks complete exact dependency closure with an explicit-stack
traversal. Stable `RG.MIRROR.CLIENT.*` failures contain no business payload. Additional future probe
fields and object versions are accepted, while a missing required version or false required feature
fails closed. Stage 1 deferred features are observational and may move from `false` to `true`
without breaking Stage 0 clients.

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

All three endpoints derive scope, actor, and clearance from the verified workload identity. Absent,
cross-scope, and above-clearance reads deliberately share `404 RG.MIRROR.SNAPSHOT_NOT_FOUND` so the API does
not become an asset-existence oracle.

The Stage 0 baseline verifies all seven shipped resource graphs plus all three frontend visual examples. The
MirrorPlan protocol increment adds nine semantic integrity cases and extends the strict protocol-field test. Its
focused protocol and probe suite passes 32 tests with no failures, errors, or skips. After adding the Stage 1
compiler, internal mirror runtime kernel, and MirrorResolution protocol, the latest complete Resource Gateway gate
passes 4398 tests with no
failures or errors and 3 conditional frontend skips, exercises the real browser workflow, and successfully rebuilds
the executable Spring Boot JAR.

The Stage 1 `MirrorPlan` protocol presence does not make mirror execution available. Capability discovery reports
`mirrorPlanProtocol=true`, while `mirrorPlanCompilation`, `mirrorExternalLeafInterception`, and `mirrorServing`
remain false until compiler, interception, isolation, independent verification, and evidence paths pass their own
release gates.

## Stage 1 compiler kernel

`MirrorPlanCompiler` verifies an exact closure, recursively joins direct and nested capability dependency edges to
the frozen BLOGE `InvocationInventory`, and delegates all owner controls to the existing
`ExecutionControlCompiler.compileMirror` adapter. The public plan contains no FixtureBundle values or replay payloads;
its `executionControlFingerprint` binds the exact internal `EffectiveExecutionPlan`. Missing owner rules become
implicit deny plus `ABSTAINED`, and read-only external operators are still mandatory interception sites.

The accepted reuse decision and behavior-loss matrix are recorded in
[`ADR-004`](../../adr/ADR-004-mirror-plan-reuses-fixture-bundle.md). `CompiledMirrorPlan` now retains the exact Graph,
FixtureBundle, governed replay closure, and execution control in process. The internal `MirrorRunService` re-verifies
the public seal, authenticated scope and purpose, TTL, graph/fixture/control generation, external-only coverage, and
the static invocation floor before executing through the independent test engine. It carries the plan's logical
timeout into BLOGE `ExecutionBudget`; an unmatched external remains implicit deny and cannot reach the real binding.

This is still a kernel rather than a service endpoint. Fixed-priority resolver provenance, dynamic occurrence
budgeting, mirror evidence vNext, production composition and egress proofs, exact artifact storage, and authenticated
API admission remain open. Capability discovery therefore continues to report compilation, external interception,
and serving as unavailable.
