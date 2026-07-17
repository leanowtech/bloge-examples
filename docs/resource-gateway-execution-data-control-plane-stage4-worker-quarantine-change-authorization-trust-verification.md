# Stage 4 Worker Quarantine Change-Authorization Trust Verification

## Purpose

The in-process maker/checker protocol prevents one Resource Gateway actor from unilaterally
discarding a worker quarantine. It did not prove that an enterprise change-management authority
approved that exact destructive mutation. Merely adding a caller-supplied ticket string would make
the audit record look richer without adding authority.

This increment introduces and enforces a separate external-authorization trust domain. It freezes
the signed protocol, verifies real Ed25519 authority signatures, and requires the verified envelope
on the checker HTTP command without reusing request-identity JWT keys, Resource Gateway
evidence-signing keys, or quarantine database state.

## Signed Contract

`bloge.workerQuarantineChangeAuthorization.v1` contains canonical
`bloge.workerQuarantineChangeAuthorizationMaterial.v1` and one through 32 distinct authority
signatures. The material binds:

| Fact | Meaning |
| --- | --- |
| `trustDomain` | deployment-owned external governance boundary |
| `authorizationId` | immutable external work-order or approval identity |
| `action` | closed value `WORKER_QUARANTINE_DISCARD` |
| `scopeFingerprint` | identity-derived tenant/organization/project/environment closure |
| `subjectFingerprint` | exact quarantine claim, reason, and mutation fingerprint |
| `policyFingerprint` | exact accepted external approval-policy revision |
| `issuedAt/notBefore/expiresAt` | bounded authorization lifecycle |

No ticket description, tenant value, actor value, claim token, credential, or business payload is
part of the envelope. Each authority signs the canonical material fingerprint with Ed25519.
Protocol timestamps use microsecond precision so canonical signatures survive PostgreSQL/H2
timestamp persistence without lossy normalization.

## Independent Trust Policy

`WorkerQuarantineChangeAuthorizationTrustStore` is deliberately independent from caller identity and
local maker/checker authorization. Its configured implementation accepts only public Ed25519 keys,
one through 32 exact policy fingerprints, and a distinct-authority M-of-N threshold. It rejects:

- trust-domain, action, scope, subject, or policy drift;
- premature, expired, future-dated, or longer-than-24-hour authorization windows;
- changed canonical material;
- duplicate authority claims, malformed signatures, bad trusted signatures, inactive/revoked keys,
  and insufficient quorum.

Failed verification exposes only a closed status, stable reason code, and bounded signature counts.
Only a verified result carries the opaque external authorization ID and material fingerprint. The
capability descriptor contains counts and policy shape but no key material.

## Checker API Enforcement

`bloge.durableWorkerQuarantineDiscardApprovalRequest.v2` requires one complete
`changeAuthorization`. Resource Gateway deterministically derives and publishes the canonical
preimages `bloge.workerQuarantineChangeAuthorizationScope.v1` and
`bloge.workerQuarantineChangeAuthorizationSubject.v1`; callers fingerprint those exact objects and
obtain external signatures over the resulting material. The service then:

1. derives scope exclusively from verified tenant/organization/project/environment identity;
2. derives the subject from exact key, claim owner/version/deadline, and reason, without claim token;
3. verifies canonical material, binding, policy, validity, key lifecycle, and M-of-N signatures;
4. asks the database to recheck time, reserve the authorization, and create the local checker approval;
5. returns only `bloge.durableWorkerQuarantineChangeAuthorizationReference.v1`, never signatures or keys.

New approvals cannot use the compatibility overload. Legacy v1 approvals and receipts remain
integrity-readable and are projected as `LEGACY_IN_PROCESS`, but a legacy approval cannot authorize
a new discard. New and current evidence uses `EXTERNAL_VERIFIED` plus a non-null external reference.

Exact idempotent approval replay is checked against the immutable database intent before live trust
evaluation. Therefore a lost response remains replayable after the signed window expires or while
the trust configuration is temporarily unavailable. A new request, changed intent, or expired
replay tombstone does not receive this exemption.

## Configuration And Readiness

| Property suffix under `gateway.testing.durable.worker-quarantines.change-authorization` | Environment variable | Semantics |
| --- | --- | --- |
| `trust-domain` | `RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_TRUST_DOMAIN` | exact deployment-owned governance boundary |
| `accepted-policy-fingerprints` | `RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_POLICY_FINGERPRINTS` | comma-separated exact SHA-256 policy identities |
| `signature-threshold` | `RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_SIGNATURE_THRESHOLD` | required distinct authorities, `1..32` |
| `authority-keys-json` | `RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_AUTHORITY_KEYS_JSON` | bounded Ed25519 public-key records and validity state |

An entirely absent local `test` configuration creates an explicit unavailable trust store: the rest
of the test runtime starts, capability reports the endpoint but
`externalWorkerQuarantineChangeAuthorization=false`, and new approval returns `503`. Partial or
malformed configuration fails application startup. `staging` requires all four values before the
launcher starts Spring. With a usable quorum, capability reports the feature as true and publishes
only trust domain, authority/key/policy counts, threshold, and bounded algorithm metadata.

## Verification

```bash
/opt/apache-maven-3.9.16/bin/mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ConfiguredWorkerQuarantineChangeAuthorizationTrustStoreTest test
```

Verified on 2026-07-17: the trust, binding, configuration, API, capability, Schema, and application
integration gate executed 84 tests with no failures, errors, or skips. Its verifier subset generates
real Ed25519 key pairs and covers exact quorum success, unavailable trust, binding and policy drift,
time boundaries, material tampering, bad trusted signatures, unknown/revoked authority keys, strict
configuration parsing, and protocol-model invariants.

## Durable Reservation And Consumption

The database control plane now has a separate
`rg_test_durable_worker_quarantine_change_authorizations` authority table. A verified reference is
bound to the local checker approval and reserved under both `(trustDomain, authorizationId)` and a
globally unique material fingerprint. Database time independently enforces `notBefore <= now <
expiresAt`, and the local approval deadline is capped by the external deadline.

An approved discard locks and integrity-verifies the reservation, requires the exact scope,
approval request, approval ID, policy, and material fingerprint, and changes `RESERVED` to
`CONSUMED` in the same transaction that consumes the checker approval, deletes the quarantine,
writes the command/history evidence, and appends audit. External references are included in v2
approval, receipt, command, and history fingerprints. Legacy v1 rows remain readable with their
original fingerprint material.

Focused database verification now executes 51 tests. New counterexamples prove that one external
authorization cannot back two checker commands, premature and expired windows are rejected by
database time, approval-audit failure rolls back both approval and reservation, and successful
discard retains the exact external reference in its token-free receipt and immutable history. They
also prove that legacy approvals remain readable but cannot drive a new discard.
The post-edit persistence/service regression executes 65 tests with zero failures, errors, or skips.
Resource Gateway `clean verify` executes 2,273 tests with zero failures, zero errors, and two existing
conditional skips, then packages the executable Spring Boot JAR. The independent test-kit
`clean verify` executes 74 tests with zero failures, errors, or skips and verifies the packaged
authoritative Schema, shaded CLI, and public JavaDoc.

## Honest Boundary

This increment closes the Resource Gateway enforcement path from strict v2 HTTP Schema through
signature verification, durable reservation, single consumption, key-free evidence, readiness, and
capability disclosure. It proves that an independently signed authority decision binds the exact
destructive mutation; it does not make Resource Gateway the system of record for ticket lifecycle,
device/session assurance, approval-policy evaluation, approver employment state, emergency
break-glass review, legal hold, or external WORM retention.

The current trust source is startup-loaded public-key configuration. Revocation and policy changes
take effect only after configuration rollout/restart; dynamic refresh, stale-cache limits,
fleet-wide key-version convergence, external ticket status callbacks, and witness anchoring remain
future hardening. Those boundaries must not be collapsed into the now-closed signed-decision
enforcement claim.
