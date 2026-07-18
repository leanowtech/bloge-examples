# Stage 5 Dynamic Suite-Stability Serving-Inventory Verification

## Decision

The static signed serving inventory established who belongs to an exact authority cohort, but its
operational state changed only through process restart. That left three industrial gaps:

1. revocation could not close a running fleet before the nested inventory expired;
2. two healthy replicas could consume different publication generations;
3. HTTPS availability alone could be mistaken for protocol, ordering, or non-equivocation proof.

Resource Gateway now consumes a strictly versioned, deployment-signed `ACTIVE` or `REVOKED`
publication with an independently signed witness checkpoint. It bootstraps before admission,
refreshes through bounded conditional HTTPS, fails closed on every ambiguous refresh, and requires
all live cohort members to publish one identical private publication/witness generation before the
cohort converges.

The static signed adapter remains available only as an explicit `test` fallback. A staging cohort
requires the dynamic witnessed source and rejects simultaneous static document injection.

## Root Cause And Authority Split

One object cannot safely answer every fleet-admission question. The control is deliberately split:

| Question | Authority | Resource Gateway proof |
| --- | --- | --- |
| Which slots belong to this immutable cohort? | deployment governance | nested signed serving inventory |
| Is that exact inventory active now? | deployment publication authority | signed publication state and validity window |
| Was this publication order independently observed? | separate witness trust domain | signed checkpoint and predecessor chain |
| Can a complete fleet restart accept an older valid chain head? | test-runtime database | stable-scope durable publication/witness floor |
| Did every live Resource Gateway replica consume the same generation? | test-runtime database | exact cohort generation convergence |
| Is the local source still reachable and fresh? | local refresh lane | last successful refresh plus hard maximum age |

No unsigned HTTP status, ETag, local instance list, capability flag, or health response is an
authority for membership or revocation.

## Protocol

The authoritative Schema is
[`suite-stability-serving-inventory-publication-v1.schema.json`](schemas/resource-gateway-testing/suite-stability-serving-inventory-publication-v1.schema.json).
The document contains three independently verified layers:

| Layer | Signed identity | Required bindings |
| --- | --- | --- |
| nested inventory | `inventory.materialFingerprint` | scope, cohort, artifact, protocol, exact sorted slots, policy, revision, validity |
| publication | `materialFingerprint` | trust domain, sequence, nested inventory fingerprint, `ACTIVE`/`REVOKED`, predecessor, policy, validity, reason |
| witness | `witness.materialFingerprint` | independent domain, same sequence, publication fingerprint, predecessor checkpoint, validity |

Publication and witness signatures are canonical, sorted, distinct-authority Ed25519 signatures.
Deployment and witness trust must differ by trust domain, authority id, and public-key bytes. Private
keys are never accepted by Resource Gateway.

Canonical fingerprints use the repository protocol mapper: UTF-8 minified JSON, lexicographically
sorted object properties, protocol-order arrays, decimal integers, and UTC whole-second RFC 3339
instants. The known-answer fixtures pin these identities:

| Material | Known fingerprint |
| --- | --- |
| publication material | `sha256:b1a05ea0b8ce3108fe7446dda054563dc5920cf6f7ad4c3379b790ea49f32d7c` |
| witness material | `sha256:0534c1e48b46b8a0d178ab1e1cf4983f3a6b5d09317bb01473aec6815729a2b7` |

Runtime checks remain authoritative where JSON Schema cannot express canonical ordering,
cross-object equality, fingerprint recomputation, signature quorum, key lifecycle, time, or chain
continuity.

## Strict HTTPS Negotiation

The remote endpoint must use HTTPS outside the explicit loopback-only test escape hatch. Every
request sends:

```http
Accept: application/vnd.bloge.suite-stability-serving-inventory.v1+json
X-BLOGE-Serving-Inventory-Protocol: bloge.testSuiteStabilityServingInventoryPublication.v1
If-None-Match: <previous-etag>
```

Both `200` and `304` responses must return the exact protocol header and the exact vendor media type,
optionally followed by media parameters. Generic JSON, a media-type prefix collision, redirect,
unexpected status, missing protocol header, oversized body, duplicate key, unknown field, trailing
content, timeout, or interrupted request closes local admission.

An ETag is only a cache validator. A modified response is trusted only after all three signature and
binding layers pass. A `304` may renew local source freshness, but it cannot extend the signed
publication, witness, or nested inventory expiry.

## Atomic Refresh And Revocation

Bootstrap must produce a currently valid `ACTIVE` publication. A signed `REVOKED` bootstrap fails
startup. After startup, each refresh publishes either a fully verified successor or no successor.
There is no partially updated inventory, publication, witness, or ETag state.

| Event | Local result | Admission result |
| --- | --- | --- |
| valid unchanged `304` | source freshness renewed | allowed while every signed deadline remains current |
| valid `ACTIVE` successor | sequence and witness generation advance atomically | waits for cohort generation convergence |
| valid `REVOKED` successor | refresh remains healthy, state becomes revoked | immediately closed |
| transport or protocol failure | `REFRESH_UNAVAILABLE` | immediately closed |
| no successful refresh before maximum age | `SOURCE_EXPIRED` | closed |
| publication or witness expiry | explicit expiry status | closed |
| sequence rollback, gap, fork, or bad predecessor | invalid document | closed |
| nested inventory rollback or same-revision fork | invalid document | closed |

Publication and witness lifetimes are bounded to one day. The local maximum snapshot age is bounded
to 2 seconds through 24 hours and must cover at least one refresh interval plus one request timeout.
Capability and health reads never perform network I/O.

## Durable Chain And Restart Semantics

The process-local chain check remains the first ordering fence: sequence may stay equal or advance by
exactly one, equal sequence requires the same publication, witness, and nested inventory identities,
and a successor must name both predecessor fingerprints. Before that verified candidate becomes
observable, `DatabaseTestSuiteStabilityServingInventoryPublicationFloor` serializes the stable scope
through a dedicated lock row and atomically applies the same transition to a durable chain head.

The floor record contains sequence, current publication fingerprint, current witness fingerprint,
database observation time, and a whole-record fingerprint. An absent floor accepts only sequence 1;
an exact current generation is idempotent; a lower sequence, same-sequence fork, gap, or either wrong
predecessor is rejected. Store outage, malformed columns, or fingerprint corruption fails bootstrap
or refresh closed. Two replicas racing with different successors linearize at the database lock, so
exactly one can advance the floor. A reconstructed authority reading the same database therefore
cannot accept a pre-restart chain head.

The floor is not an external transparency log. Restoring the entire database from an older backup can
also restore the floor and is not detectable without an independently anchored checkpoint, WORM log,
or cross-domain gossip. That boundary is reported explicitly rather than being hidden behind the
`durable` capability.

## Cross-Replica Generation Convergence

Each cohort heartbeat now stores two private fields:

- `serving_inventory_source_sequence`;
- `serving_inventory_generation_fingerprint`.

Dynamic mode publishes the outer publication sequence and witness material fingerprint. Static test
mode publishes the inventory revision and material fingerprint. Local unsigned mode must publish
zero and blank. Existing member tables are upgraded additively and the persisted heartbeat protocol
advances to `bloge.testSuiteStabilityAuthorityCohortMember.v2`. Legacy rows retain a v1 record
fingerprint and therefore fail closed until a current process republishes or their database-clock
lease expires.

The repository validates the whole member record, policy/source shape, lease, and database time. An
external cohort converges only when all exact live slots have one trust snapshot and one identical
`(source sequence, generation fingerprint)` pair. A split returns
`SERVING_INVENTORY_GENERATION_DIVERGED`. Public health and capability projection expose only the
distinct generation count, never sequence values or fingerprints.

This catches active replicas that refresh at different speeds. It does not provide push-based global
revocation: a replica that cannot reach the source fails closed locally, while peers independently
refresh or disappear from the cohort after their database lease expires.

## Staging Configuration

The existing cohort, HTTP current-authority, and dynamic JWKS settings remain required. Dynamic
serving-inventory settings are:

```bash
export RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_SIGNED_INVENTORY_ENABLED=true
export RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_REMOTE_ENABLED=true
export RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_REMOTE_URI=https://deployment.example/v1/serving-inventory
export RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_REMOTE_REFRESH_SECONDS=30
export RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_REMOTE_TIMEOUT_MS=3000
export RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_REMOTE_MAXIMUM_AGE_SECONDS=60

export RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_TRUST_DOMAIN=deployment.example
export RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_POLICY_FINGERPRINTS=sha256:<64-lowercase-hex>
export RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_SIGNATURE_THRESHOLD=2
export RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_AUTHORITY_KEYS_JSON='[...]'

export RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_WITNESS_DOMAIN=deployment-witness.example
export RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_WITNESS_SIGNATURE_THRESHOLD=2
export RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_WITNESS_AUTHORITY_KEYS_JSON='[...]'
unset RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_SIGNED_INVENTORY_JSON
```

`scripts/visual-canvas-demo.sh` rejects missing dynamic mode, non-HTTPS endpoints, static/remote
ambiguity, invalid bounds, malformed public-key arrays, equal trust domains, or missing thresholds
before staging startup. Java remains the authority for strict parsing, public-key construction,
signature verification, chain validation, and trust independence. Dynamic mode automatically uses
the configured isolated test-runtime database for the durable floor; there is no in-memory fallback
and no additional operator-supplied secret. Database initialization or floor bootstrap failure stops
staging startup.

## Capability, Health, And Operations

Capability discovery adds:

- `dynamicSuiteStabilityServingInventory`;
- `witnessedSuiteStabilityServingInventoryPublications`;
- `durableSuiteStabilityServingInventoryPublicationFloor`.

`convergedSuiteStabilityAuthorityTrustCohort` also requires one serving-inventory generation when
external inventory is enabled. The authorizer descriptor, inventory health, and cohort health expose
only fixed status, booleans, timing policy, counts, publication state, and aggregate sequence. They
omit endpoint, ETag, inventory/instance/publication/checkpoint ids, fingerprints, signatures,
authority/key ids, public keys, and private material.

Operations should alert on `REFRESH_UNAVAILABLE`, `SOURCE_EXPIRED`, `REVOKED`, publication/witness
expiry, `SERVING_INVENTORY_GENERATION_DIVERGED`, or a cohort lease count below the signed inventory.
A refresh-success counter without `available=true` is not readiness: signed revocation is a successful
refresh and intentionally reports health down.

## Verification

The original dynamic-source focused gate executes 72 tests, and the durable-floor focused gate adds
database reconstruction, exact idempotency, scope isolation, rollback/fork/gap/predecessor rejection,
record corruption, store outage, and real two-replica successor contention. Coverage also includes real
loopback HTTP negotiation, ETag/304, generic and prefix-collision media downgrade, response bounds,
strict JSON, Ed25519 thresholds, trust-domain/key independence, active bootstrap, signed runtime
revocation, refresh recovery, hard source age, publication/witness expiry, sequence fork/gap/broken
predecessor, nested inventory rollback, dynamic policy construction, health/capability privacy,
additive database migration, and two-replica generation divergence/recovery.

The complete Resource Gateway `clean verify` executes 2711 tests with zero failures, zero errors,
and 2 conditional browser skips, then successfully repackages the executable Spring Boot JAR.

## Deliberate Limits

1. Restoring the durable-floor database itself to an older backup is not externally detectable.
2. A compromised witness threshold can equivocate; there is no transparency log, gossip, or
   cross-region checkpoint comparison.
3. Deployment and witness public-key sets are startup configuration. Restart-free trust-root
   rotation and revocation are not implemented for this protocol.
4. The adapter verifies independent trust domains, authority ids, and key bytes, but does not prove
   organizational separation or signer ceremony quality.
5. KMS/HSM signing, mTLS, certificate pinning, endpoint HA, external alert routing, chaos, capacity,
   non-H2 dialect, backup/restore rollback, cross-region failover, and DR certification remain open.
6. Publication renewal over one nested inventory is restart-free. A replacement inventory changes
   the frozen cohort policy and deliberately requires a governed cohort rollout.
7. The dynamic serving-inventory witness does not witness the separate current-authority JWKS. A
   signed-JWKS transparency mechanism remains a distinct trust problem.

The next root-cause step is an externally anchored non-equivocation proof and restart-free trust-root
rotation, followed by deployment certification for KMS/HSM, mTLS, HA, chaos, non-H2 storage, backup
rollback, and regional DR.
