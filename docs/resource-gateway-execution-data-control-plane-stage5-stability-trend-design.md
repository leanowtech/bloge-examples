# Stage 5 retained-window stability trend design

**Implementation status (2026-07-19): retained-window server/test-kit v1 implemented; signed
cross-retention observation-ledger core implemented but not yet exposed as a public capability.**

## 1. Root problem

One signed suite-stability run answers a bounded question about one immutable suite revision in one
execution window. It does not answer whether behavior changed across windows, whether several cases
shifted together, or whether a new runtime plan created a regime boundary. Treating unrelated
terminal runs as a chart without a protocol would create three false claims:

1. a retained subset could be presented as complete history after older evidence expired;
2. runs with different target, fixture, or effective-plan closure could be compared as one regime;
3. coincident failures could be labelled a common cause without causal evidence.

The next protocol therefore produces a signed, independently reconstructable read model over one
exact suite revision and one closed retained-evidence window. It detects deterministic transitions
and correlation signals. It does not infer causality or mutate quarantine state.

## 2. Request boundary

`bloge.testSuiteStabilityTrendAnalysisRequest.v1` precommits:

- exact `suiteId + revision + fingerprint`;
- inclusive database-record start and exclusive end instants;
- `minimumRuns` required for a conclusion;
- `maximumRuns` hard response and verification budget.

The end instant must not be in the future. The window is bounded by the configured stability
retention and contains at most 100 terminal analyses. The repository counts the complete matching
window and expired subset before reading at most `maximumRuns` retained records in chronological
`createdAt + stabilityRunId` order. Expired rows therefore do not consume the retained-response
budget, while both retention loss and total-window truncation remain explicit.

The query is exact-suite only. Cross-revision analysis is rejected because a reused case id does not
prove that inputs, fixtures, target closure, or assertions retained the same meaning.

## 3. Completeness invariant

The repository returns three independent facts:

- retained signed source records in the requested window;
- whether any matching row is already beyond evidence retention;
- whether the matching row count exceeded `maximumRuns`.

`completeWindow` is true only when neither retention loss nor truncation occurred. A missing source
cannot be treated as a stable observation. An incomplete window remains useful for diagnostics but
its aggregate status is always `INCONCLUSIVE`.

This v1 guarantee is intentionally limited to records still represented in the current stability
store. The compact observation-ledger write path described below now exists, but the v1 endpoint
does not read it and `crossRetentionSuiteStabilityTrend` remains false. A public v2 range protocol,
ledger lifecycle policy, and independent consumer verifier are still required before the product
may claim trend continuity beyond full evidence retention.

### 3.1 Cross-retention observation-ledger core

Every newly published terminal stability run is now projected through the same pure
`TestSuiteStabilityObservationProjector` used by retained-window trend evaluation. The compact
`bloge.testSuiteStabilityObservationEvidence.v1` contains only scope fingerprint, exact suite ref,
source request/evidence/attestation identities, source status, target/regime fingerprints, ordered
case outcome/fixture/plan-set fingerprints, and source times. It contains no fixture value, input,
output, credential, actor, tenant id, or diagnostic payload.

The original stability attestation is verified before this projection is signed in the separate
`bloge.testSuiteStabilityObservationAttestation.v1` domain. A missing or invalid second signature
blocks terminal publication. The terminal record, compact observation entry, contiguous per-scope
sequence, predecessor observation id, and mutable ledger head are then committed in one local
transaction. A dedicated exact-scope database lock serializes concurrent replicas without placing
a potentially remote KMS call inside the transaction.

The first committed entry establishes an immutable `coverageFrom` rollout floor. The head records
the latest sequence, observation id, entry fingerprint, and database append time; every update uses
an exact predecessor CAS. Reads verify the whole-record head fingerprint, contiguous sequence and
predecessor chain, head-to-latest-row and tail-to-head closure, canonical
entry/observation/attestation fingerprints, and every indexed database column against the stored
envelope. A page is capped at the head sequence observed before the query, so a concurrent append
cannot leak into an older read snapshot; orphan rows and dangling heads fail closed. Corruption or
a ledger write failure rolls back the terminal insert, progress deletion, and lease consumption
together.

This core deliberately does not make the ledger a public proof yet. Sequence, predecessor, rollout
floor, and database append time are producer-authoritative ordering facts, while each compact
observation is independently signed. The next protocol generation must sign the exact requested
ledger range and head/floor closure, publish strict Schema, and let the test kit verify every
observation signature and reconstruct the trend. Until then, the retained v1 source-closed path is
the only advertised trend capability.

## 4. Comparable execution regime

Each source run receives a derived `regimeFingerprint` over:

```text
suite fingerprint
+ target fingerprint
+ ordered case id
+ each case's verified fixture fingerprint set
+ each case's verified effective-plan fingerprint set
```

The fingerprint contains no fixture value, context, output, credential, or diagnostic text. A plan
or fixture drift is a regime boundary, not statistical flakiness. Comparisons across distinct regime
fingerprints remain visible but cannot be used to claim a within-regime outcome transition.

## 5. Deterministic trend semantics

The aggregate status is derived with fail-closed precedence:

1. `INCONCLUSIVE`: incomplete window, fewer than `minimumRuns`, or any unverified source;
2. `INSTABILITY_OBSERVED`: at least one signed source or case is `FLAKY`;
3. `REGIME_DRIFT_OBSERVED`: all sources are conclusive but regime fingerprint changed;
4. `CONSISTENT_FAILURE_OBSERVED`: behavior is invariant but at least one source failed consistently;
5. `STABLE_PASS`: every retained source is stable passing in one regime.

Case trends use the same vocabulary and bind every contributing source run. The result is a
historical observation, not a forecast, probability bound, correctness proof, or publish decision.

## 6. Correlation without causation

V1 emits only two bounded signal types:

- `MULTI_CASE_FLAKINESS`: two or more cases are flaky in one stability run;
- `COINCIDENT_OUTCOME_SHIFT`: two or more case outcome-set fingerprints change between adjacent
  analyses inside the same regime.

Every signal names exact previous/current run ids, regime fingerprint, and sorted case ids. The
evidence fixes `causalityStatus=NOT_PROVEN`. A signal can trigger investigation or a later workbook
rule; it cannot identify a dependency, operator, deployment, team, or external system as the cause.

## 7. Signed closure

`bloge.testSuiteStabilityTrendEvidence.v1` contains:

- deterministic analysis id and canonical request fingerprint;
- exact request window and completeness facts;
- ordered source summaries with source evidence and attestation fingerprints;
- independently derivable regime/case outcome-set fingerprints;
- aggregate/case statuses, correlation signals, and bounded diagnostics.

`bloge.testSuiteStabilityTrendAttestation.v1` signs the evidence fingerprint and exact ordered source
closure in a domain separate from a single stability run. The server verifies every source
attestation before sealing. The independent test kit must fetch or receive each referenced source,
verify it against an externally pinned key set, reconstruct every summary and transition, and then
verify the trend signature. Trusting producer counters or labels is forbidden.

Independent verification has three explicit trust layers. Source evidence and its semantic labels
are independently reconstructed; trend regimes, transitions, signals, diagnostics, and aggregate
status are independently reconstructed; database `createdAt`, expired-row count, and truncation are
producer-authoritative storage observations whose signature and cross-field consistency are
verified. An offline bundle cannot honestly claim that it re-queried the producer database.

## 8. Isolation and authorization

The endpoint is available only in non-production `test` or `staging` profiles and uses a dedicated
`TEST_SUITE_STABILITY_TREND_READ` operation. Exact suite lookup applies tenant, environment,
classification clearance, and immutable fingerprint checks before repository history is read.
Responses remain payload-free.

## 9. Required negative proofs

Completion requires tests proving rejection or fail-closed projection for:

- future, reversed, overlong, undersized, and over-budget windows;
- suite path/ref/fingerprint drift and cross-scope reads;
- one expired source and one silently truncated source set;
- invalid source evidence, source attestation, or source closure fingerprint;
- fixture/plan drift incorrectly classified as flakiness;
- outcome shifts across a regime boundary incorrectly emitted as correlation;
- producer-forged aggregate, case trend, correlation signal, or source ordering;
- trend signature verification under an unpinned, unavailable, retired, or revoked key.
- compact observation signing without a valid original source attestation;
- terminal publication when the compact-observation signer is unavailable;
- cross-replica concurrent append producing duplicate sequence or broken predecessor identity;
- entry/index projection drift, missing tail, corrupt head, or ledger failure leaving a terminal
  without its compact observation.

## 10. Deliberately unclaimed

V1 does not provide cross-suite common-cause confirmation, cross-revision semantic lineage, public
cross-retention continuity, compact-ledger retention/archive/erasure policy, externally witnessed
ledger non-equivocation, automatic quarantine mutation, change-point statistics, seasonal baselines,
production-path comparison, distributed attempt execution, or physical test-runtime isolation.
Those controls remain separate because collapsing them into a read projection would make the
evidence easier to display and harder to trust.
