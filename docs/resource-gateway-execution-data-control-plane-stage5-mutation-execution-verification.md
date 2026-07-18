# Stage 5 pure-DSL mutation execution verification

## 1. Verified claim

Resource Gateway can now materialize and execute an exact bounded pure-DSL mutation suite, retain a
durable baseline/mutant child closure, emit generation-matched signed V5 evidence, and let an
independent client re-derive the score before CI or governance consumes it.

The claim is intentionally narrow:

1. The target is a graph with recoverable `bloge-dsl.ast.v1` source.
2. Mutation is restricted to the reviewed generation-one orchestration operators.
3. The suite contains at most 16 mutants, 16 oracle cases, and 256 mutant-case work units.
4. The runtime uses the isolated test engine and exact governed fixtures, but it remains in the same
   application deployment unless operators deploy the test profile separately.
5. No semantic equivalent-mutant proof, flaky rerun analysis, statistical confidence, or
   cross-process parallel scheduler is claimed.

## 2. Immutable admission boundary

`POST /api/testing/targets/graphs/{graphName}/mutation-suites` accepts
`bloge.testMutationSuiteMaterializationRequest.v1` under `TEST_SUITE_WRITE`. Before registering V5,
the service:

- regenerates the plan using the submitted bound and compares exact target, recoverable-source,
  graph-artifact, and plan fingerprints;
- rejects unavailable plans and requires explicit acceptance of a partial plan;
- resolves an exact immutable V1, V2, or V4 executable graph oracle for the same target;
- rereads every fixture revision, target binding, and fingerprint;
- requires at least one governed business assertion per oracle case;
- rejects V3 admission suites, V5 mutation suites, matrix overflow, target drift, and any attempt to
  upload executable mutant source or choose a favorable subset;
- freezes the complete plan/oracle/fixture closure and score policy in content-addressed
  `bloge.testSuite.v5`.

Ordinary suite registration and ordinary suite execution reject V5. This prevents a producer from
flattening a mutation matrix into ordinary case coverage.

## 3. Baseline-first execution state machine

`POST /api/testing/suites/{suiteId}/mutation-executions` accepts only
`bloge.testMutationSuiteExecutionRequest.v1`. `TestMutationSuiteExecutionService` follows this order:

1. Reserve or replay the tenant/environment-scoped request identity.
2. Resolve the exact V5 suite, graph, oracle, fixtures, and current dependency-bound target.
3. Acquire a database-authoritative owner lease and persist a signed initial checkpoint.
4. Execute every oracle case against the unmodified baseline graph.
5. Stop the matrix if the baseline is not fully passing and evidence-complete.
6. Regenerate each reviewed mutant through the same planner and require every source/artifact/target
   fingerprint to match the immutable coordinate.
7. Execute the mutant with the baseline case input and exact governed fixture in the isolated test
   engine, checkpointing signed child facts as work progresses.
8. Re-derive classification and score, sign a terminal V5 attestation, and persist the aggregate.

`COLLECT_ALL` executes every oracle case. `STOP_AFTER_KILL` may skip only later cases of the current
mutant after a signed assertion kill; it never skips a later mutant. A client therefore cannot inflate
the score by selecting which mutants receive execution.

## 4. Classification and score invariants

| Observation | Case classification | Score effect |
| --- | --- | --- |
| Signed `ASSERTION_FAILED` with a failed governed assertion | `ASSERTION_KILLED` | Mutant may be `KILLED` |
| Signed `PASSED` with all governed assertions passing | `SURVIVED` | Mutant is `SURVIVED` only when every case passes |
| Runtime, timeout, fixture, or control failure | `EXECUTION_FAILED` | Mutant is `INCONCLUSIVE` when the matrix is terminal |
| Missing, invalid, or incomplete child evidence | `EVIDENCE_INCOMPLETE` | Mutant is `INCONCLUSIVE` when the matrix is terminal |
| Unscheduled work without a valid assertion kill | `NOT_SCHEDULED` | Mutant remains unclassified |

The denominator is exactly `killed + survived`. Inconclusive mutants are separately bounded by
policy. Any unclassified mutant forces the numeric score to zero. `requireNoSurvivors` is independent
of the threshold. Generation one fixes `equivalentMutantsExcluded=0` and rejects
`excludeEquivalentMutants=true`, so an unknown equivalent mutant cannot silently improve the score.

## 5. Durable evidence and recovery

The runner shares the immutable suite-run repository, process-owner lease coordinator, heartbeat,
checkpoint fence, attestation service, and portable-bundle path used by governed suite execution.
The mutation generations are fixed as follows:

| Object | Version |
| --- | --- |
| execution response | `bloge.testSuiteExecutionResponse.v6` |
| aggregate evidence | `bloge.testSuiteRunEvidence.v5` |
| aggregate attestation | `bloge.testSuiteRunAttestation.v5` |
| portable bundle | `bloge.testSuiteEvidenceBundle.v5` |

The signed ordered child closure uses `baseline/<caseId>` and `<mutantId>/<caseId>`. Every child ref
must match the aggregate run id and evidence fingerprint; mutant children must additionally match the
regenerated mutant target fingerprint.

When an owner disappears after its lease expires, bounded reconciliation:

- preserves all terminal baseline and mutant facts exactly;
- marks pending baseline work `EVIDENCE_INCOMPLETE`;
- marks pending mutant work `NOT_SCHEDULED` with `ABANDONED_RUN_RECONCILED`;
- re-derives mutant classification, counts, denominator, score, aggregate status, and promotion;
- signs and persists a V5 terminal `EVIDENCE_INCOMPLETE` closure;
- never regenerates a mutant or reruns a child that may already have produced side effects.

This is fail-closed crash convergence, not automatic resume. An explicit new idempotency identity is
required for a deliberate rerun.

## 6. Public protocol and capabilities

The authoritative testing schema includes strict request, materialization, V5 suite, V6 response,
V5 evidence, V5 attestation, and V5 bundle shapes. Capability discovery advertises these facts
independently:

- `pureDslMutationPlanning`
- `mutationSuiteMaterialization`
- `pureDslMutationExecution`
- `mutationScoreEvidence`

The two HTTP endpoints and all four flags appear only when the isolated test execution surface is
assembled. Production profile omission remains the structural guard; a feature flag is not treated as
an authorization boundary.

## 7. Independent consumer and CI proof

`resource-gateway-test-kit` is independent from the Spring Boot server and packages the authoritative
Schema. Its client binds materialization responses to the requested graph/plan/oracle identity and
execution responses to the exact suite/idempotency identity. `TestSuiteRun` then independently
re-derives:

- baseline status;
- per-case and per-mutant classification;
- assertion-only kill provenance;
- killed, survived, inconclusive, and unclassified counts;
- denominator and basis-point score;
- immutable score-policy reasons and aggregate pass/fail.

`TestSuiteEvidenceVerifier` rejects cross-generation evidence, a valid signature over an inflated
score, mislabeled prefixed child coordinates, missing or duplicate children, and child fingerprint
drift. `TestSuiteRunAssertions.assertMutationSatisfied(...)` exposes the typed JUnit gate.

The shaded CLI requires explicit `--mode MUTATION`, accepts only `COLLECT_ALL` or
`STOP_AFTER_KILL`, calls the dedicated endpoint, and emits payload-free JUnit XML with one row per
baseline case and mutant. Individual survivors are informational; the frozen aggregate score policy
owns the process exit code.

## 8. Verification performed

Focused server protocol/application command:

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=TestMutationSuiteControllerTest,TestingControlProtocolSchemaTest,\
TestabilityCapabilitiesTest,TestRuntimeApplicationIntegrationTest test
```

Result on 2026-07-18: **9 tests, 0 failures, 0 errors, 0 skips**. This includes a real Spring HTTP
materialize -> execute -> query -> evidence-bundle flow against the built-in loan-decision graph and
transport fixtures.

Focused CLI/report command:

```bash
mvn -f resource-gateway-test-kit/pom.xml \
  -Dtest=ResourceGatewaySuiteCliTest,JUnitXmlReportWriterTest test
```

Result on 2026-07-18: **14 tests, 0 failures, 0 errors, 0 skips**. It covers the dedicated endpoint,
mode-specific strategies, payload-free output, per-mutant report rows, policy-owned JUnit failure, and
pre-network fail-closed configuration.

Full Resource Gateway command:

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
```

Result on 2026-07-18: **2436 tests, 0 failures, 0 errors, 2 conditional skips**, including 35
configured browser tests, followed by successful Spring Boot executable-JAR packaging.

Full independent test-kit command:

```bash
mvn -f resource-gateway-test-kit/pom.xml clean verify
```

Result on 2026-07-18: **111 tests, 0 failures, 0 errors, 0 skips**, followed by authoritative Schema
packaging, normal JAR, shaded CLI JAR, and strict public JavaDoc verification.

## 9. Deliberately unclaimed work

1. Semantic equivalent-mutant detection or proof.
2. Flaky rerun, quarantine, confidence interval, or statistical stopping policy.
3. Cross-process/distributed mutant scheduling, fairness, backpressure, or autoscaling.
4. A physically separate test-runtime deployment and egress/network policy proof.
5. Hard process/container cancellation for an uncooperative operator.
6. Production-scale capacity, non-H2 dialect, chaos, and long-duration certification.

These are real industrialization gaps. They remain visible rather than being hidden behind a passing
generation-one mutation score.
