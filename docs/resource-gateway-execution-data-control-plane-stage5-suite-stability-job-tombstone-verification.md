# Stage 5 Suite-Stability Job Tombstone Verification

## 1. Delivered boundary

This increment closes request resurrection after detailed terminal queue records expire. It adds a
bounded repository retention primitive, not a background retention service or a public asynchronous
API.

The primitive atomically:

1. reads one bounded, database-clock-expired terminal job page under row locks;
2. verifies every source whole-record fingerprint;
3. derives a tenant/environment-bound keyed HMAC index for the caller request identity;
4. inserts a payload-free, integrity-fingerprinted tombstone;
5. deletes the source only through its exact previous fingerprint;
6. verifies and deletes one independent bounded page of expired tombstones.

Any malformed source, tombstone, key generation, lifecycle, or exact-delete fence rolls back the
whole page. The repository accepts 1 through 10,000 records and 1 through 3,650 whole days of
tombstone retention; it rejects out-of-range values instead of silently clamping them.

## 2. Replay semantics

The detailed job row remains the replay authority while it exists. After it becomes a tombstone:

| Incoming request | Result |
| --- | --- |
| same scoped request identity and exact submission fingerprint | `REPLAY_WINDOW_EXPIRED` |
| same scoped request identity but another submission fingerprint | `IDEMPOTENCY_CONFLICT` |
| request identity whose tombstone has expired | identity may be used again |

The tombstone contains no `clientRequestId`, job id, request JSON, principal, suite reference, actor,
or payload. It stores only scope, a versioned HMAC index, submission fingerprint, lifecycle times,
record version, and whole-record fingerprint. A plain digest is intentionally rejected as a design:
caller idempotency keys can be human-readable and vulnerable to offline enumeration.

## 3. Key authority and rotation

`TestSuiteStabilityJobRequestKeyProtector` derives an independent HMAC-SHA-256 key domain from each
32-byte configured root. Its key and message domains are separate from recovery-sequence and worker-
quarantine request indexes. Every index also binds exact tenant and environment scope.

New tombstones use only the active generation. Reads try an active-first set of at most 16 configured
generations and compare MACs in constant time. Because plaintext request identity is erased, an old
tombstone cannot be re-keyed. Rotation therefore uses this order:

1. append the new key to every replica's key ring;
2. prove every serving replica can read old and new generations;
3. switch the active generation;
4. retain the old verification key until its final tombstone expires;
5. remove the old key only after retention evidence proves no live reference remains.

Repository startup scans distinct unexpired tombstone generations and fails closed when any
generation is unavailable. The active key id is non-secret; root material must come from deployment
secret injection. Local profile defaults are demonstration material and must not be used as an
enterprise secret.

## 4. Transaction and concurrency proof

Source insertion and replay already serialize on the environment authority. Retention locks the
terminal source before creating its tombstone, and source deletion occurs only after tombstone
insertion in the same transaction. A concurrent replay therefore observes either the retained source
or the committed tombstone, never an empty resurrection window.

Expired tombstones are exact-fingerprint deleted. Submission may also remove an expired matching
tombstone under the same transaction before reusing the identity. A live duplicate across key
generations, a corrupt record fingerprint, or a missing verification key is treated as control-plane
ambiguity and fails closed.

## 5. Configuration

The profile-gated authority reads:

```text
RG_TEST_STABILITY_JOB_REQUEST_KEY_ACTIVE_ID
RG_TEST_STABILITY_JOB_REQUEST_KEY_RING
```

The key ring syntax is a comma-separated `keyId=base64Root` list. Each decoded root must be exactly
32 bytes; the active id must occur exactly once. Both test and staging composition roots create this
authority even while the worker is disabled because replay safety belongs to storage, not worker
lifecycle.

## 6. Verification

Focused verification covers deterministic scope binding, domain separation, active-first rotation,
malformed configuration, terminal detail replacement, plaintext absence, exact replay expiry,
changed-intent conflict, old-generation reads, missing-key startup failure, corrupt-source rollback,
corrupt-tombstone rejection, tombstone expiry/reuse, and strict retention bounds.

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=TestSuiteStabilityJobRequestKeyProtectorTest,\
DatabaseTestSuiteStabilityJobRepositoryTest,\
RepositoryTestSuiteStabilityJobParentAuthorityTest,\
TestRuntimeProfileIsolationTest test
```

The 45 focused tests pass with zero failures, errors, or skips.

## 7. Explicit remaining gap

No background component invokes retention yet. A product-ready follow-up must add a database-clock,
cross-replica lease and fenced scheduler, aggregate-only success/failure counters, last-success
freshness readiness, bounded retry/backoff, and operator runbook. Until that lands, this increment is
a correctness-preserving repository primitive and must not be described as automated retention.

Authenticated job HTTP, strict Schema, capability truth, independent test-kit support, poison-row
quarantine/repair, non-H2 dialect certification, backup erasure, and soak/chaos/DR evidence remain
outside this increment.
