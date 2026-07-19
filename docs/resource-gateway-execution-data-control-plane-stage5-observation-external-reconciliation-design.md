# Stage 5 observation external reconciliation control-plane design

**Phases A-E and the Phase F autonomous scheduling/readiness increments implemented (2026-07-20): every externally
acknowledged WORM copy is normalized into a payload-free expected-object index in the exact
retirement transaction. Per-authority database-clock owner/token/epoch leases now fence durable
snapshot cycles; verified page envelopes and normalized items commit atomically with cursor/root
progress, and a terminal page succeeds only after a constant-memory replay reproduces every staged
item, page sequence, signed count, and signed root. Each completed cycle is then compared with an
atomically frozen local snapshot by a bounded ordered merge; only a second terminal replay may expose
self-verifying classifications. Completed comparisons then drive a separately fenced, payload-free
finding projection with immutable transition evidence. Derived finding/evidence retention and a
profile-gated downstream-first scheduler are now active only under explicit test/staging
configuration. Fingerprint-verified stage snapshots, schedule-aware Actuator readiness, and exact
configured/ready capability truth now close the operational observation loop. Source cycle/
comparison/classification retention remains the next phase, and production provider certification,
legal hold/erasure, backup/recovery continuity, historical trust, and witnessed non-equivocation
remain outside this preview.**

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
lock, the control plane therefore uses database time when it is strictly newer; otherwise it advances
the locked record's persisted `updated_at` by one database-portable microsecond. This
database-derived Lamport successor prevents regression and timestamp ties without relying on a
process clock or a second connection. The resulting strict per-authority order is the prerequisite
for replaying finding transitions across completed comparisons.

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

## 10. Governed finding lifecycle

`DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane` consumes completed
comparisons without inheriting their mutable aggregate columns as truth. `projectNextPage(authorityId)`
locks one finding authority, chooses the oldest unprojected comparison, and rejects timestamp ties or
regression. This ordering is sound because the comparison authority now advances with a strict
database-derived Lamport successor: newer transaction time, or locked `updated_at` plus one
microsecond. UUID order is never used to invent business chronology.

Projection start performs two fail-closed actions in one `REQUIRES_NEW` transaction:

1. independently replays the completed comparison count/root/outcome counters and re-derives every
   classification from the frozen expected/observed source union;
2. copies every current finding for the authority into an immutable pre-state snapshot and records
   its count and ordered fingerprint root.

Each later transaction reads at most `N` classifications, where `N` is configured from 1 through
500. For each source object it verifies that live current state still equals the frozen pre-state,
applies one deterministic lifecycle transition, inserts one immutable event, and advances one exact
object-id cursor, page sequence, transition counter set, event root, and whole-record fingerprint.
Replica changes resume the same projection and cannot duplicate `(comparison, object)` evidence.

| Transition | Deterministic rule |
| --- | --- |
| `OPENED` | non-match with no prior finding |
| `OBSERVED` | non-match while the finding is already open |
| `REOPENED` | non-match after a resolved episode |
| `RESOLVED` | `MATCHED` closes an open finding with `MATCHED_ON_RECHECK` |
| `CONFIRMED` | `MATCHED` needs no finding mutation |

`occurrence_count` counts only non-matched observations; `episode_count` increments only on first
open and reopen. Resolution is an evidence state transition, not proof that Resource Gateway
remediated storage. The current row retains the latest non-matched kind, exact classification
lineage, first/last observation, evaluation/resolution times, version, and a whole-record
fingerprint. It contains no payload, receipt, signature, endpoint, credential, owner token, or free
text.

Before publishing `COMPLETED`, the control plane independently replays source classifications,
source semantics, frozen finding root, event root/counters, exact classification-event coverage,
and the complete resulting finding table. The last gate re-derives every transition from immutable
pre-state plus source classification and rejects a stable but wrong event even when its event hash,
event root, transition counters, and projection fingerprint were all recomputed consistently.
`events(...)` repeats complete source/event replay before returning any completed page; `findings(...)`
refuses reads while a projection is partially applied. Neither boundary exposes remediation or a
destructive operation.

## 11. Bounded finding and evidence retention

**Implemented Phase E (2026-07-20):**
`DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane` owns this
derived-data lifecycle. It uses `REQUIRES_NEW`, database time, owner/token/epoch/revision fencing,
whole-record state fingerprints, exact source deletes, permanent evidence-availability markers, and
independent 1..500 row bounds. `operationalSnapshot(...)` exposes only low-cardinality counters and
backlogs; `archives(...)` exposes verified payload-free resolved lifecycle records.

Retention is a protocol transition, not a periodic `DELETE`. A retention worker must never make a
partially removed projection look exportable, remove the compact projection summary that prevents a
completed comparison from being consumed again, or race an active finding projection. Phase E
therefore separates three lifecycles:

| Layer | Retention behavior | Durable fact that remains |
| --- | --- | --- |
| current findings | only resolved rows older than the active window are copied to a payload-free archive and exactly deleted | archive row until its independent window expires; cumulative archive/purge commitment afterward |
| frozen snapshots and events | completed projections older than the evidence window enter `ACTIVE` retirement and are deleted by bounded object-id pages | completed projection summary plus permanent retirement marker and replayed roots/counts |
| source comparison/classification | never deleted by the finding-retention authority | source protocol remains owned by its own later retention policy |

One database-clock lease fences the retention job across replicas. Every tick may archive at most
`N` resolved findings for one authority, purge at most `N` expired archive rows, and remove at most
`N` event plus `N` snapshot rows from one evidence retirement. The job state, lease epoch, exact
cumulative counters, compact purge root, and whole-record fingerprint commit in the same
`REQUIRES_NEW` transaction as the row mutations. The page bound is 1..500; active retention is at
least one hour, archive and evidence retention are at least one day, and every window is capped at
ten years.

Before archiving a resolved row, the worker locks and verifies the authority, proves that no finding
projection is active, verifies the finding fingerprint, inserts a separately fingerprinted archive
record, and deletes only the exact source version/fingerprint. Open findings are never archived.
After archival, a later discrepancy starts a new operational finding lifecycle; the old lifecycle is
still independently visible from the archive until that archive's governed expiry. This explicit
window boundary avoids pretending that a compact operational queue is an eternal case-management
registry.

Evidence retirement first locks and verifies an old `COMPLETED` projection, then atomically creates
an immutable availability marker and durable page progress. `events(...)` checks that marker while
holding the projection lock and refuses both `ACTIVE` and `COMPLETED` retirement, so no reader can
observe a partial deletion. Each retirement page verifies every event/snapshot fingerprint and
extends the original ordered root before exact deletion. Completion is allowed only when the deleted
counts and roots equal the frozen projection summary. The projection summary and retirement marker
are never deleted; they prevent reprocessing and distinguish intentionally retired evidence from
corruption.

## 12. Failure analysis

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
| transaction waits for authority lock and publishes older or equal time | `CURRENT_TIMESTAMP` is fixed at transaction start | use newer database time or the locked persisted `updated_at` plus one microsecond |
| partial comparison is exported | staged rows look authoritative | export requires fingerprint-verified `COMPLETED` state |
| two completed comparisons have equal or regressing time | UUID tie-break invents lifecycle order | strict Lamport successor and fail-closed chronology check |
| process dies while findings are projected | current rows advance without a durable source cursor | authority, projection, cursor, event root, and frozen pre-state commit atomically per page |
| active finding projection is queried | partially applied current table looks final | current finding export is denied while authority is active |
| historical transition disappears | page-only export misses an earlier gap | complete event-root/count and exact source coverage replay before every export |
| source comparison hashes are consistently rewritten | downstream trusts upstream self-hash | re-derive classifications from frozen expected/observed union |
| transition hashes are consistently rewritten | event/root/projection self-hash is circular | re-derive transition and full resulting finding table from frozen pre-state |
| governance resolution gains storage authority | evidence workflow becomes an accidental delete path | finding API has no remediation or WORM mutation operation |
| a timer deletes event rows directly | partial evidence looks like corruption or completeness depends on requested page | create an availability marker before bounded deletion and deny every export during/after retirement |
| retired projection row is deleted | old comparison becomes unprojected again | preserve compact projection summary and permanent retirement marker |
| retention races an active projection | frozen pre-state and resulting table diverge | lock verified authority first and archive only when `active_projection_id` is empty |
| one replica resumes another replica's deletion with stale state | page is skipped or deleted twice | database-clock lease plus owner/token/epoch/revision and exact row fingerprint fences |
| missing/tampered evidence is silently retired | deletion launders pre-existing corruption | verify each row and require terminal count/root equality with the frozen projection |
| archive rows disappear out of band | retention counters still look healthy | verify whole-record archive fingerprints and exact `totalArchived-totalPurged` cardinality |
| old resolved lifecycle remains forever in the active queue | current table grows without operational bound | copy exact resolved state to an independently retained archive before source deletion |
| scheduler opens inventory cycles faster than downstream stages can consume them | source-first periodic polling has no pipeline backpressure | drain finding, then comparison, and open inventory only when both report `CURRENT` |
| one unavailable authority blocks all later authorities | whole-tick exception scope | visit the complete bounded authority set and isolate failure per authority |
| two local scheduled invocations overlap | timer/runtime re-entry | process-local overlap gate plus database authority lease and transactional stage fences |
| half-enabled reconciliation silently does nothing | optional bean composition hides missing authority or replica identity | explicit property requires inventory authority and stable instance id; startup fails closed |
| production accidentally enables maintenance | property-only guard | entire composition root remains under `!production & (test | staging)` profile veto |

## 13. Operational readiness and capability truth

A scheduler heartbeat is not evidence that reconciliation is working. It can continue firing while
one authority fails every pass, a comparison cursor never moves, the latest completed projection is
days old, or retention has stopped deleting eligible rows. Phase F therefore treats operational
truth as a join across process-local scheduling and database-authoritative stage/lifecycle facts:

| Fact | Authority | Readiness use |
| --- | --- | --- |
| latest attempt, latest complete all-authority success, consecutive unhealthy ticks | process-local scheduler clock | detect a dead lane and bounded transient-failure exhaustion after restart grace |
| active inventory update and latest completion | fingerprint-verified database snapshot | detect remote collection stall without exposing snapshot/cycle identity |
| active comparison update and latest completion | fingerprint-verified database snapshot | detect frozen ordered-merge stall without exposing cursor/source identity |
| active finding update and latest replay-verified completion | fingerprint-verified database snapshot | detect governance projection stall and evidence staleness |
| last retention success and eligible resolved/archive/evidence backlog | database-clock retention snapshot | detect lifecycle enforcement failure independently of scheduler liveness |

`TestSuiteStabilityObservationExternalArchiveReconciliationHealth` has four closed states:

| State | Actuator | Meaning |
| --- | --- | --- |
| `INITIALIZING` | `UNKNOWN` | first scheduler/evidence/retention completion is still inside bounded startup grace |
| `HEALTHY` | `UP` | all observed authorities and lifecycle SLOs pass |
| `SLO_VIOLATED` | `OUT_OF_SERVICE` | one or more stable closed violation codes exceed policy |
| `STORE_UNAVAILABLE` | `DOWN` | membership or any integrity-verified durable aggregate is ambiguous |

Stable violations separate scheduler never-success, scheduler staleness, consecutive unhealthy tick
budget, inventory/comparison/finding stage stalls, absent or stale completed evidence, retention
never-success/staleness, and overdue resolved/archive/evidence backlogs. Stage/evidence ages are
calculated against each database snapshot's own clock. Only scheduler liveness uses process time;
restart resets that local history and re-enters bounded `INITIALIZING`, never `HEALTHY` by default.
An `OPEN` finding is a valid business/governance outcome and is reported only as an aggregate count;
it cannot turn infrastructure readiness red.

Health details and logs contain no authority, object, comparison, projection, snapshot, cursor,
lease, key, topology, or fingerprint identity. `/api/integration/capabilities` publishes the same
assessment as `testability.externalArchiveReconciliation` and distinguishes `configured` from
time-sensitive `ready`. The three feature flags separately state monitor assembly and current
readiness, preventing both “bean exists therefore ready” and “degraded therefore absent” errors.
When the profile or property is absent the descriptor is exactly `DISABLED`; any descriptor failure
is projected as configured but `STORE_UNAVAILABLE`.

Policy validation is schedule-aware: startup grace must cover both scheduler intervals; scheduler,
stage, evidence, and retention staleness cannot be shorter than their driving interval; every
duration and backlog count has a hard upper bound. Invalid policy fails startup even before the
first scheduled run.

## 14. Executable evidence

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
started before the authority lock must move persisted comparison time strictly forward.

The frozen Phase C source passed the complete Resource Gateway `clean verify`: 2938 tests, zero
failures, zero errors, two existing browser-environment skips, and a successfully repackaged Spring
Boot executable JAR.

The Phase D focused gate adds 14 green database tests and the joint A-D gate executes 97 tests with
zero failures, errors, or skips. They prove all five transitions across three strictly ordered
comparisons, accumulated-backlog ordering, bounded cross-replica resume, active-export denial,
current-state export, completed-event export, empty roots, outer-transaction isolation, settings and
destructive-API boundaries, source/snapshot/event/current/projection/authority corruption rejection,
missing historical event rollback, complete export replay, and two independent semantic-oracle
attacks in which every public hash and aggregate was made internally self-consistent.

The frozen Phase D source passed the complete Resource Gateway `clean verify`: 2952 tests, zero
failures, zero errors, two existing browser-environment skips, and a successfully repackaged Spring
Boot executable JAR.

The Phase E focused gate adds 14 green database tests and the joint A-E gate executes 111 tests with
zero failures, errors, or skips. They prove bounded resolved archive/purge, open and recent finding
preservation, active-projection exclusion, exact archive cardinality, database-clock policy bounds,
live-lease rejection, cross-replica multi-page evidence retirement, empty and accumulated projection
handling, retirement-time export denial, permanent compact summaries, outer-transaction isolation,
source/progress/marker/archive corruption rejection, and both pre-start and mid-retirement missing
history attacks. In the latter case, already committed pages remain quarantined behind an `ACTIVE`
marker and can never be exported or mislabeled as a successful retirement.

The frozen Phase E source passed the complete Resource Gateway `clean verify`: 2966 tests, zero
failures, zero errors, two existing browser-environment skips, and a successfully repackaged Spring
Boot executable JAR.

The first Phase F increment adds a downstream-first
`TestSuiteStabilityObservationExternalArchiveReconciliationService`, a fixed-delay scheduler, an
independent finding-retention scheduler, and explicit Spring/YAML wiring. One authority invocation
mutates at most one stage. Every scheduler tick visits the complete lexically stable authority set,
which is bounded by the protocol to sixteen members; a failure is contained to one member and logs
carry only aggregate stage counts. A new inventory cycle is opened only after finding projection and
comparison both report `CURRENT`, preventing collection from outrunning governance projection.
Reconciliation remains disabled by default, requires a stable replica instance id when enabled, and
cannot assemble under any profile set containing `production`.

The Phase F scheduling focused gate executes 41 tests with zero failures, errors, or skips. It covers
the initial/active/completed cycle snapshot, downstream stage priority, inventory backpressure,
membership order/duplicate/cardinality rejection, per-authority failure isolation, local overlap,
failure recovery, bounded retention retry/policy, full test-profile wiring, incomplete configuration
fail-fast behavior, schedule/page bounds, and production-profile physical absence. The resulting
source passed the complete Resource Gateway `clean verify`: 2981 tests, zero failures, zero errors,
two existing browser-environment skips, and a successfully repackaged Spring Boot executable JAR.

The Phase F readiness focused gate executes 81 tests with zero failures, errors, or skips. New
coverage proves comparison/finding operational snapshots before, during, and after completion;
fingerprint-tampered operational state rejection; scheduler attempt/success/failure-budget history;
startup grace; all independent stall/freshness/backlog violations; store-unavailable fail closure and
identity redaction; open-finding non-veto semantics; schedule-aware policy bounds; default-off and
production profile absence; and disabled/configured/healthy/degraded capability projection.
After this second increment, the complete Resource Gateway `clean verify` executes 2991 tests with
zero failures, zero errors, two existing browser-environment skips, and a successfully repackaged
Spring Boot executable JAR.

The third Phase F increment establishes the integrity precondition for source retention. Inventory
authority and cycle rows now carry versioned whole-record fingerprints over every persisted state
column, including lease/cursor/lifecycle fields. Lock reads verify them before remote I/O,
comparison, or readiness; writes use the previous revision and fingerprint as one CAS fence. The
comparison control plane reuses the same canonical material and cannot freeze expected state from a
tampered source pointer or cycle. Startup adds and backfills the two columns for legacy test/staging
rows exactly once, rejects any row that already has an invalid fingerprint, and then enforces
non-null storage. This migration is a local trust-baseline transition and is not an N/N-1 production
rollout protocol. It does not yet delete or bound source history.
The focused inventory-integrity gate executes 63 tests with zero failures, errors, or skips. The
complete Resource Gateway `clean verify` executes 2996 tests with zero failures, zero errors, two
existing browser-environment skips, and a successfully repackaged Spring Boot executable JAR.

## 15. Remaining phases and acceptance

The local expectation, durable remote cycle, frozen comparison, bounded ordered merge, terminal
semantic classification, payload-free governed finding lifecycle, and derived finding/evidence
retention portions of Phases A-E are complete. No partial comparison, finding projection, or evidence
retirement can be exported as governance evidence.

Until source cycle/comparison/classification retention is implemented,
Resource Gateway may claim **durable local expectation indexing, verified inventory cycle staging,
completed payload-free classification evidence, replay-verified governed finding evidence, and
database-fenced bounded derived-evidence retention with explicitly enabled autonomous scheduling,
aggregate readiness, and exact capability truth**. It may claim the explicitly configured test/
staging loop is operationally observable, but not that its source history is bounded or that it is a
certified production orphan-reconciliation service.
