# Stage 5 suite-stability controlled execution verification

## 1. Increment boundary

This atomic increment makes the existing synchronous suite-stability algorithm externally
controllable without duplicating it in a worker. It does **not** yet start a worker, expose an
asynchronous endpoint, or advertise a capability. The public synchronous endpoint continues to use
the same execution path with a stateless no-op controller.

The new seam consists of:

1. `TestSuiteStabilityExecutionDescriptor`, a payload-free immutable parent identity;
2. `TestSuiteStabilityExecutionControl`, a fail-closed cooperative control contract;
3. `executeControlled(...)`, the server-owned worker entry point over the existing algorithm;
4. explicit checkpoints around durable progress restoration, each new source attempt, source
   verification before parent checkpoint, evidence sealing, and parent terminal publication.

The descriptor contains only run, scope, idempotency, request fingerprint, and classification. It
does not contain a fixture, context value, child id, source output, bearer credential, or business
payload.

## 2. Control ordering

| Order | Callback | Attempt | Required invariant |
| --- | --- | ---: | --- |
| 1 | `executionStarted` | n/a | bind one exact parent identity before new work |
| 2 | `BEFORE_PROGRESS_RESTORE` | 0 | cancellation may stop before governed source refetch |
| 3 | `BEFORE_ATTEMPT` | one-based | no new child suite run starts after a stop decision |
| 4 | `AFTER_SOURCE_VERIFICATION` | one-based | stop may win before the source enters parent progress |
| 5 | `BEFORE_EVIDENCE_SEAL` | 0 | complete horizon is still cancellable before signing |
| 6 | `prepareTerminal` | n/a | external authority linearizes before parent publication |
| 7 | parent complete | n/a | exact live parent lease and full journal are consumed |

`prepareTerminal` is intentionally outside the parent-store exception mapping. A queue
cancellation or deadline decision must remain distinguishable from an unavailable evidence store;
it cannot be converted into a retryable generic 503.

Idempotent parent replay still executes `executionStarted -> prepareTerminal` and skips all attempt
checkpoints. This is required after a worker crashes between parent publication and queue
completion: its successor can resolve the signed parent and converge the queue without rerunning
the stability horizon.

## 3. Failure and race behavior

| Event | Durable result |
| --- | --- |
| control stops before a new attempt | no additional child submission |
| control stops after source verification | child evidence remains governed; parent prefix is not appended |
| control stops at terminal preparation | complete parent progress is consumed by the stop tombstone; no signed parent row |
| parent already exists | controller must still linearize external terminal authority |
| controller decision is ambiguous | exception propagates; execution never assumes permission |
| ordinary synchronous invocation | no-op controller preserves prior behavior |

The control seam does not itself decide authorization, cancellation, deadline, retry, or queue
state. Those responsibilities belong to the forthcoming worker lease guard. Keeping the seam
policy-free prevents the stability algorithm from depending on one scheduler implementation.

## 4. Verification

Focused command:

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=TestSuiteStabilityExecutionServiceTest test
```

The 17 focused tests pass with zero failures, errors, or skips. They cover the pre-existing
idempotency, lease-loss, durable-prefix recovery, evidence and statistical behavior plus:

- exact payload-free callback ordering for three attempts;
- cancellation after source verification and before parent checkpoint;
- cancellation at the final publication boundary without store-error remapping;
- synchronous re-entry rejection after a parent stop;
- terminal control invocation during idempotent parent replay.

## 5. Required next step

The next atomic increment must provide a worker guard that:

1. heartbeats the exact queue owner/epoch fence during long source attempts;
2. verifies the bound descriptor exactly matches the claimed job;
3. writes the parent stop tombstone when cancellation or deadline wins;
4. calls queue `prepareCompletion` from `prepareTerminal`;
5. preserves `COMMITTING` across crash recovery and completes the queue from replayed evidence;
6. fails closed on delegated authority ambiguity and classifies retryable versus terminal failures.
