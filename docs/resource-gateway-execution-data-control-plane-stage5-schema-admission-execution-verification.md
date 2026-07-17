# Stage 5 Schema-Admission Execution Verification

## 1. Decision and scope

This increment turns a reviewed `bloge.testSuite.v3` from a materialized authoring asset into an
executable, signed, admission-only regression suite. It closes the previous materialization record's
deliberate runtime rejection without weakening the separation between schema validation and business
correctness.

In scope:

- exact target/input-schema/boundary-plan/generator provenance re-resolution;
- shared-validator execution with no graph/operator business invocation;
- durable idempotency, runtime admission, owner lease, signed checkpoints, terminal evidence, and
  abandoned-run reconciliation;
- generation-matched public response, evidence, attestation, and portable bundle;
- capability discovery, authoritative JSON Schema, standalone test-kit projection/assertions/JUnit
  output, and offline Ed25519 verification;
- real Spring HTTP materialize -> execute -> read -> evidence export proof.

Out of scope: property-space quantification, shrinking, mutation score, business structural or
semantic coverage, publication eligibility, flaky analysis, and deployment/network sandboxing.

## 2. Non-negotiable invariants

| Invariant | Enforcement |
| --- | --- |
| One evaluation meaning | evidence v3 fixes `evaluationMode=SCHEMA_ADMISSION` and `executionPurpose=SCHEMA_ADMISSION_SUITE_EXECUTION` |
| Exact reviewed snapshot | suite target, input-schema fingerprint, boundary-plan fingerprint, generator version, and validator mode are bound into evidence |
| Atomic current target | graph/operator target, schema, and regenerated plan are resolved as one immutable `TestSchemaAdmissionTarget` |
| Exact case provenance | each stored input and expectation must match the selected regenerated plan case |
| Exact validator result | outcome and ordered canonical validator-code set must match the stored expectation |
| No business execution | graph/operator case runner is never called; common case rows have blank `runId`, null child evidence, and zero assertions |
| No false coverage | structural coverage is exactly `NOT_EVALUATED` with empty facts |
| No false promotion | promotion is always `BLOCKED` with `SCHEMA_ADMISSION_ONLY` and `BUSINESS_EXECUTION_NOT_PERFORMED` |
| Signed isolation fact | attestation v3 requires an empty `childEvidenceRefs` closure and signs that closure |
| No unsigned terminal evidence | signer/verification failure prevents trusted v3 publication |
| Same-generation transport | response v4, evidence v3, attestation v3, and bundle v3 cannot be mixed with older generations |
| Crash honesty | reconciliation preserves completed validator observations, converts only unfinished work to incomplete, and never resumes evaluation |

## 3. Public protocol closure

| Object | Wire version | Key distinction |
| --- | --- | --- |
| immutable suite | `bloge.testSuite.v3` | reviewed inputs plus admission expectations |
| execution response | `bloge.testSuiteExecutionResponse.v4` | carries only evidence v3 and attestation v3 |
| aggregate evidence | `bloge.testSuiteRunEvidence.v3` | typed admission result/coverage and exact provenance |
| aggregate attestation | `bloge.testSuiteRunAttestation.v3` | domain-separated empty business-child closure |
| portable bundle | `bloge.testSuiteEvidenceBundle.v3` | terminal payload-free export |

The authoritative schema is
[`testing-control-plane-v1.schema.json`](schemas/resource-gateway-testing/testing-control-plane-v1.schema.json).
It freezes all constants, strict object shapes, no-child compatibility rows, admission lifecycle
states, plan-status/gap-acceptance combinations, structural non-coverage, permanent promotion block,
empty attestation closure, and same-generation response/bundle references.

The capability probe advertises these versions under `supportedObjects` and sets
`schemaAdmissionSuiteExecution=true` only when the profile-isolated testing runtime is assembled.
The feature remains false when that runtime is absent.

## 4. Runtime lifecycle

1. Authenticate tenant/organization/project/environment and `TEST_EXECUTION` purpose.
2. Resolve the exact immutable suite revision and reject fingerprint drift.
3. Atomically resolve the current target, projected input schema, and regenerated boundary plan.
4. Compare target, schema, plan, selected cases, expectations, generator, and gap acceptance.
5. Reserve database-authoritative runtime capacity using an admission-only intent with no operator or
   dependency subjects.
6. Reserve/replay the caller's exact idempotency key and acquire the suite-run owner lease.
7. Sign and persist a `RUNNING` evidence-v3 checkpoint before evaluating the first case.
8. For each case, prove plan membership and call the shared schema validator; persist a newly signed
   checkpoint under the current lease.
9. Derive typed admission coverage and a permanently blocked promotion verdict.
10. Sign and atomically persist terminal evidence. Export a bundle only for independently verifiable
    terminal attestation.

`COLLECT_ALL` evaluates every case. `FAIL_FAST` preserves the first mismatch and marks the remaining
cases `NOT_SCHEDULED`; it does not rewrite a mismatch into infrastructure failure.

## 5. Failure taxonomy

| Failure class | Stable evidence/API signal | Result |
| --- | --- | --- |
| target drift | `RG.TEST.SUITE_ADMISSION_TARGET_CONFLICT` | no checkpoint or validator call |
| input-schema drift | `RG.TEST.SUITE_ADMISSION_INPUT_SCHEMA_CONFLICT` | no checkpoint or validator call |
| plan drift/unavailable | `...BOUNDARY_PLAN_CONFLICT` / `...BOUNDARY_PLAN_UNAVAILABLE` | no checkpoint or validator call |
| suite provenance defect | `RG.TEST.SUITE_ADMISSION_PROVENANCE_INVALID` | no run published |
| case provenance drift | `PROVENANCE_MISMATCH` + stable diagnostic | signed terminal failed evidence |
| validator disagreement | `EXPECTATION_MISMATCH` + stable diagnostic | signed terminal failed evidence |
| validator unavailable | `EVIDENCE_INCOMPLETE` | never interpreted as schema rejection |
| capacity denial | database admission rejection | no lease/checkpoint/business call |
| signer unavailable | `RG.TEST.SUITE_ATTESTATION_UNAVAILABLE` | fail closed; no unsigned v3 record |
| lease loss | `SUITE_RUN_LEASE_LOST` | unfinished cases become incomplete/not scheduled |
| terminal write failure | `SUITE_RUN_TERMINAL_PERSISTENCE_FAILED` | best-effort signed incomplete terminalization |
| abandoned checkpoint | `ABANDONED_RUN_RECONCILED` | signed v3 incomplete terminal evidence; no resume |

## 6. Independent consumer behavior

The standalone test-kit branches on evaluation meaning rather than treating v4 as a more permissive
business-suite response:

- `passed()` and `assertPassed(...)` remain business-execution predicates;
- `admissionPassed()` and `assertAdmissionPassed(...)` require all typed results to match and
  admission coverage to be satisfied;
- `evaluationPassed()` dispatches to the explicit mode without equating the modes;
- `gateFailureCodes(false)` can drive a schema-regression CI gate;
- requiring promotion remains a failure by design;
- the offline verifier recomputes aggregate, bundle, and signature-material fingerprints and accepts
  the signed empty closure only for the exact v3 generation;
- any response/evidence/attestation/bundle generation mismatch fails closed.

## 7. Verification matrix

| Proof | Covered behavior |
| --- | --- |
| `TestSchemaAdmissionEvaluatorTest` | atomic target locks, plan/schema drift, exact case provenance, validator mismatch, pending/partial coverage |
| `TestSuiteExecutionServiceTest` | zero business calls, admission-only capacity intent, idempotency, collect-all/fail-fast, signing, terminal export, lease and persistence failures |
| `TestSuiteRunAttestationServiceTest` | v3 domain separation and empty child closure |
| `TestSuiteRunEvidenceProtocolCodecTest` | v3 polymorphic persistence and mixed-generation rejection |
| `TestSuiteRunReconciliationServiceTest` | abandoned v3 terminalization preserving completed observations |
| `TestRuntimePersistenceTest` | database generation preservation and compare-and-set recovery |
| `TestingControlProtocolSchemaTest` | Java/schema version lock and protocol-shape invariants |
| test-kit `TestingProtocolTest` | packaged schema accepts v4/v3 and rejects a business child closure |
| test-kit `TestSuiteRunAssertionsTest` | admission projection, mode-specific success, and false business-coverage rejection |
| test-kit `TestSuiteEvidenceVerifierTest` | offline verification of signed empty closure and generation binding |
| `TestRuntimeApplicationIntegrationTest` | real HTTP planning, materialization, v3 execution, readback, and bundle export |

Final gates on 2026-07-18:

- Resource Gateway `clean verify`: 2364 tests, 0 failures, 0 errors, 2 conditional skips, and a
  successfully packaged Spring Boot executable JAR;
- standalone test-kit `clean verify`: 84 tests, 0 failures, 0 errors, 0 skips, with authoritative
  Schema packaging, ordinary/shaded JAR packaging, and the strict public-JavaDoc gate all passing;
- focused server protocol/capability/real-HTTP checks: 7 tests green;
- focused test-kit protocol/projection/verifier/HTTP/JUnit/CLI checks: 55 tests green.

## 8. Quality judgment and remaining gap

The increment is internally and externally coherent: runtime semantics, persisted polymorphism,
cryptographic domain, wire schema, capability, client projection, CI reporting, offline verification,
and real HTTP behavior all describe the same admission-only fact. The previous highest-risk gap,
“internal code can emit a generation that external consumers reject,” is closed.

Residual Stage 5 risk is now outside this increment rather than hidden inside it. Boundary cases are
deterministic examples, not quantified property coverage; no shrink trace exists; no mutant inventory
or survivor evidence exists; repeated-run variance is not measured; and process/profile isolation is
not deployment-grade network/runtime isolation. Those items must receive their own protocol entities,
failure semantics, and adversarial verification rather than being folded into admission coverage.
