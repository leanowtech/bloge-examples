# Stage 2 Fixture Registry Integrity Verification

## Scope

This increment makes the stored-fixture registry's "immutable, content-addressed revision" claim an
enforced trust-boundary invariant. The database index columns, stored envelope, serialized bundle,
repository object graph and lookup key are separate corruption domains. Trusting only the indexed
`fingerprint` allowed changed `bundle_json`, retained mutable aliases, or a valid object returned for
the wrong tenant/organization/project/environment/region/id/revision to enter execution and suite
publication.

The fix is deliberately shared by graph/operator execution, suite dependency validation and durable
recovery. It does not add another fixture representation or change `bloge.fixtureBundle.v1`.

## Frozen Invariants

`StoredFixtureBundleIntegrity.verifiedSnapshot(...)` requires all of the following before a stored
fixture can cross a repository trust boundary:

1. a newly registered stored envelope uses `bloge.storedFixtureBundle.v2` and has complete tenant,
   organization, project, environment, region, author, timestamp, id and positive revision;
2. the nested bundle uses `bloge.fixtureBundle.v1`;
3. envelope and bundle id/revision are exactly equal;
4. the indexed fingerprint has the canonical `sha256:<64 lowercase hex>` shape;
5. recomputing `ProtocolFingerprint` over the deserialized bundle equals the indexed fingerprint.
6. the bundle is serialized and reconstructed before use, producing a fresh canonical JSON value
   graph rather than returning a repository-owned reference;
7. nested maps, collections and arrays in selectors, behaviors, assertions and metadata are copied
   recursively and exposed as unmodifiable values; cycles and nesting deeper than 128 fail closed;
8. reads match the complete authorized
   tenant/organization/project/environment/region/id/revision lookup key;
9. creates return the exact submitted schema, scope, id, revision and fingerprint; an idempotent
   retry preserves the original registry author and timestamp rather than overwriting provenance;
10. only the detached snapshot, never the pre-verification object, enters planning or execution.
11. the v2 row's `binding_fingerprint` matches full scope, id, revision and content fingerprint, so
    changing indexed ownership columns cannot transplant an otherwise valid bundle.
12. legacy `bloge.storedFixtureBundle.v1` rows are accepted only by explicit migration tooling and
    legacy repository methods; v2 reads, execution, recovery and Scenario compilation fail closed
    instead of promoting them.

Fingerprint comparison is constant-time. `FixtureBundleIntegrityException` contains no id, scope,
fingerprint or payload. This prevents corruption and cross-scope substitution from becoming an
error-message oracle.

## Trust Boundaries

- `DatabaseFixtureBundleRepository` canonicalizes before create and after every JSON read. It writes
  and returns only the detached snapshot; a partially changed row or caller-owned nested alias is
  never accepted as a valid immutable revision.
- The database keeps `rg_test_fixture_bundles` and `rg_test_fixture_bundles_v2` separate. New
  authenticated API writes use only v2. Two organizations or projects may reuse the same local
  fixture id and revision without collision or visibility across scope.
- `TestExecutionApiService` canonicalizes before handing a create request to the repository, then
  verifies the immutable identity and content in the create receipt while preserving first-write
  provenance on idempotent retries. Reads bind the result to the exact authorized lookup key.
- `TestSuiteRegistryService` repeats exact-key snapshot verification before publishing a dependency.
  These checks keep alternate, mocked or future repository implementations from substituting a
  valid cross-scope revision or retaining a mutable value. Authorized synchronous callers receive
  stable
  `RG.TEST.FIXTURE_INTEGRITY_INVALID` with no payload details, and the required security audit stores
  only event type, outcome and reason code.
- `DurableTestRecoveryAuthorizer` binds the snapshot to the authorized lookup key before comparing
  the checkpoint's complete exact fixture reference. Corrupt or cross-key storage is a `503`
  dependency-authority outage; a valid same-key replacement with a new fingerprint remains a `409`
  exact-closure conflict. Recovery never guesses which content to trust.
- Suite fixture references and durable checkpoint references continue to pin the previous canonical
  fingerprint, so a validly re-fingerprinted same-key substitution cannot satisfy existing assets.

## Automated Evidence

- `FixtureBundleDeepImmutabilityTest` covers recursive copy/freeze and bounded cycle rejection for
  protocol JSON values.
- `StoredFixtureBundleIntegrityTest` covers canonical detachment of arbitrary mutable values,
  content drift, envelope id/revision/protocol drift, exact lookup-key binding, incomplete scope and
  payload-free diagnostics.
- `TestRuntimePersistenceTest` updates `bundle_json` directly while leaving the indexed fingerprint
  unchanged and proves that a reconstructed database repository rejects the row. It also proves a
  database create no longer returns or persists a caller-owned mutable value. Full-scope tests
  additionally prove same-id isolation across projects, ambiguous legacy lookup invisibility and
  scope-column movement rejection through the binding fingerprint.
- `TestExecutionApiServiceTest` and `TestSuiteRegistryServiceTest` use alternate in-memory
  repositories to return forged, cross-scope or substituted create results, then verify fail-closed
  public errors, zero execution persistence and payload-free security audits. They also prove an
  idempotent same-content create preserves the original author and timestamp.
- `DurableTestRecoveryAuthorizerTest` distinguishes a valid new content revision from a corrupt
  envelope and freezes the separate `409` versus `503` semantics.
- `BuiltInTestSuiteCatalogMaterializationIntegrationTest` materializes and reruns all seven governed
  graph suites, proving exact create-receipt verification remains compatible with catalog-wide
  idempotency.

The 2026-07-24 full Resource Gateway Java gate executed `4,985` tests with zero failures, zero
errors, and three profile-conditional skips. The independent test-kit executed `344/344` behavior
tests and passed strict JavaDoc plus ordinary/shaded JAR packaging; the authoring frontend executed
`150/150` Vitest cases and completed its TypeScript/Vite production build.

## Migration And Isolation

No process can derive organization, project or region ownership from a v1
tenant/environment-only envelope. Migration is therefore an explicit governed re-registration:

1. recover the authoritative FixtureBundle definition from its source registry or version control;
2. authenticate as the destination full enterprise scope;
3. submit it through the normal fixture registration API, which returns a v2 envelope;
4. compare the canonical content fingerprint and record the old-to-new scope mapping;
5. re-register dependent suites and recompile dependent ScenarioPacks;
6. keep the v1 row read-only until references and retention obligations expire.

Missing ownership evidence quarantines the legacy fixture. The runtime never falls back to a default
organization/project/region and never copies a v1 row into v2 on first read.

## Honest Remaining Gaps

- A canonical hash detects accidental corruption and partial tampering; it is not source
  authentication. An actor able to rewrite the complete fixture row and every independently stored
  reference requires signed registry attestation, an external transparency anchor or WORM controls
  to be detected.
- Backup verification, legal hold and cryptographic erasure remain deployment lifecycle concerns;
  this increment does not claim them.

## Reproduction

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=FixtureBundleDeepImmutabilityTest,StoredFixtureBundleIntegrityTest,TestRuntimePersistenceTest,TestExecutionApiServiceTest,TestSuiteRegistryServiceTest,DurableTestRecoveryAuthorizerTest,BuiltInTestSuiteCatalogMaterializationIntegrationTest test

mvn -f resource-gateway-examples/pom.xml clean verify
```
