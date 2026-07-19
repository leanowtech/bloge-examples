# Stage 5 compact-observation floor retirement design

**Implementation status (2026-07-20): internal database-authoritative signed floor-retirement core,
strict public lifecycle Schema, default-disabled authorized pagination, independent lifecycle
verification, and mandatory external-first archive-receipt admission are implemented and verified.
Receipt-aware lifecycle v2 now provides public proof export and caller-policy verification. The
the strict multi-authority HTTPS adapter is implemented for test/staging, while certified
production providers, historical trust publication, orphan reconciliation, legal hold/erasure,
backup purge, disaster-recovery continuity, and witnessed non-equivocation remain
unavailable.**

## 1. Strongest judgment

Compact observations cannot become durable cross-retention evidence merely by deleting old rows on
a schedule. The hard problem is proving all of the following at the same time:

1. a versioned policy, rather than an arbitrary worker, selected the retired prefix;
2. the prefix was complete and still belonged to the exact floor/head snapshot that was approved;
3. a verifier can reconnect the surviving active chain to the retired predecessor;
4. a crash, concurrent append, retry, replica race, or partial database failure cannot create two
   histories or delete material without its archive and signed retirement record;
5. lifecycle claims do not outrun the actual archive, legal-hold, erasure, backup, and external
   witness controls.

The retirement core therefore treats floor movement as a signed state transition rather than a
retention-scheduler side effect. A later increment now exposes that transition through a separate,
default-disabled lifecycle proof endpoint without weakening the range protocol's cursor rules.

## 2. Trust and ownership boundary

| Concern | Current authority | Current guarantee | Deliberately outside this increment |
| --- | --- | --- | --- |
| Eligible prefix | Database repository under exact-suite lock | append-time cutoff, minimum suffix, maximum batch, exact policy fingerprint | enterprise policy registry and approval workflow |
| Retirement intent | Retirement-specific Ed25519 signer | exact floor, head, archive, policy, generation, and database time are signed | external M-of-N authorization |
| Atomic mutation | Local relational transaction | archive + retirement + floor/head CAS + active deletion commit together | cross-store distributed transaction |
| Replay | Deterministic retirement id and exact stored record | identical retry returns the historical successor floor | externally durable consumer checkpoint |
| Archive durability | External-first receipt protocol, strict test/staging HTTPS adapter, plus same-database bounded segment | no supported local commit can delete active rows without exact pre-verified compliance-retention receipts | certified production providers, independent accounts/failure domains, and geographic durability |
| History consistency | Per-scope sequence, predecessor chain, signed retirement generations | local non-forking transition under one database authority | external witnessed non-equivocation and gossip |

`TestSuiteStabilityObservationFloorRetirementService` is the trusted orchestration boundary. It
performs prepare, sign, immediate retirement verification, external immutable archive admission,
receipt verification, canonical envelope construction, and commit. The repository commit API has
no receipt-free overload. `DatabaseTestSuiteStabilityRunRepository` revalidates canonical identities,
exact receipt-to-retirement binding, and database state but deliberately performs neither remote I/O
nor external key resolution under its transaction lock. Direct repository commit remains an internal
trusted call, not an untrusted adapter surface.

## 3. Durable model

### 3.1 Ledger floor

`bloge.testSuiteStabilityObservationLedgerFloor.v1` is a separately fingerprinted CAS value:

- generation `0` is the rollout floor at sequence `1` with blank predecessor and retirement refs;
- generation `n > 0` identifies the first active sequence after retirement `n`;
- it retains the last archived observation id and entry fingerprint needed to verify the surviving
  first entry;
- it binds the exact suite, active floor coordinate, coverage start, latest signed retirement, and
  database update time.

The mutable head continues to identify the latest active entry. Its `coverageFrom` moves with the
floor while its latest coordinate remains pinned. A valid ledger must have both a floor and a head,
and both must resolve to active coordinates in the same exact-suite scope.

### 3.2 Archive segment

`bloge.testSuiteStabilityObservationArchiveSegment.v1` contains:

- at most 100 contiguous retired entries;
- the predecessor immediately before that prefix;
- a duplicate of the immediate surviving successor;
- deterministic segment id, canonical segment fingerprint, generation, and database archive time.

Duplicating the successor is intentional. It lets the archive prove that its last retired entry and
the surviving floor were adjacent without relying on mutable active-table state. The segment remains
payload-free because compact observations contain fingerprints and bounded outcome metadata rather
than business input/output values.

### 3.3 Signed retirement

`bloge.testSuiteStabilityObservationFloorRetirementEvidence.v1` binds the exact previous floor,
pinned head, complete archive segment, exclusive cutoff, minimum retained suffix, maximum retirement
batch, immutable retention-policy fingerprint, closed reason, generation, and database retirement
time. The independent attestation signs the evidence fingerprint plus archive, floor, and head
fingerprints. The complete retirement record has its own whole-record fingerprint.

Deterministic archive and retirement ids make an exact retry distinguishable from identity reuse
with different material. A unique `(scopeFingerprint, retirementGeneration)` constraint prevents
two accepted histories for one local generation.

## 4. Required invariants

The implementation fails closed unless all invariants hold:

1. `successorGeneration = previousFloorGeneration + 1`.
2. The archive starts exactly at `previousFloor.floorSequence`.
3. Its first entry equals the previous floor observation and entry fingerprints.
4. Every retired entry has the expected sequence, scope, predecessor, canonical fingerprint, and
   append time before the exclusive cutoff.
5. The duplicated successor is exactly one sequence after the retired prefix and points to its last
   observation.
6. At least `minimumRetainedEntries` remain at or below the pinned head.
7. The current floor and head still equal the signed plan when commit acquires the scope lock.
8. Every active retired row and the successor still equal the signed archive material.
9. The exact external receipt set, local archive, retirement, successor floor, adjusted head, and
   active-prefix deletion share one local transaction.
10. Floor and head updates use exact predecessor fingerprints and coordinates, not last-write-wins.
11. Indexed projections and stored JSON must agree on every security-relevant coordinate.
12. A scope with active rows, floor, archive, or retirement material but no committed head is
    corruption, not an empty ledger; direct floor reads also require a committed head.
13. Every receipt binds the exact signed retirement, nested archive, policy, immutable retention
    deadline, external object id, unique authority, and unique failure domain.
14. An external outage, invalid receipt, insufficient copy threshold, or authenticated immutable
    conflict returns before any local retirement mutation.

## 5. Prepare, sign, commit

### 5.1 Prepare under lock

The repository acquires the exact-suite database lock, reads the current verified floor and head,
and selects only the contiguous eligible prefix. Selection is bounded by the caller's maximum batch,
append-time cutoff, and minimum retained suffix. It also reads the immediate successor, obtains
database time, and derives the content-addressed archive and retirement evidence.

Preparation does not mutate the ledger. The lock is released before signing so a remote or managed
signer cannot hold a database transaction open.

### 5.2 Sign outside the transaction

The attestation service independently rebuilds deterministic ids, all nested fingerprints, floor
and head integrity, and the complete archive chain. It signs in a retirement-specific domain and
immediately verifies the detached signature. Signer outage or invalid material returns a bounded
failure without any persistence mutation.

An append may legitimately occur while signing. That makes the pinned head stale and causes commit
to reject the plan. The caller must prepare a new intent; silently widening the signed deletion set
is forbidden.

### 5.3 Archive externally before the local lock

After retirement verification, the service sends the complete signed retirement and immutable
retention deadline to the configured external authority. The challenge-bound request embeds the
complete retirement rather than a caller-projected reference. Each authority signs exact object,
retirement, archive, policy, retention, durability, write-once, and early-delete-denial facts. The
service verifies the multi-copy set before invoking the repository. An authority outage, bad
signature/topology, or authenticated immutable conflict cannot reach local mutation.

Remote I/O happens before the local transaction. A subsequent append can make the signed plan stale;
the safe outcome is an externally durable orphan plus zero local mutation. Orphan reconciliation is
a later bounded workflow and has no authority to shorten the signed retention deadline.

### 5.4 Commit under the same scope lock

One transaction performs this ordered protocol:

1. acquire the exact-suite lock;
2. return the deterministic historical successor for an exact committed replay;
3. re-read and compare the complete current floor and head with the signed pins;
4. compare every retired active row and the surviving successor with the signed archive;
5. reject retirement time ahead of current database time;
6. insert the exact external archive receipt set;
7. insert the immutable local archive segment;
8. insert the immutable signed retirement record;
9. CAS the floor to the derived successor generation;
10. CAS the head coverage boundary while preserving its pinned latest coordinate;
11. delete exactly the archived active prefix and verify the affected-row count;
12. commit all changes, or roll back all changes on any failure.

The insert-before-delete order ensures no active row can disappear unless the same transaction also
contains its archive and signed retirement. The final row-count check catches unexpected gaps even
after all earlier comparisons passed.

## 6. Concurrency and replay semantics

Two replicas preparing independently may produce different database-time identities. Only a plan
whose exact floor and head still match can commit. Two replicas committing the same signed record
serialize on the scope lock; the first mutates the ledger and the second returns the same historical
successor floor. A different record for an already occupied generation is rejected and its earlier
archive insert is rolled back with the transaction.

An append between prepare and commit changes the head pin. The retirement is rejected even when the
selected old prefix itself did not change. This conservative conflict prevents a signed policy
decision from being applied to a ledger snapshot it did not authorize.

## 7. Rollout and recovery

The first terminal append inserts sequence one, rollout floor, and head in the terminal publication
transaction. Existing pre-floor ledgers are migrated at repository initialization:

- each discovered scope is locked in its own transaction;
- the floor absence is rechecked after lock acquisition so concurrent replicas converge;
- sequence one and `coverageFrom` must prove an unambiguous generation-zero floor;
- a missing first entry or mismatched coverage aborts startup instead of inventing history.

The migration is deliberately conservative. A legacy ledger whose rollout coordinate cannot be
proved needs operator repair or restore from a known-good backup.

## 8. Read behavior after retirement

Active reads reject a cursor before `floorSequence - 1`. The range predecessor is taken from the
durable floor's archived predecessor coordinate, so the first retained entry remains verifiable.
Current reads validate floor-to-active-row and head-to-latest-row closure before returning data.

The existing compact-range request still requires first-page `afterSequence=0`; it does not silently
accept an arbitrary moved floor. The separate
`stability-observation-ledger-lifecycle-pages` preview now discovers and proves the ordered chain
from generation zero to one snapshot-pinned current floor. Consumers independently verify every
compact observation, archive, retirement, derived successor floor, page signature, and cross-page
checkpoint. Only after the terminal lifecycle page verifies may its
`currentFloor.floorSequence - 1` and current head fingerprint seed the range request. Detailed wire,
pagination, and verifier invariants are in
[Stage 5 observation-floor lifecycle protocol](resource-gateway-execution-data-control-plane-stage5-observation-floor-lifecycle-protocol-design.md).

Consumers that require proof of the external-first deletion precondition use the dedicated
`stability-observation-ledger-lifecycle-archive-pages` v2 preview. It preserves the same request,
cursor, and snapshot pins while exporting an exact receipt set parallel to every retirement and an
outer signature over their joint closure. The independent verifier accepts archive authorities,
failure domains, retention revisions, horizons, and keys only from caller-owned policy. See the
[receipt-aware lifecycle v2 design](resource-gateway-execution-data-control-plane-stage5-observation-lifecycle-v2-external-proof-design.md).

This closes portable proof of the recorded external acknowledgement, not physical historical
permanence. Both lifecycle routes are default-disabled, absent in production, and the advertised
cross-retention capability remains false.

## 9. Failure matrix

| Failure | Detection | Required outcome |
| --- | --- | --- |
| Signer unavailable | seal result | no archive, retirement, floor, head, or row mutation |
| External archive unavailable or conflicting | bounded authority result | no local receipt, archive, retirement, floor, head, or row mutation |
| Invalid, rebound, duplicate-domain, or insufficient receipts | authority and canonical closure verification | reject before local transaction |
| Future cutoff, even with no eligible prefix | database-time validation before ledger lookup | reject invalid policy request |
| Evidence or nested fingerprint forged | pre-sign and pre-commit canonical checks | reject before deletion |
| Append races with signing | head pin mismatch | whole commit rolls back |
| Retired row or successor missing/changed | exact active-row comparison | whole commit rolls back |
| Duplicate generation | database unique constraint | archive insert and all later changes roll back |
| Floor/head CAS miss | affected-row count | whole commit rolls back |
| Partial prefix delete | affected-row count | archive, retirement, floor, and head roll back |
| Archive indexed column tampered | indexed-to-JSON read validation | signed retirement becomes unreadable |
| Receipt indexed column or JSON tampered | canonical and indexed-to-JSON read validation | external acknowledgement becomes unreadable |
| Floor or retirement material remains without head | bidirectional consistency read | fail closed as corruption |
| Two startup replicas backfill | scope lock plus in-lock absence recheck | exactly one generation-zero floor |

## 10. Verification evidence

The external-first floor-retirement focused gate executes 52 tests with zero failures, errors, or
skips:

- 4 external archive protocol tests cover canonical closure, malformed challenge, shortened
  retention, insufficient/duplicate/unsorted topology, and receipt-material rebinding;
- 48 repository/service tests include the pre-existing lease/terminal/ledger contract plus signed archive
  continuity, exact replay across generations, append conflict, missing-row rollback, projection
  tamper, signer/archive outage, invalid receipt verification, authenticated immutable conflict,
  legacy backfill, concurrent backfill, bidirectional orphan-floor and orphan-lifecycle rejection,
  future-cutoff rejection, cross-replica commit convergence, exact-receipt replay, and
  duplicate-generation rollback.

Lifecycle-specific repository, service, signer, controller, profile, Schema, independent verifier,
HTTP, tamper, and two-page checkpoint tests are maintained with the lifecycle protocol design. Final
whole-project counts are recorded in the parent industrial evolution plan after each complete gate.

## 11. Security and privacy properties

- No fixture value, request/response payload, credential, actor token, or diagnostic body is copied
  into the floor, archive, or retirement record.
- The policy is bound by fingerprint; this core does not claim that the policy was approved by the
  right enterprise authority.
- Local database signatures make unauthorized rewriting detectable to a verifier with a trusted
  key. They do not prove the producer showed every consumer the same history.
- Deleting the active copy is not erasure: the local archive, database log, replicas, snapshots, and
  backups still require explicit lifecycle policy.

## 12. Remaining productization gates

The public lifecycle v1/v2 contracts and independent verifiers close local discovery and receipt
proof portability. The
advertised capability must remain false until the remaining mandatory gates are closed:

1. certify provider/account/region independence and historical trust publication for the
   implemented strict HTTPS multi-authority adapter, then add idempotent orphan reconciliation;
2. define legal hold precedence, hold release authorization, erasure proof, backup expiry, and purge
   evidence across replicas and disaster-recovery copies;
3. anchor each retirement generation to externally witnessed non-equivocation checkpoints and prove
   rollback/fork/split-view resistance after restore;
4. add a database-leased bounded scheduler, backlog/SLO telemetry, readiness, and capability truth;
5. run restart, failover, backup/restore, key lifecycle, cross-version, and multi-region fault tests;
6. add operational repair procedures for corrupted floor/head/archive state without fabricating a
   signed history.

## 13. Quality judgment

The internal deletion transition is now structurally incapable of committing without an exact
external acknowledgement object: it has explicit authority material, bounded work, exact snapshot
pins, deterministic replay, cross-replica serialization, atomic receipt-and-archive-before-delete,
CAS state movement, conservative migration, and executable corruption proofs. The remaining gap is
physical deployment, lifecycle operations, and external non-equivocation, not proof portability or
write-side protocol shape. Floor discovery, local lifecycle verification, and exact recorded
receipt verification are independently verifiable through separate v1/v2 routes, but no production
WORM adapter is production-certified and wired. Resource Gateway may describe this as an
external-first receipt-gated local
retirement core with portable acknowledgement proof, not as industrial cross-retention continuity.
