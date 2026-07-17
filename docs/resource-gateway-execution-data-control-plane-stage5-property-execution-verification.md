# Stage 5 Bounded Property Execution Verification

## Scope

This increment executes an exact immutable `bloge.testSuite.v4` and emits independently verifiable,
payload-free property evidence. It closes the plan -> materialization -> execution -> evidence -> CI
consumer loop without claiming exhaustive input coverage or globally minimal counterexamples.

| Surface | Delivered contract |
| --- | --- |
| Execute | `POST /api/testing/suites/{suiteId}/executions` with an exact V4 suite ref |
| Read | `GET /api/testing/suite-executions/{suiteRunId}` |
| Export | `GET /api/testing/suite-executions/{suiteRunId}/evidence-bundle` |
| Response | `bloge.testSuiteExecutionResponse.v5` |
| Evidence | `bloge.testSuiteRunEvidence.v4` |
| Attestation | `bloge.testSuiteRunAttestation.v4` |
| Portable bundle | `bloge.testSuiteEvidenceBundle.v4` |
| Capability | `propertySuiteExecution=true` only when isolated suite execution is enabled |

## Evidence semantics

V4 carries the complete property interpretation as signed canonical material:

- exact suite, target, input-schema, property-plan, seed, generator, resource bounds, and accepted
  generation gaps;
- mandatory `PROPERTY_EXECUTION`, `BOUNDED_SAMPLED`, and `exhaustive=false` facts;
- ordered root and precomputed shrink lineage with input fingerprint and deterministic complexity;
- one typed result for every frozen case and the exact signed child run when one exists;
- one derived trial verdict per root and one derived aggregate property-coverage verdict;
- payload-free counterexample references, diagnostics, and bounded scope metadata.

The model rejects contradictory states. `SATISFIED` requires complete passing assertion evidence.
`COUNTEREXAMPLE` requires complete `ASSERTION_FAILED` child evidence with at least one failed
assertion. Timeout, target error, fixture error, mock mismatch, or evidence failure is
`EXECUTION_FAILED` or `EVIDENCE_INCOMPLETE`; neither is allowed to masquerade as a property
violation.

## Minimality boundary

The planner freezes one linear shrink path per seeded root. Execution observes that exact path; it
does not synthesize a new candidate after the suite fingerprint is published. The minimum observed
counterexample is the lowest-complexity failed case in that path, with deterministic tie-breaking by
path order.

Every counterexample reference therefore requires:

```text
minimalityScope = PRECOMPUTED_SHRINK_PATH
globallyMinimal = false
```

The Java domain and authoritative JSON Schema both reject `globallyMinimal=true`. A passing bounded
run proves only that every reviewed finite case satisfied the assertions. It is not proof over the
complete schema domain.

## Scheduling semantics

`COLLECT_ALL` executes every root and every frozen shrink candidate in suite order.

`FAIL_FAST` uses a trial, not an individual case, as the stopping boundary. After the first root or
shrink counterexample, the runner completes the already-started root's remaining frozen shrink path
and then marks later trials unscheduled. This preserves reproducible minimization context without
starting unrelated roots. Execution failures and incomplete evidence also stop subsequent trials.

Each child runs through the existing authorized graph/operator adapter with the suite's exact stored
fixture revision. The service validates the target, fixture, run identity, evidence identity, and
child fingerprint before admitting the result into the aggregate closure.

## Durability and recovery

The existing database-time owner lease, capacity admission, checkpoint fence, idempotency key, and
terminal persistence protocol apply to V4 without a second property-only scheduler.

- the first signed `RUNNING` checkpoint freezes the complete pending property closure;
- every completed child advances a signed checkpoint and ordered child evidence closure;
- exact `clientRequestId` replay returns the stored checkpoint or terminal record;
- intent drift under the same key fails with the existing idempotency conflict;
- lease loss, signing failure, generation mismatch, or terminal write failure fails closed;
- a stale abandoned-run scan uses status/version/owner/expiry compare-and-set before terminalizing;
- reconciliation preserves completed root/shrink facts, converts only pending cases to
  `EVIDENCE_INCOMPLETE`, signs V4 terminal evidence, and never invokes the target or regenerates input.

The database repository accepts V4 evidence only with V4 attestation. Response and portable-bundle
constructors enforce the matching V5 and V4 envelope generations, so a mixed-generation record
cannot become trusted through a read or export path.

## Production isolation

Property execution does not introduce a production request override. It remains behind the existing
profile-isolated `/api/testing/**` surface, authenticated testing purposes, exact immutable fixture
references, runtime admission, and capability probe. A production profile has neither the testing
controller nor `propertySuiteExecution=true`.

Capability truth is derived from the execution endpoint state, not a manually independent flag. This
prevents a deployment from advertising property evidence while the suite runner is absent.

## Independent consumer proof

The standalone test-kit packages the same authoritative Schema and now:

- accepts V5 execution responses and V4 portable bundles;
- projects typed property case, trial, coverage, and counterexample records;
- evaluates `PROPERTY_EXECUTION` independently from structural and schema-admission predicates;
- exposes `assertPropertySatisfied` and payload-free `assertCounterexampleFound` assertions;
- emits stable property failure codes for CI/JUnit consumers;
- verifies the V4 aggregate fingerprint, Ed25519 attestation, ordered root/shrink child closure,
  derived property semantics, generation match, key lifecycle, and pinned key-set policy offline.

The verifier never trusts `signatureStatus=VERIFIED` by itself. Mutation of aggregate evidence,
signature bytes, child order, protocol generation, key material, lifecycle history, or trusted pin
produces a fail-closed result.

## Verification matrix

| Proof | Covered failure modes |
| --- | --- |
| V4 domain/evaluator | false counterexample, execution failure mislabeled as violation, broken lineage, non-decreasing complexity, missing child identity, false global minimality, contradictory coverage/lifecycle |
| Runner | complete root/shrink execution, path-local minimum, trial-bounded fail-fast, exact idempotency, capacity rejection, lease loss, signing/terminal-write fail-closed |
| Repository/reconciliation | V4/V4 generation guard, checkpoint persistence, completed-fact preservation, pending-only incomplete conversion, no recovery-time business invocation |
| Real Spring/H2 HTTP | property plan, immutable materialization, authenticated execution, database persistence/readback, V5/V4 response generation |
| Capability/Schema | endpoint-derived feature flag, exact V5/V4/V4/V4 one-of generations, bounded resources, lifecycle conditions, `globallyMinimal=false` |
| Standalone test-kit | V5 client consumption, typed assertions, stable gate codes, V4 portable export, real Ed25519 offline verification and closure-order rejection |

## Build proof

The Resource Gateway `clean verify` executes 2389 tests with no failures or errors and two existing
conditional browser skips. It includes the real Spring/H2 property materialize -> execute path, 34
browser regression cases, capability/Schema checks, repository generation guards, and produces the
Spring Boot executable JAR.

The independent test-kit `clean verify` executes 92 tests with no failures, errors, or skips. It
passes authoritative-schema packaging, normal and shaded JAR checks, V5/V4 client consumption, real
Ed25519 V4 offline verification, and strict public JavaDoc validation.

## Remaining boundary

This increment establishes deterministic bounded property execution, not statistical property
certification. It does not yet provide adaptive shrink search, cross-worker parallel trial scheduling,
flake probability estimation, confidence intervals, pure-DSL mutation generation/execution/score,
production-scale load qualification, or deployment-level network isolation. Those remain explicit
Stage 5 work and must not be inferred from a passing V4 bundle.
