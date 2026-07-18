# Stage 5 statistical suite-stability design

**Implementation status (2026-07-18): implemented.** Request v2, evidence/attestation/response v3,
exact server evaluation, immutable persistence, strict Schema, capability discovery, independent
test-kit re-derivation, pinned-key verification, JUnit and opt-in CLI gate are present. The
deliberately unclaimed items in section 10 remain open work.

## 1. Decision

Resource Gateway will add an opt-in statistical generation above deterministic suite-stability
evidence. The generation uses a precommitted fixed horizon and an exact zero-instability-event
binomial bound. It does not use a producer-selected confidence label, an observed-pass percentage,
or an adaptive "stop when green" loop.

The statistical claim is deliberately precise:

> Conditional on the signed model assumptions, if the probability that one complete suite attempt
> changes its verified semantic outcome vector is at least `q`, the probability of observing zero
> changes in the precommitted horizon is at most `1 - C`.

This is a bound on repeatability under the observed execution regime. It is not a correctness proof,
a production SLO, a guarantee about future releases, or proof that the model assumptions are true.

## 2. Root problem

The current v2 evidence proves that an exact suite produced the same verified semantic outcomes in a
fixed 3..20-attempt sample. That deterministic fact is useful, but it cannot honestly answer:

1. how much confidence the sample supports;
2. which minimum instability rate the sample was designed to detect;
3. whether the horizon was chosen before or after observing results;
4. whether missing or invalid evidence was silently removed from the denominator;
5. whether a case count multiplied the false-confidence surface;
6. whether stable behavior was confused with correct behavior or release eligibility.

The root defect is therefore not a missing percentage field. It is the absence of a signed sampling
contract from request through evidence, attestation, consumer re-derivation, and CI admission.

## 3. Statistical model

### 3.1 Event and claim scope

One trial is one complete `COLLECT_ALL` execution of the exact immutable suite. Its outcome is the
ordered vector of every case's verified `evidenceStatus + semanticResultFingerprint` identity.
An instability event occurs when an attempt vector differs from the first verified vector.

The claim scope is `SUITE_ATTEMPT_ANY_CASE`. A change in any case is one suite-level event. The model
does not independently claim `C` confidence for every case, so it does not need to hide a
multiple-comparison correction behind the number of cases. Per-case statuses remain deterministic
diagnostics, while the statistical release condition is suite-level.

### 3.2 Exact horizon rule

The request freezes:

- confidence target `C` in basis points;
- maximum instability rate `q` in basis points;
- a fixed attempt horizon `n`;
- model `ZERO_INSTABILITY_EXACT_BINOMIAL`;
- scope `SUITE_ATTEMPT_ANY_CASE`;
- stopping rule `PRECOMMITTED_FIXED_HORIZON`;
- censoring policy `FAIL_CLOSED`.

For zero observed events, the horizon is sufficient only when:

```text
(1 - q)^n <= 1 - C
```

The server and independent Java consumer must evaluate this relation with exact integer arithmetic.
For basis-point values `Q` and `C`, the comparison is:

```text
(10000 - Q)^n * 10000 <= (10000 - C) * 10000^n
```

The minimum sufficient horizon is the first bounded `n` satisfying that relation. Floating-point
rounding cannot change admission, evidence, or a release gate. `achievedConfidenceBps` is a
conservative floor for display; the exact cross-product comparison owns the Boolean verdict.

Examples:

| Confidence | Instability ceiling | Minimum clean attempts |
| --- | --- | --- |
| 95% | 10% | 29 |
| 95% | 5% | 59 |
| 95% | 1% | 299 |
| 99% | 1% | 459 |

A request whose fixed horizon cannot meet its declared target is rejected before the first suite
execution. This prevents guaranteed-to-fail sampling jobs and post-hoc relabeling.

### 3.3 Why fixed horizon

Generation one deliberately rejects optional stopping. The service executes the complete horizon
even after an early mismatch. This has three properties:

1. the caller cannot keep sampling until a favorable run appears and then choose the prefix;
2. the signed request fingerprint proves that the sample size preceded the observations;
3. every retained result has one simple, reproducible stopping reason: `FIXED_HORIZON_REACHED`.

Sequential probability-ratio tests and alpha-spending may be added only as a new protocol generation
with their own estimator, spending schedule, and consumer verifier. They must not be smuggled into
this fixed-horizon model as an execution optimization.

## 4. Censoring and independence

Every requested attempt remains in the ordered signed closure. A source or child signature failure,
missing evidence, reused source/child run, effective-plan drift, or incomplete terminal result is a
censored attempt. Under `FAIL_CLOSED`, one censored attempt makes the statistical assessment
`INCONCLUSIVE`; the service does not drop it, replace it, or increase the denominator with a later
run. A verified instability event has monotonic negative-proof precedence: if an analysis contains
both a proven variant and censoring, its assessment is `REJECTED`, not weakened to `INCONCLUSIVE`.

Distinct server-derived request ids and rejection of reused source/child run ids prove only that the
same durable run was not counted twice. They do not prove stochastic independence or stationarity.
The v3 assessment therefore signs these explicit conditional assumptions:

- `ATTEMPTS_EXCHANGEABLE_WITHIN_ANALYSIS_WINDOW`;
- `OUTCOME_EVENT_DETECTION_BY_SEMANTIC_FINGERPRINT`;
- `EXECUTION_REGIME_STATIONARY_WITHIN_ANALYSIS_WINDOW`;
- `NO_UNOBSERVED_COMMON_CAUSE_CLAIM`.

The last code is intentionally negative: Resource Gateway does not claim to have ruled out a shared
dependency, scheduler, cache, or environment state that correlates attempts.

## 5. Protocol generations

The additive generation is:

| Object | Statistical generation | Compatibility rule |
| --- | --- | --- |
| request | `bloge.testSuiteStabilityExecutionRequest.v2` | v1 remains deterministic-only |
| evidence | `bloge.testSuiteStabilityEvidence.v3` | v1/v2 remain readable |
| attestation | `bloge.testSuiteStabilityAttestation.v3` | generation must match evidence |
| response | `bloge.testSuiteStabilityExecutionResponse.v3` | generation must match evidence and attestation |

Request v2 requires `statisticalPolicy`; request v1 forbids it. Evidence v3 requires one
`statisticalAssessment`; v1/v2 forbid it. The v3 assessment binds the frozen policy, derived minimum
horizon, observed/verified/censored attempt counts, observed instability-event count, conservative
achieved confidence, derived assessment status, fixed stopping reason, and fixed assumption codes.

The signed attestation already binds the canonical request fingerprint, evidence fingerprint, and
ordered source promotion closure. Moving the new assessment into canonical v3 evidence therefore
binds the statistical model without duplicating mutable labels in signature material.

## 6. Derived verdicts

The statistical assessment has exactly three terminal statuses:

| Status | Derivation |
| --- | --- |
| `SATISFIED` | horizon sufficient, every attempt verified, and zero suite-level instability events |
| `REJECTED` | at least one verified suite-level instability event, including when other attempts are censored |
| `INCONCLUSIVE` | one or more requested attempts are censored and no instability event was proven |

Deterministic case and aggregate statuses remain unchanged. A suite can be statistically repeatable
but consistently wrong. Consequently v3 promotion is eligible only when all existing conditions are
true and the statistical assessment is `SATISFIED`:

- aggregate status is `STABLE`;
- every attempt and child closure is verified;
- every source suite promotion is `ELIGIBLE`;
- the statistical assessment is `SATISFIED`.

`CONSISTENT_FAILURE` remains promotion-blocked even when its repeated failure is statistically
repeatable. Source-promotion blocking remains orthogonal. Quarantine remains a recommendation and is
not used to hide confidence or correctness failures.

## 7. Bounded execution and admission

Statistical request v2 permits 3..1000 attempts. The server additionally limits
`attempts * suiteCaseCount` to 10,000 case observations before execution. These are protocol and
generation-one resource bounds, not capacity claims. Existing request v1 remains limited to 3..20.

The service admits only confidence values `5000..9999` basis points and instability thresholds
`1..5000` basis points. It rejects an unreachable horizon, unsupported model/scope/rules, nested or
oversized metadata, unsupported suites, and exact suite drift before executing attempt one.

## 8. Independent consumer and CI semantics

The test kit must not trust producer counts or labels. It independently:

1. reconstructs every suite-attempt outcome vector from the signed case closure;
2. re-derives verified, censored, and instability-event counts;
3. recomputes the exact minimum horizon and achieved confidence with integer arithmetic;
4. re-derives assessment, aggregate, promotion, and quarantine verdicts;
5. verifies the v3 signature against the externally pinned key set.

The existing deterministic v2 assertion remains available and must not be renamed as statistical.
A new statistical assertion and CLI gate require v3, `SATISFIED`, eligible promotion, and pinned
trust. JUnit output remains payload-free and includes only bounded model coordinates and verdicts.

## 9. Test obligations

Implementation is incomplete until tests prove at least:

1. exact 95%/10%, 95%/5%, 95%/1%, and 99%/1% horizon boundaries;
2. one fewer attempt is rejected while the exact minimum is admitted;
3. stable pass reaches confidence and may be eligible;
4. consistent failure reaches repeatability confidence but remains promotion-blocked;
5. a semantic variant rejects confidence and requires quarantine;
6. one censored source/child/plan observation makes confidence inconclusive without denominator repair;
7. source-promotion blocking cannot be laundered by a satisfied statistical assessment;
8. forged producer counts, status, assumptions, achieved confidence, or horizon are rejected;
9. v1/v2 canonical fingerprints remain unchanged and cannot be projected as v3;
10. strict Schema, capabilities, persistence, HTTP, CLI, JUnit, JavaDoc, and full builds stay green.

## 10. Deliberately unclaimed

The synchronous parent execution is now protected by a database-authoritative, cross-replica
lease. One live owner may coordinate a stability run for an exact scope and request fingerprint;
expired ownership may be taken over with a higher fence epoch, and terminal persistence consumes
the exact lease atomically. The implementation and verification boundary is documented in
[Stage 5 suite-stability execution lease verification](resource-gateway-execution-data-control-plane-stage5-suite-stability-execution-lease-verification.md).

This generation does not claim:

- historical drift detection across code, fixture, dependency, or environment epochs;
- a confidence interval for non-zero event rates;
- sequential/adaptive stopping or alpha spending;
- proof of independence, stationarity, or absence of common causes;
- automatic quarantine mutation, owner workflow, or expiry;
- asynchronous parent queues, tenant fairness, distributed attempt-worker scheduling, or physically
  separate runtime isolation;
- long-duration soak, capacity, chaos, or non-H2 database certification.

Those are separate lifecycle and infrastructure problems. Keeping them visible is part of the
statistical contract, not an implementation disclaimer to be removed later.
