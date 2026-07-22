# Stage 4 Physical Provider Inventory Managed Trust-Root Consumer Verification

## Decision

The dynamic physical-attempt provider-inventory consumer can now verify inventory and witness
publications with one restart-free, atomically published deployment/witness runtime-key snapshot.
The exact signed trust-root generation is part of the accepted inventory generation, so a root
rotation fences previously resolved provider wrappers even when the inventory endpoint returns
`304 Not Modified`.

This increment also upgrades the identity-free inventory descriptor and physical-attempt runtime
capability to v2. It does not yet install the managed-root authority through Spring test/staging
configuration; until that composition exists, the static-key path remains available for migration
but cannot advertise industrial readiness.

## Closed invariants

| Failure class | Enforced invariant |
| --- | --- |
| Key/generation TOCTOU | `VerifiedKeySet` carries the fingerprint of the root publication that produced its keys. Verification never reads keys and generation in separate operations. |
| Root rotation hidden by `304` | A changed root generation forces complete inventory and witness signature verification against the new immutable dual-domain key set. |
| Revoked signer resurrection | If the unchanged inventory no longer satisfies the rotated key thresholds, refresh fails closed and provider resolution becomes unavailable. |
| Stale resolved adapter | The private source-generation fingerprint includes inventory, witness, inventory material, and managed-root generation; wrappers resolved before any accepted rotation are fenced. |
| Torn capability claim | Capability projection brackets descriptor/cohort reads with exact inventory observations. Its top-level facts must equal the embedded descriptor facts. |
| Static-key readiness inflation | Capability v2 requires managed refresh, a healthy root sequence, one atomic dual-domain publication, a durable root floor, an external anchor, and a Byzantine quorum anchor. |
| Protocol drift | Descriptor v1 and capability v1 remain packaged for negotiation; strict v2 schemas define the current managed-root facts and readiness vocabulary. |
| Identity leakage | Descriptor, snapshot, capability, and Tool Studio feature flags expose only bounded status, sequence, and Boolean strength facts, never URI, ETag, authority, key, or fingerprint identity. |

## Runtime state transitions

1. Bootstrap fetches and verifies the inventory with one immutable managed `VerifiedKeySet`.
2. The consumer stores the key set's own generation fingerprint in `RefreshState`.
3. An ordinary inventory `304` reuses the accepted authority only while the root generation is
   unchanged.
4. A root-generation change plus inventory `304` re-runs publication and witness verification.
5. Successful re-verification publishes a new combined source generation and fences old wrappers.
6. A key-set/generation mismatch reports `TRUST_ROOT_GENERATION_UNVERIFIED`; revoked or
   insufficient signatures report refresh unavailable and admit no provider work.

## Versioned artifacts

- `bloge.testSuiteStabilityPhysicalAttemptProviderInventoryDescriptor.v1` retained
- `bloge.testSuiteStabilityPhysicalAttemptProviderInventoryDescriptor.v2` current
- `bloge.testSuiteStabilityPhysicalAttemptRuntimeCapability.v1` retained
- `bloge.testSuiteStabilityPhysicalAttemptRuntimeCapability.v2` current
- `bloge.testSuiteStabilityPhysicalAttemptProviderInventoryRefreshSnapshot.v2`
- [`physical-attempt-provider-inventory-descriptor-v2.schema.json`](schemas/resource-gateway-testing/physical-attempt-provider-inventory-descriptor-v2.schema.json)
- [`physical-attempt-runtime-capability-v2.schema.json`](schemas/resource-gateway-testing/physical-attempt-runtime-capability-v2.schema.json)

Tool Studio capability features now include managed-root refresh/availability, atomic dual-root
publication, durable root floor, external root anchoring, and Byzantine root-floor truth.

## Focused verification

```text
ConfiguredTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootAuthorityTest   6/6
DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthorityTest              10/10
TestSuiteStabilityPhysicalAttemptRuntimeCapabilityTest                              12/12
PhysicalAttemptProviderInventoryProtocolSchemaTest                                   8/8
ToolStudioPhysicalAttemptCapabilityTest                                               5/5
PhysicalAttemptProviderInventorySchemaPackagingTest                                  1/1
Total                                                                                42/42
```

The rotation test uses real Ed25519 inventory and witness signatures. It covers compatible root
rotation over an unchanged inventory, old-wrapper fencing, a deliberately torn key/generation
read that must remain unavailable, and removal of the old signers that must fail complete
re-verification. Protocol tests lock Java fields and capability status vocabulary to the current
schemas; the independent test-kit proves both historical and current schemas are packaged.

## Project gates

```text
Resource Gateway clean verify     4292 tests, 0 failures, 0 errors, 2 skips
Surefire independent aggregate     480 XML, 4292 tests, 0 failures, 0 errors, 2 skips
Independent test-kit clean verify  231 tests, 0 failures, 0 errors, 0 skips
Test-kit independent aggregate      25 XML,  231 tests, 0 failures, 0 errors, 0 skips
Strict JavaDoc                       0 warnings, 0 errors
```

The Resource Gateway build completed in 7 minutes 58 seconds and repackaged the Spring Boot
executable JAR. The independent test-kit copied all 75 testing-protocol schemas into its artifact;
its ordinary and shaded JAR gates, including both physical inventory descriptor and capability
versions, completed successfully.

## Remaining product boundary

The next increment must add a physical-domain external-first trust-root floor and Spring properties,
beans, health, test/staging downgrade fences, and strict transport configuration. Only that product
composition may turn the new managed-root capability facts true. Production organization
independence, HSM/KMS custody, root publisher HA/anti-equivocation, N/N-1 backfill, and certified
process/container providers remain outside this increment.
