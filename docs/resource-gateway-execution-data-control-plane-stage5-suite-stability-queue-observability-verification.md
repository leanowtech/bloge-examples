# Stage 5 suite-stability queue observability verification

## 1. Increment boundary

This increment adds database-authoritative queue telemetry and readiness to the profile-isolated
suite-stability job runtime. It does not expose job operations over HTTP, purge terminal records, or
claim that one local process can prove fleet-wide worker membership.

The implementation adds:

1. one-statement database-clock queue observation per exact `test` or `staging` environment;
2. fixed-cardinality Micrometer queue, worker-result, and local-lifecycle metrics;
3. an Actuator `HealthIndicator` for queued depth, oldest queued age, expired live leases, and store
   availability;
4. startup validation that an SLO depth threshold cannot exceed the hard queue capacity;
5. telemetry isolation so a metric registry outage cannot kill a poll lane or change a job result.

## 2. Snapshot authority

The repository no longer assembles an observation from separate status, oldest-age, lease, and
tenant queries. One conditional-aggregate SQL statement reads database time and all aggregate facts
from the same statement snapshot. This removes false readiness transitions caused by a concurrent
claim between independent queries.

The statement also returns total rows. The adapter requires that total to equal the sum of every
closed lifecycle status. An unknown persisted status therefore fails closed instead of disappearing
from all gauges and making a damaged queue look empty. A queued timestamp later than the database
observation is also rejected.

## 3. Readiness semantics

Per environment, readiness is `OUT_OF_SERVICE` when any configured threshold is exceeded:

| Violation | Database fact |
| --- | --- |
| `QUEUE_DEPTH_EXCEEDED` | `QUEUED` rows exceed the readiness threshold |
| `QUEUE_BACKLOG_STALE` | oldest queued creation time exceeds the age threshold |
| `EXPIRED_LIVE_LEASE_BACKLOG` | expired `RUNNING/CANCEL_REQUESTED/COMMITTING` leases exceed policy |

An observation failure is `DOWN` with `QUEUE_STORE_UNAVAILABLE`. A failure in one enabled
environment makes aggregate health `DOWN` while retaining payload-free facts for environments that
were observed successfully.

Terminal `FAILED`, `CANCELLED`, and `EXPIRED` job counts are visible but are deliberately not health
violations. They can represent valid product-under-test outcomes or authorized cancellation. Mixing
business correctness with platform readiness would cause workload behavior to evict a healthy
control-plane replica.

## 4. Metric contract

All series are registered up front. The only tags are closed `environment`, `status`, and `outcome`
vocabularies. No job, tenant, actor, suite, request key, failure code, exception, or payload can
become a label.

The scheduler reports every `TestSuiteStabilityJobWorkResult.Outcome`, unexpected null/exception
polls, configured state, active local polls, and closed state. Metric writes are secondary effects;
their exceptions are suppressed after one bounded warning and cannot terminate a lane. Queue
observation failures set prior gauges to `-1` and health to `-2` rather than leaving stale values
looking current.

## 5. Verification

Focused command:

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DatabaseTestSuiteStabilityJobRepositoryTest,\
RepositoryTestSuiteStabilityJobParentAuthorityTest,\
DatabaseTestSuiteStabilityRunRepositoryTest,TestSuiteStabilityExecutionServiceTest,\
TestSuiteStabilityJobExecutionCoordinatorTest,TestSuiteStabilityJobWorkerTest,\
TestSuiteStabilityJobSchedulerTest,TestSuiteStabilityJobTelemetryTest,\
TestSuiteStabilityJobSloMonitorTest,TestRuntimeProfileIsolationTest test
```

The 96 focused tests pass with zero failures, errors, or skips. New tests cover empty and active H2
aggregate snapshots, expired or missing live leases, unknown stored status, all SLO violations, business-failure
non-interference, partial environment outage, telemetry outage isolation, metric label inventory,
local scheduler lifecycle, production profile exclusion, and invalid/inert startup policy.

## 6. Remaining gap

Backlog age eventually detects a fleet with no effective workers, but a local gauge cannot prove the
complete serving inventory or detect all workers stopping immediately. That requires a durable
fleet heartbeat/membership protocol or an external orchestrator inventory proof; this increment does
not manufacture one from process memory.

The transaction-safe terminal-detail-to-HMAC-tombstone primitive now exists, but its cross-replica
lease, scheduler, counters, and freshness SLO are still absent. Poison-row quarantine/repair,
authenticated job HTTP, strict Schema, capability truth, independent test-kit support, alert routing,
and non-H2/soak/chaos evidence remain required before asynchronous execution is a product capability.
