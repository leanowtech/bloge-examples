# Execution Data Control Plane Stage 2: TestSuite Registry Verification

> Verified: 2026-07-20
>
> Scope: canonical immutable `bloge.testSuite.v1` through `v5` registry values and repository trust
> boundaries; runner semantics are specified in their own verification documents

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

The registry now also treats every Java object and persistence adapter as an untrusted ownership
boundary. Request admission first round-trips the exact suite generation through the canonical codec.
Database create/read and service create/read then independently reconstruct and verify a
`StoredTestSuite` snapshot. This is deliberately redundant: a compliant JDBC adapter does not make an
alternate repository implementation implicitly trusted.

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
11. Case inputs, case metadata, and suite metadata recursively copy and freeze JSON maps,
    collections, and arrays; cycles, non-string object keys, and nesting beyond 128 containers fail.
12. A stored envelope must use `bloge.storedTestSuite.v1`; its suite id and revision must equal the
    exact decoded v1-v5 suite generation, and its indexed fingerprint must equal a fresh canonical
    fingerprint of that generation.
13. Every read result is bound to the complete authorized
    `(tenant, environment, suiteId, revision)` lookup key. A different but internally valid stored
    revision is an integrity failure, not a successful lookup.
14. Every create receipt must equal the submitted immutable schema, scope, id, revision, and content
    fingerprint. Idempotent retries retain the authoritative first writer's `createdAt` and
    `createdBy`; those provenance fields must remain complete but need not equal the retry.
15. Malformed/unsupported stored JSON, envelope-content drift, cross-scope substitution, and receipt
    substitution emit a payload-free `TEST_SUITE_INTEGRITY_INVALID` security event and return
    `503 RG.TEST.SUITE_INTEGRITY_INVALID`. Database connectivity failures remain
    `RG.TEST.SUITE_STORE_UNAVAILABLE`; valid different content at the same immutable key remains a
    `409 RG.TEST.SUITE_REVISION_CONFLICT`.

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

Current integrity-focused command:

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=StoredTestSuiteIntegrityTest,TestSuiteDeepImmutabilityTest,\
FixtureBundleDeepImmutabilityTest,StoredFixtureBundleIntegrityTest,\
TestSuiteRegistryServiceTest,TestRuntimePersistenceTest,TestSuiteV4Test,TestSuiteV5Test test
```

It proves mutable-object detachment, deep JSON immutability, cycle/depth/key bounds, v1-v5
compatibility, alternate-repository substitution, idempotent first-writer provenance, valid JSON
tampering, malformed JSON, and exact JDBC scope lookup. The final count is recorded after the full
gate below. Result: 48 tests, 0 failures, 0 errors, 0 skips.

Full Resource Gateway verification:

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
```

Result: 3053 tests, 0 failures, 0 errors, and 2 conditional browser skips. The 35 configured
real-browser tests completed, and Maven rebuilt the executable Spring Boot JAR.

Schema consumer verification:

```bash
mvn -f resource-gateway-test-kit/pom.xml clean verify
```

Result: 13 tests, 0 failures, 0 errors. The test-kit JAR contains the updated canonical
`testing-control-plane-v1.schema.json`.

## Deliberate Non-Claims

This increment proves local canonical consistency and ownership isolation. It does not prove that the
storage source is honest: an authority able to replace both suite JSON and its indexed fingerprint can
still mint a self-consistent row. External signatures, independently witnessed/WORM commitments,
multi-region replication consistency, backup/restore verification, and non-H2 dialect certification
remain separate work. A stored suite is still an execution manifest; only generation-matched signed
terminal evidence proves that a particular run passed.
