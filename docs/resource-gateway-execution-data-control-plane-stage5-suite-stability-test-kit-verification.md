# Stage 5 asynchronous suite-stability test-kit verification

> Current-state note: the product HTTPS current-authority adapter listed as a later server-side gap
> in this historical increment is now implemented and verified in
> [suite-stability current-authority verification](resource-gateway-execution-data-control-plane-stage5-suite-stability-current-authority-verification.md).

## 1. Increment boundary

This increment adds an independent Java consumer for the durable suite-stability job protocol. The
test-kit depends on the packaged wire Schema, Jackson, the JDK HTTP client, and its existing evidence
types. It does not depend on Resource Gateway server artifacts or Spring.

The client now supports:

- typed fixed-horizon and statistical asynchronous submission requests;
- strict `202 Accepted` and canonical `Location` verification;
- typed payload-free admission and lifecycle projections;
- exact submit-response binding to suite, request, priority, deadline, and execution fingerprint;
- authenticated query and idempotent cancellation;
- dual-bounded submission and cancellation retry;
- dual-bounded terminal polling;
- bounded, sanitized `Retry-After` projection.

This increment does not treat a job view as correctness evidence. A successful job only supplies a
`stabilityRunId` and evidence fingerprint; release policy must fetch the terminal stability response
and use the existing pinned-key-set verifier.

## 2. Independent public types

| Type | Responsibility |
| --- | --- |
| `TestSuiteStabilityJobRequest` | Builds exact request-v1 or request-v2 execution intent and the v1 queue envelope |
| `TestSuiteStabilityJob` | Strict payload-free closed lifecycle projection |
| `TestSuiteStabilityJobSubmission` | Durable admission/replay disposition plus retained job |
| `TestSuiteStabilityJobRetryPolicy` | HTTP-attempt, single-delay, and monotonic elapsed bounds |
| `TestSuiteStabilityJobPollingPolicy` | Query-count, interval, server-delay, and monotonic elapsed bounds |

The public `TestingProtocol` constants are checked against the same definitions copied into the
ordinary and shaded test-kit JAR:

- `bloge.testSuiteStabilityJobSubmitRequest.v1`;
- `bloge.testSuiteStabilityJobCancelRequest.v1`;
- `bloge.testSuiteStabilityJobView.v1`;
- `bloge.testSuiteStabilityJobSubmitResponse.v1`.

## 3. Request construction and binding

`fixedHorizon(...)` admits exactly 3..20 attempts and emits
`bloge.testSuiteStabilityExecutionRequest.v1`. `statistical(...)` emits request v2, requires the exact
supported probability policy, and rejects a precommitted horizon that cannot satisfy that policy.

Both factories require:

1. a positive immutable suite revision and full lowercase SHA-256 suite fingerprint;
2. a bounded protocol-safe caller idempotency key;
3. scalar bounded provenance metadata accepted by the authoritative Schema;
4. an explicit queue priority;
5. an integral-second absolute deadline.

The request calculates the canonical fingerprint of the nested execution object. A submit response
is rejected unless its job has the exact suite reference, client request id, execution fingerprint,
priority, and deadline. The client cannot independently derive the server-owned job id because the
verified tenant and environment are credential claims, but it requires the exact job-id shape and a
canonical relative `Location` containing that same id.

## 4. Lifecycle and non-disclosure

The typed job closes status to:

`QUEUED`, `RUNNING`, `CANCEL_REQUESTED`, `COMMITTING`, `SUCCEEDED`, `FAILED`, `CANCELLED`, `EXPIRED`,
and `QUARANTINED`.

The model independently enforces terminal-state consistency and success-reference pairing after
strict Schema validation. Unknown properties are rejected. Tests prove that a response containing a
principal is rejected without copying the actor or response body into the public exception.

The projection retains only formal suite/request identity, priority, retry count, governed
timestamps, status, optional bounded failure code, and successful evidence references. Defensive
copies prevent callers from mutating retained raw JSON.

## 5. Retry and polling

Automatic retry is deliberately narrow:

1. the failure must be a server-declared retryable `429` or `503`;
2. the immutable request or cancellation body is reused exactly;
3. total HTTP attempts are bounded;
4. each local or server-provided delay is bounded;
5. monotonic elapsed time bounds whether another retry may start, while the client request timeout
   independently bounds each HTTP attempt;
6. a valid `Retry-After` takes precedence over exponential local delay;
7. a present but malformed or over-bound `Retry-After` stops retry instead of being treated as
   absence;
8. thread interruption restores the interrupt flag and fails with a payload-free local code.

Polling has independent query-count and elapsed-time bounds. It checks the monotonic deadline before
and after every query, so a terminal response arriving outside the caller's horizon is not silently
accepted. Successful non-terminal reads use the configured interval. Retryable query failures may
use a bounded server directive. Every terminal state is returned as-is for caller policy.

## 6. Cancellation

Cancellation always carries a separate protocol-safe idempotency identity. The one-shot method is
available for callers that own orchestration. The retry overload resends the exact same body under
the same dual bounds as submission.

The client requires the returned job id to match the requested resource. It does not claim that a
returned `CANCEL_REQUESTED` is already terminal or that a returned `COMMITTING` was cancelled.

## 7. Failure protocol

`ResourceGatewayTestException` continues to expose only status, stable code, bounded title,
retryability, and correlation id. It now also exposes:

- an optional validated `Duration retryAfter`;
- a separate `retryAfterSpecified` fact, allowing automatic retry to distinguish absence from a
  rejected directive.

Raw headers, problem details, credentials, request metadata, and response bodies are never retained
in exception messages.

## 8. Verification

Focused model, HTTP, compatibility, and packaged-Schema verification:

```bash
mvn -f resource-gateway-test-kit/pom.xml \
  -Dtest=TestSuiteStabilityJobTest,TestSuiteStabilityJobClientTest,\
TestingProtocolTest,ResourceGatewayTestClientTest test
```

Result: `62` tests, `0` failures, `0` errors, `0` skipped.

Complete independent-library gate:

```bash
mvn -f resource-gateway-test-kit/pom.xml clean verify
```

Result: `168` tests, `0` failures, `0` errors, `0` skipped. The ordinary JAR, shaded CLI JAR,
authoritative Schema resources, and warning-free public JavaDoc were produced successfully.

Server compatibility gate:

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
```

Result: `2620` tests, `0` failures, `0` errors, `2` conditional browser skips; the executable
Spring Boot JAR was repackaged successfully.

Negative coverage includes invalid local identities, malformed metadata, insufficient statistical
horizon, mismatched submit fields, non-canonical location, cross-resource query response, sensitive
additional response fields, invalid terminal references, retry-attempt exhaustion, invalid
`Retry-After`, transient submission/cancellation retry, failed terminal return, and three-step
queued/running/succeeded polling.

## 9. Residual risk

The combined server and client protocol still does not close:

1. a product-supplied real IAM/delegation current-authority adapter;
2. an immutable separately queryable cancellation semantic audit event;
3. durable fleet membership and cross-platform serving-inventory proof;
4. poison-job quarantine/repair workflow;
5. non-H2 dialect certification, load/soak/chaos/DR qualification, legal hold, and backup erasure;
6. hard cancellation of a non-cooperative operator;
7. independent wall-clock abstraction for deterministic testing of HTTP-date retry directives.

The next implementation step should close the highest-risk server-side production-readiness gap
rather than expanding the public client surface without a matching runtime guarantee.
