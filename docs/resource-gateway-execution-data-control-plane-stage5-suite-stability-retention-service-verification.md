# Stage 5 Suite-Stability Retention Service Verification

## 1. Delivered boundary

This increment turns the suite-stability tombstone primitive into an automatically scheduled,
cross-replica retention service for the isolated `test` and `staging` control planes. It adds:

1. one database-authoritative singleton lease with owner, opaque token, monotonic epoch, deadline,
   revision, cumulative counters, last-success time, and whole-record fingerprint;
2. one bounded transaction that verifies the live fence before and after processing, atomically
   tombstones expired terminal jobs, purges expired tombstones, advances counters, and releases the
   lease;
3. one fixed-delay scheduler that treats live-lease contention as a normal closed outcome;
4. one aggregate database-clock snapshot for lifecycle counts, backlog counts/ages, and freshness;
5. fixed-cardinality Micrometer metrics and a fail-closed Actuator health contributor.

It does not expose asynchronous job HTTP, change the queue's 30-day terminal-detail policy, or
weaken the keyed-HMAC replay tombstone semantics.

## 2. Ownership and transaction protocol

`rg_test_suite_stability_job_retention` is the authority. A replica may process a page only after an
exact compare-and-set moves the singleton from idle or expired ownership to its owner/token/epoch
fence using database time. Another replica receives `LEASE_BUSY`; it neither waits nor performs
deletion.

The claimed page runs in a new transaction:

1. lock and integrity-check the singleton state;
2. require exact owner, token, epoch, deadline, and a still-live database-clock lease;
3. lock at most one configured page of expired terminal jobs in stable order;
4. verify every whole-record fingerprint, insert a scoped HMAC tombstone, and exact-delete detail;
5. lock and verify at most one independent page of expired tombstones, then exact-delete them;
6. recheck the same fence and its database-clock expiry;
7. atomically advance durable counters and `lastSuccessAt`, then clear ownership.

Any corrupt source, corrupt tombstone, stale token/epoch, expired lease, arithmetic overflow, or
state-fingerprint mismatch rolls back the entire page. A failed page attempts a fenced lease
release; process death relies on lease expiry and higher-epoch takeover. There is intentionally no
mid-page lease renewal. A page that cannot finish inside the lease rolls back, so operators must
reduce page size or increase the bounded lease rather than accepting partial deletion.

## 3. Snapshot, telemetry, and readiness

`observeRetention()` uses one SQL statement and database observation time. It exposes only:

- cumulative jobs tombstoned and tombstones purged;
- current detailed-job and tombstone record counts;
- overdue terminal-job and expired-tombstone counts plus their oldest expiry times;
- lease owner/epoch/deadline, revision, last successful page, and observation time.

The lease token, request index, tenant, environment, job, suite, actor, key, payload, and exception
text never enter the snapshot, metrics, health details, or scheduler logs. Metrics use the sole
closed `result=completed|lease_busy|failed` tag and aggregate gauges under
`resource.gateway.test.runtime.suite.stability.jobs.retention.*`.

The health contributor is `UNKNOWN` during startup grace, `UP` only when freshness and both backlog
policies pass, `OUT_OF_SERVICE` for a stable SLO violation, and `DOWN` when the store cannot produce
an integrity-verified snapshot. Violation codes are stable and payload-free:

- `RETENTION_NEVER_SUCCEEDED` / `RETENTION_STALE`;
- `JOB_RETENTION_BACKLOG_EXCEEDED` / `JOB_RETENTION_BACKLOG_STALE`;
- `TOMBSTONE_PURGE_BACKLOG_EXCEEDED` / `TOMBSTONE_PURGE_BACKLOG_STALE`;
- `RETENTION_STORE_UNAVAILABLE`.

Metric failure cannot reclassify or roll back an already committed page. Store failure always fails
readiness closed.

## 4. Configuration and runbook

Both profile YAML files map the following environment variables:

```text
RG_TEST_STABILITY_JOB_RETENTION_INSTANCE_ID
RG_TEST_STABILITY_JOB_RETENTION_LEASE_SECONDS=120
RG_TEST_STABILITY_JOB_TOMBSTONE_RETENTION_DAYS=365
RG_TEST_STABILITY_JOB_RETENTION_PAGE_SIZE=100
RG_TEST_STABILITY_JOB_RETENTION_INTERVAL_MS=3600000
RG_TEST_STABILITY_JOB_RETENTION_SLO_INTERVAL_MS=30000
RG_TEST_STABILITY_JOB_RETENTION_SLO_STARTUP_GRACE_SECONDS=180
RG_TEST_STABILITY_JOB_RETENTION_SLO_MAX_STALENESS_SECONDS=10800
RG_TEST_STABILITY_JOB_RETENTION_SLO_MAX_OVERDUE_JOBS=0
RG_TEST_STABILITY_JOB_RETENTION_SLO_MAX_JOB_AGE_SECONDS=3600
RG_TEST_STABILITY_JOB_RETENTION_SLO_MAX_EXPIRED_TOMBSTONES=0
RG_TEST_STABILITY_JOB_RETENTION_SLO_MAX_TOMBSTONE_AGE_SECONDS=3600
```

The scheduler page is limited to 1..1,000, the repository hard guard to 1..10,000, the lease to
whole seconds from 1 second through 1 hour, and schedule/observation intervals to 1 second through
30 days. The freshness SLO must cover at least one schedule interval plus one lease window. Invalid
values fail application startup even when the stability worker is disabled, because retention owns
stored lifecycle safety independently of execution.

Operational response:

| Signal | First diagnosis | Corrective action |
| --- | --- | --- |
| repeated `lease_busy` | another replica owns a live page | confirm owner turnover and lease age; do not start a second ad hoc sweeper |
| `RETENTION_NEVER_SUCCEEDED` | scheduler/store/configuration never committed a page | inspect application scheduling and database availability |
| `RETENTION_STALE` | pages fail, starve, or exceed the lease | inspect failed attempts; reduce page size or raise the bounded lease/SLO coherently |
| job backlog | terminal detail expires faster than pages drain it | shorten interval or increase page size after load testing |
| tombstone backlog | replay reservations expire faster than purge | apply the same bounded capacity tuning; never delete rows manually |
| `RETENTION_STORE_UNAVAILABLE` | snapshot missing, corrupt, or database unavailable | restore database/integrity authority before returning the replica to service |

Request-index key rotation still follows append fleet-wide, prove read compatibility, switch active,
wait through the final tombstone lifetime, then remove the old generation. Retention counters do not
replace a serving-fleet compatibility proof.

## 5. Verification

Focused tests cover cross-replica lease contention, expired-lease takeover with a higher epoch,
stale-fence rollback, bounded page progress, durable counters, exact backlog snapshots, singleton
tamper rejection, corrupt-source rollback, scheduler and telemetry fault isolation, low-cardinality
metric inventory, every readiness state/violation, profile isolation, and unsafe configuration.

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DatabaseTestSuiteStabilityJobRepositoryTest,\
RepositoryTestSuiteStabilityJobParentAuthorityTest,\
TestSuiteStabilityJobRequestKeyProtectorTest,\
TestSuiteStabilityJobRetentionSchedulerTest,\
TestSuiteStabilityJobRetentionTelemetryTest,\
TestSuiteStabilityJobRetentionSloMonitorTest,\
TestRuntimeProfileIsolationTest test
```

The focused gate executes 61 tests with zero failures, errors, or skips. The full Resource Gateway
`clean verify` executes 2,599 tests with zero failures or errors and 33 existing conditional skips,
then successfully packages the executable Spring Boot JAR.

## 6. Explicit remaining gaps

Authenticated asynchronous submit/query/cancel HTTP, strict Schema, capability truth, independent
test-kit clients, poison-row quarantine/repair, non-H2 dialect certification, sustained capacity and
chaos/DR evidence, legal hold, backup erasure proof, and external WORM anchoring remain outside this
increment. A database row deletion is not proof that replicas, backups, exports, or audit systems
have erased the same data.
