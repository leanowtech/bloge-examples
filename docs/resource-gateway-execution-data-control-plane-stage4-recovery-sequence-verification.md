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

## Bounded Retention And Key Rotation

Exact response replay is bounded rather than permanent. The default detailed-command window is an
absolute 30 days from first reservation. A request accepted before that deadline atomically advances
an integrity-protected activity fence for one more command window; replay and retention row locks
therefore cannot admit a child writer after maintenance selected the parent. The activity fence
does not extend the replay deadline. A database-clock leased scheduler then selects a stable page
whose deadline and activity fence both elapsed, validates their whole-record fingerprints, derives
the only legal child step/claim/automatic-heartbeat keys,
verifies every discovered child, inserts a domain-separated keyed-HMAC tombstone, and physically
deletes the detail in one transaction. No plaintext outer request id is copied into the tombstone.

The default tombstone window is 365 additional days. During it, exact intent receives
`RG.TEST.DURABLE_RECOVERY_SEQUENCE_REPLAY_WINDOW_EXPIRED`; changed intent remains a conflict. Once
the tombstone is verified and purged, the outer key may be reused. Outer and tombstone pages are
independently bounded to `1..1000`; derived automatic-heartbeat discovery has a hard 4,096-row
limit. Child corruption, stale replica fence, retention-state corruption, tombstone corruption, or
an unavailable HMAC generation rolls the page back or fails startup before maintenance proceeds.

Rotation is fleet-wide append, switch, wait, remove. Add the new 32-byte root to every replica's
bounded key ring, verify fleet rollout, make its id active everywhere, keep every old generation
until its final tombstone expires, then remove it. Built-in cohort proof is not yet available for
this ring, so deployment orchestration must prevent mixed incomplete rings. Tombstones cannot be
re-keyed after plaintext identity erasure. The key and message domains are distinct from
worker-quarantine request indexing even when a deployment accidentally uses the same root.
Telemetry exposes only closed result tags and aggregate counters; no tenant,
run, request, payload, key material, or exception text becomes a label.

## Retention SLO And Readiness

Bounded deletion is not operationally complete unless a stalled scheduler becomes machine-visible.
The `test` and `staging` profiles therefore install a dedicated fail-closed Actuator health
indicator. It reads a repeatable-read snapshot with one database-authority `observedAt`, then checks:

- whether any page has succeeded after startup grace;
- the age of the last committed page;
- the count and oldest true eligibility age of outer rows ready for erasure; and
- the count and oldest expiry age of tombstones ready for purge.

For an outer row, eligibility is `max(createdAt + commandRetention, activityUntil)`. This prevents
the configured replay window from being misreported as backlog age. Snapshot policy must exactly
match the repository replay/activity window; drift fails closed instead of producing a plausible
but false count. `HEALTHY`, `INITIALIZING`, `SLO_VIOLATED`, and `STORE_UNAVAILABLE` map to Actuator
`UP`, `UNKNOWN`, `OUT_OF_SERVICE`, and `DOWN`. Details contain only stable violation codes,
aggregate counts, database-clock ages, and observation time. Store errors are never emitted, and a
metrics outage cannot downgrade or mask the independently computed health result.

New gauges publish overdue/expired counts, last-success age, oldest backlog ages, and the closed
health value `1/0/-1/-2`, without identity tags. Capability discovery exposes
`durableRecoverySequenceRetentionSloHealth` only with the isolated test runtime. Default policy is
three minutes startup grace, three hours maximum retention staleness, zero tolerated ready rows,
and one hour maximum oldest backlog age; deployments should set limits from measured page capacity
and scheduling jitter before wiring the aggregate Actuator endpoint into readiness.

## Verification

The persistence and orchestration gate is:

```bash
/opt/apache-maven-3.9.16/bin/mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DatabaseDurableTestExecutionCheckpointRepositoryTest,\
DurableTestRecoverySequenceServiceTest test
```

Verified on 2026-07-17: the retention-focused gate ran 122 tests with zero failures, errors, or skips.
Counterexamples prove payload-free exact replay, late-intent drift rejection, audit rollback,
stored-record tamper rejection, fresh intermediate claims, partial-prefix continuation, full replay
identity, terminal short-circuiting, signal exhaustion, pre-mutation rejection of oversized late
signals, atomic parent/child erasure, rollback on corrupt child state, independent page bounds,
cross-replica lease fencing, public policy bounds, stable child-key formats, HMAC domain separation,
rotation lookup, startup refusal when a referenced tombstone generation is unavailable, absolute
deadline rejection before physical retention, row-lock serialization with an accepted replay,
activity-fence tamper rejection, and fail-safe legacy activity migration.

The retention health, database snapshot, telemetry, capability, and profile-isolation gate is:

```bash
/opt/apache-maven-3.9.16/bin/mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DurableRecoverySequenceRetentionSloMonitorTest,\
DurableRecoverySequenceRetentionSchedulerTest,\
DatabaseDurableTestExecutionCheckpointRepositoryTest,\
TestRuntimeProfileIsolationTest,TestabilityCapabilitiesTest test
```

Verified on 2026-07-17: 113 tests passed with zero failures, errors, or skips. Counterexamples cover
startup grace, stale and never-successful retention, count and age backlog violations, database
store outage, telemetry isolation, unsafe policy bounds, replay-policy drift, aggregate-only
metrics, no identity leakage, and production-profile exclusion.

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

Verified on 2026-07-17: 2,329 tests ran with zero failures and errors; two existing conditional
tests were skipped. The real-browser authoring workflows passed and the executable Spring Boot JAR
was rebuilt successfully.

## Honest Boundary

This closes automatic orchestration only for a finite signal program already present in one HTTP
request. It does not provide a durable signal inbox, asynchronous continuation, long polling,
runtime-state offload, tenant-fair scheduling, cross-process supervision, hard worker cancellation,
stream offsets, or pre-checkpoint historical trace evidence. Sequence-owned detail now has bounded
retention, but unrelated durable creation, acquisition, claim, heartbeat, step, and terminal command
families do not yet share one general lifecycle authority. Same-database physical deletion also does
not provide legal hold, backup erasure proof, external WORM evidence, or non-H2 dialect certification.
Those are separate Stage 4/5 requirements and remain explicit gaps.
