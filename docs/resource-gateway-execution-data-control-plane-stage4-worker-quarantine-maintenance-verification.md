# Stage 4 Worker Quarantine Maintenance Verification

## Problem Closed

Automatic quarantine stops a permanently incompatible checkpoint from repeatedly consuming
authorization and worker-pull capacity. It does not by itself answer who may inspect the dead-letter,
who owns remediation, whether an operator acted on the exact checkpoint, or whether a lost response
can be retried without repeating a destructive action.

This increment adds a dedicated, profile-isolated maintenance protocol for exact-checkpoint worker
quarantines. It is payload-free, tenant/project scoped, database-clock fenced, idempotent, audited in
the state transaction, and retains token-free immutable action history. The follow-on
[two-person discard increment](resource-gateway-execution-data-control-plane-stage4-worker-quarantine-two-person-discard-verification.md)
replaces every newly initiated direct discard with an independent maker/checker protocol.

## Trust Boundary

All maintenance endpoints require:

- a verified workload credential;
- the exact `TEST_RUNTIME_MAINTENANCE` purpose;
- trusted environment `test` or `staging`;
- operator endpoints require `RG_TEST_WORKER_QUARANTINE_REQUIRED_GROUP` membership;
- discard approval requires `RG_TEST_WORKER_QUARANTINE_REQUIRED_APPROVER_GROUP` membership;
- at least `RG_TEST_WORKER_QUARANTINE_REQUIRED_CLEARANCE`;
- tenant, organization, project, environment, and owner derived from identity.

Requests cannot select scope or owner. Unknown JSON fields fail deserialization. Production does not
assemble the controller, service, database control plane, capability objects, or endpoint markers.

## Public Protocol

| Method | Path | Result |
| --- | --- | --- |
| `GET` | `/api/testing/durable-state/worker-quarantines` | bounded payload-free active page |
| `GET` | `/api/testing/durable-state/worker-quarantines/history` | bounded token-free immutable action history |
| `POST` | `/api/testing/durable-state/worker-quarantines/claims` | exact server-issued owner/token/version/expiry fence |
| `POST` | `/api/testing/durable-state/worker-quarantines/resolutions` | `RELEASE`, legacy exact replay, or approval-required rejection |
| `POST` | `/api/testing/durable-state/worker-quarantines/discard-approvals` | token-free checker approval for one exact live claim |
| `POST` | `/api/testing/durable-state/worker-quarantines/approved-discards` | atomically consume approval and remove quarantine |
| `GET` | `/api/testing/durable-state/worker-quarantines/approved-discards/history` | bounded token-free maker/checker evidence |

The authoritative JSON Schema defines all request and response versions. Page limits are `1..1000`;
claim duration is `1..3600` seconds; request IDs and uppercase reason codes use closed patterns.

## State Model

```text
automatic failure threshold
          |
          v
      AVAILABLE <------------------+
          | claim                    | RELEASE
          v                          |
       CLAIMED ----------------------+
          |
          | independent checker approval + maker DISCARD
          v
 active quarantine deleted + immutable history retained
          |
          v
 exact checkpoint may be reconsidered by a later worker scan
```

An expired `CLAIMED` row is effectively actionable and may be claimed by another actor. Expiry alone
does not delete quarantine or make the checkpoint worker-eligible. `RELEASE` relinquishes maintenance
ownership but preserves worker suppression. Only a fenced, independently approved `DISCARD` removes
the exact automatic quarantine.

## Persistence Separation

The automatic failure fact remains in
`rg_test_durable_worker_candidate_quarantines`. Maintenance never rewrites its reason, threshold,
failure count, scope, checkpoint binding, or timestamps.

The maintenance protocol owns seven separate tables:

| Table | Responsibility |
| --- | --- |
| `rg_test_durable_worker_quarantine_controls` | current `AVAILABLE`/`CLAIMED` owner fence and monotonic version |
| `rg_test_durable_worker_quarantine_claim_commands` | caller-stable claim intent and exact response replay |
| `rg_test_durable_worker_quarantine_resolutions` | caller-stable resolution intent and token-free receipt replay |
| `rg_test_durable_worker_quarantine_history` | immutable token-free action history retained after discard |
| `rg_test_durable_worker_quarantine_discard_approvals` | exact token-free checker intent and one-way consumption state |
| `rg_test_durable_worker_quarantine_discards` | caller-stable approved-discard intent and receipt replay |
| `rg_test_durable_worker_quarantine_discard_history` | dedicated token-free maker/checker evidence |

Control rows cascade with the automatic quarantine. History is independent so `DISCARD` cannot erase
the fact that an operator removed suppression. Every active, control, resolution, and history read
recomputes its derived scope key and canonical whole-record fingerprint; projection drift, enum drift,
or content tampering fails closed.

## Linearization And Lock Order

Claims, approvals, releases, and approved discards use the same leading lock order:

1. lock the full durable checkpoint authority row `FOR UPDATE`;
2. parse and verify its sealed checkpoint JSON and fingerprint;
3. require the exact tenant/org/project/environment, run, and checkpoint fingerprint;
4. lock the exact automatic quarantine and maintenance control;
5. evaluate claim expiry using the database clock;
6. write state, command result, history when applicable, and bound audit in one transaction.

This order matches checkpoint transition before quarantine mutation. A transition cannot race between
validation and action: it either wins first and the command returns stale, or waits and then clears
the old scheduling state after the maintenance transaction.

## Fencing And Idempotency

The claim token is generated by the server. Release and approved discard require an exact match on
owner, token, version, caller-observed `claimUntil`, and a still-live database-clock lease. Discard
also requires a live, unconsumed checker approval for the same key, owner, version, expiry, and reason;
the checker identity must differ from the maker. Any mismatch fails closed.

`clientRequestId` is scoped to verified identity and operation intent:

- an exact claim retry replays the original token-bearing claim response;
- an exact release, approval, or approved-discard retry replays its original token-free receipt;
- the same request ID with changed key, duration, action, reason, fence, or actor is an idempotency
  conflict;
- only the first state transition appends the transaction-bound semantic action event;
- replay and rejection attempts append separate token-free audit events.

## Audit And Data Minimization

Claim/release/approval/discard state and their semantic security event commit together through
`TestRuntimeTransactionMutation`. If the event sink cannot bind or commit, the command receipt and
state mutation roll back.

The claim token appears only in the successful claim response, the resolution request, and the
internal claim-command replay row. It is excluded from list/history payloads, resolution receipts,
health details, metrics, problem responses, and semantic audit facts. Business input/output,
fixture, dispatch, dependency value, and checkpoint JSON are never exported by this protocol.

## Operations And SLO

The global repeatable-read SLO snapshot uses one database timestamp and reports only aggregates:

- quarantine count by closed automatic reason;
- effective count by `AVAILABLE` and `CLAIMED`;
- expired maintenance claims;
- retained legacy action-history and approved-discard history counts;
- live and expired unconsumed discard approvals;
- maximum automatic failure count and oldest unresolved age.

Stable health codes are:

- `WORKER_CANDIDATE_QUARANTINE_BACKLOG`;
- `WORKER_CANDIDATE_QUARANTINE_STALE`;
- `WORKER_CANDIDATE_QUARANTINE_CLAIM_EXPIRED`.
- `WORKER_CANDIDATE_QUARANTINE_DISCARD_APPROVAL_EXPIRED`.

Micrometer uses only the closed `reason` and `state` labels. Scope, run, checkpoint, owner, token,
exception, and payload values cannot become metric identity.

| Property | Environment variable | Default |
| --- | --- | --- |
| `gateway.testing.durable.worker-quarantines.required-group` | `RG_TEST_WORKER_QUARANTINE_REQUIRED_GROUP` | `resource-gateway-test-runtime-operators` |
| `gateway.testing.durable.worker-quarantines.required-approver-group` | `RG_TEST_WORKER_QUARANTINE_REQUIRED_APPROVER_GROUP` | `resource-gateway-test-runtime-quarantine-approvers` |
| `gateway.testing.durable.worker-quarantines.required-clearance` | `RG_TEST_WORKER_QUARANTINE_REQUIRED_CLEARANCE` | `RESTRICTED` |
| `gateway.testing.runtime-slo.worker-quarantine-max-records` | `RG_TEST_RUNTIME_SLO_WORKER_QUARANTINE_MAX_RECORDS` | `100` |
| `gateway.testing.runtime-slo.worker-quarantine-max-oldest-age-seconds` | `RG_TEST_RUNTIME_SLO_WORKER_QUARANTINE_MAX_OLDEST_AGE_SECONDS` | `86400` |
| `gateway.testing.runtime-slo.worker-quarantine-max-expired-claims` | `RG_TEST_RUNTIME_SLO_WORKER_QUARANTINE_MAX_EXPIRED_CLAIMS` | `0` |
| `gateway.testing.runtime-slo.worker-quarantine-max-expired-discard-approvals` | `RG_TEST_RUNTIME_SLO_WORKER_QUARANTINE_MAX_EXPIRED_DISCARD_APPROVALS` | `0` |

## Counterexample Matrix

| Counterexample | Required result |
| --- | --- |
| Caller supplies another tenant, owner, or future JSON field | request rejected before state access |
| Production profile starts | maintenance beans and capability endpoints are absent |
| Two actors claim one available row | one claim wins; the other receives not actionable |
| Claim lease expires | another authorized actor may take over with a new token/version |
| Owner, token, version, or `claimUntil` is forged | resolution returns stable fence conflict |
| Checkpoint changes after list or claim | claim/resolution returns stale checkpoint; successor is untouched |
| Request ID is reused with changed intent | stable idempotency conflict; no mutation |
| Claim response is lost | exact retry returns the original token and fence |
| Resolution response is lost | exact retry returns the original token-free receipt |
| `RELEASE` succeeds | quarantine remains and returns to actionable maintenance state |
| direct new `DISCARD` is submitted | stable approval-required rejection; no mutation |
| maker approves its own discard | self approval rejected; no approval row |
| independently approved `DISCARD` succeeds | automatic quarantine is removed; two-person history remains integrity-verifiable |
| two distinct commands race to consume one approval | exactly one mutation and history record commits |
| Audit append fails | state, command result, receipt, and history all roll back |
| Control/history content is changed out of band | read fails closed |
| Health/metrics are inspected | only closed aggregate labels and counts are present |

## Verification

Focused verification covers database lifecycle and rollback, scoped authorization, HTTP routing,
strict protocol schemas, profile isolation, capability discovery, SLO aggregation, health codes, and
fixed-cardinality telemetry:

```bash
/opt/apache-maven-3.9.16/bin/mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DatabaseDurableWorkerQuarantineControlPlaneTest,DurableWorkerQuarantineServiceTest,DurableWorkerQuarantineControllerTest,TestRuntimeProfileIsolationTest,TestingControlProtocolSchemaTest,TestabilityCapabilitiesTest,TestRuntimeApplicationIntegrationTest,DatabaseTestRuntimeSloControlPlaneTest,TestRuntimeSloMonitorTest,TestRuntimeSloTelemetryTest test
```

The release gate also runs Resource Gateway `clean verify` and independent test-kit `clean verify`,
because protocol schema packaging and public JavaDoc are part of the compatibility surface.

The focused gate covers the base maintenance and follow-on maker/checker protocols. The database
control-plane tests include real two-transaction checkpoint-transition and approval-consumption
races, database-clock expiry, tamper rejection, atomic audit rollback, and concurrent exact retries.
The combined focused gate executes 48 tests, Resource Gateway `clean verify` executes 2,201 tests
with 34 existing conditional browser skips, and test-kit `clean verify` executes 63 tests; all gates
have zero failures and errors. Exact scope is recorded in the dedicated two-person verification.

## Honest Boundary

This increment is a governed test/staging remediation primitive, not a complete enterprise
dead-letter product. New discards require distinct verified maker/checker actors and separate
deployment groups, but there is no external ticket binding, governance-gate callback, device/session
assurance, or proof beyond the configured identity provider. The
claim-command table retains the token needed to reproduce an exact lost claim response; field-level
encryption, bounded command-receipt retention, and cryptographic token hashing are not yet present.
History has application-level whole-record fingerprints but remains in the same database; it is not
externally anchored WORM evidence. History retention/purge, webhook notification, deep links, and
alert routing are also absent.

Runtime-state dispatch, fair/priority scheduling, cross-process supervision, hard cancellation,
non-H2 dialect certification, and production-load qualification remain separate Stage 4/5 work.
