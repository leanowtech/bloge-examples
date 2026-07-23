# Mirror deployment-isolation attestation control plane

This guide describes the Stage 1 trust-control plane for externally signed deployment-isolation
attestations. It covers ingest, durable anti-rollback state, irreversible revocation, current-only
distribution, operator wiring, failure semantics, and rollout checks.

It does not claim that Mirror execution is certified. Deployment-agent refresh, runtime admission,
and evidence-commit binding remain separate gates.

## 1. What this increment closes

Resource Gateway now turns the existing short-lived signed attestation into governed current state:

- the stream identity is complete `tenant / organization / project / environment / region`, exact
  immutable deployment, `keySetId`, and `attestationId`;
- the first accepted external revision comes from operator-owned local policy rather than from the
  request or an empty-database trust-on-first-use decision;
- every later external revision must be exactly the durable floor plus one;
- immutable attestation bodies, append-only local statuses, and one mutable current pointer are
  stored separately;
- ingest commits body, initial `ACTIVE` status, floor CAS, and success audit in one transaction;
- revocation appends revision-two `REVOKED` status and advances only the status pointer;
- `REVOKED` cannot transition back to `ACTIVE`; a newer external attestation revision is required;
- reads return one canonically fingerprinted atomic bundle, never separately sampled body/status;
- exact reads work only while all attestation and status coordinates remain current;
- active reads re-verify the same current authority publication generation, key, deployment, and
  time window;
- revoked reads remain available during authority outage and after proof expiry so denial
  propagation cannot be blocked by a positive-trust dependency;
- every route is authenticated before decoding and is physically absent from production profiles.

## 2. The root problem: attestation v1 has no predecessor

`resourceGateway.mirrorDeploymentIsolationAttestation.v1` contains a positive `revision`, but it
does not contain a signed predecessor fingerprint. An empty local database therefore cannot prove
whether a valid revision `7` is the real current head or a replayed older head.

Accepting the first valid signature would be trust on first use. That is insufficient for a
recreated database, a new region, disaster recovery, or a hostile replay inside the validity
window.

`MirrorDeploymentIsolationAttestationAdmissionPolicyProvider` closes that gap by returning the
exact `bootstrapRevision` for one full-scope stream. The provider is operator-owned and local. The
HTTP request cannot supply or override it. After bootstrap, the database floor enforces continuous
revision progression, rollback rejection, same-revision fork rejection, and gap rejection.

For a future attestation v2, a signed predecessor or separately signed source-head protocol can
replace the manually governed bootstrap revision. The current mechanism is explicit rather than
silently pretending v1 has a cryptographic chain it does not carry.

## 3. Trust and lifecycle model

The positive trust path is:

```text
local bootstrap roots and binding
  -> current authority key-set publication and durable generation floor
  -> exact advertised attestation key and issuer
  -> external attestation signature, deployment, and active time window
  -> operator-pinned first revision or continuous repository successor
  -> local ACTIVE status
  -> atomic current bundle
```

The denial path is deliberately shorter:

```text
authenticated MIRROR_TRUST_ADMIN command
  -> exact current attestation/status optimistic fence
  -> append REVOKED status
  -> status-head CAS and success audit in one transaction
  -> revoked bundle remains distributable without positive authority availability
```

This asymmetry is intentional. A dependency outage must stop new positive trust, but must not stop
distribution of a durable security denial.

Version one has only these status transitions:

| Current | Command | Result |
|---|---|---|
| absent | trusted ingest at exact bootstrap revision | `ACTIVE`, status revision 1 |
| `ACTIVE` | exact same ingest | idempotently return current bundle |
| `ACTIVE` | exact fenced revoke | `REVOKED`, status revision 2 |
| `REVOKED` | same revoke reason and original/exact fence | idempotently return revoked bundle |
| `REVOKED` | ingest same external revision | return revoked bundle; never reactivate |
| `REVOKED` | next continuous external revision | new `ACTIVE`, status revision 1 |
| any | lower/same-fork/skipped external revision | reject |

## 4. Wire contracts

Four strict protocols participate:

| Object | Purpose |
|---|---|
| `resourceGateway.mirrorDeploymentIsolationAttestation.v1` | External authority-signed proof |
| `resourceGateway.mirrorDeploymentIsolationAttestationStatus.v1` | Content-addressed local `ACTIVE` or `REVOKED` fact |
| `resourceGateway.mirrorDeploymentIsolationAttestationBundle.v1` | Atomic full-scope attestation plus current status |
| `resourceGateway.mirrorDeploymentIsolationAttestationRevocationRequest.v1` | Exact optimistic fence plus closed revocation reason |

Authoritative schemas are under `docs/schemas/resource-gateway-mirror/` and are packaged in the
independent `resource-gateway-test-kit` JAR. Duplicate keys, unknown fields, wrong versions,
oversized bodies, excessive depth, excessive node count, non-canonical fingerprints, and
`ACCEPTED` used as a revocation reason fail before service execution.

An active status binds:

- complete enterprise scope;
- exact immutable deployment coordinates and image digest;
- exact authority `keySetId / generation / publicationFingerprint`;
- exact attestation `id / revision / attestationFingerprint`;
- status revision `1`, blank predecessor, `ACTIVE`, and `ACCEPTED`;
- trusted control-plane acceptance time.

A revoked successor binds the same coordinates, status revision `2`, the active status
fingerprint as predecessor, one closed denial reason, and a non-backward transition time.

## 5. Persistence and transaction boundaries

The database uses three additive tables:

| Table | Mutability | Role |
|---|---|---|
| `mirror_isolation_attestations` | append-only | External signed bodies plus exact authority reference |
| `mirror_isolation_attestation_statuses` | append-only | Active and revoked content-addressed status publications |
| `mirror_isolation_attestation_heads` | CAS pointer | Full-scope current attestation, authority, and status floor |

Every read reconstructs the bundle from the indexed head and immutable rows, then rechecks JSON,
indexed coordinates, nested fingerprints, full-scope identity, authority reference, and complete
bundle fingerprint. Missing rows, moved rows, damaged JSON, or mismatched indexes return a
sanitized store-integrity failure.

Ingest and revocation methods are Spring transactions. Repository `SELECT ... FOR UPDATE`, body or
status insert, floor CAS, and mandatory success audit all join the same transaction manager. Audit
failure therefore rolls back the newly visible trust state. Failure audits use the existing
independent failure-audit boundary.

## 6. Operator wiring

The built-in provider is intentionally unavailable. Install a bounded local snapshot provider:

```java
@Bean
MirrorDeploymentIsolationAttestationAdmissionPolicyProvider mirrorAttestationAdmission(
        LocalAttestationAdmissionSnapshotCache cache) {
    return new MirrorDeploymentIsolationAttestationAdmissionPolicyProvider() {
        @Override
        public boolean available() {
            return cache.current().ready();
        }

        @Override
        public Optional<AdmissionPolicy> resolve(
                CapabilitySnapshot.Scope scope,
                String deploymentScopeId,
                String keySetId,
                String attestationId) {
            return cache.current().resolve(
                    scope, deploymentScopeId, keySetId, attestationId);
        }
    };
}
```

`available()` and `resolve()` run on capability-probe or HTTP request threads. They must perform a
bounded, non-blocking read from one immutable local snapshot. A separate authenticated refresher
may update that snapshot. Do not call a remote governance service synchronously from either method.

The provider must pin the exact first accepted revision for every stream. Back up and restore its
source of truth with the attestation tables; region bootstrap and disaster recovery must not invent
a lower revision.

The existing `MirrorDeploymentIsolationAuthorityTrustPolicyProvider` must also be ready before an
active proof can be ingested or served.

## 7. Protected API

All routes require the explicit Mirror switch, a `test` or `staging` profile, a verified integration
identity, complete project/region scope, and the stated purpose.

| Method and path | Purpose | Semantics |
|---|---|---|
| `POST /api/mirror/trust/deployment-isolation/attestations` | `MIRROR_TRUST_ADMIN` | Verify current authority and append exact bootstrap/successor |
| `GET /api/mirror/trust/deployment-isolation/attestations/{attestationId}/latest` | `MIRROR_TRUST_DISTRIBUTION` or `MIRROR_REHEARSAL` | Return one re-verified active or durable revoked bundle |
| `GET /api/mirror/trust/deployment-isolation/attestations/{attestationId}/revisions/{revision}` | same read purposes | Return only when attestation and status addresses are still current |
| `POST /api/mirror/trust/deployment-isolation/attestations/{attestationId}/revocations` | `MIRROR_TRUST_ADMIN` | Append irreversible exact-current denial |

Every route also requires `deploymentScopeId` and `keySetId` query parameters. Exact reads require
`attestationFingerprint`, `statusRevision`, and `statusFingerprint`.

Example revocation body:

```json
{
  "schemaVersion": "resourceGateway.mirrorDeploymentIsolationAttestationRevocationRequest.v1",
  "attestationRevision": 7,
  "attestationFingerprint": "sha256:REPLACE_WITH_64_LOWERCASE_HEX",
  "expectedStatusRevision": 1,
  "expectedStatusFingerprint": "sha256:REPLACE_WITH_64_LOWERCASE_HEX",
  "reason": "SECURITY_INCIDENT"
}
```

The placeholders above are explanatory and are not valid wire values until replaced.

## 8. Stable failure semantics

| Code family | HTTP | Meaning | Retry rule |
|---|---:|---|---|
| `..._MALFORMED`, `..._INVALID`, `..._REF_INVALID` | 400 | Closed protocol or canonical content failed | Fix request; do not blind retry |
| `..._PURPOSE_REQUIRED`, `..._SCOPE_MISMATCH`, `..._POLICY_REJECTED` | 403 | Identity or local binding rejected | Fix authority or scope |
| `..._NOT_FOUND` | 404 | No authorized current stream | Reconcile inventory before retry |
| `..._REVISION_CONFLICT`, `..._STATUS_CONFLICT`, `..._AUTHORITY_SUPERSEDED` | 409 | Optimistic floor or generation changed | Fetch current and make a new decision |
| `..._EXPIRED`, `..._AUTHORITY_REVOKED`, `..._AUTHORITY_EXPIRED` | 410 | Positive trust is no longer usable | Obtain a new authority/proof generation |
| `..._POLICY_UNAVAILABLE`, `..._AUTHORITY_UNAVAILABLE`, `..._STORE_UNAVAILABLE` | 503 | Local governed dependency or integrity unavailable | Bounded retry; fail readiness |

Problems and audits contain stable identifiers and closed reasons only. Attestation JSON, policy
proof details, public-key material, exception messages, stack traces, and business payloads are not
included.

## 9. Capability probe

The integration capability response separates three claims:

- `mirrorIsolationAttestationTrustProtocol=true`: schemas and local trust semantics are supported;
- `mirrorIsolationAttestationDistributionApi=true`: protected routes are physically assembled;
- `mirrorIsolationAttestationDistributionReady=true`: both current authority policy and local
  bootstrap revision policy report ready.

Route assembly does not imply readiness. Readiness does not imply runtime certification. The
default provider keeps readiness false.

## 10. Rollout and operations checklist

1. Provision authority trusted distribution and verify its current floor first.
2. Inventory every attestation stream and pin an exact bootstrap revision from the external source
   of truth.
3. Rehearse empty database, restored database, new region, and disaster-recovery bootstrap.
4. Exercise exact retry, lower revision, same-revision fork, gap, concurrent successors, and
   content-address collision.
5. Exercise security revocation while authority is healthy, unavailable, expired, and superseded.
6. Verify revocation audit failure leaves status active and ingest audit failure leaves no body,
   status, or head residue.
7. Alert on API assembled but readiness false, active proof near expiry, authority generation
   change without successor proof, status conflict, store corruption, and audit outage.
8. Back up all three attestation tables and authority publication/floor tables as one recovery set.
9. Restrict direct database writes and test full-disk, lock contention, backup restore, and schema
   migration on the production database engine.
10. Keep execution certification disabled until the deployment agent and runtime dual binding pass.

## 11. Verified coverage and remaining gates

The focused suite covers strict decoding, protocol/schema parity, canonical nested fingerprints,
operator-pinned bootstrap, restart persistence, continuous successors, rollback/fork/gap rejection,
full-scope isolation, immutable deployment drift, body/status/index corruption, competing writers,
exact-current reads, irreversible and idempotent revocation, authority generation supersession,
expiry, denial propagation during authority outage, payload-free audit, and transaction rollback on
mandatory audit failure.

The remaining path to certified deployment isolation is:

1. authenticated deployment-agent mTLS/HTTPS pull with bounded refresh, anti-equivocation checks,
   and atomic read-only cache replacement;
2. execution admission that pins one exact current authority and active attestation bundle;
3. evidence commit that rechecks the same generations and full execution-window coverage;
4. immediate fail-closed response to cache staleness, expiry crossing, authority change, and
   revocation;
5. language-neutral canonical status/bundle fixtures and deployment certification gates;
6. production database HA/DR, custody, capacity, migration, and chaos evidence.

Until those gates close, Mirror runs remain `EXPLORATORY` and retain
`DEPLOYMENT_EGRESS_NOT_ATTESTED`.
