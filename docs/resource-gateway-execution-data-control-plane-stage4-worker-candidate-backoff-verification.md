# Stage 4 Worker Candidate Backoff Verification

## Problem Closed

The persisted cyclic cursor makes every finite candidate reachable, but reachability alone still
lets a permanently ineligible candidate consume dependency-authority calls on every cycle. That
creates an avoidable authority hot spot and can make healthy work compete with deterministic
failures.

This increment adds an internal, database-timed negative scheduling cache for deterministic worker
candidate failures. It suppresses repeated authorization until a bounded retry deadline while the
cyclic scan continues through the candidate. It does not change the public worker-acquisition
request or response schema.

## Closed Failure Vocabulary

Only failures whose outcome is deterministic for the exact checkpoint may create a deferral:

| Reason | Source | Meaning |
| --- | --- | --- |
| `LEGACY_PROTOCOL` | checkpoint protocol | v1 or target-less checkpoint cannot enter v2 recovery |
| `AUTHORIZATION_DENIED` | exact re-authorization returns `403` | current principal is not allowed to recover this closure |
| `AUTHORIZATION_CONFLICT` | exact re-authorization returns `409` | current dependency closure cannot be authorized exactly |

Authority/store outages, unexpected runtime failures, and every other `5xx` remain fail-closed.
They commit no acquisition result, cursor progress, or deferral. Transient infrastructure faults
therefore cannot be converted into false `NO_WORK` or long-lived negative cache entries.

## Invariants

1. A deferral is keyed by derived authenticated scope plus `runId` and is bound to the exact
   checkpoint fingerprint.
2. Retry time is computed from the database clock in the acquisition transaction. Callers and
   application clocks cannot make a deferral active or due.
3. Delay grows exponentially from the configured initial delay to the configured cap. Both values
   are whole seconds in `1s..24h`, and the cap cannot be shorter than the initial delay.
4. An active deferral skips re-authorization but still counts as examined and advances the cyclic
   scan through its candidate.
5. A due deferral permits one fresh authorization attempt. The failure count increases only if the
   scan token wins compare-and-advance; stale concurrent scans cannot amplify it.
6. A candidate may be deferred only at or before the terminal progress committed by the same scan.
   Its scope, run, and checkpoint fingerprint must still match the authoritative checkpoint.
7. Checkpoint replacement, successful lease claim, or ordinary checkpoint update removes the old
   fingerprint's deferral. A changed checkpoint is immediately eligible for fresh evaluation.
8. Deferral mutation, cursor advance, lease claim/hidden dispatch when present, immutable command
   result, and semantic audit share one local transaction.
9. Scope projections and the complete deferral record are fingerprint-verified on read. Drift fails
   closed instead of silently disabling or extending suppression.
10. The public response remains payload-free and does not expose reason, retry deadline, counters,
    scope identity, or dependency details.

## Persistence And Linearization

`rg_test_durable_worker_candidate_deferrals` stores one record per scoped run: exact scope
projections, checkpoint fingerprint, closed reason, consecutive failure count, first/last database
observation, retry deadline, and whole-record fingerprint.

The worker first reads candidate plus active-deferral projection in the same database-clock,
`REPEATABLE_READ` scan snapshot used by the cyclic cursor. It authorizes only candidates without an
active deferral. The write transaction locks and verifies the scope cursor, compare-and-advances it,
then applies only the deterministic deferrals observed within that winning progress range. If the
cursor token is stale, the worker result may still linearize, but no new deferral is written and no
failure count is increased.

For a first failure, `retryAfter = databaseNow + initialBackoff`. For a due repeat of the same reason
and checkpoint, the delay doubles with saturation at `maximumBackoff`. A changed reason restarts the
sequence. Counts saturate rather than overflow.

## Counterexample Matrix

| Counterexample | Expected result |
| --- | --- |
| Same deterministic failure appears in the next cycle before its deadline | authority is not called; scan still advances |
| Retry deadline is due and the same failure repeats | one winning scan increments the count and doubles delay to the cap |
| Two replicas retry the same due record from one cursor snapshot | stale cursor token cannot amplify the count |
| Checkpoint fingerprint changes during the backoff | old record is bypassed and cleared; new checkpoint is evaluated immediately |
| Authority or dependency store returns `5xx` | no result, cursor, or deferral commits |
| Deferral scope/count/timestamp/fingerprint is tampered | read fails closed as store unavailable |
| Transaction-bound audit fails | lease, dispatch, result, cursor, and deferrals all roll back |
| Candidate is claimed by another exact path | successful checkpoint transition clears its historical deferral |
| Lost worker response is retried with the same key | immutable command result replays without a new scan or count increase |

## Configuration

| Spring property | Environment variable | Default |
| --- | --- | --- |
| `gateway.testing.durable.worker-acquisitions.initial-backoff-seconds` | `RG_TEST_DURABLE_WORKER_INITIAL_BACKOFF_SECONDS` | `5` |
| `gateway.testing.durable.worker-acquisitions.maximum-backoff-seconds` | `RG_TEST_DURABLE_WORKER_MAXIMUM_BACKOFF_SECONDS` | `300` |
| `gateway.testing.runtime-slo.worker-backoff-max-active` | `RG_TEST_RUNTIME_SLO_WORKER_BACKOFF_MAX_ACTIVE` | `1000` |
| `gateway.testing.runtime-slo.worker-backoff-max-retry-due` | `RG_TEST_RUNTIME_SLO_WORKER_BACKOFF_MAX_RETRY_DUE` | `100` |
| `gateway.testing.runtime-slo.worker-backoff-max-consecutive-failures` | `RG_TEST_RUNTIME_SLO_WORKER_BACKOFF_MAX_CONSECUTIVE_FAILURES` | `16` |
| `gateway.testing.runtime-slo.worker-backoff-max-oldest-age-seconds` | `RG_TEST_RUNTIME_SLO_WORKER_BACKOFF_MAX_OLDEST_AGE_SECONDS` | `3600` |

## Operations And SLO

The global test-runtime snapshot aggregates the closed reason vocabulary only. It exposes total and
active records by reason, retry-due count, maximum active consecutive failures, and oldest active
age. No tenant, organization, project, run, checkpoint, owner, exception, or payload enters health
details or metric identity.

Stable health violations are:

- `WORKER_CANDIDATE_BACKOFF_CAPACITY_EXCEEDED`
- `WORKER_CANDIDATE_RETRY_DUE_BACKLOG`
- `WORKER_CANDIDATE_REPEATED_FAILURES`
- `WORKER_CANDIDATE_BACKOFF_STALE`

Micrometer gauges are rooted at `resource.gateway.test.runtime.worker.candidate.deferrals`; only the
closed `reason` tag is used. Retry-due, maximum-failure, and oldest-age gauges have no tags. A
retry-due record means the negative-cache deadline has elapsed and the record awaits a later cyclic
scan or exact checkpoint transition; it is an operational pressure signal, not proof that the run
is still recoverable.

The capability probe reports `durableTestWorkerCandidateBackoff=true` only when testing endpoints
are enabled.

## Verification

Focused reproduction:

```bash
/opt/apache-maven-3.9.16/bin/mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DatabaseDurableTestExecutionCheckpointRepositoryTest,DurableTestWorkerAcquisitionServiceTest,DatabaseTestRuntimeSloControlPlaneTest,TestRuntimeSloMonitorTest,TestRuntimeSloTelemetryTest,TestabilityCapabilitiesTest,TestRuntimeProfileIsolationTest test
```

The focused gate executes 91 tests with 0 failures, 0 errors, and 0 skips. It includes 70 database
repository tests, 9 acquisition-service tests, and database SLO, monitor, telemetry, capability, and
profile-isolation regressions.

Resource Gateway `clean verify` executes 2,162 tests with 0 failures, 0 errors, and 2 existing
browser-condition skips, and packages the executable Spring Boot JAR. The independent test kit
executes 63 tests with 0 failures, 0 errors, and 0 skips, including public JavaDoc, packaged schemas,
and shaded CLI verification.

## Honest Boundary

This is bounded temporary suppression, not permanent quarantine. It has no dead-letter state,
manual remediation workflow, per-tenant policy, priority/fairness queue, adaptive retry classifier,
alert routing, or automatic deletion of indefinitely abandoned due records. Global SLO pressure
makes those records visible without exposing identities. Runtime-state delivery, cross-process
worker supervision, hard cancellation, non-H2 dialect certification, and production-load
qualification also remain Stage 4 work.
