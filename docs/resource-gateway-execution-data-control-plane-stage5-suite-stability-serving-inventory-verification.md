# Stage 5 Suite-Stability Signed Serving-Inventory Verification

## Decision

The exact authority cohort previously trusted a replica-local list of expected instance ids. That
proved convergence against a configured set, but it could not prove that the set was complete: a
misconfigured or malicious deployment could remove absent peers and let the remaining replica
self-admit.

Staging cohorts now require a deployment-owned, M-of-N Ed25519-signed serving inventory. Resource
Gateway verifies the statement, derives the expected set from it, binds its revision and
fingerprints into the cohort policy, and persists a stable-scope revision floor. Fresh submission,
worker claim, and post-claim authorization remain closed when the attestation expires, diverges,
rolls back, forks, or no longer matches exact live cohort convergence.

This is an attestation consumer. Resource Gateway does not become a deployment registry, signer,
key custodian, or rollout controller.

## Root Cause And Control Layers

Fleet admission needs three independent facts:

| Question | Authority | Control |
| --- | --- | --- |
| Which serving slots are intended? | deployment governance | signed serving inventory |
| Is this statement newer and unique for the stable scope? | test-runtime database | monotonic revision floor |
| Are those exact slots live and on one trust generation now? | database-clock cohort leases | exact cohort convergence |

A signed but stale inventory is unsafe. A fresh inventory without exact runtime convergence is
unsafe. Exact convergence against a replica-selected list is incomplete. Admission therefore
requires all three controls simultaneously.

## Signed Protocol

`bloge.testSuiteStabilityServingInventory.v1` contains canonical material and 1..32 distinct
authority signatures. Material binds:

| Field | Security meaning |
| --- | --- |
| `trustDomain` | prevents signatures from crossing deployment-governance domains |
| `inventoryId` | unique audit correlation identity |
| `revision` | monotonic value within stable `scopeId` |
| `scopeId` / `cohortId` | stable fleet and immutable rollout generation |
| `artifactFingerprint` | exact image or JAR SHA-256 |
| `protocolVersion` | exact Resource Gateway integration protocol |
| `expectedInstanceIds` | sorted, unique, complete 1..256 serving-slot list |
| `policyFingerprint` | exact external issuance-policy revision |
| `issuedAt` / `notBefore` / `expiresAt` | bounded activation and hard expiry |

The envelope carries the canonical SHA-256 material fingerprint. Canonical bytes are UTF-8 minified
JSON with object property names sorted lexicographically, arrays kept in protocol order, integral
numbers emitted in decimal, and whole-second instants emitted as UTC RFC 3339 strings. For the
material fixture in the Schema test, the expected identity is
`sha256:aa895f2dcd3491b286f2cbd5c59ac472ddca87c5c837ff5a067c72c049918414`.
Each detached Ed25519 signature covers the ASCII bytes of the complete `sha256:<hex>` fingerprint
and names one authority, key, and signing time. The verifier accepts only
public X.509-encoded Ed25519 keys, distinct authorities, accepted policy fingerprints, and a met
M-of-N threshold. Unknown authorities do not count; a bad signature from a trusted active key
invalidates the envelope. Duplicate JSON keys, unknown fields, trailing tokens, private material,
non-canonical ids/lists/times, future or expired material, and lifetimes over 30 days fail startup.

The authoritative machine-readable contract is
[`suite-stability-serving-inventory-v1.schema.json`](schemas/resource-gateway-testing/suite-stability-serving-inventory-v1.schema.json).
Sorting, whole-second time, fingerprint, signature, threshold, and cross-field equality remain
runtime invariants because JSON Schema cannot express all of them safely.

## Local Binding And Policy Derivation

Every process verifies that signed material equals its local scope, cohort, artifact, protocol,
and contains its own serving slot. The cohort expected set is then derived from signed material.
`RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_EXPECTED_INSTANCE_IDS`, when supplied, is only a defensive
equality assertion; it is never an alternative authority in signed mode.

`TestSuiteStabilityAuthorityCohortPolicy` includes an immutable attestation projection: source type,
revision, material fingerprint, external policy fingerprint, and expiry. Its canonical cohort
fingerprint therefore changes if the deployment inventory changes, even when instance names happen
to remain equal.

## Runtime Freshness And Failure Semantics

The static adapter re-evaluates `notBefore` and `expiresAt` on every local observation. It performs
no remote I/O on admission or health reads. Expiry closes the gate without restart. The monitor
also exact-checks revision, source, both fingerprints, expiry, and expected set against the frozen
policy before heartbeat publication and every descriptor read.

Stable aggregate states include:

| State | Meaning |
| --- | --- |
| `SERVING_INVENTORY_EXPIRED` | signed material crossed its hard deadline |
| `SERVING_INVENTORY_NOT_YET_VALID` | local clock is before activation |
| `SERVING_INVENTORY_DIVERGED` | a current signed observation no longer equals frozen policy |
| `SERVING_INVENTORY_UNAVAILABLE` | the local authority could not produce a safe observation |
| `SERVING_INVENTORY_ROLLBACK` | database floor is above the presented revision |
| `SERVING_INVENTORY_FORKED` | same revision names different material or policy |
| `SERVING_INVENTORY_FLOOR_CORRUPT` | durable anti-rollback state failed integrity checks |

These states close fresh submit and worker claims. They do not expose inventory ids, instance ids,
fingerprints, key ids, endpoints, or signatures.

## Durable Revision Floor

`rg_test_suite_stability_authority_inventory_floors` stores one whole-record-fingerprinted floor per
stable scope. Only the cohort that owns the active scope lease may establish or advance it. A
non-active successor cannot poison the current generation. Lower revisions and same-revision
different material/policy fail transactionally; a higher revision advances only when its cohort
legitimately owns the scope. Snapshot reads independently validate the floor.

The floor intentionally outlives cohort member retention and deployment generations. Reusing a
scope with a lower revision is a rollback, not routine cleanup. A legitimate reset needs a governed
new scope or an explicit future migration procedure.

## Staging Configuration

Enable HTTP authority, dynamic JWKS, and cohort controls, then inject deployment-owned inventory
trust:

```bash
export RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_ENABLED=true
export RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_SCOPE_ID=rg-stability-authority
export RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_ID=release-2026-07-19-01
export RG_RESOURCE_GATEWAY_INSTANCE_ID=rg-stability-01
export RG_RESOURCE_GATEWAY_ARTIFACT_FINGERPRINT=sha256:<64-lowercase-hex>

export RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_SIGNED_INVENTORY_ENABLED=true
export RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_TRUST_DOMAIN=deployment.example
export RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_POLICY_FINGERPRINTS=sha256:<64-lowercase-hex>
export RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_SIGNATURE_THRESHOLD=2
export RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_AUTHORITY_KEYS_JSON='[...]'
export RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_SIGNED_INVENTORY_JSON='{...}'
```

`application-staging.yml` requires signed inventory whenever the cohort is enabled. The `test`
profile retains explicit local configured mode for isolated development tests. The demo startup
script validates required switches, identifiers, fingerprints, threshold, and JSON envelope shape;
the Java verifier remains the cryptographic and semantic authority.

## Capability And Health Truth

`externallyAttestedSuiteStabilityServingInventory=true` means the assembled exact cohort is bound
to a currently verified external inventory. It does not mean the cohort is converged; clients must
also require `convergedSuiteStabilityAuthorityTrustCohort=true`. Authorizer descriptors and
Actuator health expose only booleans, fixed status, counts, and lease duration.

## Verification

Fifty-three focused protocol, persistence, capability, and profile tests pass with zero failures,
errors, or skips. They cover distinct-authority quorum, signature tamper, missing authority, binding drift,
policy rejection, canonical ordering, duplicate authorities, strict public-only JSON, future/
expired/excessive lifetime, runtime expiry, policy derivation, required staging semantics, revision
advance, rollback, same-revision fork, floor corruption, monitor divergence, capability truth, and
strict Schema parity. The complete Resource Gateway `clean verify` executes 2688 tests with zero
failures, zero errors, and two conditional skips, then successfully repackages the executable
Spring Boot JAR.

## Deliberate Limits

1. The current adapter consumes immutable startup configuration. There is no restart-free inventory
   refresh, revocation feed, or bounded-stale remote cache yet; expiry is the hard safety boundary.
2. Correctness depends on the configured external signer threshold and custody. Compromise of that
   threshold can attest a false set. KMS/HSM, mTLS/pinning, signer HA, and ceremony audit remain
   deployment responsibilities not certified here.
3. The protocol does not prove deployment-platform non-equivocation across regions. Transparency
   logs, witness gossip, and platform conformance tests remain required for that claim.
4. The independent dynamic JWKS source still lacks signed-JWKS witness/non-equivocation proof.
5. Database floor behavior is implemented and tested on the example datastore; non-H2 dialect,
   backup/restore rollback, cross-region failover, and disaster-recovery certification remain open.

The next root-cause increment is a refreshable, fail-closed inventory source with explicit
revocation, version negotiation, anti-equivocation witness evidence, and bounded availability SLO.
