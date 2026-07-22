# Resource Gateway Stage 4 Physical Provider Inventory External Non-Equivocation Core Verification

## 1. Increment Boundary

The dynamic physical provider inventory already verifies independent deployment and witness chains,
then stores their last accepted generation in the Resource Gateway database. That prevents ordinary
rollback while the database history is trustworthy, but a coordinated restore of application state
and database backup can still erase both local chains.

This increment freezes the domain protocol and fail-closed core needed to close that failure mode:

- one physical-provider-inventory-specific external sequence-anchor port;
- one canonical head that binds the deployment publication and independent witness together;
- external compare-and-append before local durable-floor advancement;
- exact retry after an externally successful but locally uncertain commit;
- separate external and non-zero Byzantine quorum capability facts;
- aggregate-only Actuator health and Tool Studio feature projection;
- additive v1 descriptor/capability Schema evolution.

It deliberately does **not** yet install the HTTP/quorum adapter in the physical Spring composition.
No existing deployment becomes externally anchored merely by upgrading to this commit.

## 2. Canonical Composite Head

`ExternallyAnchoredTestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor` reduces one
physical generation to a content-addressed composite:

```text
fingerprint({
  schemaVersion,
  scopeId,
  sequence,
  publicationMaterialFingerprint,
  witnessMaterialFingerprint
})
```

The external stream uses the stable id
`physical-attempt-provider-inventory-publication`. Its previous head is reconstructed from the exact
publication and witness predecessor fingerprints already carried by the accepted generation. A
notary quorum therefore cannot accept only one of the two mutable chains or silently substitute a
different scope/sequence.

The Java marker port prevents Spring from accidentally injecting a sequence authority governed by
the suite-stability, test-secret, or recovery-fleet trust policy. The shared
`SERVING_INVENTORY_PUBLICATION` enum is only the ordering class; the marker type and stream id provide
product-domain separation.

## 3. Failure Semantics

The write order is fixed:

1. compare-and-append the exact composite head to the external authority;
2. only after quorum success, advance the local database floor;
3. expose the new dynamic resolver generation only after the wrapped floor returns.

If the external operation fails or is ambiguous, the local floor is never touched. If the external
operation succeeds and the local transaction fails, an exact retry resubmits the same head. The
external authority's idempotent same-head acceptance then permits local repair without creating an
externally unanchored visible generation. A different head at the same sequence remains a conflict.

Construction rejects a non-durable local floor and an external authority that is unavailable,
non-durable, or not challenge-bound. `byzantineQuorumAnchored` is true only when the authority
descriptor proves a non-zero `3f+1 / 2f+1` fault model. A single external service is reported as
external anchoring but can never satisfy industrial `READY`.

## 4. Capability And Wire Contract

The provider-inventory descriptor adds two optional, backward-compatible booleans:

- `externalNonEquivocation`;
- `byzantineQuorumNonEquivocation`.

The runtime capability adds ordered blockers `EXTERNAL_ANCHOR_REQUIRED` and
`BYZANTINE_QUORUM_REQUIRED`. Byzantine truth without external truth is structurally invalid. Tool
Studio exports the same two facts as independent feature flags, so a governance consumer does not
need to infer trust strength from `READY` alone.

The two v1 JSON Schemas keep the properties optional for additive N/N-1 compatibility. Producers in
this repository always emit explicit booleans; old consumers may ignore them, while the current
capability projector treats absence as false.

## 5. Information Boundary

External-anchor health contains only status, timestamps, bounded counters, quorum dimensions,
managed-trust counters, bootstrap-root counters, and transport-security posture. It never includes
scope, stream, endpoint, authority id, key id, challenge, signature, or fingerprint. Snapshot reads
must perform no remote I/O.

## 6. Verification Evidence

Focused Maven verification passed 36 tests with zero failures, errors, or skips:

- 6 external-floor core tests cover ordering, domain separation, predecessor binding, external
  failure isolation, exact retry, unsafe construction, honest single-authority reporting, lifecycle,
  and health redaction;
- 10 capability tests cover the new blocker ordering and invalid implication;
- 5 Tool Studio tests cover disabled and ready feature projection;
- 6 Schema tests lock the additive wire shape;
- 9 dynamic-authority tests preserve refresh and fail-closed regressions.

`git diff --check` is clean. Maven still reports the pre-existing local artifact metadata warning for
`bloge-durable` and `bloge-test`, whose published POM omits the `bloge-execution-control` version; it
does not affect this focused gate.

## 7. Residual Gap

This core removes the protocol-design uncertainty but not the deployment gap. The next increment
must wire the strict HTTP/quorum implementation into the physical test/staging composition, require
managed receipt trust and complete-chain bootstrap roots in staging, publish aggregate health, add
strict configuration/YAML tests, and prove startup failure for every unsafe downgrade. Managed
deployment/witness trust-root hot rotation, N/N-1 inventory backfill, retention/evidence lifecycle,
real process/container providers, and production HA/DR/chaos certification still remain.

Relative to the complete industrial testability plan, the estimated substantive gap is now about
15%. It remains outside the allowed +/-8% completion band.
