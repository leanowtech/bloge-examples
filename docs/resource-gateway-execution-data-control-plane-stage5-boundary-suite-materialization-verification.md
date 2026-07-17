# Stage 5 Boundary-Suite Materialization Verification

## Result

This increment converts a reviewed `bloge.testBoundaryCasePlan.v1` subset into immutable governed
assets without claiming that schema-admission execution or correctness evidence already exists.

The delivered protocol is:

- `bloge.testBoundarySuiteMaterializationRequest.v1`;
- `bloge.testBoundarySuiteMaterialization.v1`;
- `bloge.testSuite.v3` with `evaluationMode=SCHEMA_ADMISSION`;
- graph and operator `POST .../boundary-suites` routes under `TEST_SUITE_WRITE`;
- capability `schemaBoundarySuiteMaterialization=true`;
- capability `schemaAdmissionSuiteExecution=false`.

## Safety Model

| Risk | Enforced invariant |
| --- | --- |
| Target changes after review | Server regenerates the plan and compares target, input-schema, and plan fingerprints before writing |
| Partial generation is mistaken for complete coverage | `PARTIAL` requires explicit `acceptCoverageGaps=true`; `UNAVAILABLE` is rejected |
| Caller smuggles arbitrary inputs into a generated suite | Every selected case ID must belong to the exact regenerated plan; stored input comes from that plan |
| Expected rejection is treated as an ordinary failed business case | v3 stores an explicit outcome and diagnostic-code expectation per case |
| Admission-only assets claim DAG coverage | v3 requires empty invocation, edge, assertion, fixture-consumption, and semantic coverage |
| Admission-only assets become promotion evidence | v3 requires zero certifiable cases and no target-certification requirement |
| Materialization retry creates drifting revisions | Fixture and suite revisions derive from canonical content; exact retries resolve to the same refs |
| Two-asset write fails halfway | Fixture is written first and is inert; failure can leave only an immutable unreferenced fixture |
| Existing runner invents unsupported evidence | v3 execution fails before admission, target execution, or run persistence |

## Asset Closure

The generated fixture carries only target/classification/provenance. It has no fixture rules,
assertions, logical clock, or random seed. Every generated case references that exact fixture revision.

The v3 suite binds:

1. exact target fingerprint;
2. exact projected input-schema fingerprint;
3. exact reviewed boundary-plan fingerprint;
4. complete selected input values in source-plan order;
5. exactly one `AdmissionExpectation` for every case ID;
6. content-derived immutable revision and full protocol fingerprint.

The registry independently rechecks expectation closure, fixture inertness, payload bounds, case
identity, classification, target identity, and the no-promotion policy. JSON Schema mirrors these
wire-level constraints; cross-field case/expectation key equality remains a service invariant.

## Failure Semantics

| Code | Status | Meaning |
| --- | ---: | --- |
| `RG.TEST.BOUNDARY_SUITE_REQUEST_INVALID` | 400 | Version, IDs, classification, fingerprints, or selected-case bounds are invalid |
| `RG.TEST.BOUNDARY_PLAN_FINGERPRINT_CONFLICT` | 409 | Current target/schema/plan differs from the reviewed values |
| `RG.TEST.BOUNDARY_PLAN_UNAVAILABLE` | 400 | No proven case exists |
| `RG.TEST.BOUNDARY_PLAN_GAPS_NOT_ACCEPTED` | 400 | A partial plan lacks explicit gap acknowledgement |
| `RG.TEST.BOUNDARY_CASE_SELECTION_INVALID` | 400 | A selected case does not belong to the exact plan |
| `RG.TEST.SUITE_ADMISSION_EVIDENCE_UNAVAILABLE` | 409 | v3 is registered but signed schema-admission execution is not implemented |

## Verification Matrix

Focused tests prove:

- deterministic graph materialization and source-plan ordering;
- exact v3 expectation closure and inert fixture construction;
- stale fingerprint, unknown selection, oversized ID, partial-gap, and unavailable-plan rejection;
- operator route delegation;
- v3 codec round trip and immutable registry generation support;
- missing expectation, non-inert fixture, and promotion-claim rejection;
- pre-execution v3 runner rejection with no run record or admission interaction;
- strict JSON Schema versions, object closure, and v3 union registration;
- capability feature/object/endpoint publication;
- real Spring HTTP materialization followed by exact suite revision readback.

Final verification completed with:

- Resource Gateway `clean verify`: 2348 tests, 0 failures, 0 errors, 34 conditional skips, and a
  repackaged Spring Boot executable JAR;
- independent test kit `clean verify`: 77 tests, 0 failures, 0 errors, 0 skips, packaged authority
  schema, normal and shaded JARs, and the public Javadoc gate.

## Remaining Gap

This is not a terminal Stage 5 result. The next protocol increment must produce signed,
payload-governed schema-admission case results, bind validator generation and current target/schema
identity into aggregate evidence, define replay/idempotency/retention semantics, and only then turn
`schemaAdmissionSuiteExecution` on. Property seeds/shrinking, mutation execution/scoring, flaky
analysis, and physical test-runtime isolation remain separate later increments.
