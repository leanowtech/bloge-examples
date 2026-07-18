# Stage 5 pure-DSL mutation evidence protocol verification

> Historical protocol-only verification. The statements below about a missing public runner and
> disabled execution capabilities describe this increment at delivery time. They are superseded
> operationally by
> [Stage 5 mutation execution verification](resource-gateway-execution-data-control-plane-stage5-mutation-execution-verification.md).

## 1. Verified claim

At the time of this increment, it defined the immutable, payload-free protocol needed to classify and
score an exact `bloge.testSuite.v5` mutation matrix. It did **not** claim that the public mutation
runner existed, so `pureDslMutationExecution` and `mutationScoreEvidence` remained disabled then.

The protocol closes these integrity boundaries:

1. `KILLED` requires an independently retained child whose exact status is `ASSERTION_FAILED`.
2. Timeout, fixture, control-plan, target, persistence, and evidence failures are never kills.
3. `SURVIVED` requires every oracle case to pass with at least one governed assertion.
4. A partially scheduled mutant without a valid kill remains unclassified, not inconclusive.
5. Generation one excludes no equivalent mutant and reports `equivalentMutantsExcluded = 0`.
6. The numeric denominator is exactly `killed + survived`; inconclusive outcomes remain separately
   bounded and unclassified outcomes force `scoreBasisPoints = 0`.
7. The baseline oracle must pass before a mutation score can be satisfied.
8. Aggregate status, promotion, score, mutant counts, kill provenance, target fingerprints, fixture
   references, and ordered case closure are recomputed and cross-checked by constructors.

## 2. Protocol generations

| Object | Version | Purpose |
|---|---|---|
| Mutation suite | `bloge.testSuite.v5` | Exact baseline, reviewed plan, oracle, mutants, bounds, and score policy |
| Aggregate evidence | `bloge.testSuiteRunEvidence.v5` | Baseline results, typed mutant matrix, classification, and score verdict |
| Aggregate attestation | `bloge.testSuiteRunAttestation.v5` | Domain-separated checkpoint or terminal signature over up to 272 child refs |
| Execution response | `bloge.testSuiteExecutionResponse.v6` | Exact evidence/attestation generation pairing |
| Portable bundle | `bloge.testSuiteEvidenceBundle.v5` | Payload-free terminal mutation evidence export |

The authoritative schema remains
[`testing-control-plane-v1.schema.json`](schemas/resource-gateway-testing/testing-control-plane-v1.schema.json).
Every new object is strict (`additionalProperties: false`) and retains the V5 16 × 16 / 256 bounds.

## 3. Classification matrix

| Child observation | Case status | Mutant effect |
|---|---|---|
| Signed `ASSERTION_FAILED` | `ASSERTION_KILLED` | Proves `KILLED`; later cases may be short-circuited |
| Signed `PASSED` with assertions | `SURVIVED` | Contributes to `SURVIVED` only when every case passes |
| Signed runtime/control/fixture/timeout failure | `EXECUTION_FAILED` | Complete matrix becomes `INCONCLUSIVE` |
| Missing, invalid, or incomplete evidence | `EVIDENCE_INCOMPLETE` | Complete matrix becomes `INCONCLUSIVE` |
| Pending case | `PENDING` | Mutant remains `PENDING` or `RUNNING` |
| Unscheduled case without a valid kill | `NOT_SCHEDULED` | Mutant remains unclassified and score is incomplete |

An inconclusive mutant is excluded from the numeric denominator but checked against
`maximumInconclusiveMutants`. `requireNoSurvivors` is enforced independently from the numeric
threshold, so a nominally sufficient percentage cannot hide a forbidden survivor.

## 4. Evidence and persistence closure

- `TestSuiteRunEvidenceProtocolCodec` dispatches V5 by exact `schemaVersion` and preserves canonical
  round trips and fingerprints.
- `TestSuiteRunAttestationService` signs V5 in an independent v5 domain and verifies immediately.
- `DatabaseTestSuiteRunRepository` rejects V5 evidence paired with any non-v5 attestation.
- `TestSuiteExecutionResponse` and `TestSuiteEvidenceBundle` reject cross-generation pairing.
- No executable DSL, business request, response, input, or output payload is copied into aggregate
  mutation evidence.

## 5. Verification performed

Focused command:

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=TestMutationSuiteEvidenceEvaluatorTest,TestSuiteRunEvidenceProtocolCodecTest,\
TestSuiteRunAttestationServiceTest,TestingControlProtocolSchemaTest test
```

Result on 2026-07-18: **23 tests, 0 failures, 0 errors, 0 skips**.

The tests cover assertion-only kill semantics, survivor and inconclusive classification, threshold,
inconclusive and no-survivor gates, partial-scheduling suppression, child target drift, tampered
score rejection, V5 codec round trip, v5 signature-domain selection, response/bundle pairing, and
schema constants/bounds. `jq empty` and `git diff --check` also pass.

## 6. Deliberately unclaimed in this historical increment

The following remain required before enabling mutation execution or score evidence capabilities:

1. A dedicated idempotent mutation runner and public request endpoint.
2. Baseline-first oracle execution and exact-mutant regeneration before scheduling.
3. A child execution path that runs the regenerated graph in the isolated test engine while
   retaining baseline-bound governed fixtures.
4. Lease checkpoints, fail-closed abandoned-run reconciliation, and complete child-ref ordering.
5. Resource Gateway and independent test-kit schema/client integration plus real Spring/H2 tests.
6. Full clean verification, operating documentation, and capability activation only after all
   prior items are proven.

Equivalent-mutant detection, flaky analysis, statistical confidence, and deployment-level physical
isolation remain later Stage 5 work and are not implied by this protocol increment.
