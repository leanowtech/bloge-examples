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
8. deterministic bounded retry backoff and retry exhaustion;
9. payload-free closed-status queue observations;
10. whole-row integrity verification and bounded terminal retention deletion.

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
| active | database deadline reached | `EXPIRED` | cleared lease and stable deadline code |
| `RUNNING` | signed parent publication | `SUCCEEDED` | terminal run and evidence fingerprints retained |
| malformed retained row | integrity verification | fail closed | no claim or projection is returned |

Terminal states are `SUCCEEDED`, `FAILED`, `CANCELLED`, `EXPIRED`, and `QUARANTINED`. The last state
is reserved in the closed vocabulary; automatic poison-row quarantine is not implemented by this
atomic step and therefore is not claimed.

## 4. Cross-replica invariants

- An environment lock serializes policy, admission, reconciliation, cursor, and claim mutations.
- A changed policy can replace the active generation only after no non-terminal job remains.
- Live global and tenant counts use database time and exclude expired ownership.
- A claim advances the fairness cursor and exact job lease in one local transaction.
- Heartbeat, retry, complete, and cancellation update the whole-row fingerprint by exact predecessor
  compare-and-set.
- A stale owner cannot renew, retry, complete, or undo a committed cancellation.
- Stored request and principal JSON are decoded only after scope lookup and are re-fingerprinted on
  every read.
- The principal snapshot contains no bearer token or transport header. It is not yet a replacement
  for a dynamic delegated-authority revalidation adapter.

## 5. Verification

Focused command:

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DatabaseTestSuiteStabilityJobRepositoryTest test
```

The focused suite covers scoped submission replay, priority/deadline/principal idempotency conflict,
tenant/global capacity, tenant rotation, tenant-local priority, cross-replica running limits,
queued and running cancellation, cancellation-versus-retry ordering, retry exhaustion, policy
drift and drain-time advancement, fixed-cardinality observation, retained-row integrity failure,
and non-premature retention deletion.

## 6. Required next step

This core must not be advertised as a usable asynchronous runtime until the next atomic steps add:

1. authenticated submit/query/cancel protocol and strict JSON Schema;
2. a worker that claims only when it owns a local execution slot and heartbeats while running;
3. a control checkpoint in the parent runner before each attempt and before terminal publication;
4. atomic or fenced convergence between queue terminal state and parent progress/evidence state;
5. current authorization revalidation for delegated principals;
6. bounded-cardinality Micrometer projection and SLO/readiness thresholds;
7. retention scheduler, test-kit typed client, capability truth, and full build evidence;
8. poison-row quarantine, non-H2 certification, contention/soak/chaos proof, and hard process
   cancellation as explicit later work.
