# Resource Gateway Stage 2 Suite-run Reconciliation Verification

## 1. Scope

This increment closes the permanent abandoned-`RUNNING` failure mode for immutable TestSuite
execution. It does not resume or re-execute an interrupted case.

Implemented chain:

1. initial `RUNNING` checkpoint and process-owner lease are inserted atomically;
2. a daemon heartbeat renews ownership while a synchronous child is still running;
3. heartbeat and evidence checkpoints advance a database-owned version fence;
4. an oldest-first bounded sweeper scans only expired, retained `RUNNING` rows;
5. reconciliation writes payload-free, promotion-blocked `EVIDENCE_INCOMPLETE` evidence through a
   status/owner/expiry/version compare-and-set;
6. completed child references survive terminalization, pending cases become evidence-incomplete,
   and no case is automatically rerun.

The v3 extension applies the same lease/version/CAS protocol to schema-admission checkpoints without
inventing business-child semantics. It preserves completed typed validator observations, converts
only pending common/admission results to evidence-incomplete, keeps the child closure empty, retains
the exact plan/schema/generator proof coordinates, and signs a v3 terminal aggregate.

## 2. Safety invariants

| Invariant | Enforcement |
|---|---|
| Slow child is not judged by run age | independent renewable owner lease |
| Replica clock skew cannot expire a live owner | shared database time is the lease/sweep authority |
| Dead owner cannot keep a row forever | lease expiry plus scheduled anti-entropy sweep |
| Stale scan cannot overwrite live progress | checkpoint version CAS and owner/expiry predicate |
| Duplicate side effects are not introduced | terminalization only; no automatic resume |
| Partial evidence is not promotable | aggregate `EVIDENCE_INCOMPLETE`, coverage `INCOMPLETE`, promotion `BLOCKED` |
| Schema admission is not rewritten as business execution | v3 structural coverage remains `NOT_EVALUATED`; typed admission coverage alone becomes `INCOMPLETE`; child closure remains empty |
| Evidence generations do not drift during recovery | v1/v2/v3 terminal evidence and attestations retain matching protocol generations |
| Reconciliation does not leak fixtures or topology | new fields contain counters, owner fingerprint, version, and time only |
| One bad row does not block the batch | per-candidate isolation and next-sweep retry |
| Production cannot activate the machinery | beans remain under `!production & (test | staging)` |

## 3. Configuration

`RG_TEST_SUITE_LEASE_SECONDS` and `RG_TEST_SUITE_HEARTBEAT_SECONDS` control ownership. The service
normalizes the heartbeat below the lease. `RG_TEST_SUITE_RECONCILIATION_INTERVAL_MS` and
`RG_TEST_SUITE_RECONCILIATION_BATCH_SIZE` bound the anti-entropy workload. A blank
`RG_TEST_SUITE_RUNNER_INSTANCE_ID` produces a process-local UUID owner.

## 4. Verification

Focused proof covers:

- H2 persistence round-trip, lease renewal, wrong-owner rejection, expired scan, terminal CAS, and
  stale-candidate rejection after a concurrent heartbeat;
- real scheduled heartbeat loss changing the local guard to not-held;
- preservation of passed/failed child facts and conversion of pending cases;
- v3 preservation of completed validator facts, typed pending-case conversion, empty child closure,
  plan/schema fingerprint retention, and verified terminal attestation;
- refusal to derive any terminal evidence when checkpoint verification/signing authority is
  unavailable;
- blocked promotion, incomplete coverage, canonical evidence fingerprint, CAS-race accounting, and
  per-candidate failure isolation;
- Spring test-profile assembly and machine-readable capability advertisement.

Commands:

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=TestSuiteRunLeaseCoordinatorTest,TestSuiteRunReconciliationServiceTest,\
TestRuntimePersistenceTest,TestSuiteExecutionServiceTest,TestabilityCapabilitiesTest,\
TestRuntimeApplicationIntegrationTest test

mvn -f resource-gateway-examples/pom.xml -Pfrontend clean verify
```

Verified result on 2026-07-16:

- focused reconciliation/profile proof: 22 tests, 0 failures, 0 errors;
- full Resource Gateway verification: 1763 tests, 0 failures, 0 errors, 34 conditional skips;
- npm audit: 0 vulnerabilities; production frontend build, real-browser regression, and Spring Boot
  executable JAR packaging succeeded.

Schema-admission v3 extension verified on 2026-07-17:

- execution/reconciliation/persistence/attestation/codec matrix: 44 tests, 0 failures, 0 errors;
- full Resource Gateway verification: 2364 tests, 0 failures, 0 errors, 2 conditional skips;
- JavaDoc generation and Spring Boot executable JAR packaging succeeded.

## 5. Honest residual boundary

Lease, evidence, and reconciliation currently share the isolated test-runtime database. When that
entire store remains unavailable, no implementation can commit a terminal record there; the next
sweep converges after recovery. Independent alert SLOs, a cross-failure-domain recovery queue, and
a physically separate test-runtime deployment remain required before claiming disaster recovery.
