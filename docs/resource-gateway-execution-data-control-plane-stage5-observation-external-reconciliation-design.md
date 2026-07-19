# Stage 5 observation external reconciliation control-plane design

**Phases A-C and both terminal completeness gates implemented (2026-07-20): every externally
acknowledged WORM copy is normalized into a payload-free expected-object index in the exact
retirement transaction. Per-authority database-clock owner/token/epoch leases now fence durable
snapshot cycles; verified page envelopes and normalized items commit atomically with cursor/root
progress, and a terminal page succeeds only after a constant-memory replay reproduces every staged
item, page sequence, signed count, and signed root. Each completed cycle is then compared with an
atomically frozen local snapshot by a bounded ordered merge; only a second terminal replay may expose
self-verifying classifications. Governed findings, scheduling, and capability wiring remain
subsequent phases, so reconciliation is not yet advertised as complete.**

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
| Reconciliation control plane | database lease, durable remote pages, root completion, frozen comparison and classification evidence | WORM mutation or remediation |
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
by the ordered comparison:

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

## 7. Durable authority cycle

`DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane` exposes one narrow
operation: `stageNextPage(authorityId)`. Its settings freeze a stable replica owner, a whole-second
1..3600 second lease, and a 1..500 item page size. Callers cannot supply lease tokens, epochs,
snapshots, cursors, challenge material, or completion claims.

Three durable layers preserve progress:

| Table | Durable fact |
| --- | --- |
| `external_inventory_authorities` | current owner/token/epoch/deadline/revision, active cycle, last completed cycle |
| `external_inventory_cycles` | pinned trust/archive/failure-domain topology, snapshot, next cursor/sequence, accumulated count/root, lifecycle |
| `external_inventory_pages/items` | normalized page topology, signed page envelope, and payload-free item facts |

Lease acquisition locks one authority row and uses database `CURRENT_TIMESTAMP`. A live lease returns
`BUSY` before any remote call. An expired lease increments epoch and revision and resumes the exact
active cycle; a new cycle is created only when no active cycle exists. Remote HTTPS I/O then happens
outside the transaction. The old owner cannot commit after takeover because page commit rechecks
authority, active cycle, owner, random token, epoch, revision, exact deadline, and the exclusive
database-clock expiry boundary.

Every successful or rejected page releases its lease with an exact fence. A verifier-level
`INVALID_PAGE` or `UNAVAILABLE` preserves the cycle and cursor for a fresh challenge retry. Provider
`SNAPSHOT_EXPIRED` atomically marks the old cycle terminal and clears the active-cycle pointer; only a
later invocation can create a new page-zero cycle.

## 8. Atomic page and completeness gate

The adapter first verifies request binding, topology, key lifecycle, snapshot identity, freshness,
item/page fingerprints, and Ed25519 signature. The control plane invokes that verifier again, then in
one `REQUIRES_NEW`, `READ_COMMITTED` transaction:

1. locks and verifies the exact live lease;
2. locks and validates the active durable cycle;
3. binds page authority, maximum size, request expiry, page expiry, snapshot, object cursor, and page
   sequence to the durable cursor;
4. advances the order-sensitive root and bounded object count;
5. inserts the exact signed page JSON and normalized item rows;
6. on terminal, streams every staged item in object-id order, reconstructs and fingerprints it,
   replays the root/count, and proves page sequence `0..N` has no gap;
7. advances or completes the cycle and releases the lease.

Steps 1-7 share one transaction. A terminal root mismatch, missing prior page, corrupt staged item,
cursor drift, duplicate identity, lease takeover, serialization failure, or final CAS failure rolls
back the new page, items, cursor, and completion. The caller's surrounding transaction cannot undo a
committed page because control-plane transactions are independently `REQUIRES_NEW`.

The accumulated root is only a resumable checkpoint. It is never accepted as terminal proof without
the independent streaming replay. This avoids O(snapshot size) heap use while refusing to derive a
completeness claim from mutable aggregate columns alone.

## 9. Frozen bidirectional classification

`DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane` accepts only the
authority pointer to a completed cycle. Its first `compareNextPage(authorityId)` transaction locks
that pointer, replays the complete remote item root, copies the current local expected-object set to
an immutable comparison snapshot, independently replays a topology-bearing expected root, and
publishes one active comparison. A local object committed later cannot enter the already pinned
comparison; it is visible to the next completed remote cycle.

Each subsequent `REQUIRES_NEW` call locks the comparison authority and active run, reads at most
`N + 1` rows from each sorted source, chooses a safe shared upper bound, and commits no more than
`2N` union outcomes plus one exact cursor. No remote I/O occurs while these rows are locked. A process
or replica switch resumes the same frozen snapshots and cursor. The closed outcome rules are:

Comparison timestamps remain monotonic across lock waits. Because PostgreSQL/H2
`CURRENT_TIMESTAMP` denotes transaction-start time, a transaction that started before waiting for
the authority lock may otherwise publish time older than the row it eventually acquires. After the
lock, the control plane therefore advances time to the maximum of transaction database time and the
locked record's persisted `updated_at`. This database-derived Lamport lower bound prevents time
regression without relying on a process clock or a second connection.

| Outcome | Deterministic meaning |
| --- | --- |
| `MATCHED` | topology and canonical item fingerprint are identical |
| `MISSING_REMOTE` | expected object exists, complete remote snapshot has no object id |
| `UNEXPECTED_REMOTE` | remote object exists, frozen local snapshot has no object id |
| `RETENTION_SHORTENED` | same topology and object id, observed retain-until is earlier |
| `MATERIAL_CONFLICT` | same topology and object id, other immutable material differs |
| `UNKNOWN` | both object ids exist but topology drift makes material comparison unsafe |

Every row binds comparison, cycle, authority, object, both optional item/commitment/retention facts,
both topology facts, and outcome in a canonical fingerprint. The mutable comparison state has a
whole-record fingerprint and exact outcome counters/root. These are checkpoints, not the completion
oracle. Before changing `ACTIVE` to `COMPLETED`, the control plane independently:

1. streams and reproduces the frozen expected count/root;
2. streams and reproduces the signed remote count/root;
3. streams and reproduces every classification fingerprint, count, outcome counter, and root;
4. proves classifications cover the exact local/remote object-id union with no missing or extra row;
5. re-derives every outcome from a fresh SQL source-union and compares the complete semantic record.

The fifth gate deliberately rejects a stable but incorrect classifier even when an operator has
recomputed every public SHA checkpoint consistently. Any failure rolls back the terminal page and
cursor. `classifications(comparisonId, afterObjectId, limit)` exports only a completed comparison in
strict keyset order and verifies each row again. The public boundary has no remediation operation.

## 10. Failure analysis

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
| two replicas read the same page | process-local lock | database-clock authority lease and random token |
| old worker commits after takeover | owner-only lease | owner/token/epoch/revision/deadline fence |
| remote I/O holds database locks | transaction wraps network call | acquire, remote read, and commit are separate transactions |
| process dies between pages | in-memory cursor | active cycle and exact cursor persisted after every page |
| process dies after remote response | response treated as checkpoint | no cursor advance before local page transaction commits |
| provider expires continuation | implicit restart mixes snapshots | terminal `SNAPSHOT_EXPIRED` cycle before new page zero |
| aggregate root is tampered | final proof trusts checkpoint | stream all staged item fingerprints at terminal |
| historical page disappears | items alone hide envelope gap | terminal page-span proof requires exact `0..N` |
| surrounding request rolls back | propagation joins caller | independent `REQUIRES_NEW` mutations |
| local objects change between comparison pages | live-table merge has no stable left side | atomically frozen expected snapshot per remote cycle |
| one side has a denser key range | equal offsets skip the sparse side | safe shared object-id upper bound over two keyset windows |
| process dies during comparison | in-memory merge cursor | active comparison, page sequence, counts, root, and cursor are durable |
| valid-shaped source row is changed | constructor shape mistaken for integrity | canonical item and topology-bearing root replay |
| classification row disappears | aggregate state trusted alone | terminal classification stream and exact union coverage |
| classifier emits stable wrong outcome | self-hash is circular evidence | independent source-union semantic derivation |
| transaction waits for authority lock and publishes older time | `CURRENT_TIMESTAMP` is fixed at transaction start | advance from the maximum of database time and locked persisted `updated_at` |
| partial comparison is exported | staged rows look authoritative | export requires fingerprint-verified `COMPLETED` state |

## 11. Executable evidence

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

The Phase A Resource Gateway `clean verify` executed 2914 tests with zero failures and errors, two
existing browser-environment skips, and a successfully repackaged Spring Boot executable JAR.

The Phase B focused gate adds 11 green database tests proving:

- a two-page pinned snapshot starts on one replica and completes on another;
- a live lease returns `BUSY` without remote I/O;
- database-clock expiry permits takeover and rejects the stale worker fence;
- invalid verification releases only the lease and preserves the exact cursor;
- snapshot expiry closes the old cycle before a new page-zero cycle;
- terminal count/root mismatch rolls back page, items, cursor, and completion;
- terminal streaming replay detects a valid-shaped tampered staged item;
- outer transaction rollback cannot erase a committed page checkpoint;
- an empty signed snapshot completes on the domain-separated empty root;
- corrupt durable cursor state fails before lease acquisition or remote I/O;
- a missing historical page envelope blocks terminal completion.

The frozen Phase B source then passed the complete Resource Gateway `clean verify`: 2925 tests, zero
failures, zero errors, two existing browser-environment skips, and a successfully repackaged Spring
Boot executable JAR.

The Phase C focused gate adds 13 green database tests and the joint A-C gate executes 83 tests with
zero failures, errors, or skips. They prove all six outcomes, bounded multi-page order, immutable
local snapshot cuts, next-cycle visibility, cross-replica resume, expected/remote/run/classification
tamper rejection, missing historical classification rollback, semantic-oracle independence, active
export denial, empty roots, outer-transaction isolation, current-cycle idempotency, and absence of a
destructive public operation. A lock-wait regression test additionally proves that a transaction
started before the authority lock cannot move persisted comparison time backward.

The frozen Phase C source passed the complete Resource Gateway `clean verify`: 2938 tests, zero
failures, zero errors, two existing browser-environment skips, and a successfully repackaged Spring
Boot executable JAR.

## 12. Remaining phases and acceptance

The local expectation, durable remote cycle, frozen comparison, bounded ordered merge, and terminal
semantic classification portions of Phases A-C are complete. No partial or active comparison can be
exported as governance evidence.

Phase D must persist payload-free governed findings for at least `MISSING_REMOTE`, `UNEXPECTED_REMOTE`,
`MATERIAL_CONFLICT`, `RETENTION_SHORTENED`, and `UNKNOWN`. Finding open/reopen/observe/resolve transitions
must be fingerprinted, fenced, retained, exported, and separated from remediation authority.

Until findings, scheduler, health/readiness, retention, and capability truth are implemented,
Resource Gateway may claim **durable local expectation indexing, verified inventory cycle staging,
and completed payload-free classification evidence**, but not governed external orphan
reconciliation.
