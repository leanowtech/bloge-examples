# Stage 5 Seeded Property Plan Verification

> Historical verification record: this document proves the authoring-plan increment. The reviewed
> plan can now be materialized and executed with generation-matched signed property evidence; see
> [Stage 5 Property Execution Verification](resource-gateway-execution-data-control-plane-stage5-property-execution-verification.md).

## Scope

This increment introduces a reproducible property-input authoring protocol. It does not execute a
property suite and does not emit correctness evidence.

| Surface | Delivered contract |
| --- | --- |
| Graph planning | `GET /api/testing/targets/graphs/{graphName}/property-cases` |
| Operator planning | `GET /api/testing/targets/operators/{operatorRef}/property-cases` |
| Protocol | `bloge.testPropertyCasePlan.v1` |
| Quantification | Always `BOUNDED_SAMPLED`; `exhaustive` is always `false` |
| Capability | `seededPropertyCasePlanning=true`; `propertySuiteExecution=false` |

## Proof obligations

1. The caller supplies the seed. The exact target, input-schema fingerprint, policy, trials, shrink
   paths, and gaps participate in the plan fingerprint.
2. Repeating the same request against the same exact target and schema returns the same plan.
3. Root inputs are unique. A low-cardinality domain returns a disclosed shortfall rather than duplicate
   padding.
4. Every root and shrink input is accepted by the shared `VisualSchemaValidator` before publication.
5. A shrink path is linear, parent-linked, and strictly decreases deterministic complexity.
6. Unsupported constraints, BLOGE projection loss, invalid/opaque schemas, and generation bounds are
   machine-readable gaps; no silent completeness claim is allowed.
7. Inputs are recursively immutable, including arrays containing schema-valid `null` elements.
8. Planning is profile-isolated and uses the existing target-read authorization boundary.
9. The public JSON Schema and capability catalog use the same versions and resource bounds as Java.
10. Property execution remains unavailable until immutable suite and signed evidence generations are
    defined and independently consumable.

## Bounded algorithm

| Bound | Value |
| --- | ---: |
| Unique root trials | 16 |
| Shrink steps per root | 5 |
| Root plus shrink cases | 96 |
| Candidate attempts per root | 32 |
| Recursive schema depth | 8 |
| Generated string/collection size | 32 |

Generation supports the direct object, array, integer, decimal, string, boolean, null, enum, and const
paths. Constraints not directly generated remain useful as validator checks, but are also disclosed so
consumers cannot infer that the generator explored those constraint spaces.

## Negative verification

The focused tests cover invalid policy bounds, opaque and contradictory schemas, integer domains beyond
the v1 generator's long range, low-cardinality uniqueness exhaustion, unsupported pattern disclosure,
immutable nested inputs, null-bearing arrays, missing HTTP seed, endpoint defaults, capability disablement,
and exact same-seed HTTP replay. The plan model rejects malformed fingerprints, duplicate roots, broken
parent chains, non-decreasing shrink complexity, unavailable plans without gaps, and any exhaustive claim.

## Remaining boundary

This plan is neither a persisted `TestSuite` nor proof that an operator or DAG preserves a business
property. The next protocol generation must materialize the complete plan closure against a governed
fixture with real assertions, reject target/schema/plan drift, execute through the isolated test kernel,
and bind seed, shrink lineage, outcomes, evidence, attestation, and portable bundle in one generation.

## Validation result

| Gate | Result |
| --- | --- |
| JSON Schema parse | `jq empty` passed |
| Focused planner/controller/capability/schema/HTTP suite | 28 tests, 0 failures, 0 errors, 0 skips |
| Resource Gateway `clean verify` | 2372 tests, 0 failures, 0 errors, 2 conditional skips; executable JAR built |
| Independent test-kit `clean verify` | 84 tests, 0 failures, 0 errors, 0 skips; schema packaging, normal/shaded JAR, and public Javadoc passed |
