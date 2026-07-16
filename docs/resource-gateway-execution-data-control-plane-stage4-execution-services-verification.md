# Stage 4 Execution Services Verification

## Scope

This increment connects Resource Gateway's governed fixture controls to BLOGE's run-scoped
`ExecutionServices`. It controls logical time, random values, generated UUIDs and
environment-dependent DSL function resolution without placing control data in `GraphContext`.

## Frozen Protocol

- `bloge.effectiveExecutionPlan.v3` adds `executionServiceBindings`.
- Each binding exposes service, provider mode, availability, determinism, a configuration
  fingerprint, declared consumers and certification gaps.
- Plan bindings do not export raw logical-clock configuration, random seeds, provider scopes,
  credentials or secret values. Evidence may expose governed logical timestamps and records only
  provider-scope fingerprints and structural function call sites.
- Capability discovery retains v1/v2 reader versions and advertises v3 as the producer version.

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

## Automated Evidence

`GovernedExecutionServicesTest` verifies reproducibility, seed isolation, payload-free plan
projection, logical-clock advancement, usage audit and fail-closed ambient authorities.
`TestRunServiceTest.compiledLogicalClockReachesOperatorContextAndControlsCertification` executes a
real BLOGE graph and proves the compiled clock reaches the operator and controls evidence class.
`ExecutionServicesBoundaryTest` is the production-path architecture guard. Planner, target
classification, capability and JSON Schema tests freeze the wire and certification semantics.

## Honest Remaining Gaps

- `semanticResultFingerprint` is not emitted yet.
- Durable checkpoint/resume does not persist provider counters or logical-clock state.
- Repeated concurrent calls at the exact same invocation scope still depend on occurrence
  assignment order; deterministic parallel scheduling or a stronger invocation coordinate is
  required before claiming byte-identical semantics there.
- Streaming/suspendable execution does not yet have equivalent governed evidence.
- Identity, feature flags and secrets require separate typed authorities; raw values must never be
  added to fixture bundles.
