# Stage 5 suite-stability worker guard verification

> Current-state note: the product HTTPS current-authority adapter listed as a later gap in this
> historical increment is now implemented and verified in
> [suite-stability current-authority verification](resource-gateway-execution-data-control-plane-stage5-suite-stability-current-authority-verification.md).

## 1. Increment boundary

This atomic increment connects the durable queue lease to the existing policy-free stability
execution control. It still does not claim or execute jobs by itself, expose HTTP, or advertise a
capability. It establishes the guard that a later worker must use.

The increment adds:

1. `TestSuiteStabilityJobCompletionPreparation`, an exhaustive final publication decision;
2. `TestSuiteStabilityJobExecutionCoordinator`, one process-wide daemon heartbeat scheduler;
3. one closeable execution guard per exact job/owner/epoch/expiry fence;
4. deterministic job-to-parent descriptor binding before any controlled algorithm checkpoint;
5. synchronous renewal at every cooperative checkpoint and immediately before queue success;
6. permanent local fail-closed state after cancellation, deadline, parent completion, lease loss,
   coordinator shutdown, descriptor mismatch, or store ambiguity.

## 2. Typed terminal preparation

The previous `prepareCompletion` contract returned a lease on success and threw a generic conflict
when cancellation or deadline won. A worker would have needed to infer control flow from exception
type, message, or a second query. The repository now returns exactly one closed decision:

| Decision | Lease | Meaning |
| --- | --- | --- |
| `PREPARED` | renewed `COMMITTING` fence | cancel/deadline window is irrevocably closed |
| `CANCELLED` | none | retained parent stop and queue cancellation won |
| `DEADLINE_EXCEEDED` | none | database deadline and retained parent stop won |
| `PARENT_COMPLETED` | none | verified signed parent won and queue already converged |
| `LEASE_LOST` | none | owner/epoch/expiry fence is no longer current |

Only `PREPARED` may carry a lease. Every other decision carries one bounded stable code and no
payload. Known races no longer use persistence exception text as protocol.

## 3. Guard ordering

1. `monitor(job, lease, policy)` rejects any cross-job, cross-scope, fingerprint, or lifecycle
   mismatch before scheduling heartbeat work.
2. `executionStarted(descriptor)` must equal the deterministic descriptor derived from the job.
3. Every algorithm checkpoint synchronously invokes `checkAndRenew` and replaces the local fence
   with its exact successor.
4. The daemon scheduler performs the same operation during long child attempts.
5. `prepareTerminal` consumes the typed repository decision and retains only a `COMMITTING` lease.
6. `leaseForCompletion` performs one final synchronous renewal for exact queue completion.

The first stop or ambiguous result is sticky for the guard. No later successful database call can
resurrect it. Closing a guard only cancels local scheduling; it does not mutate durable state.
Workers must complete/retry/fail explicitly, while ambiguous ownership is recovered by database
expiry and a higher epoch.

## 4. Safety boundary

Background heartbeat protects long cooperative source attempts from accidental lease expiry, and
queue fencing prevents a stale process from publishing queue success. It is not hard cancellation.
An operator that ignores interruption may continue consuming CPU or issuing already-authorized
side effects until its own runtime boundary ends. Parent stop, parent lease, and queue lease prevent
later checkpoint/publication, but true wall-clock termination still requires a separately
killable process or container worker.

## 5. Verification

Focused command:

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DatabaseTestSuiteStabilityJobRepositoryTest,\
RepositoryTestSuiteStabilityJobParentAuthorityTest,\
DatabaseTestSuiteStabilityRunRepositoryTest,TestSuiteStabilityExecutionServiceTest,\
TestSuiteStabilityJobExecutionCoordinatorTest test
```

The 64 focused tests cover the prior parent/queue invariants plus seven guard behaviors and two
typed-preparation repository behaviors. They prove exact descriptor binding, latest-fence renewal,
typed cancellation/deadline propagation, irrevocable terminal preparation, final committing
renewal, sticky store ambiguity, real periodic heartbeat during blocked child work, typed parent
winner convergence, and stale-fence rejection.

## 6. Required next step

The bounded worker core now uses this guard and is verified in
[Stage 5 suite-stability worker core verification](resource-gateway-execution-data-control-plane-stage5-suite-stability-worker-core-verification.md).
The remaining work is a real current-authority adapter, background scheduling, configuration,
telemetry, public API, Schema, test-kit, and capability truth.
