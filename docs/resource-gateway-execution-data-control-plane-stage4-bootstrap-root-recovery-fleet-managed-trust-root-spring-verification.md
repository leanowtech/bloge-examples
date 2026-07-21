# Stage 4 Recovery Fleet Managed Trust-Root Spring Verification

## 1. Closed root cause

The dynamic recovery-fleet inventory consumer already supported restart-free atomic rotation of
its deployment and witness runtime verification keys. That interface was not reachable through the
product composition root: Spring always parsed static runtime keys, staging did not require managed
roots, the demo preflight did not recognize this mode, and the integration capability discarded the
root state already present in the authority descriptor.

This was a security downgrade path, not a missing convenience property. A deployment could claim
dynamic trust rotation while still pinning runtime keys for the lifetime of every process. This
increment makes the managed root source, its floor, lifecycle, health, staging policy, and capability
projection one coherent test/staging product path.

## 2. Composition interface

The existing dynamic-inventory prefix remains the public configuration seam:

```text
gateway.testing.external-sequence-anchor.bootstrap-root-recovery-fleet-dynamic-inventory
```

Managed runtime keys are selected by its nested `trust-roots` group. The common parent continues to
own exact deployment/fleet/artifact binding, accepted inventory policies, the inventory publication
URI, inventory refresh timing, and the reviewed local `LaneResolver`. The nested group owns only the
atomic dual-key publication source and bootstrap trust:

| Property | Meaning |
| --- | --- |
| `enabled` / `required` | Select managed roots and reject static fallback |
| `trust-root-set-id` | Stable durable dual-root stream identity |
| `accepted-policy-fingerprints` | Deployment-approved key-rotation policies |
| `deployment-root-domain` | Deployment bootstrap-root trust domain |
| `deployment-root-signature-threshold` | Deployment bootstrap M-of-N threshold |
| `deployment-root-authority-keys-json` | Public Ed25519 deployment bootstrap keys |
| `witness-root-domain` | Independent witness bootstrap-root trust domain |
| `witness-root-signature-threshold` | Witness bootstrap M-of-N threshold |
| `witness-root-authority-keys-json` | Public Ed25519 witness bootstrap keys |
| `publication-uri` | Strict HTTPS atomic dual-key source |
| refresh/timeout/unknown-key/maximum-age | Finite refresh and freshness policy |
| `allow-insecure-loopback` | Test-profile-only local HTTP escape hatch |

The configuration accepts no signer private key, provider credential, lane endpoint, or business
payload. Bootstrap public keys are still restart-bound trust anchors; the runtime verification keys
inside their signed publication rotate without restart.

## 3. Mode and downgrade invariants

Exactly one key mode is valid:

| Mode | Static runtime domains/thresholds/keys | Nested managed roots |
| --- | --- | --- |
| Test migration mode | Complete deployment and witness values | Disabled |
| Managed mode | Empty domains, zero thresholds, empty key arrays | Complete and enabled |
| Mixed mode | Any static value plus managed roots | Rejected |
| Partial mode | Missing source, policy, key group, threshold, or identity | Rejected |

Managed root and inventory publication URIs must differ. Bootstrap authority/key material must be
independent across deployment and witness groups. The signed root material itself enforces all four
runtime/bootstrap trust domains as distinct. URI, policy, key, threshold, identity, and timing checks
finish before either floor creates tables or either HTTP source is contacted.

When the `staging` profile is active, an enabled recovery fleet requires all of the following:

1. Dynamic inventory `required=true`.
2. Managed trust roots `required=true`.
3. Both modes enabled and fully configured.
4. Two distinct HTTPS sources.
5. Both insecure-loopback switches disabled.
6. No static runtime trust values.

The same checks exist in `scripts/visual-canvas-demo.sh` for fast operator feedback and in Spring for
the authoritative startup gate. Test keeps both static and managed paths available. Any active
`production` profile physically excludes the complete recovery-fleet configuration.

## 4. State and lifecycle order

The product composition performs work in this order:

1. Bind strict properties and run topology/mode/timing preflight.
2. Create or select one durable inventory-publication floor.
3. Create or select one durable trust-root publication floor.
4. Bootstrap the managed trust-root authority from a verified HTTP publication.
5. Bootstrap the inventory authority using one immutable root generation.
6. Re-read one descriptor during fleet preflight and require both dynamic inventory and healthy
   managed-root truth from that same descriptor.
7. Construct coordinator, worker, scheduler, health, SLO, and integration projections.

Spring dependency destruction reverses the owned part of this graph: scheduler and worker stop,
then the inventory refresh lane closes, then the root refresh lane closes. Caller-owned lane
resolver, database, and custom floor adapters remain outside that ownership.

The default root floor is
`DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor`. It namespaces
the shared durable kernel by deployment scope, fleet, and root-set id. Rebuilding the complete
Spring context over the same H2 database accepts exact replay but still rejects rollback, fork,
gap, predecessor drift, and corrupted floor state.

## 5. Capability protocol v2

The previous strict capability remains frozen as
`bloge.externalSequenceAnchorBootstrapRootRecoveryFleetCapability.v1`. The current integration
projection is a new version:

```text
bloge.externalSequenceAnchorBootstrapRootRecoveryFleetCapability.v2
```

The supported-object catalog advertises only the current v2 because the endpoint does not negotiate
or project a v1 representation. The frozen v1 schema remains historical compatibility evidence,
not a promise that the current endpoint can emit it. V2 adds only aggregate, identity-free root truth:

- managed refresh enabled and currently available;
- bounded root status and monotonic sequence;
- atomic dual-root publication and durable floor;
- external and Byzantine root-floor strength;
- combined inventory non-equivocation, which is true only when every mutable ordering stream meets
  the claimed strength.

The strict
[`external-sequence-anchor-bootstrap-root-recovery-fleet-capability-v2.schema.json`](schemas/resource-gateway-testing/external-sequence-anchor-bootstrap-root-recovery-fleet-capability-v2.schema.json)
encodes disabled/managed, healthy/available, durable/external/Byzantine, and combined-claim
implications. Capability reads use process-local immutable snapshots only and perform no HTTP,
database, lane, provider, or payload operation. URI, ETag, scope, fleet, root/key id, public key,
policy, material fingerprint, signature, and exception text remain private.

## 6. Operational checks

For a staging fleet, set the common fleet and dynamic-inventory variables, then configure:

```text
RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOTS_ENABLED=true
RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOTS_REQUIRED=true
RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOT_URI=https://...
RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOT_SET_ID=...
RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOT_POLICY_FINGERPRINTS=sha256:...
RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_DEPLOYMENT_ROOT_DOMAIN=...
RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_DEPLOYMENT_ROOT_THRESHOLD=...
RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_DEPLOYMENT_ROOT_KEYS_JSON=[...]
RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_WITNESS_ROOT_DOMAIN=...
RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_WITNESS_ROOT_THRESHOLD=...
RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_WITNESS_ROOT_KEYS_JSON=[...]
```

Static runtime domain, threshold, and key variables must remain empty, zero, and `[]`. After startup,
inspect `GET /api/integration/capabilities`: the structured `testability.recoveryFleet` object must
report capability v2, `managedTrustRootRefresh=true`, `managedTrustRootAvailable=true`,
`managedTrustRootStatus=HEALTHY`, and a positive root sequence. The root and inventory Actuator
health indicators must both be UP before recovery readiness is trusted.

## 7. Verification

The focused product-path tests use two real loopback HTTP servers, four independent Ed25519 signer
roles, strict publications, and the H2 durable database. They verify successful bootstrap, both
health indicators, descriptor/capability truth, lifecycle closure, and complete context rebuild over
both persisted floors. Negative cases reject static/managed mixing, one shared URI, staging required
downgrade, insecure staging transport, malformed/unknown properties, missing lane resolvers,
non-durable custom floors, duplicate inventory candidates, and script-level staging bypass before
network or DDL.

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=VisualCanvasDemoScriptTest,\
ExternalSequenceAnchorBootstrapRootRecoveryFleetDynamicInventoryConfigurationTest,\
ExternalSequenceAnchorBootstrapRootRecoveryFleetRuntimeConfigurationTest,\
ExternalSequenceAnchorBootstrapRootRecoveryFleetDynamicInventoryConfigurationSchemaTest,\
ExternalSequenceAnchorBootstrapRootRecoveryFleetCapabilityTest,\
ExternalSequenceAnchorBootstrapRootRecoveryFleetCapabilityProtocolSchemaTest,\
ToolStudioRecoveryFleetCapabilityTest test
```

The focused gate runs 57 tests with zero failures, errors, or skips. Four of those tests freeze the
strict dynamic-inventory configuration Schema against both Spring property records and the Java 25
generated configuration metadata. The wider recovery-fleet/profile/integration gate runs 270 tests
with zero failures, errors, or skips. Five changed recovery-fleet public types pass strict JavaDoc
with `-Werror -Xdoclint:all`. The final independent `clean verify` runs 3556 tests with zero failures,
zero errors, and two existing conditional browser skips, then rebuilds the Spring Boot executable
JAR successfully.

One earlier full-suite attempt observed the pre-existing ceremony auto-heartbeat timing assertion
at one committed heartbeat instead of two after the browser-heavy suite. The subsequent testability
step removed that scheduler-count assumption: the signer is latch-controlled, the test waits for
same-request response-loss recovery, crosses the original lease deadline using database authority
time, proves the rival remains `BUSY`, and checks `claimVersion = heartbeatCount + 1`. The exact path
passes five consecutive repetitions and the complete 15-case ceremony service suite. Database time
remains the real lease authority; the test no longer substitutes wall-clock luck for protocol proof.

## 8. Honest boundary

This increment closes the Spring product path, staging downgrade fence, demo preflight, aggregate
health, and capability protocol. It does not provide an externally witnessed or Byzantine root
floor by default; the database floor is durable but local to the database trust domain. It also does
not add mTLS/client identity, certificate or response-key pinning, publisher HA/anti-equivocation,
HSM/KMS signer custody, cross-replica convergence alerting, certified production-profile wiring,
target-database qualification, backup/restore rollback proof, multi-region DR, or chaos evidence.
Those remain explicit deployment and production-certification gates.
