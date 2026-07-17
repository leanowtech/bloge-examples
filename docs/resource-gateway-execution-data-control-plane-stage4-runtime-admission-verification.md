# Stage 4 Test Runtime Admission Verification

> Status: implemented on 2026-07-17
>
> Scope: `test` and `staging` profiles only
>
> Result: database-authoritative immediate admission is wired into every engine-starting testing path

## 1. Problem Closed

The runtime already observed queue depth and evidence completeness, but observation could not stop a
large regression suite or several replicas from exhausting the same operator or dependency. A local
semaphore would still over-admit after horizontal scaling, and independently gating every suite child
would make a suite compete with its own parent.

This increment introduces one profile-owned permit protocol with four quota dimensions:

- tenant and environment;
- immutable suite identity;
- every recursively reachable operator binding;
- every exact or deliberately conservative external dependency.

The invariant is: an engine starts only after all required claims commit atomically, and a terminal
result is published only while exact renewable ownership remains valid.

## 2. Execution Boundaries

| Path | Admission point | Release point |
|---|---|---|
| Graph execution | after target/control-plan preflight | after sanitized evidence is created or execution fails |
| Operator execution | after micro-graph/control-plan preflight | after sanitized evidence is created or execution fails |
| Batch | independently for each sequential child | after each child |
| Suite | before creating the RUNNING aggregate | after all serial children and terminal aggregate checkpoint |
| Durable creation | after exact authorization and prior-result lookup | after the first committed durable boundary |
| Durable terminal recovery | after dispatch/checkpoint re-authorization and prior-result lookup | after atomic terminal commit |

Suite child calls use package-private already-admitted adapters. They preserve authorization, fixture
compilation, isolated engine construction, sanitization, signing, and evidence persistence, but do not
acquire a second permit. This is the no-self-lock rule.

Queries, owner claims, and heartbeats do not start engines. They intentionally do not consume execution
capacity. Idempotent suite/durable result replay is returned before acquisition.

## 3. Distributed Permit Protocol

`DatabaseTestRuntimeAdmissionControl` owns four tables in the independent test-runtime store:

| Table | Authority |
|---|---|
| `rg_test_admission_locks` | fixed request-lock stripes and subject serialization keys |
| `rg_test_admission_subject_policies` | dimension, generation, and exact active limit |
| `rg_test_admission_leases` | hashed intent/policy, owner, epoch, token hash, database expiry |
| `rg_test_admission_claims` | all subjects held by one admission identity |

Acquisition locks a request stripe and every subject key in stable order, validates policy generation,
counts only database-clock-live leases, then inserts the lease and complete claim set in one transaction.
There is no partially admitted run. A stable request key cannot be rebound to a different intent.

Request locks use 4096 stable stripes. This bounds lock-table growth from random direct-run identities.
Release and expiry cleanup take the same stripe before mutating claims, so an old token or cleanup page
cannot remove claims belonging to a concurrently reacquired permit. Token hash, owner, epoch, policy,
and intent form the exact fencing boundary.

Policy rows retain subject history to detect stale replicas. A new generation may replace an old one
only when that tenant has no live permit. Mixed-generation replicas therefore fail closed rather than
enforce contradictory limits. Operators should drain before changing limits.

## 4. Privacy And Failure Semantics

Raw tenant-bound operator/dependency names exist only in the coordinator process. Persistence receives
canonical SHA-256 scoped subjects. Fixture payload, business context, credentials, raw lease token, and
diagnostic exception text are absent from tables, errors, logs, and metric labels.

The integration capability probe exposes `databaseAuthoritativeTestRuntimeAdmission` and
`boundedCardinalityTestRuntimeAdmissionMetrics` only with the profile-owned test runtime. Production
reports both as false and does not compose the authority, coordinator, policy, telemetry, or cleanup
scheduler.

Quota saturation and a duplicate live intent return bounded `429` responses with `Retry-After`. Stable
key rebinding returns `409`. Store outage, generation drift, coordinator shutdown, and lease loss return
stable payload-free `503` problems. The HTTP problem handler derives `Retry-After` only from a bounded
numeric detail and never reflects malformed input.

One daemon renewer maintains all local permits. Application shutdown marks guards lost, releases their
exact claims, and rejects new admission. If release cannot reach the store, database expiry recovers
capacity. Cleanup is oldest-first and bounded per tick.

## 5. Configuration

See the complete property/environment table in
[Resource Gateway Testing Control Plane API](resource-gateway-testing-control-plane-api.md#4212-database-authoritative-runtime-admission).
Defaults are tenant `16`, suite `2`, operator `8`, dependency `4`, lease `30s`, heartbeat `5s`, cleanup
every `60s`, and cleanup page `1000`. Invalid cross-field values fail startup; they are never clamped.

## 6. Reproducible Evidence

Focused verification:

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=TestExecutionApiServiceTest,TestSuiteExecutionServiceTest,\
DurableTestExecutionCreationServiceTest,DurableTestTerminalRecoveryServiceTest,\
TestRuntimeProfileIsolationTest,TestRunServiceTest,TestExecutionProblemHandlerTest,\
DatabaseTestRuntimeAdmissionControlTest,TestRuntimeAdmissionCoordinatorTest test
```

The focused run contains 96 tests with zero failures, errors, or skips. Meaningful cases include:

- two replicas competing for the last tenant permit;
- all-or-nothing tenant/suite/operator/dependency claims;
- exact compiled inventory passed from preflight to lifecycle admission;
- rejected control plans consuming no capacity;
- suite parent acquisition and child no-reacquisition;
- durable prior-result replay consuming no capacity;
- durable creation and recovery holding through committed boundaries;
- stale release and expiry cleanup racing a replacement lease without deleting its claims;
- stable-key intent rebinding rejection;
- bounded request-lock stripe cardinality;
- shutdown invalidation and immediate release;
- bounded `Retry-After` and non-reflection of malformed details;
- test/staging composition and production-profile absence.

The final project gate also passed:

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
```

Result: 2121 tests, zero failures, zero errors, 34 existing conditional skips, and a successfully
repackaged Spring Boot JAR.

## 7. Deliberate Remaining Gaps

This increment is immediate admission backpressure. It does not provide a queue, tenant-weighted
fairness, priority aging, remote worker polling/acquisition, autoscaling feedback, or preemption. It
does not physically stop an uncooperative operator after a lease is lost; hard wall-clock enforcement
still requires a cancellable worker boundary. H2 is the only certified SQL dialect, and production-scale
contention, failover, and long-soak evidence remain required before declaring full capacity productization.
