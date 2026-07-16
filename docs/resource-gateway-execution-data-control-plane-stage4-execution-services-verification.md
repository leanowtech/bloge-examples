# Stage 4 Execution Services Verification

## Scope

This increment connects Resource Gateway's governed fixture controls to BLOGE's run-scoped
`ExecutionServices` and gives deterministic outcomes a stable semantic identity. It controls logical
time, random values, generated UUIDs and environment-dependent DSL function resolution without
placing control data in `GraphContext`.

## Frozen Protocol

- `bloge.effectiveExecutionPlan.v3` adds `executionServiceBindings`.
- Each binding exposes service, provider mode, availability, determinism, a configuration
  fingerprint, declared consumers and certification gaps.
- Plan bindings do not export raw logical-clock configuration, random seeds, provider scopes,
  credentials or secret values. Evidence may expose governed logical timestamps and records only
  provider-scope fingerprints and structural function call sites.
- Capability discovery retains v1/v2 reader versions and advertises v3 as the producer version.
- `bloge.testRunEvidence.v2` adds `semanticResultFingerprint`; the schema retains explicit v1 and v2
  definitions and a dual-read union. Execution response v1 references evidence v1, while current
  signed execution response v2 requires evidence v2.

## Semantic Result Identity

`bloge.semanticTestResult.v1` is a domain-separated canonical projection. It includes terminal
status, execution purpose, target/fixture/plan fingerprints, stable node and edge coordinates,
inputs/outputs, outcomes, attempts, fixture consumption, assertions, sorted diagnostics, governed
logical time, semantic execution-service usage and side-effect intent facts.

It excludes run id, evidence class, wall-clock timestamps, durations, signatures, response
projection, broad governance metadata, parallel completion order, engine-only service calls and
volatile side-effect identifiers. Semantic execution-service usage includes service/mode, semantic
provider call count, function call count and structural function call sites; raw provider call count
and scope fingerprints remain audit evidence but are not business-result identity.

The sanitizer recomputes the fingerprint from persisted redacted values. Consequently two runs that
differ only in a secret value produce the same stored semantic identity instead of a secret-guessing
oracle. The full evidence fingerprint remains different across run id, timing and signing events.

## Safety Invariants

1. Planner construction creates one stateful service set; runtime cannot rebuild it from mutable
   fixture storage.
2. The same logical clock reaches engine scheduling and `OperatorContext.timeSource()`.
3. One fixture seed drives domain-separated SHA-256 streams for RANDOM and UUID. Counters are
   scoped by stable invocation coordinates instead of one scheduler-sensitive global cursor.
4. Missing logical clock or seed permits exploratory use, but declared or observed semantic use
   prevents certifiable evidence.
5. IDENTITY, FEATURE_FLAG and SECRET have no fixture authority yet and fail closed on every call.
6. A source-boundary test prevents `GovernedExecutionServices` references outside the testing
   subsystem.
7. Every execution snapshots caller business context and creates a new root `GraphContext`; repeated
   and concurrent use of one request cannot reuse engine services, budgets, outputs or side effects.
8. Current evidence is not signed or accepted on read when its semantic fingerprint does not match
   its canonical projection. Historical evidence v1 remains readable only with an empty semantic
   fingerprint.
9. `STANDARD` and `SUMMARY` retain the full-evidence semantic fingerprint as signed lineage but omit
   values needed for independent recomputation. `FULL` is the conformance input for independent
   implementations.

## Automated Evidence

`GovernedExecutionServicesTest` verifies reproducibility, seed isolation, payload-free plan
projection, logical-clock advancement, usage audit and fail-closed ambient authorities.
`TestRunServiceTest.compiledLogicalClockReachesOperatorContextAndControlsCertification` executes a
real BLOGE graph and proves the compiled clock reaches the operator and controls evidence class.
`ExecutionServicesBoundaryTest` is the production-path architecture guard. Planner, target
classification, capability and JSON Schema tests freeze the wire and certification semantics.
`TestSemanticResultFingerprintTest` proves stable ordering and the included/excluded material;
`TestEvidenceSanitizerTest` proves redaction-time recomputation; integrity tests reject stale semantic
identity before signing and on verification. Test-kit protocol tests prove v1/v2 schema compatibility,
and `TestRunAssertions.assertSameSemanticResult` provides a payload-free CI regression assertion.

## Honest Remaining Gaps

- Durable checkpoint/resume does not persist provider counters or logical-clock state.
- Repeated concurrent calls at the exact same invocation scope still depend on occurrence
  assignment order; deterministic parallel scheduling or a stronger invocation coordinate is
  required before claiming byte-identical semantics there.
- Streaming/suspendable execution does not yet have equivalent governed evidence.
- Identity, feature flags and secrets require separate typed authorities; raw values must never be
  added to fixture bundles.

## Reproduction

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=GovernedExecutionServicesTest,TestRunServiceTest,TestSemanticResultFingerprintTest,TestEvidenceSanitizerTest,TestEvidenceIntegrityServiceTest,TestingControlProtocolSchemaTest test

mvn -f resource-gateway-test-kit/pom.xml \
  -Dtest=ResourceGatewayTestClientTest,TestRunAssertionsTest,TestingProtocolTest test
```
