# Resource Gateway Testing Control Plane API

> Status: Stage 2 public control plane, protocol `bloge.testing.v1`
>
> Runtime profiles: `test`, `staging` only
>
> Production invariant: ordinary run APIs reject fixture/control fields before DTO deserialization

Machine-readable schema bundle:
[testing-control-plane-v1.schema.json](schemas/resource-gateway-testing/testing-control-plane-v1.schema.json).
It defines every public payload: graph/operator target descriptors, fixture and test-suite
registration/stored revisions, graph/operator and immutable-suite execution requests, common and
aggregate responses, effective plan, and evidence.

## 1. What This API Is

The testing control plane lets a verified caller freeze a graph or operator binding, inject
deterministic operator/resource fixtures, execute the real DAG or a one-node micro graph on an
isolated short-lived BLOGE engine, and retain sanitized evidence. It is an engineering protocol,
not a `testMode` switch on production execution.

The trust transition is explicit:

1. `IntegrationRequestAuthenticator` verifies the bearer workload and `X-Purpose`.
2. `TestExecutionApiService` accepts only identities whose trusted environment is `test` or `staging`.
3. The endpoint mints `GRAPH_CONTRACT_TEST` or `OPERATOR_UNIT_TEST`; request content cannot mint an
   authorized purpose.
4. The graph, operator bindings, and a conservative snapshot of all resource descriptors are frozen.
5. `ExecutionControlCompiler` resolves every selector and rejects zero-match, ambiguity, stale target,
   unsafe external REAL/SPY, and fallback-to-real plans before graph execution.
6. A new engine instance executes the plan without production cache, quota, circuit breaker, durable
   state, listener, or context-carrier instances.
7. Evidence is bounded and redacted before it is written to the independent test-runtime database.

## 2. Start And Stop

The visual demo starts with the `test` profile by default, which assembles `/api/testing/**` and uses
a separate H2/Hikari pool for fixtures, immutable test suites, child test runs, recoverable suite-run
checkpoints, and test security events:

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

`production` intentionally has no `TestExecutionController`, fixture/suite repository,
child/suite-run repository, or testability capability marker. The capability probe reports
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
X-Purpose: TEST_EXECUTION | TEST_FIXTURE_READ | TEST_FIXTURE_WRITE | TEST_SUITE_READ | TEST_SUITE_WRITE
```

The local test-profile defaults are:

| Operation | Required `X-Purpose` |
| --- | --- |
| target discovery | any testing purpose, including suite read/write |
| execute, batch, child-run query, suite execute/query | `TEST_EXECUTION` |
| fixture revision query | `TEST_FIXTURE_READ` |
| immutable fixture registration | `TEST_FIXTURE_WRITE` |
| test-suite revision query | `TEST_SUITE_READ` |
| immutable test-suite registration | `TEST_SUITE_WRITE` |

The local demo bearer is `bloge-aneke-demo-token` and is granted all five testing purposes.
Production credentials should keep fixture authors, suite authors, readers, and runners separate.
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

### 4.1.1 Discover and freeze an operator binding

```bash
curl -sS http://localhost:8080/api/testing/targets/operators/httpResource \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_EXECUTION'
```

`bloge.testOperatorTargetDescriptor.v2` returns the operator target fingerprint, implementation
closure fingerprint, runtime-binding-state fingerprint, schema fingerprint, composability manifest
fingerprint, input/output schemas, execution model, side-effect/idempotency declarations, resource
dependencies, and explicit testability facts. v2 is intentional: v1 did not carry the required
composability facts and is retained by the Java test kit only as a historical version constant.

| `testabilityClass` | Meaning |
| --- | --- |
| `EXECUTABLE_UNIT` | Synchronous read-only binding has a valid self-contained composability manifest |
| `CONDITIONAL_TRANSPORT` | `HttpResourceOperator` is executable only with strict transport fixtures |
| `OPAQUE_RUNTIME` | Effects are not exposed through a controllable composability port |
| `UNSUPPORTED_EXECUTION_MODEL` | Streaming/suspendable execution is discoverable but blocked in v1 |

Discovery never executes the operator. `certificationEligible=true` additionally requires
fingerprintable implementation bytes, formalized runtime state, valid behavioral declarations, and a
bounded `OperatorComposabilityManifest`. A stateless binding satisfies only the runtime-state
condition; it no longer receives certification merely because it has no instance fields. A configured
binding must implement `OperatorRuntimeBindingSnapshotProvider`; the returned bounded credential-free
map is fingerprinted but never returned or persisted. A non-resource binding must also implement
`OperatorComposabilityManifestProvider` and bind a self-contained dependency declaration to a
conformance suite reference and SHA-256 artifact fingerprint. Missing manifests, declared execution
services (`TIME`, `RANDOM`, `UUID`, `IDENTITY`, `FEATURE_FLAG`), generic dependency ports, mutable
global state, or malformed conformance facts fail certification closed in v1 runtime semantics.
`HttpResourceOperator` has a built-in contract that fingerprints its protocol-processing class
closure and the conservative descriptor snapshot.

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

### 4.2.2 Register an immutable test suite

A suite is a reviewed execution manifest, not an inline list of mutable fixtures. Every case carries
an exact fixture id, revision, and full fingerprint; the suite itself freezes the target fingerprint,
case intent, coverage policy, promotion policy, classification, and provenance:

```http
PUT /api/testing/suites/loan-decision-regression
Authorization: Bearer bloge-aneke-demo-token
X-Purpose: TEST_SUITE_WRITE
Content-Type: application/json
```

```json
{
  "schemaVersion": "bloge.testSuiteRegistrationRequest.v1",
  "testSuite": {
    "schemaVersion": "bloge.testSuite.v1",
    "suiteId": "loan-decision-regression",
    "revision": 1,
    "target": {
      "kind": "GRAPH",
      "id": "loanDecisionPolicy",
      "fingerprint": "sha256:<from-target-descriptor>"
    },
    "classification": "INTERNAL",
    "cases": [
      {
        "caseId": "prime-r1",
        "caseType": "GOLDEN",
        "input": {"applicantId": "prime", "requestedAmount": 450000},
        "fixtureBundleRef": {
          "fixtureBundleId": "loan-prime-v1",
          "revision": 1,
          "fingerprint": "sha256:<returned-by-fixture-registration>"
        },
        "tags": ["ci", "release-gate"],
        "metadata": {"requirementId": "RISK-1024"}
      }
    ],
    "coveragePolicy": {
      "minimumCases": 1,
      "requiredCaseTypes": ["GOLDEN"],
      "requiredInvocationSiteIds": ["/root/assembleLoanDecision#PRIMARY"],
      "requiredEdgeTransfers": [],
      "minimumAssertionsPerCase": 1,
      "requireAllFixtureRulesConsumed": true
    },
    "promotionPolicy": {
      "requireAllCasesPassed": true,
      "minimumCertifiableCases": 1,
      "requireTargetCertificationEligible": true
    },
    "metadata": {"owner": "risk-quality"}
  }
}
```

Registration is dependency-closed and fail closed:

- the current target must exactly match the suite target fingerprint;
- every case must resolve an existing fixture in the same verified tenant and environment;
- blank or stale fixture fingerprints are rejected; there is no implicit `latest` lookup;
- suite classification must be at least as restrictive as every fixture classification;
- graph case input must be a JSON object, case ids must be unique, and cases are bounded to 100;
- required case types, minimum case count, and minimum assertion density must already be satisfiable;
- `requireTargetCertificationEligible=true` rejects a target revision with certification gaps;
- `(tenant, environment, suiteId, revision)` is immutable and idempotent for equivalent content.

Coverage uses `invocationSiteId` and explicit source/destination site pairs rather than local
`nodeId` or `edgeId`. The structural coordinate includes graph path and invocation kind, so the same
node name in a root graph, foreach body, and compensation graph cannot collapse into one false hit.

Query an exact revision with a separate reader purpose:

```bash
curl -sS 'http://localhost:8080/api/testing/suites/loan-decision-regression?revision=1' \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_SUITE_READ'
```

### 4.2.3 Execute an exact suite revision

Suite execution accepts neither inline cases nor `latest`. The request binds the exact suite content
and carries a tenant/environment-scoped idempotency key:

```http
POST /api/testing/suites/loan-decision-regression/executions
Authorization: Bearer bloge-aneke-demo-token
X-Purpose: TEST_EXECUTION
Content-Type: application/json
```

```json
{
  "schemaVersion": "bloge.testSuiteExecutionRequest.v1",
  "suiteRef": {
    "suiteId": "loan-decision-regression",
    "revision": 1,
    "fingerprint": "sha256:<returned-by-suite-registration>"
  },
  "clientRequestId": "risk-ci-1842-loan-regression",
  "strategy": "COLLECT_ALL",
  "metadata": {"pipeline": "release-candidate", "buildId": "1842"}
}
```

`COLLECT_ALL` schedules every bounded case. `FAIL_FAST` stops scheduling new cases after the first
non-pass result; it does not cancel the case already running and therefore cannot pretend to undo an
external side effect. The runner:

1. verifies the exact suite fingerprint and current target fingerprint before any case runs;
2. writes a `RUNNING` aggregate checkpoint before the first case and after every child run;
3. executes graph and operator cases through the existing authorized adapters with `FULL` internal
   evidence and only the suite's exact stored fixture reference;
4. validates every child target, fixture, run id, and evidence identity before aggregation;
5. derives invocation-site, edge-transfer, case-type, assertion-density, and required-fixture
   consumption coverage from child evidence rather than author metadata;
6. stores one terminal `bloge.testSuiteRunEvidence.v1` and its canonical fingerprint.

The response links independently persisted child runs without copying their payloads. This
abridged view omits required fields that remain authoritative in the machine schema:

```json
{
  "schemaVersion": "bloge.testSuiteExecutionResponse.v1",
  "suiteRunId": "<server-run-id>",
  "evidenceFingerprint": "sha256:<aggregate-evidence>",
  "evidence": {
    "schemaVersion": "bloge.testSuiteRunEvidence.v1",
    "status": "PASSED",
    "caseResults": [
      {
        "caseId": "prime-r1",
        "status": "PASSED",
        "runId": "<child-test-run-id>",
        "evidenceStatus": "PASSED",
        "evidenceClass": "CERTIFIABLE"
      }
    ],
    "coverage": {
      "status": "SATISFIED",
      "missingInvocationSiteIds": [],
      "missingEdgeTransfers": [],
      "assertionDensityViolations": [],
      "fixtureConsumptionViolations": []
    },
    "promotion": {
      "status": "ELIGIBLE",
      "reasons": [],
      "coverageSatisfied": true,
      "allCasesCompleted": true
    }
  }
}
```

The full wire shape is authoritative in the machine schema. Repeating the same
`clientRequestId` with the same normalized request returns the existing checkpoint or terminal run
without executing another case. Reusing it with different intent returns
`RG.TEST.SUITE_RUN_IDEMPOTENCY_CONFLICT`.

Query the latest durable checkpoint or terminal evidence:

```bash
curl -sS http://localhost:8080/api/testing/suite-executions/<suiteRunId> \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_EXECUTION'
```

Aggregate status is `RUNNING`, `PASSED`, `COMPLETED_WITH_FAILURES`, `PARTIAL`, or
`EVIDENCE_INCOMPLETE`. Coverage failure prevents `PASSED` even when every child assertion passes.
`promotion.status=ELIGIBLE` means only that the server-owned suite policy is satisfied; it is not a
signature, certification, owner approval, ANEKE gate decision, or publication.

Test-kit methods and canvas `Save as governed suite` remain adapter work; direct API users can now
run the authoritative suite asset without rebuilding mutable execution requests case by case.

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

### 4.3.1 Execute a frozen operator binding

Register the fixture through the same fixture endpoint with `target.kind=OPERATOR`, then submit:

```http
POST /api/testing/targets/operators/customer.normalize/executions
Authorization: Bearer bloge-aneke-demo-token
X-Purpose: TEST_EXECUTION
Content-Type: application/json
```

```json
{
  "schemaVersion": "bloge.testOperatorExecutionRequest.v1",
  "target": {
    "kind": "OPERATOR",
    "id": "customer.normalize",
    "fingerprint": "sha256:<from-operator-target-descriptor>"
  },
  "executionPurpose": "OPERATOR_UNIT_TEST",
  "input": {"name": "Ada"},
  "fixtureBundle": null,
  "fixtureBundleRef": {
    "fixtureBundleId": "normalize-contract",
    "revision": 1,
    "fingerprint": "sha256:<returned-by-registration>"
  },
  "verbosity": "FULL",
  "metadata": {"suiteRef": "customer-normalization", "caseRef": "uppercase"}
}
```

The service converts JSON input to the registry-declared Java input type, runs the exact binding as
node `subject`, and returns `bloge.testExecutionResponse.v1`. Stored provenance alone is never enough
for certification: an opaque binding, unformalized configured state, schema waiver, or output-level
resource replacement forces `EXPLORATORY`. `HttpResourceOperator` earns `CERTIFIABLE` only when its
selected resource interactions use strict `boundary=TRANSPORT` protocol responses.

### 4.3.2 Run from Author Canvas

In `/author/`, double-click a node and open `Executable Operator Suite`. `Run Case` and `Run All`
perform the rapid inline path:

1. Resolve `lowering.operatorRef` (or the visual `operatorRef`) and discover the frozen target.
2. Reject `OPAQUE_RUNTIME` and unsupported targets before execution.
3. For a native binding, run real code with a strict node-level `SPY` rule.
4. For a resource visual operator, lower the visual input to `{resourceId, params}`, run
   `httpResource`, and inject the editable `Transport response` only at `TRANSPORT` boundary.
5. Compare native whole output or resource `/payload` against `Expected output`, then show the real
   run id, evidence class, diagnostics, and actual subject output in the table row.

The canvas sends `X-Purpose: TEST_EXECUTION` and obtains authorization headers from a replaceable
host provider. The standalone demo provider uses the test-profile demo identity; a VSCode or embedded
host must inject its own short-lived credential. Testing endpoints exist only in test/staging profiles
and are absent in production.

`Govern + Run` and `Govern All` perform a second, provenance-bearing path. The client canonicalizes
the frozen target, lowered input, fixture and row metadata, derives a bounded content-addressed id,
registers immutable revision 1 through `PUT /api/testing/fixture-bundles/{id}` with
`X-Purpose: TEST_FIXTURE_WRITE`, verifies that the registry returned the same id/revision and a
non-empty authoritative fingerprint, and only then executes with `fixtureBundleRef` under
`X-Purpose: TEST_EXECUTION`. Repeating unchanged row content is idempotent; changing relevant content
produces a different id instead of mutating history. A registry identity mismatch fails before the
execution POST.

`Run*` remains the fast inline `EXPLORATORY` loop. `Govern*` supplies stored provenance, but it does
not promise `CERTIFIABLE`: target composability, strict schema checks and fixture fidelity still
govern the server-authoritative evidence class. `Govern All` currently governs and executes each row
independently; it does not yet register the first-class `bloge.testSuite.v1` revision available at
`/api/testing/suites/{suiteId}`. `Apply Fixture` only
writes the row back to the visual draft's ordinary node fixture and never registers a control-plane
fixture.

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
`bloge-resource-gateway-test-kit` module provides graph/operator target, fixture, and run
projections, a strict
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

- public graph target discovery/execution/batch/query and operator target discovery/micro-graph
  execution APIs, sharing one fixture registry, evidence model, and run store;
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
- operator implementation closure, schema, runtime-state, and resource dependency fingerprints;
  stateless and explicitly snapshot-providing configured bindings can certify, while opaque state
  fails closed.
- Author Canvas `Executable Operator Suite` target discovery, native `SPY`, resource
  `TRANSPORT` lowering, real run/evidence display, content-addressed governed row registration,
  stored-ref execution, registry-identity validation, and opaque-target fail-closed behavior.
- first-class immutable `bloge.testSuite.v1` protocol, dependency-closed registry, independent
  read/write purposes, target/fixture/classification drift checks, JDBC persistence, and capability
  discovery.
- idempotent immutable-suite runner for graph and operator targets, durable per-case checkpoints,
  fail-fast/collect-all scheduling, child evidence identity checks, aggregate structural coverage,
  promotion eligibility verdict, suite-run query, and capability discovery.

Still intentionally outside this increment:

- `REPLAY`, retry-attempt/occurrence selectors, streaming/suspendable controls and evidence, and
  durable-resume plan restoration;
- signed certification, full branch/rule/retry/fallback/compensation semantic coverage, ANEKE
  projection, and mutation testing;
- canvas/test-kit/CI adapters over the suite registry and runner, automatic resume/reconciliation of
  abandoned `RUNNING` checkpoints, and suite-history list/trend APIs;
- deterministic random/UUID/function execution services and deterministic concurrent scheduling;
- a physically separate test-runtime deployment and network policy;
- certification of streaming foreach/loop graphs until their invocation and edge evidence is
  occurrence-addressable and built-in suites exercise it.

Those items remain visible in the two industrial testability evolution plans and must not be inferred
as complete from `executionEndpointEnabled=true`.
