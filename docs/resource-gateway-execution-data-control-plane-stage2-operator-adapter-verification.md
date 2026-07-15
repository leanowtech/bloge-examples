# Execution Data Control Plane Stage 2 Operator Adapter Verification

> Verified: 2026-07-15
>
> Scope: public synchronous operator target discovery, governed micro-graph execution, evidence,
> and standalone Java test-kit consumption

## Delivered Boundary

The operator adapter is a thin protocol edge over the existing `OperatorMicroGraphRunner` and
`TestRunService`. It does not introduce a second operator execution mechanism. The server exposes:

| Endpoint | Contract |
| --- | --- |
| `GET /api/testing/targets/operators/{operatorRef}` | `bloge.testOperatorTargetDescriptor.v2` |
| `POST /api/testing/targets/operators/{operatorRef}/executions` | `bloge.testOperatorExecutionRequest.v1` -> common `bloge.testExecutionResponse.v1` |
| `PUT /api/testing/fixture-bundles/{fixtureBundleId}` | Existing immutable registry now accepts `GRAPH` and `OPERATOR` targets |

The capability probe advertises both objects/endpoints and
`operatorMicroGraphExecution=true`. Streaming and suspendable flags remain false.

## Certification Invariants

1. Path, target kind/id, target fingerprint, fixture target fingerprint and optional stored fixture
   fingerprint must all agree before input conversion or execution.
2. The target fingerprint covers operator reference, implementation closure bytecode, input/output
   schema, runtime-binding state, composability manifest, JSON conversion profile, execution model,
   side-effect/idempotency protocol, and known resource dependencies.
3. Stateless bindings have an empty formal state snapshot, but statelessness alone does not prove
   determinism or dependency isolation. Configured operators must implement
   `OperatorRuntimeBindingSnapshotProvider`; its credential-free map is bounded to 64 KiB,
   fingerprinted, and never exposed or persisted. Unformalized state forces exploratory evidence.
4. Non-resource certification requires `OperatorComposabilityManifestProvider`: dependency mode
   `NONE`, no execution services, no undeclared mutable global state, and a conformance suite ref plus
   SHA-256 artifact fingerprint. Missing, contradictory, malformed, or currently uncontrollable
   declarations fail closed as `OPAQUE_RUNTIME`.
5. `HttpResourceOperator` freezes its mapping/protocol implementation closure and every registered
   descriptor. Its conditional target can certify only when the fixture controls selected resource
   calls at `TRANSPORT` boundary.
6. `OPAQUE_RUNTIME`, unsupported execution models, inline fixtures, schema waivers, and output-level
   resource doubles cannot issue `CERTIFIABLE` evidence even when execution passes.
7. JSON input is converted to the registry-declared Java type only after authorization and target
   freezing. Conversion failure returns `RG.TEST.OPERATOR_INPUT_INVALID` without invoking code.
8. Full sanitized evidence is stored in the independent test-run store; response verbosity only
   changes projection.

## Verification Matrix

| Proof | Test |
| --- | --- |
| explicitly self-contained typed binding executes real code and certifies with stored SPY fixture | `TestOperatorExecutionApiServiceTest.governedFixtureExecutesTypedRealBindingAndProducesCertifiableEvidence` |
| stateless read-only binding without a manifest is opaque | `statelessReadOnlyBindingWithoutComposabilityManifestIsOpaque` |
| declared but uncontrolled TIME service fails certification closed | `declaredButUncontrolledExecutionServiceFailsCertificationClosed` |
| manifest ordering is canonical and contradictory dependency modes are rejected | `OperatorComposabilityManifestTest` |
| opaque external binding replaced by stored output still stays exploratory | `governedOutputDoubleCannotUpgradeAnOpaqueRuntimeToCertifiableEvidence` and `OperatorMicroGraphRunnerTest.storedOutputDoubleCannotMakeAnOpaqueBindingCertifiable` |
| configured binding without state contract fails certification closed | `configuredReadOnlyBindingNeedsAFormalStateSnapshotBeforeCertification` |
| explicit bounded state snapshot restores a stable certification path | `explicitRuntimeBindingSnapshotMakesConfiguredReadOnlyBindingFreezable` |
| oversized state snapshot fails closed before fingerprint publication | `oversizedRuntimeBindingSnapshotFailsCertificationClosed` |
| incomplete operator behavior declarations fall back to opaque, non-certifiable facts | `incompleteBehavioralContractIsDiscoverableButFailsCertificationClosed` |
| input conversion profile drift changes the target fingerprint | `targetFingerprintChangesWhenPublicInputConversionProfileChanges` |
| streaming target is discoverable but rejected before fixture resolution | `streamingBindingIsDiscoverableButV1ExecutionFailsBeforeFixtureResolution` |
| path mismatch and stale target fingerprint fail before execution | `pathTargetMismatchAndStaleOperatorFingerprintFailClosed` |
| stale fixture provenance fails before application input coercion | `staleFixtureFailsBeforeApplicationInputCoercion` |
| real Spring registry exposes `httpResource` as conditional transport | `TestRuntimeApplicationIntegrationTest` |
| controller purposes, schema versions, capability objects/endpoints | `TestExecutionControllerTest`, `TestingControlProtocolSchemaTest`, `TestabilityCapabilitiesTest` |
| independent client discovers and runs operator; builder emits OPERATOR payloads | `ResourceGatewayTestClientTest`, `FixtureBundleBuilderTest` |

## Reproduce

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=TestOperatorExecutionApiServiceTest,OperatorMicroGraphRunnerTest,TestExecutionControllerTest,TestRuntimeApplicationIntegrationTest,TestingControlProtocolSchemaTest,TestabilityCapabilitiesTest test
mvn -f resource-gateway-test-kit/pom.xml clean verify
mvn -f resource-gateway-examples/pom.xml clean verify
```

Focused server tests: 30 tests, 0 failures, 0 errors, 0 skipped. Standalone test-kit: 13 tests,
0 failures, 0 errors, 0 skipped. Resource Gateway `clean verify`: 1726 tests, 0 failures,
0 errors, 34 conditional skips, with the Spring Boot JAR packaged successfully.

## Explicit Non-Claims

- The canvas operator-suite action still proves `SCHEMA_CONTRACT`; it has not yet been migrated to
  this executable API.
- Streaming and suspendable operator execution/evidence are not implemented.
- Arbitrary hidden static/global state cannot be proven absent by a Java declaration. The manifest,
  behavior and state providers are governance contracts tied to conformance artifact identity;
  sandbox conformance, egress observation and declaration/observation drift detection remain
  later-stage anti-cheating controls.
- The adapter is profile and identity isolated but still runs inside the Resource Gateway process;
  separate deployment and deny-by-default network policy remain Stage 5.
