# Execution Data Control Plane Stage 2: TestSuite Registry Verification

> Verified: 2026-07-24
>
> Scope: canonical immutable `bloge.testSuite.v1` through `v5` values stored in the
> full-enterprise-scope `bloge.storedTestSuite.v2` registry; runner semantics are specified in
> their own verification documents

## Delivered Boundary

This increment turns a reviewed group of cases into a server-authoritative testing asset. It adds:

- `TestSuite`, case type, exact target, exact fixture reference, coverage policy, promotion policy,
  classification, and metadata records with clear Java documentation;
- immutable `(tenant, organization, project, environment, region, suiteId, revision)` JDBC
  persistence in the independent test-runtime database;
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
4. Every case references an existing fixture in the same verified tenant, organization, project,
   environment and region.
5. Every fixture fingerprint and target fingerprint equals the immutable suite reference.
6. Suite classification dominates every fixture classification and caller clearance covers both.
7. Case ids are unique; graph inputs are JSON objects; cases and JSON body sizes are bounded.
8. Required case types, minimum case count, assertion density, and promotion minima are satisfiable;
   a policy that requires certification rejects an ineligible frozen target.
9. Equivalent repeated registration is idempotent; different content at the same revision conflicts.
10. Production identities and security-audit failures fail closed before registry access.
11. Case inputs, case metadata, and suite metadata recursively copy and freeze JSON maps,
    collections, and arrays; cycles, non-string object keys, and nesting beyond 128 containers fail.
12. A newly registered envelope must use `bloge.storedTestSuite.v2`, carry all five enterprise
    scope dimensions, and have suite id and revision equal to the exact decoded v1-v5 suite
    generation. Its indexed fingerprint must equal a fresh canonical fingerprint of that generation.
13. Every read result is bound to the complete authorized
    `(tenant, organization, project, environment, region, suiteId, revision)` lookup key. A
    different but internally valid stored revision is an integrity failure, not a successful lookup.
14. Every create receipt must equal the submitted immutable schema, scope, id, revision, and content
    fingerprint. Idempotent retries retain the authoritative first writer's `createdAt` and
    `createdBy`; those provenance fields must remain complete but need not equal the retry.
15. Malformed/unsupported stored JSON, envelope-content drift, cross-scope substitution, and receipt
    substitution emit a payload-free `TEST_SUITE_INTEGRITY_INVALID` security event and return
    `503 RG.TEST.SUITE_INTEGRITY_INVALID`. Database connectivity failures remain
    `RG.TEST.SUITE_STORE_UNAVAILABLE`; valid different content at the same immutable key remains a
    `409 RG.TEST.SUITE_REVISION_CONFLICT`.
16. The v2 table stores a second `binding_fingerprint` over full scope, suite id, revision and
    content fingerprint. Moving a self-consistent suite row by changing indexed scope columns is an
    integrity failure.
17. `bloge.storedTestSuite.v1` and the legacy table remain migration inputs only. Full-scope
    repository reads, execution, recovery and Scenario compilation never search or promote them.

No API resolves `latest`. A suite cannot be used as a lower-classification wrapper around restricted
fixtures, and a missing fixture is not deferred until execution.
Coverage references use collision-free invocation-site ids and edge endpoint pairs, not local node
or edge names that can repeat in nested graphs.

## Migration And Isolation

The v1 and v2 tables are deliberately separate. Deployment does not guess organization, project or
region from a tenant/environment-only row, because any such guess can silently assign a business
asset to the wrong owner.

For every retained v1 suite:

1. export or recover the authoritative `TestSuite` definition and each referenced FixtureBundle
   from its governed source;
2. authenticate as the destination tenant/organization/project/environment/region;
3. register each fixture through the normal v2 fixture API;
4. register the suite through the normal v2 suite API and verify the returned scope and fingerprint;
5. recompile dependent MirrorPlans and ScenarioPacks against the v2 coordinates;
6. retain the v1 row read-only for rollback/audit, then remove it under the deployment retention
   policy after all references and evidence have expired.

There is intentionally no lazy read-through, automatic promotion or "default project". If the
authoritative source is unavailable, the asset remains quarantined instead of entering execution.

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

It proves mutable-object detachment, deep JSON immutability, cycle/depth/key bounds, suite v1-v5
compatibility, alternate-repository substitution, idempotent first-writer provenance, valid JSON
tampering, malformed JSON, same-id reuse in different projects, legacy lookup isolation, indexed
scope movement detection and exact JDBC scope lookup.

Full Resource Gateway verification:

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
```

The 2026-07-24 full Java gate executed `4,985` tests with zero failures, zero errors,
and three profile-conditional skips. It includes the real-Chrome authoring workflow and direct
database corruption tests for both FixtureBundle and TestSuite v2 scope bindings.

Schema consumer verification:

```bash
mvn -f resource-gateway-test-kit/pom.xml clean verify
```

The test-kit requires v2 stored envelopes, verifies all scope fields, and packages the updated
canonical `testing-control-plane-v1.schema.json`. Its independent `clean verify` gate executed
`344/344` behavior tests and passed the strict JavaDoc and shaded-JAR build. The authoring frontend
executed `150/150` Vitest cases and completed its TypeScript/Vite production build.

## Deliberate Non-Claims

This increment proves local canonical consistency and ownership isolation. It does not prove that the
storage source is honest: an authority able to replace both suite JSON and its indexed fingerprint can
still mint a self-consistent row. External signatures, independently witnessed/WORM commitments,
multi-region replication consistency, backup/restore verification, and non-H2 dialect certification
remain separate work. A stored suite is still an execution manifest; only generation-matched signed
terminal evidence proves that a particular run passed.
