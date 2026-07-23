# Mirror deployment-isolation authority trusted distribution

This guide describes the Stage 1 trusted-distribution boundary for
`resourceGateway.mirrorDeploymentIsolationAuthorityKeySetPublication.v1`.
It is an operator and integrator guide, not a claim that deployment-isolation certification is
complete.

## 1. What this increment closes

The server now provides a full-scope, append-only, content-addressed authority publication log:

- the stream identity is complete `tenant / organization / project / environment / region`, exact
  immutable deployment identity, and `keySetId`;
- bootstrap-root keys, expected binding, threshold, and accepted policy fingerprints come from a
  local operator-owned SPI, never from the publication request;
- the publication body is immutable and each generation is content addressed;
- one database row is the durable trusted floor for each stream;
- publication insert and floor compare-and-set commit in one transaction;
- exact retries are idempotent, while rollback, fork, gap, predecessor mismatch, deployment drift,
  and content-address reuse fail closed;
- reads re-resolve local policy, re-verify signatures and validity, and expose only the current
  floor;
- success and failure paths use the payload-free Mirror operation audit;
- production and mixed `production,test` profiles contain none of these routes or beans.

This increment does **not** yet provide deployment-agent mTLS/HTTPS refresh, an atomic agent cache,
attestation ingestion, attestation revocation distribution, or execution-admission/evidence-commit
binding. Mirror evidence therefore remains `EXPLORATORY` with
`DEPLOYMENT_EGRESS_NOT_ATTESTED`.

## 2. Trust ownership

The trust boundary has three independent authorities:

| Authority | Owns | Must not own |
|---|---|---|
| Security bootstrap roots | M-of-N approval of an authority key-set publication | Mirror evidence signing |
| Isolation-attestation authority | Short-lived proof of effective deployment controls | Its own root trust or floor |
| Resource Gateway | Strict admission, durable floor, current publication distribution, audit | Root creation, caller-selected trust, deployment-control attestation |

`MirrorDeploymentIsolationAuthorityTrustPolicyProvider` is the only server-side input for local
trust. Its returned snapshot contains:

- exact `ExpectedBinding`;
- independently pinned bootstrap-root public keys;
- local threshold and accepted policy generations;
- immutable deployment coordinates, including image digest.

The default provider is intentionally unavailable. An enabled Mirror server starts and advertises
the route assembly, but publication operations return
`503 RG.MIRROR.AUTHORITY_TRUST_UNAVAILABLE` until the deployment supplies a governed provider.

Minimal wiring example:

```java
@Bean
MirrorDeploymentIsolationAuthorityTrustPolicyProvider mirrorAuthorityTrust(
        LocalAuthorityTrustSnapshotCache trustCache) {
    return new MirrorDeploymentIsolationAuthorityTrustPolicyProvider() {
        @Override
        public boolean available() {
            return trustCache.current().ready();
        }

        @Override
        public Optional<TrustPolicy> resolve(
                CapabilitySnapshot.Scope scope,
                String deploymentScopeId,
                String keySetId) {
            return trustCache.current().resolve(
                    scope, deploymentScopeId, keySetId);
        }
    };
}
```

The cache refresher, not either provider method, should obtain roots and policy from a separately
authenticated security control plane, signed local file, HSM-backed inventory, or equivalent
governed source. Both methods run on HTTP and capability-probe request threads, so they must perform
bounded, non-blocking reads of one immutable detached local snapshot. The provider must not
construct binding or roots from the publication passed to the HTTP endpoint.

## 3. Runtime assembly and capability probe

The complete surface requires:

```properties
spring.profiles.active=test
gateway.testing.mirror.enabled=true
```

`staging` is also accepted. Any active `production` profile physically excludes the controller,
service, decoder, integrity verifier, repository, and default provider.

Interpret capability flags independently:

| Flag | Meaning |
|---|---|
| `mirrorIsolationAuthorityPublicationProtocol` | This binary understands the strict v1 artifact |
| `mirrorIsolationAuthorityDistributionApi` | Protected publish/read routes are physically assembled |
| `mirrorIsolationAuthorityDistributionReady` | The local trust-policy provider currently reports ready |

`mirrorIsolationAuthorityDistributionReady=true` is global component readiness. It does not assert
that every scope has a policy, that a requested stream exists, or that its current publication is
still active. The scoped read remains authoritative for those facts.

## 4. Protected API

| Method and path | Purpose | Required authenticated purpose |
|---|---|---|
| `POST /api/mirror/trust/deployment-isolation/authority-key-sets` | Verify and append one generation | `MIRROR_TRUST_ADMIN` |
| `GET /api/mirror/trust/deployment-isolation/authority-key-sets/{keySetId}/latest?deploymentScopeId=...` | Read the current floor | `MIRROR_TRUST_DISTRIBUTION` or `MIRROR_REHEARSAL` |
| `GET /api/mirror/trust/deployment-isolation/authority-key-sets/{keySetId}/generations/{generation}?deploymentScopeId=...&publicationFingerprint=...` | Read an exact content address only if it is still current | `MIRROR_TRUST_DISTRIBUTION` or `MIRROR_REHEARSAL` |

The POST body is the publication object itself, not the compatibility fixture wrapper. The shared
fixture contains historical fixed times and is for offline interoperability tests; do not use it as
a live publication.

The transport authenticates before decoding or lookup. The POST decoder rejects:

- an empty body or a body above 2 MiB;
- duplicate JSON keys;
- unknown fields at any record depth;
- a non-v1 schema version;
- depth above 32 or more than 10,000 JSON nodes;
- non-canonical identifiers, base64, fingerprints, ordering, or collection bounds.

Spring still buffers the HTTP body. Ingress request-size, connection, rate, and concurrency limits
remain mandatory deployment controls.

## 5. Admission sequence

For publication generation `g`, the server performs this sequence:

```text
authenticate dedicated operation
  -> strict bounded decode
  -> require full non-production identity scope
  -> compare publication scope with authenticated scope
  -> resolve local trust by scope + deploymentScopeId + keySetId
  -> reject inconsistent or unavailable local policy
  -> read durable floor
  -> recompute both canonical fingerprints
  -> verify exact binding, validity, all root signatures, and M-of-N threshold
  -> verify bootstrap/successor relation against the floor
  -> lock the stream floor row FOR UPDATE
  -> recheck immutable deployment and persisted head integrity
  -> insert immutable publication body
  -> CAS floor from old generation/fingerprint to new generation/fingerprint
  -> append success audit
  -> commit and return the publication
```

The repository rechecks the chain under the database lock. Verification before the transaction is
therefore not a time-of-check/time-of-use trust gap: if another replica advances the floor, the
losing append is either an exact idempotent retry or a closed chain conflict.

## 6. Durable state and concurrency semantics

`mirror_isolation_authority_publications` contains immutable public trust material and indexed
identity. `mirror_isolation_authority_trusted_floors` contains the exact deployment binding and the
current `(generation, publicationFingerprint)`.

The floor starts at generation zero with a blank fingerprint and does not represent trust. The
first accepted publication must be generation one. Every later candidate must be exactly
`floor.generation + 1` and name `floor.publicationFingerprint` as predecessor.

| Situation | Result |
|---|---|
| Same generation and same fingerprint at the current floor | Return stored publication idempotently |
| Generation below current floor | Reject rollback |
| Same generation, different fingerprint | Reject fork |
| Generation above `floor + 1` | Reject gap |
| Wrong predecessor | Reject chain conflict |
| Same deployment scope but changed cluster/namespace/workload/account/image | Reject identity mismatch |
| Damaged floor, missing head body, changed index, or changed JSON | Fail closed as store unavailable |
| Two replicas append different successors concurrently | Floor lock and CAS allow exactly one winner |

The exact-generation GET intentionally does not provide historical browsing. A generation is served
only when generation and fingerprint equal the current floor. Historical rows remain durable for
restricted database audit and future evidence retention, but the trusted distribution API cannot
be used as a downgrade oracle.

## 7. Stable failures

Public problems contain a stable code, status, retryability, correlation id, and bounded protocol
limits where applicable. They do not contain publication JSON, keys, signatures, exception text,
or database details.

| Code | HTTP | Typical cause |
|---|---:|---|
| `RG.MIRROR.AUTHORITY_PUBLICATION_MALFORMED` | 400 | Duplicate, unknown, oversized, deep, or non-v1 JSON |
| `RG.MIRROR.AUTHORITY_PUBLICATION_INVALID` | 400 | Canonical fingerprint or signature material invalid |
| `RG.MIRROR.AUTHORITY_PUBLICATION_REF_INVALID` | 400 | Malformed read coordinate |
| `RG.MIRROR.AUTHORITY_PURPOSE_REQUIRED` | 403 | Wrong authenticated purpose |
| `RG.MIRROR.AUTHORITY_ENVIRONMENT_FORBIDDEN` | 403 | Identity is not test/staging |
| `RG.MIRROR.AUTHORITY_PUBLICATION_SCOPE_MISMATCH` | 403 | Uploaded publication is outside authenticated scope |
| `RG.MIRROR.AUTHORITY_PUBLICATION_POLICY_REJECTED` | 403 | Binding, root, threshold, or policy rejected locally |
| `RG.MIRROR.AUTHORITY_PUBLICATION_NOT_FOUND` | 404 | Policy or current stream is absent in exact scope |
| `RG.MIRROR.AUTHORITY_PUBLICATION_EXPIRED` | 410 | Current floor is no longer active |
| `RG.MIRROR.AUTHORITY_PUBLICATION_CHAIN_CONFLICT` | 409 | Bootstrap, rollback, fork, gap, predecessor, or CAS conflict |
| `RG.MIRROR.AUTHORITY_TRUST_UNAVAILABLE` | 503 | Local trust provider is not ready |
| `RG.MIRROR.AUTHORITY_TRUST_POLICY_INVALID` | 503 | Provider returned inconsistent local coordinates |
| `RG.MIRROR.AUTHORITY_PUBLICATION_STORE_UNAVAILABLE` | 503 | Persisted state failed integrity checks |
| `RG.MIRROR.AUTHORITY_PUBLICATION_NOT_TRUSTED` | 503 | Current floor failed read-time local re-verification |

Chain conflicts are not blindly retried. The publisher must fetch or independently learn the
current floor, rebuild the exact successor, obtain fresh threshold signatures, and submit a new
generation. Store/trust 503s are retryable only after the dependency recovers.

## 8. Audit and observability

The closed Mirror operation vocabulary now includes `authority_key_set_publish` and
`authority_key_set_read`. Metrics retain only fixed-cardinality operation/outcome/reason tags.

Audit rows may contain key-set id, deployment-scope id, and canonical publication fingerprint as
stable resource coordinates. They cannot represent public keys, signatures, request bodies,
business payloads, exception messages, or stack traces. Invalid untrusted path/query values are
blanked before audit construction.

The publication insert, floor CAS, and success audit share one local transaction. If mandatory
success audit fails, publication and floor both roll back. Failure audit uses the existing
independent transaction boundary so a rejection remains visible after business rollback.

## 9. Operator rollout checklist

1. Configure a dedicated test/staging deployment and identity; never add `production` profile.
2. Implement the trust-policy provider against a separately governed root inventory.
3. Pin at least the locally required number of distinct root authorities and distinct key material.
4. Monitor the three capability flags and alert when API is assembled but trust is not ready.
5. Publish generation one only after validating exact deployment and policy coordinates out of band.
6. Exercise idempotent retry, competing successor, rollback, expiry, root revocation, policy drift,
   database restart, audit outage, and full-disk behavior.
7. Restrict direct database reads and back up publication plus floor tables together.
8. Keep deployment-agent and runtime certification disabled until the remaining bindings land.

## 10. Verified coverage and remaining gates

Repository, service, strict decoder, controller, route isolation, capability probe, and real Spring
transaction tests cover restart persistence, full-scope isolation, tamper detection, floor
rollback/fork/gap/predecessor rejection, deployment drift, concurrent successors, unavailable and
wrong local roots, expiry, sanitized audit, and mandatory-audit rollback.

The increment adds 23 focused scenarios and passes a 66-test authority integration gate. Seven
authority public types pass `javadoc --release 25 -Werror -Xdoclint:all` with zero warnings. The
frozen tree also passes Resource Gateway `clean verify` with 4,565 tests, zero failures, zero
errors, and three conditional skips, including real Chrome regression plus executable Boot JAR
packaging. The independent test-kit passes 269 tests and packages its library JAR, shaded CLI JAR,
and schemas.

The next vertical slice is:

1. authenticated deployment-agent mTLS/HTTPS pull with bounded refresh and atomic read-only cache;
2. full-scope append-only isolation-attestation ingest and revocation status;
3. execution admission and evidence commit checks against the same authority-publication and
   attestation generations;
4. fail-closed behavior for refresh staleness, expiry crossing, and revocation propagation;
5. cross-language canonicalization and certification fixtures beyond Java.
