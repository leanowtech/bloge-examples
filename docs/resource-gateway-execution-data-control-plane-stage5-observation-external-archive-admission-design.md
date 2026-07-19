# Stage 5 observation external-archive admission design

**Implemented non-production core (2026-07-20): independently verified external WORM acknowledgement is a
mandatory, persisted precondition for every future compact-observation floor retirement. This
increment closes the write-side deletion-authority gap, and lifecycle v2 now exports the exact
receipts for caller-policy verification. Production HTTP authority transport, legal hold, backup
purge, disaster-recovery certification, and witnessed lifecycle non-equivocation remain later
gates.**

## 1. Strongest judgment

The current local retirement transaction is internally consistent but not industrially durable. It
inserts the compact archive and retirement in the same database transaction that deletes active
rows. A successful transaction therefore proves atomic local movement, not survival outside the
same database, backup, operator, or administrative failure domain.

Adding a scheduler before fixing that boundary would automate an unsafe authority. The correct
order is:

1. freeze and sign the exact retirement candidate;
2. store that complete signed object in external immutable storage;
3. independently verify a policy-sized set of signed receipts;
4. persist the exact receipt set in the local retirement transaction;
5. only then move floor/head and delete the active prefix.

The safety preference is explicit: an external orphan after a local CAS race is acceptable and
reconcilable; local deletion without a proven external copy is not.

## 2. Scope boundary

| Owner | Owns now | Deliberately does not own |
| --- | --- | --- |
| Retirement planner | exact floor/head/archive/policy candidate under suite lock | external storage or policy approval |
| Retirement signer | canonical signed retirement and nested compact archive | WORM durability claim |
| External archive authority | fresh request, remote immutable write, receipt signature verification, copy threshold | local floor/head mutation |
| Repository commit | receipt canonicality, exact retirement binding, atomic receipt/archive/retirement/floor/head/delete | external key resolution or remote I/O |
| Enterprise governance | retention policy approval, legal hold, erasure exception, provider certification | Resource Gateway graph execution |
| Future lifecycle consumer | independent receipt and chain verification | trusting Gateway's local database as an external witness |

The archive object remains payload-free: compact observations contain identities, fingerprints,
status projections, timing, and signatures, not fixture values, credentials, request payloads, or
node outputs.

## 3. Protocol entities

### 3.1 `ExternalArchiveRequest.v1`

One request binds:

- external `trustDomain` and `archiveSetId`;
- the complete signed `FloorRetirement`, including the compact archive segment;
- requested immutable `retainUntil`;
- fresh 256-bit challenge;
- whole-second `requestedAt` and a maximum 60-second `expiresAt`;
- canonical `requestFingerprint` over every field except itself.

The external object id is deterministic over retirement id/fingerprint, archive id/fingerprint, and
retention-policy fingerprint. A provider retry must therefore be idempotent for exact material and
must report an authenticated conflict for different material under the same object identity.

### 3.2 `ExternalArchiveReceipt.v1`

Each independently trusted authority signs:

- exact request, trust domain, archive set, authority, failure domain, and key identity;
- deterministic object id;
- exact retirement and archive identities/fingerprints;
- exact retention-policy fingerprint and authority-enforced `retainUntil`;
- external `storedAt` plus short receipt issue/expiry window;
- fixed `COMPLIANCE` retention mode;
- explicit `externallyDurable`, `writeOnce`, and `deleteBeforeRetentionDenied` assertions;
- Ed25519 algorithm and detached signature.

The short receipt expiry limits admission replay. It does not shorten `retainUntil`; that deadline is
part of the independently signed durable-storage claim.

### 3.3 `ExternalArchiveReceiptSet.v1`

The set contains the complete request, required copy count, authority-id-sorted receipts, local
confirmation time, deterministic set id, and canonical set fingerprint. Authority ids and failure
domains must each be unique. The set may contain more receipts than required, but never fewer.

The set fingerprint is a local integrity envelope, not a substitute for external signatures. The
configured authority must verify every retained receipt before the repository sees it.

The three wire objects are frozen in the strict standalone
[`suite-stability-observation-external-archive-v1.schema.json`](schemas/resource-gateway-testing/suite-stability-observation-external-archive-v1.schema.json)
and duplicated as authoritative definitions in `testing-control-plane-v1.schema.json`. Both reject
unknown properties and business payload fields. Java validation remains authoritative for temporal
ordering, sorted authority ids, distinct failure domains, exact nested binding, canonical
fingerprints, and cryptographic trust because JSON Schema cannot prove those relations.

## 4. Mandatory invariants

1. No repository API exists that can commit a floor retirement without a receipt set.
2. The request embeds the exact complete signed retirement; references alone are insufficient.
3. Request, receipt, and set fingerprints are independently recomputed before commit.
4. Every receipt binds the same request, retirement, archive, policy, and equal-or-longer retention.
5. Every receipt's object id equals the deterministic id derived from the retirement.
6. Receipt authority ids and failure domains are unique and sorted canonically.
7. `requiredCopies >= 1`, `receiptCount >= requiredCopies`, and both are bounded.
8. The external authority descriptor may claim availability only with external durability,
   challenge binding, compliance retention, and a valid independent-copy topology.
9. The service re-verifies the receipt set after archive and immediately before local commit.
10. The repository performs no remote I/O while holding the exact-suite database lock.
11. The repository persists the exact receipt set before local archive/retirement insertion and
    active-prefix deletion in the same transaction.
12. Exact replay requires byte-equivalent retirement and receipt-set material.
13. A changed floor, head, or active row after external storage aborts the local transaction and
    leaves all active rows untouched.
14. Receipt insertion, archive insertion, retirement insertion, floor/head CAS, and active deletion
    either all commit or all roll back.
15. Existing historical local retirements are not silently relabelled as externally archived.

## 5. External-first state machine

```text
ELIGIBLE
  -> PLANNED
  -> RETIREMENT_SIGNED
  -> EXTERNAL_ARCHIVE_REQUESTED
  -> RECEIPTS_VERIFIED
  -> LOCAL_COMMITTING
  -> COMMITTED
```

Failure transitions:

| State | Failure | Result |
| --- | --- | --- |
| `PLANNED` | retirement signer unavailable | no external write, no local mutation |
| `RETIREMENT_SIGNED` | archive authority unavailable | no local mutation |
| `EXTERNAL_ARCHIVE_REQUESTED` | invalid signature/copy topology | no local mutation, security event |
| `EXTERNAL_ARCHIVE_REQUESTED` | authenticated immutable conflict | no local mutation, operator intervention |
| `RECEIPTS_VERIFIED` | append or retirement race | external orphan, no local mutation |
| `LOCAL_COMMITTING` | any insert/CAS/delete failure | full local rollback; external object remains |
| `COMMITTED` | response loss | exact retirement plus exact receipt-set replay returns same floor |

An external orphan is negative storage efficiency but positive safety. A later reconciliation
worker can compare external object ids with locally committed receipt-set ids and classify orphans;
it must never delete an object before its signed retention deadline.

## 6. Database contract

`rg_test_suite_stability_observation_archive_receipts` stores one exact set per retirement:

- receipt-set id and fingerprint;
- retirement id/fingerprint;
- scope and retirement generation;
- archive segment id/fingerprint;
- retention-policy fingerprint and requested retain-until;
- required/actual copy counts and confirmation time;
- strict serialized receipt set.

Unique constraints bind retirement identity and `(scope, generation)`. Indexed columns are checked
against strict JSON on every read. The row is inserted before local archive and retirement rows in
the same transaction. Exact replay reads and compares the stored set before returning the historical
successor floor.

The migration creates an empty receipt table. It does not fabricate receipts for historical local
retirements. Those generations remain locally verifiable but cannot acquire a retroactive external
durability claim until a separately authorized backfill exports their still-present local archives
and obtains genuine external receipts.

## 7. Trust and cryptography

The repository verifies canonical structure but does not resolve external keys. Key resolution,
signing-time validity, trust-domain membership, failure-domain uniqueness, and detached Ed25519
verification belong to the external archive authority implementation. This split keeps network and
key operations outside the database lock while preserving a narrow internal commit boundary.

The authority descriptor is capability metadata, not proof that a provider is legally certified.
Enterprise deployment still needs provider certification, account/IAM separation, retention-policy
approval, audit export, key custody, and contractual evidence.

## 8. Concurrency and idempotency

| Race | Required behavior |
| --- | --- |
| append after planning, before external write | local commit rejects pinned head; external object may be orphaned |
| second retirement wins first | stale commit rejects pinned floor/head |
| same request reaches authority twice | exact material returns an accepted idempotent receipt |
| same object id with different material | authenticated conflict, never availability retry |
| local response lost after commit | exact receipt-set replay returns same successor |
| local receipt row differs on replay | fail closed as corruption/conflict |
| one authority unavailable | commit only if remaining independently verified receipts meet threshold |
| receipt window expires before commit | authority verification rejects; no local mutation |

## 9. Required tests

The write-side focused gate now executes 52 tests with zero failures, errors, or skips. Executable
tests prove:

- canonical request, receipt, object id, set id, and set fingerprint derivation;
- malformed challenge, shortened retention, duplicate authority/domain, unsorted receipts, and
  insufficient copies are rejected;
- archive authority outage, invalid verification, and authenticated conflict leave all local rows
  untouched;
- successful external-first retirement persists one exact receipt set and removes only the bounded
  active prefix;
- receipt projection or JSON tampering is unreadable;
- exact replay accepts only identical retirement plus receipt set;
- concurrent append after external acknowledgement leaves no local receipt/archive/retirement row;
- any failure after receipt insertion rolls the receipt row back with the local transaction;
- legacy local retirement rows are not presented as externally acknowledged.

The gate combines four protocol-integrity tests with 48 database/service tests. It directly proves
that authority outage, invalid verification, and authenticated immutable conflict leave the receipt,
archive, retirement, floor, head, and active-row surfaces unchanged; a post-acknowledgement append
and any later transaction failure also leave no partial local receipt row.

Receipt-aware lifecycle v2 now exports each exact persisted set next to its retirement and the
independent test-kit verifies external signatures against caller-pinned topology and retention
policy. This closes proof portability; it does not turn the in-memory preview authority into a
production storage provider. The remaining root dependency is a certified HTTPS multi-authority
WORM adapter with historical trust publication, orphan reconciliation, legal hold/erasure, backup
purge, disaster-recovery continuity, and externally witnessed non-equivocation. See the
[lifecycle v2 external-proof design](resource-gateway-execution-data-control-plane-stage5-observation-lifecycle-v2-external-proof-design.md).

## 10. Deliberately unclaimed and next stages

This write-side core does not yet claim a deployable WORM integration. The next stages are ordered:

1. strict HTTPS multi-authority adapter with bounded bodies/timeouts, no redirects, fresh challenge,
   configured Ed25519 keys, signing-time policy, health, and staging-required wiring;
2. external orphan inventory/reconciliation without early deletion authority;
3. legal-hold precedence and release authorization;
4. backup/replica purge evidence and disaster-recovery continuity;
5. externally witnessed retirement-generation non-equivocation and rollback detection;
6. only then a database-leased bounded retirement scheduler, backlog SLO, readiness, and capability
   advertisement.

The capability remains false throughout this core increment. The honest claim is narrower: the
local deletion path is structurally incapable of committing without an exact, previously verified
external immutable-archive receipt set, and v2 consumers can independently verify that recorded
acknowledgement.
