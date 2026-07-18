# Stage 5 suite-stability parent-first terminal verification

## 1. Increment boundary

This atomic increment closes the cross-transaction crash window between a durable stability job
and its resumable parent execution. It does not yet start a worker or expose the queue through HTTP.
It makes the queue repository the only authority allowed to publish cancellation, expiry,
worker-failure, or success terminal states.

The increment adds:

1. one shared deterministic parent identity derivation for synchronous and queued execution;
2. `TestSuiteStabilityJobParentAuthority`, a closed stop-or-completed decision contract;
3. a repository-backed implementation that commits parent stop before queue terminalization;
4. signed-parent winner verification that binds scope, request, classification, evidence, and
   detached signature;
5. mandatory parent completion proof before every `COMMITTING -> SUCCEEDED` transition;
6. queue convergence to `SUCCEEDED` when valid signed parent evidence won the race.

## 2. Root-cause invariant

Writing the queue terminal first and the parent stop second is unsafe. A process crash between
those commits leaves a terminal queue job while the synchronous endpoint can reclaim parent
progress and publish evidence. A compensating sweeper only shortens that incorrect interval and
cannot prove that no stale owner escaped.

The durable ordering is therefore:

1. lock and integrity-check the exact queue predecessor;
2. invoke the parent authority with the exact job identity;
3. commit an idempotent parent stop in an independent transaction, or prove a valid signed parent
   already completed;
4. commit the outer queue transition to its stop state, or to `SUCCEEDED` for the signed winner.

An outer queue rollback can leave a conservative parent stop, which a successor replays with the
stable server actor. It cannot leave the dangerous inverse: terminal queue state above resumable
parent progress.

The success direction is equally strict. A worker's run id and evidence fingerprint are claims,
not proof. Before queue success, the authority loads the deterministic parent record, rebinds its
scope, request, classification and source closure, recomputes the canonical evidence fingerprint,
and verifies the detached signature. Missing, mismatched, corrupted, or unverifiable parent
material leaves the queue in `COMMITTING` under its exact recoverable lease.

## 3. Race and failure matrix

| Event | Parent result | Queue result |
| --- | --- | --- |
| cancellation/deadline/failure wins | retained payload-free stop | requested stop state |
| outer queue transaction rolls back | stop remains and is replayable | previous non-terminal state |
| signed parent won first | verified immutable terminal | `SUCCEEDED` with exact references |
| worker reports parent references before parent commit | no terminal proof | remains `COMMITTING` |
| parent proof store is unavailable | ambiguous, fail closed | outer queue transaction rolls back |
| parent identity contradicts job | fail closed | no queue mutation |
| canonical evidence hash is wrong | fail closed | no queue mutation |
| detached signature is invalid/unavailable | fail closed | no queue mutation |
| repeated running cancellation checkpoint | exact stop replay | `CANCELLED` |

The `stability-job-control` actor is intentionally stable. It is a replay identity, not a worker
identity, so a successor can finish the outer transition after a crash without weakening strict
stop replay.

## 4. Verification

Focused command:

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DatabaseTestSuiteStabilityJobRepositoryTest,\
RepositoryTestSuiteStabilityJobParentAuthorityTest,\
DatabaseTestSuiteStabilityRunRepositoryTest,TestSuiteStabilityExecutionServiceTest test
```

The 55 focused tests pass with zero failures, errors, or skips. In addition to queue lifecycle and
parent lease coverage, they prove:

- queued cancellation cannot commit when the parent authority fails;
- retry exhaustion cannot commit failure when parent stop fails;
- a committed parent stop remains idempotent and blocks later synchronous claim;
- a cryptographically verified signed terminal wins a later stop and yields queue success;
- a record with a corrupted detached signature cannot masquerade as a completed winner;
- queue success rolls back and retains its live `COMMITTING` lease when proof is unavailable;
- a contradictory authority response cannot be committed as success;
- one real H2 queue cannot complete before its real H2 parent record exists, then succeeds after
  that exact signed record is retained;
- synchronous and queued paths derive the same deterministic parent identity.

## 5. Remaining boundary

This increment removes one crash-consistency defect; it does not provide background liveness. The
next step must wire a guarded worker that binds claimed job to execution descriptor, heartbeats the
exact owner/epoch, maps cooperative checkpoints to queue decisions, enters `COMMITTING` before
publication, and classifies retryable infrastructure failures without bypassing parent-first
terminalization. Capability truth remains false until that path, public Schema, authorization
revalidation, telemetry, retention, and test-kit consumption are complete.
