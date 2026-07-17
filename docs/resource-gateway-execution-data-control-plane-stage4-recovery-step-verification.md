# Stage 4 Durable Recovery-Step Verification

## Purpose

The previous public recovery protocol accepted one signal only when that signal reached a terminal
BLOGE lifecycle. The engine session could already identify exactly one subsequent signal
suspension, but the application rejected it and discarded the staged state. A graph with two or
more human, timer-adapter, or external-signal boundaries therefore could not make durable progress.

This increment introduces the atomic recovery-step authority and its authenticated public control
contract. One issued recovery dispatch may now advance exactly one signal to either a new
`SUSPENDED` checkpoint or one of the five supported terminal outcomes.

## State Machine

| Source | Engine boundary | Committed control state | Dispatch after commit |
| --- | --- | --- | --- |
| live `RESUMING` | exactly one signal suspension | `SUSPENDED` | none; lease is released to the queue |
| live `RESUMING` | completed/failed/cancelled/terminated | `TERMINAL` | none; terminal receipt is final |
| stale, expired, or unissued dispatch | any | no mutation | rejected |
| paused, active without a stable wait, or ambiguous waits | unsupported | no mutation | rejected by the runtime |

A suspended step sets `leaseExpiresAt = updatedAt = databaseNow`. This is an explicit ownership
release, not a general relaxation of monotonic lease rules. A later worker acquisition must scan,
freshly authorize, and claim the new checkpoint; the old dispatch cannot control the next signal.
This choice prevents one worker from monopolizing an arbitrarily long signal chain and gives the
future fairness scheduler a real scheduling boundary.

## Atomicity And Idempotency

`RecoveryStepCommand` binds:

- caller-stable idempotency key and authenticated request fingerprint;
- exact issued dispatch, source checkpoint, owner, epoch, revision, and lease deadline;
- resulting suspended or terminal outcome;
- exact BLOGE engine-state closure, fixture-consumption state, provider state, and evidence gaps.

`advanceRecoveryStepIdempotently` commits the four BLOGE stores, next control checkpoint,
immutable command record, optional terminal receipt, and transaction-bound companion mutation in
one local transaction. Response-loss replay resolves the immutable command before consulting the
live checkpoint and never reapplies the engine mutation. Same-key drift is a conflict.

The command record stores canonical evidence-gap JSON and its fingerprint. Reads verify the record,
source dispatch issuance, source fence, checkpoint closure, evidence-gap closure, and terminal
receipt when present. A suspended result must have no receipt; a terminal result must have one that
binds the exact source dispatch, terminal checkpoint, execution outcome, completion time, and gaps.

## Runtime Boundary

`DurableTestTerminalRecoveryRuntime.prepareStep` reuses the existing synchronous BLOGE
`resumeSuspended` path. It accepts only the two stable shapes already proved by the engine factory:

1. a terminal `ExecutionStatus`; or
2. `SUSPENDED` with exactly one new live signal wait.

The returned `PreparedRecoveryStep` is an in-process transaction handoff. It owns the staged
session and is not serializable worker payload. Closing it before repository commit discards all
speculative execution/checkpoint/wait/work-item mutations. The terminal-only v1 runtime delegates
to this primitive but still rejects and discards a suspended result, preserving its contract.

## Public Control Contract

`POST /api/testing/durable-executions/{runId}/recovery-steps` exists only under `test` or `staging`
and uses the dedicated `TEST_DURABLE_RECOVERY_STEP` operation. The strict
`bloge.durableTestRecoveryStepRequest.v1` accepts only:

- one caller-stable `clientRequestId`;
- the exact server-issued owner, lease epoch, and revision;
- the exact source checkpoint fingerprint; and
- one node id plus an explicit JSON signal value bounded to 256 KiB.

Unknown fields fail closed at the request, fence, and signal levels. A caller cannot provide an
outcome, engine state, fixture/provider cursor, lease deadline, dispatch, evidence label, or
terminal receipt. The service resolves immutable replay first, then applies the existing hidden
dispatch, principal continuity, fresh dependency authorization, database-authoritative admission,
and automatic heartbeat controls before opening the isolated BLOGE session.

`bloge.durableTestRecoveryStepResponse.v1` returns only the resulting control status, outcome,
owner/epoch/revision, database observation time, checkpoint fingerprint, and payload-free boundary
coordinates. `terminal` is JSON null for `SUSPENDED`; a terminal outcome carries the existing
promotion-blocking receipt projection. Signal data, checkpoint body, fixture values, provider state,
dispatch, authorization, and lease expiry are absent.

## Verification

```bash
/opt/apache-maven-3.9.16/bin/mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DatabaseDurableTestExecutionCheckpointRepositoryTest,DurableTestTerminalRecoveryRuntimeTest test
```

Verified on 2026-07-17: 85 tests passed with zero failures, errors, or skips. The new cases prove:

- suspended state and the four-store mutation commit atomically;
- lease release uses database time and makes the new checkpoint immediately scan-eligible;
- immutable replay does not execute the engine mutation twice;
- terminal steps retain a verified promotion-blocking receipt;
- companion-audit failure rolls back checkpoint, engine state, and command result;
- outcome/evidence intent drift is an idempotency conflict;
- evidence-gap JSON tampering fails closed; and
- expired and self-consistent-but-unissued dispatches cannot mutate control state.

The public protocol gate is:

```bash
/opt/apache-maven-3.9.16/bin/mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DurableTestRecoveryStepServiceTest,DurableTestRecoveryStepControllerTest,\
TestingControlProtocolSchemaTest,TestabilityCapabilitiesTest,TestRuntimeProfileIsolationTest test
```

Verified on 2026-07-17: 15 tests passed with zero failures, errors, or skips. They cover suspended
and terminal application commits, replay before mutable dependencies, strict transport rejection,
profile isolation, exact Schema versions and shapes, and truthful capability/endpoint discovery.

Full acceptance on 2026-07-17 also passed:

- Resource Gateway `clean verify`: 2285 tests, zero failures, zero errors, two existing conditional
  browser skips, and a repackaged Spring Boot JAR;
- Resource Gateway test-kit `clean verify`: 75 tests, zero failures/errors/skips, ordinary and shaded
  JARs, packaged authoritative Schema, and the public Javadoc gate.

## Honest Boundary

This increment closes the public one-signal step needed to advance a graph through multiple signal
suspensions. It is a control primitive, not an automatic chain orchestrator: after a suspended
result, a later worker must acquire and freshly authorize the released checkpoint before sending the
next signal. The terminal-only v1 endpoint remains compatible and continues to reject a second
suspension.

It also does not ship runtime state to a remote process, enforce a wall-clock kill, supervise a
worker across process failure, restore stream offsets, or preserve pre-checkpoint node/edge/attempt
history. Those remain separate Stage 4 requirements and must not be inferred from this atomic
checkpoint advance.
