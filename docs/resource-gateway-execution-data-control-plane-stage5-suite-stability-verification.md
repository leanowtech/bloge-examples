# Stage 5 bounded suite-stability verification

## 1. Verified claim

Resource Gateway can execute one exact immutable executable suite under either a 3..20-attempt
deterministic contract or a 3..1000-attempt precommitted statistical contract, compare every case by
verified semantic outcome rather than run-local identity, retain signed payload-free evidence, and
let an independent CI consumer re-derive the complete analysis against an externally pinned atomic
key set.

The claim is intentionally bounded:

1. V1 structural, V2 semantic, and V4 property suites are supported because they produce executable
   child evidence. V3 schema-admission and V5 mutation suites are rejected.
2. Attempts are fixed before execution and use `COLLECT_ALL`. Request v1 is limited to 3..20;
   statistical requests v2/v3 are limited to 3..1000 and `attempts * caseCount <= 10000`.
3. Deterministic stability means invariant observed behavior. Current statistical v4 proves an
   exact baseline-conditional rate bound, including non-zero event samples, under its signed
   assumptions; neither is proof about all future executions.
4. Stability is necessary but not sufficient for promotion. Every verified source suite must also
   carry an `ELIGIBLE` promotion verdict in v2/v3/v4 evidence; statistical evidence must also
   satisfy its generation-specific exact assessment.
5. Quarantine is a signed recommendation only. This increment does not mutate suite state, waive a
   failure, authorize publication, or call an external governance system.
6. Every verified source reference is durably checkpointed before the next attempt. A successor
   verifies that prefix and schedules only the remaining horizon.

## 2. Exact request and idempotency boundary

`POST /api/testing/suites/{suiteId}/stability-executions` accepts deterministic
`bloge.testSuiteStabilityExecutionRequest.v1` and statistical requests v2/v3 under `TEST_EXECUTION` or,
for governed replay fixtures, `TEST_REPLAY`. Both requests bind:

- exact suite id, revision, and fingerprint;
- caller-owned parent `clientRequestId`;
- exact attempt count;
- bounded scalar provenance metadata.

Historical request v2 requires `ZERO_INSTABILITY_EXACT_BINOMIAL`. Current request v3 requires
`BASELINE_CONDITIONAL_EXACT_BINOMIAL`. Both bind `SUITE_ATTEMPT_ANY_CASE`,
`PRECOMMITTED_FIXED_HORIZON`, `FAIL_CLOSED`, confidence basis points, and an admitted
instability-rate ceiling. The exact generation-specific horizon inequality is evaluated before
attempt one, and mixed request/model generations are rejected.

The service rejects an unsupported suite generation, stale suite fingerprint, purpose or clearance
mismatch, production identity, nested/oversized metadata, version-specific attempt/work bounds, an
insufficient exact horizon, and unsupported probability coordinates before creating evidence. Parent
request identity is tenant/environment scoped and content
addressed. Same key plus same intent returns the retained result; same key plus different intent is a
conflict.

Before attempt one, the service now acquires a database-clock parent execution lease bound to that
same scope and request fingerprint. A concurrent immutable duplicate receives retryable `429`
without scheduling a child. An expired owner may be replaced only by an epoch-incrementing takeover;
the service renews before every new attempt and immediately before terminal publication. Each
verified source reference and lease renewal commit atomically; terminal insert, full-journal
validation, and exact progress/lease consumption share one transaction. Detailed evidence is in
[Stage 5 suite-stability execution-lease verification](resource-gateway-execution-data-control-plane-stage5-suite-stability-execution-lease-verification.md).
Crash-prefix reconstruction and the public progress projection are verified in
[Stage 5 suite-stability durable parent progress verification](resource-gateway-execution-data-control-plane-stage5-suite-stability-durable-progress-verification.md).

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

For current statistical v4, the first verified ordered suite vector is a baseline, not a Bernoulli
trial. Each later verified vector contributes one comparison and one event when it differs in any
case. A complete uncensored sample is `SATISFIED` when its upward-rounded one-sided exact
Clopper-Pearson upper rate bound is no greater than the configured ceiling, and `REJECTED`
otherwise. Any censoring is `INCONCLUSIVE`, forces achieved confidence to zero, and withholds the
upper bound. Historical v3 preserves its original zero-event and censor-precedence semantics so old
signatures remain reproducible; it is never projected into v4.

## 5. Signed evidence, retention, and query

The public generations are:

| Object | Version |
| --- | --- |
| request | deterministic v1; legacy statistical v2; current statistical v3 |
| evidence | audit v1; deterministic source-closed v2; legacy statistical v3; current statistical v4 |
| attestation | v1/v2/v3/v4 matching the evidence generation |
| response | v1/v2/v3/v4 matching evidence and attestation |

The stability-specific signing domain binds the canonical parent request fingerprint, canonical
evidence fingerprint, and exact ordered `(attempt, suiteRunId, aggregateEvidenceFingerprint,
sourcePromotionStatus, sourcePromotionReasons)` source closure. Only a complete verified terminal
signature may enter the immutable stability store. Statistical evidence binds the policy, derived
minimum/observed/verified/censored counts, instability events, conservative achieved confidence,
status, fixed stop reason, and explicit assumptions. V4 additionally binds the post-baseline
comparison count and conservative exact upper rate bound; censored v4 evidence must omit that bound.
`GET /api/testing/stability-executions/{stabilityRunId}` verifies stored fingerprints and signature
again before returning the result.

The JDBC repository is independent from ordinary suite-run tables and enforces one immutable parent
request identity per tenant/environment. Payload-free progress and execution-lease tables,
fixed-cardinality lock stripes, database time, owner/epoch fencing, and bounded expired-orphan lease
cleanup prevent two replicas from executing or publishing the same live parent intent. Stability terminal retention uses
`gateway.testing.store.retention-days` and is capped from the earliest source start. The service
refuses to persist an analysis when the source retention window can no longer support it. Historical
v1 decode/encode preserves its canonical JSON and fingerprint; v1 may be queried and verified for
audit, but cannot enter a release gate because it lacks source-promotion closure. Generation one has
no terminal-evidence retention sweeper or legal-hold workflow; database expiry is a protocol bound,
not yet a complete physical-deletion proof. Lease cleanup does not change that terminal-retention
limitation.

## 6. Public Schema and capability discovery

The authoritative Draft 2020-12 testing Schema has strict N/N-1 branches and rejects unknown fields,
invalid or mixed generations, malformed fingerprints, incomplete case/source closures, invalid
status/confidence combinations, and unsigned terminal responses. Capability discovery advertises
request v1/v2/v3 and response-side v1/v2/v3/v4, both HTTP endpoints, and independent feature flags:

- `signedSuiteStabilityAnalysis`
- `idempotentSuiteStabilityRerun`
- `exactBinomialSuiteStabilityConfidence`
- `baselineConditionalSuiteStabilityRateBound`
- `nonZeroSuiteStabilityRateInterval`
- `sequentialSuiteStabilityAlphaSpending` (`false` in this generation)
- `crossReplicaSuiteStabilityExecutionLease`
- `durableSuiteStabilityParentProgress`

The supported objects and endpoints appear only when the isolated test execution surface is
assembled. These feature flags become `true` only when that surface and a signer are available.
Production profile omission remains the security boundary; a feature flag is not authorization.

## 7. Independent consumer and CI gate

`resource-gateway-test-kit` packages the authoritative Schema without depending on the Spring Boot
server. `TestSuiteStabilityRun` independently re-derives case classification, aggregate status,
counts, source-promotion closure, promotion/quarantine verdicts, diagnostics, timestamps, canonical
evidence fingerprint, and the ordered source-reference closure embedded in the signed analysis. For
v3/v4 it also reconstructs every suite-attempt vector, exact integer horizon, censor count, event
count, achieved confidence, assumptions, assessment, and statistical promotion. For v4 it
independently recomputes `verifiedAttempts - 1`, the exact binomial CDF, and the upward-rounded upper
rate bound. A producer-supplied label
that disagrees with those facts is rejected during projection.

`TestSuiteStabilityEvidenceVerifier` then recomputes signature material, applies signing-time key
lifecycle policy, verifies the Ed25519 signature, and can require an atomic key-set fingerprint
obtained through an independent channel. This consumer verifies the signed stability projection. A
valid v1 signature remains `VERIFIED` for audit, while
`sourcePromotionClosureAvailable=false` forces assertions, JUnit, and CLI release gates closed. It
does not re-execute the suite or independently refetch every source child, which remains a server-side
evidence-admission responsibility.

The shaded CLI exposes explicit `--mode STABILITY` and requires an external key-set pin. Without
statistical options it retains deterministic 3..20 behavior. Supplying both `--confidence-bps` and
`--max-instability-rate-bps` selects current request v3; omitted `--attempts` defaults to the exact minimum
horizon. The CLI rejects partial policy configuration, insufficient horizons, `--strategy`, and
`--allow-non-eligible`. It emits one payload-free JUnit row per case, one pinned-trust attestation
row, and one aggregate gate row. Exit semantics are:

- `0`: requested deterministic or statistical gate, source-promotion closure, correctness,
  promotion, exact evidence semantics, and pinned trust all pass;
- `1`: terminal governed evidence exists, but a stability, promotion, or trust gate fails;
- `2`: configuration, transport, protocol, or infrastructure prevents a trustworthy verdict.

## 8. Verification performed

Focused Resource Gateway protocol/application command:

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=TestSuiteStabilityEvidenceEvaluatorTest,TestSuiteStabilityAttestationServiceTest,\
TestSuiteStabilityExecutionServiceTest,TestSuiteStabilityControllerTest,\
TestingControlProtocolSchemaTest,TestabilityCapabilitiesTest,\
DatabaseTestSuiteStabilityRunRepositoryTest,TestSuiteStabilityLeaseCoordinatorTest,\
TestSuiteStabilityLeaseRetentionSchedulerTest,TestSuiteStabilityStatisticalPolicyTest,\
TestRuntimeApplicationIntegrationTest test
```

The focused stability set covers stable, flaky, consistent-failure, and inconclusive outcomes;
30/0, 30/1, and 60/1 exact interval anchors; plan/source/child drift; signature failure; exact
idempotency; model/version mismatch; source-promotion blocking; fixed-horizon censoring; forged
comparison counts and rate bounds; v1-v3 canonical compatibility; active-owner exclusion;
database-clock takeover; stale-fence rejection; persistence round trips; strict Schema; and
capability/endpoint/Spring-wiring truth.

Full Resource Gateway command:

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
```

Result on 2026-07-19: **2766 tests, 0 failures, 0 errors, 2 conditional skips**, followed by
successful configured browser regression and Spring Boot executable-JAR packaging.

Full independent test-kit command:

```bash
mvn -f resource-gateway-test-kit/pom.xml clean verify
```

Result on 2026-07-19: **175 tests, 0 failures, 0 errors, 0 skips**, followed by authoritative Schema
packaging, normal JAR, shaded CLI JAR, and strict public JavaDoc verification. Stability coverage
includes typed re-derivation, exact statistical policy arithmetic, source-promotion laundering
rejection, v1 audit-only compatibility, v3/v4 model and confidence forgery rejection, v4 comparison
count and upper-bound reconstruction, censored and consistent-failure semantics, invalid closures,
wrong/missing key-set pins, attempt/work bounds, assertions, payload-free JUnit cardinality, and
`0/1/2` CLI behavior.

## 9. Deliberately unclaimed work

1. Adaptive/alpha-spending stopping or historical/correlated common-cause trend analysis.
2. Automatic quarantine state mutation, expiry, owner approval, remediation, or ANEKE gate feedback.
3. Cross-replica parent ownership and prefix recovery are closed, but asynchronous submission,
   distributed attempt scheduling, durable queueing, fairness, backpressure, cancellation, or
   autoscaling remain absent.
4. Physical test-runtime, network, identity, secret, and data-store isolation proof.
5. Independent client refetch and verification of every source aggregate and child evidence bundle.
6. Bounded physical progress/terminal deletion, non-H2 dialect certification, long-duration soak,
   capacity, chaos, or disaster-recovery proof.

These gaps remain visible because a bounded repeated result is useful evidence, but it is not a
complete enterprise flaky-test management system.
