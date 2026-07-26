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
| `mirror-plan-v1.schema.json` | `MirrorPlan` | Legacy sealed payload-free execution generation without recorded-corpus serving data |
| `mirror-plan-v2.schema.json` | `MirrorPlan` | Current sealed plan; requires a signed serving generation whenever recorded corpus resolvers are present |
| `mirror-serving-generation-token-v1.schema.json` | `MirrorServingGenerationToken` | Signed scope/purpose/dependency-bound generation, revocation cursor, expiry, and floor-cache staleness authority |
| `mirror-plan-create-request-v1.schema.json` | `MirrorPlanCreateRequest` | Payload-free protected compile command containing only reviewed artifact identities and bounded requested budgets |
| `mirror-execution-request-v1.schema.json` | `MirrorExecutionRequest` | Strict stateless execution command containing only request/plan identity, reviewed plan fingerprint, and business context |
| `mirror-execution-request-v2.schema.json` | `MirrorExecutionRequest` | Stateful execution command adding an exact payload-free Session id/state-fingerprint binding |
| `mirror-run-summary-v1.schema.json` | `MirrorRunSummary` | Compact payload-free terminal projection derived from verified evidence |
| `mirror-resolution-v1.schema.json` | `MirrorResolution` | Fingerprinted per-attempt source, confidence, freshness, payload visibility, output/error, and abstention provenance |
| `mirror-run-evidence-v1.schema.json` | `MirrorRunEvidence` | Payload-free node, edge, resolution, semantic-result, request-context, and isolation facts for one terminal run |
| `mirror-run-evidence-v2.schema.json` | `MirrorRunEvidence` | Stateless trust-bound evidence generation; adds run trust and requires it for v2 deployment-egress or certifiable claims |
| `mirror-run-evidence-v3.schema.json` | `MirrorRunEvidence` | Stateful generation requiring one exact sealed `mirrorStateRunEvidence.v1` closure |
| `mirror-run-evidence-v4.schema.json` | `MirrorRunEvidence` | Read/write stateful generation requiring one exact sealed `mirrorStateRunEvidence.v2` transition closure |
| `mirror-run-evidence-v5.schema.json` | `MirrorRunEvidence` | Failure-aware read/write generation requiring one exact sealed `mirrorStateRunEvidence.v3` write-attempt outcome closure |
| `mirror-evidence-attestation-v1.schema.json` | `MirrorEvidenceAttestation` | Domain-separated detached Ed25519 signature over one complete mirror run evidence value |
| `mirror-evidence-attestation-v2.schema.json` | `MirrorEvidenceAttestation` | Stateless detached-signature generation with a distinct v2 signature domain |
| `mirror-evidence-attestation-v3.schema.json` | `MirrorEvidenceAttestation` | Stateful detached-signature generation with a distinct v3 signature domain |
| `mirror-evidence-attestation-v4.schema.json` | `MirrorEvidenceAttestation` | Read/write stateful detached-signature generation with a distinct v4 signature domain |
| `mirror-evidence-attestation-v5.schema.json` | `MirrorEvidenceAttestation` | Failure-aware stateful detached-signature generation with a distinct v5 signature domain |
| `mirror-evidence-bundle-v1.schema.json` | `MirrorEvidenceBundle` | Portable `HASH_ONLY` evidence, attestation, and complete bundle fingerprint closure |
| `mirror-evidence-bundle-v2.schema.json` | `MirrorEvidenceBundle` | Strict stateless trust-bound bundle; requires v2 evidence and attestation and rejects mixed generations |
| `mirror-evidence-bundle-v3.schema.json` | `MirrorEvidenceBundle` | Strict stateful bundle; requires v3 evidence/attestation and rejects mixed generations |
| `mirror-evidence-bundle-v4.schema.json` | `MirrorEvidenceBundle` | Strict read/write stateful bundle; requires v4 evidence/attestation and rejects mixed generations |
| `mirror-evidence-bundle-v5.schema.json` | `MirrorEvidenceBundle` | Strict failure-aware stateful bundle; requires v5 evidence/attestation and rejects mixed generations |
| `mirror-deployment-isolation-attestation-v1.schema.json` | `MirrorDeploymentIsolationAttestation` | Short-lived external proof binding an exact deployment generation to fail-closed egress and credential controls |
| `mirror-deployment-isolation-attestation-status-v1.schema.json` | `MirrorDeploymentIsolationAttestationStatusPublication` | Locally content-addressed `ACTIVE` or irreversible `REVOKED` status for one exact attestation revision |
| `mirror-deployment-isolation-attestation-bundle-v1.schema.json` | `MirrorDeploymentIsolationAttestationBundle` | Atomic current-only distribution of authority reference, external attestation body, and local status |
| `mirror-deployment-isolation-agent-snapshot-v1.schema.json` | `MirrorDeploymentIsolationAgentSnapshot` | Crash-safe local cache generation binding refresh deadline, optional denial-only authority body, and atomic attestation bundle |
| `mirror-deployment-isolation-run-trust-v1.schema.json` | `MirrorDeploymentIsolationRunTrust.Binding` | Stable decision plus admitted/committed local agent observations signed into v2 evidence |
| `mirror-deployment-isolation-attestation-revocation-request-v1.schema.json` | `MirrorDeploymentIsolationAttestationRevocationRequest` | Exact-current optimistic command for one irreversible status transition |
| `mirror-deployment-isolation-authority-key-set-publication-v1.schema.json` | `MirrorDeploymentIsolationAuthorityKeySetPublication` | Full-scope, monotonic, M-of-N bootstrap-root-signed publication of isolation-attestation authority keys |
| `read-only-shadow-guard-policy-publication-v1.schema.json` | `ReadOnlyShadowGuardPolicyPublication` | Short-lived signed current-head policy for one authority-owned shared concurrency, rate, and circuit budget |
| `read-only-shadow-sampling-grant-publication-v1.schema.json` | `ReadOnlyShadowSamplingGrantPublication` | Short-lived signed current-head logical-sampling authorization joining an exact execution scope to one exact shared guard policy |
| `read-only-shadow-kill-switch-publication-v1.schema.json` | `ReadOnlyShadowKillSwitchPublication` | Fifteen-minute signed current-head operational enable/deny decision for one exact execution scope |
| `read-only-shadow-authority-key-set-publication-v1.schema.json` | `ReadOnlyShadowAuthorityKeySetPublication` | Root-threshold-signed, scope/kind/issuer-bound current key set with monotonic revocation cursor and irreversible retained key lifecycle |
| `read-only-shadow-authority-key-set-page-v1.schema.json` | `ReadOnlyShadowAuthorityKeySetPage` | Frozen high-water, bounded contiguous cursor page for independently verified cross-process authority trust distribution |
| `capability-observation-v1.schema.json` | `CapabilityObservationEnvelope` | Signed payload-free capability invocation with exact sanitized-payload, proof, schema, purpose, trace, and state references |
| `capability-observation-admission-v1.schema.json` | `CapabilityObservationAdmission` | Content-addressed local `ADMITTED` or terminal `QUARANTINED` decision |
| `capability-observation-receipt-v1.schema.json` | `CapabilityObservationReceipt` | Atomic ingest result linking the exact producer envelope to its immutable local decision |
| `capability-observation-review-request-v1.schema.json` | `CapabilityObservationReviewRequest` | Closed payload-free terminal review command for one exact quarantine |
| `capability-observation-review-v1.schema.json` | `CapabilityObservationReview` | Immutable review fact that never rewrites the original admission |
| `capability-corpus-candidate-request-v1.schema.json` | `CapabilityCorpusCandidateRequest` | Ordered exact source selection with optimistic candidate-lineage fencing |
| `capability-corpus-revision-v1.schema.json` | `CapabilityCorpusRevision` | Non-serving payload-free source snapshot with deterministic metadata risk |
| `capability-corpus-publish-request-v1.schema.json` | `CapabilityCorpusPublishRequest` | Owner-reviewed publication command with independent lineage fencing |
| `capability-corpus-publication-v1.schema.json` | `CapabilityCorpusPublication` | Immutable serving-publication fact for one exact eligible candidate |
| `capability-corpus-trajectory-publish-request-v1.schema.json` | `CapabilityCorpusTrajectoryPublishRequest` | Explicit owner-reviewed retry sequence bound to exact published sources and current retry policy |
| `capability-corpus-trajectory-publication-v1.schema.json` | `CapabilityCorpusTrajectoryPublication` | Immutable payload-free serving artifact for one governed recorded retry trajectory |
| `capability-corpus-cluster-validation-v1.schema.json` | `CapabilityCorpusClusterValidation` | Externally verified payload-free membership, match-path, identity-projection, holdout, and Wilson-confidence proof |
| `capability-corpus-cluster-publish-request-v1.schema.json` | `CapabilityCorpusClusterPublishRequest` | Owner-reviewed, predecessor-fenced command referencing one current corpus, cluster policy, and validation proof |
| `capability-corpus-cluster-publication-v1.schema.json` | `CapabilityCorpusClusterPublication` | Immutable payload-free recorded-cluster publication with exact support, identity safety, confidence, policy, and lineage |
| `fixture-mirror-corpus-bindings-v1.schema.json` | `FixtureMirrorCorpusBindings` | Strict immutable fixture metadata selecting one exact latest publication per external capability |
| `fixture-mirror-trajectory-bindings-v1.schema.json` | `FixtureMirrorTrajectoryBindings` | Strict immutable fixture metadata selecting reviewed trajectories under an exact selected corpus publication |
| `fixture-mirror-cluster-bindings-v1.schema.json` | `FixtureMirrorClusterBindings` | Strict immutable fixture metadata selecting reviewed recorded clusters under an exact selected corpus publication |
| `bounded-state-expression-v1.schema.json` | `BoundedStateExpression` | Closed deterministic state expression AST with runtime depth/node bounds |
| `state-model-v1.schema.json` | `StateModel` | Content-addressed entity schemas, unique business keys, invariants, scope, and provenance |
| `state-read-spec-v1.schema.json` | `StateReadSpec` | Exact read capability to Session business-key lookup and bounded response projection |
| `write-effect-spec-v1.schema.json` | `WriteEffectSpec` | Owner-governed atomic multi-entity virtual mutation and exact idempotency contract |
| `session-state-space-v1.schema.json` | `SessionStateSpace` | Payload-bearing isolated world, tombstones, business-key index, transition journal, and receipts |
| `mirror-session-payload-v1.schema.json` | `MirrorSessionPayload` | Encrypted data-plane aggregate closing one state model, admitted write effects, and current session state |
| `mirror-session-create-request-v1.schema.json` | `MirrorSessionCreateRequest` | Strict idempotent session creation command with no independent tenant selector |
| `mirror-session-descriptor-v1.schema.json` | `MirrorSessionDescriptor` | Payload-free lifecycle, dependency, revision, and fingerprint projection |
| `mirror-session-command-request-v1.schema.json` | `MirrorSessionCommandRequest` | Strict exact-effect state transition command with an optional optimistic state fence |
| `mirror-session-command-result-v1.schema.json` | `MirrorSessionCommandResult` | Current payload-free descriptor plus original or newly committed transaction receipt |
| `mirror-state-write-attempt-v1.schema.json` | `MirrorStateWriteAttempt` | Durable payload-free intent, execution coordinate with a domain-separated correlation fingerprint, terminal outcome, and crash-reconciliation proof |
| `mirror-session-store-generation-v1.schema.json` | `MirrorSessionStoreGeneration` | Initialize-once durable state-plane generation fence that remains stable across restart and changes for an independently initialized store |
| `mirror-session-checkpoint-v1.schema.json` | `MirrorSessionCheckpoint` | Payload-free exact Session, dependency, state, descriptor, and store-generation closure |
| `mirror-session-checkpoint-attestation-v1.schema.json` | `MirrorSessionCheckpointAttestation` | Detached Ed25519 attestation in the checkpoint-specific signature domain |
| `mirror-session-checkpoint-bundle-v1.schema.json` | `MirrorSessionCheckpointBundle` | Portable `HASH_ONLY` checkpoint and attestation bundle |
| `mirror-session-recovery-result-v1.schema.json` | `MirrorSessionRecoveryResult` | Payload-free exact recovery admission and reconstructed Session run binding |
| `case-handling-assertion-v1.schema.json` | `CaseHandlingAssertion` | Payload-free business handling assertion over graph, node, edge, capability, state, effect, governance, latency, retry, and resource evidence |
| `scenario-handling-assertion-result-v1.schema.json` | `ScenarioHandlingAssertionResult` | Content-addressed payload-free result binding one exact assertion to one verified evidence bundle; unavailable facts remain explicitly indeterminate |
| `scenario-rehearsal-execution-request-v1.schema.json` | `ScenarioRehearsalExecutionRequest` | Payload-free request containing only an aggregate idempotency key and exact compiled-plan ref; runtime overrides are forbidden |
| `scenario-case-rehearsal-result-v1.schema.json` | `ScenarioCaseRehearsalResult` | Content-addressed per-case execution and assertion interpretation with complete-or-absent child evidence identity |
| `scenario-rehearsal-result-v1.schema.json` | `ScenarioRehearsalResult` | Content-addressed ordered aggregate with fail-closed outcome precedence and derived case/assertion counters |
| `scenario-rehearsal-evidence-attestation-v1.schema.json` | `ScenarioRehearsalEvidenceAttestation` | Domain-separated Ed25519 manifest binding stable aggregate run id, request, compiled plan, result fingerprint, and signing time |
| `scenario-rehearsal-evidence-bundle-v1.schema.json` | `ScenarioRehearsalEvidenceBundle` | Independently verifiable `HASH_ONLY` portable aggregate containing one complete content-addressed result and detached signature |
| `scenario-rehearsal-legal-hold-command-v1.schema.json` | `ScenarioRehearsalLegalHoldCommand` | Strict idempotent placement/release command with independent hold identity and stable governance reason |
| `scenario-rehearsal-purge-command-v1.schema.json` | `ScenarioRehearsalPurgeCommand` | Strict idempotent aggregate-deletion command carrying no policy override or business payload |
| `scenario-rehearsal-retention-event-v1.schema.json` | `ScenarioRehearsalRetentionEvent` | Signed payload-free retention/hold/deletion transition with complete scope, immutable retention boundary, previous-event address, and explicit child-evidence disposition |
| `scenario-rehearsal-retention-state-v1.schema.json` | `ScenarioRehearsalRetentionState` | Rebuildable multi-hold projection whose latest signed event becomes the independently verifiable deletion proof after purge |
| `scenario-rehearsal-workbook-seed-v1.schema.json` | `ScenarioRehearsalWorkbookSeed` | Deterministic payload-free ANEKE input binding exact Plan, signed aggregate, initial signed retention proof, ordered case/assertion closure, and conservatively derived gate blockers |
| `scenario-rehearsal-batch-workbook-seed-v1.schema.json` | `ScenarioRehearsalBatchWorkbookSeed` | Bounded deterministic ANEKE batch input binding signed terminal batch evidence, signed retention registration, ordered child commitments and correctness projections, conservative blockers, and a domain-separated root seal for no-fan-out verification |
| `scenario-rehearsal-batch-job-page-v1.schema.json` | `ScenarioRehearsalBatchJobPage` | Exact-scope newest-first payload-free jobs with immutable creation-time keyset pagination for Owner workbench discovery |
| `scenario-case-v1.schema.json` | `ScenarioCase` | Exact binding from one business intent to an existing TestSuite case, FixtureBundle, MirrorPlan, deterministic services, optional isolated Session checkpoint, explicit fault rules, and handling assertions |
| `scenario-pack-v1.schema.json` | `ScenarioPack` | Content-addressed ordered scenario closure and fail-closed sequential rehearsal policy |
| `scenario-rehearsal-compile-request-v1.schema.json` | `ScenarioRehearsalCompileRequest` | Exact registered ScenarioPack revision and fingerprint requested for online closure compilation |
| `compiled-scenario-rehearsal-plan-v1.schema.json` | `CompiledScenarioRehearsalPlan` | Compiler-issued payload-free execution license after exact TestSuite, FixtureBundle, MirrorPlan, assertion, and optional checkpoint closure verification |
| `scenario-rehearsal-batch-finalization-status-v1.schema.json` | `ScenarioRehearsalBatchFinalizationStatus` | Exact-job payload-free outbox state, attempt, retry/lease fence, stable failure class, and terminal evidence coordinate |
| `scenario-rehearsal-batch-finalization-remediation-request-v1.schema.json` | `ScenarioRehearsalBatchFinalizationRemediationRequest` | Admin-only compare-and-set command for one reviewed quarantined generation |
| `scenario-rehearsal-batch-finalization-remediation-receipt-v1.schema.json` | `ScenarioRehearsalBatchFinalizationRemediationReceipt` | Immutable content-addressed receipt linking old and renewed finalization intents, policy generation, retention floor, and audit acceptance time |
| `scenario-rehearsal-batch-finalization-health-v1.schema.json` | `ScenarioRehearsalBatchFinalizationHealth` | Exact enterprise-scope payload-free backlog, age, quarantine, policy-drift, control-integrity, failure-class, and server-threshold SLO projection |
| `scenario-rehearsal-remediation-preview-request-v1.schema.json` | `ScenarioRehearsalRemediationPreviewRequest` | Owner intent constrained to exact rerun or selected compiled-plan replacement, one immutable predecessor workbook, and one governance ticket |
| `scenario-rehearsal-remediation-plan-v1.schema.json` | `ScenarioRehearsalRemediationPlan` | Content-addressed preview that freezes predecessor evidence, complete successor batch request, closed remediation reason, and the fixed OWNER plus INDEPENDENT_REVIEWER policy |
| `scenario-rehearsal-remediation-approval-command-v1.schema.json` | `ScenarioRehearsalRemediationApprovalCommand` | Compare-and-set approval or rejection command against one plan fingerprint and expected approval generation |
| `scenario-rehearsal-remediation-approval-v1.schema.json` | `ScenarioRehearsalRemediationApproval` | Content-addressed append-only role-bound approval fact with server-owned actor, delegation, timestamp, and previous-head link |
| `scenario-rehearsal-remediation-submit-command-v1.schema.json` | `ScenarioRehearsalRemediationSubmitCommand` | Final compare-and-set submission command bound to the approved plan and exact approval-chain head |
| `scenario-rehearsal-remediation-receipt-v1.schema.json` | `ScenarioRehearsalRemediationReceipt` | Immutable receipt linking one predecessor batch to the distinct successor rehearsal created from the frozen plan |
| `scenario-rehearsal-remediation-lineage-v1.schema.json` | `ScenarioRehearsalRemediationLineage` | Content-addressed complete decision lineage reconstructed from the frozen plan, append-only approval chain, derived state, and optional successor receipt |
| `scenario-rehearsal-remediation-comparison-v1.schema.json` | `ScenarioRehearsalRemediationComparison` | Deterministic predecessor/successor comparison reconstructed from two independently verified root-signed workbooks, with exact root and entry blocker set differences rather than a synthetic score |
| `domain-fidelity-inventory-registration-request-v1.schema.json` | `DomainFidelityInventoryRegistrationRequest` | Strict Owner command for one immutable full-scope coverage-denominator revision |
| `domain-fidelity-inventory-v1.schema.json` | `DomainFidelityInventory` | Owner-approved content-addressed domain coverage denominator with exact Scenario/capability units |
| `domain-fidelity-profile-v1.schema.json` | `DomainFidelityProfile` | Signed payload-free seven-dimension fidelity vector with complete denominator, confidence, source, and abstention debt |
| `read-only-shadow-comparison-v1.schema.json` | `ReadOnlyShadowComparison` | Legacy signed payload-free single-request typed baseline/candidate comparison; egress proof closes to the exact `DEPLOYMENT_ISOLATION_ATTESTATION` protocol kind |
| `read-only-shadow-comparison-v2.schema.json` | `ReadOnlyShadowComparison` | Legacy certifiable comparison adding exact normalization policy and source-resolution attestation closure |
| `read-only-shadow-comparison-v3.schema.json` | `ReadOnlyShadowComparison` | Current comparison adding double-observed grant, guard-policy, and kill-switch publication evidence with exact material/attestation coordinate closure |
| `read-only-shadow-job-request-v1.schema.json` | `ReadOnlyShadowJobRequest` | Immutable payload-free online-source Shadow admission command with exact grant ordinal, authority coordinates, and deadline |
| `read-only-shadow-job-request-v2.schema.json` | `ReadOnlyShadowJobRequest` | Detached-evidence admission command that must name one exact content-addressed `SHADOW_SOURCE_BINDING`; no latest-run inference is permitted |
| `read-only-shadow-source-binding-registration-request-v1.schema.json` | `ReadOnlyShadowSourceBindingRegistrationRequest` | Unsigned authority command containing exact baseline facts and candidate evidence coordinates but no caller-selected fingerprint or seal |
| `read-only-shadow-source-binding-v1.schema.json` | `ReadOnlyShadowSourceBinding` | Signed payload-free detached baseline/candidate pair with nested baseline and outer binding content addresses, exact candidate bundle closure, and bounded validity |
| `read-only-shadow-source-resolution-attestation-v1.schema.json` | `ReadOnlyShadowSourceResolutionAttestation` | Signed payload-free proof that one stable execution independently re-resolved the exact source binding and candidate evidence under one immutable comparison policy, with separate source/resolution times and zero-write closure |
| `read-only-shadow-job-v1.schema.json` | `ReadOnlyShadowJob` | Integrity-addressed public durable queue projection without payload or raw lease owner |
| `read-only-shadow-job-lifecycle-event-v1.schema.json` | `ReadOnlyShadowJobLifecycleEvent` | Append-only payload-free committed transition fact with database time and fencing coordinates |
| `read-only-shadow-job-lifecycle-page-v1.schema.json` | `ReadOnlyShadowJobLifecyclePage` | Bounded monotonic cursor page of exact-job lifecycle facts |
| `scenario-pack-stage7-v1.fixture.schema.json` | compatibility fixture envelope | One fixed payload-free pack/case/assertion closure and expected independent projection |
| `mirror-state-run-evidence-v1.schema.json` | `MirrorStateRunEvidence` | Exact Session head, model, stateful binding, and payload-free live/absent/tombstone access closure |
| `mirror-state-run-evidence-v2.schema.json` | `MirrorStateTransitionRunEvidence` | Initial/final Session heads, exact read revisions, and payload-free write receipt/event transition closure |
| `mirror-state-run-evidence-v3.schema.json` | `MirrorStateWriteOutcomeRunEvidence` | One terminal outcome per state-write attempt: committed, replayed, rejected, pre-commit failed, or commit outcome unknown |
| `mirror-state-workbook-seed-v1.schema.json` | `MirrorStateWorkbookSeed` | Deterministic payload-free ANEKE seed with exact evidence/state coordinates, counts, and conservative blockers |
| `mirror-state-transition-workbook-seed-v1.schema.json` | `MirrorStateTransitionWorkbookSeed` | Deterministic v4-only ANEKE seed with initial/final heads, committed/replayed receipt assertions, payload-free events, counts, and blockers |
| `mirror-state-write-outcome-workbook-seed-v1.schema.json` | `MirrorStateWriteOutcomeWorkbookSeed` | Deterministic v5-only ANEKE seed with exact attempt outcome/stage/failure identity, optional receipt closure, counts, and conservative blockers |
| `stateful-refund-stage3-v1.fixture.schema.json` | compatibility fixture envelope | Exact state model, write effect, initial session, and executable refund expectation |
| `capability-lifecycle-transition-v1.schema.json` | `CapabilityLifecycleTransitionRequest` | Optimistically fenced governance transition for one exact revision |
| `capability-mirror-compatibility-v1.schema.json` | `CapabilityMirrorCompatibility` | Minimum protocol/object/feature baseline a mirror consumer can negotiate |

`capability-mirror-stage0-v1.fixture.json` is the authoritative Stage 0 compatibility fixture. The
server capability test and standalone test-kit both consume this exact file, preventing either side
from passing against a separately maintained expectation.

Read-only Shadow authority signatures use canonical padded Base64 and distinct signature domains.
Verification keys are independent local trust inputs delegated to one exact enterprise scope and
one publication type. A retired key verifies only signatures created strictly before its recorded
retirement; a revoked key verifies none. Sampling-grant consumers must independently verify the
referenced current guard-policy publication and preserve both attestations.

Authority key-set consumers persist the pair `throughGeneration +
publicationFingerprint` only after independently verifying every contiguous
successor. A page freezes one repository high-water; its complete
`highWaterPublication` is mandatory whenever the stream is non-empty. Terminal
and empty catch-up pages must re-verify that head at the consumer's current
trusted time. `highWaterGeneration` is therefore not, by itself, a trust
statement or a valid revocation cursor.

`mirror-evidence-stage1-v1.fixture.json` is the fixed Stage 1 cryptographic compatibility fixture.
It contains a server-produced `HASH_ONLY` bundle and public Ed25519 key, but no private key or
business payload. The server rehydrates and verifies it through its Java protocol model; the
standalone test-kit validates the strict schemas and independently re-derives every nested seal,
closure, aggregate fingerprint, key policy, and signature from the same file.

The producer emits v2 for stateless runs, v3 for read-only Session runs, and v5
for new Session runs containing virtual writes. V4 remains the compatible
successful-transition generation. Readers and the standalone test-kit
continue to verify v1, including legacy v1 certifiable evidence without a
run-trust binding. A portable bundle must use one generation throughout;
v1/v2/v3/v4/v5 mixing is rejected. V2/v3/v4/v5 certifiable
evidence carries `resourceGateway.mirrorDeploymentIsolationRunTrust.v1`, while a v2 exploratory
or stateful exploratory run without deployment proof omits that field and preserves the explicit
`DEPLOYMENT_EGRESS_NOT_ATTESTED` limitation. See
[Mirror runtime trust binding](../../resource-gateway-mirror-runtime-trust-binding.md).

`mirror-deployment-isolation-stage1-v1.fixture.json` is a second fixed Stage 1 cryptographic
fixture. It contains one short-lived deployment-isolation attestation, the external authority's
public key, immutable expected deployment coordinates, and a covered execution window. It contains
neither a private key nor business payload. Both the producer implementation and the standalone
test-kit verify this exact file.

`mirror-deployment-isolation-authority-key-set-stage1-v1.fixture.json` is the fixed public-only
trusted-publication fixture. It contains one generation-one publication, two independent
bootstrap-root public keys, exact local binding policy, and a deterministic verification time. It
contains no private key or business payload. Both implementations re-derive the material and
publication fingerprints, verify both Ed25519 signatures, and expose the advertised attestation
key only after every local binding and policy check succeeds.

`capability-observation-stage2-v1.fixture.json` is the fixed public-only Stage 2 observation
fixture. It contains a signed payload-free support-refund invocation, exact sanitized-payload,
sanitization-proof, schema, grant, trace, state, and outcome references, the producer public key,
local expected scope, and a deterministic verification instant. It contains no private key,
request/response value, business key, secret, stack trace, or provider message. The server schemas
and standalone test-kit independently re-derive both fingerprints and verify the Ed25519
signature. Payload-vault existence and sanitization-proof validity remain separate admission
authorities and are not implied by this fixture.
The admission order, operator-owned SPI contract, quarantine/unavailable split, stable errors and
runbook are documented in
[Capability Observation admission](../../resource-gateway-capability-observation-admission.md).

`capability-corpus-stage2-v1.fixture.json` is the fixed payload-free Stage 2 governance fixture. It
contains one quarantine review command/fact, one admitted-source candidate command/revision, one
owner-reviewed publication command/fact, local expected full scope, and a deterministic verification
instant. Both producer and standalone test-kit re-derive all six canonical fingerprints, exact
command-to-fact bindings, source ordering, lineage, policy-independent risk statistics and time
horizons. The fixture does not prove live payload/proof existence, current policy, actor
authorization, current serving-head status or resolver readiness. See
[Capability Corpus governance](../../resource-gateway-capability-corpus-governance.md).

`fixture-mirror-corpus-bindings-v1.fixture.json` is the fixed payload-free serving-selection
fixture. It contains canonically ordered exact capability/publication references and no request or
response values. The server parser and standalone `FixtureMirrorCorpusBindingsVerifier` both reject
unknown fields, wrong artifact kinds, duplicate capability/publication coordinates, and
non-canonical capability order. A successful offline result proves only structural compatibility;
latest-head status, current policy, grant, retention, tombstone, payload bytes, and runtime
readiness are online authority decisions.

The trajectory command and publication schemas freeze an explicit owner-reviewed sequence rather
than allowing consumers to infer retries from observation proximity. The independent
`CapabilityCorpusTrajectoryVerifier` recomputes command/publication and referenced corpus
content addresses, exact source membership, consecutive numbering, common request fingerprint,
lineage, and horizons. Current retry policy, retryable outcomes, trace ordering, grants,
retention, tombstones, and payload bytes remain online authority decisions.

`capability-corpus-cluster-stage2-v1.fixture.json` is the fixed payload-free recorded-cluster
compatibility fixture. It closes one exact corpus revision/publication, external validation,
owner command, and immutable cluster publication. The server producer and independent
`CapabilityCorpusClusterVerifier` re-derive all content addresses, exact corpus/command/validation
lineage, member and representative membership, common response Schema, JSON Pointer topology,
holdout counts, and the 95% Wilson precision interval. A successful offline result deliberately
retains limitations for current cluster policy, validation authority, grant/retention, source
lifecycle, and payload authority. It proves that a publication is structurally safe to consider;
it does not prove that current online authorities permit materialization.

`fixture-mirror-trajectory-bindings-v1.fixture.json` is the fixed payload-free trajectory
selection fixture. The server parser cross-checks every capability and corpus publication against
the same fixture's `mirrorCorpus` selection before online materialization. The independent
`FixtureMirrorTrajectoryBindingsVerifier` proves strict schema closure, canonical
capability/trajectory order, and unique exact trajectory coordinates. It intentionally cannot see
the sibling `mirrorCorpus` object or prove current heads, policies, grants, source lifecycle,
payload authority, or node retry capacity. Those remain fail-closed server checks. A successfully
materialized trajectory is consumed by the real BLOGE one-based retry loop through
`RECORDED_TRAJECTORY`; protocol support alone never implies dynamic readiness. The producer parser
does not normalize invalid wire values: artifact kind is case-sensitive, identifiers must already
match the bounded wire pattern, and revisions must be positive integers within signed 64-bit range.

`fixture-mirror-cluster-bindings-v1.fixture.json` is the fixed payload-free cluster selection
fixture. The server parser cross-checks every capability and corpus publication against the same
fixture's `mirrorCorpus` selection. The independent `FixtureMirrorClusterBindingsVerifier` can
perform the same cross-check when given both nested objects, while still refusing to claim current
cluster/corpus heads, policy, validation, grant, lifecycle, retention, payload, or runtime
readiness.

`stateful-refund-stage3-v1.fixture.json` is the fixed Stage 3 payload-bearing compatibility
fixture. It freezes one exact `order`/`refund` state model, one `query-order` state-read lowering,
one two-entity atomic `create-refund` write effect, a sealed initial session, and read/write
expectations. The fixture is
test data and contains no credential or production customer payload. The server rehydrates the
typed records and verifies every nested/top-level fingerprint. The independent
`MirrorStateProtocolVerifier` applies the strict Schemas, exact model/effect/session closure,
bounded expressions, mutation-alias admission, entity/tombstone/business-key seals, contiguous
revision/receipt/event closure, response fingerprints, and the latest resulting-world binding
without linking server or Spring classes. The test-kit also seals aggregate fingerprints and drives
the protected create/read/command/destroy API while validating both sides of transport. This proves
protocol and current test/staging data-plane compatibility. The server now
executes state-model-backed `READ_ONLY` sites through an exact Session head and
`VIRTUAL_MUTATION` sites through a serialized run session. Committed writes
advance the head observed by downstream reads, tombstones terminate precedence,
and no real external write operator is invoked. A read-only execution emits
`mirrorStateRunEvidence.v1` inside a v3 bundle; a read/write execution emits
`mirrorStateRunEvidence.v3` inside a v5 bundle. V3 keeps the complete v2
receipt/event closure for committed and replayed writes and adds one exact
terminal attempt for rejected, proven pre-commit-failed, and
commit-outcome-unknown writes. The independent `MirrorEvidenceVerifier` proves
exact access/write-attempt/resolution/failure-fingerprint/receipt/event closure.
`MirrorStateWorkbookSeed.fromVerifiedBundle` derives a payload-free ANEKE seed
for v3 read-only evidence. `MirrorStateTransitionWorkbookSeed.fromVerifiedBundle`
independently verifies v4 and projects exact committed/replayed receipts and
payload-free event assertions. `MirrorStateWriteOutcomeWorkbookSeed.fromVerifiedBundle`
independently verifies v5, exposes all five outcome classes and the last
trustworthy write stage, and blocks unknown commit outcomes pending
reconciliation. Both test client methods compare the producer seed with a
locally reconstructed canonical fingerprint. The fixture itself does not contain
a fixed v3, v4, v5, or checkpoint signature vector and therefore does not certify
non-Java stateful canonicalization. The separately tested checkpoint protocol
proves signed exact recovery admission after a process restart against the same
encrypted data-plane generation; it does not move or restore Session payload.
Neither fixture nor checkpoint protocol proves TEE/KMS custody, cross-region
payload HA/DR, retention certification, or production readiness.
See the
[Stateful Mirror kernel guide](../../resource-gateway-stateful-mirror-kernel.md).

The Scenario protocol is an orchestration layer over existing testing assets,
not a second test model. `ScenarioCase` carries no business input or expected
response; it freezes the exact `TEST_SUITE` case and `FIXTURE_BUNDLE` that own
those values. It also freezes one exact `MIRROR_PLAN`, logical clock/random
services, optional `MIRROR_SESSION_CHECKPOINT`, explicit fixture fault-rule ids,
and payload-free handling assertions. Generation one is deliberately
sequential, requires isolated case Sessions, forbids real calls, credentials,
and network egress, and exports only `HASH_ONLY` evidence. The standalone
`ScenarioPackVerifier` applies all three packaged strict Schemas, re-derives
every content address, resolves the exact case/assertion closure, and rejects
scope/target drift, per-plan execution-service drift, shared checkpoints,
implicit faults, invalid assertion selectors, stale approval, and extra
artifacts without linking Resource Gateway server code.

`scenario-pack-stage7-v1.fixture.json` is the fixed E7 cross-implementation
compatibility vector. It contains one refund pack, one case that points to an
existing test-suite coordinate, one output-schema handling assertion, an
explicit verification instant, and the expected payload-free projection. It
contains no TestSuite input, FixtureBundle payload, Session state, request,
response, or credential. `CapabilityMirrorProtocol.scenarioPackCompatibilityFixture`
validates the envelope and all referenced Schemas, re-runs the independent
closure verifier, and compares every projected field before exposing a detached
copy.

Completed Scenario aggregate evidence has a separate retention lifecycle.
Revision one registers the exact evidence bundle fingerprint and immutable
minimum retention boundary in the same local transaction as terminal evidence.
Later `HOLD_PLACED`, `HOLD_RELEASED`, and `PURGED` events form a signed,
previous-fingerprint-linked chain under complete enterprise scope. Holds are
independent: releasing one never releases another. A purge event preserves the
deleted aggregate fingerprint and deleted progress-row count and records child
Mirror evidence as `RETAINED`; it carries no deleted payload.

`ScenarioRehearsalRetentionVerifier` applies the strict state/event Schemas,
re-derives the latest canonical event fingerprint, checks projection closure,
enforces signing-time key policy, and verifies the Ed25519 seal without server
classes. This proves the current projection and latest deletion proof. The
server additionally replays the complete retained event chain on every read;
external full-history export, WORM storage, and transparency anchoring are
deployment capabilities rather than claims of this v1 wire contract.

## Deployment isolation attestation boundary

`resourceGateway.mirrorDeploymentIsolationAttestation.v1` turns the previous untyped
`deploymentIsolationRef` into a verifiable artifact protocol. Its signed material binds:

- an exact deployment scope, cluster, namespace, workload, service account, and immutable image
  digest;
- an ordered set of out-of-process enforcement layers;
- mandatory fail-closed, default-deny egress, external-business-egress denial, production
  credential denial, production identity denial, and continuous-enforcement facts;
- exact network-policy, credential-policy, and destination-allowlist fingerprints;
- a bounded allowlist of non-business egress classes and payload-free
  `DEPLOYMENT_POLICY_PROOF` references;
- observation, effective, expiry, issuer, and immutable attestation revision coordinates.

The validity interval is at most 15 minutes, issuance may lag observation by at most 5 minutes,
and a mirror execution must fit wholly inside
`[max(validFrom, signedAt), expiresAt)`. The signature domain is
`RESOURCE_GATEWAY_MIRROR_DEPLOYMENT_ISOLATION_V1`; Ed25519 signs the lowercase canonical SHA-256
material fingerprint. Timestamps must use canonical UTC `Instant` text, Base64 values must use
their unique padded encoding, and protocol collections must already be in canonical order. Policy proof
references are unique by `(kind, id, revision)`; a second fingerprint at the same coordinate is a conflict,
not another proof. Consumers must reject rather than silently normalize alternate wire forms. The complete artifact has a second
content fingerprint. Consumers must use
an independently distributed SRE/security authority key whose key id, issuer, algorithm, signing
window, and lifecycle state all match. The Resource Gateway evidence-signing key is deliberately
not an isolation authority.

The companion `resourceGateway.mirrorDeploymentIsolationAuthorityKeySetPublication.v1` protocol
removes static-map and caller-upload trust from authority-key distribution. One publication binds
the full enterprise scope, exact deployment, expected attestation issuer, stable key-set stream,
bootstrap-root trust domain, exact M-of-N threshold, policy generation, at-most-24-hour window, and
monotonic generation/predecessor chain. Authority keys and root signatures use canonical order and
unique coordinates. At least one active attestation key covers the publication window. Every
supplied root signature, not merely a threshold-sized subset, must be locally pinned, lifecycle
allowed, valid at signing time, and cryptographically correct. Local expected binding and the last
durably accepted floor are not read from the publication. Bootstrap requires generation one;
successors must advance exactly one generation from the floor and name its fingerprint. Idempotent
reverification is allowed, while rollback, fork, skipped generation, and predecessor mismatch fail
closed.

These increments freeze both externally signed artifacts, strict Schemas, producer integrity kernels,
independent test-kit verifiers, and shared signed compatibility fixtures. Authority publications now have a
full-scope append-only repository, protected publish/latest/current API, operator-owned local trust
SPI, and atomic durable-floor CAS. Current-only reads re-verify local binding, roots, validity, and
floor; historical generations are not served as trusted distribution. Attestations now have separate
append-only body and status logs, a CAS current head, operator-owned bootstrap-revision policy, and
protected ingest/current/exact-current/revoke APIs. Ingest atomically commits body, initial `ACTIVE`
status, head, and mandatory audit. An attestation revision can only move once to `REVOKED`; denial
distribution deliberately bypasses positive authority availability, while every active read re-verifies
the same current authority generation, key lifecycle, deployment identity, signature, and time window.
The deployment agent now pulls these artifacts through private-PKI, SPKI-pinned, identity-bound
mTLS, enforces a separately provisioned bootstrap floor and contiguous successors, and atomically
replaces one durable read-only snapshot. Valid revocation does not depend on positive authority
availability, while an old active snapshot is usable only until its local hard deadline. These
controls do **not** yet provide execution-admission/evidence-projector binding. Current mirror runs therefore remain
`EXPLORATORY` with `DEPLOYMENT_EGRESS_NOT_ATTESTED`; protocol availability alone must not produce
`CERTIFIABLE` evidence.

The cache contract, non-TOFU provisioning, refresh/expiry SLO, filesystem guarantees, health states,
and recovery procedures are specified in the
[deployment-agent guide](../../resource-gateway-mirror-deployment-agent.md).

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

Plans without recorded corpus continue to serialize as `resourceGateway.mirrorPlan.v1` and omit
`servingGeneration`. Any plan selecting `RECORDED_EXACT`, `RECORDED_TRAJECTORY`, or
`RECORDED_CLUSTER` must serialize as `resourceGateway.mirrorPlan.v2` and carry one independently
verified `resourceGateway.mirrorServingGenerationToken.v1`. The token binds the payload-free
materialized dependency closure, scope, purpose, generation, predecessor, revocation cursor,
expiry, and maximum floor-cache staleness. The protected API envelope uses the returned plan's
actual schema version. See the
[serving-generation guide](../../resource-gateway-mirror-serving-generation.md).

`maximumInvocations` is enforced twice. Compilation rejects a value below the complete recursive
static inventory with `RG.MIRROR.INVOCATION_BUDGET_TOO_SMALL`. Execution then atomically consumes
one unit before each actual operator occurrence is resolved. The same resolver is inherited by
root nodes, nested graphs, foreach and loop re-entry, streaming nodes, and compensation. Retry
attempts remain inside one occurrence and do not consume another unit. Parallel branches cannot
oversubscribe the limit. Exhaustion stops the next occurrence before fixture binding or operator
execution, produces terminal `EXECUTION_FAILED` evidence, and adds the stable payload-free
`INVOCATION_BUDGET_EXHAUSTED` limitation. It is therefore an evidenced business-run failure, not a
transport-level retry signal. Already admitted concurrent work may complete, but no later
occurrence is admitted.

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
profile; any active `production` profile physically removes all mappings:

| Method and path | Response | Semantics |
|---|---|---|
| `POST /api/mirror/executions` | `resourceGateway.mirrorRunSummary.v1` | Execute once or return an identical completed request |
| `GET /api/mirror/runs/{runId}` | `resourceGateway.mirrorRunSummary.v1` | Read a verified payload-free terminal projection |
| `GET /api/mirror/runs/{runId}/evidence` | `resourceGateway.mirrorEvidenceBundle.v1/v2/v3/v4/v5` | Read independently verified signed `HASH_ONLY` evidence using the bundle's actual generation |
| `GET /api/mirror/runs/{runId}/state-workbook-seed` | `resourceGateway.mirrorStateWorkbookSeed.v1` | Derive a deterministic payload-free ANEKE seed from a verified stateful v3 bundle; reject stateless runs |
| `GET /api/mirror/runs/{runId}/state-transition-workbook-seed` | `resourceGateway.mirrorStateTransitionWorkbookSeed.v1` | Derive deterministic payload-free write assertions from a verified v4 bundle; reject every other generation |
| `GET /api/mirror/runs/{runId}/state-write-outcome-workbook-seed` | `resourceGateway.mirrorStateWriteOutcomeWorkbookSeed.v1` | Derive deterministic terminal write-attempt assertions from verified v5 evidence; reject every other generation |

Stateless `POST /api/mirror/executions` v1 accepts exactly `schemaVersion`, `requestId`, `planId`,
`expectedPlanFingerprint`, and `context`; stateful v2 additionally requires one exact
`sessionBinding` containing only Session id and expected state fingerprint. Neither accepts scope, purpose, fixture/replay references, resolver
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

Every protected operation also commits a payload-free terminal audit before returning. Plan creation and Run
evidence/request completion share their successful audit transaction; failed operations use an independent
transaction so the audit survives the rollback it explains. Audit construction or persistence failure returns
retryable `503 RG.MIRROR.OPERATION_AUDIT_UNAVAILABLE` and suppresses the protected result. The audit stores only
scope/trace coordinates, closed outcome dimensions, stable resource ids, stable `RG.MIRROR.*` reason code, and
duration. Request context, fixture/replay values, trace values, exception messages, and stacks are not representable.
The capability probe reports `mirrorOperationObservability=true` only with the isolated Plan and Run adapters.

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
| 503 | `RG.MIRROR.OPERATION_AUDIT_UNAVAILABLE` | Yes | Mandatory terminal audit failed, so no protected result was published |

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
- An isolation-authority key-set publication is accepted only when its strict structure, both
  canonical fingerprints, exact full-scope/deployment/issuer/key-set/trust-domain/policy binding,
  local threshold, validity window, monotonic trusted floor, distinct root public-key material,
  and every independent root signature verify. Unknown or revoked extra signatures are fatal; a publication cannot lower its own
  threshold or choose its own trust floor. Verified public keys remain scoped to that exact
  publication generation.
- A deployment-isolation attestation is accepted only when its strict structure, deterministic
  ordering, domain-separated material fingerprint, complete artifact fingerprint, external
  Ed25519 authority, exact local deployment generation, and complete execution interval all
  verify. Every deny control is mandatory; mutable image tags and non-policy proof references are
  illegal. A missing, revoked, wrong-issuer, wrong-key, stale, future, expired, or identity-drifted
  artifact fails closed.
- Revision one must be `DRAFT`; later revisions are contiguous, append-only, and accepted only through the
  lifecycle transition matrix. `REVOKED` is terminal.
- A recorded-cluster publication binds one exact current corpus and externally verified support
  set. `IDENTITY_FREE_RESPONSE` permits no identity projection; `REQUEST_PROJECTION` requires
  owner-approved request-to-response JSON Pointer mappings whose response paths are globally
  disjoint and non-overlapping. Holdout precision is recomputed from counts with
  `WILSON_PRECISION_95_V1`; low point estimates, lower bounds, support, identity diversity, or
  false-positive thresholds cannot be waived by the command. Runtime serving additionally requires
  an exact fixture binding and online revalidation of every mutable authority and content address.

## Independent client admission

The test-kit packages the Stage 0 schemas, protected plan/execution commands, payload-free run
summary, Stage 1 evidence, state evidence/workbook seed, deployment-isolation schemas, and the shared observation, corpus,
trajectory, cluster, and binding compatibility fixtures in its JAR. A Stage 0 consumer first
calls `CapabilityMirrorCompatibility.assess(capabilityPayload)` and requires a compatible result.
It then calls `CapabilityMirrorVerifier.verifySnapshot(value)` or `verifyClosure(value)` before
persisting or compiling the artifact. A mirror evidence consumer resolves the attestation key id and calls
`MirrorEvidenceVerifier.verify(bundle, key)` before accepting a run into a correctness workbook or release gate.
For a verified v3 bundle it may then call
`MirrorStateWorkbookSeed.fromVerifiedBundle(bundle, key)` to reconstruct the deterministic payload-free
ANEKE seed locally. A producer-supplied seed validated only through `fromPayload` is not a substitute for source
bundle verification.
Before treating an isolation reference as certification evidence, a consumer separately calls
`MirrorDeploymentIsolationAttestationVerifier.verify(attestation, authorityKey,
expectedDeployment, executionStartedAt, executionCompletedAt)` with local immutable deployment
coordinates and an externally pinned isolation-authority key.

The verifiers do not deserialize server Java models. They validate wire JSON and independently re-derive canonical
SHA-256 material. Mirror evidence verification additionally proves trace ordering, external-attempt/resolution
closure, nested resolution seals, evidence and bundle fingerprints, signing time, key policy, and the
domain-separated Ed25519 signature. V3 additionally proves nested state self-sealing, exact Session/model
coordinates, deterministic stateful binding/access order, and state access/node attempt/resolution closure.
Deployment-isolation verification additionally proves exact
runtime identity and full-window coverage. Results contain only bounded reason codes, ids, and fingerprints. Stable
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
| `GET /api/mirror/runs/{runId}/state-workbook-seed` | Derive a deterministic payload-free ANEKE seed from one verified stateful v3 bundle | `MIRROR_REHEARSAL` |
| `GET /api/mirror/runs/{runId}/state-transition-workbook-seed` | Derive committed/replayed write assertions from one verified stateful v4 bundle | `MIRROR_REHEARSAL` |
| `GET /api/mirror/runs/{runId}/state-write-outcome-workbook-seed` | Derive failure-aware write-attempt assertions from one verified stateful v5 bundle | `MIRROR_REHEARSAL` |
| `GET /api/mirror/rehearsal-jobs/{jobId}/workbook-seed` | Read one root-sealed, payload-free Scenario batch correctness projection without fetching every child | `MIRROR_REHEARSAL` or `GOVERNANCE_EVIDENCE_INGESTION` |
| `GET /api/mirror/rehearsal-jobs` | List newest payload-free jobs in exact authenticated scope using `beforeCreatedAt` plus `beforeJobId` keyset pagination | `MIRROR_REHEARSAL` or `GOVERNANCE_EVIDENCE_INGESTION` |
| `POST /api/mirror/sessions` | Create or exactly replay one sealed encrypted Session | `MIRROR_REHEARSAL` |
| `GET /api/mirror/sessions/{sessionId}` | Read the current payload-free Session descriptor | `MIRROR_REHEARSAL` |
| `POST /api/mirror/sessions/{sessionId}/commands` | Execute or exactly replay one admitted virtual write effect | `MIRROR_REHEARSAL` |
| `GET /api/mirror/sessions/{sessionId}/write-attempts/{attemptId}` | Read and independently verify one payload-free durable write outcome | `MIRROR_REHEARSAL` |
| `POST /api/mirror/sessions/{sessionId}/checkpoints` | Sign one payload-free exact store-generation and Session-head checkpoint | `MIRROR_REHEARSAL` |
| `POST /api/mirror/sessions/{sessionId}/recoveries` | Verify a checkpoint against the current transactional snapshot and return an exact run binding | `MIRROR_REHEARSAL` |
| `DELETE /api/mirror/sessions/{sessionId}` | Irreversibly clear encrypted payload and return the terminal descriptor | `MIRROR_REHEARSAL` |
| `POST /api/mirror/trust/deployment-isolation/authority-key-sets` | Verify local trust and append one generation plus durable floor | `MIRROR_TRUST_ADMIN` |
| `GET /api/mirror/trust/deployment-isolation/authority-key-sets/{keySetId}/latest` | Re-verify and read the current floor | `MIRROR_TRUST_DISTRIBUTION` or `MIRROR_REHEARSAL` |
| `GET /api/mirror/trust/deployment-isolation/authority-key-sets/{keySetId}/generations/{generation}` | Read an exact address only while it remains current | `MIRROR_TRUST_DISTRIBUTION` or `MIRROR_REHEARSAL` |
| `POST /api/mirror/trust/read-only-shadow/authority-key-sets` | Root-verify and atomically append one scope-bound authority key-set successor | `MIRROR_TRUST_ADMIN` |
| `GET /api/mirror/trust/read-only-shadow/authority-key-sets/pages` | Read a frozen contiguous cursor page for one authenticated Shadow authority stream | `MIRROR_TRUST_DISTRIBUTION` or `MIRROR_SHADOW` |
| `POST /api/mirror/trust/deployment-isolation/attestations` | Verify current authority and atomically append one external proof plus active status | `MIRROR_TRUST_ADMIN` |
| `GET /api/mirror/trust/deployment-isolation/attestations/{attestationId}/latest` | Re-verify and read the atomic current active/revoked bundle | `MIRROR_TRUST_DISTRIBUTION` or `MIRROR_REHEARSAL` |
| `GET /api/mirror/trust/deployment-isolation/attestations/{attestationId}/revisions/{revision}` | Read exact coordinates only while they remain the current head | `MIRROR_TRUST_DISTRIBUTION` or `MIRROR_REHEARSAL` |
| `POST /api/mirror/trust/deployment-isolation/attestations/{attestationId}/revocations` | Apply one exact-current irreversible revocation | `MIRROR_TRUST_ADMIN` |
| `POST /api/mirror/observations` | Admit or quarantine one signed payload-free observation and return an atomic receipt | `MIRROR_CORPUS_INGESTION` |
| `POST /api/mirror/observations/{observationId}/reviews` | Append one terminal review without changing the quarantine admission | `MIRROR_CORPUS_GOVERNANCE` |
| `POST /api/mirror/corpus-candidates` | Freeze ordered admitted sources into a non-serving candidate revision | `MIRROR_CORPUS_GOVERNANCE` |
| `POST /api/mirror/corpus-publications` | Publish one current eligible candidate after owner and source rechecks | `MIRROR_CORPUS_GOVERNANCE` |
| `POST /api/mirror/corpus-trajectories` | Publish one explicit owner-reviewed retry trajectory | `MIRROR_CORPUS_GOVERNANCE` |
| `POST /api/mirror/corpus-clusters` | Publish one externally validated, owner-reviewed recorded cluster | `MIRROR_CORPUS_GOVERNANCE` |

Session routes are physically present only when the active profile is `test` or `staging` and both
`gateway.testing.mirror.enabled` and `gateway.testing.mirror.stateful.enabled` are true.
Authentication and purpose authorization happen before raw JSON decoding. Scope is always derived
from the verified workload identity; no Session command contains a tenant selector. The encrypted
payload lives in a dedicated JDBC data plane, while descriptors expose only dependency, lifecycle,
revision, time, and fingerprint facts. A state-plane outage, key failure, corrupt ciphertext,
lease conflict, or CAS conflict fails closed and never falls through to a real resource.
Checkpoint creation also requires a healthy signing authority. The bundle contains no payload,
lease, fence, payload-encryption key id, or key material. Recovery re-verifies its signature and
exact scope/store-generation/dependency/state closure against one transactional current snapshot;
it never imports the checkpoint as state or rolls the Session head backward.

All authority/attestation GET routes additionally require the exact
`application/vnd.bloge.mirror-deployment-isolation-trust.v1+json` media type and
`X-BLOGE-Mirror-Trust-Protocol: mirror-deployment-isolation-trust-v1`. This separates deployment
agent distribution from generic JSON callers before controller authentication and lookup.

All endpoints derive scope, actor, and clearance from the verified workload identity. Absent,
cross-scope, and above-clearance reads deliberately share `404 RG.MIRROR.SNAPSHOT_NOT_FOUND` so the API does
not become an asset-existence oracle.

The Stage 0 baseline verifies all seven shipped resource graphs plus all three frontend visual examples. The
MirrorPlan protocol increment adds nine semantic integrity cases and extends the strict protocol-field test. Its
focused protocol and probe suite passes 32 tests with no failures, errors, or skips. After adding the Stage 1
compiler, internal mirror runtime kernel, MirrorResolution protocol, governed observation admission, and governed
corpus publication/runtime increments, the latest complete Resource Gateway gate passes 4893 tests with no failures or
errors and 35 conditional skips, and successfully rebuilds the executable Spring Boot JAR. The independent test-kit
gate passes 320 tests with no failures, errors, or skips, packages all 72 mirror protocol resources, and rebuilds its
ordinary/shaded JAR plus public Javadocs.

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
plan/evidence storage, request-id coordination with epoch fencing, atomic terminal commit, dynamic occurrence
budgeting, independent test-kit verification, and deployment-trust admission/confirmation/evidence commit binding
are complete. Pre-MVC ingress controls, non-Java v2 fixtures, cross-language numeric canonicalization, and
customer-environment certification remain open production gates.
