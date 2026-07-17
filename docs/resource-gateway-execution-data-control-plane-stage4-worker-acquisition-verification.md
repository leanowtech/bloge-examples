# Stage 4 Worker Pull Acquisition Verification

## Scope

This increment closes the missing control-plane transition between an indexed expired durable
checkpoint queue and a worker that does not already know a `runId`. It adds a non-blocking,
payload-free pull protocol. It does not transfer BLOGE runtime state to a remote process.

Public protocol:

```http
POST /api/testing/durable-executions/worker-acquisitions
Authorization: Bearer <workload-token>
X-Purpose: TEST_EXECUTION | TEST_REPLAY
```

Request and response versions are:

- `bloge.durableTestWorkerAcquisitionRequest.v1`
- `bloge.durableTestWorkerAcquisitionResponse.v1`

## Invariants

1. Scope comes only from verified tenant, organization, project, and `test`/`staging` environment.
2. A caller cannot select a run, owner, lease, priority, queue filter, or candidate limit.
3. Candidate time comes from the database clock; scope, resumable state, expiry, order, and limit are
   SQL predicates before checkpoint JSON decoding.
4. Every candidate is sealed-JSON/index verified and freshly re-authorized before lease mutation.
5. Exact source fence CAS, hidden authorization dispatch, immutable `ACQUIRED` result, and semantic
   audit commit in one local transaction.
6. A bounded scan with no claimable candidate commits database-timed `NO_WORK` and audit in one
   transaction. Infrastructure failure never becomes `NO_WORK`.
7. `ACQUIRED` and `NO_WORK` share one organization/project-scoped idempotency namespace. A committed
   result never changes when queue state changes; a later poll uses a new key.
8. The response exposes only target plus owner/epoch/revision/expiry/checkpoint fence. Dispatch,
   authorization, context, fixture/replay payload, provider cursor, and engine checkpoint stay hidden.
9. Acquisition does not hold a runtime admission permit. Capacity is acquired immediately before
   the existing server-side terminal recovery starts BLOGE.
10. Production profile cannot assemble the controller, service, or test-runtime store.

## Persistence Linearization

`rg_test_durable_worker_acquisitions` uses
`{tenant, environment, organization, project, clientRequestId}` as its physical key. Whole-record
fingerprinting covers scope, authenticated request fingerprint, outcome, database observation time,
run id, result checkpoint fingerprint, and hidden dispatch fingerprint. Result JSON is retained only
for `ACQUIRED`; `NO_WORK` must have no result material.

Candidate discovery and authorization intentionally occur before the mutation transaction because
dependency resolution may perform non-local reads. The transaction then rechecks the exact
owner/epoch/revision/checkpoint/expiry fence with database time. A stale candidate rolls back and the
service may try the next bounded candidate. If two replicas race on one candidate, at most one CAS
wins. If two replicas race on one idempotency key with different observations, the primary-key loser
rolls back its lease mutation and replays the winner.

## Failure Semantics

| Condition | Result |
| --- | --- |
| Missing credential or forbidden purpose | `401` / `403`, no scan |
| Production identity/profile | `403` or endpoint absent |
| Malformed version/key or caller-owned selector | `400`, no scan |
| Candidate exact dependency conflict | Skip within bounded window |
| Identity authority or dependency store outage | `503`, no false `NO_WORK` |
| Candidate CAS race | Try next candidate; bounded exhaustion may commit `NO_WORK` |
| Same key, different authenticated intent | `409` idempotency conflict |
| Acquisition store or sealed-result corruption | payload-free `503` |
| Transaction-bound audit failure | lease, dispatch, result, and audit all roll back |
| Lost response | exact immutable replay before queue scan or reauthorization |

## Verification Matrix

The focused suite covers:

- SQL scope, expiry, stable oldest-first ordering, and hard candidate limit;
- immutable `NO_WORK` after later work appears;
- exact acquisition dispatch integrity and ambiguous-response replay;
- transaction rollback when companion audit fails;
- whole-record tamper rejection and same-key/different-intent rejection;
- same tenant/environment key independence across projects;
- ineligible candidate skip and stale-CAS continuation;
- dependency infrastructure fail closed;
- payload-free controller serialization and caller-selector rejection;
- test/staging assembly plus production and mixed-profile veto;
- capability endpoint and authoritative Draft 2020-12 schema parity.

Reproduction commands:

```bash
/opt/apache-maven-3.9.16/bin/mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DatabaseDurableTestExecutionCheckpointRepositoryTest,DurableTestWorkerAcquisitionServiceTest,DurableTestWorkerAcquisitionControllerTest,TestRuntimeProfileIsolationTest,TestingControlProtocolSchemaTest,TestabilityCapabilitiesTest test

/opt/apache-maven-3.9.16/bin/mvn -f resource-gateway-examples/pom.xml clean verify

/opt/apache-maven-3.9.16/bin/mvn -f resource-gateway-test-kit/pom.xml clean verify
```

Verification results on 2026-07-17:

- focused Resource Gateway gate: 74 tests, 0 failures, 0 errors, 0 skips;
- Resource Gateway `clean verify`: 2146 tests, 0 failures, 0 errors, 2 conditional browser
  skips, with the executable Spring Boot JAR packaged successfully;
- independent test-kit `clean verify`: 63 tests, 0 failures, 0 errors, 0 skips, with the library
  JAR, shaded CLI JAR, packaged schema, and JavaDoc gate completed successfully.

## Honest Boundary

This increment is a remote control-plane acquisition protocol, not a complete worker product. It
does not provide long polling, persisted fairness cursor, tenant weighting, priority/aging,
unrecoverable-candidate quarantine, runtime-state delivery, cross-process heartbeat supervision,
hard process/container cancellation, multi-suspension orchestration, non-H2 dialect certification,
or production load qualification. Those remain Stage 4 work and must not be inferred from
`durableTestWorkerPullAcquisition=true`.
