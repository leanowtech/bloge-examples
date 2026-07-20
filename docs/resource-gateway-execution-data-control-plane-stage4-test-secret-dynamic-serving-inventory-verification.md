# Stage 4 Test-Secret Dynamic Serving-Inventory Verification

## Scope

This increment promotes the deployment-signed test-secret serving inventory from a startup-only
document to a strictly versioned, witnessed and atomically refreshed runtime authority. A signed
`ACTIVE` successor keeps resolution open; a signed `REVOKED` successor closes it immediately.
Transport, parsing, signature, freshness, predecessor, witness or durable-floor ambiguity also
closes resolution.

This increment originally kept deployment and witness public keys static. The subsequent
[managed trust-root increment](resource-gateway-execution-data-control-plane-stage4-test-secret-trust-root-rotation-verification.md)
now rotates those daily runtime verification keys without restart through one atomic dual-quorum
publication. Offline bootstrap roots remain deployment trust anchors. External non-equivocation,
mTLS/pinning, certified multi-site HA, chaos/DR SLOs, and automatic hot adoption of a changed fleet
topology are still not claimed.

## Protocol

The HTTPS endpoint serves media type
`application/vnd.bloge.test-secret-authority-serving-inventory.v1+json` and must echo:

```text
X-BLOGE-Test-Secret-Serving-Inventory-Protocol:
  bloge.testSecretAuthorityServingInventoryPublication.v1
```

The envelope contains three independently verified layers:

1. `bloge.testSecretAuthorityServingInventory.v1`: exact scope, cohort, artifact, response
   protocol, test-secret authority id and sorted serving-slot inventory.
2. `bloge.testSecretAuthorityServingInventoryPublication.v1`: monotonic `ACTIVE` or `REVOKED`
   deployment decision, policy, validity window and exact predecessor.
3. `bloge.testSecretAuthorityServingInventoryWitness.v1`: independent checkpoint over the exact
   publication fingerprint with its own predecessor and validity window.

Deployment and witness signatures are distinct-authority Ed25519 M-of-N quorums. Trust domains,
authority identities and public-key material must not overlap. The strict parser rejects duplicate,
unknown, trailing and private fields. Authoritative Schemas:

- [`test-secret-authority-serving-inventory-v1.schema.json`](schemas/resource-gateway-testing/test-secret-authority-serving-inventory-v1.schema.json)
- [`test-secret-authority-serving-inventory-publication-v1.schema.json`](schemas/resource-gateway-testing/test-secret-authority-serving-inventory-publication-v1.schema.json)
- [`test-secret-authority-v1.schema.json`](schemas/resource-gateway-testing/test-secret-authority-v1.schema.json)

## Commit Protocol

One refresh follows this order:

1. Fetch once with strict vendor `Accept`, exact protocol header, bounded timeout, no redirect and
   optional `If-None-Match`.
2. Parse the complete candidate under duplicate/unknown/trailing rejection.
3. Verify nested inventory fingerprint, deployment signatures, policy, time window and exact local
   scope/cohort/artifact/protocol/authority/instance binding.
4. Verify publication fingerprint, state, validity and predecessor.
5. Verify independent witness fingerprint, quorum, validity and predecessor.
6. Atomically accept the publication and witness generation in the namespaced database floor.
7. Publish the complete local snapshot.

No partial candidate becomes observable. A failed refresh preserves the last verified head for
diagnosis but marks the source unavailable, so the HTTP secret adapter performs no authority
request. A valid successor can recover without restart. A valid `304` renews source freshness but
cannot bypass signed document expiry or the independent maximum snapshot age.

The database floor rejects rollback, same-sequence fork, sequence gaps, either predecessor
mismatch and corrupt stored records. The `test-secret/` scope namespace prevents collision with
suite-stability inventory floors.

## Cohort Boundary

The cohort policy freezes the nested inventory identity at process start. Runtime publication
successors may revoke or reactivate that exact inventory without restart. A publication carrying a
new member set or inventory revision is cryptographically checked, but the running cohort rejects
the resulting policy divergence. Fleet topology changes therefore require a coordinated new
deployment cohort; this prevents one replica from silently hot-adopting a different membership
authority while peers still serve the previous generation.

## Operational Truth

Actuator health and descriptors are local reads and contain only state, aggregate counts, timing,
failure family and thresholds. They omit URI, ETag, member ids, authority/key ids, fingerprints,
signatures and key material.

Capability flags are independent:

- `testSecretAuthorityDynamicServingInventory`
- `testSecretAuthoritySignedInventoryRevocation`
- `testSecretAuthorityWitnessedInventoryPublication`
- `testSecretAuthorityDurableInventoryPublicationFloor`
- `testSecretAuthorityDynamicServingInventoryReady`

The ready flag requires active local publication, converged exact cohort, independent witness and
durable floor simultaneously. A configured feature is not reported ready during revocation,
refresh failure, source expiry or cohort divergence.

## Automated Evidence

`DynamicTestSecretAuthorityServingInventoryAuthorityTest` covers witnessed bootstrap, aggregate
health, ETag `304`, hard source age, signed revoke/recover, invalid-candidate atomicity, durable
restart floor, rollback/gap rejection, authority independence and real HTTP negotiation.

`DatabaseTestSecretAuthorityServingInventoryPublicationFloorTest` covers reconstruction,
idempotent head acceptance, domain namespace isolation, rollback/fork/gap/predecessor rejection and
stored-record corruption. `HttpTestSecretAuthorityTest` proves a revoked inventory blocks network
I/O. `TestRuntimeProfileIsolationTest` proves real Spring assembly of dynamic authority, database
floor, health and resolution descriptor. Integration and test-kit tests freeze capability and wire
truth.

Focused gate:

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DynamicTestSecretAuthorityServingInventoryAuthorityTest,\
DatabaseTestSecretAuthorityServingInventoryPublicationFloorTest,\
HttpTestSecretAuthorityTest,TestRuntimeProfileIsolationTest,\
ToolStudioIntegrationServiceTest test
```

Release gates:

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
mvn -f resource-gateway-test-kit/pom.xml clean verify
```

Latest full verification on 2026-07-20:

- Resource Gateway: 3,171 tests, 0 failures, 0 errors, 2 skipped across 344 Surefire reports.
- Test kit: 230 tests, 0 failures, 0 errors, 0 skipped across 24 Surefire reports; shaded JAR and
  Javadoc packaging also completed.

## Deliberate Gaps

- Daily deployment and witness runtime keys now rotate without restart, but their offline bootstrap
  roots still require a governed deployment ceremony.
- The local inventory and managed-root database floors are durable but not externally anchored against a fully compromised
  database; there is no cross-region witness gossip or Byzantine non-equivocation proof.
- Publication transport has no built-in mTLS identity, certificate pinning or signed endpoint
  discovery.
- Scheduler and endpoint behavior have deterministic tests, but production HA, partition, clock,
  KMS/HSM, backup-restore and disaster-recovery certification remain open.
- A changed nested fleet topology requires a coordinated cohort deployment rather than hot
  membership adoption.
