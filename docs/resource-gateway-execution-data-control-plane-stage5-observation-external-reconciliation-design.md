# Stage 5 observation external reconciliation control-plane design

**Phase A implemented (2026-07-20): every externally acknowledged WORM copy is now normalized into
a payload-free expected-object index in the exact retirement transaction. Existing receipt sets are
backfilled in bounded keyset pages, exact retirement replay repairs absence, and any material drift
fails closed. Database-clock authority leases, durable remote page staging, final root replay,
bidirectional classification, and governed findings remain subsequent phases; reconciliation is not
yet advertised as complete.**

## 1. Root problem

The signed inventory protocol makes one remote page admissible, but a remote scan is meaningless
without an independently committed local expectation. Parsing receipt-set JSON during every scan is
not a viable expectation store:

- one retirement can have several independently acknowledged authorities;
- a full JSON scan is unbounded and cannot support ordered merge comparison;
- a receipt-set row and a later derived index can diverge if they are committed separately;
- retired observation payload must not be copied into an operational reconciliation table;
- a crash or replica switch must not change which objects Resource Gateway expects to exist.

The first durable reconciliation invariant is therefore:

> An active observation prefix may be deleted only in a transaction that also commits one canonical
> expected inventory item for every accepted external authority receipt.

## 2. Ownership boundary

| Component | Owns | Deliberately does not own |
| --- | --- | --- |
| Retirement repository | receipt-set persistence, expected-object normalization, local deletion transaction | remote inventory reads, findings, external deletion |
| Expected-object index | exact local comparison material per authority and object | retired observations, credentials, provider addresses |
| Reconciliation control plane, next phase | database lease, durable remote pages, root completion, merge comparison | WORM mutation |
| ANEKE/governance | disposition, exception workflow, remediation approval | rewriting signed local or remote facts |
| External authority | immutable object retention and signed inventory | local governance classification |

No delete, purge, overwrite, retention shortening, or legal-hold release operation is added to the
inventory or repository boundary.

## 3. Canonical local expectation

`TestSuiteStabilityObservationExternalArchiveInventoryIntegrity.expectedItem(...)` converts an exact
member of a canonical committed receipt set into `ExternalArchiveInventoryItem.v1`. It derives the
retention-bearing object commitment again from the signed retirement and receipt retention deadline;
it never trusts a duplicated commitment column supplied by a caller.

The resulting comparison item binds:

- deterministic object id;
- object commitment;
- retirement id and fingerprint;
- compact archive segment id and fingerprint;
- retention policy fingerprint;
- retain-until and provider stored-at times;
- canonical item fingerprint over all preceding fields.

The projection rejects a receipt that is not an exact member of the supplied receipt set. Signature
admission remains the external authority adapter's responsibility; normalization does not invent a
second trust decision.

## 4. Durable schema

`rg_test_suite_stability_observation_external_archive_objects` has one row per
`(authority_id, object_id)`. It also enforces one object per `(authority_id, retirement_id)` and a
globally unique receipt fingerprint.

| Column family | Purpose |
| --- | --- |
| topology | trust domain, archive set, authority, failure domain |
| immutable identity | object, retirement, and segment ids |
| integrity | object commitment, retirement/segment/policy/item fingerprints |
| receipt lineage | receipt fingerprint, receipt-set id and fingerprint |
| retention | retain-until and stored-at |
| operations | database-derived indexed-at |

The table has no JSON, business payload, observation, input/output, signature, challenge, endpoint,
key, or credential column. Its object-id and retirement indexes support the two directions required
by the future ordered comparison:

1. remote item to local expectation, detecting unknown or conflicting remote objects;
2. local expectation to completed remote snapshot, detecting acknowledged objects that disappeared.

## 5. Atomic write path

For a new retirement, one `REQUIRES_NEW`, `READ_COMMITTED` transaction now performs:

1. lock the exact observation scope;
2. verify signed retirement and canonical external receipt set;
3. pin and verify current floor, head, and every active archive row;
4. insert the receipt set;
5. derive and insert every expected authority object;
6. insert compact archive and signed retirement;
7. advance floor and head;
8. delete the exact active prefix and verify the deleted count.

Any uniqueness conflict, projection mismatch, archive conflict, floor/head race, or incomplete delete
rolls back all eight effects. There is no state in which the active observations are gone while the
local reconciliation expectation is absent.

An exact retirement replay reprojects the stored receipt set. Missing rows are repaired in the replay
transaction; an existing row whose topology, lineage, commitment, times, or item fingerprint differs
is treated as storage corruption and rejected.

## 6. Historical migration

Repository startup performs a bounded keyset backfill over receipt-set id:

```text
WHERE receipt_set_id > last_id
ORDER BY receipt_set_id
LIMIT 500
```

Each receipt set is reloaded through the existing column-to-canonical-record consistency verifier,
then indexed in its own transaction. This has bounded heap use, exact replay semantics, and safe
convergence when several replicas start together. A new concurrent retirement writes its own index
inside its commit, so it cannot be lost behind the migration cursor.

Malformed historical JSON, contradicted projection columns, invalid receipt-set integrity, or an
index uniqueness collision with different material fails startup. Availability is not preferred over
silently omitting an acknowledged WORM object from reconciliation.

## 7. Failure analysis

| Failure | Root cause | Control |
| --- | --- | --- |
| receipt stored but expectation absent | asynchronous derived-index writer | same database transaction |
| active rows deleted before index insert | incorrect mutation order | index inserted before floor/head advance and delete |
| restart loses in-memory expectation | process-local derivation | durable normalized table |
| old deployments have receipt rows only | additive schema evolution | bounded fail-closed startup backfill |
| two replicas backfill the same object | concurrent startup | unique identity plus exact-material replay |
| duplicate id hides different material | idempotency based only on key | reconstruct and verify every canonical field |
| compromised row changes one digest | unverified normalized columns | recompute expected item fingerprint on replay |
| reconciliation table leaks evidence payload | copying archive JSON | fixed payload-free column vocabulary |
| scanner parses all receipt JSON repeatedly | missing query projection | indexed normalized comparison rows |

## 8. Executable evidence

The focused gate executes 59 tests with zero failures, errors, or skips. New and extended cases prove:

- a canonical receipt projects to the exact expected inventory item;
- a non-member receipt cannot be projected;
- successful retirement persists every exact topology, lineage, commitment, and item field;
- the table has no JSON, payload, or observation column;
- retirement conflict and missing-prefix failures leave no expected-object row;
- concurrent replicas converge on one receipt, archive, retirement, and expected object;
- restart backfills a deliberately removed historical index row;
- corrupt historical receipt JSON blocks startup;
- exact replay repairs absence and rejects valid-shaped commitment drift.

The complete Resource Gateway `clean verify` executes 2914 tests with zero failures and errors, two
existing browser-environment skips, and a successfully repackaged Spring Boot executable JAR.

## 9. Remaining phases and acceptance

Phase B must add a per-authority database-clock owner/token/epoch lease and a durable cycle cursor.
Every verified remote page must be stored before the same transaction advances the cursor. A process
crash must resume the exact pinned snapshot; snapshot expiry must close the old cycle explicitly
before a new page-zero request.

Phase C must replay all staged item fingerprints and require the terminal accumulated count/root to
equal the signed snapshot values. Only a completed snapshot may enter an ordered bidirectional merge
against this local expected-object index.

Phase D must persist payload-free governed findings for at least `MISSING_REMOTE`, `UNEXPECTED_REMOTE`,
`MATERIAL_CONFLICT`, `RETENTION_SHORTENED`, and `UNKNOWN`. Finding open/reopen/observe/resolve transitions
must be fingerprinted, fenced, retained, exported, and separated from remediation authority.

Until Phases B-D are implemented and wired behind readiness/capability truth, Resource Gateway may
claim **durable local expectation indexing**, but not external orphan reconciliation.
