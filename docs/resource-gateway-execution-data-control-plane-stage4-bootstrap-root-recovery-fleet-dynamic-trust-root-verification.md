# Stage 4 Recovery Fleet Dynamic Trust-Root Verification

## 1. Closed root cause

The recovery-fleet inventory authority previously accepted only deployment and witness runtime
verification keys supplied at construction time. The signed dual-root publication and durable
generation floor protected restart-time configuration, but they were not a runtime authority.
Operators therefore still had to restart every replica to rotate either verifier domain, and a
partial rollout could expose deployment and witness keys from different generations.

This increment makes one verified dual-domain key set the runtime unit of change. It adds a bounded
remote source and connects that source directly to the dynamic recovery-fleet inventory consumer.
No private key, lane runtime, business payload, endpoint, ETag, key id, or fingerprint is exposed by
health output.

## 2. Runtime protocol

`DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority` consumes the
existing strict
`bloge.externalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication.v1`
publication under these transport constraints:

| Boundary | Enforced rule |
| --- | --- |
| Transport | HTTPS only; plain HTTP is restricted to an explicit loopback test mode |
| Redirects | Never followed |
| Media type | Exactly `application/vnd.bloge.bootstrap-root-recovery-fleet-inventory-trust-roots.v1+json` |
| Protocol header | Exactly one header matching the publication Schema version |
| Entity validator | One strong, quoted ETag; weak, blank, duplicate, or changed 304 validators are rejected |
| Response size | At most 512 KiB |
| Parser | Duplicate fields, unknown fields, and trailing tokens are rejected |
| Freshness | Request deadline, refresh interval, unknown-key cooldown, and hard snapshot age are finite and cross-validated |

A `200` response carrying changed content under the previous ETag is invalid. A `304` renews only
source freshness and must retain the exact validator. Snapshot reads and health reads never perform
network or database I/O. Clock rollback and the exact hard-age boundary both close admission.

## 3. Atomic publication order

Each modified document is processed in this order:

1. Strictly parse and fingerprint the complete publication.
2. Verify exact scope, fleet, protocol, policy, four trust domains, time window, and predecessor.
3. Verify independent deployment-root and witness-root M-of-N Ed25519 quorums.
4. Construct one immutable `VerifiedKeySet` containing both runtime verifier domains and thresholds.
5. Advance the durable trust-root generation floor.
6. Publish the key set and its generation fingerprint in one process-local state replacement.

The floor therefore precedes visibility. Rollback, same-sequence fork, gap, broken predecessor,
ETag equivocation, invalid lifecycle, unsatisfied quorum, and floor outage leave no new key set
observable. A correctly signed revocation may advance the floor, but it closes runtime key access
when either runtime threshold is no longer satisfiable.

## 4. Unknown-key refresh

The inventory consumer supplies both deployment and witness signature references to `keysFor`.
When either domain contains an unknown key, the authority performs one synchronous refresh under
the same refresh lock used by the background scheduler. A bounded cooldown prevents a stream of
unknown key ids from amplifying remote requests. The returned key maps, thresholds, trust domains,
and generation fingerprint all come from the same immutable `VerifiedKeySet`; no separate mutable
read can combine keys from generation N with the fingerprint from generation N+1.

The refresh failure is sticky for admission until a later complete verified successor succeeds.
The last verified material remains available only for aggregate diagnostics.

## 5. Inventory-consumer fence

`DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority` retains its static-key
constructors for compatibility and adds a managed-root constructor. In managed mode it:

- verifies deployment publication, nested inventory, and witness checkpoint with one exact key-set
  generation;
- stores the deployment signature threshold that was used for that generation instead of reading a
  later threshold;
- reports `TRUST_ROOT_<status>` whenever the root source, lifecycle, quorum, or freshness closes;
- reports `TRUST_ROOT_GENERATION_UNVERIFIED` when a newer key-set generation is visible but the
  cached inventory has not yet been verified against it;
- re-verifies the complete cached publication on every inventory `304` before renewing freshness;
- fails closed when the cached publication is not valid under the new roots.

This generation fence is intentionally checked on every observation and around the existing worker
lane/cursor commit fences. A root rotation cannot silently authorize a cached inventory merely
because its content endpoint returned `304 Not Modified`.

## 6. Failure vocabulary

| Condition | Root status / inventory observation | Admission |
| --- | --- | --- |
| Verified current roots | `HEALTHY` | Eligible after inventory verification |
| Root source or protocol failure | `REFRESH_UNAVAILABLE` / `TRUST_ROOT_REFRESH_UNAVAILABLE` | Closed |
| Root hard age or clock rollback | `SOURCE_EXPIRED` / `TRUST_ROOT_SOURCE_EXPIRED` | Closed |
| Deployment runtime threshold unavailable | `DEPLOYMENT_THRESHOLD_UNAVAILABLE` | Closed |
| Witness runtime threshold unavailable | `WITNESS_THRESHOLD_UNAVAILABLE` | Closed |
| Root generation changed before inventory refresh | `TRUST_ROOT_GENERATION_UNVERIFIED` | Closed |
| Cached inventory fails new-root verification | inventory `REFRESH_UNAVAILABLE` | Closed |
| Authority closed | `CLOSED` / `TRUST_ROOT_CLOSED` | Closed |

Health details contain only state, sequence, aggregate thresholds/counters, last refresh time,
bounded timing policy, and floor-strength booleans. They deliberately omit deployment identity and
cryptographic material. The machine-readable shape is frozen by the strict
[`bloge.externalSequenceAnchorBootstrapRootRecoveryFleetInventoryDynamicTrustRootSnapshot.v1`](schemas/resource-gateway-testing/external-sequence-anchor-bootstrap-root-recovery-fleet-inventory-dynamic-trust-root-snapshot-v1.schema.json)
Schema.

Managed inventory descriptors additionally expose only closed booleans:
`managedTrustRootRefresh`, `atomicDualTrustRootPublication`,
`externallyAnchoredTrustRootFloor`, `byzantineQuorumAnchoredTrustRootFloor`,
`externalInventoryNonEquivocation`, and `byzantineQuorumInventoryNonEquivocation`. The combined
external claim is true only when every mutable ordering stream is external-first; the Byzantine
claim additionally requires intersecting quorum anchors for every such stream.

## 7. Verification

The focused test class uses real Ed25519 signatures and verifies:

- restart-free atomic rotation of both runtime verifier domains through the managed inventory;
- unknown-key synchronous refresh and durable floor advancement;
- inventory `304` re-verification after a disjoint root rotation;
- signed threshold revocation and immediate inventory fail-closed behavior;
- source failure followed by a valid successor recovery;
- source-age renewal without snapshot I/O and hard expiry;
- fork, gap, broken predecessor, and same-ETag changed-content rejection;
- exact HTTP media/protocol/ETag behavior and redirect rejection;
- unsafe settings and unavailable bootstrap rejection;
- aggregate-only Actuator health and idempotent close.

Focused gate:

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthorityTest,\
ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryDynamicTrustRootSnapshotProtocolSchemaTest,\
DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthorityTest test
```

The dynamic authority/integration gate runs 10 tests, the snapshot protocol/Schema consistency gate
runs 4, and all 13 existing dynamic-inventory adversarial tests remain green. Combined result:
27 tests, 0 failures, 0 errors, 0 skips.

The four touched public API types also pass `javadoc --release 25 -Werror -Xdoclint:all`.

The final independent project gate completed with 3547 tests, 0 failures, 0 errors, and 2
environment-conditional browser skips, then successfully repackaged the executable Spring Boot JAR:

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
```

## 8. Honest boundary

This increment closed the dynamic source and managed inventory-consumer kernel. The subsequent
[managed trust-root Spring increment](resource-gateway-execution-data-control-plane-stage4-bootstrap-root-recovery-fleet-managed-trust-root-spring-verification.md)
now provides deployment properties, lifecycle/health composition, a staging downgrade fence, and
capability v2. mTLS/certificate pinning, an externally witnessed Byzantine trust-root floor,
HSM/KMS custody, production databases, HA, DR, and chaos certification remain deployment/product
gates; their absence must not be represented as end-to-end production readiness.
