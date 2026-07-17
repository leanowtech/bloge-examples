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

## Honest Boundary

This increment freezes and verifies an external authorization object; it does not yet activate that
object in the discard API. Checker approval, database-time revalidation, authorization-ID uniqueness,
one-way consumption, immutable discard history, capability/configuration wiring, and JSON Schema are
the next implementation stage. Until that stage lands, the existing maker/checker endpoint remains
an in-process two-person control and must not be represented as enterprise work-order binding.

Even after enforcement is wired, Resource Gateway will verify only the signed authority decision. It
will not become the system of record for ticket lifecycle, device/session assurance, approval-policy
evaluation, legal hold, or external WORM retention.
