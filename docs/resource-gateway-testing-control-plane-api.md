# Resource Gateway Testing Control Plane API

> Status: Stage 2 public control plane, protocol `bloge.testing.v1`
>
> Runtime profiles: `test`, `staging` only
>
> Production invariant: ordinary run APIs reject fixture/control fields before DTO deserialization

Machine-readable schema bundle:
[testing-control-plane-v1.schema.json](schemas/resource-gateway-testing/testing-control-plane-v1.schema.json).
It defines every public payload: target descriptor, fixture registration and stored revision,
single/batch execution request and response, effective plan, and evidence.

## 1. What This API Is

The testing control plane lets a verified caller freeze a graph target, inject deterministic
operator/resource fixtures, execute the real DAG on an isolated short-lived BLOGE engine, and retain
sanitized evidence. It is an engineering protocol, not a `testMode` switch on production execution.

The trust transition is explicit:

1. `IntegrationRequestAuthenticator` verifies the bearer workload and `X-Purpose`.
2. `TestExecutionApiService` accepts only identities whose trusted environment is `test` or `staging`.
3. The server mints `GRAPH_CONTRACT_TEST`; request content cannot mint an authorized purpose.
4. The graph, operator bindings, and a conservative snapshot of all resource descriptors are frozen.
5. `ExecutionControlCompiler` resolves every selector and rejects zero-match, ambiguity, stale target,
   unsafe external REAL/SPY, and fallback-to-real plans before graph execution.
6. A new engine instance executes the plan without production cache, quota, circuit breaker, durable
   state, listener, or context-carrier instances.
7. Evidence is bounded and redacted before it is written to the independent test-runtime database.

## 2. Start And Stop

The visual demo starts with the `test` profile by default, which assembles `/api/testing/**` and uses
a separate H2/Hikari pool for fixtures, test runs, and test security events:

```bash
./scripts/start-visual-canvas-demo.sh --open
./scripts/visual-canvas-demo.sh status
./scripts/stop-visual-canvas-demo.sh
```

Choose a profile explicitly when needed:

```bash
./scripts/start-visual-canvas-demo.sh --profile test
./scripts/start-visual-canvas-demo.sh --profile staging
./scripts/start-visual-canvas-demo.sh --profile production
```

`production` intentionally has no `TestExecutionController`, fixture repository, test-run repository,
or testability capability marker. The capability probe reports
`testability.executionEndpointEnabled=false` in that profile.

Direct Maven startup:

```bash
mvn -f resource-gateway-examples/pom.xml spring-boot:run \
  -Dspring-boot.run.profiles=test
```

Independent-store settings:

| Property | Environment variable | Default |
| --- | --- | --- |
| `gateway.testing.store.jdbc-url` | `RG_TEST_STORE_JDBC_URL` | `jdbc:h2:file:./data/resource-gateway-test-runtime;AUTO_SERVER=TRUE` |
| `gateway.testing.store.username` | `RG_TEST_STORE_USERNAME` | `sa` |
| `gateway.testing.store.password` | `RG_TEST_STORE_PASSWORD` | empty |
| `gateway.testing.store.maximum-pool-size` | `RG_TEST_STORE_MAXIMUM_POOL_SIZE` | `4` |
| `gateway.testing.store.retention-days` | `RG_TEST_STORE_RETENTION_DAYS` | `30` |

## 3. Authentication

Testing endpoints require a verified bearer and the least-privilege purpose for the operation:

```text
Authorization: Bearer <verified workload credential>
X-Purpose: TEST_EXECUTION | TEST_FIXTURE_READ | TEST_FIXTURE_WRITE
```

The local test-profile defaults are:

| Operation | Required `X-Purpose` |
| --- | --- |
| target discovery | any of `TEST_EXECUTION`, `TEST_FIXTURE_READ`, `TEST_FIXTURE_WRITE` |
| execute, batch, run query | `TEST_EXECUTION` |
| fixture revision query | `TEST_FIXTURE_READ` |
| immutable fixture registration | `TEST_FIXTURE_WRITE` |

The local demo bearer is `bloge-aneke-demo-token` and is granted all three purposes. Production
credentials should keep author, reader, and runner responsibilities separate.
`RG_INTEGRATION_ENVIRONMENT_ID` and `RG_INTEGRATION_ALLOWED_PURPOSES` override profile defaults;
deployment manifests must set both explicitly so a staging runner cannot inherit production identity claims.

`X-Tenant-Id`, `X-Environment-Id`, and actor headers are optional claim hints only. They never create
identity. If supplied, they must match the verified credential. Fixture and run lookups always apply
the verified tenant and environment scope, returning 404 rather than revealing cross-scope existence.

## 4. End-To-End Flow

### 4.1 Discover and freeze the target

```bash
curl -sS http://localhost:8080/api/testing/targets/graphs/loanDecisionPolicy \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_EXECUTION'
```

The response contains:

- graph id and current composite SHA-256 fingerprint;
- graph-level input/output schema contract;
- resource descriptor fingerprints;
- `CONSERVATIVE_ALL_REGISTERED` dependency policy.
- certification eligibility and explicit gaps; a graph without recoverable definition source is
  always restricted to `EXPLORATORY` evidence.

The conservative policy exists because BLOGE expressions may compute `resourceId` at runtime. A
descriptor change may invalidate more fixture bundles than strictly necessary, but the system never
certifies against an incomplete dependency set.

### 4.2 Register an immutable governed fixture

Use the discovered target fingerprint in both `target.fingerprint` and
`fixtureBundle.targetFingerprint`:

```http
PUT /api/testing/fixture-bundles/loan-prime-v1
Authorization: Bearer bloge-aneke-demo-token
X-Purpose: TEST_FIXTURE_WRITE
Content-Type: application/json
```

```json
{
  "schemaVersion": "bloge.fixtureBundleRegistrationRequest.v1",
  "target": {
    "kind": "GRAPH",
    "id": "loanDecisionPolicy",
    "fingerprint": "sha256:<from-target-descriptor>"
  },
  "fixtureBundle": {
    "schemaVersion": "bloge.fixtureBundle.v1",
    "fixtureBundleId": "loan-prime-v1",
    "revision": 1,
    "targetFingerprint": "sha256:<from-target-descriptor>",
    "classification": "INTERNAL",
    "logicalClock": null,
    "randomSeed": null,
    "rules": [
      {
        "schemaVersion": "bloge.fixtureRule.v1",
        "ruleId": "applicant-profile",
        "selector": {
          "graphPath": "/root",
          "nodeId": "fetchApplicant",
          "operatorRef": "",
          "resourceRef": "loan-applicant-service.getProfile",
          "functionRef": "",
          "capabilities": [],
          "tags": [],
          "invocationKind": "RESOURCE",
          "attempts": [],
          "occurrences": [],
          "correlationKey": "",
          "match": {
            "canonicalInput": null,
            "pathEquals": {},
            "pathsExist": [],
            "pathsAbsent": [],
            "schema": {},
            "correlationKey": "",
            "boundedRegex": {}
          }
        },
        "behavior": {
          "kind": "RETURN",
          "boundary": "TRANSPORT",
          "value": null,
          "rawBody": "{\"code\":0,\"data\":{\"applicantId\":\"prime\",\"score\":780,\"segment\":\"private-bank\"}}",
          "statusCode": 200,
          "headers": {"Content-Type": "application/json"},
          "errorCode": "",
          "errorType": "",
          "errorMessage": "",
          "after": null,
          "sequence": [],
          "replayRef": ""
        },
        "consumption": {
          "required": true,
          "minUses": 1,
          "maxUses": 1,
          "onExhausted": "FAIL",
          "onUnmatched": "FAIL"
        },
        "schemaCheck": {"mode": "STRICT", "waiverReason": ""}
      }
    ],
    "assertions": [
      {
        "scope": "OUTPUT_PATH",
        "nodeId": "assembleLoanDecision",
        "path": "/policy/ruleId",
        "operator": "EQUALS",
        "expected": "R1",
        "numericTolerance": null
      }
    ],
    "metadata": {"owner": "risk-quality", "caseType": "golden"}
  }
}
```

The `(tenant, environment, fixtureBundleId, revision)` key is immutable. Repeating byte-equivalent
content is idempotent; different content returns `RG.TEST.FIXTURE_REVISION_CONFLICT`.

### 4.2.1 Control logical time, delay, and timeout

`DELAY` and `TIMEOUT` are active only when the fixture bundle declares a `logicalClock` origin.
Each run receives its own monotonic clock. A logical sleep advances that clock atomically and returns
without wall-clock waiting; the clock and its state are never shared between test runs.

```json
{
  "logicalClock": "2026-07-15T09:00:00Z",
  "rules": [
    {
      "schemaVersion": "bloge.fixtureRule.v1",
      "ruleId": "bureau-timeout",
      "selector": {
        "graphPath": "/root",
        "nodeId": "fetchCreditScore",
        "operatorRef": "",
        "resourceRef": "",
        "functionRef": "",
        "capabilities": [],
        "tags": [],
        "invocationKind": "PRIMARY",
        "attempts": [],
        "occurrences": [],
        "correlationKey": "",
        "match": {
          "canonicalInput": null,
          "pathEquals": {},
          "pathsExist": [],
          "pathsAbsent": [],
          "schema": {},
          "correlationKey": "",
          "boundedRegex": {}
        }
      },
      "behavior": {
        "kind": "TIMEOUT",
        "boundary": "NODE",
        "value": null,
        "rawBody": "",
        "statusCode": null,
        "headers": {},
        "errorCode": "CREDIT_BUREAU_TIMEOUT",
        "errorType": "TIMEOUT",
        "errorMessage": "credit bureau did not answer",
        "after": "PT3S",
        "sequence": [],
        "replayRef": ""
      },
      "consumption": {
        "required": true,
        "minUses": 2,
        "maxUses": 2,
        "onExhausted": "FAIL",
        "onUnmatched": "FAIL"
      },
      "schemaCheck": {"mode": "STRICT", "waiverReason": ""}
    }
  ]
}
```

Time-control rules obey these fail-closed constraints:

- `after` is required, positive, and no greater than 365 days;
- only `boundary=NODE` is supported;
- `TIMEOUT` cannot also carry a return or protocol payload;
- `DELAY` advances time and then returns its schema-gated `value`;
- `TIMEOUT` advances time and throws BLOGE's `OperatorTimeoutException`, so the graph's real retry
  and fallback policies remain in charge;
- a timeout without recovery produces top-level `TIMED_OUT`, node status `TIMEOUT`, and the declared
  stable `errorCode`;
- evidence metadata records `logicalTime.mode`, `origin`, `current`, and `elapsedMs`; audit
  `startedAt/completedAt` remain real timestamps.

This mode verifies time-dependent business behavior and the graph's reaction to timeout. It does
not prove wall-clock watchdog accuracy, interruption of blocked operator code, or deterministic
completion order between concurrent branches. Those remain engine/sandbox conformance concerns.

### 4.3 Execute with a stored fixture

```http
POST /api/testing/executions
Authorization: Bearer bloge-aneke-demo-token
X-Purpose: TEST_EXECUTION
Content-Type: application/json
```

```json
{
  "schemaVersion": "bloge.testExecutionRequest.v1",
  "target": {
    "kind": "GRAPH",
    "id": "loanDecisionPolicy",
    "fingerprint": "sha256:<from-target-descriptor>"
  },
  "executionPurpose": "GRAPH_CONTRACT_TEST",
  "context": {"applicantId": "prime", "requestedAmount": 450000},
  "fixtureBundle": null,
  "fixtureBundleRef": {
    "fixtureBundleId": "loan-prime-v1",
    "revision": 1,
    "fingerprint": "sha256:<returned-by-registration>"
  },
  "verbosity": "FULL",
  "metadata": {"suiteId": "loan-decision-regression", "caseId": "prime-r1"}
}
```

Exactly one of `fixtureBundle` and `fixtureBundleRef` is required. Inline bundles are fingerprinted
immediately but always produce `EXPLORATORY` evidence. Stored bundles can produce `CERTIFIABLE`
evidence only when there is no schema waiver and each mocked resource site is protocol-derived or
transport-level rather than an output-level self-report.

### 4.4 Query a run or run a batch

```bash
curl -sS 'http://localhost:8080/api/testing/executions/<runId>?verbosity=SUMMARY' \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_EXECUTION'
```

`POST /api/testing/executions/batch` accepts 1-100 independent requests. Stage 2 runs them
sequentially; one item cannot share mutable plan or fixture-consumption state with another.

### 4.5 Java and JUnit 5 test kit

Java consumers do not need to assemble wire payloads or CI reports manually. The independent
`bloge-resource-gateway-test-kit` module provides target/fixture/run projections, a strict
`FixtureBundleBuilder`, a bounded JDK HTTP client, JUnit 5 assertions, and JUnit XML:

```bash
mvn -f resource-gateway-test-kit/pom.xml clean install
```

The client requests a fresh bearer credential for every operation, supplies the correct
`X-Purpose`, rejects protocol-version drift and oversized bodies, and omits payload/problem details
from exceptions and reports. Its typed `TestRun` projection retains node/site/correlation/occurrence,
retry-attempt, and edge endpoint facts without carrying payload values; legacy v1 responses remain
readable as zero-coordinate summaries. See the
[test-kit guide](../resource-gateway-test-kit/README.md) for a complete discover, register, execute,
assert, and report example.

### 4.6 Run the built-in graph dogfooding catalog

The compatibility graph-suite adapter now delegates to the same execution-control kernel. Its stored
catalog covers all seven built-in graphs with 14 cases:

```bash
curl -sS http://localhost:8080/api/gateway/graphs/contracts/tests/suites
curl -sS -X POST http://localhost:8080/api/gateway/graphs/contracts/tests/suites/run-all
```

All resource rows are explicit F3 transport fixtures. `minUses/maxUses` declares retry cardinality;
old rows that omit fidelity/cardinality fields remain one-use `OUTPUT_LEVEL` fixtures and therefore
remain exploratory. `enrichOrderList` includes a certifiable two-item parallel foreach case whose
nested shipping and invoice calls are independently controlled and occurrence-addressed. The
detailed matrix and unreachable-endpoint proof are in
[Stage 2 dogfooding verification](resource-gateway-execution-data-control-plane-stage2-dogfooding-verification.md).

## 5. Verbosity And Persistence

| Verbosity | HTTP response | Persisted record |
| --- | --- | --- |
| `SUMMARY` | status, fingerprints, consumption, assertions, diagnostics; no node/edge trace | full sanitized evidence |
| `STANDARD` | node/edge status and fidelity; payload values omitted | full sanitized evidence |
| `FULL` | sanitized node input/output and edge values | full sanitized evidence |

Sensitive keys, bearer/basic credentials, labeled secrets, oversized collections, deep objects, and
long strings are redacted or truncated before `rg_test_run_records` is written. Raw in-memory
`GraphResult` is never persisted by this API.

### 5.1 Occurrence and retry coordinates

`NodeTrace` is one logical node occurrence, not one row per retry. Consumers must use the complete
coordinate instead of joining on local `nodeId`:

| Field | Meaning |
| --- | --- |
| `invocationSiteId` | stable structural primary/compensation site id |
| `graphPath` | path of the graph owning the node, such as `/root/enrichOrders/foreach` |
| `correlationKey` | runtime foreach/loop or business correlation coordinate |
| `occurrence` | one-based binding count for this invocation site |
| `graphOccurrence` | one-based execution of the containing graph; joins sibling nodes and edges even when a branch skips a site |
| `attempts[]` | ordered actual delegate calls inside the occurrence; `attempt` is one-based |

`occurrence=0`, `graphOccurrence=0`, or `attempt=0` is reserved for a legacy producer that cannot
provide that coordinate. Current synchronous execution emits positive coordinates. A retry does not
increase `occurrence`; it appends another `AttemptTrace`, preserving the distinction between
"the second foreach item" and "the second retry of one item".

`EdgeTrace` carries `graphPath`, `correlationKey`, `graphOccurrence`,
`fromInvocationSiteId`, and `toInvocationSiteId`. Its status is:

- `TRANSFERRED`: source completed successfully or was mocked and the target was actually invoked;
- `SKIPPED`: a conditional edge was not selected after a successful source;
- `NOT_TRANSFERRED`: source did not produce a transferable value or the target was not invoked for a
  non-conditional path.

A target that later fails still has an incoming `TRANSFERRED` edge: transfer evidence describes data
movement, while node evidence describes processing outcome. `STANDARD` responses retain all
coordinates and attempt status/fidelity but omit node, attempt, and edge payload values. `FULL`
returns sanitized values.

## 6. Status Model

The public evidence status is exactly one of:

| Status | Meaning |
| --- | --- |
| `PASSED` | execution, assertions, and fixture consumption passed |
| `ASSERTION_FAILED` | graph completed but a business assertion failed |
| `EXECUTION_FAILED` | graph or controlled operator failed unexpectedly |
| `CONTROL_PLAN_REJECTED` | selector, fingerprint, behavior, or safety preflight rejected the plan |
| `FIXTURE_UNMATCHED` | an external invocation had no approved matching fixture |
| `FIXTURE_UNUSED` | a required fixture rule was not consumed |
| `CONTROL_PLAN_UNAVAILABLE` | reserved for durable resume without the original immutable plan |
| `EVIDENCE_INCOMPLETE` | execution ended but sanitized evidence could not be durably committed |
| `CANCELLED` | controlled cancellation |
| `TIMED_OUT` | an injected or run-level timeout was not recovered |

`MOCKED` is a node observation, not a top-level terminal status.

## 7. Production Boundary

The production run routes below are guarded before Jackson DTO deserialization:

- `/api/gateway/resources/execute`
- `/api/gateway/examples/compose/run`
- `/api/visual/drafts/run`
- `/api/visual/drafts/{draftId}/run`
- `/api/visual/publications/{publicationId}/run`

Nested `controlPlan`, `requestedControls`, `fixtureBundle`, `fixtureBundleRef`, `executionPurpose`,
`testMode`, mock, or behavior-override fields return
`RG.PRODUCTION.CONTROL_FIELD_FORBIDDEN`. The rejection must first commit a credential-free
`PRODUCTION_RUN_CONTROL_GUARD` audit record; audit failure returns 503 and remains fail closed.

## 8. Current Stage 2 Boundaries

Implemented now:

- public graph target discovery, fixture registration, execute, batch, and query APIs;
- profile, identity, purpose, tenant/environment, and classification gates;
- independent datasource, tables, retention, evidence sanitization, and security events;
- immutable plan plus graph/operator/resource dependency fingerprints;
- profile-sensitive capability probe and production control-field guard.
- standalone Maven test-kit with HTTP client, fail-closed fixture builder, JUnit 5 assertions,
  payload-free JUnit XML, and packaged canonical JSON Schema.
- complete seven-graph/14-case built-in dogfooding catalog, F3 legacy-suite migration, bounded retry
  consumption, and a Spring proof that root and synchronous nested resource calls do not escape fixtures.
- run-scoped advancing logical clock plus bounded `DELAY` and `TIMEOUT`; timeout injection uses the
  real BLOGE retry/fallback chain and emits normalized logical-time evidence.
- recursively frozen synchronous subgraph/foreach/loop/compensation sites, with run-scoped fixture
  propagation and fail-closed cycle/depth/site limits.
- occurrence-addressable synchronous node/attempt/edge evidence, including runtime correlation and
  containing-graph occurrence coordinates; non-empty parallel foreach certification is enabled.

Still intentionally outside this increment:

- `REPLAY`, retry-attempt/occurrence selectors, streaming/suspendable controls and evidence, and
  durable-resume plan restoration;
- signed certification, semantic coverage, ANEKE projection, and mutation testing;
- deterministic random/UUID/function execution services and deterministic concurrent scheduling;
- a physically separate test-runtime deployment and network policy;
- certification of streaming foreach/loop graphs until their invocation and edge evidence is
  occurrence-addressable and built-in suites exercise it.

Those items remain visible in the two industrial testability evolution plans and must not be inferred
as complete from `executionEndpointEnabled=true`.
