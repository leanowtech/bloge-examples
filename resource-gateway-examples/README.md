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
| Durable stability jobs | Authenticated non-blocking submit/query/cancel protocol, deterministic idempotency, database capacity/fairness/deadline control, transaction-bound payload-free cancellation audit, lifecycle views, opt-in current-authority worker, and honest capability discovery |
| Governed run controls | Absolute deadline, monotonic remaining-budget propagation, fenced cancel, durable owner lease/epoch, cross-instance commands, and automatic signed evidence recovery after owner failure |
| Auditable external writes | Versioned write contracts, binding/activation conformance, execution-scoped journal, commit receipts, UNKNOWN_COMMIT DAG guard, and signed reconciliation evidence |
| Dynamic workload identity | Atomic JWKS/revocation refresh, zero-restart key rotation, bounded propagation SLO, group/clearance/delegation claims, and explicit 401/503 semantics |
| Managed evidence signing | Non-exportable KMS/HSM provider protocol, atomic public-key generations, locally verified signatures, rotation/revoke semantics, and machine-readable custody health |
| Consistent draft export | Frozen operator/library/binding/activation/test-suite refs, deterministic dependency fingerprints, and retryable 409 conflict on assembly-time drift |
| Governed capability closures | Sealed Resource/Operator/Graph projections, exact cycle-checked closure for all seven shipped graphs, nested foreach/loop boundary inventory, full enterprise scope, append-only lifecycle revisions, classification-aware reads, and honest mirror readiness flags |
| Governed capability observations | Signed payload-free invocation facts, operator-owned admission policy, external vault/proof verification, durable admitted-or-quarantined decisions, full-scope idempotency, and independent offline verification |
| Governed capability corpora | Immutable quarantine review, exact admitted-source candidates, metadata risk gates, independent owner-reviewed publication lineage, second source-authority verification, and honest resolver readiness |
| Stateful mirror sessions | Versioned entity/write/session/API protocols, atomic multi-entity mutations, exact replay, AES-GCM isolated persistence, lease/fence/CAS concurrency, TTL/destroy, payload-free signed state-read evidence, ANEKE workbook seeds, and independently verified clients |
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
Add `--stateful` to assemble the encrypted stateful-mirror Session API and its
dedicated local data plane.

| Open | Best first move |
| --- | --- |
| `http://localhost:8080/author/` | Build a schema-constrained graph on the visual canvas |
| `http://localhost:8080/showcase/` | Explore guided product scenarios and sample outputs |
| `http://localhost:8080/examples/gateway` | Use the legacy Custom Composer regression surface |
| `http://localhost:8080/api/integration/capabilities` | Verify protocol versions, endpoints, feature flags, identity provider, payload policy, and signer readiness |
| `POST http://localhost:8080/api/mirror/sessions` | Create an encrypted stateful simulation Session after starting with `--stateful` (test/staging only) |
| `GET http://localhost:8080/api/integration/capability-snapshots/{capabilityId}?revision=0` | Read the latest authorized capability snapshot; use a positive revision for an exact read |
| `PUT http://localhost:8080/api/integration/capability-snapshots/{capabilityId}/revisions/{revision}` | Append one exact sealed capability snapshot revision |
| `POST http://localhost:8080/api/integration/capability-snapshots/{capabilityId}/lifecycle-transitions` | Append an optimistically fenced lifecycle-only revision |
| `POST http://localhost:8080/api/integration/capability-closures/project` | Project a portable visual `GraphDraft` into a sealed, scope-bound root-plus-external-leaf capability closure |
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
| `POST http://localhost:8080/api/testing/suites/{suiteId}/stability-executions` | Execute one exact V1/V2/V4 suite with deterministic request v1, fixed-horizon statistical request v2/v3, or anytime-valid maximum-horizon request v4, under a cross-replica parent lease, then retain signed payload-free evidence (test/staging only) |
| `POST http://localhost:8080/api/testing/suites/{suiteId}/stability-trend-analyses` | Derive and sign a bounded retained-history trend for one exact suite revision, with explicit retention/truncation gaps, execution-regime drift, case transitions, and non-causal correlation signals (test/staging only) |
| `POST http://localhost:8080/api/testing/suites/{suiteId}/stability-cross-retention-trend-analyses` | Preview a signed floor/head/cursor-pinned compact-observation range; disabled unless `gateway.testing.stability-cross-retention-preview-enabled=true`, absent in production, and not advertised as a capability yet |
| `POST http://localhost:8080/api/testing/suites/{suiteId}/stability-observation-ledger-lifecycle-pages` | Discover and prove up to ten signed floor-retirement generations under one current-floor/head snapshot; shares the default-disabled cross-retention preview flag, is absent in production, and does not make the capability true |
| `POST http://localhost:8080/api/testing/suites/{suiteId}/stability-observation-ledger-lifecycle-archive-pages` | Return lifecycle v2 with the exact external archive receipt set for every retirement; requires independent caller-pinned archive trust policy, shares the non-production preview flag, and cannot downgrade to v1 |
| `GET http://localhost:8080/api/testing/stability-executions/{stabilityRunId}` | Read one retained stability analysis with its exact ordered source-run closure and detached signature (test/staging only) |
| `GET http://localhost:8080/api/testing/stability-executions/{stabilityRunId}/progress` | Poll payload-free `RUNNING`, `RECOVERABLE`, or `COMPLETED` durable parent progress; v2 distinguishes planned horizon, observed prefix, and terminal reason without exposing owner/epoch/source ids/payloads (test/staging only) |
| `POST http://localhost:8080/api/testing/suites/{suiteId}/stability-jobs` | Submit an exact stability request without blocking; returns `202`, deterministic `jobId`, query `Location`, and payload-free lifecycle (test/staging only; fresh submission requires the opt-in worker) |
| `GET http://localhost:8080/api/testing/stability-jobs/{jobId}` | Read one organization/project-scoped durable job without exposing principal, request metadata, lease fence, cancellation fingerprint, or row seal (test/staging only) |
| `POST http://localhost:8080/api/testing/stability-jobs/{jobId}/cancellations` | Idempotently cancel queued work or request cooperative running cancellation; every first command, including `COMMITTING`/terminal no-ops, commits one payload-free semantic audit event with the job mutation (test/staging only) |
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
| `POST http://localhost:8080/api/mirror/plans` | Resolve reviewed graph/closure/fixture/replay artifacts and compile an immutable mirror plan (explicit mirror switch plus test/staging only) |
| `GET http://localhost:8080/api/mirror/plans/{planId}` | Read a verified payload-free mirror plan in the complete authenticated enterprise scope |
| `POST http://localhost:8080/api/mirror/executions` | Run one sealed mirror generation under durable request-id fencing and return a payload-free summary |
| `GET http://localhost:8080/api/mirror/runs/{runId}` | Read a verified payload-free terminal mirror summary in the complete scope |
| `GET http://localhost:8080/api/mirror/runs/{runId}/evidence` | Export the independently verified signed `HASH_ONLY` evidence bundle |
| `GET http://localhost:8080/api/mirror/runs/{runId}/state-workbook-seed` | Export a deterministic payload-free ANEKE seed from one verified stateful v3 bundle |
| `POST http://localhost:8080/api/mirror/trust/deployment-isolation/authority-key-sets` | Verify and append one current isolation-authority key-set generation |
| `GET http://localhost:8080/api/mirror/trust/deployment-isolation/authority-key-sets/{keySetId}/latest` | Distribute the re-verified current authority floor |
| `POST http://localhost:8080/api/mirror/trust/deployment-isolation/attestations` | Verify and append an operator-pinned attestation bootstrap or continuous successor |
| `GET http://localhost:8080/api/mirror/trust/deployment-isolation/attestations/{attestationId}/latest` | Read one atomic current attestation and local status bundle |
| `POST http://localhost:8080/api/mirror/trust/deployment-isolation/attestations/{attestationId}/revocations` | Irreversibly revoke one exact current attestation status |
| `POST http://localhost:8080/api/mirror/observations` | Admit or quarantine one signed payload-free capability observation under operator-owned policy and external payload-proof verification |
| `POST http://localhost:8080/api/mirror/observations/{observationId}/reviews` | Append one terminal quarantine review without changing the original admission |
| `POST http://localhost:8080/api/mirror/corpus-candidates` | Freeze ordered admitted observations into a non-serving corpus candidate and compute metadata risk |
| `POST http://localhost:8080/api/mirror/corpus-publications` | Publish one current eligible candidate after owner authorization and a second source-authority check |
| `POST http://localhost:8080/api/mirror/corpus-trajectories` | Publish one explicit owner-reviewed retry sequence from the exact current corpus |
| `POST http://localhost:8080/api/mirror/corpus-clusters` | Publish one externally validated, owner-reviewed recorded cluster without moving payload into Resource Gateway |

Deployment-agent authority/attestation GETs require vendor negotiation in addition to normal
`MIRROR_TRUST_DISTRIBUTION` or `MIRROR_REHEARSAL` authentication:

```http
Accept: application/vnd.bloge.mirror-deployment-isolation-trust.v1+json
X-BLOGE-Mirror-Trust-Protocol: mirror-deployment-isolation-trust-v1
```

Stop it with:

```bash
./scripts/stop-visual-canvas-demo.sh
```

Capability snapshot and closure projection endpoints require `Authorization: Bearer ...` and a purpose accepted by
the operation. Use `CAPABILITY_PROJECTION` for exact append or visual draft projection,
`CAPABILITY_GOVERNANCE` for lifecycle transitions, and
`MIRROR_REHEARSAL` or `CHANGE_SYNC` for reads. Scope and clearance come from verified identity claims;
`X-Tenant-Id` and similar headers are only consistency hints. The demo token includes these purposes, while
enterprise deployments should issue separate author, governor, and rehearsal identities. The capability
closure projection request carries only the portable draft, positive target revision, deterministic creation time,
and a classification no higher than the caller's clearance. Tenant, organization, project, environment, region,
purpose, ownership, and `DRAFT` lifecycle are server-derived. The capability probe reports snapshot/closure
protocol, projection, seven built-in graph closures, visual draft closure projection, API, lifecycle, and the
sealed `resourceGateway.mirrorPlan.v1` and recorded-corpus `resourceGateway.mirrorPlan.v2` wire models plus
`resourceGateway.mirrorServingGenerationToken.v1` as available. Protocol availability is deliberately separate from
runtime readiness. With the mirror switch disabled, every mirror runtime flag remains false and no `/api/mirror/**`
route exists. With `RG_MIRROR_RUNTIME_ENABLED=true` under `test` or `staging`, the protected plan adapter now reports
`mirrorPlanCompilation=true` and `mirrorExternalLeafInterception=true`; the fully assembled durable run/evidence
adapter additionally reports `mirrorOperationObservability=true` and reports `mirrorServing=true` while its signer
is usable. Signer readiness is re-evaluated on each
probe; a signer outage leaves the installed endpoints discoverable but changes serving to false and execution fails
closed with `503`. Serving means the isolated exploratory API is callable. The probe separately reports
`mirrorIsolationRunTrustReady` and `mirrorCertifiableEvidenceServingReady`; only the latter means
a certification-required plan can obtain double-observed deployment trust. Exploratory runs remain
explicit, while eligible runs emit v2 `CERTIFIABLE` evidence with admitted and committed agent
snapshot references. The complete protocol and lifecycle rules are in the
[mirror schema guide](../docs/schemas/resource-gateway-mirror/README.md) and
[runtime trust-binding guide](../docs/resource-gateway-mirror-runtime-trust-binding.md).

Observation capability discovery uses the same separation. `mirrorObservationProtocol=true`
means the wire model is supported, `mirrorObservationAdmissionApi=true` means the non-production
route is assembled, and `mirrorObservationAdmissionReady=true` means both the operator-owned
policy source and external sanitized-payload reference authority are currently usable. The default
providers are unavailable, so enabling Mirror runtime does not invent trust. Producer workloads
need the dedicated `MIRROR_CORPUS_INGESTION` purpose and exact
tenant/organization/project/environment/region scope. Integration, receipt semantics, stable
errors, and outage drills are documented in the
[capability observation admission guide](../docs/resource-gateway-capability-observation-admission.md).

Corpus governance has a separate readiness boundary. `mirrorCorpusGovernanceProtocol=true` means
the review/candidate/publication wire objects and strict Schemas are supported;
`mirrorCorpusGovernanceApi=true` means the three non-production routes are assembled; and
`mirrorCorpusGovernanceReady=true` requires both the operator-owned governance policy provider and
external source-lifecycle authority to be currently available. The defaults are unavailable.
`mirrorCorpusExactResolverProtocol=true` means the runtime understands strict
`fixtureBundle.metadata.mirrorCorpus` bindings and the fixed
`OWNER_SPECIFIED -> RECORDED_EXACT -> RECORDED_TRAJECTORY -> RECORDED_CLUSTER ->
GOVERNED_REPLAY -> ABSTAINED` chain.
`mirrorCorpusResolverReady=true` is stronger: the policy provider, source-lifecycle authority,
regional `CapabilityCorpusPayloadAuthority`, shared `MirrorServingGenerationAuthority`, and
operator-owned `MirrorServingGenerationTrustProvider` must all be currently usable. The default
payload, generation, and trust authorities are unavailable, so enabling the mirror profile never
invents payload-vault or generation trust. The probe also reports
`mirrorServingGenerationFencing` and `mirrorServingGenerationAuthorityReady` from this complete
dynamic chain.
Governance workloads require `MIRROR_CORPUS_GOVERNANCE`; plan/run workloads continue to require
`MIRROR_REHEARSAL`.

Recorded retry trajectories have their own honest probe boundary.
`mirrorCorpusTrajectoryPublicationProtocol=true` advertises the strict command/publication
protocol, `mirrorCorpusTrajectoryPublicationApi=true` reports route assembly, and
`mirrorCorpusTrajectoryPublicationReady=true` requires current corpus policy, retry policy, and
source-lifecycle authorities. `POST /api/mirror/corpus-trajectories` accepts only an explicit
owner-reviewed 2..32-attempt sequence from the exact latest corpus publication. The service
revalidates source membership, grants, one request fingerprint, trace/span ordering, current retry
policy, retryable intermediate failures, and a terminal final attempt before appending the
payload-free artifact. It never infers retries from nearby observations.

`mirrorCorpusTrajectoryResolverProtocol=true` means the runtime also accepts strict
`fixtureBundle.metadata.mirrorTrajectories` bindings. Every binding must repeat the exact
capability and corpus publication selected in `mirrorCorpus`; materialization rechecks the current
trajectory head, current retry policy, source lifecycle, grants, trace/order, and response content
addresses. `mirrorCorpusTrajectoryResolverReady=true` additionally requires exact-corpus serving
authorities and the retry-policy authority to be live; it is probed independently from the
trajectory publication route so a read-only serving deployment can report its real capability.
The binding parser rejects raw values that the strict Schema rejects instead of normalizing
lowercase kinds, padded/oversized identifiers, or non-64-bit revisions. The frozen sequence is
indexed separately from standalone exact samples and is consumed by the real BLOGE one-based retry
loop. A plan is rejected before execution when a trajectory needs more attempts than the node's
`retryAttempts + 1`; sequence exhaustion never repeats the final sample or falls through to a real
external call.

Recorded clusters have a separate publication boundary.
`mirrorCorpusClusterPublicationProtocol=true` means the validation, command, and publication v1
objects plus strict Schemas are supported.
`mirrorCorpusClusterPublicationApi=true` means `POST /api/mirror/corpus-clusters` is assembled,
while `mirrorCorpusClusterPublicationReady=true` additionally requires live operator-owned
`CapabilityCorpusClusterPolicyProvider`, external
`CapabilityCorpusClusterValidationAuthority`, corpus policy, and source-lifecycle authority. The
default cluster policy and validation providers are unavailable.

Publication rechecks the current corpus and policy, exact source membership, common response
Schema, `EXACT_REPLAY + CLUSTER_MODELING` grants, retention/horizon, holdout counts, false-positive
rate, and the independently recomputed 95% Wilson precision interval. Identity-free clusters
cannot declare projections; request-projection clusters must map current request identity into
globally disjoint response JSON Pointer paths. The publication route never reads payload.

`mirrorCorpusClusterResolverProtocol=true` advertises strict
`fixtureBundle.metadata.mirrorClusters` binding and runtime `RECORDED_CLUSTER` support. Each binding
repeats the exact capability and corpus publication selected by `mirrorCorpus`. Before compilation,
the serving boundary rechecks the current cluster and corpus heads, current corpus/cluster policies,
current validation proof, every member grant/lifecycle/horizon, exact match values, distinct
identity support, and all request/representative-response content addresses. Runtime matching uses
exact JSON Pointer equality only. For request-projection clusters it first verifies every declared
source and destination, then replaces all identity destinations from the current request; a missing
path abstains and multiple matching clusters fail closed.
`mirrorCorpusClusterResolverReady=true` is the independent dynamic serving probe. Publication API
readiness does not imply resolver readiness, and both default to false until operator-owned policy,
validation, source-lifecycle, and regional payload authorities are installed.

The binding selects an exact latest publication for one exact external capability revision. Plan
creation and every runtime materialization recheck the publication head, current policy, source
lineage, exact-replay grant, retention, classification, region, tombstone state, and response
content address before freezing response JSON in the in-memory run generation. Payload bytes are
not written to the public plan, database, HTTP response, evidence, audit, metrics, or logs.
Single retryable-error observations still fail closed; only an explicitly published and
fixture-bound full attempt sequence can produce retryable runtime behavior. The immutable fact
model, fixture bindings, provider contracts, request
examples, errors, startup commands and remaining production gates are in the
[capability corpus governance guide](../docs/resource-gateway-capability-corpus-governance.md).

### Stateful mirror Session data plane and DAG reads

The Stage 3 vertical freezes `StateModel`, `StateReadSpec`, `WriteEffectSpec`,
`SessionStateSpace`, five Session API objects, and a closed bounded expression AST. The
`MirrorStateTransactionEngine` serializes one session's writes, atomically
applies ordered multi-entity mutations, returns the original receipt for exact
idempotent retries, rejects same-key command drift, validates entity schemas and
complete unique business keys, and derives logical time, IDs, and sequences
without ambient nondeterminism. Update/delete copy-on-write accepts only an
exact recorded corpus sample or owner-specified fixture; tombstoned identities
cannot be recreated.

Session integrity requires a contiguous revision/receipt journal and exact
event closure, while each entity, tombstone, event, receipt, current world, and
complete session is content-addressed. The test kit packages the same strict
Schemas and refund fixture, seals canonical payloads, verifies them without
linking server classes, and exposes bounded create/read/command/destroy client
methods.

With `test` or `staging` plus `--stateful`, `/api/mirror/sessions` stores the
complete payload under AES-256-GCM in a dedicated JDBC data plane. The public
descriptor remains payload-free. Same-process fair locking, cross-replica DB
lease/fence, expected-state checks, CAS, exact lease release, TTL and destroy
define the concurrency and lifecycle boundary. Authentication happens before
decoding and scope comes only from verified identity.

The database serializes create, payload-growth commit, and expiry capacity
decisions under one cross-replica guard. Global and exact enterprise-scope
limits cover both active-session count and all canonical serialized payload
bytes not yet erased, including expired payload awaiting cleanup. Commands also pass a fair,
non-blocking replica-local admission gate before waiting on a session lock.
Saturation is a stable retryable `429`; exact idempotent replay remains
available while full. A bounded oldest-first worker erases expired ciphertext,
and aggregate health plus fixed-cardinality metrics never expose customer
dimensions.

State-model-backed `READ_ONLY` and `VIRTUAL_MUTATION` external capabilities can
now be compiled with `SESSION_STATE` first in resolver precedence. Execution
request v2 binds the run to one caller-reviewed Session state fingerprint. A
read-only run uses that immutable head throughout. A read/write run owns one
fair, serialized run session: each virtual write passes through the same
admission, database lease, idempotency, optimistic fence, CAS, and audit path as
the protected Session command API, then atomically advances the head visible to
downstream nodes. The write binding is exactly
`[SESSION_STATE, ABSTAINED]`, so the graph can never invoke the registered real
write operator. Reads return live entities through the declared bounded
projection; absent keys may continue to lower governed sources, while an
indexed tombstone is terminal. Missing read or write specifications are
plan/Session closure defects rejected before graph scheduling.

Read-only Session runs emit nested
`resourceGateway.mirrorStateRunEvidence.v1` inside mirror evidence,
attestation, and bundle v3. Read/write runs emit
`resourceGateway.mirrorStateRunEvidence.v2` inside the independently
domain-separated v4 generation. V2 state evidence binds both initial and final
Session heads, every read's exact observed revision, and every write's
request/idempotency/command/receipt/response fingerprints, contiguous
revision/world/logical-clock transition, and complete transition-event
closure. It retains neither entity values, business keys, nor raw idempotency
keys. Every read or write is also closed against the matching node delegate
attempt and `MirrorResolution`. Existing v1/v2 stateless and v3 read-only
bundles remain readable with their original signature semantics. Repository
reads after restart rehydrate the state-evidence subtype and re-verify its
nested seal and detached signature.

The standalone test kit independently verifies both v3 read closure and v4
read/write transition closure. It can derive
`resourceGateway.mirrorStateWorkbookSeed.v1` only from a verified v3 bundle;
transition-aware workbook projection remains a separate milestone.
The protected
`GET /api/mirror/runs/{runId}/state-workbook-seed` route returns the same
deterministic payload-free projection in the authenticated scope. A seed names
the exact bundle, state evidence, Session head, model, revision, access counts,
and conservative blockers; it does not replace the signed bundle or let
Resource Gateway make ANEKE's workbook, owner-approval, or publish-gate
decision.

This is not yet a production-certified stateful runtime: TEE/KMS custody,
signed checkpoint/recovery, transition-aware workbook assertions,
cryptographic deletion proof, target-database capacity/lock certification and
HA/DR certification remain. The probe reports `mirrorStatefulResolverReady`
only when mirror execution, the Session API, and the encrypted state store are
all ready. It separately reports `mirrorStateRunEvidenceReady`,
`mirrorStateTransitionEvidenceReady`, `mirrorStateWorkbookSeedApi`,
`mirrorStateWorkbookSeedReady`, and the deliberately false
`mirrorStateTransitionWorkbookSeedReady`; `mirrorStatefulRuntimeReady` remains
false until checkpoint and recovery closure is complete. Startup, request v2
usage, Java usage, capacity
configuration, stable errors, and remaining industrial work packages are in the
[stateful mirror kernel guide](../docs/resource-gateway-stateful-mirror-kernel.md).

The Stage 1 compiler and run kernels verify Capability Closure against the recursively
frozen BLOGE invocation inventory, adapt the existing FixtureBundle into mandatory external-site controls, retain
the exact Graph/fixture/control generation in process, and execute it through the independent test engine after
scope, purpose, TTL, fingerprint, coverage, and logical deadline checks. Mirror fixtures cannot replace internal
business nodes, and unmatched external leaves fail closed. Set `RG_MIRROR_RUNTIME_ENABLED=true` only with the
`test` or `staging` profile to assemble this internal kernel and its append-only mirror stores. The `production`
profile physically excludes the compiler, runtime, integrity service, and repositories even when `test` is also
active. Sealed public plans and independently verified `HASH_ONLY` evidence now persist under a complete
tenant/organization/project/environment/region compound key; exact retries are idempotent, conflicting identities
and tampered rows fail closed, and no fixture/replay/context/result payload column exists. This is still not a
production-certified runtime. The protected plan endpoint authenticates `MIRROR_REHEARSAL`, requires complete project and
region scope, fingerprints the current registered graph, independently verifies the exact stored fixture envelope,
freezes governed replay dependencies without changing caller purpose, derives all isolation policy on the server,
and persists only the payload-free plan. Because the legacy testing fixture registry is tenant/environment scoped,
fixture registration also creates an append-only payload-free organization/project/region authorization binding
when mirror is assembled; plan compilation requires that exact binding before reading the fixture. Historical
fixtures must be re-registered idempotently to gain a binding. Plan requests expose no real-call, credential, egress, region, lifecycle,
or clearance override. The execution endpoint accepts only requestId, exact plan identity, reviewed plan fingerprint,
and business context. It server-binds BLOGE tenant/project scope, reconstructs Graph/Fixture/Replay artifacts, and
requires the complete recompiled plan to equal the stored plan before execution. A payload-free durable request row
coordinates concurrent retry and restart recovery with lease owner, epoch, and hard-expiry fencing. Claim, expiry,
takeover, release, commit fencing, and `retryAfterSeconds` all use the coordination database clock, so replica wall-clock
skew cannot change execution authority. Signed evidence and terminal request state commit atomically. Expired authority
cannot publish even before takeover, and authority-row locking occurs before time sampling so lock wait cannot bypass
expiry. H2 time is sampled through an independent short connection after locking because its transaction timestamp is
frozen; configure the datasource with capacity for the transaction connection plus the clock connection. The
deployment-egress proof and M-of-N authority key-set publication protocols are frozen. Authority publications now
have full-scope append-only trusted distribution, a durable database CAS floor, strict protected APIs, and read-time
local re-verification. The deployment agent now admits certification plans before durable claim,
confirms the same stable attestation-bundle decision after execution, and holds a read permit through
atomic evidence commit. Routine refresh may advance only the local cache generation; revocation,
successor attestation, expiry, rollback, or decision drift fail closed. V1 evidence remains readable
and v2 adds the signed run-trust binding. Multi-node revocation convergence, customer PKI/KMS/IdP,
database HA/DR, managed clock controls, and non-Java v2 compatibility still require environment
certification. The fixture reuse decision is in
[ADR-004](../docs/adr/ADR-004-mirror-plan-reuses-fixture-bundle.md).

### Mirror dynamic occurrence budget

`maximumInvocations` is a whole-run operator-occurrence limit, not merely a count of nodes in the
saved graph. Plan compilation now rejects a limit smaller than the recursively frozen static
inventory with `RG.MIRROR.INVOCATION_BUDGET_TOO_SMALL`. At runtime, a run-scoped atomic budget is
checked by BLOGE's inherited `ExecutionOperatorResolver` after exact inventory verification and
before fixture binding or operator execution. Root nodes, nested graph re-entry, every foreach or
loop item, streaming nodes, and compensation consume one occurrence. Retries remain ordered
attempts inside the already admitted occurrence and do not consume another occurrence.

Parallel expansion uses compare-and-set admission, so no race can admit more than the sealed plan
limit. Once exhausted, later work fails non-retryably with
`RG.MIRROR.INVOCATION_BUDGET_EXHAUSTED` before the operator can perform a side effect. Already
admitted work may finish; no new occurrence is admitted. The terminal signed evidence remains
available with status `EXECUTION_FAILED` and limitation `INVOCATION_BUDGET_EXHAUSTED`. Internal
test evidence also records only the maximum/admitted/rejected counters under
`mirrorInvocationBudget`; it stores no site, correlation, input, output, or exception data.

Choose the value from reviewed worst-case expansion, not the static node count alone. For a root
with one foreach node and an item graph with two nodes, a five-item input can require
`1 + (5 * 2) = 11` occurrences before compensation. Timeout, ingress limits, tenant concurrency,
and the occurrence budget protect different resources and must all remain enabled.

### Mirror operation observability

Every protected Plan, Run, Evidence, deployment-trust, observation, corpus, trajectory, and cluster
operation reaches exactly one terminal observer before its service result is returned. The observer
pre-registers a fixed set of Micrometer series; no tenant, organization, actor,
correlation, request, plan, run, exception, or business value can become a tag.

| Metric | Tags | Meaning |
|---|---|---|
| `resource.gateway.mirror.operations` | `operation`, `outcome` | Terminal operation count |
| `resource.gateway.mirror.duration` | `operation`, `outcome` | Terminal service duration timer |
| `resource.gateway.mirror.failures` | `operation`, `reason` | Rejected/failed count by bounded reason class |

The closed `operation` vocabulary is `plan_create`, `plan_read`, `run_create`, `run_read`,
`evidence_read`, `authority_key_set_publish`, `authority_key_set_read`,
`isolation_attestation_ingest`, `isolation_attestation_read`,
`isolation_attestation_revoke`, `observation_ingest`, `observation_review`,
`corpus_candidate_create`, `corpus_publish`, `corpus_trajectory_publish`, and
`corpus_cluster_publish`.
Outcomes are `succeeded`, `rejected`, and `failed`; failure reasons are `invalid_request`, `forbidden`, `not_found`,
`conflict`, `expired`, `capacity`, `unavailable`, `audit_unavailable`, and `unexpected`. This produces 240 bounded
series in total. Registry exporters may rename timer units according to their normal conventions.

The append-only `mirror_operation_audit` table is the durable authority. Each row contains database sequence/time,
complete enterprise scope, correlation and actor coordinates, the closed operation/outcome/reason, exact stable
`RG.MIRROR.*` code, optional request/plan/run ids, and duration. Its schema cannot represent context, fixture,
replay, node/edge input or output, exception message, or stack trace. There is deliberately no public audit-read
endpoint in this increment; platform audit pipelines should consume the restricted database projection.

Success audit is written in the same local transaction as plan persistence or the evidence/request terminal commit.
If that audit cannot commit, the business mutation rolls back and the caller receives retryable
`503 RG.MIRROR.OPERATION_AUDIT_UNAVAILABLE`. A rejection/failure audit uses an independent `REQUIRES_NEW`
transaction so it survives the business rollback it explains; if this mandatory write also fails, the original
result is replaced by the same sanitized 503. Metrics remain advisory and never weaken this fail-closed rule.

Operations should page immediately on any `audit_unavailable`, alert on sustained `unavailable`/`unexpected`, and
track rejection ratios by operation without adding identity tags. Size the JDBC pool for an outer business
transaction plus the independent failure-audit transaction. The current table has no in-process deletion path;
production rollout still requires deployment-owned partitioning, access control, retention/archive policy, capacity
alerts, and a tested full-disk response. `mirrorOperationObservability=true` means this bounded observer is assembled,
not that an active audit-store health probe or those deployment controls have been certified.

The Plan command is recursively strict and its canonical JSON tree is capped at 16 MiB. Because MVC materializes
JSON before the command decoder runs, deployments must also enforce raw request-body size, connection, and rate
limits at the ingress boundary; the application decoder alone is not a denial-of-service boundary.

The execution command takes buffered raw bytes so duplicate keys, scalar coercion, non-canonical whitespace, raw size,
canonical size, depth, and node count are rejected before a typed command is created. Spring still buffers those bytes;
proxy/container streaming-body, connection, and rate limits remain production gates.

After compiling and reviewing a plan, execute that exact generation with a stable request id. Reusing the same id
and context returns the stored terminal result; changing plan or context under that id returns
`RG.MIRROR.RUN_IDEMPOTENCY_CONFLICT`. A concurrent identical call returns retryable
`RG.MIRROR.RUN_REQUEST_IN_PROGRESS` and `retryAfterSeconds`.

```bash
PLAN_ID='reviewed-plan-id'
PLAN_FINGERPRINT='sha256:replace-with-the-fingerprint-returned-by-plan-compilation'

curl -sS http://localhost:8080/api/mirror/executions \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: MIRROR_REHEARSAL' \
  -H 'Content-Type: application/json' \
  --data "{
    \"schemaVersion\": \"resourceGateway.mirrorExecutionRequest.v1\",
    \"requestId\": \"demo-rehearsal-001\",
    \"planId\": \"${PLAN_ID}\",
    \"expectedPlanFingerprint\": \"${PLAN_FINGERPRINT}\",
    \"context\": {\"customerId\": \"C-1001\"}
  }"

curl -sS http://localhost:8080/api/mirror/runs/REPLACE_WITH_RUN_ID \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: MIRROR_REHEARSAL'

curl -sS http://localhost:8080/api/mirror/runs/REPLACE_WITH_RUN_ID/evidence \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: MIRROR_REHEARSAL'

# Stateful v3 runs only
curl -sS http://localhost:8080/api/mirror/runs/REPLACE_WITH_RUN_ID/state-workbook-seed \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: MIRROR_REHEARSAL'
```

Do not place `bloge.tenantId`, `bloge.namespace`, or `__nodeOutput:` keys in `context`; the service derives scope
from the authenticated identity and rejects internal engine-state injection. Run summaries expose only identities,
fingerprints, timestamps, status/trust class, and trace counts. Use the evidence endpoint for signed hash-only
node/edge/resolution facts; neither response returns business payload values.
The state-workbook route rejects stateless or incomplete runs with
`RG.MIRROR.STATE_WORKBOOK_SEED_UNAVAILABLE`; it never manufactures an empty
state workbook.

The strict `resourceGateway.mirrorResolution.v1` protocol is also frozen. It binds every future resolver outcome to
the exact run, plan, capability and invocation attempt; separates resolved null, visible/redacted output, hash-only
evidence, resolved error, rejection and abstention; and fingerprints both visible output and the complete artifact.
The protocol is implemented, schema-tested, produced by the run kernel, and exported inside signed evidence through
the authenticated durable serving surface.

Mirror compilation now freezes source-first selection separately from ordinary test selection. Owner rules precede
governed replay before selector specificity is considered, while ambiguity within one source remains fail-closed.
The exact strategy and per-site order are fingerprinted, and a mirror with no external edges still rejects fixtures
that target internal business nodes.

`MirrorResolver` and `MirrorResolverChain` now provide the bounded runtime extension point. The first adapters serve
exact owner rules and governed replay rules; future sources can be added without changing source precedence. The
chain owns final abstention and fails closed for unavailable or duplicate sources, ordinary controls, and
same-source runtime ambiguity. Mirror controls now execute through that chain and a single-completion journal emits
sealed, coordinate-ordered `MirrorResolution` records. Requests and outputs are represented by bounded canonical
fingerprints; owner and replay results retain exact artifact provenance; business error, rejection, and abstention
remain distinct. Ordinary tests keep their previous selection path. The probe advertises interception only when
the protected plan adapter is assembled, and advertises serving only when the complete run/evidence chain is assembled.

The portable evidence protocol supports the frozen v1 and v2 stateless
generations, a v3 read-only stateful generation, and a v4 read/write stateful
generation. V3 requires nested
`resourceGateway.mirrorStateRunEvidence.v1` and uses distinct
`resourceGateway.mirrorRunEvidence.v3`,
`resourceGateway.mirrorEvidenceAttestation.v3`, and
`resourceGateway.mirrorEvidenceBundle.v3` schemas and signature domain. V4
requires nested `resourceGateway.mirrorStateRunEvidence.v2` and similarly uses
distinct run-evidence, attestation, bundle schemas and a v4 signature domain.
Every generation signs only a
`HASH_ONLY` projection that binds request context, plan, capability closure, execution control, fixture revision,
semantic result, ordered node/edge traces, every sealed resolution, and explicit isolation facts. Newly produced
Ed25519 signatures and the complete bundle fingerprint are verified immediately. The internal run kernel now
projects its real node/attempt/edge values to bounded fingerprints, proves exact closure against every external
resolution, and refuses to return a result when no explicit signer exists or immediate signature verification
fails. A cryptographically signed run is still exploratory unless deployment egress denial is bound to an exact
isolation attestation and every limitation is closed. Payload-free persistence and protected plan serving are
complete; run/evidence serving is also complete for isolated test/staging use. Agent-snapshot admission,
terminal confirmation, v2 evidence binding, and transaction commit fencing are complete. Pre-materialization
ingress controls, non-Java v2 fixtures, cross-language canonicalization, and environment certification remain
production gates.
V3 additionally closes one immutable Session head and every state access
against the existing node-attempt/resolution trace. V4 closes an advancing
Session head, exact read revisions, virtual-write receipts and transition
events against the same trace. The independent test kit verifies v1/v2/v3/v4,
rejects mixed generations, and can derive the state workbook seed only after v3
verification. A local exploratory demo will normally return
`gateReady=false` with blockers such as `EVIDENCE_NOT_CERTIFIABLE` and
`RUN_EVIDENCE_LIMITED`; that is an honest trust result, not a transport failure.
The Spring kernel now has
profile/property isolation and
ordinary business run APIs reject nested mirror, replay, replacement, and scenario controls before DTO binding,
while runtime readiness is derived from profile-owned assembled adapters rather than configuration text.

### Mirror deployment-isolation attestation protocol

The strict `resourceGateway.mirrorDeploymentIsolationAttestation.v1` protocol now defines the
external proof required to close `DEPLOYMENT_EGRESS_NOT_ATTESTED`. It binds an exact deployment
scope, cluster, namespace, workload, service account, immutable image digest, out-of-process
enforcement layers, fail-closed deny facts, policy fingerprints, bounded non-business egress
classes, and payload-free policy-proof references. Its validity is at most 15 minutes, signing may
lag observation by at most 5 minutes, and the complete mirror execution must fit inside the signed
window.

`MirrorDeploymentIsolationAttestationIntegrity` independently checks both canonical fingerprints,
the detached Ed25519 signature, an externally pinned authority key and issuer, key lifecycle and
signing window, exact local deployment identity, and execution-window coverage. The authority key
is separate from the mirror evidence signer. The independent test-kit verifies the same fixed
signed fixture without server or Spring classes.

Authority keys are now distributable through the separate strict
`resourceGateway.mirrorDeploymentIsolationAuthorityKeySetPublication.v1` protocol. Each publication binds the
complete enterprise scope, exact deployment, attestation issuer, stable key-set stream, bootstrap-root trust
domain, exact local M-of-N threshold, policy fingerprint, short validity window, and a monotonic generation plus
predecessor fingerprint. All supplied signatures must verify under distinct locally pinned authorities using
distinct root public-key material; an unknown, revoked, out-of-window, or cryptographically invalid extra signature
rejects the whole publication even after the threshold is met.
`MirrorDeploymentIsolationAuthorityKeySetIntegrity` rejects scope/identity drift, threshold
downgrade, rollback, fork, skipped generation, and predecessor mismatch, then exposes only verified public
attestation keys. The standalone test-kit implements the same checks and packages a public-only two-root fixture.

Authority publications now pass through a full-scope repository and protected trusted-distribution API. The
operator-owned `MirrorDeploymentIsolationAuthorityTrustPolicyProvider` supplies local binding and bootstrap roots;
the default is unavailable and request bodies cannot select trust. Immutable publication insertion, per-stream
`SELECT ... FOR UPDATE` floor CAS, and success audit commit together. Reads re-resolve local policy, re-verify the
current publication, and never serve a historical generation as trusted. The capability probe separately reports
protocol support, route assembly, and trust-provider readiness.

Attestations now pass through a second full-scope trust-control plane. An operator-owned admission provider pins the
exact first external revision, eliminating empty-database trust on first use; later revisions must be continuous.
Immutable proof bodies, append-only `ACTIVE`/`REVOKED` status publications, and one current CAS head commit with the
mandatory success audit. Reads return one canonical atomic bundle. Active bundles must still bind to the same current
authority generation and active time window, while revoked bundles remain distributable during authority outage so a
security denial cannot be blocked. Exact reads never expose historical generations as trusted.

The reusable deployment agent now pulls these current artifacts through private-PKI, SPKI-pinned,
identity-bound mTLS, requires an operator-provisioned non-TOFU bootstrap floor, rejects rollback,
fork, gaps and same-revision reactivation, and atomically replaces a durable read-only snapshot.
Revocation can commit without a positive authority read; stale ACTIVE use is bounded by the local
hard snapshot age. Certification-required runs now bind an admitted and committed agent snapshot
to v2 evidence and keep a transaction-lifetime trust permit through terminal commit. Exploratory
runs remain explicit; only an eligible plan with a stable ACTIVE decision can become `CERTIFIABLE`.
Customer PKI/KMS/IdP, multi-replica revocation convergence, non-Java v2 compatibility, and
environment certification remain deployment responsibilities. See the
[mirror schema guide](../docs/schemas/resource-gateway-mirror/README.md#deployment-isolation-attestation-boundary).
Operational wiring, endpoint semantics, stable failures, and rollout checks are in the
[authority trusted-distribution guide](../docs/resource-gateway-mirror-authority-trusted-distribution.md) and
[attestation control-plane guide](../docs/resource-gateway-mirror-attestation-control-plane.md). Deployment-side
assembly, cache ownership, revocation SLOs and recovery are in the
[deployment-agent guide](../docs/resource-gateway-mirror-deployment-agent.md).

### Mirror recorded-payload generation lifecycle

Recorded exact, trajectory, and cluster payloads now belong to one explicit in-process generation.
Unbound serving results and compiler site-bound views share the same owner. `MirrorRunService`
acquires an execution lease before any admission or engine work; `MirrorRunIntegrationService`
closes the owning `CompiledMirrorPlan` after evidence commit or on every failure path. Plan creation,
which persists only the payload-free public plan, closes its temporary generation immediately.

The lifecycle is `OPEN -> DRAINING -> CLOSED`. Owner close rejects new runs, lets already admitted
leases finish, then synchronously overwrites owned response JSON and private cluster-match byte
buffers. Escaped `Sample`, `Trajectory`, `Cluster`, or bound-view references fail closed after
destruction. Repeated owner and lease close calls are safe. Internal diagnostics can inspect
`ResolvedCorpusPayloads.lifecycle()` for payload-free active-lease, resident-byte, and
zeroized-byte accounting; business values never enter that snapshot or `toString()`.

Recorded-cluster matching owns its projected response only for the resolver call and zeroizes it
immediately after lowering it into a runtime rule. Failed capability, trajectory, cluster, or
whole-generation assembly also closes every payload object whose ownership already transferred.
Once attached, nested payload objects reject direct close; only the generation owner can destroy
them after active leases drain. Attachment and destruction carry the same process-local owner
token, so a failed second generation cannot clean up payloads held by the first. Payload-authority
materializations, their verification copies, and cluster-projection serialization buffers are
independently zeroized at their serving or resolver boundary.

Direct Java callers that materialize a runtime generation own it and must use try-with-resources:

```java
try (CompiledMirrorPlan generation = mirrorPlans.materialize(plan, identity)) {
    MirrorRunResult result = mirrorRuntime.execute(new MirrorRunRequest(
            requestId, generation, context, scope, "MIRROR_REHEARSAL"));
    // Persist or project terminal payload-free evidence before this block exits.
}
```

The protected HTTP run endpoint already applies this lifecycle and requires no client change.
Production certification still requires the planned forked-JVM heap-residue scan, asynchronous
cancellation/crash injection, fixed-cardinality leak telemetry, and a production payload authority
that minimizes heap plaintext with a direct-memory or sidecar-handle implementation.

### Mirror serving-generation fencing

Every non-empty recorded exact, trajectory, or cluster payload generation must now obtain a signed
current-floor token before compilation. The token binds the full enterprise scope, purpose,
payload-free materialized dependency closure, monotonic generation/predecessor, revocation cursor,
expiry, and signed maximum floor-cache staleness. The compiler emits `mirrorPlan.v2`; plans without
recorded corpus remain v1-compatible.

Every new run forces a shared authority floor read. Operator occurrences may reuse that verified
floor only inside the signed staleness window. A newer floor lets already admitted occurrence work
finish but rejects later occurrences before fixture selection, resolver use, or business operator
execution. Authority outage after the cache boundary, rollback, expiry, key failure, token drift,
and stale generation all fail closed with stable evidence codes. Metrics use only bounded
`check` and `outcome` tags.

Deployments must replace the default unavailable `MirrorServingGenerationAuthority` and
`MirrorServingGenerationTrustProvider`; Resource Gateway never treats an authority response or
locally generated key as a trust root. Wiring requirements, stable failures, metrics, rollout
checks, and revocation drills are documented in the
[serving-generation guide](../docs/resource-gateway-mirror-serving-generation.md).

Useful variants:

```bash
./scripts/start-visual-canvas-demo.sh --port 18080
./scripts/start-visual-canvas-demo.sh --no-build
./scripts/start-visual-canvas-demo.sh --run-tests
./scripts/start-visual-canvas-demo.sh --stateful
./scripts/start-visual-canvas-demo.sh --profile production
./scripts/visual-canvas-demo.sh status
./scripts/visual-canvas-demo.sh restart
./scripts/stop-visual-canvas-demo.sh
```

`--stateful` starts the Session data plane, read/virtual-write resolver, v3/v4
evidence projection, and v3 read-only workbook-seed route in the same service.
No extra sidecar is required for the local demonstration; use the ordinary stop
script above.

`--stateful` uses conservative local defaults: 1,000 global and 100 per-scope
active sessions, 4 GiB global and 512 MiB per-scope retained canonical payload,
32 concurrent commands per replica, and a 100-session expiry page every 30
seconds. Override them with
`RG_MIRROR_STATEFUL_MAXIMUM_ACTIVE_SESSIONS`,
`RG_MIRROR_STATEFUL_MAXIMUM_SCOPE_ACTIVE_SESSIONS`,
`RG_MIRROR_STATEFUL_MAXIMUM_RETAINED_PAYLOAD_BYTES`,
`RG_MIRROR_STATEFUL_MAXIMUM_SCOPE_RETAINED_PAYLOAD_BYTES`,
`RG_MIRROR_STATEFUL_MAXIMUM_CONCURRENT_COMMANDS`,
`RG_MIRROR_STATEFUL_EXPIRY_BATCH_SIZE`, and
`RG_MIRROR_STATEFUL_EXPIRY_SWEEP_INTERVAL_MILLIS`. These are hard safety
bounds, not production sizing recommendations. Target database dialect, row-lock
semantics, guard contention, expiry lag, and peak/soak behavior require
deployment-specific certification; this payload counter is not a physical
database disk quota, and the repository test gate currently certifies H2 only.

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

The compact-observation external WORM adapter is a separate, default-off test/staging integration.
Enable it with `RG_TEST_STABILITY_OBSERVATION_ARCHIVE_HTTP_ENABLED=true` and provide the trust
domain, archive-set id, minimum retention, copy threshold, bounded timeout/lifetime, public
Ed25519-key JSON, and authority/failure-domain/HTTPS-endpoint JSON documented in the
[Testing Control Plane API](../docs/resource-gateway-testing-control-plane-api.md). Staging rejects
loopback HTTP and fewer than two copies; production never installs the adapter or retirement bean.
The same endpoint now accepts a distinct signed, challenge-bound read-only inventory protocol for
immutable snapshot paging. A provider may serve a pre-generated snapshot, but the client rejects
future snapshots and snapshots older than the configured bound (300 seconds by default). There is
no inventory controller or delete operation. Durable inventory, frozen comparison, governed
findings, bounded derived-evidence retention, and bounded source-history retirement can now run
autonomously when the separate
`RG_TEST_STABILITY_OBSERVATION_ARCHIVE_RECONCILIATION_ENABLED=true` flag and stable
`RG_TEST_STABILITY_OBSERVATION_ARCHIVE_RECONCILIATION_INSTANCE_ID` are supplied. The scheduler
drains findings and comparisons before opening another inventory cycle, isolates one failed
authority, and remains physically absent in production. Its aggregate Actuator indicator now
combines scheduler freshness, fingerprint-verified database progress for inventory/comparison/
finding, completed-evidence age, and derived-evidence retention freshness/backlog. Startup grace,
transient failure budget, stage-idle and lifecycle thresholds are bounded configuration. A separate
source-retention lane defaults to 365-day processed history, 30-day expired snapshots, one 100-row
dependency segment per hour, and a 120-second database lease. Its database last-success, permanent
active-marker age, and both eligible backlogs enter readiness; normal lease contention does not.
Authority,
object, cursor, lease and fingerprint identities never appear. `/api/integration/capabilities`
separately reports `configured` and current `ready` truth. Descriptor v2 embeds an independent
`sourceRetention` state, and dedicated configured/readiness/health feature flags keep source
lifecycle truth visible even if another stage is degraded. Open governance findings are reported as
an aggregate business outcome and do not make the control loop unhealthy. Inventory authority and
cycle rows now use the same versioned whole-record fingerprint in collection, comparison, and
readiness paths. Signed inventory page JSON and its indexed columns, normalized item ownership,
comparison-authority pointers, and classification commit metadata also have separate whole-row
fingerprints; collection, classification export, and finding projection verify them before use. The
first upgraded test/staging startup establishes a one-time baseline for legacy rows and then requires
non-null fingerprints; perform that upgrade with all replicas stopped because it is not an N/N-1
production migration protocol. These local unkeyed seals expose drift but do not replace database
access/audit controls or an externally witnessed integrity commitment.

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

Stored fixture identity is verified at every trust boundary: the database repository and each
execution, suite-publication, and durable-recovery consumer reconstruct an independently owned,
deeply frozen canonical snapshot, recompute its bundle fingerprint, and bind it to the complete
tenant/environment/id/revision lookup key. Create responses must also match the submitted immutable
identity and content, while idempotent retries preserve first-write provenance. A valid same-key
replacement is treated as dependency drift; mutable aliases, cross-scope
substitution, malformed storage, and tampering fail closed without exposing fixture content.
See the
[stored fixture integrity verification](../docs/resource-gateway-execution-data-control-plane-stage2-fixture-registry-integrity-verification.md)
for invariants, failure semantics, and remaining trust assumptions.

Stored TestSuite revisions use the same fail-closed ownership model across all v1-v5 generations.
Registration first canonicalizes caller-owned case inputs and metadata; repository create/read and
service create/read independently detach the returned value, recompute its exact-generation
fingerprint, bind its envelope to the full tenant/environment/suiteId/revision key, and verify create
receipts. Malformed JSON, content drift, or a valid cross-scope substitution produces the payload-free
`RG.TEST.SUITE_INTEGRITY_INVALID`; idempotent retries retain first-write provenance, while a valid
different suite at the same key remains an immutable revision conflict. These local hashes detect
drift but do not replace external signing or WORM storage. See the
[suite registry verification](../docs/resource-gateway-execution-data-control-plane-stage2-suite-registry-verification.md).

Governed F4 replay values now cross the same kind of hostile storage boundary. The vault detaches
caller JSON/beans, recomputes the available value fingerprint, verifies exact create receipts and
tenant/environment/id/revision lookups, and binds descriptor JSON to every indexed projection. A
second payload-free record commitment protects scope, provenance, and lifecycle state after expiry
erases the value; read-time and scheduled expiry replace that commitment in the same CAS update.
Malformed rows or valid cross-key adapter substitutions emit payload-free
`RG.TEST.REPLAY_INTEGRITY_INVALID` failures. Legacy available rows are revalidated on upgrade;
historical tombstones receive an explicit value-free baseline and therefore are not presented as
retroactively externally authenticated. See the
[replay vault storage integrity verification](../docs/resource-gateway-execution-data-control-plane-stage2-replay-storage-integrity-verification.md).

Signed child evidence now uses one canonical snapshot from seal through storage. Payload-bearing JSON
containers are recursively frozen, arbitrary Java values are detached by an exact evidence round
trip, and the signer returns the value it actually signed. JDBC verifies that signature before a new
write and binds serialized evidence to signed identity metadata, target/fixture/plan fingerprints,
the full tenant/environment/run lookup, and independently indexed row columns on every read.
Mutation, a forged `VERIFIED` manifest, indexed/JSON drift, or cross-scope substitution fails before
projection as payload-free `RG.TEST.EVIDENCE_INTEGRITY_INVALID`. See the
[child evidence storage integrity verification](../docs/resource-gateway-execution-data-control-plane-stage3-child-evidence-storage-integrity-verification.md).

See
[Testing Control Plane API](../docs/resource-gateway-testing-control-plane-api.md)
for the complete target-discovery, fixture-registration, execution, evidence,
and production-isolation workflow. Java/JUnit/CI consumers can use the independent
[Resource Gateway Test Kit](../resource-gateway-test-kit/README.md) for fixture and immutable-suite
builders, bounded deterministic identity/feature-flag fixture controls, typed catalog materialization,
exact suite execution, signed bounded stability analysis,
signed retained-window and compact-range trend reconstruction, pinned-key-set offline verification, payload-free
assertions/XML, and the fail-closed CLI instead of
hand-assembling HTTP requests or interpreting aggregate evidence ad hoc. The same independent JAR
now packages all Stage 0 capability-mirror schemas plus the shared compatibility fixture. ANEKE and
other governance consumers can negotiate `/api/integration/capabilities` and verify snapshot/closure
schema, canonical fingerprint, exact dependency closure, and enterprise scope without linking this
Spring Boot application. See the test-kit's capability-mirror section and
[`docs/schemas/resource-gateway-mirror`](../docs/schemas/resource-gateway-mirror/README.md).

The stability protocol's
terminal publication now also verifies and signs a payload-free compact observation, then commits
that observation, its contiguous per-suite ledger coordinate, the full terminal record, progress
consumption, and lease consumption in one database transaction. This is the durable write-side
foundation for history beyond full-run retention. A bounded, signed range read plus strict Schema and
independent five-layer test-kit verification now exist as a default-disabled test/staging preview.
An internal database-authoritative core can sign a bounded retirement intent, atomically retain its
payload-free local archive, move the durable floor/head coverage, and delete only the exact active
prefix. A separate default-disabled lifecycle endpoint and independent test-kit now prove the
ordered local retirement chain from generation zero to one snapshot-pinned current floor/head,
which can then seed the active compact-range request. `crossRetentionSuiteStabilityTrend`
intentionally remains disabled. The strict multi-authority HTTPS adapter closes the test/staging
write shape with concurrent bounded requests, signed conflict receipts, exact topology/key
verification, and aggregate health. Its signed immutable-snapshot inventory protocol now closes the
read transport shape and exposes a fail-closed historical-page verifier that checks canonical
material, configured topology, snapshot identity, and signing-time key validity without incorrectly
reapplying the page's consumed live-admission deadline. The default-off test/staging control loop
now wires durable lease/cursor, frozen classification, replay-verified finding projection,
downstream backpressure, and bounded
finding/evidence retention. Its source-history retention core now separately fences processed and
expired sources, verifies signing-time trust for stored pages, deletes one bounded dependency segment
per transaction, and permanently gates classification export during and after retirement. It now
runs on its own profile-gated fixed-delay scheduler and reports database-authoritative freshness,
stalled progress, backlog, and nested capability truth. Certified providers, historical trust
publication, legal-hold/erasure/backup and recovery controls,
and witnessed non-equivocation are still required. See the
[lifecycle protocol design](../docs/resource-gateway-execution-data-control-plane-stage5-observation-floor-lifecycle-protocol-design.md),
the [HTTPS WORM adapter design](../docs/resource-gateway-execution-data-control-plane-stage5-observation-http-worm-adapter-design.md),
the [external inventory protocol design](../docs/resource-gateway-execution-data-control-plane-stage5-observation-external-inventory-protocol-design.md),
and the [external reconciliation design](../docs/resource-gateway-execution-data-control-plane-stage5-observation-external-reconciliation-design.md).
The stability protocol's
v2+ evidence keeps behavioral stability separate from release eligibility: every verified source
suite promotion verdict is signed into the attempt closure, so `STABLE + BLOCKED` remains visible
when behavior is repeatable but source certification is insufficient. Historical v1 evidence stays
auditable but cannot enter a release gate. Current statistical v4 reserves the first verified vector
as baseline, signs `verifiedAttempts - 1` comparison trials, and reports an exact one-sided rate
upper bound for complete zero- or non-zero-event samples. Any censoring remains fail closed, while
deterministic `FLAKY` still blocks promotion even when a configured statistical rate ceiling is
satisfied. Historical v3 retains its original zero-event wire semantics for audit; neither
generation is a correctness proof. A database-clock parent
lease now returns retryable `429` to concurrent duplicates before child execution. Every verified
source reference and lease renewal then commit atomically before another attempt can start. Crash
takeover verifies the durable prefix and executes only the remaining horizon; terminal insertion
atomically consumes both progress and lease. The public progress projection exposes only lifecycle,
suite identity, and counts. V5 adds a precommitted alternative and anytime-valid e-process, stops only
at the first reconstructed crossing, first censor, or maximum horizon, and signs the actual observed
prefix plus terminal reason. The durable asynchronous queue is available in test/staging; physical
distribution and isolation of individual attempts are not yet provided.
The invariants and deliberately unclaimed guarantees are recorded in
[Stage 5 suite-stability verification](../docs/resource-gateway-execution-data-control-plane-stage5-suite-stability-verification.md)
and the focused
[execution-lease verification](../docs/resource-gateway-execution-data-control-plane-stage5-suite-stability-execution-lease-verification.md)
and [durable-progress verification](../docs/resource-gateway-execution-data-control-plane-stage5-suite-stability-durable-progress-verification.md).

Fixture `executionServices` v2 can resolve opaque test-secret references without storing plaintext.
The default authority is unavailable. Set `RG_TEST_SECRET_AUTHORITY_HTTP_ENABLED=true`, the HTTPS
base URI, exact authority id, and either a strict public Ed25519 key array or the opt-in dynamic JWKS
settings to enable the built-in adapter in `test`/`staging`. It calls
`/v1/test-secret-resolutions` once per fresh or recovered run with a new
256-bit challenge and accepts only an exact short-lived signed `AUTHORIZED` or `DENIED` response.
Unsigned HTTP denial, redirect, timeout, malformed/oversized response, stale or revoked key, closure
drift, and signature failure all fail closed; values remain run-scoped and never enter a checkpoint.
Set `RG_TEST_SECRET_AUTHORITY_JWKS_ENABLED=true` and its HTTPS URI to get atomic ETag refresh,
unknown-key rotation, explicit `enabled`/`revoked` propagation, a hard maximum snapshot age, and
payload-free Actuator health without restart. Static and dynamic key modes are mutually exclusive;
dynamic bootstrap or any ambiguous refresh fails closed. Multi-replica deployments can additionally
set `RG_TEST_SECRET_AUTHORITY_COHORT_ENABLED=true` plus one stable fleet scope, immutable deployment
cohort, exact instance slot and artifact fingerprint. In staging, also enable the required
deployment-signed inventory and supply its trust domain, accepted policy fingerprints, Ed25519
M-of-N public authority keys, and strict signed JSON envelope. The signed material is authoritative
for the complete serving-slot set and also binds the test-secret authority identity; an optional
configured list is equality-only. Database-clock process-start leases then block secret resolution
until every exact slot is live, healthy, on one complete JWKS generation and on one signed-inventory
generation. Duplicate starts, overlapping deployments, inventory rollback/fork, runtime expiry and
generation drift fail closed. Set the signed-inventory `remote` properties to consume a strict
vendor-media HTTPS publication with ETag refresh, signed `ACTIVE/REVOKED` state, an independent
witness quorum and a namespaced durable publication/witness floor. Candidate verification and floor
advance complete before one atomic local publish; any transport, protocol, signature, freshness,
chain or floor ambiguity blocks resolution without discarding the last diagnostic head. A valid
successor recovers without restart. Capability and health expose aggregate readiness only. Staging
additionally requires managed roots: independent deployment and witness bootstrap-root quorums sign
one atomic deployment/witness runtime-key publication, whose strict HTTPS/ETag refresh and database
floor permit routine key rotation without restarting Resource Gateway. A root generation change
closes resolution until the inventory is reverified, including after an inventory `304`; managed and
legacy static runtime keys cannot be mixed. Staging also requires an external `3f+1 / 2f+1`
challenge-bound notary quorum for both the composite publication/witness stream and the atomic
runtime-root stream. External compare-and-append completes before each local database floor; a
signed conflict is fatal, while an external success followed by local failure is exact-retry safe.
The smallest staging topology is four independent notaries with three accepted receipts. Notary
bootstrap roots are themselves restart-free: staging pins one public genesis, replays a complete
cross-signed root chain from a strict HTTPS bundle, and advances a dedicated durable floor before a
root head becomes usable. The demo startup script validates this configuration before build or Java
startup. Staging also authenticates the external notary, managed receipt-trust publication, and
complete root-bundle source with separately pinned mutual-TLS identities. Automated certificate
rotation, HSM/KMS custody, authority HA/chaos, root anti-equivocation, target-database and DR
certification remain open.
A changed member topology still
requires a coordinated new cohort generation.
See the
[testing control-plane guide](../docs/resource-gateway-testing-control-plane-api.md#421a-control-identity-feature-flag-and-secret-built-ins),
the [managed test-secret trust-root verification](../docs/resource-gateway-execution-data-control-plane-stage4-test-secret-trust-root-rotation-verification.md),
and the [test-secret external non-equivocation verification](../docs/resource-gateway-execution-data-control-plane-stage4-test-secret-external-non-equivocation-verification.md).

The durable stability queue and authenticated asynchronous submit/query/cancel protocol are present
in the isolated `test`/`staging` datastore. Query and cancellation remain available while the worker
is disabled or draining; fresh submission then returns
`503 RG.TEST.STABILITY_JOB_SUBMISSION_UNAVAILABLE`. Set
`RG_TEST_STABILITY_JOB_WORKER_ENABLED=true` only when one current-authority provider is ready. The
built-in provider is enabled with `RG_TEST_STABILITY_JOB_AUTHORITY_HTTP_ENABLED=true`; it sends a
credential-free, challenge-bound request to
`<RG_TEST_STABILITY_JOB_AUTHORITY_HTTP_BASE_URI>/v1/stability-job-authorizations` and accepts only a
short-lived Ed25519-signed `AUTHORIZED` or `REVOKED` response from
`RG_TEST_STABILITY_JOB_AUTHORITY_ID`. Configure one or more X.509-encoded public keys through
`RG_TEST_STABILITY_JOB_AUTHORITY_KEYS_JSON`, or set
`RG_TEST_STABILITY_JOB_AUTHORITY_JWKS_ENABLED=true` and provide
`RG_TEST_STABILITY_JOB_AUTHORITY_JWKS_URI` for restart-free Ed25519 rotation. Dynamic mode performs
an atomic bootstrap, ETag-based background refresh, cooldown-bound unknown-key refresh and hard
snapshot-age expiry. Any refresh ambiguity closes fresh admission; it never silently continues with
stale revocation state. HTTPS is mandatory; the insecure-loopback escape hatches exist only for
local tests. Multi-replica dynamic-JWKS deployments can additionally enable
`RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_ENABLED=true`. Each process then publishes a database-clock
lease keyed by fleet scope, deployment cohort, serving instance, and process start. Fresh submission, worker
claim, and post-claim reauthorization remain closed until the exact expected instance set is
live, healthy, on one artifact/protocol/policy, and observing one complete JWKS generation. A stable
scope elects only one live deployment cohort, so overlapping rollouts cannot each self-admit. This
can retain local configured inventory in the `test` profile. Staging additionally requires an
M-of-N Ed25519-signed deployment serving inventory delivered through a strictly versioned HTTPS
publication. Deployment authorities sign `ACTIVE`/`REVOKED` state and an independent witness domain
signs the same sequence and predecessor chain. Atomic ETag refresh, hard source age, signed expiry,
revocation, protocol downgrade, chain ambiguity, and refresh failure all fail closed. Every live
cohort member must also publish one identical private publication/witness generation before
convergence. Before any verified generation becomes observable, a database-clock stable-scope floor
atomically persists its sequence and publication/witness fingerprints; complete process or fleet
restart therefore cannot accept rollback, fork, gap, or a broken predecessor while the test-runtime
database remains intact. Floor corruption or database outage fails startup/refresh closed. The nested
inventory revision, material/policy fingerprints, expiry, exact set,
artifact, scope, cohort, and protocol remain bound into policy; a durable stable-scope revision floor
rejects rollback and same-revision forks. The optional local instance list is only an equality
assertion. Static document injection remains a `test` fallback and is forbidden with staging remote
mode.

Physical-attempt terminal projection has a separate opt-in local runtime. It remains disabled by
default through `RG_TEST_PHYSICAL_ATTEMPT_TERMINAL_PROJECTION_ENABLED=false` and is physically absent
from every profile containing `production`. An embedding deployment may enable it only after
supplying pinned `TestSuiteStabilityPhysicalAttemptStartVerifier`,
`TestSuiteStabilityPhysicalAttemptObservationVerifier`, and
`TestSuiteStabilityAttemptCancellationVerifier` beans. Startup also requires the isolated database
`DatabaseTestSuiteStabilityJobRepository`; a generic or remote queue adapter is rejected because the
terminal queue transition and physical-slot release must share its transaction authority. The same
switch activates physical-attempt orphan-slot fencing on that queue and startup verifies the fence is
actually enabled; a legacy fence-off database queue is rejected. Set a
stable replica identity with `RG_TEST_PHYSICAL_ATTEMPT_TERMINAL_PROJECTION_WORKER_ID`. The remaining
`RG_TEST_PHYSICAL_ATTEMPT_TERMINAL_PROJECTION_*` settings bound pollers, zero-queue call capacity,
lease/call/completion budgets, retry, and readiness SLOs; invalid combinations fail startup without
echoing configured identities. Actuator health exposes aggregate work counts and local capacity only,
and Micrometer labels are closed enums. This lane consumes already registered terminal work.

Autonomous retained-start discovery is a second, independently gated test/staging runtime. First
enable the terminal-projection lane, then set
`RG_TEST_PHYSICAL_ATTEMPT_OBSERVATION_RECONCILIATION_ENABLED=true`, configure a stable
`..._WORKER_ID`, and supply exactly one
`TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority` bean. An arbitrary function or Map
resolver no longer satisfies startup. Static signed inventory remains useful for isolated component
tests, but Tool Studio intentionally reports it as `DYNAMIC_INVENTORY_REQUIRED` rather than fleet
readiness.

The product dynamic authority is opt-in with
`RG_TEST_PHYSICAL_ATTEMPT_PROVIDER_INVENTORY_ENABLED=true` and is physically absent from any profile
containing `production`. The embedding deployment must register exactly one
`TestSuiteStabilityPhysicalAttemptRuntimeAdapterCatalog`; this catalog is only the installed adapter
superset. The signed publication remains the sole admission authority and must name every expected
Resource Gateway replica. There is deliberately no local expected-replica list that could narrow a
fleet. Configure the trust domain, scope, cohort, accepted policy fingerprints, independent
deployment/witness trust domains, local replica/artifact identities, and the HTTPS source using the
`RG_TEST_PHYSICAL_ATTEMPT_PROVIDER_INVENTORY_*` variables documented in `application-test.yml` and
`application-staging.yml`. Exactly one verification mode is allowed. Static migration mode supplies
the deployment/witness thresholds and public Ed25519 keys directly. Managed mode sets those four
static fields to `0`/`[]` and enables
`RG_TEST_PHYSICAL_ATTEMPT_PROVIDER_INVENTORY_TRUST_ROOT_ENABLED=true`.

Managed mode consumes one atomically signed deployment/witness runtime-key publication. Configure
`..._TRUST_ROOT_SET_ID`, accepted root policy fingerprints, independent deployment/witness bootstrap
root domains, thresholds and public-key arrays, the root publication URI, refresh/timeout/unknown-key
budgets, and the hard maximum age. The root endpoint must return
`application/vnd.bloge.physical-attempt-provider-inventory-trust-root-publication.v1+json` and the
exact `X-BLOGE-Physical-Provider-Inventory-Trust-Root-Protocol` value
`bloge.testSuiteStabilityPhysicalAttemptProviderInventoryTrustRootPublication.v1`. It must not reuse
the inventory publication URI. The root authority bootstraps before the inventory consumer, owns an
independent database sequence floor and Actuator health contributor, supports restart-free atomic
dual-key rotation, and closes only after its consumer during context shutdown.

The publication endpoint must return
`application/vnd.bloge.physical-attempt-provider-inventory-publication.v1+json`, the exact
`X-BLOGE-Physical-Provider-Inventory-Protocol` value
`bloge.testSuiteStabilityPhysicalAttemptProviderInventoryPublication.v1`, and a strict signed
`ACTIVE` or `REVOKED` envelope. Bootstrap and each ETag refresh verify the nested inventory,
deployment quorum, independent witness quorum, predecessor chains, exact signed replica set, hard
freshness, and the database publication/witness floor before atomically exposing a generation.
Refresh ambiguity immediately closes resolution; a valid successor restores it without restart.
Previously resolved wrappers are generation-fenced and also close after successor or revocation.

Each process start publishes a database-clock lease keyed by the signed scope/cohort and local
replica identity. Cohort readiness requires the exact signed replica set, one publication generation,
one artifact and protocol, and no missing, unexpected, duplicate, drifted, expired, or corrupt row.
The heartbeat interval must not exceed half the lease. Startup rejects missing catalogs, unknown
properties, unsafe timing, unsigned fallback, and non-database floor/cohort composition without
echoing configured identities.

Adapter descriptors must exactly reproduce signed key, isolation, latency, and retention facts
before provider I/O. Inventory hard expiry closes both new resolution and previously resolved
wrappers without restart. The reconciliation runtime also rejects non-database start, observation,
or terminal-work journals and unsafe deadline/window/lease/capacity combinations. Its database
journal discovers retained starts in bounded fair-scope pages; a verified terminal completion and
projection-work registration commit atomically.
Observation provider-call capacity is enforced by nonblocking admission permits. Active and
interrupt-ignoring lingering calls consume permits and reject excess work immediately. A separate
bounded executor buffer is only a worker-handoff mechanism after a provider has returned; it prevents
a completed sequential descriptor/observation call from being misclassified as saturation while the
worker is still in executor bookkeeping. Timeout, interruption, and close remove unstarted handoffs
and recycle their permits, while a started provider retains its permit until it actually exits.
Actuator health reports only aggregate discovery lag, due age, quarantine, scheduler state, and
provider-call capacity. The provider-inventory health contributor similarly exposes aggregate
refresh/cohort facts without replica, provider, deployment, key, fingerprint, or URI identities.
Micrometer uses only closed labels. Tool Studio publishes the typed, identity-free
`physicalAttemptRuntime` capability and reaches `READY` only while the dynamic authority, exact cohort,
terminal projection, and observation reconciliation are simultaneously healthy. All switches remain
disabled by default, and the standalone demo script intentionally enables none of these physical
lanes. The physical Spring composition now binds publication and witness into one
domain-separated, external-first composite head and reports external versus Byzantine quorum truth
separately through a dedicated domain marker and the shared strict HTTP/quorum adapter. `test` keeps
this optional for local migration, so disabled deployments remain honestly database-floor-only. An
enabled anchor must resolve to exactly one available, externally durable and challenge-bound marker
bean. A configured non-zero fault bound also requires a Byzantine descriptor; hidden, duplicate,
unsafe, or invalid-quorum anchors fail startup.

Configure this path below
`RG_TEST_PHYSICAL_ATTEMPT_PROVIDER_INVENTORY_EXTERNAL_ANCHOR_*` in the profile YAML. The groups are:
the notary set and timing policy, `..._TRANSPORT_*` for the notary HTTPS identity,
`..._TRUST_*` plus `..._TRUST_TRANSPORT_*` for managed receipt-key publication, and
`..._BOOTSTRAP_ROOT_*` plus `..._BOOTSTRAP_ROOT_TRANSPORT_*` for its complete-chain root source.
Staging also requires managed roots with `..._TRUST_ROOT_ENABLED=true`,
`..._TRUST_ROOT_REQUIRED=true`, strict HTTPS, and every insecure-loopback escape hatch off. It
refuses the physical inventory unless external anchoring is enabled and required with
`minimum-faults >= 1` and `maximum-faults >= 1`; managed receipt trust, complete-chain bootstrap
roots, and all three private-PKIX/SPKI/mTLS/workload-identity transports must also be enabled and
required and carry exact client/server certificate identities, with every insecure-loopback escape
hatch off. Staging YAML makes each identity requirement follow transport enablement, and Java
preflight independently rejects any unbound transport so direct property injection cannot downgrade
the policy. The three transport identities are now
part of the shared 15-target restart-free certificate-rotation inventory. Aggregate health exposes
only strength and availability facts. The strict product entry point is the
[physical external-anchor configuration Schema](../docs/schemas/resource-gateway-testing/physical-attempt-provider-inventory-external-anchor-configuration-v1.schema.json).

N/N-1 root and publication backfill, bounded evidence retention, HSM/KMS custody, root-publisher
HA/anti-equivocation certification, a certified process/container adapter, external notary
production certification, fleet failover/chaos evidence, and production profile wiring remain
open. See the
[dynamic provider-inventory verification](../docs/resource-gateway-execution-data-control-plane-stage4-dynamic-physical-provider-inventory-verification.md),
[external non-equivocation core verification](../docs/resource-gateway-execution-data-control-plane-stage4-physical-provider-inventory-external-non-equivocation-core-verification.md),
[external non-equivocation runtime verification](../docs/resource-gateway-execution-data-control-plane-stage4-physical-provider-inventory-external-non-equivocation-runtime-verification.md),
[managed trust-root consumer verification](../docs/resource-gateway-execution-data-control-plane-stage4-physical-provider-inventory-managed-trust-root-consumer-verification.md),
[managed trust-root product-composition verification](../docs/resource-gateway-execution-data-control-plane-stage4-physical-provider-inventory-managed-trust-root-product-composition-verification.md),
and the strict
[publication](../docs/schemas/resource-gateway-testing/physical-attempt-provider-inventory-publication-v1.schema.json),
[generation floor](../docs/schemas/resource-gateway-testing/physical-attempt-provider-inventory-publication-generation-v1.schema.json), and
[cohort binding](../docs/schemas/resource-gateway-testing/physical-attempt-provider-inventory-cohort-binding-v1.schema.json)
schemas.

Staging also requires managed serving-inventory runtime keys. One canonical publication atomically
carries the deployment and witness key sets and is independently approved by an M-of-N deployment
bootstrap-root quorum and an M-of-N witness bootstrap-root quorum. Configure
`RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_TRUST_ROOTS_ENABLED=true`, the HTTPS source,
stable root-set id, accepted policy fingerprints, independent root domains, thresholds, and public
root-key arrays under the corresponding `...INVENTORY_*_ROOT_*` variables in
`application-staging.yml`. Legacy static runtime trust domains, thresholds, and key arrays must be
unset (`0`/`[]`) in managed mode. The source performs strict bootstrap, ETag refresh, bounded
unknown-key refresh, hard-age expiry, dual-quorum verification, and a database-backed sequence
floor before exposing a new immutable runtime key snapshot. Inventory verification binds the exact
root generation; rotation A to B therefore needs no application restart, while root outage,
rollback, fork, partial publication, threshold revocation, or generation disagreement closes
admission. The root health contributor and cohort descriptor expose only aggregate state and
boolean protocol facts.

Staging additionally requires both mutable ordering streams to be anchored outside the rollbackable
Resource Gateway database. Set
`RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_ENABLED=true` and configure one
stable trust domain/set id and one HTTPS endpoint plus distinct failure domain per notary. Staging
also requires `...EXTERNAL_ANCHOR_MANAGED_TRUST_ENABLED=true` and
`...EXTERNAL_ANCHOR_BOOTSTRAP_ROOTS_ENABLED=true`. Deployment pins the public-only
`...EXTERNAL_ANCHOR_BOOTSTRAP_ROOT_GENESIS_JSON`; a strict HTTPS
`...EXTERNAL_ANCHOR_BOOTSTRAP_ROOT_BUNDLE_URI` supplies the complete cross-signed successor chain.
Only a full genesis replay accepted by its dedicated database floor can authorize the strict
HTTPS/ETag notary publication containing the exact key lifecycle set, policy, quorum, validity
window and monotonic predecessor chain. Legacy bootstrap thresholds/key arrays and static notary
keys must be `0`/`[]`. Unknown
receipt keys trigger one cooldown-bound refresh, so routine key rotation and revocation require no
Resource Gateway restart; any refresh, signature, lifecycle, rollback, fork, gap or durable-floor
ambiguity immediately fails receipt verification closed. The built-in policy uses `3f+1`
authorities and a `2f+1` accepted-receipt
threshold; staging enforces `f>=1`, so the smallest deployment is four independently operated
notaries with a three-signature quorum. Every request carries a fresh 256-bit challenge and exact
publication or trust-root sequence head. A valid signed conflict is fatal even when another quorum
accepts; unavailable or malformed minority responses are tolerated only while the acceptance
threshold remains. The external compare-and-append commits before the local database floor. Thus an
external success followed by local failure is safe to retry, while no local generation can become
visible without an external checkpoint. This closes complete database-backup rollback only under
the declared `<=f` Byzantine and independent-failure-domain assumptions; deploying, certifying,
backing up, and monitoring the external notary service remains a deployment responsibility. The
embedded bootstrap-root maker/checker service now automatically renews an exact database-issued
successor fence during long signer calls, freezes renewal before terminal commit, and fails closed
on response ambiguity, malformed successors, expiry, or shutdown. Optional unattended recovery now
uses one fixed-delay daemon lane per root-set journal. Discovery, failed-attempt backoff, automatic
attempt budget, expired-lease takeover, and new fence issuance occur atomically under database time;
the policy fingerprint is bound to the durable root-set lock so replicas with different retry
pressure fail startup. Runtime authority resolution starts only after acquisition and the approved
public cohort is recomputed before signing. Resolution, descriptor, and signature calls all pass
through configurable wall-clock deadlines and one fixed-capacity, zero-queue daemon pool;
interrupt-ignoring calls remain visible as bounded lingering occupancy, while saturation rejects new
work immediately. Heartbeats and recovery never extend checker approval or the proposal execution
deadline. A successful `PRODUCED` transition now atomically enqueues one content-addressed,
complete-chain publication request in a separate durable outbox. The outbox verifies its source
ceremony on every claim, preserves root sequence order, uses database leases/backoff/attempt limits,
backfills legacy produced rows, and rejects policy drift or whole-row corruption. Remote publishers
must replay the exact `publicationId`; only a matching receipt advances the outbox to `PUBLISHED`.
The built-in publisher adapter sends that request over strict HTTPS with the publication id as the
idempotency key and the predecessor as an HTTP conditional. It accepts only bounded strict JSON
whose fresh response material, request fingerprint, status, publisher binding, and Ed25519 signature
all verify against one statically pinned public response key. A database-fenced publication service
and one-lane scheduler perform automatic delivery through a fixed-capacity zero-queue call
supervisor. Invalid or unsigned `409` responses enter ordinary retry backoff; only a meaningful
signed conflict enters durable `QUARANTINED` and blocks every successor sequence. Close each
recovery/publication scheduler before its owning service. A profile-gated Spring composition root
can now run one publication lane in `test` or `staging`; it is physically absent whenever
`production` is active, including mixed-profile startup, and remains disabled until
`RG_TEST_BOOTSTRAP_ROOT_PUBLICATION_ENABLED=true`. Enabling it requires scope, root-set, worker,
endpoint, publisher/trust identity, static Ed25519 response key and whole-second key lifecycle
values under the matching `RG_TEST_BOOTSTRAP_ROOT_PUBLICATION_*` variables in the two profile YAML
files. Staging additionally requires `TRANSPORT_ENABLED=true`, `TRANSPORT_REQUIRED=true`, one
dedicated PKCS#12 client identity, one to sixteen server SPKI pins, and either JVM PKIX roots or an
explicit PKCS#12 trust store. Every enabled staging control-plane transport also requires
`EXPECTED_CLIENT_SUBJECT_DN`, `EXPECTED_CLIENT_URI_SAN`, `CLIENT_ISSUER_SPKI_PINS`,
`EXPECTED_SERVER_URI_SAN`, and `SERVER_ISSUER_SPKI_PINS` under its existing transport prefix.
The staging profile derives `certificate-identity-required=true` from `TRANSPORT_ENABLED`; the test
profile leaves it false for explicit compatibility tests. Keystore passwords are opaque references
resolved only while the immutable TLS context is built; the demo resolver accepts `env:VARIABLE`, while embedders may
provide exactly one vault/workload-identity resolver. PKIX, hostname verification, SPKI pinning and
mTLS must all succeed. The test profile retains the historical system-trust adapter only as an
explicit migration path. Unknown fields, partial transport identity, insecure staging loopback,
unsafe timeout/lease margins, or reuse of the inventory/managed-root client identity fail before
credential resolution, journal DDL, or protocol-adapter assembly. Aggregate Actuator health and the
service snapshot report only system/private trust, pinning, mTLS and certificate-identity-bound
booleans, never paths, references, pin values or certificate selectors. The v1 Java snapshot
constructor remains as an explicit
system-trust compatibility projection. The default lane uses the
isolated test-runtime database journal/outbox; deployments may supply an equivalent durable outbox
or publisher bean. Close the caller-owned publisher after the service. See the
[publisher transport verification](../docs/resource-gateway-execution-data-control-plane-stage4-bootstrap-root-publisher-transport-verification.md).

For test/staging paths that require restart-free certificate replacement, the transport package now
provides `ControlPlaneCertificateIdentityPolicy` and `RotatingControlPlaneHttpTransport` as a kernel.
The policy binds one client key to an exact Subject and single workload URI SAN, validates its chain
with a pinned issuer as the PKIX trust anchor at the declared activation instant, and constrains the
server issuer and workload URI independently. The rotation kernel preloads exactly the next
generation outside the request-state lock, enforces bounded activation and old/new certificate
overlap, and lets a cached client atomically select one complete TLS generation per request. The
static identity policy is wired through all control-plane transport properties, both Spring
profiles, strict configuration schemas, health, recovery-fleet capability v4, and Tool Studio
features. Staging therefore rejects an enabled publisher, inventory, trust-root, notary,
managed-trust, or root-bundle link without complete workload identities. Embedders can now place
`ControlPlaneCertificateRotationController` in front of that kernel. Its strict v1 event is bound
to deployment scope, target, active settings fingerprint, contiguous generation, candidate
settings fingerprint and activation window; an independent public-key-only M-of-N Ed25519 policy
must verify before an opaque material id can be resolved. Exact concurrent replay resolves and
stages once, failures preserve the old generation, and target drift fails closed without exposing
paths, secret references or resolver errors. A database-clock rotation floor linearizes accepted
generations across replicas, journals exact event identities, advances due successors atomically,
rejects restart rollback and same-generation forks, and exposes a strict credential-free snapshot
v1. The test/staging product runtime now binds this floor-first state machine to all 15 stable
transport ids: it verifies a strict out-of-band baseline, restores an active or pending durable
successor from the controlled material catalog, and lets exact replay repair a replica whose local
staging failed after durable acceptance. Set
`RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_ENABLED=true` and
`RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_REQUIRED=true`, then provide the exact deployment scope,
trust domain, accepted policy fingerprints, M-of-N public Ed25519 authorities, timing bounds, target
baseline generation/material ids, and public material-location catalog under the adjacent
`RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_*` variables. The demo script rejects partial policy,
malformed bounds/JSON, private keys, or resolved passwords before building the service. The
fleet-convergence primitives now also define an exact replica inventory, process-start leases,
strict `STAGED/ACTIVE/FAILED` acknowledgements, all-replica or fenced-quorum stage thresholds, one
database-authoritative active fleet, and an external inventory revision/downgrade floor. Their
aggregate snapshot separates `activationPermitted` from exact all-replica `converged` and never
returns replica ids or TLS material locations. The test/staging runtime now runs the exact
process-start heartbeat, caches every database decision for at most two heartbeat intervals, and
uses the same monitor to fence both the durable floor and live transport. A due successor first
requires a fresh all-replica `STAGED` proof at or after signed database time, then advances the
durable floor, then activates locally; the new generation cannot serve until every configured slot
reports exact `ACTIVE`. Restarted signed generations must republish that proof, database loss or
lease expiry closes admission, and a failed candidate leaves the old generation usable while health
stays degraded. Multi-replica mode requires externally attested inventory;
`FENCED_QUORUM` is rejected at startup until a real traffic fence exists. Enable the sibling
`gateway.testing.control-plane-certificate-rotation-convergence` policy with the adjacent
`RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_CONVERGENCE_*`, fleet, process, artifact, inventory and
lease variables. Actuator and Tool Studio now report bounded convergence integration,
availability, current proof and serving readiness.

Signed certificate-status admission is available beside rotation in the `test` and `staging`
profiles. Set `RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_ENABLED=true` only with required signed
rotation and the exact same deployment scope. Configure an independent status trust domain,
accepted policy fingerprints, M-of-N public Ed25519 authority keys, a pinned publication-chain
baseline, bounded refresh policy, and a private-PKIX/SPKI/mTLS identity under
`RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_TRANSPORT_*`. The source accepts only strict normalized
v2 responses containing an M-of-N signed exact source head and at most one complete-snapshot
successor. Independent database-clock floors reject head/publication gaps, forks, rollback, omitted
targets, stale head renewal, and revoked-to-valid resurrection before updating the local dual-clock
admission cache.
Every live rotating transport checks the exact target, generation, and settings fingerprint before
request dispatch. `REVOKED`, `UNKNOWN`, mismatch, or hard expiry therefore fails before handler I/O.

The status source deliberately uses a static, separately governed client identity rather than one
of the rotating transports it protects; this avoids a recursive bootstrap dependency. A transient
source outage may continue serving only while the last durable signed snapshot remains inside its
wall-clock and monotonic hard-expiry lease. Actuator and Tool Studio distinguish source
availability from cached admission freshness, and expose no URI, certificate, fingerprint,
credential reference, or provider diagnostic. A sibling fixed-cardinality SLO assessment tracks
startup grace, current source outage, exact source-head availability and lag, last-success age,
hard-expiry headroom, and mature refresh failure and admission-denial ratios. Configure its bounded
thresholds with `RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_SLO_*`, including
`RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_SLO_MAXIMUM_SOURCE_HEAD_LAG`. Consecutive batch-limit
cycles remain diagnostic only. A fresh cache may still admit
exact requests while the SLO reports `SOURCE_UNAVAILABLE`; availability policy and alert truth are
intentionally separate. Micrometer and Tool Studio export only closed decisions and aggregate
counts. The demo preflight validates exact scope binding, HTTPS, public-only trust material, finite
I/O/scheduler/SLO bounds, private trust, workload identities, and cross-source client-identity
isolation before Maven build. See the
[certificate-status product verification](../docs/resource-gateway-execution-data-control-plane-stage4-certificate-status-product-verification.md).

Authenticated CA event delivery is now available as a separate test/staging product path. Set
`RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_ENABLED=true` only after signed rotation
and convergence are both enabled and required, then configure an HTTPS endpoint, pinned baseline
sequence/fingerprint, bounded poll/page policy, and an independent
`RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_TRANSPORT_*` client identity. The source
requires private PKIX, server SPKI pinning, mTLS, and exact client/server workload identities. Its
TLS identity authorizes page delivery only: every event in a fingerprint-chained page still passes
the independent M-of-N rotation trust policy. A stable serving-slot database cursor performs
`fetch -> stage exact page -> apply every event -> commit`; partial failure leaves the page staged,
and restart accepts only exact replay. The watcher pauses without source I/O while fleet serving
admission is fenced, bounds each cycle to 1..32 pages, and exports only fixed-cardinality health and
Tool Studio booleans. The demo preflight rejects HTTP/loopback staging sources, missing convergence,
weak transport, unreadable key stores, invalid bounds, or resolved credentials before Maven build.
See the [event watcher product verification](../docs/resource-gateway-execution-data-control-plane-stage4-certificate-rotation-event-watcher-product-verification.md).

`productionReady` deliberately remains false. The delivered test/staging path does not yet prove a
certified enterprise CA/OCSP/CRL normalizer, dynamic status-authority trust rotation, CA source HA
and retention/compaction, event/status-source client-certificate hot rotation, external alerting
and burn-rate routing, multi-region source-head equivocation witnessing, HSM custody, production
database certification, or HA/DR/chaos behavior.
See the
[certificate identity and rotation kernel verification](../docs/resource-gateway-execution-data-control-plane-stage4-certificate-identity-and-rotation-kernel-verification.md).

The same single-root journal can run unattended ceremony recovery by also setting
`RG_TEST_BOOTSTRAP_ROOT_RECOVERY_ENABLED=true`. Recovery requires publication to remain enabled,
one strict public-only genesis document, accepted ceremony policy fingerprints, a worker identity,
bounded signer/scheduler policy, and exactly one application-provided
`ExternalSequenceAnchorBootstrapRootAuthorityResolver` bean. The resolver maps the exact approved
public cohort to opaque signer ports; private keys, HSM credentials, authority endpoints, and
provider inventories must not be bound through recovery properties. Staging additionally rejects a
genesis without Byzantine fault tolerance. The recovery scheduler and ceremony service close before
the shared journal; the service close gate prevents a shutdown poll from consuming a new durable
attempt. Its aggregate-only Actuator health treats no work, approval wait, competing lease, and
retry delay as healthy workflow states, but fails on attempt exhaustion, latest scheduler/execution
failure, fence loss, or fully lingering signer capacity.

Embedders that already compose multiple root-set services can place exact service/resolver pairs in
an `ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory`. Each `Lane` must carry the service's
exact immutable `ExpectedBinding` and a reviewed `sha256:` runtime-binding fingerprint; mismatched or
duplicate scope/root-set identities are rejected before polling. Publish every add/remove/rebind as a
strictly newer inventory generation, then call
`ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.runCycle()`. A cycle visits at most the
configured lane budget and resumes after the last attempted canonical key, including after a failed
lane, so a poison prefix cannot starve later root sets. Generation rollback, same-generation
descriptor drift, and same-generation service/resolver replacement fail closed. Lane failures are
returned without exception text or provider diagnostics; the underlying per-root journal remains the
only acquisition, retry, attempt-budget, and fence authority. Close the worker before its caller-owned
services; close waits for an admitted cycle but never closes inventory, resolvers, or services.

For continuous embedding, wrap that worker in an
`ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler`. Its single fixed-delay daemon lane and
explicit `runOnce()` path share one admission monitor, so local cycles never overlap. Runtime failures
remain visible but do not stop later polls; a fatal error terminates the periodic future after
publishing a bounded failure snapshot. The scheduler also reports an overdue idle timer or active
cycle against explicit health budgets. Register
`ExternalSequenceAnchorBootstrapRootRecoveryFleetHealth` when aggregate Actuator readiness is
needed: closed, overdue, cycle-wide, and latest lane failures are DOWN, while empty/no-work cycles are
UP. Health reads no inventory and exports no lane identity or diagnostics. Close in strict order:
scheduler, worker, then caller-owned services/resolvers. Reentrant scheduler close from an active
cycle is rejected; concurrent close callers share a completion barrier without holding the cycle
monitor, preventing shutdown lock inversion.

The test/staging Spring composition can now install the durable fleet runtime, but it is disabled by
default and is not a new HTTP endpoint or deployment-wide inventory registry. Contribute exactly one
caller-owned `ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory` bean, then set
`RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_ENABLED=true`, a stable
`RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_ID`, and a per-replica
`RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_WORKER_ID`. Partition count, lease, lane budget, initial delay,
poll interval, cycle budget, and drain timeout use the adjacent
`RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_*` settings in `application-test.yml` and
`application-staging.yml`. The composition preflights inventory/topology before coordinator tables,
then owns database coordinator, worker, scheduler, and aggregate health. Spring closes scheduler
before worker and never closes the caller-owned inventory, lane services, resolvers, or database.
Fleet mode and `RG_TEST_BOOTSTRAP_ROOT_RECOVERY_ENABLED=true` are mutually exclusive; any active
`production` profile physically removes the fleet composition.

Deployments can replace a self-asserted local lane list with
`ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority`. Its strict signed
attestation binds the complete canonical lane descriptors to deployment scope, artifact,
`fleetId`, fixed partition count, generation, policy, and hard validity window under distinct-authority
Ed25519 M-of-N verification. Only then are signed lane keys resolved from a reviewed local in-memory
catalog, and every resolved descriptor must match exactly. Worker admission rechecks the same signed
generation around each lane and before durable cursor commit; expiry or generation change fails the
cycle closed. The separate inventory health projection remains aggregate-only and honestly advertises
that this static mode has no automatic refresh, signed revocation, or durable generation floor.

Deployment governance can now publish that exact attestation inside a strict witnessed
`ACTIVE`/`REVOKED` predecessor chain. The publication machine contract binds deployment scope,
fleet, policy, sequence, inventory identity, state, validity, and both publication/witness
predecessors. A
`DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor` serializes
verified heads by scope and fleet, survives process reconstruction, and rejects rollback,
same-sequence forks, gaps, broken predecessors, nested-inventory rollback or same-generation drift,
same-inventory reactivation after revocation, corrupt rows, and cross-fleet reuse. Its v2 record
upgrades a v1 row only after an exact cryptographically verified replay of the stored dual head;
it never guesses missing legacy inventory state. The
`DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority` now consumes that
protocol through bounded HTTPS/ETag refresh, independently verifies deployment and witness
M-of-N signatures, revalidates the nested inventory, advances the durable floor before local
publication, and atomically exposes only an exact `ACTIVE` runtime snapshot. A verified `REVOKED`
publication advances the floor without resolving removed lanes and immediately closes recovery
admission. Any refresh, protocol, signature, runtime-binding, or floor failure also fails closed;
the last verified object remains diagnostics-only and cannot be used as stale admission authority.

The trust-root control plane defines how the deployment and witness runtime verification keys
rotate as one signed generation. Independent deployment-root and witness-root quorums
approve the same strict, short-lived material; exact scope/fleet/protocol/policy binding, four-domain
independence, canonical Ed25519 keys, dual thresholds, and sequence/predecessor continuity are
verified before a durable database floor advances. Only then can one immutable dual-key set become
observable. A signed emergency revocation advances the floor but closes runtime admission when a
threshold is no longer satisfiable. A strict HTTPS/ETag authority now refreshes that publication,
performs cooldown-bounded unknown-key refresh, and supplies the exact same immutable key-set
generation to the dynamic inventory verifier. Root generation drift closes admission until the
inventory is reverified, including on a source `304`; disjoint replacement roots therefore reject a
cached inventory instead of extending its trust. The test/staging Spring path now exposes this as a
product mode under `bootstrap-root-recovery-fleet-dynamic-inventory.trust-roots`. Managed mode
forbids every static runtime domain, threshold, and key; owns a separate durable root floor and
aggregate health indicator; and closes inventory before roots. Staging requires dynamic inventory,
managed roots, external Byzantine ordering for the publication and atomic-root streams, and pinned
mutual TLS for every control-plane call. Inventory, inventory trust-root, bootstrap-root publisher,
and each product domain's notary, managed receipt-trust publication, and complete root-bundle source
must use independent client identity configurations. All links retain PKIX and hostname verification,
add one to sixteen exact server SPKI pins, and reject insecure loopback. Test retains explicit
system-trust/static compatibility paths for migration. The demo script checks the same downgrade and
global identity-isolation invariants before build, while Spring remains the authoritative gate.
External-anchor descriptor and health surfaces expose only fixed transport booleans, never endpoints,
paths, secret references, pins, or certificate identities. See the
[recovery fleet trust-root kernel verification](../docs/resource-gateway-execution-data-control-plane-stage4-bootstrap-root-recovery-fleet-trust-root-kernel-verification.md)
and [dynamic trust-root verification](../docs/resource-gateway-execution-data-control-plane-stage4-bootstrap-root-recovery-fleet-dynamic-trust-root-verification.md), the
[managed trust-root Spring verification](../docs/resource-gateway-execution-data-control-plane-stage4-bootstrap-root-recovery-fleet-managed-trust-root-spring-verification.md), plus the strict
[dynamic inventory Spring configuration v2 JSON Schema](../docs/schemas/resource-gateway-testing/external-sequence-anchor-bootstrap-root-recovery-fleet-dynamic-inventory-configuration-v2.schema.json),
[external anchor configuration JSON Schema](../docs/schemas/resource-gateway-testing/external-sequence-anchor-bootstrap-root-recovery-fleet-external-anchor-configuration-v1.schema.json),
[transport and non-equivocation verification](../docs/resource-gateway-execution-data-control-plane-stage4-bootstrap-root-recovery-fleet-transport-and-non-equivocation-verification.md),
[trust-root publication JSON Schema](../docs/schemas/resource-gateway-testing/external-sequence-anchor-bootstrap-root-recovery-fleet-inventory-trust-root-publication-v1.schema.json)
and [dynamic snapshot JSON Schema](../docs/schemas/resource-gateway-testing/external-sequence-anchor-bootstrap-root-recovery-fleet-inventory-dynamic-trust-root-snapshot-v1.schema.json).

Embedders that share the test-runtime database can also construct a
`DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator` and pass it, one stable
`fleetId`, and one fixed partition count to every worker replica. The coordinator uses database-clock
partition leases, active-command retry deduplication, generation fencing, exact renewal/completion,
failure abandonment, and durable per-partition cursors; the worker heartbeats independently of slow
lane execution. A busy coordinator is reported separately from an empty completed inventory. The
lane journal remains the only execution/write fence.

The test/staging Spring path now supports two explicit inventory modes. Embedders may continue to
supply one static or custom authority bean. Alternatively, enabling
`bootstrap-root-recovery-fleet-dynamic-inventory` constructs the witnessed HTTPS authority from
public-only strict properties, requires exactly one caller-owned reviewed lane resolver, and uses a
database publication/witness floor unless one custom durable floor is supplied. Stateless topology,
trust-domain, public-key, binding, URI, and duration validation completes before floor DDL or remote
I/O. Staging additionally requires the certified dynamic mode and rejects static fallback; test keeps
it optional. Both configurations are physically absent when `production` is active. The existing
worker fences every lane and cursor commit against current authority generation and availability.

The Spring path does not generate trust roots or discover lane runtimes. The existing
`GET /api/integration/capabilities` endpoint now publishes an identity-free, versioned recovery-fleet
state machine and conservative boolean projections. Capability v1 remains frozen; v2 adds managed
root availability/status/sequence, atomic dual-root and floor strength, plus combined
non-equivocation claims; v3 adds separate aggregate transport-authentication facts for inventory and
managed-root sources. Its probe reads only startup-frozen bean
candidates and fresh process-local snapshots; it does not perform bootstrap I/O. Online partition
rebalance, external fleet-wide alert/convergence wiring, production-profile wiring, pinned mTLS for
managed notary-trust and bootstrap-root consumer endpoints, response-key hot rotation,
publisher/notary HA and gossip
certification, target-database/DR/chaos certification,
provider-confirmed cancellation, and HSM custody remain deployment gates. The genesis, complete
bundle, and publication HTTP Schemas, failure matrix, runtime wiring, and remaining ceremony limits
are documented in the
[bootstrap-root ceremony verification](../docs/resource-gateway-execution-data-control-plane-stage4-bootstrap-root-ceremony-kernel-verification.md)
and [recovery fleet kernel verification](../docs/resource-gateway-execution-data-control-plane-stage4-bootstrap-root-recovery-fleet-kernel-verification.md).
The signed lane-inventory protocol, runtime reverse binding, hard-expiry fence, health projection, and
remaining dynamic-control-plane gates are in the
[recovery fleet signed inventory verification](../docs/resource-gateway-execution-data-control-plane-stage4-bootstrap-root-recovery-fleet-signed-inventory-verification.md).
The import/export wire contract is the strict
[fleet inventory JSON Schema](../docs/schemas/resource-gateway-testing/external-sequence-anchor-bootstrap-root-recovery-fleet-inventory-v1.schema.json).
The witnessed publication wire contract is the strict
[fleet inventory publication JSON Schema](../docs/schemas/resource-gateway-testing/external-sequence-anchor-bootstrap-root-recovery-fleet-inventory-publication-v1.schema.json),
with kernel verification in the
[publication floor verification](../docs/resource-gateway-execution-data-control-plane-stage4-bootstrap-root-recovery-fleet-publication-floor-kernel-verification.md).
The dynamic consumer, revocation, refresh, runtime-fence, health, and failure semantics are in the
[dynamic fleet inventory verification](../docs/resource-gateway-execution-data-control-plane-stage4-bootstrap-root-recovery-fleet-dynamic-inventory-verification.md).
The profile/configuration/lifecycle contract and H2 context-rebuild proof are in the
[recovery fleet runtime composition verification](../docs/resource-gateway-execution-data-control-plane-stage4-bootstrap-root-recovery-fleet-runtime-composition-verification.md).
The strict dynamic properties, startup ordering, staging downgrade fence, ownership contract, and
real signed-HTTP Spring proof are in the
[dynamic fleet inventory Spring composition verification](../docs/resource-gateway-execution-data-control-plane-stage4-bootstrap-root-recovery-fleet-dynamic-inventory-spring-composition-verification.md).
The capability state machine, strict Schema, no-I/O projection, compatibility, and integration proof
are in the
[recovery fleet capability verification](../docs/resource-gateway-execution-data-control-plane-stage4-bootstrap-root-recovery-fleet-capability-verification.md),
with its current machine contract in the
[recovery fleet capability v3 JSON Schema](../docs/schemas/resource-gateway-testing/external-sequence-anchor-bootstrap-root-recovery-fleet-capability-v3.schema.json), the frozen
[v2 JSON Schema](../docs/schemas/resource-gateway-testing/external-sequence-anchor-bootstrap-root-recovery-fleet-capability-v2.schema.json),
and the frozen
[v1 JSON Schema](../docs/schemas/resource-gateway-testing/external-sequence-anchor-bootstrap-root-recovery-fleet-capability-v1.schema.json).

Enabled test/staging fleets also install a versioned process-local SLO assessment and 41
fixed-cardinality Micrometer series. The monitor reuses the authority-bracketed immutable capability
projection and performs no inventory, lane, database, network, or payload I/O. It evaluates current
runtime failure independently from startup grace, successful-poll freshness, and mature poll/cycle/
lane failure ratios. An unattested local inventory, snapshot tear, or observation failure is never
healthy; unavailable value gauges use `-1`, not a false zero. Staging cannot disable this monitor.
Configure the strict policy with `RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_SLO_*`; startup grace must
cover initial delay plus one poll interval, and maximum success age must cover two poll intervals.
The assessment carries the exact policy used, while metric tags are limited to closed status,
violation, outcome, and scope vocabularies. Registry/exporter configuration, alert routing,
long-lived SLI storage, and fleet-wide convergence remain deployment responsibilities. See the
[recovery fleet SLO verification](../docs/resource-gateway-execution-data-control-plane-stage4-bootstrap-root-recovery-fleet-slo-verification.md)
and strict
[SLO assessment JSON Schema](../docs/schemas/resource-gateway-testing/external-sequence-anchor-bootstrap-root-recovery-fleet-slo-assessment-v1.schema.json).

Scope, cohort, recovery-fleet inventory/root sources, static-key exclusion, managed-root freshness,
external-anchor quorum/timing, authenticated transport, global client-identity isolation, and lease
settings are checked by
`scripts/visual-canvas-demo.sh` before staging startup. A deployment may
instead contribute one custom
`TestSuiteStabilityJobAuthorizer` with a ready key-free descriptor. There is no allow-all fallback:
zero providers, multiple providers, an undeclared provider, missing trust, unsafe URI, or invalid
time policy fails startup. Queue capacity, fairness, retry, lease, deadline, and retention use the
`RG_TEST_STABILITY_JOB_*` variables documented in `application-test.yml` and
`application-staging.yml`. Worker environment/lane and heartbeat/lease contradictions also fail
startup. Startup readiness is not cached as perpetual authority: every fresh submission and every
capability probe reevaluates the single provider's local, key-free descriptor. An expired/revoked
local trust key, provider ambiguity, or descriptor failure immediately closes fresh admission and
reports submission unavailable without probing the remote PDP. Retained exact idempotent replay is
resolved before this mutable readiness check, so key rotation cannot make an accepted request
appear absent or cause it to execute twice. The capability probe separates
`asyncSuiteStabilityJobProtocol` from
`asyncSuiteStabilityJobSubmission`, and additionally reports
`suiteStabilityCurrentAuthorityRevalidation` plus
`signedChallengeBoundSuiteStabilityAuthority`. Dynamic deployments additionally expose
`dynamicSuiteStabilityAuthorityTrust` and `suiteStabilityAuthorityTrustRefreshSlo`, so clients do
not infer executability merely because the routes or Schema exist. Cohort deployments also expose
`exactSuiteStabilityAuthorityTrustCohort` and the current-state
`convergedSuiteStabilityAuthorityTrustCohort`; descriptors and health contain aggregate counts and
status only. `externallyAttestedSuiteStabilityServingInventory` separately proves that the expected
set comes from a currently verified external attestation;
`dynamicSuiteStabilityServingInventory` and
`witnessedSuiteStabilityServingInventoryPublications` describe the stronger refresh protocol;
`durableSuiteStabilityServingInventoryPublicationFloor` confirms that its ordering floor survives a
complete fleet restart. Managed deployments additionally report
`restartFreeSuiteStabilityServingInventoryKeyRotation` and
`atomicDualQuorumSuiteStabilityServingInventoryTrustRoots`; convergence requires every member to
declare the same atomic dual-root protocol. The stronger
`externallyAnchoredSuiteStabilityServingInventoryOrdering` and
`byzantineQuorumSuiteStabilityServingInventoryNonEquivocation` flags are true only when both
publication and managed-root ordering streams have the corresponding external guarantee. None
expose instance ids, cohort ids,
inventory ids, endpoints, key ids, signatures, or trust fingerprints. The private authority request
excludes credentials, correlation id,
execution metadata, fixture/context/payload and node output. HTTP denial, redirect, timeout,
malformed/oversized JSON, stale decision, echo mismatch, unknown/revoked key, or invalid signature
is `UNAVAILABLE`; only a verified signed revocation is definitive. Capacity responses use
`Retry-After`, configured with
`RG_TEST_STABILITY_JOB_API_RETRY_AFTER_SECONDS` (default `5`). See the focused
[current-authority verification](../docs/resource-gateway-execution-data-control-plane-stage5-suite-stability-current-authority-verification.md),
[dynamic authority trust verification](../docs/resource-gateway-execution-data-control-plane-stage5-suite-stability-dynamic-authority-trust-verification.md),
[authority cohort verification](../docs/resource-gateway-execution-data-control-plane-stage5-suite-stability-authority-cohort-verification.md),
[signed serving-inventory verification](../docs/resource-gateway-execution-data-control-plane-stage5-suite-stability-serving-inventory-verification.md),
[managed trust-root rotation verification](../docs/resource-gateway-execution-data-control-plane-stage5-suite-stability-trust-root-rotation-verification.md),
[external non-equivocation verification](../docs/resource-gateway-execution-data-control-plane-stage5-suite-stability-external-non-equivocation-verification.md),
[machine-readable serving-inventory Schema](../docs/schemas/resource-gateway-testing/suite-stability-serving-inventory-v1.schema.json),
[machine-readable managed trust-root publication Schema](../docs/schemas/resource-gateway-testing/suite-stability-serving-inventory-trust-root-publication-v1.schema.json),
[machine-readable external checkpoint Schema](../docs/schemas/resource-gateway-testing/suite-stability-external-sequence-checkpoint-v1.schema.json),
[machine-readable compact-observation external archive Schema](../docs/schemas/resource-gateway-testing/suite-stability-observation-external-archive-v1.schema.json),
[strict HTTPS WORM adapter design](../docs/resource-gateway-execution-data-control-plane-stage5-observation-http-worm-adapter-design.md),
[signed external inventory protocol design](../docs/resource-gateway-execution-data-control-plane-stage5-observation-external-inventory-protocol-design.md),
the [receipt-aware lifecycle v2 design](../docs/resource-gateway-execution-data-control-plane-stage5-observation-lifecycle-v2-external-proof-design.md),
and [machine-readable authority Schema](../docs/schemas/resource-gateway-testing/suite-stability-authority-v1.schema.json).
Actuator exposes separate stability-queue, dynamic-authority-trust, and managed inventory-root
health contributors. The
`RG_TEST_STABILITY_JOB_SLO_*` settings bound per-environment queue depth, oldest wait, observation
interval, and expired live leases; the depth SLO cannot exceed hard queue capacity. Micrometer
publishes only closed environment/status/outcome dimensions. Business test failures remain visible
status totals but do not make the deployment unhealthy. Database observation failure is `DOWN`, and
metric-registry failure cannot stop worker execution. See
[queue observability verification](../docs/resource-gateway-execution-data-control-plane-stage5-suite-stability-queue-observability-verification.md).

Expired terminal job detail can now be replaced transactionally by a payload-free request tombstone.
Exact replay then returns `REPLAY_WINDOW_EXPIRED`; another intent using the same scoped request key
remains an idempotency conflict. Tombstones keep only a tenant/environment-bound, independently
domain-separated keyed HMAC index and an integrity fingerprint, never the plaintext request id or
job payload. Configure `RG_TEST_STABILITY_JOB_REQUEST_KEY_ACTIVE_ID` and
`RG_TEST_STABILITY_JOB_REQUEST_KEY_RING` with deployment secrets; append the new generation fleet-
wide before changing active, and keep every old verification key until its final tombstone expires.
Startup fails when a live tombstone references a missing generation. A profile-gated retention
service now acquires one database-clock lease across replicas, processes independent bounded detail
and tombstone pages atomically, advances integrity-protected cumulative counters, and publishes
aggregate-only metrics plus freshness/backlog readiness. Defaults are a 120-second lease, 100 rows
per page, a one-hour interval, and 365-day tombstones; terminal job detail still follows the queue's
30-day default. A page that outlives its lease rolls back in full. Configure and operate it with the
`RG_TEST_STABILITY_JOB_RETENTION_*` variables in the profile YAML; invalid page, lease, interval, or
freshness combinations fail startup even while the worker is disabled. See
[job tombstone verification](../docs/resource-gateway-execution-data-control-plane-stage5-suite-stability-job-tombstone-verification.md)
and the [retention service verification](../docs/resource-gateway-execution-data-control-plane-stage5-suite-stability-retention-service-verification.md).

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

Suite checkpoint and terminal persistence now uses the exact canonical v1-v5 aggregate returned by
the signing boundary. The store and service independently bind its signature, aggregate fingerprint,
tenant/organization/project/environment/actor/classification metadata, record envelope, lookup key,
and indexed columns; create/update receipts and abandoned-run candidates from replaceable repository
adapters are verified before use. Altered JSON, forged `VERIFIED` labels, cross-scope substitutions,
and mutable caller aliases fail closed without echoing business payloads. The invariant and remaining
database/WORM trust assumptions are documented in
[Stage 3 suite-run storage integrity verification](../docs/resource-gateway-execution-data-control-plane-stage3-suite-run-storage-integrity-verification.md).

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
