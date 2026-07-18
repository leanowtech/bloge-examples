# Stage 5 suite-stability worker scheduler verification

## 1. Increment boundary

This atomic increment adds the bounded process-local scheduler lifecycle around the verified
single-poll worker. It does not yet register the scheduler in Spring configuration or claim that a
current-authority provider exists.

The scheduler provides:

1. 1..1024 fixed-delay polling lanes;
2. exact `test` and/or `staging` queue enablement only;
3. at least one lane per enabled environment as a startup invariant;
4. lane staggering to avoid a simultaneous empty-queue polling burst;
5. exception isolation so one unexpected poll failure cannot kill a lane;
6. bounded graceful drain followed by best-effort interruption;
7. observable local active-poll and closed state without queue or tenant labels.

## 2. Root-cause decisions

Each lane calls the synchronous worker and waits for it to finish before its fixed delay begins.
This bounds each lane to one active claim and avoids building an unbounded executor handoff queue.
The worker's semaphore remains the final local claim-before-capacity fence.

When multiple environments are enabled, fewer lanes than environments would create deterministic
starvation: the round-robin assignment would never schedule at least one queue. Construction now
fails unless `maximumPollers >= enabledEnvironments`. This is a startup correctness condition, not
a warning or runtime SLO.

## 3. Shutdown semantics

`close()` is synchronized and idempotent. It marks the scheduler closed, cancels future periodic
invocations without interrupting current work, shuts down the executor, and waits up to the
configured drain timeout. Remaining threads are then interrupted and given one short final wait.

Interruption is not presented as hard cancellation. If an operator or external authority ignores
it, queue/parent lease expiry and fencing prevent stale checkpoint/publication, while the process
may still require container-level termination.

## 4. Verification

Focused command:

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DatabaseTestSuiteStabilityJobRepositoryTest,\
RepositoryTestSuiteStabilityJobParentAuthorityTest,\
DatabaseTestSuiteStabilityRunRepositoryTest,TestSuiteStabilityExecutionServiceTest,\
TestSuiteStabilityJobExecutionCoordinatorTest,TestSuiteStabilityJobWorkerTest,\
TestSuiteStabilityJobSchedulerTest test
```

The 77 focused tests pass with zero failures, errors, or skips. Scheduler tests prove bounded lane
polling and post-close quiescence, recovery after one unexpected worker exception, graceful
in-flight drain, rejection of production/zero-lane coordinates, and fail-fast prevention of an
unserved enabled environment.

## 5. Required next step

The composition root must now create the parent authority, queue repository, execution coordinator,
policy, worker, and scheduler from startup-validated configuration. Scheduler enablement must fail
when no unique current-authority authorizer is available. Metrics, readiness/backlog SLOs, retention,
HTTP, Schema, test-kit, and capability truth remain separate required increments.
