# Stage 4 Execution Services Verification

## Scope

This increment connects Resource Gateway's governed fixture controls to BLOGE's run-scoped
`ExecutionServices` and gives deterministic outcomes a stable semantic identity. It controls logical
time, random values, generated UUIDs, identity attributes, feature flags and environment-dependent
DSL function resolution without
placing control data in `GraphContext`. The current increment also defines and validates the
payload-free provider-state checkpoint needed by a later durable/suspendable adapter.

The next persistence increment is documented separately in
[Stage 4 durable checkpoint verification](resource-gateway-execution-data-control-plane-stage4-durable-checkpoint-verification.md).
It supplies a trusted composite repository and local transaction boundary, while the actual BLOGE
suspend/resume adapter remains pending.

## Frozen Protocol

- `bloge.effectiveExecutionPlan.v3` adds `executionServiceBindings`.
- Each binding exposes service, provider mode, availability, determinism, a configuration
  fingerprint, declared consumers and certification gaps.
- Plan bindings do not export raw logical-clock configuration, random seeds, provider scopes,
  identity/flag configuration, credentials or secret values. Evidence may expose governed logical
  timestamps and records only
  provider-scope fingerprints and structural function call sites.
- Capability discovery retains v1/v2 reader versions and advertises v3 as the producer version.
- `bloge.executionServiceStateSnapshot.v1` binds logical time, hashed RANDOM/UUID scope cursors and
  cumulative provider/function usage to the exact plan and execution-service binding-set
  fingerprints. `restorable` is derived from declared and observed semantic provider use;
  `restoreGaps` explains fail-closed non-restorability.
- The snapshot excludes the random seed, raw scope, identity/flag/secret values and fixture
  payloads. Capability discovery advertises it as a supported object, not as a public endpoint.
- `bloge.fixtureExecutionServices.v1` is a strict nested contract at
  `fixtureBundle.metadata.executionServices`. It carries bounded scalar identity attributes and
  boolean feature flags while preserving the existing top-level v1 fixture shape.
- `bloge.fixtureExecutionServices.v2` adds only bounded opaque `secretRefs`. It never accepts raw
  values. `bloge.testSecretResolutionContext.v1` binds enterprise scope, actor/delegation, purpose,
  execution and fixture identities, and the exact ref closure before external authority resolution.
- `bloge.testSecretAuthorityRequest.v1` adds a fresh request id, 256-bit challenge, request time and
  exact context/request fingerprints without credentials or business payload. The strict
  `bloge.testSecretAuthorityResponse.v1` signs the echoed request, decision, authority generation,
  exact versioned closure and values with Ed25519. The private standalone JSON Schema freezes both
  directions and their key-free descriptors.
- `bloge.testSecretAuthorityTrustRefreshSnapshot.v1` exposes only local dynamic-trust availability,
  closed refresh state, bounded key counts, last success, process-local success/failure counters,
  failure family, refresh interval and hard maximum age. The complete public-key/lifecycle
  generation has a private canonical fingerprint for a later cohort gate; URI, ETag, key ids and
  public or secret material never enter health or capability output.
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
5. IDENTITY and FEATURE_FLAG use exact immutable fixture maps. Missing keys fail closed and never
   consult production identity/flag authorities. SECRET accepts only a short-lived closure that an
   external `TestSecretAuthority` returned and Resource Gateway independently matched to the exact
   request; otherwise it fails closed.
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
10. Provider calls and checkpoint capture share a fair read/write coordination boundary; a snapshot
    cannot split a sequence cursor from its cumulative usage count.
11. Restore independently recomputes plan and binding fingerprints, restore eligibility, and
    RANDOM/UUID cursor closure. A self-fingerprinted but policy-inconsistent snapshot is rejected.
12. `ExecutionControlCompiler` runs normal preflight and recompiles the plan before restore. Any
    snapshot/plan/configuration mismatch becomes generic `CONTROL_PLAN_UNAVAILABLE`; it never falls
    back to current providers or REAL execution.
13. Secret plaintext exists only in the in-memory `ResolvedTestSecrets` handed to one run-scoped
    provider. Plans and checkpoints contain hashed dependency/version bindings. Fresh durable
    creation and every recovery re-authorize; version or authority-generation drift changes the
    rebuilt binding and blocks recovery.
14. The built-in authority performs one bounded HTTPS request without redirects or automatic
    retries. Only signed `AUTHORIZED` or signed `DENIED` is policy truth; HTTP status alone is not.
    Response bytes are capped at 2 MiB and every authority implementation is capped at 1 MiB of
    aggregate run-scoped plaintext.
15. Dynamic JWKS trust performs a mandatory usable bootstrap, publishes complete immutable key
    generations, shares one cooldown-bounded unknown-key refresh, and immediately invalidates trust
    on every ambiguous refresh. ETag 304 renews freshness; a silent refresh lane still expires at
    the local maximum age. Explicit `revoked`, disabled, inactive and expired keys cannot release a
    closure.

## Automated Evidence

`GovernedExecutionServicesTest` verifies reproducibility, seed isolation, payload-free plan
projection, logical-clock advancement, exact identity/flag lookup, missing-key/secret rejection,
usage audit, external test-secret isolation, atomic concurrent capture, exact continuation,
configuration drift, and
tamper/policy/cursor rejection. `FixtureExecutionServicesTest` freezes strict shape, scalar, key,
entry, aggregate-size, opaque-reference, immutability, and payload-safe rejection semantics.
`TestSecretResolutionServiceTest` proves exact enterprise/fixture closure binding, independent
response verification, no-authority bypass for non-secret fixtures, payload-safe failures, and
security audit. `DurableTestRecoveryAuthorizerTest` proves recovery calls the authority again and
rejects exact secret-version drift.
`TestSecretAuthorityProtocolTest`, `ConfiguredTestSecretAuthorityTrustStoreTest`,
`HttpTestSecretAuthorityTest`, `DynamicJwksTestSecretAuthorityTrustStoreTest`,
`TestSecretAuthorityProtocolSchemaTest`, and profile-isolation tests
freeze the credential-free request, secret-bearing signed response, challenge/replay binding,
static and dynamic Ed25519 lifecycle, concurrent unknown-key rotation, revocation, ETag refresh,
hard expiry, strict HTTPS/JSON/body bounds, signed denial semantics, startup fail-fast, payload-free
health and capability projection. `ResolvedTestSecretsTest` independently enforces the 1 MiB
aggregate plaintext ceiling for built-in and deployment-provided authorities.
`TestRunServiceTest.compiledLogicalClockReachesOperatorContextAndControlsCertification` executes a
real BLOGE graph and proves the compiled clock reaches the operator and controls evidence class.
`TestRunServiceTest.dslIdentityAndFeatureFlagBuiltInsUseOnlyTheFixtureAuthority` executes real DSL
`identity` and `featureFlag` built-ins and proves their control-plane projections omit raw values.
`ExecutionServicesBoundaryTest` is the production-path architecture guard. Planner, target
classification, capability and JSON Schema tests freeze the wire and certification semantics.
`TestSemanticResultFingerprintTest` proves stable ordering and the included/excluded material;
`TestEvidenceSanitizerTest` proves redaction-time recomputation; integrity tests reject stale semantic
identity before signing and on verification. Main capability tests and test-kit protocol tests prove
v1/v2 compatibility plus the checkpoint schema/version. `TestRunAssertions.assertSameSemanticResult`
provides a payload-free CI regression assertion.

The release gate completed `mvn -f resource-gateway-examples/pom.xml clean verify` with 3,118 tests,
zero failures, zero errors and two conditional browser skips; both browser suites, containing 35
configured tests, and the executable Spring Boot JAR build completed. The independent test-kit
`clean verify` completed 230 tests with zero failures, errors or skips, then passed authoritative-schema packaging,
ordinary/shaded JAR packaging and public JavaDoc validation.

## Honest Remaining Gaps

- The provider-state protocol and compiler restore seam now have a trusted fenced composite
  repository that can bind plan, fixture cursor, provider snapshot, and an engine-state closure in
  one local transaction. BLOGE durable/suspend stores do not yet call it, and there is no public
  checkpoint/resume endpoint or cold-start resume claim.
- `InvocationRecorder` now captures fixture-rule and dynamic occurrence cursors only at a quiescent
  invocation boundary, restores them atomically, and prevents concurrent `maxUses` over-consumption.
  Hashed cursor identities omit raw coordinates but remain pseudonymous. Pre-checkpoint invocation/attempt trace facts, pending
  timers, wait records, side-effect journal positions, and stream offsets remain engine adapter
  responsibilities.
- `snapshotFingerprint` and the composite checkpoint fingerprint are content identities, not source
  authentication. Trust currently comes from the isolated fenced store; cross-process export still
  requires signed attestation.
- Repeated concurrent calls at the exact same invocation scope still depend on occurrence
  assignment order; deterministic parallel scheduling or a stronger invocation coordinate is
  required before claiming byte-identical semantics there.
- Streaming/suspendable execution does not yet have equivalent governed evidence.
- The typed authority SPI, run-scoped provider, capability truth, durable re-authorization, strict
  signed challenge-bound HTTPS adapter, static Ed25519 lifecycle, atomic dynamic JWKS refresh,
  revocation propagation, hard local expiry and payload-free health are implemented. Exact
  cross-replica trust-generation convergence, signed-JWKS witness, endpoint/mTLS/KMS HA, external
  alert integration and chaos/DR certification are not. The adapter and dynamic trust remain
  explicitly disabled by default.

## Reproduction

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=FixtureExecutionServicesTest,ResolvedTestSecretsTest,TestSecretResolutionServiceTest,TestSecretAuthorityProtocolTest,ConfiguredTestSecretAuthorityTrustStoreTest,DynamicJwksTestSecretAuthorityTrustStoreTest,HttpTestSecretAuthorityTest,TestSecretAuthorityProtocolSchemaTest,GovernedExecutionServicesTest,DurableTestRecoveryAuthorizerTest,ExecutionControlCompilerTest,TestRunServiceTest,TestSemanticResultFingerprintTest,TestEvidenceSanitizerTest,TestEvidenceIntegrityServiceTest,TestingControlProtocolSchemaTest,TestRuntimeProfileIsolationTest test

mvn -f resource-gateway-test-kit/pom.xml \
  -Dtest=FixtureBundleBuilderTest,ResourceGatewayTestClientTest,TestRunAssertionsTest,TestingProtocolTest test
```
