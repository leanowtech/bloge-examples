# Stage 4 Worker Cyclic Scan Cursor Verification

## Problem Closed

The first worker-pull increment used a bounded oldest-expiry-first page. If every candidate in that
page was permanently ineligible, later valid work could remain invisible forever. Increasing the
page size only moved the starvation threshold and turned a correctness defect into a capacity knob.

This increment adds one persisted cyclic keyset cursor per verified tenant, organization, project,
and environment scope. It makes progress through a bounded queue page durable without exposing the
cursor or queue contents on the public protocol.

## Invariants

1. The cursor scope is server-derived and has exactly one stable content-addressed `scope_key`.
2. Ordering is `(leaseExpiresAt, updatedAt, runId)` and remains fully evaluated by indexed SQL.
3. A scan reads its cursor and tail/head pages in one read-only `REPEATABLE_READ` transaction using
   the database clock.
4. Tail candidates are returned first. Only unused page capacity wraps to the queue head, and every
   wrapped candidate carries the next cycle epoch.
5. Each candidate carries an internal compare-and-advance token for the exact cursor snapshot and
   its own ordering coordinate. The caller cannot provide or observe this token.
6. Cursor advance commits only with the immutable `ACQUIRED` or `NO_WORK` result and semantic audit.
   Lease CAS and hidden dispatch remain in the same transaction for `ACQUIRED`.
7. Dependency or identity-authority infrastructure failure commits neither an outcome nor cursor
   progress. Deterministic candidate conflicts may advance through the last examined candidate.
8. A stale concurrent token is a no-op and cannot regress a newer cursor. It does not invalidate an
   otherwise correct worker acquisition result.
9. Cursor rows have a whole-record fingerprint. Lookup uses the independent derived `scope_key`, so
   tampering a scope projection cannot hide the row and silently reset scanning to the queue head.
10. Initial, same-cycle, wrap, and overflow transitions are validated independently of caller data.

## Liveness Claim

For a finite, stable candidate set of size `N`, candidate window `L`, and a sequence of successful
new worker-pull commands in the same scope, every candidate is inspected within at most
`ceil(N / L)` committed cursor advances after the current position. A full ineligible prefix can no
longer permanently hide work behind it.

This claim does not hold across storage/authority outages because fail-closed polls intentionally do
not advance. It also does not claim bounded waiting under unbounded queue churn, weighted tenant
fairness, priority, or aging. A later increment adds bounded deterministic per-candidate suppression
without changing this liveness claim.

## Persistence Linearization

`rg_test_durable_worker_scan_cursors` stores one scope cursor and cycle epoch.
`rg_test_durable_worker_scan_cursor_locks` serializes first creation and updates, including the
otherwise unsafe absent-row race. The acquisition transaction locks the scope, reloads and verifies
the current cursor, and advances only if the candidate token's expected fingerprint still matches.

The cursor is scheduling metadata, not execution authority. Checkpoint sealed JSON, exact lease CAS,
fresh dependency authorization, hidden dispatch, and immutable command result remain the authorities
for ownership transfer.

## Counterexample Matrix

| Counterexample | Expected result |
| --- | --- |
| First page is entirely ineligible | `NO_WORK` commits through its last candidate; next poll reaches deeper work |
| Page crosses the previous tail | Remaining slots wrap to the head with cycle epoch + 1 |
| Two polls use the same old cursor snapshot | First progress wins; stale progress cannot move the cursor backward |
| Scope projection is tampered | Derived `scope_key` still finds the row; fingerprint verification fails closed |
| Audit mutation fails | lease/result/dispatch/cursor/audit all roll back |
| Authority infrastructure fails | no command result and no cursor advance |
| Lost response | immutable result replay happens before scanning; cursor is not advanced twice |

## Verification

Focused reproduction:

```bash
/opt/apache-maven-3.9.16/bin/mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DatabaseDurableTestExecutionCheckpointRepositoryTest,DurableTestWorkerAcquisitionServiceTest,DurableTestWorkerAcquisitionControllerTest,TestRuntimeProfileIsolationTest,TestingControlProtocolSchemaTest,TestabilityCapabilitiesTest test
```

The focused gate executes 80 tests with 0 failures, 0 errors, and 0 skips. It includes 62 database
repository tests and 8 acquisition-service tests, plus controller, runtime-profile, protocol-schema,
and capability regressions. Resource Gateway `clean verify` executes 2,152 tests with 0 failures,
0 errors, and 2 existing browser-condition skips, and packages the Spring Boot JAR. The independent
test kit executes 63 tests with 0 failures, 0 errors, and 0 skips, including its public Javadoc and
shaded-JAR checks.

## Honest Boundary

The capability probe reports `durableTestWorkerCyclicScanCursor=true`. This means bounded cyclic
progress only. Temporary deterministic-candidate backoff is separately specified in
[Stage 4 worker candidate backoff verification](resource-gateway-execution-data-control-plane-stage4-worker-candidate-backoff-verification.md).
The combined capability still does not mean a queued scheduler, tenant weighting, priority/aging,
permanent quarantine/dead-letter/manual remediation, long polling, runtime-state delivery,
cross-process worker supervision, hard cancellation, non-H2 dialect certification, or production
load qualification.
