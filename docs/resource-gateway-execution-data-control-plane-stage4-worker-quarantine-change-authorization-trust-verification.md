# Stage 4 Worker Quarantine Change-Authorization Trust Verification

## Purpose

The in-process maker/checker protocol prevents one Resource Gateway actor from unilaterally
discarding a worker quarantine. It did not prove that an enterprise change-management authority
approved that exact destructive mutation. Merely adding a caller-supplied ticket string would make
the audit record look richer without adding authority.

This foundation increment introduces a separate external-authorization trust domain. It freezes the
signed protocol and verifies real Ed25519 authority signatures without reusing request-identity JWT
keys, Resource Gateway evidence-signing keys, or quarantine database state.

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

## Verification

```bash
/opt/apache-maven-3.9.16/bin/mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ConfiguredWorkerQuarantineChangeAuthorizationTrustStoreTest test
```

Verified on 2026-07-17: 8 tests passed with no failures, errors, or skips. The tests generate real
Ed25519 key pairs and cover exact quorum success, unavailable trust, binding and policy drift, time
boundaries, material tampering, bad trusted signatures, unknown/revoked authority keys, strict
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

Focused database verification now executes 50 tests. New counterexamples prove that one external
authorization cannot back two checker commands, premature and expired windows are rejected by
database time, approval-audit failure rolls back both approval and reservation, and successful
discard retains the exact external reference in its token-free receipt and immutable history.
Resource Gateway `clean verify` executes 2,267 tests with zero failures, zero errors, and two
existing conditional skips, then packages the executable Spring Boot JAR.

## Honest Boundary

This increment freezes and verifies an external authorization object and completes its durable
reservation/consumption substrate. It does not yet require the signed envelope in the checker HTTP
API. Scope/subject derivation, verifier invocation, strict JSON Schema, configuration/readiness,
capability disclosure, API projections, and API-level negative tests remain the next stage. Until
that stage lands, the existing endpoint still creates legacy in-process approvals and must not be
represented as enterprise work-order enforcement.

Even after enforcement is wired, Resource Gateway will verify only the signed authority decision. It
will not become the system of record for ticket lifecycle, device/session assurance, approval-policy
evaluation, legal hold, or external WORM retention.
