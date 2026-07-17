# Stage 4 Bounded Durable Recovery-Sequence Verification

## Purpose

The atomic recovery-step protocol made a multi-suspension graph recoverable, but forced every
caller to repeat a fragile choreography: inspect the new fence, claim the released checkpoint,
submit the next signal, and reconstruct progress after an ambiguous response. A controller-local
loop would hide that work without solving late-signal drift or partial-response idempotency.

This increment introduces a bounded synchronous recovery sequence. The complete ordered signal
intent is durably reserved before signal zero, and every intermediate claim and step remains an
ordinary authenticated, fenced, idempotent child command.

## Protocol

`POST /api/testing/durable-executions/{runId}/recovery-sequences` is available only in `test` or
`staging` and requires the dedicated `TEST_DURABLE_RECOVERY_SEQUENCE` operation. The strict
`bloge.durableTestRecoverySequenceRequest.v1` accepts:

- one outer caller-stable `clientRequestId`;
- the exact initially issued owner, epoch, revision, and checkpoint fingerprint; and
- 1 through 16 ordered signals, each with an exact node id and explicit JSON data.

Each signal is limited to 256 KiB and the complete program to 1 MiB. The entire list is validated
before reservation or child execution, so an oversized or malformed late signal cannot leave a
committed prefix.

The response contains one payload-free `durableTestRecoveryStepResponse.v1` per consumed signal,
the provided and consumed counts, final outcome/status, and one stop reason:

| Stop reason | Meaning |
| --- | --- |
| `TERMINAL` | A child reached one of the five terminal outcomes; later supplied signals were not consumed. |
| `SIGNALS_EXHAUSTED` | Every supplied signal committed and the graph reached another suspension. |

Signal data, dispatches, authorization receipts, fixture/provider values, engine bodies, context,
credentials, and lease expiry are absent.

## Intent Reservation

`RecoverySequenceCommand` binds the outer key to the complete authenticated request fingerprint,
scope, run, and signal count. `reserveRecoverySequenceIdempotently` stores only those payload-free
facts, database-authority creation time, and a whole-record fingerprint in
`rg_test_durable_recovery_sequences`. Its semantic audit mutation shares the same local transaction.

The request fingerprint covers every signal node plus a bounded canonical data fingerprint, signal
ordering, initial fence, run, and the complete principal continuity material. The database row does
not contain the original signal list or any individual data fingerprint. Same-key drift is rejected
before a child command, including a change only to a late signal that has not executed yet. Stored
scope, count, run, request fingerprint, timestamp, or record-fingerprint tampering fails closed.

## Child State Machine

For child index `i` the server derives keys from tenant, environment, and the outer key; callers do
not own child identities.

1. Execute or replay recovery step `i` against the current exact dispatch fence.
2. If the result is terminal, stop without touching later signals.
3. If the result is suspended and no signals remain, return `SIGNALS_EXHAUSTED`.
4. Otherwise claim or replay the exact released checkpoint, freshly re-authorizing its complete
   dependency and principal closure and issuing a new hidden dispatch.
5. Execute child `i + 1` under that new fence.

An old dispatch never crosses a suspension. Every step retains its own admission permit, automatic
heartbeat, staged BLOGE session, four-store atomic commit, optional terminal receipt, and audit.

## Failure And Replay Semantics

| Failure point | Committed state | Unchanged outer retry |
| --- | --- | --- |
| Before reservation commit | none | reserves and starts at signal zero |
| After reservation, before step zero | intent only | replays reservation and executes step zero |
| After suspended step, before claim | stable suspended prefix | replays step, then claims exact checkpoint |
| After claim, before next step | prefix plus live `RESUMING` fence | replays claim and executes next step |
| After terminal step, before response | immutable terminal result | replays the complete consumed prefix |
| Same outer key with changed intent | no new mutation | deterministic conflict before child access |

The sequence is intentionally not one cross-step database transaction. Earlier signals may have
business effects and cannot be rolled back honestly when a later signal fails. The guarantee is a
durable, gap-free, exactly replayable prefix, not fictional all-or-nothing business atomicity.

## Verification

The persistence and orchestration gate is:

```bash
/opt/apache-maven-3.9.16/bin/mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DatabaseDurableTestExecutionCheckpointRepositoryTest,\
DurableTestRecoverySequenceServiceTest test
```

Verified on 2026-07-17: 93 tests passed with zero failures, errors, or skips. New counterexamples
prove payload-free exact replay, late-intent drift rejection, audit rollback, stored-record tamper
rejection, fresh intermediate claims, partial-prefix continuation, full replay identity, terminal
short-circuiting, signal exhaustion, and pre-mutation rejection of oversized late signals.

The public protocol gate is:

```bash
/opt/apache-maven-3.9.16/bin/mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DurableTestRecoverySequenceControllerTest,\
DurableTestRecoverySequenceServiceTest,TestingControlProtocolSchemaTest,\
TestabilityCapabilitiesTest,TestRuntimeProfileIsolationTest test
```

Verified on 2026-07-17: 18 tests passed with zero failures, errors, or skips. The independent
Resource Gateway test-kit `clean verify` passed 77 tests with no failures/errors/skips, packaged the
authoritative Schema in ordinary and shaded JARs, rejected invalid stop-reason and unbounded program
shapes, and passed its public Javadoc gate.

The complete Resource Gateway acceptance gate is:

```bash
/opt/apache-maven-3.9.16/bin/mvn -f resource-gateway-examples/pom.xml clean verify
```

Verified on 2026-07-17: 2298 tests ran with zero failures and errors; 28 existing conditional tests
were skipped. The real-browser authoring workflow passed, and the executable Spring Boot JAR was
rebuilt successfully. The broader recovery control-plane gate separately ran 146 tests with zero
failures, errors, or skips across reservation, owner claim, heartbeat, recovery step, recovery
sequence, terminal recovery, Schema, capability, and production-profile isolation.

## Honest Boundary

This closes automatic orchestration only for a finite signal program already present in one HTTP
request. It does not provide a durable signal inbox, asynchronous continuation, long polling,
runtime-state offload, tenant-fair scheduling, cross-process supervision, hard worker cancellation,
stream offsets, or pre-checkpoint historical trace evidence. It also inherits the current durable
command-store lifecycle: sequence reservations do not yet have an independently tombstoned
retention protocol. Those are separate Stage 4/5 requirements and remain explicit gaps.
