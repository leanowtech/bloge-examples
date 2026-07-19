# Stage 5 statistical suite-stability design

**Implementation status (2026-07-19): implemented.** The current generation is request v3 with
evidence, attestation, and response v4. It uses an exact baseline-conditional binomial rate bound,
immutable signed evidence, strict Schema, capability discovery, independent test-kit re-derivation,
pinned-key verification, payload-free JUnit, and a fail-closed CLI gate. Historical request v2 and
v3 evidence remain readable and verifiable with their original zero-event semantics; they are not
silently reinterpreted as v4.

## 1. Decision

Resource Gateway exposes statistical repeatability as an opt-in, versioned evidence protocol above
deterministic suite stability. The current model freezes a fixed execution horizon before attempt
one, reserves the first verified suite outcome vector as the observed baseline, and treats each
later verified comparison as one Bernoulli trial. It reports an upward-rounded, one-sided exact
Clopper-Pearson instability-rate bound.

The signed claim is deliberately conditional:

> Given the disclosed exchangeability and stationarity assumptions, and conditional on the first
> verified vector being the observed baseline, the complete uncensored sample admits instability
> rates no greater than the configured ceiling at the configured confidence level.

This is repeatability evidence for one bounded execution regime. It is not proof of business
correctness, stochastic independence, future behavior, a production SLO, or release eligibility.

## 2. Root problem and v3 correction

Deterministic evidence proves what happened in a fixed sample but cannot by itself quantify the rate
of behavior changes. Historical statistical v3 added an exact zero-event claim, but its event was
defined by comparing later vectors with the first verified vector. With `n` complete attempts, the
first vector establishes the baseline and only `n - 1` Bernoulli comparisons exist. Computing the
v3 zero-event confidence with exponent `n` therefore overstates the sample by one trial.

Changing v3 arithmetic in place would make old signed evidence non-reproducible. The correction is
an additive protocol generation:

1. v3 keeps its historical wire meaning and remains independently verifiable.
2. New statistical requests use `BASELINE_CONDITIONAL_EXACT_BINOMIAL` only.
3. v4 signs `comparisonAttempts = verifiedAttempts - 1` and the exact upper rate bound.
4. Producer and independent test-kit both re-derive counts, bounds, verdicts, and signatures.
5. Model/version cross-pairs are rejected, preventing downgrade or semantic aliasing.

The protocol fixes the cause, not merely the displayed confidence field: trial identity, sample
size, estimator, assumptions, censoring, and consumer verification now share one versioned contract.

## 3. Event and claim scope

Each complete `COLLECT_ALL` suite execution produces an ordered vector of every case's verified
`evidenceStatus + semanticResultFingerprint` identity. The first verified vector is the baseline. A
later verified vector differing in any coordinate is one suite-level instability event.

The claim scope is `SUITE_ATTEMPT_ANY_CASE`. A change in any case contributes one event for that
comparison; case count does not multiply the number of independent confidence claims. Per-case
statuses remain deterministic diagnostics.

For `n` verified attempts:

```text
comparisonAttempts = n - 1
observedInstabilityEvents = count(laterVector != baselineVector)
```

The first verified attempt can never be counted as both baseline and comparison. The legacy
single-argument confidence API rejects the corrected model to prevent accidental reuse of the v3
`n` convention.

## 4. Exact fixed-horizon inference

The request freezes:

- confidence level `C` in basis points;
- maximum admitted conditional instability rate `q` in basis points;
- execution horizon `n`;
- model `BASELINE_CONDITIONAL_EXACT_BINOMIAL`;
- scope `SUITE_ATTEMPT_ANY_CASE`;
- stopping rule `PRECOMMITTED_FIXED_HORIZON`;
- censoring policy `FAIL_CLOSED`.

Let `m = n - 1` comparisons, `k` observed events, and `alpha = 1 - C`. Admission at the configured
ceiling is the exact lower-tail test:

```text
P[X <= k | X ~ Binomial(m, q)] <= alpha
```

The reported `upperInstabilityRateBps` is the smallest basis-point rate whose exact lower tail is no
larger than `alpha`; integer rounding is upward and can never make the release decision more
favorable. `achievedConfidenceBps` is the conservative floor of `1 - P[X <= k | q]` for display.
All powers, combinations, CDF sums, admission comparisons, and binary-search decisions use
`BigInteger`; floating-point rounding cannot affect evidence.

For a 95% confidence target and 10% ceiling:

| Executions | Comparisons | Events | Upper bound | Achieved floor | Statistical result |
| ---: | ---: | ---: | ---: | ---: | --- |
| 29 | 28 | 0 | above 10% | below 95% | request horizon rejected |
| 30 | 29 | 0 | 9.82% | 95.28% | `SATISFIED` |
| 30 | 29 | 1 | 15.34% | 80.11% | `REJECTED` |
| 60 | 59 | 1 | 7.79% | 98.49% | `SATISFIED` statistically |

The last row is intentionally important: a non-zero event can fit a declared rate ceiling, while
the same evidence is deterministically `FLAKY`, promotion-blocked, and quarantine-required.
Statistical tolerance cannot launder an observed correctness variant.

The minimum horizon still uses the zero-event planning case before execution. At 95%/10%, v3
historically required 29 attempts; corrected v4 requires 30 executions for 29 comparisons.

## 5. Fixed stopping and censoring

The complete horizon is precommitted and executed even after an event. The only stop reason is
`FIXED_HORIZON_REACHED`. This prevents choosing a favorable prefix or repeatedly sampling until a
green result appears.

A source/child signature failure, missing closure, source or child reuse, effective-plan drift, or
incomplete terminal evidence censors the attempt. Under v4 `FAIL_CLOSED` semantics, any censoring
produces:

- statistical status `INCONCLUSIVE`;
- `achievedConfidenceBps = 0`;
- no `upperInstabilityRateBps`;
- no denominator repair, replacement attempt, or favorable partial inference.

Historical v3 retains its signed generation semantics, including its original event/censor
precedence. Consumers must branch by exact evidence version instead of projecting old evidence into
the corrected model.

## 6. Protocol generations

| Object | Historical statistical | Current statistical | Compatibility rule |
| --- | --- | --- | --- |
| request | `bloge.testSuiteStabilityExecutionRequest.v2` | `...Request.v3` | v1 remains deterministic |
| evidence | `bloge.testSuiteStabilityEvidence.v3` | `...Evidence.v4` | v1/v2/v3 remain readable |
| attestation | `bloge.testSuiteStabilityAttestation.v3` | `...Attestation.v4` | must match evidence |
| response | `bloge.testSuiteStabilityExecutionResponse.v3` | `...Response.v4` | must match evidence/attestation |

Request v2 accepts only `ZERO_INSTABILITY_EXACT_BINOMIAL`. Request v3 accepts only
`BASELINE_CONDITIONAL_EXACT_BINOMIAL`. V4 assessment additionally requires:

- `comparisonAttempts`;
- `upperInstabilityRateBps` for complete uncensored samples;
- the two baseline-conditional assumption codes;
- a status consistent with the exact interval and censoring state.

The signed canonical evidence binds the policy, minimum horizon, observed/verified/censored counts,
event count, comparison count, confidence floor, optional upper bound, status, stop reason, and
assumptions. Attestation binds request fingerprint, evidence fingerprint, and ordered source
promotion closure.

## 7. Verdict and correctness boundaries

For complete uncensored v4 evidence:

| Status | Exact derivation |
| --- | --- |
| `SATISFIED` | the one-sided exact upper rate bound is no greater than the configured ceiling |
| `REJECTED` | the exact upper rate bound exceeds the configured ceiling |
| `INCONCLUSIVE` | one or more requested attempts are censored |

Promotion remains an intersection, not a statistical shortcut. It requires aggregate `STABLE`, all
source promotions `ELIGIBLE`, complete verified closure, satisfied statistical assessment, and
valid pinned trust. `FLAKY`, `CONSISTENT_FAILURE`, and source-promotion blocking remain decisive even
when the statistical rate assessment is satisfied. Quarantine is a signed recommendation and never
changes business outcomes.

## 8. Assumptions and claim limits

Every v4 assessment signs these disclosures:

- `ATTEMPTS_EXCHANGEABLE_WITHIN_ANALYSIS_WINDOW`;
- `EXECUTION_REGIME_STATIONARY_WITHIN_ANALYSIS_WINDOW`;
- `NO_UNOBSERVED_COMMON_CAUSE_CLAIM`;
- `OUTCOME_EVENT_DETECTION_BY_SEMANTIC_FINGERPRINT`;
- `BASELINE_IS_FIRST_VERIFIED_ATTEMPT`;
- `RATE_IS_CONDITIONAL_ON_OBSERVED_BASELINE`.

Distinct durable run identities prevent literal duplicate counting; they do not prove independence.
The negative common-cause code is deliberate: a shared dependency, cache, scheduler, test fixture,
or environment epoch may correlate attempts.

## 9. Bounded execution and independent verification

Statistical requests allow 3..1000 executions and at most 10,000 attempt-by-case observations.
Confidence is bounded to 5000..9999 basis points and the rate ceiling to 1..5000 basis points.
Unreachable horizons, unsupported policy coordinates, mixed generations, suite drift, oversized
metadata, or work-budget excess fail before execution.

The independent test kit does not trust producer labels. It reconstructs attempt vectors and source
promotion closure, recomputes exact integer CDFs and upper bounds, re-derives deterministic and
statistical verdicts, checks strict Schema, and verifies the v4 Ed25519 signature against an
externally pinned key set. Payload-free JUnit and CLI output include model, comparison count, event
count, upper bound, promotion, quarantine, and trust verdicts.

Required regression anchors include 30/0, 30/1, and 60/1 samples; censoring; consistent failure;
source-promotion blocking; forged counts, bounds, assumptions, model/version pairs, fingerprints,
and signatures; historical v1-v3 canonical compatibility; persistence round trips; capability
truth; CLI/JUnit behavior; JavaDoc; and both full Maven gates.

## 10. Deliberately unclaimed

This generation does not claim:

- sequential/adaptive stopping or alpha-spending correctness;
- historical drift or correlated common-cause detection across environment epochs;
- proof of independence, stationarity, or baseline representativeness;
- automatic quarantine mutation, remediation ownership, approval, or expiry;
- distributed attempt-level scheduling or physically isolated runtime certification;
- long-duration soak, capacity, chaos, or non-H2 database certification.

Sequential sampling requires a new protocol generation with a precommitted spending schedule and an
independent verifier. It must not be introduced as a runtime optimization under this fixed-horizon
contract.

The selected next-generation design uses an anytime-valid likelihood-ratio e-process instead of a
finite alpha-spending look schedule. Its exact arithmetic foundation and required protocol/runtime
invariants are specified in
[Stage 5 anytime-valid suite-stability design](resource-gateway-execution-data-control-plane-stage5-anytime-valid-stability-design.md).
