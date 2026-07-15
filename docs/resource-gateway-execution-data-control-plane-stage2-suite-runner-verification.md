# Execution Data Control Plane Stage 2: Immutable Suite Runner Verification

## Scope

This increment turns an exact `bloge.testSuite.v1` registry revision into an executable,
recoverable engineering protocol. It adds:

- `bloge.testSuiteExecutionRequest.v1` with an exact suite id, revision, full SHA-256 fingerprint,
  scoped `clientRequestId`, and `COLLECT_ALL` or `FAIL_FAST` scheduling;
- graph and operator case execution through the existing authorized child-run adapters;
- `RUNNING` persistence before the first case and after every child run;
- `bloge.testSuiteRunEvidence.v1` with child run links, structural coverage, and promotion eligibility;
- tenant/environment-scoped idempotency, query, retention, and classification checks;
- capability probe and canonical JSON Schema discovery.

The runner does not reconstruct mutable inline executions. Every child receives the suite's exact
target fingerprint and exact stored fixture id/revision/fingerprint.

## Safety Invariants

1. A blank/latest suite or fixture reference cannot execute.
2. Database uniqueness on `(tenant, environment, clientRequestId)` is the multi-replica race barrier.
3. An idempotency key retained after evidence expiry cannot silently rerun side effects.
4. `FAIL_FAST` stops only new scheduling; it never claims to cancel the current case.
5. Target drift blocks every case and still produces an auditable aggregate attempt.
6. Child target, fixture, run id, and evidence fingerprints are checked again before aggregation.
7. Coverage comes from child node/edge/assertion/consumption evidence, never suite metadata.
8. Coverage failure prevents aggregate `PASSED` even when every child case passes.
9. Terminal persistence failure produces `EVIDENCE_INCOMPLETE` and promotion `BLOCKED`; the service
   makes one best-effort terminal checkpoint but does not claim cross-failure-domain recovery.
10. Production identities are audited and rejected before suite lookup or child execution.

## Aggregate Semantics

Suite status is one of:

| Status | Meaning |
| --- | --- |
| `RUNNING` | Durable checkpoint exists and later cases may still be scheduled |
| `PASSED` | Every case completed without case/evidence failure and coverage is satisfied |
| `COMPLETED_WITH_FAILURES` | All cases completed, but a case or coverage requirement failed |
| `PARTIAL` | Target preflight or fail-fast left cases not scheduled |
| `EVIDENCE_INCOMPLETE` | Child or aggregate evidence integrity cannot be proven |

Coverage v1 evaluates required case types, structure-addressed invocation sites, transferred edge
endpoint pairs, minimum assertion density, and required fixture consumption. It deliberately does
not claim branch/rule/retry/fallback/compensation semantic metrics yet.

Promotion is only `NOT_EVALUATED`, `ELIGIBLE`, or `BLOCKED`. `ELIGIBLE` is a deterministic input to a
future certification or ANEKE gate; it is not a signature, approval, certification, or publication.

## Verification Matrix

Application-service tests prove:

- exact two-case collect-all execution, child provenance, structural coverage, and eligible verdict;
- same-key/same-intent retry returns the same suite run without another child execution;
- same-key/different-intent conflict;
- retired idempotency key rejection after evidence retention;
- fail-fast leaves later cases `NOT_SCHEDULED` and blocks promotion;
- target drift emits a persisted partial run without invoking a child;
- graph and operator branches both use exact stored fixtures and `FULL` internal evidence;
- passing child cases cannot hide missing edge coverage;
- terminal aggregate persistence failure fails closed and best-effort persists the incomplete state;
- production identity audit and rejection before any registry or execution call.

Persistence tests prove scoped create/find/idempotency lookup, RUNNING-to-terminal update, database
unique-key rejection, and cross-tenant non-disclosure. Controller and capability tests prove the
dedicated POST/GET routes and `TEST_EXECUTION` purpose.

The real Spring HTTP test registers a governed F3 fixture and immutable graph suite, executes it,
queries the child run and suite run, and repeats the same idempotent request. Resource descriptors
point to `127.0.0.1:1`; the case still passes with certifiable evidence, proving no HTTP call escaped
the transport fixture.

## Commands

Focused runner and protocol verification:

```bash
mvn -q -Dtest=TestSuiteExecutionServiceTest,TestRuntimePersistenceTest,\
TestExecutionControllerTest,TestingControlProtocolSchemaTest,TestabilityCapabilitiesTest,\
TestingDomainProtocolTest,TestRuntimeApplicationIntegrationTest test
```

Independent consumer artifact and canonical schema packaging:

```bash
mvn -f resource-gateway-test-kit/pom.xml clean verify
```

Full Resource Gateway regression, browser workflow, and JAR packaging:

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
```

Measured on 2026-07-15:

- focused runner/protocol verification: 33 tests, 0 failures, 0 errors, 0 skipped;
- independent test-kit: 13 tests, 0 failures, 0 errors, 0 skipped, canonical schema packaged;
- full Resource Gateway `clean verify`: 1748 tests, 0 failures, 0 errors, 34 conditional skips;
- the Spring Boot executable JAR and real-browser workflow both completed successfully.

## Explicit Non-Claims

- No canvas `Save as governed suite`, test-kit suite methods, or CI suite command is included yet.
- The seven built-in legacy catalog suites are not yet migrated into first-class registry assets.
- No async queue, automatic resume, abandoned-RUNNING reconciliation, list/history/trend endpoint,
  quota scheduler, or multi-region ownership protocol is claimed.
- No signed certification, ANEKE projection, mutation score, or full orchestration semantic coverage
  is claimed.
- Test runtime and network isolation are still profile/database boundaries in this example, not a
  separate deployment and enforced network policy.
