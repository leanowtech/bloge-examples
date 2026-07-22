# Stage 4 Physical Provider Inventory Managed Trust-Root Kernel Verification

## Decision

The physical-attempt provider-inventory protocol now owns a distinct, versioned managed
deployment/witness trust-root kernel. It does not reuse the suite-stability serving-inventory wire
document, media type, durable-floor namespace, or health schema. The shared
`AuthorityKey`/`AuthoritySignature` records remain transport-neutral Ed25519 value objects only.

This increment freezes and verifies the kernel. It does **not** yet claim that the existing dynamic
physical provider-inventory consumer or Spring test/staging composition uses managed roots.

## Closed invariants

| Failure class | Enforced invariant |
| --- | --- |
| Half rotation | Deployment-root and witness-root quorums sign one canonical material carrying both runtime key sets and thresholds. |
| Domain collapse | Four bootstrap/runtime trust domains and both authority/key sets must be independent. |
| Rollback or fork | A database-clock durable floor accepts only genesis, the exact current generation, or the exact linked successor. |
| Restart-bound runtime keys | Strict HTTPS/ETag refresh publishes one immutable dual key set; unknown runtime keys may trigger one cooldown-bounded synchronous refresh. |
| Stale trust | Source age, signed validity, active key lifecycle, threshold availability, protocol headers, document size, redirects, and closure all fail closed. A `304` cannot extend signed expiry. |
| Split local state | Publication verification and durable-floor acceptance finish before the volatile immutable snapshot becomes observable. |
| Secret leakage | Publication carries public verification material only; health exposes bounded aggregate state without URI, ETag, set, authority, key, or fingerprint identity. |

## Versioned artifacts

- `bloge.testSuiteStabilityPhysicalAttemptProviderInventoryTrustRootPublication.v1`
- `bloge.testSuiteStabilityPhysicalAttemptProviderInventoryTrustRootMaterial.v1`
- `bloge.testSuiteStabilityPhysicalAttemptProviderInventoryTrustRootGeneration.v1`
- `bloge.testSuiteStabilityPhysicalAttemptProviderInventoryDynamicTrustRootSnapshot.v1`
- `application/vnd.bloge.physical-attempt-provider-inventory-trust-roots.v1+json`
- `X-BLOGE-Physical-Provider-Inventory-Trust-Root-Protocol`
- [`physical-attempt-provider-inventory-trust-root-publication-v1.schema.json`](schemas/resource-gateway-testing/physical-attempt-provider-inventory-trust-root-publication-v1.schema.json)

The database floor uses the dedicated
`rg_test_suite_stability_physical_provider_inventory_trust_root_*` namespace, so an unrelated
serving-inventory head cannot authorize or block a physical provider-inventory rotation.

## Verification

Focused Maven gate:

```text
ConfiguredTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootAuthorityTest  6/6
DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootAuthorityTest     9/9
DatabaseTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloorTest        5/5
PhysicalAttemptProviderInventoryProtocolSchemaTest                                  8/8
Total                                                                               28/28
```

The tests use real Ed25519 signatures and cover atomic A-to-B key rotation, unknown-key refresh,
signed threshold revocation, source failure/recovery, hard age, background close, exact HTTP media
and protocol negotiation, redirect rejection, unsafe settings, insufficient/incorrect quorums,
non-canonical documents, root/runtime authority overlap, rollback, fork, predecessor mismatch,
sequence gap, durable restart recovery, concurrent writers, and stored-row corruption.

Final project gates:

```text
resource-gateway-examples clean verify
Tests run: 4289, Failures: 0, Errors: 0, Skipped: 2
Surefire XML reports: 480
Total time: 07:58 min

resource-gateway-test-kit clean verify
Tests run: 231, Failures: 0, Errors: 0, Skipped: 0
Surefire XML reports: 25
Total time: 11.876 s
```

The Resource Gateway browser gate contains 49 tests: 47 executed and 2 were conditionally skipped,
with no failure or error. The 40,013,876-byte executable JAR contains 21 managed physical
provider-inventory trust-root class entries. Both the ordinary test-kit JAR and shaded CLI JAR carry
the new authoritative Schema; `jq empty` validates its JSON syntax. The six new public types pass
`javadoc --release 25 -Werror -Xdoclint:all` with zero warnings and zero errors.

The project-wide `mvn -DskipTests javadoc:javadoc` remains blocked by 16 pre-existing errors in
untouched types, including heading-order, unescaped markup, and stale `@param` diagnostics. The new
types do not appear in that failure set; this baseline debt is recorded rather than hidden by
weakening doclint.

## Remaining product boundary

The next increment must inject this authority into
`DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority`, bind the accepted inventory
to the exact trust-root generation, reverify unchanged inventory after root rotation, expose honest
descriptor/snapshot/capability state, and make test/staging Spring composition fail closed unless the
managed root source and durable floor are present. Until then, physical inventory runtime signer
keys remain process-start configuration and the product capability must stay false.
