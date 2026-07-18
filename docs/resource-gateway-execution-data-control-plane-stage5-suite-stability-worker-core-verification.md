# Stage 5 suite-stability worker core verification

## 1. Increment boundary

This atomic increment adds the bounded worker core that claims and fully handles at most one
durable stability job on the caller thread. It is deliberately not scheduled, configured as a
Spring bean, exposed through HTTP, or advertised as a capability yet.

The increment adds:

1. `TestSuiteStabilityJobAuthorizer`, a mandatory current-authority revalidation boundary;
2. `TestSuiteStabilityJobWorker`, the single-poll execution and failure-classification owner;
3. `TestSuiteStabilityJobWorkResult`, a payload-free closed scheduler/telemetry result;
4. a fair local semaphore acquired before durable claim;
5. execution exclusively through the previously verified heartbeat guard;
6. retry/fail/complete mutations only after a fresh exact lease decision.

## 2. Execution ordering

One `processNext(environment)` call follows this order:

1. validate the exact non-production environment;
2. non-blockingly acquire a local execution slot;
3. claim one database-selected job only after the slot is owned;
4. start the queue heartbeat guard;
5. revalidate current policy/delegation authority from the credential-free durable principal;
6. execute the existing stability algorithm through the guard;
7. publish queue success only from the algorithm's signed parent response and exact
   `COMMITTING` lease;
8. release the local slot in every outcome.

The durable principal is submission evidence, not perpetual authorization. The authorizer must
return `AUTHORIZED`, `REVOKED`, or `UNAVAILABLE`; null, exceptions, and unavailable policy are
treated as ambiguity. Revocation fails parent-first before engine start. Ambiguity retries under
the exact renewed fence and never starts the engine.

## 3. Failure classification

| Failure point | Worker action |
| --- | --- |
| no local slot | return `LOCAL_CAPACITY`; do not claim |
| claim store unavailable | return `QUEUE_UNAVAILABLE`; invent no job id |
| authority revoked | exact parent-first `fail` |
| authority unavailable | exact bounded `retry` |
| cooperative cancel/deadline/parent winner | report typed result; do not mutate again |
| retryable execution problem before publication | exact bounded `retry` |
| deterministic execution problem before publication | exact parent-first `fail` |
| any failure after `COMMITTING` | retry the irrevocable publication lane only |
| lease loss or control ambiguity | make no queue mutation; database expiry owns recovery |

If retry exhaustion or a competing signed parent changes the requested transition, the worker
reports the repository's retained status rather than its original local intent. In particular, a
publication retry reports the retained publication diagnostic, not the earlier business error.

## 4. Local capacity invariant

The local semaphore is intentionally acquired before `claimNext`. Claiming first and waiting for a
thread would consume fleet-wide running capacity without heartbeat or execution progress. The
semaphore is fair, bounded to 1..1024, and always released in `finally`. It is a process-local
admission bound; database policy remains the cross-replica capacity authority.

## 5. Verification

Focused command:

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DatabaseTestSuiteStabilityJobRepositoryTest,\
RepositoryTestSuiteStabilityJobParentAuthorityTest,\
DatabaseTestSuiteStabilityRunRepositoryTest,TestSuiteStabilityExecutionServiceTest,\
TestSuiteStabilityJobExecutionCoordinatorTest,TestSuiteStabilityJobWorkerTest test
```

The 73 focused tests pass with zero failures, errors, or skips. The nine worker tests prove guarded
success publication, claim-after-slot ordering under real thread contention, pre-engine authority
revocation and ambiguity, cancellation without fall-through mutation, retryable and deterministic
failure classification, irrevocable `COMMITTING` recovery, and payload-free claim ambiguity.

## 6. Remaining boundary

The worker is not yet runnable as a product feature. The next increment must provide a real
current-authority adapter, configuration with startup validation, bounded scheduler lifecycle,
environment enablement, graceful shutdown/drain semantics, fixed-cardinality metrics, readiness
and backlog SLOs, retention scheduling, and end-to-end persistence tests. HTTP submit/query/cancel,
Schema, test-kit, and capability truth remain false until those pieces are integrated.

Heartbeat can terminalize the durable job while an authorizer or operator call is stuck, but the
local thread and slot remain occupied until that call returns. Physical hard timeout still requires
a separately killable process or container execution boundary.
