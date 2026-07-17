# Stage 4 Worker Candidate Quarantine Verification

## Problem Closed

Temporary exponential backoff protects dependency authority from a hot loop, but every due record
eventually returns to the same authorization path. A permanently incompatible checkpoint can
therefore consume unbounded retries across days and deployments. Alerting on a large counter makes
the problem visible but does not stop it.

This increment adds an internal permanent worker quarantine for one exact checkpoint closure. After
a configurable number of consecutive failures from the deterministic closed vocabulary, the same
transaction that wins cyclic cursor progress replaces temporary backoff with quarantine. Time alone
cannot make that closure eligible again.

## State Model

```text
ELIGIBLE
  | deterministic failure
  v
BACKING_OFF -- due + same reason --> BACKING_OFF
  | consecutiveFailures >= quarantineThreshold
  v
QUARANTINED
  | explicit fenced checkpoint transition
  v
ELIGIBLE (new checkpoint fingerprint)
```

`BACKING_OFF` and `QUARANTINED` are deliberately separate records. A far-future retry timestamp is
not a quarantine: it has ambiguous clock semantics, cannot support a later fenced remediation
lifecycle, and pollutes retry-due observations.

## Closed Failure Vocabulary

Only `LEGACY_PROTOCOL`, `AUTHORIZATION_DENIED`, and `AUTHORIZATION_CONFLICT` may increase the
counter. Authority/store outages and unexpected `5xx` failures remain infrastructure failures and
commit no cursor, result, deferral, or quarantine. A changed reason starts a new consecutive
sequence; policy drift may apply a new threshold only on a later winning observation.

## Persistence Contract

`rg_test_durable_worker_candidate_quarantines` contains one active record per derived scope key and
`runId`:

- complete tenant/environment/organization/project projections;
- exact checkpoint fingerprint;
- closed reason, threshold-crossing count, and applied threshold;
- first-observed and quarantined database timestamps;
- a canonical whole-record fingerprint using
  `bloge.durableWorkerCandidateQuarantine.v1`.

Every read recomputes the derived scope key and record fingerprint. Unknown reasons, impossible
counts/time ordering, scope drift, and content tampering fail closed. Candidate pages load all
matching quarantines with one bounded `IN` projection per page, so the scheduler does not add a
per-candidate query.

## Linearization And Invariants

1. Quarantine can be created only for a still-live checkpoint whose exact scope, run, and
   checkpoint fingerprint match the observed candidate. The write transaction locks that authority
   row before revalidation, so a concurrent checkpoint transition either precedes the observation
   or follows it and clears the old scheduling state.
2. The database clock supplies `firstObservedAt` and `quarantinedAt`; caller clocks are irrelevant.
3. Only a scan token that wins cursor compare-and-advance may create quarantine. A stale replica may
   commit its own immutable no-work observation, but cannot amplify failure state.
4. Threshold transition inserts quarantine and deletes the matching temporary deferral in the same
   transaction. A candidate can never be projected as both states.
5. Quarantined candidates count as examined and advance cyclic progress, but skip dependency
   re-authorization.
6. Worker acquisition rechecks the exact quarantine inside the claim transaction. A stale or
   defective service-layer selection receives `NOT_RESUMABLE` before lease CAS.
7. Worker claim, cursor, hidden dispatch, immutable result, scheduling-state mutation, and semantic
   audit share one local transaction. Audit failure rolls all of them back.
8. A successful explicit fenced checkpoint transition clears scheduling state for the old
   fingerprint atomically. A stale quarantine never suppresses a successor checkpoint.
9. Public worker request/response schemas remain unchanged and payload-free. Reason, threshold,
   count, timestamps, scope, and checkpoint identity are not disclosed.
10. The worker audit adds only aggregate `quarantinedCandidateCount`; it contains no quarantine
    payload or dependency details.

## Counterexample Matrix

| Counterexample | Required result |
| --- | --- |
| Threshold is reached in a winning scan | quarantine commits, matching deferral is absent |
| Same quarantined checkpoint appears after any wall-clock duration | no authority call and no worker claim |
| Service passes a quarantined checkpoint as a selection | repository rejects `NOT_RESUMABLE`; lease/result remain unchanged |
| Two replicas observe the threshold candidate from one cursor snapshot | only cursor winner can quarantine |
| Checkpoint fingerprint changes through an explicit fenced transition | old quarantine is removed; successor is evaluated normally |
| Checkpoint transition races between scheduling read and write | row lock serializes authority; no stale deferral/quarantine remains |
| Quarantine reason/count/time/fingerprint is tampered | candidate read fails closed |
| Transaction-bound audit fails | quarantine, cursor, result, lease, and dispatch all roll back |
| Authority/store returns `5xx` | no quarantine counter is created or increased |
| Lost acquisition response is retried with the same key | immutable result replays before scan; no state is amplified |

## Operations And SLO

The global repeatable-read snapshot exposes only:

- active quarantine count by the three closed reasons;
- total active quarantine records;
- maximum threshold-crossing failure count;
- oldest active quarantine age.

Stable health violations are `WORKER_CANDIDATE_QUARANTINE_BACKLOG` and
`WORKER_CANDIDATE_QUARANTINE_STALE`. Micrometer gauges are rooted at
`resource.gateway.test.runtime.worker.candidate.quarantines`; only the closed `reason` tag is used.
Maximum-failure and oldest-age gauges are untagged. Tenant, organization, project, run, checkpoint,
owner, token, exception, and payload values are excluded from health details and metric identity.

## Configuration

| Spring property | Environment variable | Default |
| --- | --- | --- |
| `gateway.testing.durable.worker-acquisitions.quarantine-threshold` | `RG_TEST_DURABLE_WORKER_QUARANTINE_THRESHOLD` | `32` |
| `gateway.testing.runtime-slo.worker-quarantine-max-records` | `RG_TEST_RUNTIME_SLO_WORKER_QUARANTINE_MAX_RECORDS` | `100` |
| `gateway.testing.runtime-slo.worker-quarantine-max-oldest-age-seconds` | `RG_TEST_RUNTIME_SLO_WORKER_QUARANTINE_MAX_OLDEST_AGE_SECONDS` | `86400` |

Threshold is valid in `1..1,000,000`; `1` means immediate quarantine on the first deterministic
observation. SLO record limits are non-negative and age must be positive.

Capability discovery advertises `durableTestWorkerCandidateQuarantine=true` only when the isolated
testing endpoints are enabled.

## Verification

Focused reproduction:

```bash
/opt/apache-maven-3.9.16/bin/mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DatabaseDurableTestExecutionCheckpointRepositoryTest,DurableTestWorkerAcquisitionServiceTest,DatabaseTestRuntimeSloControlPlaneTest,TestRuntimeSloMonitorTest,TestRuntimeSloTelemetryTest,TestabilityCapabilitiesTest,TestRuntimeProfileIsolationTest test
```

The focused gate executes 100 tests with 0 failures, 0 errors, and 0 skips. It includes 77 durable
repository tests, 10 acquisition-service tests, and database SLO, monitor, telemetry, capability,
and profile-isolation regressions. Repository coverage includes a real two-transaction row-lock race
between checkpoint transition and quarantine persistence.

Resource Gateway `clean verify` executes 2,171 tests with 0 failures and 0 errors. Its 34 skips all
belong to the existing conditional `VisualAuthoringBrowserDomTest` browser suite; the executable
Spring Boot JAR is packaged. The independent test kit executes 63 tests with 0 failures, 0 errors,
and 0 skips, including public JavaDoc, packaged schema, and shaded CLI verification.

## Honest Boundary

This is an automatic active dead-letter state for worker pull, not the complete remediation control
plane. There is no authenticated quarantine list, maintenance-purpose claim lease, release/discard
decision, immutable resolution receipt, retained history after a successful checkpoint transition,
owner/approval policy, or webhook. The existing explicit run-targeted owner-claim endpoint is a
separate authenticated and audited recovery escape; it is not evidence that a dedicated quarantine
workflow exists.

The next increment must add a payload-free `AVAILABLE -> CLAIMED -> RELEASED/DISCARDED` maintenance
protocol with server token/version/owner/database-expiry fencing, caller-stable idempotency,
transaction-bound action audit, exact checkpoint revalidation, history retention, and capability /
schema / deep-link support. Runtime-state dispatch, fairness/priority/backpressure, cross-process
supervision, hard cancellation, non-H2 dialect certification, and production-load qualification
remain separate Stage 4/5 work.
