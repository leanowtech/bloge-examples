# Resource Gateway Test Kit

`bloge-resource-gateway-test-kit` lets Java and JUnit 5 suites drive the
Resource Gateway testing control plane without depending on its Spring Boot
implementation.

It turns a DSL-native graph into a repeatable engineering test workflow:

```text
discover exact target
  -> control runtime data with fixtures
  -> execute an immutable suite
  -> assert business behavior
  -> verify signed evidence
  -> feed CI or a governance gate
```

> **New to the Test Kit?** Start with the
> [整体设计与使用手册](../docs/resource-gateway-test-kit-design-and-user-guide.md).
> It explains the architecture, trust boundaries, five-minute Java path, CI command,
> failure decisions, and which advanced verifier to use. The reference below is intentionally
> exhaustive and is best used after the main path works.

## Start Here

| Goal | Start with |
|---|---|
| Run one Graph or Operator locally | `ResourceGatewayTestClient` + inline `FixtureBundleBuilder` |
| Build a repeatable regression suite | immutable Fixture revision + `TestSuiteBuilder` |
| Gate a release in CI | `ResourceGatewaySuiteCli` + payload-free JUnit XML |
| Verify release-grade evidence | exact Suite revision + caller-pinned trust material |
| Integrate Mirror, Shadow, Scenario, or Fidelity governance | the specialized verifier named in the [capability map](../docs/resource-gateway-test-kit-design-and-user-guide.md#11-高级能力导航) |
| Validate Business Mirror Package, Proposal, binding, Conformance, or Legacy Graph files offline | `BusinessMirrorProtocol` + packaged compatibility fixtures |
| Verify Outcome source pages, commands, and checkpoints offline | `AuthoritativeOutcomeSourceProtocolVerifier` + caller-owned Source trust callback |
| Verify a self-contained HA/failure certification package | `RuntimeCertificationVerifier.requireReplayBundle` + caller-owned regional, approval, and evidence trust callbacks |
| Verify ANEKE Package registry and governance exchange | `PackageGovernanceProtocol` + caller-owned ANEKE projection trust callback |
| Verify a Business Mirror pilot acceptance package | `BusinessMirrorPilotAcceptanceProtocol` + packaged cancellation-fee reference fixture |

Build the library and start the local test-profile Gateway:

```bash
mvn -f resource-gateway-test-kit/pom.xml clean verify
./scripts/start-visual-canvas-demo.sh
```

The local demo uses `http://localhost:8080` and the test-only bearer token
`bloge-aneke-demo-token`. Do not reuse that credential outside the local demo.

## Verify Capability Studio Acceptance Artifacts

`CapabilityStudioAcceptanceVerifier` is the bounded offline verifier for the
Capability Studio acceptance baseline and golden-path manifest. The test-kit JAR
packages the two authoritative schemas under
`/schemas/resource-gateway-capability-studio/`; consumers do not need the Spring
Boot gateway to validate an exported artifact.

```java
CapabilityStudioAcceptanceVerifier verifier =
        new CapabilityStudioAcceptanceVerifier();
CapabilityStudioAcceptanceVerifier.VerificationResult result =
        verifier.verifyGoldenPathAcceptanceManifest(manifestJson);
if (!result.verified()) {
    throw new IllegalStateException(result.errorCode());
}
```

The baseline entry point is `verifyAcceptanceBaseline(JsonNode)`. A result is
valid when its schema and semantic checks both pass; `NO_GO` and `PENDING` are
truthful, valid artifact states and are not converted into acceptance. The
verifier enforces exact `GP-01` through `GP-10` coverage, exact `CASE-01` through
`CASE-09` coverage with the committed canonical case-type mapping, truthful
aggregate acceptance, a recursively key-sorted content fingerprint with the artifact's
own fingerprint normalized to `null`, and observed `realExternalCallCount == 0` before any
`APPROVED`/`ACCEPTED` result is accepted. Aggregate acceptance also requires
non-empty evidence references for every GP/scenario and quality result, an
observed egress timestamp, and bound sign-off identity/time/signature fields.
A schema failure returns `schemaValid=false`, whereas a cross-field gate
rejection returns `schemaValid=true, semanticValid=false`. Payload-like fields
and secrets are rejected, and error codes never include instance values or
validator paths.

## Verify Capability Studio GP-04 Branch Protocol

`CapabilityStudioBranchProtocolVerifier` verifies the cross-system branch save
contract without starting the Gateway. The JAR packages these strict Draft
2020-12 schemas:

- `capability-studio-branch-projection-v1.schema.json`
- `capability-studio-branch-update-request-v1.schema.json`
- `capability-studio-preflight-v1.schema.json`
- `capability-studio-error-v1.schema.json`

The complete save/preflight check is deliberately small:

```java
CapabilityStudioBranchProtocolVerifier verifier =
        new CapabilityStudioBranchProtocolVerifier();
CapabilityStudioBranchProtocolVerifier.VerificationResult result = verifier.verify(
        beforeProjectionJson,
        afterProjectionJson,
        preflightJson);
if (!result.verified()) {
    throw new IllegalStateException(result.errorCode());
}
```

The verifier requires exactly the next revision when branch behavior changes and
the same revision for an idempotent save. It recomputes the content fingerprint
from the branch, canonical baseline, dependency, condition, behavior, and duration,
keeps `canonicalBaselineFingerprint` stable, and binds preflight exactly to the
after projection's `branchId`, `revision`, and `fingerprint`. Preflight must be
`ISOLATED` with zero unresolved dependencies, zero real external calls, and
`fallbackToReal=false`. Same-content idempotent saves must retain the same revision
and fingerprint. Use `verifyBeforeProjection`, `verifyAfterProjection`,
`verifyPreflight`, `verifyUpdateRequest`, or `verifyError` when a pipeline needs
phase-level checks. `FailureKind.SCHEMA` identifies strict-contract failures;
`FailureKind.SEMANTIC` identifies cross-document invariant failures. Results
contain only stable checks and error codes, never payloads or secrets.

## Verify Capability Studio Scenario Dataset

`CapabilityStudioScenarioDatasetVerifier` independently verifies the payload-free
Scenario Dataset projection used by GP-03 and GP-09. It applies the packaged strict
Draft 2020-12 Schema, a 4 MiB wire/canonical limit, recomputes the Dataset content
fingerprint, and checks full enterprise-scope closure, unique Case/Contract/Behavior
references, applicable-contract closure, declared quality counts, and `ACTIVE` readiness.

```java
CapabilityStudioScenarioDatasetVerifier verifier =
        new CapabilityStudioScenarioDatasetVerifier();
CapabilityStudioScenarioDatasetVerifier.VerificationResult result =
        verifier.verify(datasetBytes);
if (!result.verified()) {
    throw new IllegalStateException(result.errorCode());
}
```

The projection carries metadata references and business summaries only. Fixture, Mock,
Replay, and protected payload material remain in their existing authorities and cannot be
embedded in the Dataset document. Errors expose only stable codes and check names.

## Verify Capability Studio Feature Rehearsal

`CapabilityStudioFeatureRehearsalVerifier` verifies the strict v1 wire projection returned by
`GET /api/capability-studio/feature-rehearsal`. It is independent from Spring and Resource
Gateway server classes. The verifier checks the canonical 6-node/5-edge topology, Run/Data Lens
identity, invocation-site edge closure, permission-aware payload handling, observed zero real
external calls, visible payload fingerprints, and the Data Lens content fingerprint.

```java
CapabilityStudioFeatureRehearsalVerifier verifier =
        new CapabilityStudioFeatureRehearsalVerifier();
CapabilityStudioFeatureRehearsalVerifier.VerificationResult result =
        verifier.verify(responseBytes);
if (!result.verified()) {
    throw new IllegalStateException(result.errorCode());
}
```

For `STRUCTURE_ONLY`, payload values must be `null`; source fingerprints remain as opaque
commitments and are included in the independently recomputed Data Lens fingerprint. For
`PAYLOAD_VISIBLE`, the verifier independently recomputes each payload fingerprint. A successful
result proves that the bounded projection is internally consistent and declares
`realExternalCallCount=0`. It does not prove operating-system network denial, enterprise identity
authorization, or that the graph and semantic fingerprints came from a trusted production
Authority. Those remain separate release gates.

`CapabilityStudioFeatureRehearsalBaselineVerifier` verifies the separate payload-free
`DEVELOPMENT_TEST_OWNED` 9 Case × 3 round projection. Its strict Schema fixes the Baseline and
Graph identities, Case order, counts, and wire shape. The semantic verifier then requires 27
unique Run IDs, three stable semantic fingerprints per Case, the expected timeout status only for
the timeout Case, exact Case-to-Oracle assertion binding, all Oracles passing, zero declared real
calls, the exact six operator footprints, and no `WRITE` or `MIXED` side effects.

```java
CapabilityStudioFeatureRehearsalBaselineVerifier verifier =
        new CapabilityStudioFeatureRehearsalBaselineVerifier();
CapabilityStudioFeatureRehearsalBaselineVerifier.VerificationResult result =
        verifier.verify(baselineResponseBytes);
if (!result.verified()) {
    throw new IllegalStateException(result.errorCode());
}
```

Verification confirms internal consistency of the development projection. It intentionally does
not upgrade `DEVELOPMENT_TEST_OWNED` to release acceptance and does not prove governed same-closure
execution, deployment egress denial, environment binding, or Owner sign-off.

## Verify a Stage Acceptance Result

`CapabilityStudioStageAcceptanceResultVerifier` verifies the strict, payload-free Stage Acceptance
Result v1 used by section 13.1 of the Capability Studio evolution plan. The result must contain
exactly one `AC-PRE-01` through `AC-PRE-05`, exact baseline/demo references, environment and
execution identity, matrix, commands, observations, evidence, issues, owner, and sign-offs.
Every precondition carries an exact evidence reference and fingerprint. The verifier enforces the
only valid status meanings: `NOT_RUN`, `BLOCKED`, `FAIL`, `PARTIAL`, and `PASS`; `PARTIAL` is
accepted only for `DEVELOPMENT_LEDGER`, never for `STAGE_EXIT`.

```java
CapabilityStudioStageAcceptanceResultVerifier verifier =
        new CapabilityStudioStageAcceptanceResultVerifier();
CapabilityStudioStageAcceptanceResultVerifier.VerificationResult result =
        verifier.verify(resultJsonBytes);
if (!result.verified()) {
    throw new IllegalStateException(result.errorCode());
}
```

`PASS` is fail-closed: all five preconditions must be `PASS`; the matrix must be non-empty and
fully executed; commands, observations, and evidence must be present; every linked evidence
reference must be `AVAILABLE`; no open P0/P1 or blocker issue may remain; and an `OWNER` sign-off
must match the declared owner with an approval, signature, and timestamp. `NOT_RUN` cannot contain
a completed matrix; `BLOCKED` requires a blocked/not-run precondition; `FAIL` requires a failed
precondition or observation. Verification results contain only stable check names and error codes,
never payloads, paths, or sensitive field values.

## Capability Inventory

The JAR packages the authoritative v1 JSON Schema and provides:

- strict runtime-certification Manifest, single-use Authorization, complete 12-scenario Report,
  and self-contained Replay Bundle Schemas plus server-produced fixtures;
  `RuntimeCertificationVerifier` independently reconstructs all content addresses, signing
  materials, recovery SLOs, zero-write constraints, Regional Data Plane and isolation-reference
  closure without linking Spring or Resource Gateway server classes;

- strict Package Registry Ingest Bundle, ANEKE Governance Projection, joined Governance View and
  Projection Receipt Schemas plus server-produced Bundle/Projection fixtures;
  `PackageGovernanceProtocol` independently verifies Package/Readiness/L0-L3/Evidence/dependency
  closure, content addresses, exact cross-system refs, validity windows and caller-owned projection
  trust, while capability negotiation accepts protocol `1.1.0` and minimum consumer `1.0.0` during
  rolling upgrades;

- strict production Outcome Source page, Backfill/revocation command, and payload-free checkpoint
  Schemas plus server-produced compatibility fixtures; `AuthoritativeOutcomeSourceProtocolVerifier`
  re-derives content addresses and stream/checkpoint closure without Spring or server artifacts,
  while the caller remains responsible for customer key, identity, generation, and revocation trust;

- strict `DomainCapabilityPackageDraft`, `DomainCapabilityPackageSnapshot`,
  `PackageReadinessReport`, `CapabilityProposalDraft`, stored Proposal revision/save receipt/page,
  `CapabilityProposalSnapshot`, payload-free Proposal simulation request/evidence/stored result,
  exact implementation binding, implementation Conformance request/test evidence/report/stored
  result, and
  `BusinessAssetLink` schemas, plus fail-closed Legacy Graph projection/catalog schemas;
  `BusinessMirrorProtocol` validates these files offline and ships complete cancellation-fee
  Package, simulation-only Proposal, exact Proposal save receipt, server-produced Proposal
  simulation result, implementation binding, complete implementation Conformance result, and
  loan-decision migration fixtures without
  linking the Resource Gateway server or Spring Boot;

- a bounded JDK HTTP client for graph/operator target discovery, fixture and immutable-suite
  registries, deterministic property planning/materialization/execution, pure-DSL mutation
  planning/V5 materialization/V6 execution, built-in graph-catalog materialization,
  graph/operator execution, suite execution, and persisted child/aggregate-run lookup;
- a fail-closed `FixtureBundleBuilder` for output-level and transport-level protocol fixtures,
  one-based attempt/occurrence selectors, and bounded deterministic identity/feature-flag controls;
- a dependency-closed `TestSuiteBuilder` with exact target/fixture references and typed semantic
  branch, decision, retry, fallback, timeout, and compensation requirements;
- runtime validation against the packaged Draft 2020-12 schema plus request/response identity binding;
- all capability-mirror Draft 2020-12 schemas, a machine-readable Stage 0 compatibility baseline,
  forward-compatible capability-probe negotiation, and registry-free offline verification of sealed
  `CapabilitySnapshot` and `CapabilityClosure` artifacts, including protected execution-command and
  payload-free run-summary schemas, independent deployment-isolation attestation verification, and
  payload-free corpus review/candidate/publication, trajectory, and recorded-cluster lifecycle
  verification;
- strict stateful-mirror payload/create/descriptor/command/write-attempt/checkpoint/recovery Schemas, a canonical
  payload sealer, payload-free semantic verification, authenticated
  create/read/command/write-attempt/checkpoint/recover/destroy client methods, independent durable-attempt,
  checkpoint signature
  and recovery-closure verification, v3 read/v4 transition evidence verification, deterministic
  v3 read and v4 transition ANEKE workbook-seed projection, and an online client that independently
  reconstructs the v4 seed before accepting the producer projection;
- strict `ScenarioPack.v1`, `ScenarioCase.v1`, and
  `CaseHandlingAssertion.v1` Schemas plus an independent payload-free scenario
  closure verifier, signed aggregate Schemas, and a dependency-light verifier
  that re-derives every assertion/case/result/bundle content address and the
  domain-separated Ed25519 signature; deterministic Scenario correctness-workbook
  Schema, dual-signature/source-closure verifier, and a client method that
  refuses producer-selected gate decisions;
- strict owner registration-command, owner-approved `DomainFidelityInventory.v1`, and signed
  payload-free `DomainFidelityProfile.v1` Schemas plus a server-independent verifier that
  reconstructs every unit denominator, dimension metric, Wilson 95% interval,
  freshness boundary, abstention debt, source composition, limitation, content
  address, and Ed25519 seal without accepting a composite score; signed
  `ReadOnlyShadowComparison.v1/v2/v3` Schemas and an independent verifier that rejects
  paired-request, zero-write, sampling-budget, typed-diff, normalization-policy,
  source-resolution, online-authority closure, content-address, key-policy, and signature drift; durable
  `ReadOnlyShadowJobRequest.v1/v2` / `ReadOnlyShadowJob.v1` Schemas plus an independent verifier
  that reconstructs request/job fingerprints, lifecycle, signed comparison, and exact closure;
  strict unsigned-registration and signed `ReadOnlyShadowSourceBinding.v1` Schemas plus
  `ReadOnlyShadowSourceBindingVerifier`, which independently verifies the nested baseline and
  outer binding addresses, exact v2 job reference, validity window, candidate evidence signature
  and source/plan/capability/request closure, local key policy, and detached Ed25519 seal without
  returning payload; strict signed
  `ReadOnlyShadowSourceResolutionAttestation.v1` Schema plus an independent verifier that
  rechecks the exact comparison-bound proof, stable execution identity, deterministic proof id,
  source-binding/candidate evidence, built-in comparison-policy identity, normalized facts,
  temporal/zero-write closure, key policy, content address, and detached signature;
  strict `OnlineReadOnlyShadowBaselineCommand.v1`,
  `OnlineReadOnlyShadowCandidateCommand.v1`,
  `OnlineReadOnlyShadowBaselineObservation.v1`, and
  `OnlineReadOnlyShadowBaselineCapability.v1` /
  `OnlineReadOnlyShadowCandidateCapability.v1` Schemas plus public resource/version
  constants for regional data-plane integration. Baseline observations also have
  an independent content-address/signature verifier and a server-produced
  public-key compatibility fixture. The candidate command binds a sealed plan to
  the exact verified baseline observation, vault receipt, request-context
  fingerprint, grant, admission, and deadline without carrying business payload;
  strict Shadow authority key-set publication/page Schemas plus an independent
  root-threshold verifier that reconstructs content addresses, exact enterprise
  binding, frozen high-water cursor continuity, online terminal freshness, and
  irreversible retained-key lifecycle across bounded catch-up pages;
- packaged validation and version constants for the payload-free
  `bloge.executionServiceStateSnapshot.v1` durable-resume building block;
- payload-safe typed child/suite-run summaries and JUnit 5 assertions;
- typed v2 child-evidence integrity manifests with v1 migration compatibility;
- signed suite checkpoint/terminal attestations, payload-free evidence-bundle export, verification
  key lookup, and dependency-light offline Ed25519 verification, including schema-admission v3
  evidence with a signed empty business-child closure, bounded-property v4 evidence, and mutation
  v5 score/child-closure re-derivation;
- bounded synchronous suite-stability execution/query, typed payload-free durable-parent progress,
  asynchronous stability-job submit/query/cancel with dual-bounded retry and polling, typed
  stability-evidence re-derivation, exact source-run closure verification, and offline Ed25519
  verification against a caller-owned key-set pin;
- strict signed observation-floor lifecycle paging with independent compact-observation, archive,
  retirement, successor-floor, outer-page, and cross-page checkpoint verification;
- challenge-bound request-index replica proof collection plus an offline exact-inventory rollout
  gate that rejects missing, duplicate, unexpected, stale, mixed-scope/artifact/protocol/mode, or
  cryptographically invalid cohorts against an externally pinned key set;
- occurrence-addressable node, retry-attempt, and edge summaries without payload fields;
- payload-free JUnit XML with deterministic CI exit codes and per-mutant or per-stability-case rows;
- an executable `-cli.jar` that fails closed on suite, coverage, promotion, immutable mutation
  score-policy, stability, or pinned-trust failure.

## Build

From the repository root:

```bash
mvn -f resource-gateway-test-kit/pom.xml clean verify
mvn -f resource-gateway-test-kit/pom.xml install
bash resource-gateway-test-kit/scripts/verify-polyglot-protocols.sh
```

The module is intentionally independent of `resource-gateway-examples`. The
server and client can therefore build and release separately against the
versioned wire schema. The polyglot gate uses only Node.js and the Go standard
library; it performs no package download and independently verifies the same
server-produced public fixture in TypeScript and Go. See the
[polyglot certification boundary](polyglot/README.md).

The fidelity profile protocol and offline verification flow are documented in
the [domain fidelity profile guide](../docs/resource-gateway-domain-fidelity-profile.md).

Regional infrastructure certification can be verified without server or Spring dependencies by
`RegionalDataPlaneCertificationVerifier`. It validates the packaged strict Contract,
Certification, and isolation-bundle v2 Schemas; recomputes every content address; checks exact
Scope/deployment/component/rotation/window/zero-write closure; and delegates only the
customer-owned external seal decision. The complete integration and incident procedure is in the
[regional data-plane certification guide](../docs/resource-gateway-regional-data-plane-certification-guide.md).

Authoritative business outcomes require two independent trust decisions.
`AuthoritativeOutcomeObservationVerifier` first validates the packaged strict
`resourceGateway.authoritativeOutcomeObservation.v1` Schema, derives the
reconciliation from pre-treatment selection, attribution windows, complete
authority watermarks and source facts, recomputes the content address, and
enforces key lifecycle and future-time policy against the signed `attestedAt`
rather than the detached seal timestamp. It checks the local Resource Gateway
Ed25519 seal before invoking the caller-owned external authority callback, so
invalid artifacts cannot amplify customer-ledger traffic:

```java
AuthoritativeOutcomeObservationVerifier.VerificationResult result =
        new AuthoritativeOutcomeObservationVerifier().verify(
                observationJson,
                resourceGatewayVerificationKey,
                candidate -> customerAuthority.verify(candidate));

if (!result.verified()) {
    throw new IllegalStateException(result.reasonCode());
}
```

Every outcome timestamp must use the producer's canonical
`Instant.toString()` representation. Equivalent offset spellings are rejected
because hashing their raw bytes would otherwise diverge after server
deserialization normalizes them.

Never implement the callback as `candidate -> true` outside a bounded test.
It must resolve the exact authority-set revision, every watermark and every
source-record reference through a separately governed customer trust channel.
When the callback is absent, the verifier returns `AUTHORITY_UNAVAILABLE`;
a valid Resource Gateway signature alone cannot establish business truth.
`DomainFidelityProfileVerifier` also recognizes
`OUTCOME_PENDING`, `OUTCOME_CENSORED`, and `OUTCOME_CONFLICTING` as explicit
abstention debt instead of silently converting them to failures.

Run the server-produced public-only fixed vector on every JSON, JDK, crypto
provider, or Test Kit upgrade:

```java
AuthoritativeOutcomeObservationCompatibilityFixture fixture =
        CapabilityMirrorProtocol
                .authoritativeOutcomeObservationCompatibilityFixture();
if (!fixture.verify().verified()) {
    throw new IllegalStateException(
            "authoritative outcome wire drift");
}
```

The fixture's internal permissive authority callback is intentionally bounded
to producer/consumer compatibility. It cannot replace the customer-governed
callback used for live evidence.

Non-Java release consumers should also run:

```bash
bash resource-gateway-test-kit/scripts/verify-polyglot-protocols.sh
```

The TypeScript and Go implementations do not call the Java verifier. They
independently reject canonicalization, authority-identity, reconciliation,
signature, and unknown-field drift against the same public-only fixture.

Resource Gateway now exposes authenticated durable create/query/claim/heartbeat/recovery endpoints
backed by BLOGE suspend state. This test-kit deliberately does not yet expose that broad control
surface as a typed Java client; it packages the checkpoint schema and only the narrow request-index
rollout proof needed by the fleet gate. Direct durable-control consumers should use the authoritative
testing API/schema until a generation-matched typed client is added.

## Capability mirror compatibility and offline verification

Business Mirror consumers can also validate the authoring protocol without a running server. In
addition to Package and Proposal domain objects, the packaged schema registry now includes the
durable Package authoring envelopes:

```java
JsonNode receipt = objectMapper.readTree(receiptJson);
BusinessMirrorProtocol.requirePackageSaveReceipt(receipt);

JsonNode page = objectMapper.readTree(pageJson);
BusinessMirrorProtocol.requirePackagePage(page);

JsonNode stored = receipt.path("result");
BusinessMirrorProtocol.requireStoredPackageDraft(stored);
```

These validators fail closed on unknown fields, malformed exact fingerprints, or a stored draft
whose canonical content no longer matches its fingerprint. Page verification additionally enforces
strict package-id order, one enterprise Scope, and a cursor bound to the last item. The fixed
`cancellation-fee-package-save-receipt-v1.fixture.json` is available through
`BusinessMirrorProtocol.PACKAGE_SAVE_RECEIPT_FIXTURE_RESOURCE`.

Compiled Business Mirror facts have semantic offline verification as well:

```java
BusinessMirrorProtocol.requireBusinessAssetLinkClosure(linkClosure);
BusinessMirrorProtocol.requirePackageReadinessReport(readiness);
BusinessMirrorProtocol.requirePackageSnapshot(snapshot);
BusinessMirrorProtocol.requirePackageCompilationReceipt(compilationReceipt);

BusinessMirrorProtocol.requireLegacyGraphPackageProjection(legacyProjection);
BusinessMirrorProtocol.requireLegacyGraphPackageProjectionCatalog(legacyCatalog);
```

The verifier recomputes all three content addresses. It also rejects dangling/cyclic L0-L3 links,
contradictory readiness status, cross-Scope provenance, mutable authoring artifacts in a Snapshot
manifest, non-deterministic manifest ordering, and receipts whose source/revision/time/fact
references disagree. These checks do not query a Resource Gateway registry and do not expose
Package content in exceptions.

Legacy migration verification additionally proves Graph/Contract/Capability/Closure bindings,
single-Scope provenance closure, exact equivalence between Package readiness blockers and migration
gaps, mandatory owner/Contract/MirrorPlan policy gaps, graph-name ordering, derived status, and the
projection content address. Discovered Contract tests remain evidence only; the verifier rejects a
projection that hides Scenario governance debt. The fixed fixture is exposed as
`BusinessMirrorProtocol.LEGACY_GRAPH_PROJECTION_FIXTURE_RESOURCE`. The end-to-end API flow is in the
[Legacy Graph migration guide](../docs/resource-gateway-business-mirror-legacy-migration-guide.md).

Implementation binding artifacts can be checked without loading Resource Gateway server classes:

```java
BusinessMirrorProtocol.requireImplementationBindingRequest(request);
BusinessMirrorProtocol.requireImplementationBinding(binding);
BusinessMirrorProtocol.requireStoredImplementationBinding(storedBinding);
```

The semantic verifier independently recomputes the binding content address and checks exact artifact
kinds, sorted Region admission, read-only/stateless safety, validity time ordering, and detached-seal
material closure. Cryptographic signer trust remains deployment-owned. The packaged
`refund-implementation-binding-stage1-v1.fixture.json` is exposed as
`BusinessMirrorProtocol.IMPLEMENTATION_BINDING_FIXTURE_RESOURCE`; it contains no request/response
payloads or credentials. See the
[Implementation Binding guide](../docs/resource-gateway-business-mirror-implementation-binding-guide.md)
for runtime adapter and API semantics.

The exact implementation Conformance chain is independently verifiable as well:

```java
BusinessMirrorProtocol.requireImplementationConformanceRequest(request);
BusinessMirrorProtocol.requireImplementationTestEvidence(implementationEvidence);
BusinessMirrorProtocol.requireImplementationConformanceReport(report);
BusinessMirrorProtocol.requireStoredImplementationConformance(storedConformance);
```

The verifier recomputes the embedded implementation evidence, aggregate report, and resulting
Proposal Snapshot content addresses. It also checks complete Suite coverage, deterministic Case
order, derived pass/fail state, target call counts, fixture-to-real behavior fingerprints, exact
binding closure, report evidence reference, and detached-seal material identity. It never accepts a
`CONFORMANT` label on its own. The packaged
`refund-implementation-conformance-stage1-v1.fixture.json` is exposed as
`BusinessMirrorProtocol.IMPLEMENTATION_CONFORMANCE_FIXTURE_RESOURCE`. Cryptographic signer trust and
the correctness of undeclared production semantics remain deployment-owned. See the
[Implementation Conformance guide](../docs/resource-gateway-business-mirror-implementation-conformance-guide.md).

L0-L3 Business Asset impact reports and bounded rebuild receipts can be verified offline:

```java
BusinessMirrorProtocol.requireBusinessAssetImpactReport(impactReport);
BusinessMirrorProtocol.requireBusinessAssetImpactRebuildReport(rebuildReport);
```

The verifier checks canonical fingerprint, exact Snapshot/Closure/source coordinates, complete
ordered impact paths, scope closure, deterministic risk/depth/path-count summaries, Deep Link
coordinates, freshness contradictions, and rebuild count/cursor consistency. The complete
Resource-to-Channel fixture is exposed as
`BusinessMirrorProtocol.BUSINESS_ASSET_IMPACT_FIXTURE_RESOURCE`. See the
[Business Asset Impact guide](../docs/resource-gateway-business-asset-impact-index-guide.md).

Pilot acceptance manifests can be verified without loading Resource Gateway server classes:

```java
BusinessMirrorPilotAcceptanceProtocol.VerifiedPilotAcceptance verified =
        BusinessMirrorPilotAcceptanceProtocol.verify(manifest);
```

The verifier recomputes both the owner-frozen denominator and Manifest content addresses, enforces
the fixed ten-gate order and Authority assignment, checks gate-specific evidence kinds and failed
reason codes, closes Package/denominator/Outcome references, validates causal event time, and
independently derives the overall status. It does not resolve
evidence refs or grant customer, ANEKE, business-owner, or target-environment authority. The
packaged fixture is deliberately `PREPARING / NOT_REQUESTED`; see the
[cancellation-fee pilot acceptance guide](../docs/resource-gateway-cancellation-fee-pilot-acceptance-guide.md).

Mirror consumers should negotiate the server before importing artifacts. Pass the decoded
`/api/integration/capabilities` payload to `CapabilityMirrorCompatibility`; the integration envelope
is not part of this method's input:

```java
JsonNode capabilityPayload = objectMapper.readTree(capabilityResponseBody)
        .path("payload");

CapabilityMirrorCompatibility.Assessment compatibility =
        CapabilityMirrorCompatibility.assess(capabilityPayload);
compatibility.requireCompatible();

String snapshotVersion = compatibility.negotiatedObjectVersions()
        .get("capabilitySnapshot");
boolean planCompilationAvailable = compatibility.deferredFeatures()
        .get("mirrorPlanCompilation");
boolean mirrorServingAvailable = compatibility.deferredFeatures()
        .get("mirrorServing");
```

Required protocol/object versions and feature facts fail closed. Deferred Stage 1 features are
reported but do not make a Stage 0 server incompatible when they are later enabled. Unknown probe
fields and additional object versions are intentionally tolerated. `reasonCodes()` and
`requireCompatible()` contain only stable `RG.MIRROR.CLIENT.*` codes, never server payload values.

When `mirrorServingAvailable` is true, submit `resourceGateway.mirrorExecutionRequest.v1` to
`POST /api/mirror/executions`, then read `resourceGateway.mirrorRunSummary.v1` from the response or
`GET /api/mirror/runs/{runId}`. The packaged schema resources are exposed as
`CapabilityMirrorProtocol.MIRROR_EXECUTION_REQUEST_SCHEMA_RESOURCE` and
`CapabilityMirrorProtocol.MIRROR_RUN_SUMMARY_SCHEMA_RESOURCE`. The command contains only stable request/plan
identity, the exact reviewed plan fingerprint, and business context; enterprise scope and execution policy are
server-owned. The summary contains no context, input, output, fixture, or replay payload.

After negotiation, verify every exported artifact before registry ingestion, impact analysis, or
mirror-plan compilation:

```java
JsonNode snapshot = objectMapper.readTree(snapshotJson);
CapabilityMirrorVerifier.VerifiedArtifact verifiedSnapshot =
        CapabilityMirrorVerifier.verifySnapshot(snapshot);

JsonNode closure = objectMapper.readTree(closureJson);
CapabilityMirrorVerifier.VerifiedClosure verifiedClosure =
        CapabilityMirrorVerifier.verifyClosure(closure);
```

The verifier uses the exact schemas packaged in the JAR and independently re-derives canonical
fingerprints. Closure verification additionally requires one exact composed root, one enterprise
scope, all and only reachable snapshots, no dependency cycle, no duplicate exact reference, and no
same-id/revision fingerprint conflict. It uses iterative graph traversal and accepts up to 10,001
snapshots, while canonical snapshot and closure material is bounded to 2 MiB and 16 MiB respectively.
No mutable server registry or Spring class is consulted.

Stateful mirror artifacts have a separate server-independent verifier and a
fixed refund compatibility fixture:

```java
JsonNode fixture = CapabilityMirrorProtocol.statefulRefundFixture();
MirrorStateProtocolVerifier verifier = new MirrorStateProtocolVerifier();

var model = verifier.verifyStateModel(fixture.path("stateModel"));
var effect = verifier.verifyWriteEffect(
        fixture.path("writeEffect"),
        fixture.path("stateModel"));
var session = verifier.verifySession(
        fixture.path("initialState"),
        fixture.path("stateModel"),
        List.of(fixture.path("writeEffect")));

JsonNode payload = verifier.sealSessionPayload(
        fixture.path("stateModel"),
        List.of(fixture.path("writeEffect")),
        fixture.path("initialState"));
```

The JAR packages the bounded-expression, state-model, write-effect,
session-state, Session lifecycle/write-attempt objects, five checkpoint/recovery objects, and fixture
Schemas. Verification re-derives nested and
top-level fingerprints, exact model/effect/session closure, mutation alias
admission, business-key component fingerprints, contiguous transaction
revisions, exact receipt/event closure, response fingerprints, and the latest
resulting-world binding. Success records and stable
`RG.MIRROR.CLIENT.*` failures are payload-free.

The payload sealer intentionally returns customer-shaped state so it can be
submitted; keep that value out of logs, exceptions, public evidence, and
control-plane persistence. The client validates before and after transport:

```java
ObjectNode create = objectMapper.createObjectNode()
        .put("schemaVersion",
                CapabilityMirrorProtocol.MIRROR_SESSION_CREATE_REQUEST_V1)
        .put("requestId", "refund-create-1");
create.set("payload", payload);

ResourceGatewayTestClient client = ResourceGatewayTestClient
        .builder(URI.create("http://localhost:8080"))
        .bearerToken(tokenProvider)
        .build();
JsonNode descriptor = client.createMirrorSession(create);
JsonNode current = client.findMirrorSession(
        descriptor.path("sessionId").asText());
JsonNode result = client.executeMirrorSessionCommand(
        descriptor.path("sessionId").asText(), commandRequest);
String attemptId = "<attempt id from run/evidence recovery coordinates>";
MirrorStateWriteAttemptVerifier.VerifiedWriteAttempt attempt =
        client.findMirrorSessionWriteAttempt(
                descriptor.path("sessionId").asText(), attemptId);
JsonNode checkpoint = client.createMirrorSessionCheckpoint(
        descriptor.path("sessionId").asText());
JsonNode recovery = client.recoverMirrorSession(
        descriptor.path("sessionId").asText(), checkpoint);
JsonNode terminal = client.destroyMirrorSession(
        descriptor.path("sessionId").asText());
```

All seven calls use `X-Purpose: MIRROR_REHEARSAL`, validate the Tool Studio
envelope, and bind the response session id to the requested id. Command
idempotency comes from the path declared by the admitted
`WriteEffectSpec.idempotency`, not from an ambient client retry key. The API is
physically absent in production; API/store readiness is distinct from
resolver/runtime readiness. See the
[stateful mirror kernel guide](../docs/resource-gateway-stateful-mirror-kernel.md)
for startup, transaction semantics, stable errors, and remaining production
work.

### Verify a ScenarioPack closure

Scenario assets reuse the existing TestSuite and FixtureBundle authorities.
They must not embed a second copy of business inputs, outputs, Session payloads,
or credentials. Resolve exact case and assertion revisions from your registry,
then verify the whole closure at an explicit policy instant:

```java
ScenarioPackVerifier.VerifiedScenarioPack verified =
        new ScenarioPackVerifier().verify(
                scenarioPackJson,
                resolvedScenarioCases,
                resolvedHandlingAssertions,
                Instant.parse("2026-07-24T05:00:00Z"));

assert verified.certificationRequired();
assert !verified.caseIds().isEmpty();
```

The verifier rejects unknown fields, fingerprint tampering, extra or missing
closure members, cross-scope or cross-target substitution, stale approvals,
shared stateful checkpoints, implicit fault injection, per-plan clock/random
drift, and handling assertions that cannot be evaluated from evidence. Exact
TestSuite case membership, FixtureBundle rule membership, MirrorPlan equality,
and live checkpoint authority are online rehearsal-compiler decisions and are
not claimed by this offline verifier.

Use `CapabilityMirrorProtocol.scenarioPackCompatibilityFixture()` to load the
fixed producer/test-kit canonicalization vector. The loader applies the fixture
Schema, verifies its complete closure at the embedded instant, compares the
expected projection, and returns a detached copy.

Completed Scenario rehearsal aggregates have a separate offline verifier.
Fetch the exact evidence bundle, resolve the public key named by its
attestation, then verify before workbook or gate ingestion:

```java
JsonNode scenarioBundle = objectMapper.readTree(scenarioEvidenceJson);
String keyId = scenarioBundle.path("attestation").path("keyId").asText();
EvidenceVerificationKey key = client.findEvidenceVerificationKey(keyId);

ScenarioRehearsalEvidenceVerifier.VerificationResult result =
        new ScenarioRehearsalEvidenceVerifier().verify(
                scenarioBundle, key);
if (!result.verified()) {
    throw new IllegalStateException(result.reasonCode());
}
```

Verification applies the packaged strict Schema, re-derives assertion, case,
aggregate and bundle fingerprints, proves ordered case identity, time,
evidence/assertion binding, derived outcome and summary counters, applies
signing-time key policy, and verifies the Scenario-specific Ed25519 signature
domain. It never returns TestCase input, Fixture value, Session entity or
business payload. A valid producer signature over a false aggregate outcome is
still rejected.

After the aggregate reaches its retention boundary, governance consumers can
verify the signed retention projection or deletion proof independently:

```java
JsonNode retention = objectMapper.readTree(retentionStateJson);
String keyId = retention.path("latestEvent")
        .path("evidenceSeal").path("keyId").asText();
EvidenceVerificationKey key = client.findEvidenceVerificationKey(keyId);

ScenarioRehearsalRetentionVerifier.VerificationResult result =
        new ScenarioRehearsalRetentionVerifier().verify(retention, key);
if (!result.verified()) {
    throw new IllegalStateException(result.reasonCode());
}
if ("PURGED".equals(retention.path("status").asText())
        && !result.verifiedDeletionProof()) {
    throw new IllegalStateException("Deletion proof is invalid");
}
```

The verifier applies the packaged command/event/state Schemas, recomputes the
latest event address, binds scope/run/request/revision/retention time back to
the projection, checks signing time and key lifecycle, and verifies Ed25519.
It never needs deleted aggregate data. The current state API exposes only the
latest event plus its predecessor address; full retained event-chain replay is
still performed by the server and is not claimed by this client method.

For ANEKE or CI workbook ingestion, prefer the closed client operation:

```java
JsonNode workbook =
        client.findScenarioRehearsalWorkbookSeed(scenarioRunId);
```

Before returning, the client fetches the strict workbook seed, signed aggregate
evidence, exact compiled plan, aggregate evidence key, and retention-event key.
`ScenarioRehearsalWorkbookVerifier` independently checks both Ed25519
signatures, plan/workbook content addresses, complete scope and source identity,
ordered compiled/executed/projected cases, assertion results, and the
conservatively derived blocker set. A valid producer signature cannot make an
incorrect `gateReady` decision acceptable.

Offline consumers can perform the same verification without HTTP:

```java
ScenarioRehearsalWorkbookVerifier.VerificationResult result =
        new ScenarioRehearsalWorkbookVerifier().verify(
                workbook, compiledPlan, evidenceBundle,
                evidenceKey, retentionKey);
if (!result.verified()) {
    throw new IllegalStateException(result.reasonCode());
}
```

The returned seed is a payload-free input to ANEKE, not the final workbook or
publish decision. ANEKE remains responsible for coverage policy, owner
approval, validation debt, environment certification, and the final gate.

`findMirrorSessionWriteAttempt` accepts an attempt id obtained from the
run/evidence recovery coordinate. Before returning its bounded projection, it
independently checks strict Schema, nested store generation, deterministic id,
record/failure fingerprints, lifecycle times, and outcome/state closure. It
never returns command input/output, entity identity, business key, raw
idempotency key, lease owner, or encryption material. For a direct command
whose response is uncertain, repeat the original command with the same
effect-defined idempotency key; exact replay remains the business recovery
path, while attempt lookup is the governance and diagnostics path.

`createMirrorSessionCheckpoint` fetches the checkpoint attestation key and
fully verifies strict Schema, store generation, nested fingerprints,
checkpoint-specific Ed25519 signature, time closure, and `HASH_ONLY` policy
before returning. `recoverMirrorSession` repeats local verification before any
recovery request, then requires the server result to bind the exact checkpoint,
generation, descriptor, and Session run binding. The test client discovers the
server key for local integration convenience; production CI and governance
must additionally pin that key through an organization-approved trust set.
Neither method reads, logs, restores, or rolls back Session payload.

Mirror run evidence has a separate fail-closed verifier. Resolve the public key named by the
attestation, then verify the decoded bundle before admitting it into a correctness workbook:

```java
JsonNode mirrorBundle = objectMapper.readTree(mirrorEvidenceJson);
String keyId = mirrorBundle.path("attestation").path("keyId").asText();
EvidenceVerificationKey key = client.findEvidenceVerificationKey(keyId);

MirrorEvidenceVerifier.VerificationResult result =
        new MirrorEvidenceVerifier().verify(mirrorBundle, key);
if (!result.verified()) {
    throw new IllegalStateException(result.reasonCode());
}

if (CapabilityMirrorProtocol.MIRROR_EVIDENCE_BUNDLE_V3.equals(
        mirrorBundle.path("schemaVersion").asText())) {
    MirrorStateWorkbookSeed seed =
            MirrorStateWorkbookSeed.fromVerifiedBundle(mirrorBundle, key);
    seed.requireGateReady();
} else if (CapabilityMirrorProtocol.MIRROR_EVIDENCE_BUNDLE_V4.equals(
        mirrorBundle.path("schemaVersion").asText())) {
    MirrorStateTransitionWorkbookSeed seed =
            MirrorStateTransitionWorkbookSeed.fromVerifiedBundle(
                    mirrorBundle, key);
    seed.requireGateReady();
} else if (CapabilityMirrorProtocol.MIRROR_EVIDENCE_BUNDLE_V5.equals(
        mirrorBundle.path("schemaVersion").asText())) {
    MirrorStateWriteOutcomeWorkbookSeed seed =
            MirrorStateWriteOutcomeWorkbookSeed.fromVerifiedBundle(
                    mirrorBundle, key);
    seed.requireGateReady();
}
```

Verification re-derives strict Schema admission, deterministic ordering, exact external-attempt to
resolution closure, request/output hash binding, nested resolution seals, evidence and bundle
fingerprints, signing-time key policy, and the domain-separated Ed25519 signature. Its result is
payload-free and suitable for CI logs. V1, v2, stateful-read v3, successful
stateful-read/write v4, and failure-aware stateful-read/write v5 use separate
signature domains and cannot be mixed inside one bundle. For v2/v3/v4/v5 deployment-egress claims the verifier also proves isolation-attestation
reference equality, stable decision/status generation, identical agent snapshot identity,
monotonic cache generation, admission before execution, and confirmation before signing. Canonical
evidence, bundle, and resolution material is
bounded to 64 MiB, 72 MiB, and 20 MiB respectively.

V3 additionally requires `resourceGateway.mirrorStateRunEvidence.v1`. The
verifier re-derives its nested self-fingerprint, validates exact Session head,
state model, state revision, world fingerprint, logical clock, canonical
binding/access order, and proves every `LIVE_ENTITY`, `ABSENT`, or
`TOMBSTONED` access closes against exactly one node attempt and resolution.
It never exposes entity values or business-key material.

V4 requires `resourceGateway.mirrorStateRunEvidence.v2`. The verifier binds the
initial and final Session heads, every read's exact observed revision, every
virtual write's request/output and receipt provenance, contiguous committed
revision chain, replay semantics, and payload-free transition-event closure to
the node attempt and resolution. Raw entity ids, idempotency keys, inputs, and
responses remain absent.

V5 requires `resourceGateway.mirrorStateRunEvidence.v3`. The verifier proves
that every executed virtual-write delegate attempt terminates as `COMMITTED`,
`REPLAYED`, `REJECTED`, `PRE_COMMIT_FAILED`, or
`COMMIT_OUTCOME_UNKNOWN`, with the exact failure stage and
`ADVANCED`/`UNCHANGED`/`UNKNOWN` state disposition. It independently
recomputes failure fingerprints and exact attempt/resolution closure. Unknown
commit outcomes must carry `WRITE_COMMIT_OUTCOME_UNKNOWN` in both nested and
outer evidence; they cannot be treated as a failed no-op.

`MirrorStateWorkbookSeed.fromVerifiedBundle` repeats v3 verification before it
projects the exact bundle/state/session/model coordinates, access counts, and
conservative blockers. `MirrorStateTransitionWorkbookSeed.fromVerifiedBundle`
repeats v4 verification and additionally projects initial/final heads,
committed/replayed receipt assertions, and payload-free event assertions.
`MirrorStateWriteOutcomeWorkbookSeed.fromVerifiedBundle` repeats v5
verification and projects every ordered attempt, all outcome counts, failure
coordinates, and successful transitions when present.
`fromPayload` checks strict seed Schema, self-fingerprint, counts, ordering, and
state closure, but cannot substitute for source-bundle signature verification.
For online use,
`client.findMirrorStateTransitionWorkbookSeed(runId)` fetches v4 evidence,
resolves its signing key, reconstructs the seed locally, reads the producer
seed, and compares canonical fingerprint plus source coordinates:

```java
MirrorStateTransitionWorkbookSeed seed =
        client.findMirrorStateTransitionWorkbookSeed(runId);
seed.requireGateReady();
```

For a new v5 run, use the failure-aware API instead:

```java
MirrorStateWriteOutcomeWorkbookSeed seed =
        client.findMirrorStateWriteOutcomeWorkbookSeed(runId);
seed.requireGateReady();
```

The client fetches v5 evidence, resolves its verification key, independently
reconstructs the seed, fetches the producer seed, and compares the canonical
fingerprint and source coordinates. A rejected write remains a blocker until
the workbook records it as an expected business outcome. Pre-commit failures
and unknown commit outcomes remain blockers; the latter require durable
reconciliation before certification.

Local exploratory evidence normally yields `gateReady=false`;
`requireGateReady()` returns the stable blocker set instead of silently
promoting it. ANEKE remains responsible for workbook coverage, owner approval,
policy, and the final publish gate. The legacy v4 transition seed deliberately
reports only observed committed/replayed writes. Prefer v5 for new write-capable
integrations. The server now supplies a durable attempt journal and recovery
reconciler. `mirrorStateWriteAttemptDurableReconciliationReady=false` therefore
means that the encrypted store, attempt table, resolver, or reconciliation
query path is currently unhealthy; writes and certification must remain closed
until readiness is restored.

Run the packaged fixed fixture in dependency-upgrade and startup probes:

```java
MirrorEvidenceCompatibilityFixture fixture =
        CapabilityMirrorProtocol.mirrorEvidenceCompatibilityFixture();
MirrorEvidenceVerifier.VerificationResult compatibility =
        new MirrorEvidenceVerifier().verify(
                fixture.bundle(), fixture.verificationKey());
if (!compatibility.verified()) {
    throw new IllegalStateException("Mirror evidence provider is incompatible");
}
```

The fixture is produced by the server and independently consumed here. It includes no private key
or business payload. Non-Java implementations are not certified merely by matching field names;
they must pass this cryptographic fixture without lossy numeric reserialization.

Detached Shadow jobs use a separate exact source-pair closure. Verify the
candidate evidence and source binding against the v2 job request's
`sourceBindingRef` and authenticated scope:

```java
ReadOnlyShadowSourceBindingVerifier.VerificationContext context =
        new ReadOnlyShadowSourceBindingVerifier.VerificationContext(
                authenticatedScope,
                jobRequest.path("sourceBindingRef"),
                candidateEvidenceBundle,
                candidateEvidenceKey,
                trustedClock.instant());

ReadOnlyShadowSourceBindingVerifier.VerificationResult source =
        new ReadOnlyShadowSourceBindingVerifier().verify(
                sourceBinding,
                sourceBindingAuthorityKey,
                context);
if (!source.verified()) {
    throw new IllegalStateException(source.reasonCode());
}
```

The verifier first applies the packaged strict Schemas, then independently
recomputes the baseline observation content address and complete source-binding
content address. A valid binding seal is insufficient: the referenced candidate
bundle must also pass `MirrorEvidenceVerifier` and match the exact run, bundle,
scope, plan, target capability, request-context fingerprint, and completion
time. Results contain bounded identities and reason codes only.

After a successful v3 comparison, verify its exact source-resolution proof as a
separate artifact. Do not infer it from a comparison label:

```java
ReadOnlyShadowSourceResolutionAttestationVerifier.VerificationContext resolutionContext =
        new ReadOnlyShadowSourceResolutionAttestationVerifier.VerificationContext(
                authenticatedScope,
                comparison.path("sourceResolutionAttestationRef"),
                jobRequest.path("requestId").asText(),
                lifecycleExecutionId,
                comparison.at("/authorityProof/admissionFingerprint").asText(),
                sourceBinding,
                sourceBindingAuthorityKey,
                context,
                trustedClock.instant());

ReadOnlyShadowSourceResolutionAttestationVerifier.VerificationResult resolution =
        new ReadOnlyShadowSourceResolutionAttestationVerifier().verify(
                sourceResolutionAttestation,
                sourceResolutionAuthorityKey,
                resolutionContext);
if (!resolution.verified()) {
    throw new IllegalStateException(resolution.reasonCode());
}
```

The verifier replaces the source-binding context's current time with the signed
resolution issue time, so historical evidence remains verifiable after the
binding's active window closes. It then re-runs
`ReadOnlyShadowSourceBindingVerifier`, recomputes the immutable
`payload-free-equality-v1` policy and candidate behavior/contract/effect/state
facts, and verifies the source-resolution content address, deterministic
`executionId`-bound identity, time order, zero-write claims, key lifecycle, and
Ed25519 seal. Artifact revisions are compared numerically rather than by
Jackson node class, preserving interoperability across JSON decoders.

Run the complete producer/consumer fixture in every Test Kit dependency,
Jackson, JDK, and crypto-provider upgrade:

```java
ReadOnlyShadowSourceResolutionCompatibilityFixture fixture =
        CapabilityMirrorProtocol
                .readOnlyShadowSourceResolutionCompatibilityFixture();

ReadOnlyShadowSourceResolutionAttestationVerifier.VerificationResult
        compatibility = fixture.verify();
if (!compatibility.verified()) {
    throw new IllegalStateException(compatibility.reasonCode());
}
```

The packaged fixture was produced by the server data plane and contains one
candidate evidence bundle, one source binding, one source-resolution
attestation, and three distinct public authority keys. Loading it verifies the
whole nested closure before returning. It catches deterministic-id domain,
recursive canonicalization, policy fingerprint, authority-role, signature,
numeric revision, and time-encoding drift that can remain invisible when the
producer and consumer test only self-generated artifacts. The fixture contains
no private key or business payload; passing it proves wire compatibility, not
current online authority, data-use permission, or production readiness.

Regional online-baseline observations have a separate dependency-light
verifier and server-produced public-only compatibility fixture:

```java
OnlineReadOnlyShadowBaselineCompatibilityFixture fixture =
        CapabilityMirrorProtocol
                .onlineReadOnlyShadowBaselineCompatibilityFixture();

OnlineReadOnlyShadowBaselineObservationVerifier.VerificationResult
        compatibility = fixture.verify();
if (!compatibility.verified() || !compatibility.zeroWrite()) {
    throw new IllegalStateException(compatibility.reasonCode());
}
```

The fixture contains the exact payload-free sidecar command, signed payload-free
observation, expected content-addressed artifact reference, public Ed25519 key,
and frozen verification time. It contains no private key, endpoint, credential,
customer request, or customer response. The loader validates both strict
schemas and independently recomputes the command fingerprint, idempotency key,
command-to-observation coordinates, deterministic observation id, observation
fingerprint, seal material, time closure, key policy, and signature.

For live evidence, construct
`OnlineReadOnlyShadowBaselineObservationVerifier.VerificationContext` from the
exact command retained by the caller, the exact observation reference expected
by the source-resolution workflow, and a trusted clock. Never derive those
expectations or the public key from the observation itself. A `VERIFIED` result
authenticates the measured write facts; inspect `zeroWrite()` separately.
Passing the fixture proves producer/consumer wire compatibility, not current
sidecar capability, workload identity, data-use permission, paired online
candidate execution, or production readiness.

`ONLINE_READ_ONLY_SHADOW_CANDIDATE_COMMAND_V1` and
`ONLINE_READ_ONLY_SHADOW_CANDIDATE_COMMAND_SCHEMA_RESOURCE` expose the strict
same-input candidate command. Candidate authorities must place the complete
command fingerprint in signed `MirrorRunEvidence.requestId`; consumers must
still verify the Mirror bundle independently.
`ONLINE_READ_ONLY_SHADOW_CANDIDATE_CAPABILITY_V1` and its Schema resource expose
the short-lived live probe contract for payload isolation, sealed-plan
execution, idempotency, signed evidence, production-credential prohibition, and
exact artifact reads. Protocol support is not runtime readiness.

Verify the complete online pair with the second fixed public-only fixture:

```java
OnlineReadOnlyShadowSourceResolutionCompatibilityFixture fixture =
        CapabilityMirrorProtocol
                .onlineReadOnlyShadowSourceResolutionCompatibilityFixture();

OnlineReadOnlyShadowSourceResolutionAttestationVerifier.VerificationResult
        compatibility = fixture.verify();
if (!compatibility.verified()) {
    throw new IllegalStateException(
            compatibility.reasonCode());
}
```

The server produced this fixture through the synthetic regional provider,
baseline and candidate connectors, and the online v2 resolver. The standalone
verifier applies both strict command schemas, delegates to the independent
baseline and Mirror evidence verifiers, then recomputes command pairing,
candidate command-to-evidence closure, current exact-read times,
`payload-free-equality-v1` facts, deterministic proof identity, full content
address, zero-write closure, key policy, and a third Ed25519 signature. The
fixture carries three public keys but no private key, endpoint, credential, or
business payload.

For live evidence, build
`OnlineReadOnlyShadowSourceResolutionAttestationVerifier.VerificationContext`
from authenticated caller coordinates plus the exact two commands and two
artifacts retained or independently resolved by the consumer. Never derive the
expected proof ref, scope, request/execution id, admission fingerprint, or
authority keys from the proof itself. `BASELINE_INVALID` and
`CANDIDATE_INVALID` preserve bounded nested reason codes; producer diagnostics
and payloads never enter the result.

Packaging and passing the fixture proves cross-implementation wire and crypto
compatibility. It does not prove that a production candidate authority,
regional payload vault, enterprise trust distribution, or full online data
plane is currently installed and authorized.

Use the complete durable-worker fixture as the one-shot ANEKE/CI gate:

```java
OnlineReadOnlyShadowWorkerCompatibilityFixture fixture =
        CapabilityMirrorProtocol
                .onlineReadOnlyShadowWorkerCompatibilityFixture();

OnlineReadOnlyShadowWorkerCompatibilityFixture.VerificationResult result =
        fixture.verify();
if (!result.verified()) {
    throw new IllegalStateException(result.reasonCode());
}
```

The public-only file was produced by the server's real H2 durable worker after
a simulated process crash, exact lease expiry, higher-epoch takeover, and
terminal success. The loader verifies the immutable request, job fingerprint,
complete lifecycle head, signed v3 comparison, two exact commands, both signed
source artifacts, signed v2 source-resolution proof, and four role-separated
public keys. It then joins scope, request/job/execution identities, comparison
and proof references, source observations, authority admission, zero-write
facts, and time order.

Comparison v1-v3 fingerprints predate recursively sorted JSON. The verifier
therefore reconstructs the exact versioned producer wire projection before
hashing instead of trusting the input document's field order. This preserves
existing signatures, accepts semantically equivalent field reordering, and
still rejects missing, extra, or changed fields.

`VerificationResult` is bounded and payload-free. A pass proves that a
consumer can independently verify one complete exported durable execution; it
does not prove current production authorization, a networked regional
provider, PostgreSQL HA, or business outcome calibration.

Capability observations use a separate producer authority and verifier. Run the packaged public-only
fixture whenever upgrading the protocol, JSON stack, crypto provider, or consumer:

```java
CapabilityObservationCompatibilityFixture fixture =
        CapabilityMirrorProtocol.capabilityObservationCompatibilityFixture();

CapabilityObservationVerifier.VerificationResult observation =
        new CapabilityObservationVerifier().verify(
                fixture.observation(),
                fixture.verificationKey(),
                fixture.expectedScope(),
                fixture.verificationTime());
if (!observation.verified()) {
    throw new IllegalStateException(observation.reasonCode());
}
```

For a real observation, construct `CapabilityObservationVerificationKey` and
`CapabilityObservationScope` from locally trusted configuration, never from the observation itself.
The verifier checks the strict `resourceGateway.capabilityObservation.v1` Schema, canonical use
ordering, full-scope equality, grant/retention/issuance windows, material and envelope fingerprints,
producer key lifecycle, issuer, algorithm, and Ed25519 signature. Its result contains only stable
coordinates and a low-cardinality reason.

This verification deliberately does not prove that `SANITIZED_PAYLOAD`,
`PAYLOAD_SANITIZATION_PROOF`, or `JSON_SCHEMA` references exist, belong to the tenant, or were
sanitized before persistence. Corpus admission still requires an independent tenant-scoped
payload-vault authority. Treating a valid producer signature as proof of payload governance would
collapse two separate trust domains. The packaged observation, admission, and receipt Schemas are
available through `CapabilityMirrorProtocol`; the fixture includes no private key or payload.

Corpus review, candidate and publication facts have a second fixed payload-free fixture:

```java
CapabilityCorpusCompatibilityFixture corpus =
        CapabilityMirrorProtocol.capabilityCorpusCompatibilityFixture();
CapabilityCorpusVerifier.VerificationResult governance =
        new CapabilityCorpusVerifier().verify(corpus);
if (!governance.verified()) {
    throw new IllegalStateException(governance.reasonCode());
}
```

The independent verifier closes all six strict Schemas, re-derives review/candidate/publish command
fingerprints and immutable artifact fingerprints, checks complete scope, exact command-to-fact
binding, ordered source coordinates, candidate/publication lineage, policy-independent risk
statistics and serving horizons. It deliberately cannot prove that the external source authority
still accepts payload/proof/grant/retention references, that an operator-owned policy is current,
that the actor remains authorized, that the publication is the current server head, or that a
resolver consumes it. Those remain online governance checks. See the
[Capability Corpus governance guide](../docs/resource-gateway-capability-corpus-governance.md).

Owner-reviewed retry trajectories have a separate offline verifier:

```java
CapabilityCorpusTrajectoryVerifier.VerificationResult trajectory =
        new CapabilityCorpusTrajectoryVerifier().verify(
                trajectoryCommand,
                trajectoryPublication,
                corpusPublication,
                corpusRevision,
                verificationTime);
```

It closes the four strict command/publication/corpus schemas, re-derives every content address,
binds the exact corpus publication and revision, and proves consecutive attempt membership plus a
common request fingerprint. A verified result deliberately retains online limitations for current
retry policy, normalized outcomes, trace ordering, grants, retention, tombstones, and payload
authority; offline structure is not runtime readiness.

Externally validated recorded-cluster publication has a fixed closed compatibility fixture:

```java
CapabilityCorpusClusterCompatibilityFixture cluster =
        CapabilityMirrorProtocol.capabilityCorpusClusterCompatibilityFixture();
CapabilityCorpusClusterVerifier.VerificationResult result =
        new CapabilityCorpusClusterVerifier().verify(cluster);
if (!result.verified()) {
    throw new IllegalStateException(result.reasonCode());
}
```

The verifier applies all five strict corpus/validation/command/publication schemas, independently
re-derives every content address, binds the exact corpus and command lineages, proves member and
representative membership plus a common response schema, rejects unsafe or overlapping identity
JSON Pointer projections, and recomputes the 95% Wilson precision interval from holdout counts.
A verified result still lists the online policy, validation-authority, grant/retention,
source-lifecycle, and payload-authority checks that an offline client cannot prove. It does not
claim that a selected cluster remains the current online head or can be materialized now.

Validate a fixture's payload-free exact publication selection before registration:

```java
JsonNode binding = objectMapper.readTree(bindingJson);
FixtureMirrorCorpusBindingsVerifier.VerificationResult verified =
        new FixtureMirrorCorpusBindingsVerifier().verify(binding);
if (!verified.verified()) {
    throw new IllegalStateException(verified.outcome().name());
}
```

The packaged fixed input is available at
`CapabilityMirrorProtocol.FIXTURE_MIRROR_CORPUS_BINDINGS_FIXTURE_RESOURCE`; its strict Schema is
`FIXTURE_MIRROR_CORPUS_BINDINGS_SCHEMA_RESOURCE`. The verifier checks schema closure, exact
artifact kinds, canonical capability ordering, and unique capability/publication coordinates. It
does not claim that a publication remains the current head or that live policy, grant, retention,
tombstone, regional vault, or resolver readiness checks pass.

Validate the sibling `fixtureBundle.metadata.mirrorTrajectories` object separately:

```java
JsonNode trajectoryBindings = objectMapper.readTree(trajectoryBindingsJson);
FixtureMirrorTrajectoryBindingsVerifier.VerificationResult verified =
        new FixtureMirrorTrajectoryBindingsVerifier().verify(trajectoryBindings);
if (!verified.verified()) {
    throw new IllegalStateException(verified.reasonCode());
}
```

The packaged fixed input is
`CapabilityMirrorProtocol.FIXTURE_MIRROR_TRAJECTORY_BINDINGS_FIXTURE_RESOURCE`; its strict Schema
is `FIXTURE_MIRROR_TRAJECTORY_BINDINGS_SCHEMA_RESOURCE`. The verifier proves closed fields,
artifact kinds, canonical capability/trajectory order, and exact trajectory-coordinate
uniqueness. Because it receives only the nested trajectory object, it cannot prove equality with
the same fixture's `mirrorCorpus` selection. Current trajectory/corpus heads, retry policy,
source/grant/retention/tombstone authorities, payload materialization, and graph retry capacity are
online Resource Gateway checks.

Validate the sibling `fixtureBundle.metadata.mirrorClusters` object and cross-check it against the
same fixture's `mirrorCorpus` selection:

```java
JsonNode clusterBindings = objectMapper.readTree(clusterBindingsJson);
JsonNode corpusBindings = objectMapper.readTree(corpusBindingsJson);
FixtureMirrorClusterBindingsVerifier.VerificationResult verified =
        new FixtureMirrorClusterBindingsVerifier().verify(
                clusterBindings, corpusBindings);
if (!verified.verified()) {
    throw new IllegalStateException(verified.reasonCode());
}
```

The packaged fixed input is
`CapabilityMirrorProtocol.FIXTURE_MIRROR_CLUSTER_BINDINGS_FIXTURE_RESOURCE`; its strict Schema is
`FIXTURE_MIRROR_CLUSTER_BINDINGS_SCHEMA_RESOURCE`. The verifier proves closed fields, artifact
kinds, canonical capability/cluster order, exact cluster-coordinate uniqueness, and equality with
one exact selected capability/corpus pair. Current heads, policies, validation revocation, grants,
source lifecycle, retention, payload content addresses, identity values, and graph runtime
readiness remain online Resource Gateway checks.

Deployment isolation uses separate bootstrap-root, isolation-attestation, and mirror-evidence
authorities. Never reuse keys between those roles or trust deployment coordinates copied from an
untrusted publication. Verify the authority key-set against immutable local binding, locally pinned
M-of-N roots with distinct public-key material, trusted time, and the last durably accepted floor
before selecting the attestation key:

```java
MirrorDeploymentIsolationAuthorityKeySetVerifier.VerificationResult keySet =
        new MirrorDeploymentIsolationAuthorityKeySetVerifier().verify(
                authorityPublication,
                expectedAuthorityBinding,
                pinnedBootstrapRoots,
                durableTrustedFloor,
                trustedClock.instant());
if (!keySet.verified()) {
    throw new IllegalStateException(keySet.reasonCode());
}
MirrorDeploymentIsolationVerificationKey authorityKey = keySet
        .authorityKey(attestation.path("seal").path("keyId").asText())
        .orElseThrow();
MirrorDeploymentIdentity expectedDeployment = localDeploymentIdentity.current();

MirrorDeploymentIsolationAttestationVerifier.VerificationResult isolation =
        new MirrorDeploymentIsolationAttestationVerifier().verify(
                attestation,
                authorityKey,
                expectedDeployment,
                executionStartedAt,
                executionCompletedAt);
if (!isolation.verified()) {
    throw new IllegalStateException(isolation.reasonCode());
}
```

Verification requires the strict Schema, canonical UTC time and Base64 forms, deterministic
collection ordering, unique `(kind, id, revision)` policy-proof coordinates, both canonical fingerprints,
exact issuer/key policy, Ed25519 signature, exact local deployment generation, and a
run wholly inside `[max(validFrom, signedAt), expiresAt)`. Attestations last at most 15 minutes and
may be signed at most 5 minutes after observation. Results expose only bounded reason codes and
artifact coordinates.

The authority-publication verifier additionally requires both canonical publication fingerprints,
exact full-scope/deployment/issuer/key-set/trust-domain/policy binding, at most 24 hours of validity,
canonical key/signature ordering, an active attestation key covering the whole publication window,
every supplied root signature to be pinned and valid, and a monotonic trusted floor. Generation one
is the only bootstrap; idempotent reread is allowed; rollback, fork, generation gaps, predecessor
mismatch, threshold downgrade, and unknown or revoked extra roots fail closed. The caller must store
and advance the trusted floor durably; an in-memory floor is insufficient for production rollback
protection.

Resource Gateway now exposes a protected current-only trusted-distribution API backed by its own
full-scope append-only log and database CAS floor. A deployment agent may fetch `latest` or an exact
current content address, but it must still use this independent verifier with its own immutable
binding, pinned roots, trusted clock, and durable local floor before replacing its cache. Server
acceptance is not transitive client trust, and the API intentionally does not serve historical
generations as a downgrade mechanism. See the
[trusted-distribution guide](../docs/resource-gateway-mirror-authority-trusted-distribution.md).

Run the second packaged fixed fixture during dependency upgrades and startup probes:

```java
MirrorDeploymentIsolationCompatibilityFixture fixture =
        CapabilityMirrorProtocol.mirrorDeploymentIsolationCompatibilityFixture();
MirrorDeploymentIsolationAttestationVerifier.VerificationResult compatibility =
        new MirrorDeploymentIsolationAttestationVerifier().verify(
                fixture.attestation(),
                fixture.verificationKey(),
                fixture.expectedDeployment(),
                fixture.executionStartedAt(),
                fixture.executionCompletedAt());
if (!compatibility.verified()) {
    throw new IllegalStateException("Deployment isolation verifier is incompatible");
}
```

Run the public-only threshold-publication fixture in the same probe:

```java
MirrorDeploymentIsolationAuthorityKeySetCompatibilityFixture authorityFixture =
        CapabilityMirrorProtocol
                .mirrorDeploymentIsolationAuthorityKeySetCompatibilityFixture();
MirrorDeploymentIsolationAuthorityKeySetVerifier.VerificationResult authorityCompatibility =
        new MirrorDeploymentIsolationAuthorityKeySetVerifier().verify(
                authorityFixture.publication(),
                authorityFixture.expectedBinding(),
                authorityFixture.bootstrapRoots(),
                null,
                authorityFixture.verificationTime());
if (!authorityCompatibility.verified()) {
    throw new IllegalStateException("Isolation authority publication verifier is incompatible");
}
```

Packaged roots and keys are fixture-only and must never be accepted for a real deployment. The
server now provides publication/attestation repositories, pinned identity-bound mTLS refresh,
durable floor CAS, atomic deployment cache, and per-run evidence binding. Certification still
depends on a live customer deployment agent and governed signer; an exploratory bundle remains
explicitly exploratory. The fixed compatibility fixture is v1, while generated v2 bundles are
strictly validated and independently verified by this kit. A fixed non-Java v2 compatibility
fixture remains future work.

### Verify selected-population completeness

Selected-population commands, evidence, resumable transport, continuous
projection, and controlled quarantine remediation use twenty-four strict Draft 2020-12
Schemas packaged in both Test Kit JARs. `CapabilityMirrorProtocol` exposes the
version and resource constants; `CapabilityMirrorSchemaValidator` resolves the
complete local `$ref` closure without fetching a network resource.

The typed HTTP client applies the command Schema before transport, chooses the
dedicated workload purpose, validates the integration envelope, and applies the
fact Schema before returning a defensive `JsonNode`:

```java
JsonNode admission =
        client.submitAuthoritativeOutcomeSelectedPopulation(
                populationCommand);
String populationId = admission.path("population")
        .path("manifest").path("populationId").asText();

client.submitAuthoritativeOutcomeSelectedPopulationDisposition(
        populationId, dispositionCommand);
JsonNode assessmentAdmission =
        client.assessAuthoritativeOutcomeSelectedPopulation(
                populationId, assessmentCommand);

String assessmentId = assessmentAdmission.path("assessment")
        .path("assessmentId").asText();
long revision = assessmentAdmission.path("assessment")
        .path("revision").asLong();

JsonNode population =
        client.findAuthoritativeOutcomeSelectedPopulation(
                populationId, 1);
JsonNode assessment =
        client.findAuthoritativeOutcomeSelectedPopulationAssessment(
                populationId, assessmentId, revision);
List<JsonNode> sourcePages =
        client.findCompleteAuthoritativeOutcomeSelectedPopulationAssessmentSources(
                populationId, assessmentId, revision, 500, 10_000);
```

The complete-source helper starts at cursor zero and requires a page carrying
`complete=true`. It rejects cursor drift, stalls, premature empty pages, invalid
page size, and exhaustion of the caller-owned page budget. A syntactically valid
suffix is not evidence of a complete assessment.

Perform independent verification after transport and before ANEKE workbook,
registry, or publish-gate ingestion:

```java
AuthoritativeOutcomeSelectedPopulationVerifier verifier =
        new AuthoritativeOutcomeSelectedPopulationVerifier();

var populationResult = verifier.verifyPopulation(
        population,
        keyId -> governedKeyHistory.resolveExact(keyId),
        (manifest, chunks) ->
                selectionAuthority.verifyExact(manifest, chunks),
        trustedClock.instant());

var assessmentResult = verifier.verifyAssessment(
        assessment,
        sourcePages,
        population,
        keyId -> governedKeyHistory.resolveExact(keyId),
        (manifest, chunks) ->
                selectionAuthority.verifyExact(manifest, chunks),
        (kind, sourceRef, selectedMember) ->
                outcomeRegistry.verifyExact(
                        kind, sourceRef, selectedMember),
        trustedClock.instant());

if (!populationResult.verified()
        || !assessmentResult.verified()) {
    throw new IllegalStateException(
            "Selected-population evidence is not independently verifiable");
}
```

The verifier has no Spring or Resource Gateway server-model dependency. Before
calling customer authorities it verifies bounded Schema, canonical addresses,
chunk/member/root closure, denominator arithmetic, Resource Gateway Ed25519
seal and key lifecycle, page cursor continuity, source-set count/fingerprint,
and exact population binding. Authority callbacks receive defensive copies.

Automation should branch only on the bounded result:
`VERIFIED`, `INVALID`, `AUTHORITY_UNAVAILABLE`, `KEY_UNAVAILABLE`, or
`POLICY_REJECTED`, plus its stable reason code. Do not log input JSON or enrich
errors with member identifiers. Selection, deletion, and source authority
callbacks are distinct by design; a Resource Gateway verification key proves
custody, not business selection or deletion authority.

Run the fixed producer/consumer fixture in dependency upgrades and startup
probes:

```java
AuthoritativeOutcomeSelectedPopulationCompatibilityFixture fixture =
        CapabilityMirrorProtocol
                .authoritativeOutcomeSelectedPopulationCompatibilityFixture();
var compatibility = fixture.verify();
if (!compatibility.verified()) {
    throw new IllegalStateException(compatibility.reasonCode());
}
```

The server-produced fixture freezes a three-member denominator: one `MATCH`,
one `MISMATCH`, and one independently authorized legal deletion. It includes
the signed population and chunks, two signed observations, one signed
disposition, a zero-missing signed assessment, its complete source page, one
public key, and a frozen verification time. It contains no private key,
credential, endpoint, request, response, or business payload.

The fixture is packaged at
`schemas/resource-gateway-mirror/authoritative-outcome-selected-population-stage1-v1.fixture.json`.
TypeScript, Go, and other consumers should use that exact public corpus for
their own Schema, canonicalization, content-address, Ed25519, denominator, and
source-closure tests. Passing the Java fixture proves server/Test Kit
compatibility; it does not certify another language implementation or replace
live customer authority callbacks.

The original complete-population route remains bounded to 64 MiB. For a larger
retryable transfer, stage the same manifest and chunks without splitting one
logical population into unrelated revisions:

```java
JsonNode uploadAdmission =
        client.beginAuthoritativeOutcomeSelectedPopulationUpload(
                uploadRequest);

for (int chunkIndex = 0;
        chunkIndex < chunks.size();
        chunkIndex++) {
    client.stageAuthoritativeOutcomeSelectedPopulationUploadChunk(
            uploadId,
            chunkIndex,
            chunks.get(chunkIndex));
}

JsonNode progress =
        client.findAuthoritativeOutcomeSelectedPopulationUpload(
                uploadId);
if (progress.path("nextMissingChunkIndex").asInt() != -1) {
    throw new IllegalStateException(
            "selected-population upload is incomplete");
}

JsonNode populationAdmission =
        client.finalizeAuthoritativeOutcomeSelectedPopulationUpload(
                uploadId);
```

Chunks may be sent out of order. Repeat the exact begin command or exact
index/chunk after an ambiguous response; both methods validate their strict
request and response schemas and expose `idempotentReplay`. Status is
payload-free. Call
`abortAuthoritativeOutcomeSelectedPopulationUpload(uploadId)` only when the
caller intends to abandon an open upload and destroy its staged chunks.

The server defaults to 16 active uploads and 1 GiB staged bytes per exact scope,
256 MiB per upload, a 24-hour incomplete TTL, a two-minute finalizer lease, and
seven-day terminal retention. Capacity and lifecycle are server-owned. A 429
means the caller should preserve the same upload identity and retry after the
bounded hint, not fan out more uploads.

Staged upload removes one-request retry amplification, but it is not unlimited
streaming projection. Finalize currently materializes the policy-bounded chunk
closure in one Resource Gateway JVM before invoking the governed selection
authority and immutable population admission. The ordinary demo startup also
has no permissive customer authority and therefore does not expose these routes
or report selected-population readiness.

After one exact population revision is admitted, register a server-owned
continuous completeness stream:

```java
ObjectNode command = json.createObjectNode();
command.put(
        "schemaVersion",
        CapabilityMirrorProtocol
                .AUTHORITATIVE_OUTCOME_CONTINUOUS_ASSESSMENT_REQUEST_V1);
command.put("projectionId", "refund-completeness");
command.set("populationRef", exactPopulationRef);

JsonNode registered =
        client.registerAuthoritativeOutcomeContinuousAssessment(
                command);
JsonNode status =
        client.findAuthoritativeOutcomeContinuousAssessment(
                "refund-completeness");

var statusVerification =
        new AuthoritativeOutcomeContinuousAssessmentVerifier()
                .verify(status);
if (!statusVerification.verified()) {
    throw new IllegalStateException(
            statusVerification.reasonCode());
}

long afterOrdinal = 0;
String predecessorFingerprint = "";
do {
    JsonNode page =
            client.findAuthoritativeOutcomeContinuousAssessmentLifecycle(
                    "refund-completeness",
                    afterOrdinal,
                    predecessorFingerprint,
                    100);
    var lifecycleVerification =
            new AuthoritativeOutcomeContinuousAssessmentLifecycleVerifier()
                    .verify(
                            page,
                            afterOrdinal,
                            predecessorFingerprint);
    if (!lifecycleVerification.verified()) {
        throw new IllegalStateException(
                lifecycleVerification.reasonCode());
    }
    afterOrdinal = lifecycleVerification.nextOrdinal();
    predecessorFingerprint =
            lifecycleVerification.eventFingerprint();
    if (!lifecycleVerification.hasMore()) {
        break;
    }
} while (true);
```

`QUARANTINED` is a terminal autonomous-scheduling state, not a retry hint.
After repairing the external cause, an authorized operations workflow must
construct the remediation command from the independently verified status and
lifecycle checkpoint:

```java
ObjectNode remediation = json.createObjectNode();
remediation.put(
        "schemaVersion",
        CapabilityMirrorProtocol
                .AUTHORITATIVE_OUTCOME_CONTINUOUS_ASSESSMENT_REMEDIATION_REQUEST_V1);
remediation.put("commandId", "incident-4821-recovery-1");
remediation.put(
        "expectedProjectionFingerprint",
        status.path("projection")
                .path("recordFingerprint").asText());
remediation.put("expectedLifecycleHeadOrdinal", afterOrdinal);
remediation.put(
        "expectedLifecycleHeadFingerprint",
        predecessorFingerprint);
remediation.put("reasonCode", "OUTCOME_AUTHORITY_REPAIRED");

JsonNode receipt =
        client.remediateAuthoritativeOutcomeContinuousAssessment(
                "refund-completeness",
                remediation);
var remediationVerification =
        new AuthoritativeOutcomeContinuousAssessmentRemediationVerifier()
                .verify(receipt);
if (!remediationVerification.verified()) {
    throw new IllegalStateException(
            remediationVerification.reasonCode());
}
```

The client uses the dedicated
`MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_ADMIN` purpose, applies both strict
Schemas, verifies the integration envelope, and independently verifies both
projection content addresses, the appended `REMEDIATION_ACCEPTED` event,
projection/lifecycle double CAS, actor-bound command fingerprint, allowed
state delta, and receipt content address. Exact retries by the same actor and
command return the same receipt. A changed command, actor, projection, or
lifecycle head is rejected; there is deliberately no force or blind-retry
variant.

Both methods validate strict Schema and integration-envelope versions. The
registration method also requires the returned projection id and exact
population reference to equal the command; the read method rejects cross-id
substitution. Both invoke the independent verifier to recompute the projection
content address, server-owned assessment stream, state/lease/time closure,
exclusive freshness boundary, and effective readiness. The caller must
preserve the same `projectionId` for an ambiguous registration response.

Treat only `status.ready=true` as a current governance fact. That field is
derived from `sourceFreshness=CURRENT` and live external-authority readiness.
`UNINITIALIZED`, `REFRESHING`, `STALE`, and `QUARANTINED` are fail-closed even
when `lastAssessmentRef` points to valid historical evidence. Fetch that exact
assessment and all source pages with the methods above, then run
`AuthoritativeOutcomeSelectedPopulationVerifier`; status Schema validation is
not independent verification of the referenced immutable assessment.

The lifecycle method validates the strict page and event schemas, the
integration envelope, projection identity, caller-owned cursor, every embedded
projection content address, every event content address, contiguous ordinals,
and predecessor links before returning. Persist the verified ordinal and event
fingerprint together. Restarting from an ordinal with a different predecessor
or trusting a server-selected continuation would weaken truncation and
substitution detection.

The Test Kit intentionally exposes no `runNow` call and accepts no polling,
lease, retry, assessment-id, or revision controls. Those are server-owned so a
governance client cannot create a false freshness claim or competing
assessment writer.

## Use

Start Resource Gateway with its `test` or `staging` profile, then discover the
current composite target fingerprint before constructing fixtures:

```java
ResourceGatewayTestClient client = ResourceGatewayTestClient
        .builder(URI.create("http://localhost:8080"))
        .bearerToken(() -> System.getenv("RESOURCE_GATEWAY_TEST_TOKEN"))
        .build();

GraphTargetDescriptor target = client.describeGraphTarget("loanDecisionPolicy");

FixtureBundleBuilder fixture = FixtureBundleBuilder
        .graph(target.graphId(), target.fingerprint())
        .id("loan-approved")
        .revision(1)
        .identityAttribute("tenant", "acme-test")
        .featureFlag("pricing-v2", true)
        .rule("credit-provider")
            .resource("credit-provider.primary")
            .protocolResponse(
                    "{\"code\":0,\"data\":{\"score\":780}}",
                    200,
                    Map.of("Content-Type", "application/json"))
            .requiredUses(1, 1)
            .add()
        .assertOutput("/approved", "EQUALS", true);

FixtureBundleRevision stored = client.registerFixture(
        "loan-approved", fixture.registrationRequest());

var execution = fixture.storedExecution(
        stored.fingerprint(),
        Map.of("applicantId", "app-42", "amount", 100_000),
        ResourceGatewayTestClient.Verbosity.STANDARD,
        Map.of("suiteRef", "loan-policy", "caseRef", "approved"));
TestRun baselineRun = client.execute(execution);
TestRun run = client.execute(execution);

TestRunAssertions.assertPassed(run);
TestRunAssertions.assertCertifiable(run);
TestRunAssertions.assertFixturesSatisfied(run);
TestRunAssertions.assertNoRealInvocations(run);

// Compare a repeated run with a frozen baseline without coupling to run ids or durations.
TestRunAssertions.assertSameSemanticResult(baselineRun, run);
String semanticResult = run.semanticResultFingerprint();

TestRun.NodeTrace occurrence = run.nodeTraces().getFirst();
String site = occurrence.invocationSiteId();
int graphOccurrence = occurrence.graphOccurrence();
List<TestRun.AttemptTrace> attempts = occurrence.attempts();
List<TestRun.EdgeTrace> edges = run.edgeTraces();

JUnitXmlReportWriter.write(
        Path.of("target/surefire-reports/resource-gateway-contracts.xml"),
        "loan-policy",
        List.of(run));
```

`identityAttribute` accepts only non-null JSON string/boolean/integer scalars;
`featureFlag` accepts exact boolean decisions. Both use the reserved, versioned
`metadata.executionServices` wire object and enforce the server's entry, key, value, and 64 KiB
aggregate bounds before sending. Unknown runtime keys fail closed. Raw secrets are intentionally not
supported by this builder.

Plan pure-DSL graph mutations without receiving mutated source, then freeze and execute the exact
reviewed plan against a governed oracle:

```java
JsonNode mutationPlan = client.planGraphMutationCases("loanDecisionPolicy", 16);
if (!mutationPlan.path("status").asText().equals("GENERATED")) {
    mutationPlan.path("gaps").forEach(System.out::println);
}

ObjectNode materializationRequest = new ObjectMapper().createObjectNode();
materializationRequest.put("schemaVersion",
        "bloge.testMutationSuiteMaterializationRequest.v1");
materializationRequest.put("suiteId", "loan-decision-mutations");
materializationRequest.put("classification", "INTERNAL");
materializationRequest.put("expectedTargetFingerprint",
        mutationPlan.path("target").path("fingerprint").asText());
materializationRequest.put("expectedSourceFingerprint",
        mutationPlan.path("sourceFingerprint").asText());
materializationRequest.put("expectedGraphArtifactFingerprint",
        mutationPlan.path("graphArtifactFingerprint").asText());
materializationRequest.put("expectedPlanFingerprint",
        mutationPlan.path("planFingerprint").asText());
materializationRequest.put("maxMutants", 16);
materializationRequest.put("acceptPlanningGaps",
        mutationPlan.path("status").asText().equals("PARTIAL"));
materializationRequest.putObject("oracleSuiteRef")
        .put("suiteId", "loan-decision-regression")
        .put("revision", 7)
        .put("fingerprint", "sha256:<exact-oracle-suite-fingerprint>");
materializationRequest.putObject("scorePolicy")
        .put("minimumScoreBasisPoints", 8000)
        .put("maximumInconclusiveMutants", 0)
        .put("requireNoSurvivors", false)
        .put("excludeEquivalentMutants", false);
JsonNode materialized = client.materializeGraphMutationSuite(
        "loanDecisionPolicy", materializationRequest);
JsonNode mutationSuite = materialized.path("suiteRef");

TestSuiteRun mutationRun = client.executeMutationSuite(
        mutationSuite.path("suiteId").asText(),
        mutationSuite.path("revision").asLong(),
        mutationSuite.path("fingerprint").asText(),
        "mutation-ci-1842",
        ResourceGatewayTestClient.MutationStrategy.STOP_AFTER_KILL,
        Map.of("pipeline", "release-candidate", "buildId", "1842"));

TestSuiteRunAssertions.assertMutationSatisfied(mutationRun);
TestSuiteRun.MutationScore mutationScore = mutationRun.requireMutationScore();
List<TestSuiteRun.MutantResult> mutants = mutationRun.mutantResults();
```

The client validates the plan, materialization, V6 response, V5 evidence, and V5 attestation against
its packaged authoritative Schema and binds every response back to the caller's exact graph, plan,
oracle, suite, and idempotency identities. It independently re-derives baseline status, mutant
classification, killing cases, denominator, score, and policy verdict. Planning by itself remains an
authoring asset; only the terminal generation-matched bundle is evidence. Verify that bundle against
an independently pinned key set before a governance gate consumes it.

Run an exact synchronous operator binding with the same governed fixture and evidence protocol:

```java
OperatorTargetDescriptor operator = client.describeOperatorTarget("customer.normalize");

FixtureBundleBuilder operatorFixture = FixtureBundleBuilder
        .operator(operator.operatorRef(), operator.fingerprint())
        .id("normalize-contract")
        .rule("real-binding")
            .operator(operator.operatorRef())
            .spy()
            .requiredUses(1, 1)
            .add()
        .assertOutput("/normalized", "EQUALS", "ADA");

FixtureBundleRevision operatorRevision = client.registerFixture(
        "normalize-contract", operatorFixture.registrationRequest());
TestRun operatorRun = client.executeOperator(operator.operatorRef(),
        operatorFixture.storedOperatorExecution(operatorRevision.fingerprint(),
                Map.of("name", "Ada"),
                ResourceGatewayTestClient.Verbosity.STANDARD,
                Map.of("suiteRef", "normalization", "caseRef", "uppercase")));

TestRunAssertions.assertPassed(operatorRun);
TestRunAssertions.assertCertifiable(operatorRun);
```

Build and execute one immutable suite without hand-writing the suite protocol:

```java
TestSuiteBuilder suite = TestSuiteBuilder.operator(operator)
        .id("normalization-regression")
        .revision(1)
        .addCase("uppercase", TestSuiteBuilder.CaseType.GOLDEN,
                Map.of("name", "Ada"), operatorRevision)
        .requireCaseTypes(TestSuiteBuilder.CaseType.GOLDEN)
        .metadata(Map.of("owner", "customer-platform"));

TestSuiteRevision storedSuite = client.registerSuite(
        "normalization-regression", suite.registrationRequest());

TestSuiteRun suiteRun = client.executeSuite(
        storedSuite.suiteId(),
        storedSuite.revision(),
        storedSuite.fingerprint(),
        "pipeline-982-job-4",
        ResourceGatewayTestClient.SuiteStrategy.COLLECT_ALL,
        Map.of("source", "junit"));

TestSuiteRunAssertions.assertPassed(suiteRun);
TestSuiteRunAssertions.assertAllCasesPassed(suiteRun);
TestSuiteRunAssertions.assertCoverageSatisfied(suiteRun);
TestSuiteRunAssertions.assertPromotionEligible(suiteRun);

TestSuiteEvidenceVerifier.VerificationResult verification =
        client.verifySuiteEvidence(suiteRun.suiteRunId());
if (!verification.verified()) {
    throw new IllegalStateException(verification.reasonCode());
}

JUnitXmlReportWriter.writeSuite(
        Path.of("target/surefire-reports/resource-gateway-suite.xml"),
        suiteRun,
        true);
```

Run the exact immutable suite repeatedly and require independently verified stability before a
release gate consumes the result:

```java
TestSuiteStabilityRun stability = client.executeSuiteStability(
        storedSuite.suiteId(),
        storedSuite.revision(),
        storedSuite.fingerprint(),
        "stability-ci-982",
        5,
        Map.of("source", "nightly"));

TestSuiteStabilityProgress progress =
        client.findSuiteStabilityProgress(stability.stabilityRunId());
// RUNNING: live DB owner; RECOVERABLE: exact retry may take over; COMPLETED: terminal exists.

String trustedPin = System.getenv("RESOURCE_GATEWAY_TRUSTED_KEY_SET_FINGERPRINT");
TestSuiteStabilityEvidenceVerifier.VerificationResult stabilityVerification =
        client.verifySuiteStability(stability.stabilityRunId(), trustedPin);

TestSuiteStabilityAssertions.assertReleaseEligible(stability, stabilityVerification);
JUnitXmlReportWriter.writeStability(
        Path.of("target/surefire-reports/resource-gateway-stability.xml"),
        stability,
        stabilityVerification);
```

For an exact probability claim, precommit the model and let the policy derive the minimum horizon:

```java
TestSuiteStabilityStatisticalPolicy policy =
        TestSuiteStabilityStatisticalPolicy.baselineConditionalExactBinomial(9_500, 1_000);
TestSuiteStabilityRun statistical = client.executeStatisticalSuiteStability(
        storedSuite.suiteId(), storedSuite.revision(), storedSuite.fingerprint(),
        "stability-ci-983", policy.minimumRequiredAttempts(), policy,
        Map.of("source", "nightly"));

TestSuiteStabilityEvidenceVerifier.VerificationResult verified =
        client.verifySuiteStability(statistical.stabilityRunId(), trustedPin);
TestSuiteStabilityAssertions.assertStatisticalReleaseEligible(statistical, verified);
JUnitXmlReportWriter.writeStability(
        Path.of("target/surefire-reports/resource-gateway-statistical-stability.xml"),
        statistical, verified, true);
```

For optional-stopping-safe early completion, precommit the ceiling, a strictly smaller alternative,
confidence, and maximum horizon. The returned closure contains the actual observed prefix, which can
be shorter than the requested maximum:

```java
TestSuiteStabilityStatisticalPolicy sequentialPolicy =
        TestSuiteStabilityStatisticalPolicy.anytimeValidEProcess(9_500, 1_000, 500);
TestSuiteStabilityRun sequential = client.executeStatisticalSuiteStability(
        storedSuite.suiteId(), storedSuite.revision(), storedSuite.fingerprint(),
        "stability-ci-984", 100, sequentialPolicy,
        Map.of("source", "nightly"));

assert sequential.requestedAttempts() == 100;
assert sequential.attempts().size() == 57;
assert sequential.statisticalAssessment().firstBoundaryCrossingAttempt() == 57;
assert sequential.statisticalAssessment().stopReason()
        == TestSuiteStabilityRun.StatisticalStopReason.E_VALUE_THRESHOLD_REACHED;
```

Deterministic stability always runs `COLLECT_ALL` exactly 3..20 times. Current statistical request v3
uses a precommitted 3..1000 horizon, exact integer arithmetic, and a 10,000 attempt-by-case work
bound. Request v4 selects the baseline-conditional anytime-valid e-process and treats that horizon as
a maximum. Its only legal terminals are the first boundary crossing, the first censored attempt, or
the maximum horizon; the independent verifier replays every signed prefix and rejects a producer
that reports a later favorable crossing. A case is compared by
`evidenceStatus + semanticResultFingerprint`, not only by pass/fail status. The aggregate is
`STABLE`, `FLAKY`, `CONSISTENT_FAILURE`, or `INCONCLUSIVE`; plan drift, reused source/child run ids,
missing evidence, an invalid signature, or an incomplete source closure can never be promoted to
stable. Stability response v2 also binds each source suite's promotion status and reasons. A result
may therefore be `STABLE + BLOCKED`: its behavior is invariant, but at least one source run was not
certifiable. Signed v1 responses remain verifiable for audit, while
`sourcePromotionClosureAvailable()` is false and every release assertion fails closed. A `FLAKY`
result carries a quarantine recommendation, but the API does not mutate suite state or bypass a
business failure. V4 excludes the first verified baseline from its comparison count and independently
reconstructs the upward-rounded one-sided exact rate bound for complete zero- or non-zero-event
samples. Historical v3 keeps its original zero-event meaning. V5's e-process is anytime-valid, but
does not prove business correctness, baseline representativeness, stationarity, or independence from
common causes.
`findSuiteStabilityProgress` is an operational poll, not a release gate. Its strict
v1/v2 projection returns only lifecycle, exact suite identity, planned/completed counts, an optional
v2 terminal reason, and timestamps; owner, epoch, source ids, fixtures, and payloads are absent.

Analyze several retained terminal stability runs without confusing execution-regime drift with
flakiness:

```java
TestSuiteStabilityTrendRequest trendRequest = new TestSuiteStabilityTrendRequest(
        storedSuite.suiteId(), storedSuite.revision(), storedSuite.fingerprint(),
        Instant.parse("2026-07-18T00:00:00Z"),
        Instant.parse("2026-07-19T00:00:00Z"),
        3, 20);

TestSuiteStabilityTrendAnalysis trend =
        client.analyzeSuiteStabilityTrend(trendRequest);
TestSuiteStabilityTrendEvidenceVerifier.VerificationResult trendVerification =
        client.verifySuiteStabilityTrend(trendRequest, trustedPin);
if (!trendVerification.verified()) {
    throw new IllegalStateException(trendVerification.reasonCode());
}
```

Trend verification fetches the exact attested source closure, independently verifies each source,
reconstructs outcome/fixture/plan sets, regime fingerprints, case transitions, correlation signals,
diagnostics, and aggregate status, then verifies the trend signature. A plan or fixture change is
`REGIME_DRIFT_OBSERVED`, not flakiness. Correlation signals always retain
`causalityStatus=NOT_PROVEN`. Persistence timestamps, expired counts, and truncation are signed
storage facts whose consistency is checked; an offline client cannot re-query the producer store.
An incomplete retained window is diagnostic evidence and always `INCONCLUSIVE`, never a release
proof. V1 intentionally does not claim cross-retention history or automatic quarantine.

For the default-disabled compact-observation preview, verification does not fetch full source runs
that may already have expired:

```java
TestSuiteStabilityCrossRetentionTrendRequest crossRetention =
        TestSuiteStabilityCrossRetentionTrendRequest.firstPage(
                storedSuite.suiteId(), storedSuite.revision(), storedSuite.fingerprint(),
                3, 20);

TestSuiteStabilityCrossRetentionTrendAnalysis page =
        client.analyzeSuiteStabilityCrossRetentionTrend(crossRetention);
TestSuiteStabilityCrossRetentionTrendEvidenceVerifier.VerificationResult verifiedPage =
        client.verifySuiteStabilityCrossRetentionTrend(crossRetention, trustedPin);
if (!verifiedPage.verified()) {
    throw new IllegalStateException(verifiedPage.reasonCode());
}

long lastSequence = page.range().entries().getLast().sequence();
TestSuiteStabilityCrossRetentionTrendRequest continuation = crossRetention.continueAfter(
        lastSequence, page.range().head().headFingerprint());
```

The verifier recomputes the exact request, trend and observation identities, compact signatures,
entry/head/range fingerprints, source-time ordering, trend labels, outer closure, and outer signature.
Use the pinned key-set overload for release-grade policy checks. Sequence zero requires a blank head
pin; every continuation requires the exact first-page head. This preview remains absent in production
and capability-disabled until external archive/erasure/recovery and witnessed non-equivocation are
complete.

After the local floor has moved, verify its complete signed retirement lifecycle before constructing
the active compact-range request:

~~~java
EvidenceVerificationKeySet keySet = client.findEvidenceVerificationKeySet();
TestSuiteStabilityObservationLedgerLifecycleEvidenceVerifier lifecycleVerifier =
        new TestSuiteStabilityObservationLedgerLifecycleEvidenceVerifier();
TestSuiteStabilityObservationLedgerLifecycleRequest lifecycleRequest =
        TestSuiteStabilityObservationLedgerLifecycleRequest.firstPage(
                storedSuite.suiteId(), storedSuite.revision(), storedSuite.fingerprint(), 10);
TestSuiteStabilityObservationLedgerLifecycleEvidenceVerifier.LifecycleCheckpoint checkpoint = null;
TestSuiteStabilityObservationLedgerLifecyclePage lifecyclePage;

while (true) {
    lifecyclePage = client.readSuiteStabilityObservationLedgerLifecyclePage(lifecycleRequest);
    var lifecycleVerification = lifecycleVerifier.verify(
            lifecyclePage, checkpoint, keySet, trustedPin);
    if (!lifecycleVerification.verified()) {
        throw new IllegalStateException(lifecycleVerification.reasonCode());
    }
    checkpoint = lifecycleVerification.checkpoint();
    if (checkpoint.complete()) {
        break;
    }
    lifecycleRequest = lifecycleRequest.continueAfter(lifecyclePage);
}

TestSuiteStabilityCrossRetentionTrendRequest retainedRange =
        new TestSuiteStabilityCrossRetentionTrendRequest(
                storedSuite.suiteId(), storedSuite.revision(), storedSuite.fingerprint(),
                lifecyclePage.currentFloor().floorSequence() - 1,
                3, 20, lifecyclePage.head().headFingerprint());
var rangeVerification = client.verifySuiteStabilityCrossRetentionTrend(
        retainedRange, trustedPin);
if (!rangeVerification.verified()) {
    throw new IllegalStateException(rangeVerification.reasonCode());
}
~~~

Construct continueAfter only after the prior page verifies. The checkpoint binds the exact suite,
scope, current floor/head snapshot, terminal generation, and terminal floor; it rejects skipped or
mixed pages. The lifecycle verifier independently rederives every compact observation, archive,
retirement, successor floor, page identity, and signature without fetching retired full stability
runs. This proves Resource Gateway's local signed chain.

Use lifecycle v2 when the gate must also verify the exact external acknowledgement that authorized
each local deletion. Archive trust comes from caller-owned policy, never from the Gateway response:

~~~java
var authority = new TestSuiteStabilityObservationExternalArchiveTrustPolicy.TrustedAuthority(
        "archive-a", "region-a", Map.of(archiveKey.keyId(), archiveKey));
var archivePolicy = new TestSuiteStabilityObservationExternalArchiveTrustPolicy(
        TestSuiteStabilityObservationExternalArchiveTrustPolicy.SCHEMA_VERSION,
        "archive.example", "archive-set-a", Set.of(approvedRetentionFingerprint),
        1, requiredRetainUntil, Map.of(authority.authorityId(), authority));

TestSuiteStabilityObservationLedgerLifecycleEvidenceVerifier.LifecycleCheckpoint checkpoint = null;
while (true) {
    var page = client.readSuiteStabilityObservationLedgerLifecycleArchivePage(lifecycleRequest);
    var verification = new TestSuiteStabilityObservationLedgerLifecycleArchiveEvidenceVerifier()
            .verify(page, checkpoint, lifecycleKeySet, trustedPin, archivePolicy);
    if (!verification.verified()) {
        throw new IllegalStateException(verification.reasonCode());
    }
    checkpoint = verification.checkpoint();
    if (checkpoint.complete()) {
        break;
    }
    lifecycleRequest = lifecycleRequest.continueAfter(page);
}
~~~

The verifier recomputes every request, receipt, receipt-set, immutable object, page, and nested
lifecycle identity, verifies external and Gateway signatures in separate trust domains, and emits no
checkpoint on policy rejection, missing keys, or cryptographic failure. Construct each continuation
only from the page already verified in that iteration. The higher-level client verification methods
provide the same direct-key and pinned-key-set flows when the caller does not also need the page.

Neither v1 nor v2 proves physical WORM durability, legal-hold/erasure handling, disaster-recovery
continuity, or global non-equivocation. Both routes share the default-disabled test/staging preview
flag, remain absent in production, and leave the advertised capability false.

For a non-blocking parent job, create one exact request and submit it with explicit retry bounds:

```java
TestSuiteStabilityJobRequest request = TestSuiteStabilityJobRequest.fixedHorizon(
        storedSuite.suiteId(),
        storedSuite.revision(),
        storedSuite.fingerprint(),
        "stability-job-ci-1842",
        10,
        Map.of("pipeline", "nightly", "buildId", "1842"),
        TestSuiteStabilityJobRequest.Priority.NORMAL,
        Instant.now().plus(Duration.ofMinutes(30)).truncatedTo(ChronoUnit.SECONDS));

TestSuiteStabilityJobSubmission admitted = client.submitSuiteStabilityJob(
        request, TestSuiteStabilityJobRetryPolicy.conservative());

TestSuiteStabilityJob terminal = client.awaitSuiteStabilityJob(
        admitted.job().jobId(), TestSuiteStabilityJobPollingPolicy.conservative());

if (terminal.status() != TestSuiteStabilityJob.Status.SUCCEEDED) {
    throw new AssertionError("stability job ended as " + terminal.status()
            + " (" + terminal.failureCode() + ")");
}

TestSuiteStabilityEvidenceVerifier.VerificationResult verified =
        client.verifySuiteStability(terminal.stabilityRunId(), trustedPin);
TestSuiteStabilityAssertions.assertReleaseEligible(
        client.findSuiteStability(terminal.stabilityRunId()), verified);
```

`TestSuiteStabilityJobRequest.statistical(...)` creates the request generation required by the
policy (v2 legacy, v3 fixed horizon, or v4 anytime-valid) and rejects a horizon that cannot support
its exact policy before network I/O. Submission requires `202` and the
canonical relative `Location`; the test-kit recalculates the nested execution fingerprint and binds
suite revision, client request id, priority, and deadline to the response. Query and cancellation
validate the strict payload-free job view and requested job id.

Both submit and cancellation have a `TestSuiteStabilityJobRetryPolicy` overload. Only
server-declared retryable `429`/`503` failures are retried, while attempt count, single delay, and
monotonic elapsed time remain bounded. A valid `Retry-After` takes precedence; an invalid or
over-bound directive stops retry rather than allowing an early request. Polling separately bounds
request count, elapsed time, ordinary interval, and server delay. It returns every terminal state,
including `FAILED`, `CANCELLED`, `EXPIRED`, and `QUARANTINED`, for caller policy; it never relabels
those states as success.

Cancel queued or running work with a distinct idempotency identity:

```java
TestSuiteStabilityJob cancelling = client.cancelSuiteStabilityJob(
        admitted.job().jobId(),
        "cancel-stability-job-ci-1842",
        TestSuiteStabilityJobRetryPolicy.conservative());
```

`CANCEL_REQUESTED` is cooperative and `COMMITTING` is already past the final cancellation point.
The payload-free job view is operational state, not signed correctness evidence. Only a successful
`stabilityRunId` fetched and independently verified against an external key-set pin can enter a
release gate.

Execute a reviewed `bloge.testSuite.v3` reference returned by the server's boundary-suite
materialization API without confusing schema admission with business execution:

```java
TestSuiteRun admissionRun = client.executeSuite(
        "loan-decision-schema-boundaries",
        materializedRevision,
        materializedFingerprint,
        "schema-admission-ci-1042",
        ResourceGatewayTestClient.SuiteStrategy.COLLECT_ALL,
        Map.of("source", "contract-regression"));

assert admissionRun.evaluationMode() == TestSuiteRun.EvaluationMode.SCHEMA_ADMISSION;
TestSuiteRunAssertions.assertAdmissionPassed(admissionRun);
assert !admissionRun.passed();             // no business graph/operator execution occurred
assert !admissionRun.promotionEligible();  // admission evidence never authorizes publication

TestSuiteRun.AdmissionCoverage coverage = admissionRun.requireAdmissionCoverage();
List<TestSuiteRun.AdmissionCaseResult> observations = admissionRun.admissionResults();

TestSuiteEvidenceBundle admissionBundle =
        client.findSuiteEvidenceBundle(admissionRun.suiteRunId());
String trustedPin = System.getenv("RESOURCE_GATEWAY_EVIDENCE_KEY_SET_PIN");
TestSuiteEvidenceVerifier.VerificationResult admissionVerification =
        client.verifySuiteEvidence(admissionRun.suiteRunId(), trustedPin);

JUnitXmlReportWriter.writeSuite(
        Path.of("target/surefire-reports/schema-admission.xml"),
        admissionRun,
        false); // promotion is deliberately blocked for admission-only evidence
```

Admission success means every stored case still belongs to the exact reviewed boundary plan and the
shared validator produced the expected outcome/codes. It does not mean the operator, DAG, assertions,
or structural/semantic coverage passed. v4 response, v3 evidence, v3 attestation, and v3 bundle are a
single generation; the schema validator and offline verifier reject mixed generations. The v3
attestation must have an empty `childEvidenceRefs` list.

Plan, freeze, execute, and independently verify a bounded property suite:

```java
JsonNode propertyPlan = client.planGraphPropertyCases(
        "loanDecisionPolicy", 918273645L, 8, 3);

ObjectNode materializationRequest = JsonNodeFactory.instance.objectNode();
materializationRequest.put("schemaVersion",
        TestingProtocol.TEST_PROPERTY_SUITE_MATERIALIZATION_REQUEST_V1);
materializationRequest.put("suiteId", "loan-decision-properties");
materializationRequest.put("classification", "INTERNAL");
materializationRequest.put("expectedTargetFingerprint",
        propertyPlan.path("target").path("fingerprint").asText());
materializationRequest.put("expectedInputSchemaFingerprint",
        propertyPlan.path("inputSchemaFingerprint").asText());
materializationRequest.put("expectedPlanFingerprint",
        propertyPlan.path("planFingerprint").asText());
materializationRequest.put("seed", 918273645L);
materializationRequest.put("trials", 8);
materializationRequest.put("maxShrinkSteps", 3);
ObjectNode propertyFixture = materializationRequest.putObject("fixtureRef");
propertyFixture.put("fixtureBundleId", stored.fixtureBundleId());
propertyFixture.put("revision", stored.revision());
propertyFixture.put("fingerprint", stored.fingerprint());
materializationRequest.put("acceptGenerationGaps", false);

JsonNode materialized = client.materializeGraphPropertySuite(
        "loanDecisionPolicy", materializationRequest);
JsonNode propertySuiteRef = materialized.path("suiteRef");
assert propertySuiteRef.path("schemaVersion").asText()
        .equals(TestingProtocol.TEST_SUITE_V4);

TestSuiteRun propertyRun = client.executeSuite(
        propertySuiteRef.path("suiteId").asText(),
        propertySuiteRef.path("revision").asLong(),
        propertySuiteRef.path("fingerprint").asText(),
        "property-ci-1842",
        ResourceGatewayTestClient.SuiteStrategy.COLLECT_ALL,
        Map.of("source", "property-regression"));

assert propertyRun.evaluationMode() == TestSuiteRun.EvaluationMode.PROPERTY_EXECUTION;
TestSuiteRunAssertions.assertPropertySatisfied(propertyRun);

TestSuiteEvidenceVerifier.VerificationResult propertyVerification =
        client.verifySuiteEvidence(propertyRun.suiteRunId());
if (!propertyVerification.verified()) {
    throw new IllegalStateException(propertyVerification.reasonCode());
}
```

The service regenerates the plan and freezes its complete root/shrink closure; there is no case
selection. The fixture must already belong to the same exact target and contain assertions. The
ordinary `TestSuiteBuilder` intentionally remains limited to V1/V2 business intents, while
`PROPERTY` is reserved for server-materialized V4. Check the capability probe before execution;
`propertySuiteExecution=true` means the isolated testing runtime can emit the complete V5/V4/V4/V4
response, evidence, attestation, and portable-bundle generation.

Property execution is bounded sampling, never an exhaustive proof. `COLLECT_ALL` runs every frozen
root and shrink candidate. `FAIL_FAST` completes the shrink path belonging to the first failing root,
then skips later roots. When a counterexample is expected, use
`TestSuiteRunAssertions.assertCounterexampleFound(propertyRun)` to obtain a payload-free reference
containing case id, input fingerprint, deterministic complexity, and
`minimalityScope=PRECOMPUTED_SHRINK_PATH`. The reference always says `globallyMinimal=false` because
the runtime proves only the smallest observed failure on the reviewed path. Abandoned checkpoints
are terminalized as incomplete evidence; the server never regenerates or reruns missing inputs during
reconciliation.

Calling any semantic requirement method emits `bloge.testSuite.v2`; builders without these methods
remain on v1:

```java
TestSuiteBuilder semanticSuite = TestSuiteBuilder.graph(target)
        .id("loan-semantic-regression")
        .addCase("prime", TestSuiteBuilder.CaseType.GOLDEN,
                Map.of("applicantId", "prime"), stored)
        .requireBranchTransferred("approve-branch",
                "/root/decision#PRIMARY", "/root/approve#PRIMARY")
        .requireDecisionRule("prime-rule", "/root/decision#PRIMARY", "/rule", "PRIME")
        .requireRetry("bureau-retry", "/root/bureau#PRIMARY", 2)
        .requireTimeout("bureau-timeout", "/root/bureau#PRIMARY", "UPSTREAM_TIMEOUT");

TestSuiteRevision storedSemanticSuite = client.registerSuite(
        "loan-semantic-regression", semanticSuite.registrationRequest());
TestSuiteRun semanticRun = client.executeSuite(
        storedSemanticSuite.suiteId(), storedSemanticSuite.revision(),
        storedSemanticSuite.fingerprint(), "pipeline-semantic-1",
        ResourceGatewayTestClient.SuiteStrategy.COLLECT_ALL, Map.of());
TestSuiteRun.SemanticCoverage semanticCoverage = semanticRun.requireSemanticCoverage();

SemanticCorrectnessWorkbook workbook = client.findSemanticCorrectnessWorkbook(
        storedSemanticSuite.suiteId(), storedSemanticSuite.revision());
workbook.requireGateReady();

// Build this value from the exact workbook manifest/evidence projection plus ANEKE's policy result.
JsonNode gateV3 = objectMapper.readTree(gateResultJson);
GovernanceGateReceipt receipt = client.submitGovernanceGateResult(gateV3);
```

The semantic workbook call uses `WORKBOOK_SYNC` and validates the independent Tool Studio schema
before projecting any field. It accepts only an exact `bloge.testSuite.v2` revision and exposes
payload-free case identities, typed requirements, signed verdict references, truncation/trust state,
and portable evidence endpoints. `READY` is a producer-side seed status, not a publish decision:
retrieve every evidence bundle used by the gate and verify it with the independently distributed
key-set pin shown below. Structural v1 is rejected rather than interpreted as empty semantic coverage.
`submitGovernanceGateResult` validates `governance-gate-result-v3.schema.json` before sending and
validates the acknowledged payload again, using the least-privilege `GOVERNANCE_GATE_FEEDBACK`
purpose. It also rejects an acknowledgement whose immutable gate id or result fingerprint differs
from the submitted decision. This is an independent protocol consumer, not a substitute for the
real ANEKE N/N-1 release matrix.

Migrate the seven built-in graph suites into the same immutable registry without parsing raw maps:

```java
TestSuiteCatalogMaterialization catalog =
        client.materializeBuiltInGraphContractCatalog();

for (TestSuiteCatalogMaterialization.SuiteAsset asset : catalog.suites()) {
    TestSuiteCatalogMaterialization.ExactSuiteRef ref = asset.suiteRef();
    System.out.println(asset.sourceSuiteId() + " -> " + ref.exactRef());
}
```

The operation is idempotent for unchanged graph dependencies and source cases. Its payload-free exact
references can be supplied directly to `executeSuite` or to the CI command below; target, descriptor,
case, intent, assertion, or policy changes produce a new immutable revision instead of overwriting
history.

For business suites, `TestSuiteRun` links each case to its child `runId`, exact fixture revision,
evidence class, assertion counters, and stable diagnostic code. Schema-admission suites instead carry
typed admission observations and deliberately blank child fields. Both projections exclude inputs,
outputs, and free-form diagnostics. Structural v2, semantic v3, and admission v4 responses expose a
generation-matched signed `CHECKPOINT` or `TERMINAL` attestation; v1 responses remain readable but
explicitly unsigned. Semantic-aware consumers call `requireSemanticCoverage()` so historical v1 fails as
`SEMANTIC_COVERAGE_UNAVAILABLE` rather than appearing empty and satisfied.
`promotionEligible()` means only that the run satisfies the suite's policy and may be submitted to a
later gate; it does not mean certified, approved, or published.

For release-grade verification, export the portable terminal bundle and one atomic key lifecycle
snapshot, then compare it with a fingerprint obtained through an independent governance channel:

```java
TestSuiteEvidenceBundle bundle = client.findSuiteEvidenceBundle(suiteRun.suiteRunId());
EvidenceVerificationKeySet keySet = client.findEvidenceVerificationKeySet();
String trustedPin = System.getenv("RESOURCE_GATEWAY_EVIDENCE_KEY_SET_PIN");
TestSuiteEvidenceVerifier.VerificationResult verification =
        new TestSuiteEvidenceVerifier().verify(bundle, keySet, trustedPin);

// Convenience form: fetch the same bundle and key set, then apply the supplied pin.
TestSuiteEvidenceVerifier.VerificationResult sameResult =
        client.verifySuiteEvidence(suiteRun.suiteRunId(), trustedPin);
```

The verifier independently recomputes the aggregate, bundle, and signature-material fingerprints,
checks the ordered child run closure, validates the signed snapshot against the external pin, and
replays activation, retirement, disablement, prospective revocation, or retroactive compromise at
the evidence signing time before verifying Ed25519. It reports only bounded reason codes. A
`CURRENT_STATE_ONLY` snapshot fails closed for release use. Reading the pin from the same HTTP
response does not create trust; use an ANEKE registry revision, protected CI configuration, or an
equivalent independent channel.

The older `findEvidenceVerificationKey` and `verify(bundle, key)` path remains useful for migration
and local diagnosis, but a single current-state key cannot prove atomic rotation or historical
revocation. The bundle uses `payloadPolicy=OMITTED`; child input/output values remain in governed
server storage. It is not a replay payload package, publish decision, or complete ANEKE workbook;
the semantic workbook projection contains references and verdicts, while this bundle supplies the
portable material that must be independently verified. See the
[key lifecycle verification record](../docs/resource-gateway-execution-data-control-plane-stage3-key-lifecycle-verification.md).

Gate a request-index format transition without trusting one load-balanced sample. The deployment
platform must provide a directly routable URI for every exact serving instance and independently
trusted policy values:

```java
Map<String, URI> servingInventory = deploymentPlatform.exactServingInstances();
String challenge = deploymentPlatform.newGateChallenge();

List<WorkerQuarantineRequestIndexReplicaProof> proofs = new ArrayList<>();
for (Map.Entry<String, URI> instance : servingInventory.entrySet()) {
    ResourceGatewayTestClient instanceClient = ResourceGatewayTestClient
            .builder(instance.getValue())
            .bearerToken(() -> System.getenv("RESOURCE_GATEWAY_MAINTENANCE_TOKEN"))
            .build();
    proofs.add(instanceClient.requestWorkerQuarantineRequestIndexReplicaProof(
            challenge,
            WorkerQuarantineRequestIndexReplicaProof.Mode.DUAL_READ_KEYED_WRITE));
}

EvidenceVerificationKeySet keySet = controlClient.findEvidenceVerificationKeySet();
WorkerQuarantineRequestIndexFleetPolicy policy =
        WorkerQuarantineRequestIndexFleetPolicy.strict(
                challenge,
                deploymentPlatform.deploymentScopeFingerprint(),
                WorkerQuarantineRequestIndexReplicaProof.Mode.DUAL_READ_KEYED_WRITE,
                deploymentPlatform.artifactFingerprint(),
                deploymentPlatform.resourceGatewayProtocolVersion(),
                servingInventory.keySet(),
                independentlyPinnedKeySetFingerprint);

WorkerQuarantineRequestIndexFleetGateVerifier.VerificationResult gate =
        new WorkerQuarantineRequestIndexFleetGateVerifier().verify(proofs, policy, keySet);
if (!gate.verified()) {
    throw new IllegalStateException(gate.reasonCode());
}
```

The verifier first requires exact set equality for `instanceId` and unique process-start UUIDs,
then validates cohort observation spread, challenge, scope, artifact, protocol, immediate predecessor
mode, DB-clock inventory, exclusive expiry, canonical material fingerprint, current active-key policy,
and every Ed25519 signature. It never discovers fleet membership. An omitted, unregistered,
partitioned, shadow, or N-1 process remains the deployment platform's responsibility; the test-kit
only proves that the complete independently supplied inventory produced one coherent valid cohort.
Run one gate per identity-derived region scope. Cross-region simultaneity remains a higher-level
release policy.

## CI Command

`clean package` produces both the library JAR and a dependency-contained
`bloge-resource-gateway-test-kit-1.0.0-cli.jar`. Credentials are accepted only through the
environment, while the exact suite identity and caller-owned idempotency key are explicit:

```bash
export RESOURCE_GATEWAY_TOKEN='<short-lived workload token>'

java -jar resource-gateway-test-kit/target/bloge-resource-gateway-test-kit-1.0.0-cli.jar \
  --base-uri http://localhost:8080 \
  --suite-id normalization-regression \
  --revision 1 \
  --fingerprint 'sha256:<64 lowercase hex characters>' \
  --client-request-id "${CI_PIPELINE_ID}-${CI_JOB_ID}" \
  --strategy COLLECT_ALL \
  --report target/test-results/resource-gateway-suite.xml
```

V5 mutation suites must opt into the dedicated endpoint and its per-mutant strategy:

```bash
java -jar resource-gateway-test-kit/target/bloge-resource-gateway-test-kit-1.0.0-cli.jar \
  --base-uri http://localhost:8080 \
  --suite-id loan-decision-mutations \
  --revision 5 \
  --fingerprint 'sha256:<64 lowercase hex characters>' \
  --client-request-id "${CI_PIPELINE_ID}-${CI_JOB_ID}-mutation" \
  --mode MUTATION \
  --strategy STOP_AFTER_KILL \
  --report target/test-results/resource-gateway-mutation.xml
```

Suite stability is a third, strategy-free mode. It requires an externally managed key-set
fingerprint rather than trusting a key set merely because the same server returned it:

```bash
export RESOURCE_GATEWAY_TRUSTED_KEY_SET_FINGERPRINT='sha256:<externally-pinned-key-set-fingerprint>'

java -jar resource-gateway-test-kit/target/bloge-resource-gateway-test-kit-1.0.0-cli.jar \
  --base-uri http://localhost:8080 \
  --suite-id normalization-regression \
  --revision 1 \
  --fingerprint 'sha256:<64 lowercase hex characters>' \
  --client-request-id "${CI_PIPELINE_ID}-${CI_JOB_ID}-stability" \
  --mode STABILITY \
  --attempts 5 \
  --report target/test-results/resource-gateway-stability.xml
```

Add both statistical coordinates to select current request v3 and the v4 exact-rate gate. When
`--attempts` is omitted, the CLI uses the exact minimum horizon; this example derives 30 executions
for 29 post-baseline comparisons:

```bash
java -jar resource-gateway-test-kit/target/bloge-resource-gateway-test-kit-1.0.0-cli.jar \
  --base-uri http://localhost:8080 \
  --suite-id normalization-regression \
  --revision 1 \
  --fingerprint 'sha256:<64 lowercase hex characters>' \
  --client-request-id "${CI_PIPELINE_ID}-${CI_JOB_ID}-statistical-stability" \
  --mode STABILITY \
  --confidence-bps 9500 \
  --max-instability-rate-bps 1000 \
  --report target/test-results/resource-gateway-statistical-stability.xml
```

Add an explicit alternative rate to select request v4 and response v5 anytime-valid execution. The
100 attempts below are a maximum; for a clean path at these coordinates the first valid boundary is
57 executions, and the CLI/JUnit report exposes both planned and observed counts:

```bash
java -jar resource-gateway-test-kit/target/bloge-resource-gateway-test-kit-1.0.0-cli.jar \
  --base-uri http://localhost:8080 \
  --suite-id normalization-regression \
  --revision 1 \
  --fingerprint 'sha256:<64 lowercase hex characters>' \
  --client-request-id "${CI_PIPELINE_ID}-${CI_JOB_ID}-anytime-stability" \
  --mode STABILITY \
  --attempts 100 \
  --confidence-bps 9500 \
  --max-instability-rate-bps 1000 \
  --alternative-instability-rate-bps 500 \
  --report target/test-results/resource-gateway-anytime-stability.xml
```

The equivalent environment option is
`RESOURCE_GATEWAY_STABILITY_ALTERNATIVE_INSTABILITY_RATE_BPS`. The CLI never chooses an alternative
implicitly; specifying it without confidence, ceiling, and maximum horizon is a configuration error.

`STANDARD` is the default mode and accepts `COLLECT_ALL` or `FAIL_FAST`. `MUTATION` accepts
`COLLECT_ALL` or `STOP_AFTER_KILL`; the latter stops only the current mutant after a signed assertion
kill and still visits every later mutant. Deterministic `STABILITY` accepts 3..20 attempts;
statistical mode is selected by the paired confidence/rate options and accepts a sufficient 3..1000
horizon. Both reject `--strategy` and `--allow-non-eligible`, and require
`--trusted-key-set-fingerprint` or
`RESOURCE_GATEWAY_TRUSTED_KEY_SET_FINGERPRINT`. A mode-incompatible option is rejected before any
network request.

The command returns:

- `0` only when the selected evaluation mode's typed verdict passes and, by default, promotion status
  is `ELIGIBLE`; mutation mode additionally requires a passing baseline and independently re-derived
  `SATISFIED` score policy, while stability mode requires `STABLE`, promotion eligibility, exact
  v2+ source-promotion closure, exact source evidence closure, and a signature rooted in the
  externally pinned key set; a configured statistical policy additionally requires independently
  re-derived v3-v5 `SATISFIED` confidence;
- `1` when governed terminal evidence was obtained but its quality, promotion, or trust gate failed;
- `2` when configuration, transport, protocol validation, report generation, or a non-terminal
  `RUNNING` checkpoint prevents a trustworthy gate verdict.

`--allow-non-eligible` disables only the promotion-eligibility requirement; the mode-specific typed
verdict must still pass. Mutation JUnit XML includes one payload-free row per baseline case and mutant,
but individual survivors are informational because the immutable aggregate score policy owns the gate
verdict. Stability JUnit XML includes one payload-free row per stability case, one pinned-trust
attestation row, and one aggregate gate row. The CLI never accepts a token argument, never generates an idempotency key implicitly, and
writes a one-test infrastructure failure report when execution fails before governed terminal suite
evidence is available. Unknown options and positional arguments are reported without echoing values.

`EXECUTABLE_UNIT` does not by itself imply certification. The server also requires a frozen
implementation closure, runtime state, and v2 composability manifest. Stateless operators satisfy
only the state-freezing condition; they do not qualify automatically. Configured operators implement
`OperatorRuntimeBindingSnapshotProvider`, while non-resource certifiable operators implement
`OperatorComposabilityManifestProvider` with a self-contained declaration and fingerprinted
conformance suite. An undeclared, unformalized stateful, or opaque binding still runs when
the plan can control it, but its evidence remains `EXPLORATORY`. `HttpResourceOperator` requires
transport-level resource fixtures so its mapping and response protocol execute for real.

Use inline fixtures only for exploratory authoring. Registered immutable
fixtures are required for certifiable evidence. Resource fixtures that need to
prove response protocol and payload extraction behavior should use
`protocolResponse`; `returnValue` is an output-level double and cannot by itself
earn certifiable evidence for a resource site.

The typed summaries retain `invocationSiteId`, `graphPath`, `correlationKey`,
site `occurrence`, containing `graphOccurrence`, retry attempts, and edge
endpoints. They intentionally omit node/attempt/edge payload values; use
`rawResponse()` only in an explicitly authorized diagnostic path when sanitized
payload inspection is required. Producers that predate occurrence coordinates
remain readable and project zero coordinates plus empty attempt/edge lists.

Current `bloge.testRunEvidence.v2` also carries `semanticResultFingerprint`. It identifies stable
business outcomes across equivalent deterministic runs while complete evidence fingerprints remain
unique. Historical evidence v1 remains readable but has no semantic identity;
`assertSameSemanticResult` fails closed when the baseline fingerprint is absent. `STANDARD` and
`SUMMARY` expose this value as signed full-evidence lineage, not as independently recomputable proof.

For timeout, retry, fallback, or time-dependent business rules, declare one
run-scoped logical clock and use `delay` or `timeout`:

```java
FixtureBundleBuilder timeoutFixture = FixtureBundleBuilder
        .graph(target.graphId(), target.fingerprint())
        .id("loan-provider-timeout")
        .logicalClock(Instant.parse("2026-07-15T09:00:00Z"))
        .rule("provider-timeout")
            .node("fetchCreditScore")
            .timeout(Duration.ofSeconds(3),
                    "CREDIT_BUREAU_TIMEOUT",
                    "credit bureau did not answer")
            .requiredUses(2, 2)
            .add();
```

`requiredUses(2, 2)` proves that a graph configured for one retry consumed the
timeout twice. `delay(after, value)` advances the same logical clock and then
returns a fixed schema-gated value. Both controls are node-boundary controls,
require `logicalClock`, reject durations over 365 days, and consume no wall time.
They verify retry/fallback and time-dependent business semantics, not real
watchdog timing or thread interruption.

Use separate rules when each retry attempt or nested graph re-entry needs different behavior:

```java
FixtureBundleBuilder scriptedRetry = FixtureBundleBuilder
        .graph(target.graphId(), target.fingerprint())
        .id("scripted-retry")
        .logicalClock(Instant.parse("2026-07-15T09:00:00Z"))
        .rule("first-attempt-times-out")
            .node("fetchCreditScore")
            .attempts(1)
            .timeout(Duration.ofSeconds(3))
            .add()
        .rule("second-attempt-recovers")
            .node("fetchCreditScore")
            .attempts(2)
            .returnValue(Map.of("score", 780))
            .add();
```

`attempts(...)` and `occurrences(...)` canonicalize their arguments as sorted one-based sets.
Attempts count delegate calls within one occurrence; occurrences count repeated bindings for one
site and correlation key. The dimensions are ANDed when both are present. Overlapping rules at the
same precedence are rejected before execution, and a coordinate with no matching rule follows the
declared unmatched policy, which defaults to fail closed.

## Security Defaults

- A fresh bearer token is requested from the provider for each HTTP call.
- Every operation sends an explicit least-privilege `X-Purpose` and correlation
  id.
- Redirects are disabled by the default client.
- Request and response bodies default to a 16 MiB hard limit.
- Exceptions and JUnit XML omit credentials, request bodies, node input/output,
  and problem `details`; use the run/correlation id for authorized diagnosis.
- Unknown response protocol versions fail immediately.
- Current v2 child runs require a structurally consistent versioned integrity manifest. Structural
  suite response v2 and semantic response v3 require generation-matched signed checkpoint or
  terminal attestations. Historical v1 suite responses are accepted only as unsigned migration data
  and cannot be exported as trusted terminal bundles.
- Release-grade offline verification pins the signed atomic key-set fingerprint and reconstructs
  ACTIVE, retirement, disable, and prospective/retroactive revocation at evidence signing time.
  Exact-key lookup remains a migration/diagnostic path. Missing keys, pin mismatch, stale policy,
  invalid signatures, and malformed material fail closed without echoing evidence payloads.
- Suite requests and responses are validated against the exact packaged JSON Schema; returned suite
  id, revision, fingerprint, run id, and `clientRequestId` are rebound to the originating request.
- Suite execution requires an exact positive revision, full lowercase SHA-256 fingerprint, and
  explicit `clientRequestId` before any network call.

The packaged schema is available at `TestingProtocol.SCHEMA_RESOURCE`; `clean verify` also fails on
public JavaDoc warnings so the client contract cannot silently lose parameter semantics. Full
server endpoint, identity, and profile requirements are documented in
[`docs/resource-gateway-testing-control-plane-api.md`](../docs/resource-gateway-testing-control-plane-api.md).
