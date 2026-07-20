# Stage 4 Test-Secret Signed Serving-Inventory Verification

## Scope

This increment removes replica-local configuration as the sole authority for the test-secret
trust-cohort member set. It adds a deployment-owned, statically supplied and independently signed
inventory for the dynamic JWKS test-secret authority path.

It does not add remote inventory refresh, signed witness publication, KMS/HSM custody, mTLS, or
production HA/DR certification.

## Protocol

The strict envelope is `bloge.testSecretAuthorityServingInventory.v1`; its canonical signed
material is `bloge.testSecretAuthorityServingInventoryMaterial.v1`. The material binds:

- independent inventory trust domain, inventory id and monotonic revision;
- stable fleet scope and immutable deployment cohort;
- exact artifact fingerprint and test-secret response protocol;
- exact test-secret authority id;
- sorted, unique and complete 1..256 serving-slot set;
- accepted external policy fingerprint;
- whole-second issue, activation and hard-expiry times.

One through 32 distinct authorities may sign the material fingerprint. Verification requires the
configured Ed25519 M-of-N threshold and public-only key lifecycle. The parser rejects duplicate,
unknown, trailing and private fields.

Authoritative JSON Schema:
[`test-secret-authority-serving-inventory-v1.schema.json`](schemas/resource-gateway-testing/test-secret-authority-serving-inventory-v1.schema.json).

## Runtime Invariants

1. Signed material, not a replica-local list, owns the expected member set.
2. A configured member list is optional and equality-only.
3. The local instance, scope, cohort, artifact, protocol and authority id must all match signed
   material before startup completes.
4. Inventory validity is checked at startup, every heartbeat and every descriptor read.
5. Inventory expiry or binding drift closes secret resolution without a restart.
6. Every member publishes both its complete JWKS generation and signed-inventory source
   generation under a database-clock process-start lease.
7. Availability requires exact membership, one healthy JWKS generation and one inventory
   generation across all live replicas.
8. A stable-scope database revision floor rejects lower revisions and same-revision forks.
9. Only an active cohort may establish or advance the floor.
10. The HTTPS authority adapter checks the cohort before request I/O and after response signature
    verification.

## Operational Truth

`staging` requires signed inventory whenever the test-secret cohort is enabled. `test` keeps the
feature optional for local compatibility. Partial enabled configuration fails startup.

Actuator and authority descriptors expose aggregate counts, source type and attestation booleans;
they omit member ids, inventory fingerprints, policy identities, key ids and key material.

Capability flags are intentionally separate:

- `testSecretAuthorityDeploymentSignedInventory`: the external inventory protocol is assembled;
- `testSecretAuthorityDeploymentSignedInventoryReady`: the cohort is currently healthy and
  converged on exactly one inventory generation.

## Automated Evidence

`ConfiguredTestSecretAuthorityServingInventoryAuthorityTest` proves M-of-N verification,
authority-id binding, self-shrink rejection, strict JSON, tamper rejection, canonical member order,
distinct signing authorities and runtime hard expiry.

`DatabaseTestSecretAuthorityTrustCohortRepositoryTest` proves two-replica inventory-generation
convergence, split detection, recovery, monotonic revision-floor advance, rollback rejection and
same-revision fork rejection.

`TestSecretAuthorityTrustCohortMonitorTest` proves current inventory revalidation, unpublished
generation closure and expiry closure. `TestRuntimeProfileIsolationTest` proves Spring assembly,
aggregate readiness and staging fail-fast. `ToolStudioIntegrationServiceTest`, protocol-schema
tests and the independent test-kit freeze capability and wire-version truth.

Reproduce the focused gate:

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ConfiguredTestSecretAuthorityServingInventoryAuthorityTest,\
TestSecretAuthorityServingInventoryProtocolSchemaTest,\
TestSecretAuthorityProtocolSchemaTest,\
TestSecretAuthorityTrustCohortMonitorTest,\
DatabaseTestSecretAuthorityTrustCohortRepositoryTest,\
HttpTestSecretAuthorityTest,\
TestRuntimeProfileIsolationTest,\
ToolStudioIntegrationServiceTest test
```

Release gates:

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
mvn -f resource-gateway-test-kit/pom.xml clean verify
```

## Deliberate Gaps

- Static inventory replacement still requires restart.
- There is no independently signed publication/witness chain for runtime revoke or split-view
  detection outside the shared database.
- Test-secret inventory currently reuses internal stability cohort and cryptographic primitives;
  extraction into neutral kernels remains maintainability work.
- Signer custody, certificate pinning, mTLS, endpoint HA, non-H2 certification, backup rollback,
  chaos and disaster-recovery qualification are not claimed.
