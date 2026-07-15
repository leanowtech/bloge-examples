# Execution Data Control Plane Stage 2 Built-In Catalog Materialization Verification

> Date: 2026-07-15
>
> Scope: legacy seven-graph catalog -> immutable fixture/TestSuite registry -> common suite runner -> test-kit

## 1. Closed Gap

The seven built-in resource graphs already used the common execution kernel, but their 14 table
cases still existed only as mutable `GatewayGraphContractTestSuite` catalog values. That left two
asset identities and forced every new graph to maintain both the compatibility catalog and the
governed `bloge.testSuite.v1` registry by hand.

This increment closes the asset boundary:

- `PUT /api/testing/catalogs/gateway-graph-contract-v1` materializes the trusted built-in catalog in
  the verified caller's tenant and test/staging environment;
- each source case becomes one exact immutable fixture revision;
- each source suite becomes one dependency-closed immutable TestSuite revision;
- the response contains only a catalog fingerprint and exact source-to-destination references;
- all seven destination suites execute through `TestSuiteExecutionService` and the common graph
  adapter, rather than through a second migration-only runner;
- the standalone test-kit validates and projects the complete response without raw `Map` parsing.

The old endpoints remain compatibility authoring surfaces. They are no longer the only executable
identity of the built-in regression assets.

## 2. Identity And Retry Model

Destination ids are stable and bounded:

```text
suite   = rg-built-in-<sourceSuiteId>
fixture = rg-built-in-<sourceSuiteId>-case-<one-based-index>
```

The positive revision is derived from canonical SHA-256 material containing the source asset and
the exact target dependency fingerprint. The registry then computes its independent full content
fingerprint over the final revision value.

Consequences:

1. unchanged source and dependencies return exactly the same refs;
2. graph, resource descriptor, case, fixture, assertion, intent, or policy drift produces a new
   immutable revision under the stable id;
3. a truncated-revision collision reaches the immutable repository as different content under the
   same key and fails closed rather than aliasing evidence;
4. concurrent identical calls converge through the repository unique key and equivalent-content
   check;
5. fixtures commit before their referring suite, so interruption can leave only unreferenced
   immutable revisions; retry is safe and no mutable catalog pointer can be half-updated.

The materialization response excludes creation timestamps, actor details, and payloads. Identical
operations are directly comparable by automation.

## 3. Semantic Preservation

`GatewayGraphContractFixtureMapper` is the single conversion implementation used by both the old
compatibility runner and the materializer.

| Source fact | Common asset fact |
| --- | --- |
| resource mock and expected params | resource selector plus canonical `/params` match |
| `fixtureMode=TRANSPORT_LEVEL` | transport-boundary protocol response (F3) |
| `minUses/maxUses` | bounded fail-closed consumption |
| output contract | mandatory `MATCHES_SCHEMA` assertion |
| output/node assertions | common output/node assertion records |
| numeric equality tolerance | `FixtureBundle.Assertion.numericTolerance` |
| source case intent | `GOLDEN`, `NEGATIVE`, `BOUNDARY`, or `REGRESSION` |
| required output node | planner-derived root invocation-site id |
| source suite policy/tags/descriptions | bounded immutable provenance metadata |

Required output nodes are resolved through `InvocationInventoryBuilder`. This matters for
`resourceDispatch`: `executeResource` emits `/root/executeResource#RESOURCE`, not a guessed
`#PRIMARY` coordinate. The initial red test failed on exactly this distinction; the final design
uses the planner-owned structural truth.

Legacy JSON that omits `caseType` remains `REGRESSION`. Legacy assertions that omit
`numericTolerance` remain exact equality. Tolerance is accepted only for finite, non-negative
numeric `OUTPUT_EQUALS` or `PATH_EQUALS` assertions.

## 4. Security And Protocol

- the controller is assembled only in `test` and `staging` profiles;
- the endpoint authenticates `IntegrationOperation.TEST_SUITE_WRITE` before materialization;
- fixture and suite services retain environment, tenant, clearance, target freshness, immutable
  dependency, and audit-sink gates;
- the capability probe advertises the endpoint, feature, and response version only when the testing
  control plane is enabled;
- `testing-control-plane-v1.schema.json` defines the complete materialization response with
  `additionalProperties=false` and exact SHA-256 refs;
- server and test-kit records reject inconsistent aggregate counts, duplicate source/suite refs,
  duplicate fixture refs, or case/fixture cardinality mismatch;
- the test-kit sends `TEST_SUITE_WRITE`, validates the full Draft 2020-12 response, and returns a
  payload-free typed projection whose raw response is defensive.

## 5. Executed Verification

Focused Resource Gateway verification:

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=GatewayGraphContractTestServiceTest,BuiltInTestSuiteCatalogMaterializationIntegrationTest,TestSuiteCatalogMaterializationResponseTest,TestExecutionControllerTest,TestingControlProtocolSchemaTest,TestabilityCapabilitiesTest,TestRuntimeApplicationIntegrationTest \
  test
```

Result: 34 tests, 0 failures, 0 errors, 0 skipped.

Focused independent test-kit verification:

```bash
mvn -f resource-gateway-test-kit/pom.xml \
  -Dtest=ResourceGatewayTestClientTest,TestingProtocolTest test
```

Result: 12 tests, 0 failures, 0 errors, 0 skipped. The complete test-kit `clean verify` ran 32
tests with 0 failures, 0 errors, and 0 skipped; public JavaDoc completed without warnings and both
the library and dependency-contained CLI JARs were packaged with the authoritative schema.

The real Spring integration test seeds every production graph and resource descriptor, points the
real HTTP base URL at unreachable `127.0.0.1:1`, materializes twice, and executes all exact refs.
Observed result:

- 7/7 suites passed;
- 14/14 cases passed;
- every suite coverage verdict was `SATISFIED`;
- every suite promotion verdict was `ELIGIBLE`;
- every child evidence class was `CERTIFIABLE`;
- all four case intents were represented;
- no external HTTP escape was possible.

Final Resource Gateway verification used `mvn -f resource-gateway-examples/pom.xml -Pfrontend clean verify`:
1757 tests, 0 failures, 0 errors, and 34 conditional skips. The TypeScript/Vite production build,
real Chrome DOM regression, Spring Boot executable JAR packaging, and npm audit with 0 known
vulnerabilities all completed successfully.

## 6. Remaining Boundary

This closes the built-in catalog migration and numeric-tolerance gap. It does not claim Stage 2 is
complete. `REPLAY`, streaming/suspendable control and evidence, and physical test-runtime/network
isolation remain hard Stage 2 exits. Abandoned suite-run reconciliation and history/trend APIs also
remain operational maturity gaps.
