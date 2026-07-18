# Stage 5 suite-stability durable queue core verification

## 1. Increment boundary

This atomic increment establishes the database-authoritative control-plane core for asynchronous
suite-stability parent jobs. It is deliberately **not yet exposed through HTTP, capability
discovery, or a background worker**. Product availability remains false until submission,
authorization, cooperative execution control, terminal evidence publication, Schema, and client
consumption are wired and verified end to end.

The new core owns:

1. scoped idempotent submission and database-time capacity admission;
2. one active queue policy fingerprint per environment;
3. global and per-tenant queued/running limits;
4. database-serialized tenant round-robin selection;
5. immutable priority plus bounded wait-time aging inside the selected tenant;
6. exact worker owner/epoch/expiry fencing;
7. cooperative cancellation and deadline transitions;
8. deterministic bounded retry backoff, retry exhaustion, and irrevocable publication recovery;
9. payload-free closed-status queue observations;
10. whole-row integrity verification and bounded terminal retention deletion;
11. payload-free parent stop tombstones that atomically consume resumable progress and leases;
12. parent-first queue termination that cannot leave a terminal queue row above resumable parent
    progress after an outer transaction failure.

## 2. Root-cause decision

Wrapping the synchronous stability endpoint in an executor would only move the coupling. Process
loss would still discard accepted work, local queues could not enforce fleet-wide capacity, and
each replica could select a different tenant. The durable queue therefore linearizes admission,
policy convergence, stale-owner recovery, fairness-cursor movement, and claim in the isolated
test-runtime database before any background thread is introduced.

Priority is intentionally subordinate to tenant fairness. The scheduler first selects the next
eligible tenant by a persisted lexicographic round-robin cursor, then selects that tenant's job by
aged effective priority, creation time, and job id. A high-priority tenant cannot consume another
tenant's turn. Aging raises a waiting job at most to `HIGH`; it never mutates the submitted audit
priority.

## 3. State machine

| From | Command or condition | To | Durable effect |
| --- | --- | --- | --- |
| absent | exact submission within capacity | `QUEUED` | immutable request/principal snapshot and deadline |
| `QUEUED` | fair claim | `RUNNING` | owner, incremented epoch, database expiry, cursor advance |
| `RUNNING` | exact heartbeat | `RUNNING` | renewed database expiry |
| `RUNNING` | retryable infrastructure failure | `QUEUED` | incremented retry count and bounded backoff |
| `RUNNING` | retries exhausted | `FAILED` | cleared lease and retained stable failure code |
| `QUEUED` | cancellation | `CANCELLED` | exact cancellation command retained |
| `RUNNING` | cancellation | `CANCEL_REQUESTED` | worker must stop at the next control checkpoint |
| `CANCEL_REQUESTED` | heartbeat, retry, or stale-owner recovery | `CANCELLED` | no retry path can resurrect the job |
| `QUEUED/RUNNING` | database deadline reached | `EXPIRED` | cleared lease and stable deadline code |
| `RUNNING` | final cancel/deadline check | `COMMITTING` | publication intent is irrevocably linearized |
| `COMMITTING` | cancellation or later deadline | `COMMITTING` | command is explicitly too late |
| `COMMITTING` | worker retry or lease expiry | `COMMITTING` | owner changes without reopening cancellation |
| `COMMITTING` | signed parent publication | `SUCCEEDED` | terminal run and evidence fingerprints retained |
| malformed retained row | integrity verification | fail closed | no claim or projection is returned |

`COMMITTING` is deliberately non-terminal but irrevocable. A crashed owner never demotes it to
ordinary `QUEUED`: an eligible successor claims the same state under a higher epoch and replays
the idempotent parent publication. Retry exhaustion cannot turn it into `FAILED`, because the
signed parent may already exist. Terminal states are `SUCCEEDED`, `FAILED`, `CANCELLED`,
`EXPIRED`, and `QUARANTINED`. The last state is reserved in the closed vocabulary; automatic
poison-row quarantine is not implemented by this atomic step and therefore is not claimed.

The queue state alone cannot prevent an older synchronous entry point from reclaiming durable
parent progress. The parent repository therefore has a second terminal authority:
`TestSuiteStabilityExecutionStop`. A cancellation, deadline, or worker failure first commits an
integrity-fingerprinted, payload-free parent tombstone in an independent transaction and consumes
the exact parent progress plus lease. Only then may the outer queue transaction commit
`CANCELLED`, `EXPIRED`, or `FAILED`. Future claims return `STOPPED`; stale owners cannot checkpoint
or publish. Conversely, once cryptographically verified signed terminal evidence exists, it wins
and the queue converges to `SUCCEEDED`. Stop and signed evidence therefore have one serialized,
fail-closed winner. The ordinary completion path uses the same authority: caller-provided terminal
references are not accepted until the deterministic parent record, canonical evidence fingerprint,
source closure, and detached signature have been independently verified.

## 4. Cross-replica invariants

- An environment lock serializes policy, admission, reconciliation, cursor, and claim mutations.
- A changed policy can replace the active generation only after no non-terminal job remains.
- Running concurrency uses database time and live ownership; admission additionally counts every
  irrevocable `COMMITTING` job, including an unowned recovery candidate.
- A claim advances the fairness cursor and exact job lease in one local transaction.
- Heartbeat, retry, complete, and cancellation update the whole-row fingerprint by exact predecessor
  compare-and-set.
- A stale owner cannot renew, retry, complete, or undo a committed cancellation.
- `COMMITTING` recovery preserves its state across explicit retry and expired-owner takeover; it
  remains capacity-accounted and cannot be failed or cancelled.
- Parent stop and signed terminal publication share the same scoped lock; stop creation, progress
  deletion, and lease deletion are one transaction.
- Queue stop transitions are parent-first: outer rollback may retain a conservative idempotent
  parent stop, but can never retain a terminal queue row above a resumable parent.
- A completed-parent winner is accepted only after exact scope/request/classification binding,
  canonical evidence fingerprint recomputation, source-closure validation, and detached-signature
  verification. Hash-consistent database tampering does not become queue success.
- Every `COMMITTING -> SUCCEEDED` transition requires that same completed-parent proof. Missing or
  unavailable proof rolls back the queue transition and retains its recoverable committing lease.
- Stop replay must match run id, request fingerprint, classification, reason, failure code, actor,
  and retention. Its canonical fingerprint is recomputed on every read.
- Stored request and principal JSON are decoded only after scope lookup and are re-fingerprinted on
  every read.
- The principal snapshot contains no bearer token or transport header. It is not yet a replacement
  for a dynamic delegated-authority revalidation adapter.

## 5. Verification

Focused command:

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DatabaseTestSuiteStabilityJobRepositoryTest,\
RepositoryTestSuiteStabilityJobParentAuthorityTest,\
DatabaseTestSuiteStabilityRunRepositoryTest,TestSuiteStabilityExecutionServiceTest test
```

The 55 focused tests cover scoped submission replay, priority/deadline/principal idempotency conflict,
tenant/global capacity, tenant rotation, tenant-local priority, cross-replica running limits,
queued and running cancellation, cancellation-versus-retry ordering, retry exhaustion, policy
drift and drain-time advancement, fixed-cardinality observation, retained-row integrity failure,
non-premature retention deletion, the `COMMITTING` cancellation linearization point and recovery
lane, stop-before-claim, stop-after-checkpoint, stale-owner fencing, strict stop replay, late-stop
rejection after signed evidence, corrupted-stop fail-closed behavior, parent-first rollback,
cryptographically verified parent completion, and corrupted-signature rejection.
They also cover missing completion proof, contradictory authority output, completion-proof rollback,
and a real queue-plus-parent persistence path that succeeds only after exact signed parent commit.
The 17 affected service tests additionally prove that a retained stop maps to a stable payload-free
conflict and cannot be bypassed through the synchronous execution entry point.

## 6. Required next step

This core must not be advertised as a usable asynchronous runtime until the next atomic steps add:

1. authenticated submit/query/cancel protocol and strict JSON Schema;
2. a real current-authority adapter and startup-validated wiring for the now implemented worker;
3. bounded-cardinality Micrometer projection and SLO/readiness thresholds;
4. retention scheduler, test-kit typed client, capability truth, and full build evidence;
5. poison-row quarantine, non-H2 certification, contention/soak/chaos proof, and hard process
   cancellation as explicit later work.

The completed guard step is verified in
[Stage 5 suite-stability worker guard verification](resource-gateway-execution-data-control-plane-stage5-suite-stability-worker-guard-verification.md).
The bounded worker core is verified in
[Stage 5 suite-stability worker core verification](resource-gateway-execution-data-control-plane-stage5-suite-stability-worker-core-verification.md).
