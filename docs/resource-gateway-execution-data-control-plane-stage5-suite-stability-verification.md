# Stage 5 bounded suite-stability verification

## 1. Verified claim

Resource Gateway can execute one exact immutable executable suite 3..20 times, compare every case by
verified semantic outcome rather than run-local identity, retain a signed payload-free stability
analysis, and let an independent CI consumer re-derive that analysis and verify it against an
externally pinned atomic key set.

The claim is intentionally bounded:

1. V1 structural, V2 semantic, and V4 property suites are supported because they produce executable
   child evidence. V3 schema-admission and V5 mutation suites are rejected.
2. Attempts are fixed before execution, use `COLLECT_ALL`, and are limited to 3..20.
3. Stability means invariant observed behavior under one exact suite and effective-plan closure. It
   is not a probability estimate or proof about all future executions.
4. Stability is necessary but not sufficient for promotion. Every verified source suite must also
   carry an `ELIGIBLE` promotion verdict in v2 evidence.
5. Quarantine is a signed recommendation only. This increment does not mutate suite state, waive a
   failure, authorize publication, or call an external governance system.

## 2. Exact request and idempotency boundary

`POST /api/testing/suites/{suiteId}/stability-executions` accepts only
`bloge.testSuiteStabilityExecutionRequest.v1` under `TEST_EXECUTION` or, for governed replay
fixtures, `TEST_REPLAY`. The request binds:

- exact suite id, revision, and fingerprint;
- caller-owned parent `clientRequestId`;
- exact attempt count;
- bounded scalar provenance metadata.

The service rejects an unsupported suite generation, stale suite fingerprint, purpose or clearance
mismatch, production identity, nested/oversized metadata, and attempt counts outside 3..20 before
creating stability evidence. Parent request identity is tenant/environment scoped and content
addressed. Same key plus same intent returns the retained result; same key plus different intent is a
conflict.

Each attempt receives a server-derived idempotency key under the parent namespace and executes the
ordinary immutable suite through `COLLECT_ALL`. A parent retry can therefore reuse already committed
source suite runs without silently changing the requested sample count.

## 3. Observation and classification semantics

For every suite case and attempt, the server resolves the exact signed source aggregate and complete
child evidence. A verified observation binds:

- source suite run and aggregate-evidence fingerprint;
- source suite promotion status and bounded reason closure;
- child run and complete-evidence fingerprint;
- child evidence status and evidence class;
- exact fixture and effective-plan fingerprints;
- signed semantic-result fingerprint.

The compared outcome identity is `evidenceStatus + semanticResultFingerprint`. Run ids, timestamps,
durations, and complete evidence fingerprints are deliberately excluded because independent runs
must have fresh operational identities.

| Case status | Derivation |
| --- | --- |
| `STABLE_PASS` | Every requested observation is verified, passing, and has one outcome identity |
| `CONSISTENT_FAILURE` | Every observation is verified, failing, and has one outcome identity |
| `FLAKY` | At least two verified observations have different outcome identities |
| `INCONCLUSIVE` | The available closure cannot prove one of the preceding states |

Aggregate precedence is `FLAKY`, `INCONCLUSIVE`, `CONSISTENT_FAILURE`, then `STABLE`. Thus two proven
variants remain flaky even if another attempt is unavailable, while plan drift invalidates
comparability and makes affected observations inconclusive. Aggregate `STABLE` is a necessary
promotion condition, not a sufficient one. If any verified source suite is promotion-blocked, the
analysis remains `STABLE` but promotion is `BLOCKED` with `SOURCE_SUITE_PROMOTION_BLOCKED`.
Consistent failure is repeatable but still wrong.

## 4. Independence and fail-closed rules

An analysis cannot claim stability when independence or comparability is missing:

- a source suite run id may appear in only one attempt;
- a child run id may appear in only one case/attempt observation;
- source suite/target/case coordinates and source aggregate attestations must match exactly;
- every child ref must resolve to matching full evidence with verified integrity;
- each case must preserve its exact fixture and effective-plan fingerprint across attempts;
- all requested attempt and case coordinates must be represented.

Source reuse, child reuse, signature/fingerprint mismatch, missing evidence, and effective-plan drift
produce bounded diagnostic codes and block promotion. A producer cannot turn missing proof into a
stable result by omitting an attempt or returning only aggregate pass counts.

## 5. Signed evidence, retention, and query

The public generations are:

| Object | Version |
| --- | --- |
| request | `bloge.testSuiteStabilityExecutionRequest.v1` |
| evidence | current `bloge.testSuiteStabilityEvidence.v2`; audit-compatible v1 |
| attestation | current `bloge.testSuiteStabilityAttestation.v2`; audit-compatible v1 |
| response | current `bloge.testSuiteStabilityExecutionResponse.v2`; audit-compatible v1 |

The stability-specific v2 signing domain binds the canonical parent request fingerprint, canonical
evidence fingerprint, and exact ordered `(attempt, suiteRunId, aggregateEvidenceFingerprint,
sourcePromotionStatus, sourcePromotionReasons)` source closure. Only a complete verified terminal
signature may enter the immutable stability store.
`GET /api/testing/stability-executions/{stabilityRunId}` verifies stored fingerprints and signature
again before returning the result.

The JDBC repository is independent from ordinary suite-run tables and enforces one immutable parent
request identity per tenant/environment. Stability retention uses
`gateway.testing.store.retention-days` and is capped from the earliest source start. The service
refuses to persist an analysis when the source retention window can no longer support it. Historical
v1 decode/encode preserves its canonical JSON and fingerprint; v1 may be queried and verified for
audit, but cannot enter a release gate because it lacks source-promotion closure. Generation one has
no stability-retention sweeper or legal-hold workflow; database expiry is a protocol bound,
not yet a complete physical-deletion proof.

## 6. Public Schema and capability discovery

The authoritative Draft 2020-12 testing Schema has strict N/N-1 branches and rejects unknown fields,
invalid or mixed generations, malformed fingerprints, incomplete case/source closures, invalid
status combinations, and unsigned terminal responses. Capability discovery advertises v1 and v2
for the three response-side objects, both HTTP endpoints, and two
independent feature flags:

- `signedSuiteStabilityAnalysis`
- `idempotentSuiteStabilityRerun`

The supported objects and endpoints appear only when the isolated test execution surface is
assembled. Both feature flags become `true` only when that surface and a signer are available.
Production profile omission remains the security boundary; a feature flag is not authorization.

## 7. Independent consumer and CI gate

`resource-gateway-test-kit` packages the authoritative Schema without depending on the Spring Boot
server. `TestSuiteStabilityRun` independently re-derives case classification, aggregate status,
counts, source-promotion closure, promotion/quarantine verdicts, diagnostics, timestamps, canonical
evidence fingerprint, and the ordered source-reference closure embedded in the signed analysis. A producer-supplied aggregate
label that disagrees with those facts is rejected during projection.

`TestSuiteStabilityEvidenceVerifier` then recomputes signature material, applies signing-time key
lifecycle policy, verifies the Ed25519 signature, and can require an atomic key-set fingerprint
obtained through an independent channel. This consumer verifies the signed stability projection. A
valid v1 signature remains `VERIFIED` for audit, while
`sourcePromotionClosureAvailable=false` forces assertions, JUnit, and CLI release gates closed. It
does not re-execute the suite or independently refetch every source child, which remains a server-side
evidence-admission responsibility.

The shaded CLI exposes explicit `--mode STABILITY`, requires 3..20 attempts and an external key-set
pin, and rejects `--strategy` and `--allow-non-eligible`. It emits one payload-free JUnit row per
case, one pinned-trust attestation row, and one aggregate gate row. Exit semantics are:

- `0`: v2 source-promotion closure available, stable, promotion eligible, exact evidence semantics
  valid, and pinned trust verified;
- `1`: terminal governed evidence exists, but a stability, promotion, or trust gate fails;
- `2`: configuration, transport, protocol, or infrastructure prevents a trustworthy verdict.

## 8. Verification performed

Focused Resource Gateway protocol/application command:

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=TestSuiteStabilityEvidenceEvaluatorTest,TestSuiteStabilityAttestationServiceTest,\
TestSuiteStabilityExecutionServiceTest,TestSuiteStabilityControllerTest,\
TestingControlProtocolSchemaTest,TestabilityCapabilitiesTest,\
DatabaseTestSuiteStabilityRunRepositoryTest test
```

Result on 2026-07-18: **36 tests, 0 failures, 0 errors, 0 skips**. The set covers stable, flaky,
consistent-failure and inconclusive outcomes; plan/source/child drift; signature failure; exact
idempotency; source-promotion blocking; v1 canonical compatibility; persistence conflicts; strict
Schema; and capability/endpoint gating.

Full Resource Gateway command:

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
```

Result on 2026-07-18: **2466 tests, 0 failures, 0 errors, 2 conditional skips**, including 34
configured browser tests, followed by successful Spring Boot executable-JAR packaging.

Full independent test-kit command:

```bash
mvn -f resource-gateway-test-kit/pom.xml clean verify
```

Result on 2026-07-18: **133 tests, 0 failures, 0 errors, 0 skips**, followed by authoritative Schema
packaging, normal JAR, shaded CLI JAR, and strict public JavaDoc verification. Stability coverage
includes typed re-derivation, source-promotion laundering rejection, v1 audit-only compatibility,
invalid closures, wrong/missing key-set pins, attempt bounds, assertions, payload-free JUnit
cardinality, and `0/1/2` CLI behavior.

## 9. Deliberately unclaimed work

1. Statistical confidence intervals, probabilistic flake rates, adaptive stopping, or historical
   trend analysis.
2. Automatic quarantine state mutation, expiry, owner approval, remediation, or ANEKE gate feedback.
3. Cross-process/distributed attempt scheduling, fairness, backpressure, cancellation, or autoscaling.
4. Physical test-runtime, network, identity, secret, and data-store isolation proof.
5. Independent client refetch and verification of every source aggregate and child evidence bundle.
6. Non-H2 dialect certification, long-duration soak, capacity, chaos, or disaster-recovery proof.

These gaps remain visible because a bounded repeated result is useful evidence, but it is not a
complete enterprise flaky-test management system.
