# Stage 5 anytime-valid suite-stability design

**Implementation status (2026-07-19): end-to-end protocol and consumer integration implemented.**
The server serves request v4, evidence/attestation/response v5, and progress v2; evaluates only after
a durable parent checkpoint; reconstructs the first crossing before scheduling after recovery;
admits only exact early-terminal repository closures; and exposes strict Schema and capability truth.
The independent test kit validates the v5 wire objects, replays every signed prefix with separate
exact arithmetic, supports synchronous and asynchronous clients, and projects planned/observed
counts, stop reason, alternative, and first crossing through CLI, JUnit XML, and public Java APIs.

## 1. Root decision

Resource Gateway will not bolt repeated fixed-horizon tests onto the execution loop and call that
adaptive sampling. Looking after every attempt and stopping on the first favorable fixed-sample
confidence interval inflates the false-admission probability. Dividing alpha across a finite look
schedule can repair that defect, but introduces schedule state, spends power on looks that may never
occur, and makes crash recovery depend on another mutable protocol object.

The selected next generation is a baseline-conditional, anytime-valid likelihood-ratio e-process.
Its test factor is frozen in the request, can be reconstructed from the ordered signed attempt
vectors, and remains valid at every bounded stopping time. This closes optional stopping at the
statistical object instead of relying on the runtime to stop only in approved places.

## 2. Signed claim

The first verified suite outcome vector is the observed baseline. For each later complete attempt,
`X_t = 1` when any case's verified semantic outcome differs from that baseline and `0` otherwise.
The request precommits:

- confidence `C`, with `alpha = 1 - C`;
- admitted instability ceiling `q`;
- alternative instability rate `r`, where `0 <= r < q`;
- maximum execution horizon `N`;
- suite-level event scope and fail-closed censoring;
- stopping rule `ANYTIME_VALID_E_PROCESS`.

The null is the conditional process claim:

```text
H0: P(X_t = 1 | prior signed comparison history) >= q
```

The alternative `r` controls power and expected stopping time. It is not an observed estimate and
cannot be selected after seeing a prefix.

## 3. Exact e-process

For `m` post-baseline comparisons and `k` instability events:

```text
E(m, k) = (r / q)^k * ((1 - r) / (1 - q))^(m - k)
```

For every conditional null event probability at least `q`, the expected next test factor is at most
one because `r < q`. The product is therefore a non-negative supermartingale. Ville's inequality
gives:

```text
P_H0(sup_m E(m, k_m) >= 1 / alpha) <= alpha
```

The runtime may inspect the statistic after every durable source checkpoint without increasing the
declared Type-I error. Admission is the exact integer comparison:

```text
r^k * (10000-r)^(m-k) * alphaBps
    >= q^k * (10000-q)^(m-k) * 10000
```

No floating point, logarithm, rounded p-value, or producer label owns the verdict. The conservative
display confidence is:

```text
achievedConfidenceBps = floor(10000 * (1 - 1 / E)), when E > 1; otherwise 0
```

At `C=95%`, `q=10%`, and `r=5%`, a clean path first crosses after 56 comparisons, or 57 complete
executions. At 55 comparisons its confidence floor is 94.88%; at 56 it is 95.15%. With one event,
59 comparisons remain below the boundary while 99 comparisons cross it. These are protocol test
anchors, not illustrative floating-point approximations.

## 4. Why this is not ordinary alpha spending

Both methods can control repeated looks, but the e-process is selected for this execution model:

| Dimension | Finite alpha-spending looks | Anytime-valid e-process |
| --- | --- | --- |
| Look schedule | signed list of attempt/alpha pairs | every durable prefix is valid |
| Recovery state | schedule cursor plus spent alpha | ordered event prefix only |
| Unused looks | reserve or redistribute alpha | no alpha fragmentation |
| Independent verification | replay every spending transition | replay exact event counts at each prefix |
| Change tolerance | adding a look changes policy | max horizon may change only in a new request |
| Cost | fewer computations but lower flexibility | exact bounded arithmetic at each prefix |

Explicit alpha-spending remains a possible future policy family, not an alias for this model. The
capability name must say `anytimeValidSuiteStabilityEProcess`; the existing
`sequentialSuiteStabilityAlphaSpending` flag stays false.

## 5. Implemented server protocol generations

The server uses additive generations and preserves all historical signatures:

| Object | Current server generation |
| --- | --- |
| request | `bloge.testSuiteStabilityExecutionRequest.v4` |
| evidence | `bloge.testSuiteStabilityEvidence.v5` |
| attestation | `bloge.testSuiteStabilityAttestation.v5` |
| response | `bloge.testSuiteStabilityExecutionResponse.v5` |
| progress | `bloge.testSuiteStabilityProgress.v2` |

Policy v4 adds `alternativeInstabilityRateBps` and requires the exact anytime model/stopping-rule
pair. Fixed-horizon policies omit that field, preserving their canonical material.

Evidence v5 keeps top-level `requestedAttempts` as the precommitted maximum while the ordered
attempt/case closure contains only actually executed attempts. Its statistical assessment must bind:

- maximum and observed attempt counts;
- post-baseline comparison and event counts;
- first boundary-crossing attempt, when one exists;
- conservative anytime confidence floor;
- terminal status and stop reason;
- exact conditional-process assumptions.

It does not report the fixed-sample Clopper-Pearson upper bound because that object is not
anytime-valid under repeated inspection. Reusing the v4 field would be a semantic lie.

## 6. Terminal semantics

| Condition | Statistical status | Stop reason |
| --- | --- | --- |
| first exact e-value crossing before `N` | `SATISFIED` | `E_VALUE_THRESHOLD_REACHED` |
| no crossing through `N` | `REJECTED` | `MAXIMUM_HORIZON_REACHED` |
| any censored coordinate | `INCONCLUSIVE` | `CENSORING_OBSERVED` |

The independent verifier must scan every ordered prefix. A producer cannot claim attempt 80 as the
first crossing if the same signed vectors crossed at attempt 57; it also cannot stop at attempt 56
when the boundary was not met. Maximum-horizon rejection must prove that no prior prefix crossed.

Deterministic correctness remains orthogonal. A sequential rate claim can be statistically
`SATISFIED` while an observed event makes the aggregate `FLAKY`; promotion remains blocked and
quarantine remains required. A stable repeated business failure remains
`CONSISTENT_FAILURE + BLOCKED`.

## 7. Durable execution invariants

The runtime order is mandatory:

```text
execute source -> verify source/children -> append durable parent checkpoint
-> reconstruct exact prefix -> evaluate boundary -> either seal terminal or schedule next attempt
```

The checkpoint must commit before the stop decision. If the process crashes after checkpoint and
before terminal publication, a higher-epoch successor restores and verifies the prefix, evaluates
the boundary before scheduling anything new, and publishes the same first-crossing terminal. This
prevents one extra attempt from appearing after the signed stopping condition.

Repository completion may accept `completedAttempts < plannedAttempts` only for v5 evidence whose
signed attempt closure exactly equals durable progress and whose independently derived stop reason
permits early terminalization. Progress v2 exposes planned, completed, and terminal reason without
owner, epoch, source ids, or payloads.

Cancellation and deadline authority still linearize before parent publication. They cannot replace
an eligible statistical terminal with a favorable partial prefix.

## 8. Fail-closed and threat model

The generation must reject:

- model/stopping-rule/version mismatch;
- missing, equal, or greater-than-ceiling alternative rates;
- request maxima that cannot cross on the clean path;
- producer-selected alternatives after execution begins;
- boundary checks before the source checkpoint commits;
- skipped prefixes, non-contiguous attempts, and reused source/child runs;
- fabricated first-crossing coordinates or achieved confidence;
- censored attempts projected as no-event observations;
- early completion under historical progress v1;
- test-kit projection that trusts server counters or status;
- CLI defaults that silently opt existing fixed-horizon users into sequential behavior.

## 9. Completion evidence

The server advertises `anytimeValidSuiteStabilityEProcess` only when the stability endpoint and
attestation signer are both available. End-to-end portability is not complete until every item is
closed:

1. implemented: separate server/test-kit exact arithmetic parity at crossing and non-crossing
   boundaries;
2. implemented on the server: non-zero-event crossing and maximum-horizon rejection;
3. implemented on the server: first-crossing reconstruction over ordered vectors;
4. implemented on the server: crash after checkpoint resumes into terminal without an extra source
   execution;
5. implemented on the server: repository accepts only valid early v5 terminal closure;
6. implemented on the server: progress v1 compatibility and strict v2 early-terminal semantics;
7. implemented on the server: censoring, flakiness, consistent failure, and source-promotion
   blocking;
8. implemented on the server: forged alternative, count, confidence, crossing, stop reason, and
   signature rejection;
9. implemented: strict Schema, capability, HTTP, independent synchronous/asynchronous client, CLI,
   JUnit, and public JavaDoc gates;
10. implemented: full Resource Gateway `clean verify` passes 2780 tests with zero failures and
    errors and two existing conditional skips; independent test-kit `clean verify` passes 187 tests
    with zero failures, errors, or skips and produces ordinary/shaded JARs plus public JavaDoc.

## 10. Deliberately unclaimed

Even after this generation is complete, it will not prove the conditional null assumption, absence
of common causes, representativeness of the observed baseline, cross-epoch stationarity, production
SLO compliance, or business correctness. Historical trend/common-cause detection, automatic
quarantine workflow, distributed attempt execution, and physical runtime isolation remain separate
industrial controls.
