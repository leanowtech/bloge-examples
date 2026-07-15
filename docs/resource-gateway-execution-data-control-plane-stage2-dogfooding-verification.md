# Resource Gateway Stage 2 Built-in Graph Dogfooding Verification

> Verified scope: built-in graph contract suites, F3 resource fixtures, stored-suite evidence
> classification, retry cardinality, and fail-closed root resource isolation.

## 1. Acceptance Result

Resource Gateway ships one stored contract suite for every built-in resource graph. The catalog runs
the production DSL and production resource descriptor logic on a fresh test engine; only HTTP
transport is replaced.

| Metric | Result |
| --- | ---: |
| Built-in graphs / suites | 7 / 7 |
| Executable cases | 13 |
| Observed controlled resource nodes | 23 |
| Business assertions | 33 |
| Suite failures in Spring dogfooding | 0 |
| Uncontrolled root HTTP calls | 0 |
| Certifiable graph suites | 6 |
| Explicitly exploratory graph suites | 1 (`enrichOrderList`) |
| Repository gate | 1691 tests, 0 failures, 0 errors, 33 conditional skips |

## 2. Suite Matrix

| Graph | Suite | Cases | Behavior under proof | Evidence |
| --- | --- | ---: | --- | --- |
| `aiEnrichedSearch` | `ai-enriched-search-streams` | 1 | three stream channels materialize | certifiable |
| `creditScore` | `credit-score-provider-routing` | 2 | primary success; two failed attempts then secondary | certifiable |
| `enrichOrderList` | `enrich-order-list-outer-boundary` | 1 | F3 outer resource and empty foreach boundary | exploratory |
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

This proves application-level fail-closed behavior for root graph resource nodes. It does not replace
the Stage 5 requirement for an independent test deployment and deny-by-default network policy.

## 4. Honest Certification Boundary

Resource Gateway now recursively freezes BLOGE nested graph bindings and run-scoped resolution can
control synchronous foreach/loop/subgraph/compensation sites. The remaining certification gap is
evidence: current node/edge trace projection still aggregates by local node id and cannot prove every
parallel occurrence independently. `GraphExecutionTargetSnapshot` therefore continues to mark graphs
containing `FOREACH`, `STREAMING_FOREACH`, `LOOP`, or `STREAMING_LOOP` as certification-ineligible.

The order enrichment suite uses an empty order list so no nested resource is invoked and tags itself
`nested-invocation-gap`. It validates the outer contract but emits `EXPLORATORY`, never
`CERTIFIABLE`, evidence. A non-empty foreach certification case is blocked until the suite is migrated
and node/edge evidence carries structural site plus occurrence coordinates.

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

The second response must report `totalSuites=7`, `totalCases=13`, and `passed=true`.

The recorded repository gate completed on 2026-07-15 with all 1691 tests green, including the real
Chrome authoring regression suite, and produced the executable Spring Boot JAR.
