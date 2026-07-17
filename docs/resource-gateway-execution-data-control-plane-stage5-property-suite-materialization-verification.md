# Stage 5 Immutable Property Suite Materialization Verification

## Scope

This increment turns one reviewed `bloge.testPropertyCasePlan.v1` into a governed, immutable
`bloge.testSuite.v4`. It deliberately does not execute the suite or emit property evidence.

| Surface | Delivered contract |
| --- | --- |
| Graph materialization | `POST /api/testing/targets/graphs/{graphName}/property-suites` |
| Operator materialization | `POST /api/testing/targets/operators/{operatorRef}/property-suites` |
| Request | `bloge.testPropertySuiteMaterializationRequest.v1` |
| Suite | `bloge.testSuite.v4` |
| Response | `bloge.testPropertySuiteMaterialization.v1` |
| Capability | `propertySuiteMaterialization=true`; `propertySuiteExecution=false` |

## Protocol closure

V4 carries these values as canonical fingerprint material rather than advisory metadata:

- exact target, input-schema, and property-plan fingerprints;
- `PROPERTY_EXECUTION`, `BOUNDED_SAMPLED`, and `exhaustive=false`;
- generator version, seed, trial/shrink/case/attempt/depth/collection bounds, and validator mode;
- generated or explicitly accepted partial status plus every stable generation gap;
- ordered root trial coordinates and linear shrink lineage, including input fingerprints and
  deterministic complexity;
- every frozen root and shrink input, one exact fixture reference, structural coverage, future
  promotion policy, and bounded provenance.

The model recursively freezes case inputs and metadata. Mutating a caller-owned nested map, list,
set, or array after construction cannot change registered content.

## Trust boundary

Generic suite registration rejects V4 with
`RG.TEST.PROPERTY_SUITE_MATERIALIZATION_REQUIRED`. Only the package-owned materializer can call the
protected registry path, and it must pass the exact plan regenerated in the same authenticated
request. The registry independently compares:

1. target, schema, and plan fingerprints;
2. every generation policy field, source status, gap, root, and shrink coordinate;
3. each ordered case ID and a canonical fingerprint recomputed from its input;
4. the one exact fixture's fingerprint, target, classification, and assertion density;
5. full-case coverage, empty semantic coverage, and fail-closed future promotion policy.

`PROPERTY` is reserved for V4. A V1-V3 suite cannot use that case type, and every V4 case must use
it. This prevents a manually authored business suite from masquerading as a generated property
asset.

## Fixture and selection semantics

The caller supplies one existing `FixtureBundleRef`. The service never creates an inert fixture and
rejects an assertion-free revision. Every generated case shares that exact revision so variability
comes only from the frozen plan inputs, not from case-specific mock behavior.

All roots and precomputed shrink candidates are materialized in plan order. There is no case
selection field: omitting inconvenient trials would change the asset from the reviewed plan. A
partial plan requires `acceptGenerationGaps=true`; an unavailable plan cannot be materialized.
Content-derived revisions make exact retries idempotent.

## Execution safety

The suite runner checks V4 immediately after resolving and fingerprint-verifying the exact suite.
It returns `409 RG.TEST.PROPERTY_EVIDENCE_UNAVAILABLE` before creating a run checkpoint, entering
runtime admission, or invoking a graph/operator. This prevents V4 from being mislabeled with an
older evidence or attestation generation.

Execution remains disabled until one generation closes all of these facts:

- root outcome and assertion results;
- which shrink candidates were evaluated and why;
- minimal counterexample semantics without claiming global minimality;
- exact seed/policy/plan/suite/fixture/target lineage;
- property-specific coverage and promotion verdicts;
- generation-matched checkpoint, terminal attestation, portable bundle, and test-kit verifier.

## Independent client support

The standalone test-kit packages the updated schema and exposes:

- `planGraphPropertyCases` and `planOperatorPropertyCases`;
- `materializeGraphPropertySuite` and `materializeOperatorPropertySuite`;
- V3/V4/property protocol constants;
- future `PROPERTY` case-result parsing while the ordinary V1/V2 suite builder remains limited to
  manually authored business case intents.

Every plan, request, and response is schema-validated. Unknown fields, versions, malformed policy
bounds, and response shape drift fail before a value is returned to CI code.

## Verification matrix

| Proof | Covered failure modes |
| --- | --- |
| V4 model and codec | exhaustive claim, unaccepted partial gaps, broken parent chain, non-decreasing complexity, mixed fixtures, mutable nested input, generation mismatch |
| Registry | raw V4 bypass, PROPERTY in V1, input substitution, plan/policy/lineage mismatch, stale fixture, assertion-free fixture, target/classification drift |
| Materializer | stale three-fingerprint review, unavailable/partial plan policy, fixture substitution, assertion-free fixture, deterministic revision, complete root/shrink closure |
| Runner | no repository write, admission, or business call when V4 evidence is unavailable |
| Real Spring HTTP | profile assembly, capability, authentication, planning, fixture lookup, V4 persistence/readback, explicit execution conflict |
| JSON Schema and test-kit | exact versions, V4 canonical fields, case taxonomy separation, endpoint query/body, defensive responses, packaged schema |

Focused verification covers 47 Resource Gateway tests and 37 independent test-kit tests with no
failures. The full Resource Gateway `clean verify` executes 2382 tests with no failures or errors,
two conditional skips, and produces the Spring Boot executable JAR. The independent test-kit
`clean verify` executes 85 tests with no failures, errors, or skips, and passes packaged-schema,
normal/shaded JAR, and strict public JavaDoc gates.

## Remaining boundary

This increment closes plan-to-suite governance but not property correctness evidence. The next
increment must define `TestSuiteRunEvidenceV4`, a matching attestation and bundle generation, exact
root/shrink execution semantics, aggregate evaluation, reconciliation, and independent verification.
Until all are generation-matched, `propertySuiteExecution=false` is the only honest capability.
