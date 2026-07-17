# Stage 4 Worker Quarantine Retention Verification

## Purpose

Worker-quarantine maintenance persists exact-replay commands, checker approvals, and token-free
history. Keeping those rows forever violates storage bounds and extends the lifetime of an encrypted
claim-token copy. Deleting a command alone is also unsafe: the same `clientRequestId` could then be
accepted as a new command and silently repeat a destructive action.

This increment adds a database-leased, bounded retention lifecycle. Detailed replay rows become
payload-free idempotency tombstones before deletion; history and expired tombstones are physically
deleted under independent windows. It does not introduce an archive tier or external evidence store.

## Lifecycle Contract

| Record family | Eligibility clock | Committed action |
| --- | --- | --- |
| claim command | `result_claim_until + commandRetention` | authenticate/decrypt the replay envelope, insert tombstone, delete exact source |
| resolution command | `result_acted_at + commandRetention` | insert tombstone, delete exact source |
| discard approval | `approval_until + commandRetention` | insert tombstone, delete exact source |
| approved-discard command | `result_acted_at + commandRetention` | insert tombstone, delete exact source |
| resolution history | `acted_at + historyRetention` | physically delete exact verified row |
| approved-discard history | `acted_at + historyRetention` | physically delete exact verified row |
| request tombstone | `tombstoned_at + tombstoneRetention` | physically delete exact verified row |

The command window starts only after the command result or authority deadline. The tombstone window
starts when detailed replay is removed, so request identity cannot resurrect at the boundary between
the two windows. After the tombstone itself expires, reusing that request ID is explicitly permitted.

An exact retry while a tombstone exists returns HTTP `409` with
`RG.TEST.WORKER_QUARANTINE_REPLAY_WINDOW_EXPIRED`. Reusing the same ID for changed intent remains
`RG.TEST.WORKER_QUARANTINE_IDEMPOTENCY_CONFLICT`. Neither case mutates quarantine state or audit
history.

## Data Minimization And Integrity

`rg_test_durable_worker_quarantine_request_tombstones` stores:

- operation kind and a content-addressed scope key;
- `request_key`, a server-computed `sha256:` digest over operation kind, scope, and request ID;
- the canonical intent fingerprint and source-record fingerprint;
- completion, tombstone, and expiry timestamps;
- a whole-record fingerprint over every stored field.

The table has no raw `client_request_id`, claim token, owner token, business payload, fixture,
checkpoint JSON, or reason text. Lookup recomputes `request_key` from the authenticated scope and
incoming request ID. A malformed digest, timestamp inversion, unknown operation kind, or changed
whole-record fingerprint fails closed before replay classification.

These digests are pseudonymous integrity and lookup keys, not anonymization. Low-entropy request IDs
may remain susceptible to an offline dictionary attack by a database reader who knows the scope.
A keyed request-index service is separate future hardening. Claim-token destruction is likewise
application-layer deletion: database backups, replicas, and key-manager retention require their own
erasure policy.

## Lease, Fence, And Transaction Model

One whole-record-fingerprinted singleton row named `bloge-worker-quarantine-retention` is the
database authority. A replica
acquires a database-clock lease with owner, random token, monotonically increasing epoch, expiry,
and revision. Another replica sees `LEASE_BUSY`; after expiry it may advance the epoch and take over.
The superseded owner cannot commit with its old fence.

One successful page transaction:

1. locks and verifies the live lease;
2. locks up to `pageSize` rows independently in each of four command categories, two history
   categories, and the tombstone-expiry category;
3. verifies each source whole-record fingerprint and authenticates every claim-token envelope;
4. inserts each tombstone and deletes its exact source in the same transaction;
5. deletes eligible history and expired tombstones with exact fingerprint fences;
6. rechecks the lease using database time, advances cumulative counters, records last success, and
   releases ownership in the same commit.

The maximum work in one tick is therefore `7 * pageSize`, with no unbounded table scan. Corruption,
lease expiry, stale epoch/token, delete-count drift, or database failure rolls back source deletion,
tombstones, history deletion, and counters together. A process crash before commit has ordinary
database rollback semantics; a crash after lease acquisition leaves only a bounded lease to expire.

The scheduler treats data and observability as separate failure domains. A retention transaction
failure records `FAILED` and reports that the last committed page is authoritative. Failure to emit
metrics or refresh a post-commit snapshot is logged separately and never relabels a committed page
as rolled back.

## Configuration

The scheduler exists only in `test` or `staging`, and never when `production` is also active.

| Property | Environment variable | Default | Valid range |
| --- | --- | --- | --- |
| `gateway.testing.durable.worker-quarantines.retention-instance-id` | `RG_TEST_WORKER_QUARANTINE_RETENTION_INSTANCE_ID` | generated per process | 1..255 characters after defaulting |
| `gateway.testing.durable.worker-quarantines.retention-lease-duration-seconds` | `RG_TEST_WORKER_QUARANTINE_RETENTION_LEASE_SECONDS` | `120` | 1..3600 whole seconds |
| `gateway.testing.durable.worker-quarantines.command-retention-days` | `RG_TEST_WORKER_QUARANTINE_COMMAND_RETENTION_DAYS` | `30` | effective duration 1 hour..3650 days |
| `gateway.testing.durable.worker-quarantines.history-retention-days` | `RG_TEST_WORKER_QUARANTINE_HISTORY_RETENTION_DAYS` | `365` | 1..3650 days |
| `gateway.testing.durable.worker-quarantines.tombstone-retention-days` | `RG_TEST_WORKER_QUARANTINE_TOMBSTONE_RETENTION_DAYS` | `365` | 1..3650 days |
| `gateway.testing.durable.worker-quarantines.retention-page-size` | `RG_TEST_WORKER_QUARANTINE_RETENTION_PAGE_SIZE` | `100` | 1..1000 per category |
| `gateway.testing.durable.worker-quarantines.retention-interval-ms` | `RG_TEST_WORKER_QUARANTINE_RETENTION_INTERVAL_MS` | `3600000` | 1000 ms..30 days |

Days are assembled as whole-day durations by the profile configuration. Unsafe lifecycle windows,
page sizes, lease durations, or scheduling cadence fail application assembly instead of silently
disabling cleanup or creating a busy loop.

Capability discovery advertises
`boundedDurableWorkerQuarantineMaintenanceRetention=true` only when the isolated testing runtime
owns the scheduler and database authority.

## Telemetry

Micrometer uses the prefix
`resource.gateway.test.runtime.worker.candidate.quarantines.retention.`:

| Metric | Labels | Meaning |
| --- | --- | --- |
| `attempts` | closed `result=completed|lease_busy|failed` | scheduled outcomes |
| `duration` | none | scheduler attempt duration |
| `tombstoned.total` | none | cumulative detailed rows removed |
| `tombstones.purged.total` | none | cumulative request reservations removed |
| `history.purged.total` | none | cumulative history rows removed |
| `tombstones.records` | none | current tombstone count |
| `last.success.epoch` | none | last committed page epoch second, or zero |

There are no tenant, request, run, checkpoint, owner, token, exception, or payload labels. These
metrics expose enough data to route external alerts, but this increment does not yet add an overdue
retention health violation or an alert-delivery integration.

## Counterexample Matrix

| Counterexample | Required result |
| --- | --- |
| two replicas tick concurrently | one lease owner; the other returns `LEASE_BUSY` |
| lease expires and another replica takes over | old epoch/token cannot delete or update counters |
| one eligible source is tampered | entire page rolls back; no partial tombstone or counter advance |
| tombstone is tampered | command retry fails closed before replay/conflict classification |
| claim envelope cannot authenticate | detailed row is retained; page rolls back |
| exact retry after detailed retention | stable replay-window-expired `409`; no command rerun |
| changed intent under a tombstoned ID | stable idempotency-conflict `409`; no command rerun |
| tombstone reaches its own deadline | bounded purge succeeds; later ID reuse is accepted |
| eligible rows exceed the configured page | each category processes no more than `pageSize` |
| telemetry refresh fails after commit | committed page remains successful; no false rollback claim |
| production profile starts | scheduler, telemetry bean, authority, and capability are absent |

## Verification Gate

The focused gate covers database lifecycle, cross-replica lease takeover, stale-fence rejection,
atomic rollback, page bounds, raw-ID absence, tombstone tamper, request reuse boundary, HTTP mapping,
profile isolation, capability discovery, application assembly, scheduler failure domains, and
fixed-cardinality telemetry:

```bash
/opt/apache-maven-3.9.16/bin/mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DatabaseDurableWorkerQuarantineControlPlaneTest,DurableWorkerQuarantineRetentionSchedulerTest,DurableWorkerQuarantineRetentionTelemetryTest,DurableWorkerQuarantineServiceTest,TestRuntimeProfileIsolationTest,TestRuntimeApplicationIntegrationTest,TestabilityCapabilitiesTest test
```

On 2026-07-17 this focused gate executed 51 tests with zero failures, errors, or skips; the database
authority contributed 31. Resource Gateway `clean verify` executed 2,223 tests with zero failures
or errors and 34 existing conditional browser skips, then packaged the executable Spring Boot JAR.
Independent test-kit `clean verify` executed 63 tests with zero failures, errors, or skips and passed
packaged-schema, shaded CLI, and public JavaDoc verification.

## Honest Boundary

This is same-database bounded deletion, not legal-hold orchestration, an archive, or external WORM
evidence. It has no external approval/ticket binding, retention-policy revision ledger, per-tenant
policy, deletion certificate, backup/replica erasure proof, keyed request index, or regulator hold.
The aggregate counters and source fingerprints are not an independently witnessed audit trail.

The active short-lived maintenance control fence still exists in the isolated database; KMS/HSM
key custody, non-H2 dialect certification, multi-region lease behavior, external alert routing,
production-scale contention qualification, and retention-backlog readiness policy remain separate
Stage 4/5 work.
