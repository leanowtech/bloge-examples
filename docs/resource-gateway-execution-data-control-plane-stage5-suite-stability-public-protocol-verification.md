# Stage 5 suite-stability public protocol verification

## 1. Increment boundary

This increment promotes the existing durable suite-stability queue from an internal runtime into an
authenticated, non-blocking server protocol. It adds strict submit/query/cancel wire objects,
payload-free lifecycle projection, machine-readable capability truth, profile configuration, and
transport/application/database tests.

It does **not** provide a permissive local current-authority implementation. Fresh submission is
available only when the opt-in worker starts with exactly one externally supplied
`TestSuiteStabilityJobAuthorizer`. Query and cancellation remain available while execution is
disabled or draining. The independent test-kit client is the next atomic increment and is not
claimed by this document.

## 2. Public operations

| Operation | Route | Authentication operation | Success |
| --- | --- | --- | --- |
| Submit/replay | `POST /api/testing/suites/{suiteId}/stability-jobs` | `TEST_SUITE_STABILITY_JOB_SUBMIT` | `202` + `Location` + submit response |
| Query | `GET /api/testing/stability-jobs/{jobId}` | `TEST_SUITE_STABILITY_JOB_READ` | payload-free job view |
| Cancel | `POST /api/testing/stability-jobs/{jobId}/cancellations` | `TEST_SUITE_STABILITY_JOB_CANCEL` | resulting payload-free job view |

All three operations accept only `TEST_EXECUTION` or `TEST_REPLAY` purpose and only `test` or
`staging` identity. Production still has no controller, service, repository, or capability marker.

## 3. Identity and replay invariants

1. `tenantId + environmentId + clientRequestId` canonically derives
   `stability-job-<sha256>`; callers cannot select a queue row id.
2. The execution request has its own canonical fingerprint. Reusing a request id with different
   execution, priority, or deadline is a stable idempotency conflict.
3. An exact retained replay is returned before mutable suite/current-authority lookup. Registry or
   authority drift cannot make an already accepted command disappear.
4. The database transaction reports whether submission was fresh or replayed; the HTTP response
   does not guess from timestamps or a preflight race.
5. Transient correlation id is retained on the original principal for traceability but excluded
   from submission-intent equality. Actor, delegation, groups, clearance, organization, project,
   environment, priority, deadline, and request remain bound.
6. Once detailed job retention has produced a keyed tombstone, exact replay returns `410`; another
   intent remains `409` until tombstone expiry.

## 4. Authorization and non-disclosure

Repository lookup is first constrained by verified tenant/environment, then the application service
checks organization/project and classification clearance. Organization/project mismatch and absence
both return `RG.TEST.STABILITY_JOB_NOT_FOUND`; no response reveals which boundary failed.

`bloge.testSuiteStabilityJobView.v1` includes only job/suite/request fingerprints, closed lifecycle,
priority, retry count, governed timestamps, terminal flag, bounded failure code, and successful
terminal references. It excludes:

- stored principal and authority groups;
- execution metadata or business fixture/context values;
- lease owner, epoch, expiry, and queue policy generation;
- cancellation request/fingerprint;
- persistence submission fingerprint and whole-row integrity fingerprint.

## 5. Cancellation and terminal semantics

| Current state | Cancellation result |
| --- | --- |
| `QUEUED` | parent stop first, then terminal `CANCELLED` |
| `RUNNING` | parent stop first, then `CANCEL_REQUESTED`; worker converges to `CANCELLED` |
| `CANCEL_REQUESTED` / `CANCELLED` | exact command replay returns retained state; another command id conflicts |
| `COMMITTING` | unchanged; final publication linearization point already won |
| other terminal state | unchanged; cancellation has no retroactive effect |

The cancellation fingerprint binds tenant, environment, job, command id, actor, delegation, and
purpose, but deliberately excludes transient correlation id so a transport retry is stable.

## 6. Error protocol

| Condition | HTTP | Stable code | Retry |
| --- | ---: | --- | --- |
| malformed/unsupported command | `400` | `RG.TEST.STABILITY_JOB_REQUEST_INVALID` | no |
| deadline outside database horizon | `400` | `RG.TEST.STABILITY_JOB_DEADLINE_INVALID` | no |
| idempotency/cancellation conflict | `409` | dedicated conflict code | no |
| retained detail erased | `410` | `RG.TEST.STABILITY_JOB_REPLAY_WINDOW_EXPIRED` | no |
| global/tenant capacity | `429` | dedicated capacity code | `Retry-After` |
| worker disabled | `503` | `RG.TEST.STABILITY_JOB_SUBMISSION_UNAVAILABLE` | `Retry-After` |
| policy drift/store ambiguity | `503` | stable unavailable code | bounded retry |
| absent or cross organization/project | `404` | `RG.TEST.STABILITY_JOB_NOT_FOUND` | no |

The response never copies repository exception messages, SQL, tenant, job, suite, actor, or payload
facts into the problem title or details.

## 7. Capability truth

The capability probe advertises four strict object generations and all three routes whenever the
isolated testing control plane exists. Runtime truth is split rather than collapsed:

- `asyncSuiteStabilityJobProtocol`: routes and Schema exist;
- `asyncSuiteStabilityJobQuery`: retained job reads exist;
- `asyncSuiteStabilityJobCancellation`: cancellation exists;
- `asyncSuiteStabilityJobSubmission`: worker is explicitly enabled;
- `testability.suiteStabilityJobSubmissionEnabled`: same exact runtime fact.

Thus route discovery cannot be mistaken for executable background capacity. Enabling submission
without exactly one current-authority provider still fails startup.

## 8. Verification

Focused verification covers application, transport, database replay, profile isolation, capability,
shared synchronous authorization, and strict Schema:

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=TestSuiteStabilityJobServiceTest,TestSuiteStabilityJobControllerTest,\
DatabaseTestSuiteStabilityJobRepositoryTest,TestSuiteStabilityExecutionServiceTest,\
TestingControlProtocolSchemaTest,TestabilityCapabilitiesTest,TestRuntimeProfileIsolationTest test
```

Result: `88` tests, `0` failures, `0` errors, `0` skipped.

The complete project gate also passed:

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
```

Result: `2620` tests, `0` failures, `0` errors, `34` conditional browser skips; the executable
Spring Boot JAR was repackaged successfully.

The focused gate proves deterministic admission, database-authoritative replay disposition,
correlation-independent replay, mutable-authority bypass for retained work, disabled-worker replay,
cross-scope non-disclosure, clearance, actor-bound cancellation, stable error mapping, dedicated
authentication operations, malformed JSON rejection, no payload fields in JSON, strict state
conditions, capability on/off truth, production isolation, and invalid retry configuration.

## 9. Residual risk and next step

This protocol does not close the following risks:

1. the independent test-kit does not yet expose typed async submit/query/cancel operations;
2. no product-supplied real IAM/delegation current-authority adapter exists;
3. cancellation actor is cryptographically bound in the command fingerprint but is not yet a
   separately queryable immutable semantic audit event;
4. durable fleet membership, poison-row quarantine/repair, non-H2 certification, soak/chaos/DR,
   legal hold, and backup erasure remain open;
5. cooperative cancellation cannot interrupt a non-cooperative operator; hard process isolation is
   still required for that guarantee.

The next atomic step is the independent test-kit protocol/client with packaged Schema validation,
typed polling and cancellation, response-binding checks, bounded retry handling, and no server/Spring
dependency.
