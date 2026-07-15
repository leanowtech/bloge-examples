# Execution Data Control Plane Stage 2: TestSuite Registry Verification

> Verified: 2026-07-15
>
> Scope: first-class immutable `bloge.testSuite.v1` protocol and registry, not suite execution

## Delivered Boundary

This increment turns a reviewed group of cases into a server-authoritative testing asset. It adds:

- `TestSuite`, case type, exact target, exact fixture reference, coverage policy, promotion policy,
  classification, and metadata records with clear Java documentation;
- immutable `(tenant, environment, suiteId, revision)` JDBC persistence in the independent
  test-runtime database;
- `PUT /api/testing/suites/{suiteId}` and exact-revision
  `GET /api/testing/suites/{suiteId}?revision=N`;
- separate `TEST_SUITE_WRITE` and `TEST_SUITE_READ` purposes, with target discovery permitted for
  those authoring roles;
- capability objects, feature flag, endpoints, and canonical JSON Schema definitions;
- synchronized product guide, service README, and both industrial evolution plans.

## Fail-Closed Invariants

Registration commits only after the complete dependency closure is verified:

1. Path id, suite id, positive revision, and schema version agree.
2. Target kind is `GRAPH` or `OPERATOR`; target and fixture fingerprints are full SHA-256 values.
3. The suite target fingerprint equals the current frozen graph/operator dependency snapshot.
4. Every case references an existing fixture in the same verified tenant and environment.
5. Every fixture fingerprint and target fingerprint equals the immutable suite reference.
6. Suite classification dominates every fixture classification and caller clearance covers both.
7. Case ids are unique; graph inputs are JSON objects; cases and JSON body sizes are bounded.
8. Required case types, minimum case count, assertion density, and promotion minima are satisfiable;
   a policy that requires certification rejects an ineligible frozen target.
9. Equivalent repeated registration is idempotent; different content at the same revision conflicts.
10. Production identities and security-audit failures fail closed before registry access.

No API resolves `latest`. A suite cannot be used as a lower-classification wrapper around restricted
fixtures, and a missing fixture is not deferred until execution.
Coverage references use collision-free invocation-site ids and edge endpoint pairs, not local node
or edge names that can repeat in nested graphs.

## Verification Evidence

Focused service, persistence, controller, protocol, capability, and real-application verification:

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=TestSuiteRegistryServiceTest,TestRuntimePersistenceTest,TestExecutionControllerTest,\
TestingControlProtocolSchemaTest,TestabilityCapabilitiesTest,TestRuntimeApplicationIntegrationTest test
```

Result: 24 tests, 0 failures, 0 errors. The real Spring test registers and reads a suite through HTTP
with distinct purposes and the independent H2 store.

Full Resource Gateway verification:

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
```

Result: 1736 tests, 0 failures, 0 errors, 34 conditional skips. Real browser DOM/workflow suites and
the repackaged Spring Boot JAR completed successfully.

Schema consumer verification:

```bash
mvn -f resource-gateway-test-kit/pom.xml clean verify
```

Result: 13 tests, 0 failures, 0 errors. The test-kit JAR contains the updated canonical
`testing-control-plane-v1.schema.json`.

## Deliberate Non-Claims

This increment does not yet provide:

- a suite execution endpoint or suite-run persistence;
- aggregate node/edge coverage evaluation or a promotion verdict;
- test-kit methods, JUnit suite projection, CI command, or canvas `Save as governed suite`;
- run-evidence linkage back to the exact suite revision;
- signed suite artifacts, retention lifecycle, stale-impact indexes, or multi-region replication.

Those are runner and product adapters over this registry. Until they land, an immutable suite is a
governed execution manifest, not proof that its cases have run or passed.
