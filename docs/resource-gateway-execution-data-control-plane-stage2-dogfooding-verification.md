# Resource Gateway Stage 2 Built-in Graph Dogfooding Verification

> Verified scope: built-in graph contract suites, F3 resource fixtures, stored-suite evidence
> classification, retry cardinality, occurrence-addressable nested evidence, and fail-closed
> resource isolation.

## 1. Acceptance Result

Resource Gateway ships one stored contract suite for every built-in resource graph. The catalog runs
the production DSL and production resource descriptor logic on a fresh test engine; only HTTP
transport is replaced.

| Metric | Result |
| --- | ---: |
| Built-in graphs / suites | 7 / 7 |
| Executable cases | 14 |
| Observed controlled resource calls | 28 |
| Business assertions | 37 |
| Suite failures in Spring dogfooding | 0 |
| Uncontrolled HTTP calls | 0 |
| Certifiable graph suites | 7 |
| Explicitly exploratory graph suites | 0 |
| Repository gate | 1709 tests, 0 failures, 0 errors, 2 conditional skips |

## 2. Suite Matrix

| Graph | Suite | Cases | Behavior under proof | Evidence |
| --- | --- | ---: | --- | --- |
| `aiEnrichedSearch` | `ai-enriched-search-streams` | 1 | three stream channels materialize | certifiable |
| `creditScore` | `credit-score-provider-routing` | 2 | primary success; two failed attempts then secondary | certifiable |
| `enrichOrderList` | `enrich-order-list-occurrence-control` | 2 | empty boundary; two parallel items with independent shipping and invoice fixtures | certifiable |
| `loanDecisionPolicy` | `loan-decision-policy-smoke` | 2 | BodyCode extraction and decision rules R1/R4 | certifiable |
| `productDetail` | `product-detail-all-branches` | 3 | physical, digital, and generic branches | certifiable |
| `resourceDispatch` | `resource-dispatch-descriptor-protocols` | 2 | dynamic BodyCode descriptors and typed output contract | certifiable |
| `userDashboard` | `user-dashboard-happy-and-degraded` | 2 | parallel happy path; bounded retry and fallback | certifiable |

Every resource row uses `fixtureMode=TRANSPORT_LEVEL`. `minUses/maxUses` makes retries auditable:
credit primary failure requires exactly two calls, wallet failure two, and notification failure three.
Missing fields in old JSON remain backward compatible as one `OUTPUT_LEVEL` use, which can never
upgrade a run to certifiable evidence.

## 3. No-Egress Proof

`ResourceGatewayApplicationTest` uses the real Spring beans, loaded DSL, operator registry,
descriptor registry, response protocols, and expression evaluator. Before running all suites it
re-seeds every descriptor under `http://127.0.0.1:1/unreachable`.

The batch still passes. Therefore every executed root `httpResource` node was intercepted by an F3
fixture; an escape to the real transport would fail against the unreachable endpoint. The test also
asserts that each executed `httpResource` node observation is `TRANSPORT_LEVEL`.

The non-empty `enrichOrderList` case additionally consumes two shipping and two invoice fixtures
inside the parallel foreach body. This proves application-level fail-closed behavior for the
executed root and synchronous nested resource occurrences. It does not replace the Stage 5
requirement for an independent test deployment and deny-by-default network policy.

## 4. Honest Certification Boundary

Resource Gateway now recursively freezes BLOGE nested graph bindings and run-scoped resolution can
evidence for streaming/suspendable execution. `NodeTrace` now identifies each synchronous execution
by structural `invocationSiteId`, `graphPath`, runtime `correlationKey`, site `occurrence`, and
containing `graphOccurrence`; retries are retained as ordered `attempts` inside that occurrence.
`EdgeTrace` uses the same graph coordinates and both endpoint site ids, so parallel branches and
re-entered nested graphs do not collapse onto local node ids.

`GraphExecutionTargetSnapshot` therefore permits synchronous `FOREACH` and `LOOP` certification but
continues to mark `STREAMING_FOREACH` and `STREAMING_LOOP` certification-ineligible. The order
enrichment suite proves the newly opened boundary with two parallel items and independently asserted
outputs. Streaming/suspendable control and evidence remain explicitly outside this proof.

## 5. Reproduce

Run the focused proof:

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ResourceGatewayApplicationTest,GatewayGraphContractTestServiceTest,VisualSchemaValidatorTest test
```

Run the repository gate:

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
```

With the demo running, inspect and execute the catalog:

```bash
curl -sS http://localhost:8080/api/gateway/graphs/contracts/tests/suites
curl -sS -X POST http://localhost:8080/api/gateway/graphs/contracts/tests/suites/run-all
```

The second response must report `totalSuites=7`, `totalCases=14`, and `passed=true`.

The recorded repository gate completed on 2026-07-15 with all 1709 tests green, including the real
Chrome authoring regression suite, and produced the executable Spring Boot JAR.
