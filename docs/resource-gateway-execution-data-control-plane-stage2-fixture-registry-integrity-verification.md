# Stage 2 Fixture Registry Integrity Verification

## Scope

This increment makes the stored-fixture registry's "immutable, content-addressed revision" claim an
enforced read invariant. The database index columns, stored envelope and serialized bundle are
separate corruption domains; trusting only the indexed `fingerprint` allowed a changed or damaged
`bundle_json` value to enter synchronous execution and suite publication.

The fix is deliberately shared by graph/operator execution, suite dependency validation and durable
recovery. It does not add another fixture representation or change `bloge.fixtureBundle.v1`.

## Frozen Invariants

`StoredFixtureBundleIntegrity.verify(...)` requires all of the following before a stored fixture can
cross a repository trust boundary:

1. the stored envelope uses `bloge.storedFixtureBundle.v1` and has a complete tenant, environment,
   author, timestamp, id and positive revision;
2. the nested bundle uses `bloge.fixtureBundle.v1`;
3. envelope and bundle id/revision are exactly equal;
4. the indexed fingerprint has the canonical `sha256:<64 lowercase hex>` shape;
5. recomputing `ProtocolFingerprint` over the deserialized bundle equals the indexed fingerprint.

The comparison is constant-time. `FixtureBundleIntegrityException` contains no id, scope,
fingerprint or payload. This prevents a corrupt fixture value from becoming an error-message oracle.

## Trust Boundaries

- `DatabaseFixtureBundleRepository` verifies before create and after every JSON read. A partially
  changed row is never returned as a valid immutable revision.
- `TestExecutionApiService` and `TestSuiteRegistryService` repeat verification after repository
  return. This keeps alternate, mocked or future repository implementations from bypassing the
  invariant. Authorized synchronous callers receive stable
  `RG.TEST.FIXTURE_INTEGRITY_INVALID` with no payload details, and the required security audit stores
  only event type, outcome and reason code.
- `DurableTestRecoveryAuthorizer` repeats verification before comparing the checkpoint's exact
  fixture reference. Corrupt storage is a `503` dependency-authority outage; a valid replacement
  revision with a new fingerprint remains a `409` exact-closure conflict. Recovery never guesses
  which content to trust.
- Suite fixture references and durable checkpoint references continue to pin the previous canonical
  fingerprint, so a validly re-fingerprinted same-key substitution cannot satisfy existing assets.

## Automated Evidence

- `StoredFixtureBundleIntegrityTest` covers canonical acceptance, content drift, envelope
  id/revision/protocol drift, incomplete scope and payload-free diagnostics.
- `TestRuntimePersistenceTest` updates `bundle_json` directly while leaving the indexed fingerprint
  unchanged and proves that a reconstructed database repository rejects the row.
- `TestExecutionApiServiceTest` and `TestSuiteRegistryServiceTest` use alternate in-memory
  repositories to return forged objects, then verify fail-closed public errors, zero persistence and
  payload-free security audits.
- `DurableTestRecoveryAuthorizerTest` distinguishes a valid new content revision from a corrupt
  envelope and freezes the separate `409` versus `503` semantics.

Focused verification runs 59 tests with zero failures, errors or skips.
The complete Resource Gateway `clean verify` runs 3,033 tests with zero failures and errors, two
conditional browser skips, 35 configured real-browser tests, and a successfully repackaged Spring
Boot executable JAR.

## Honest Remaining Gaps

- A canonical hash detects accidental corruption and partial tampering; it is not source
  authentication. An actor able to rewrite the complete fixture row and every independently stored
  reference requires signed registry attestation, an external transparency anchor or WORM controls
  to be detected.
- The production JDBC repository serializes on create and reconstructs on read. Alternate in-memory
  repositories are reverified at service entry, but a hostile implementation could still mutate a
  nested object between verification and use. A canonical immutable protocol-value snapshot remains
  the next defense-in-depth step.
- Backup verification, legal hold and cryptographic erasure remain deployment lifecycle concerns;
  this increment does not claim them.

## Reproduction

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=StoredFixtureBundleIntegrityTest,TestRuntimePersistenceTest,TestExecutionApiServiceTest,TestSuiteRegistryServiceTest,DurableTestRecoveryAuthorizerTest test

mvn -f resource-gateway-examples/pom.xml clean verify
```
