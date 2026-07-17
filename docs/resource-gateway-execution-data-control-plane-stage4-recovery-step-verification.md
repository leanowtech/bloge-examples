# Stage 4 Durable Recovery-Step Verification

## Purpose

The previous public recovery protocol accepted one signal only when that signal reached a terminal
BLOGE lifecycle. The engine session could already identify exactly one subsequent signal
suspension, but the application rejected it and discarded the staged state. A graph with two or
more human, timer-adapter, or external-signal boundaries therefore could not make durable progress.

This increment introduces the internal atomic recovery-step authority. One issued recovery
dispatch may now advance exactly one signal to either a new `SUSPENDED` checkpoint or one of the
five supported terminal outcomes. It deliberately does not publish a new HTTP contract yet.

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

## Honest Boundary

This increment closes the repository/runtime primitive for multiple signal suspensions. It does not
yet expose `bloge.durableTestRecoveryStepRequest/Response`, add a dedicated authenticated operation,
advertise a capability, or update the packaged wire Schema. Until that next increment lands,
external callers can still use only terminal recovery v1.

It also does not ship runtime state to a remote process, enforce a wall-clock kill, supervise a
worker across process failure, restore stream offsets, or preserve pre-checkpoint node/edge/attempt
history. Those remain separate Stage 4 requirements and must not be inferred from this atomic
checkpoint advance.
