# BLOGE Resource Gateway

**Turn external APIs into schema-checked building blocks for business workflows.**

Resource Gateway is a Spring Boot example that shows how BLOGE can turn messy
provider integrations into visible, reusable orchestration. Instead of writing a
new Java operator for every upstream endpoint, teams describe resources with
`ResourceDescriptor`, compose them in `.bloge` graphs or the visual canvas, and
let the same schema contract drive validation, simulation, runtime execution,
and reuse.

The interesting part is not "calling HTTP". The interesting part is making API
integration something the business flow can see, reason about, test, and change.

## What You Get

| Capability | Why it matters |
| --- | --- |
| Descriptor-first resources | Add most APIs by changing contracts, not cloning Java operators |
| Graph-level contracts | Every built-in resource graph exposes formal input/output JSON Schema for system integration |
| Schema-aware canvas | Drag, connect, validate, simulate, and publish under server-side schema checks |
| Runtime-backed demos | Local upstreams, real gateway execution, mock simulation, SSE examples, and reusable publications |
| Schema-gated table tests | Run real resource graphs with mocked downstream APIs, input/output schema validation, and node-level assertions |
| Governed run controls | Absolute deadline, monotonic remaining-budget propagation, fenced cancel, durable owner lease/epoch, cross-instance commands, and automatic signed evidence recovery after owner failure |
| Auditable external writes | Versioned write contracts, binding/activation conformance, execution-scoped journal, commit receipts, UNKNOWN_COMMIT DAG guard, and signed reconciliation evidence |
| Dynamic workload identity | Atomic JWKS/revocation refresh, zero-restart key rotation, bounded propagation SLO, group/clearance/delegation claims, and explicit 401/503 semantics |
| Operational controls | Cache, tenant rate limit, circuit breaker, run history, golden cases, and publication history |

## Start The Demo

From the repository root:

```bash
./scripts/start-visual-canvas-demo.sh --open
```

This packages the Spring Boot gateway with the React frontend and starts the
demo on `http://localhost:8080`.

| Open | Best first move |
| --- | --- |
| `http://localhost:8080/author/` | Build a schema-constrained graph on the visual canvas |
| `http://localhost:8080/showcase/` | Explore guided product scenarios and sample outputs |
| `http://localhost:8080/examples/gateway` | Use the legacy Custom Composer regression surface |
| `http://localhost:8080/api/gateway/graphs/contracts` | Inspect resource graph input/output contracts |
| `POST http://localhost:8080/api/gateway/graphs/contracts/tests/draft` | Generate editable graph mock/table suites from graph and resource schemas |
| `POST http://localhost:8080/api/gateway/graphs/contracts/tests/run` | Run schema-gated mock/table contract suites |
| `POST http://localhost:8080/api/gateway/graphs/contracts/tests/suites/run-all` | Run every stored contract suite with coverage policy checks |
| `POST http://localhost:8080/api/visual/operators/tests/draft` | Generate editable operator mock/table suites from operator schemas |
| `POST http://localhost:8080/api/visual/operators/tests/suites/run-all` | Run every stored operator schema mock/table suite |

Stop it with:

```bash
./scripts/stop-visual-canvas-demo.sh
```

Useful variants:

```bash
./scripts/start-visual-canvas-demo.sh --port 18080
./scripts/start-visual-canvas-demo.sh --no-build
./scripts/start-visual-canvas-demo.sh --run-tests
```

Batch-migrate existing `.bloge` files after the service is running:

```bash
./scripts/bloge-dsl-batch-import.sh report \
  --base-url http://localhost:8080 \
  --operator-library risk-policy \
  --dsl-dir resource-gateway-examples/src/main/resources/bloge/gateway \
  --out target/dsl-batch-report.json
```

Use `commit --commit-policy renderable|fully-projected|rewrite-allowed` with the
same inputs to save eligible projections as governed visual drafts. The script
does not write source `.bloge` files; source replacement still belongs behind
rewrite gate evidence and a reviewed VCS/source-writer flow.

## Notice The Product Loop

1. **Import or use a resource contract**: seeded resources such as
   `user-service.getProfile`, `order-service.listOrders`, and
   `credit-provider.primary` all run through `HttpResourceOperator`.
2. **Compose the business flow**: connect descriptors, transforms, decisions,
   subgraphs, and design-only operators under JSON Schema constraints.
3. **Prove and promote it**: validate, simulate with mock or real evidence,
   run graph/operator schema table suites, publish reusable graph products, and
   protect them with golden cases.

The showcase covers dashboard aggregation, product enrichment, enriched orders,
credit fallback, loan policy, and SSE search. The full endpoint catalog lives in
[REFERENCE.md](REFERENCE.md).

## Architecture At A Glance

![BLOGE visual canvas architecture](../docs/assets/bloge-visual-canvas-architecture.svg)

| Layer | Responsibility |
| --- | --- |
| Serving | Spring MVC gateway APIs, visual APIs, admin APIs, browser entry points |
| Orchestration | BLOGE DSL graphs schedule dependencies, fan-out, joins, transforms, decisions, and streams |
| Provider | `HttpResourceOperator` resolves descriptors, maps parameters, calls upstreams, validates response protocol, and extracts payload |
| Visual product surface | Catalog import/export, schema checks, draft validation, simulation, publication, golden cases, and run history |
| Controls | Durable run-control state, owner lease/epoch fencing, pre-run evidence reservation, side-effect claim/reconciliation, crash recovery sweeper/outbox, response cache, tenant rate limiting, circuit breaking, and tenant context |

Managed runs reserve `100 ms` by default for terminal-state and evidence finalization. BLOGE propagates the resulting
work budget through `OperatorContext`, scheduler admission, resilience timeout/retry, common HTTP calls, Resource Gateway
resources, and remote-worker envelopes. Override the reserve with
`resource-gateway.run-control.finalization-reserve-ms`; size it from measured evidence-finalization latency rather than
treating it as an operator timeout.

External-write operators declare `bloge.sideEffectProtocol.v1` and call `OperatorContext.beginSideEffect(...)` before
crossing the provider boundary. Missing contracts are DESIGN-only; a managed WRITE that returns without a journal attempt
is rejected. Descriptor-backed POST/PUT/PATCH/DELETE calls additionally require
`resourceGateway.externalWriteContract.v1`, an idempotency key, an evidence-safe lookup reference, and a provider receipt.
The Author palette shows `managed write` or `write protocol required` before a node is used. Provider-specific status
adapters implement `SideEffectReconciler`; Resource Gateway keeps original evidence immutable and appends a separately
signed reconciliation record. See the [product guide](../docs/bloge-visual-canvas-product-and-system-guide.md),
[conformance chain](../docs/assets/resource-gateway-side-effect-conformance-chain.svg), and
[reconciliation lifecycle](../docs/assets/resource-gateway-side-effect-reconciliation-lifecycle.svg).

Enterprise integration credentials can be verified from a live JWKS and versioned revocation feed. Resource Gateway
publishes key and revocation changes as one immutable snapshot, throttles unknown-`kid` refreshes, exposes refresh health
and propagation SLO through `/api/integration/capabilities`, and records organization/delegation facts without storing raw
tokens or group names. Authority outages return retryable 503; deterministically invalid credentials return 401. See the
[dynamic trust lifecycle](../docs/assets/resource-gateway-dynamic-jwks-trust-lifecycle.svg) and
[identity setup guide](../docs/bloge-visual-canvas-product-and-system-guide.md#31-调用-integration-api-前先建立受信身份).

## Extend It

To add an external API:

1. Register a `ResourceDescriptor` through bootstrap config or the admin API.
2. Define parameter mapping, response protocol, and payload schema.
3. Use it as a `resource:<resourceId>` visual operator or lower it to
   `httpResource`.
4. Compose it in a `.bloge` graph or on the visual canvas.

When you add a new built-in `.bloge` graph under `src/main/resources/bloge/gateway`,
also add a `GatewayGraphContract` entry. This is not optional: `GatewayGraphService`
fails startup when a loaded graph has no contract, and
`GatewayGraphContractCatalogTest` scans every gateway `.bloge` file to catch
schema drift in CI. Runtime execution validates each context against the graph
`inputSchema`, and public gateway endpoints resolve terminal output through the
contract `outputNodes` before validating it against `outputSchema`;
`/api/gateway/graphs/contracts` exposes both schemas; and
`/api/gateway/examples/scenarios` mirrors the same schemas into showcase
metadata so the browser can show each example's Graph Contract;
`/api/gateway/graphs/contracts/tests/draft` generates editable mock rows from
the graph input schema and resource response schemas before
`/api/gateway/graphs/contracts/tests/run` executes table-driven suites against
the real graph with downstream APIs replaced by deterministic resource mocks.
Stored suites are available under
`/api/gateway/graphs/contracts/tests/suites`; each suite can carry a coverage
policy so batch runs fail when they lack enough cases, schema validations,
mocked calls, assertions, or required output-node coverage.

For the detailed contract-test design, request format, verification evidence,
and remaining industrialization gaps, see
[Resource Graph Schema Mock Table Testing](../docs/bloge-resource-graph-schema-mock-table-testing.md).

Create a provider-specific Java operator only when the provider behavior cannot
be expressed cleanly as a descriptor-backed resource.

To add a user-supplied visual operator library:

1. Open `/author/`.
2. Paste a `bloge.visualOperatorLibrary.v1` JSON or YAML document that follows
   the [operator library schema guide](../docs/bloge-visual-operator-library-schema.md)
   and [machine schema](../docs/schemas/bloge-visual-operator-library.schema.json).
3. Validate and import it.
4. Use `/api/visual/operators/tests/draft` to generate editable operator mock
   rows from each operator's input/config/output schemas, then save or batch-run
   them through `/api/visual/operators/tests/suites`.
5. Drag operators, wire schemas, simulate, and export.

The `/author/` built-in canvas examples also carry their own graph-level
input/output schemas, and exported drafts include the current `inputSchema` so
the design can be integrated instead of remaining a diagram-only artifact.

## Build And Verify

This is a standalone Maven project. BLOGE artifacts must already exist in the
local Maven repository.

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
mvn -f resource-gateway-examples/pom.xml -Pfrontend package
mvn -f resource-gateway-examples/pom.xml spring-boot:run
```

If BLOGE core artifacts are missing, install them from the main BLOGE repo:

```bash
mvn -pl bloge-core,bloge-dsl,bloge-common-operators,bloge-spring,bloge-test \
    -am install -DskipTests -Dspotbugs.skip=true
```

Java 25 preview flags are already configured.

## Know The Boundary

- Runtime visual execution is request-response; streaming and durable operators
  are visible in catalog readiness but blocked from direct request-response runs.
- Remote worker, AI tool, event source, message handler, and webhook operators
  can be modeled; this example does not ship their production runtime plane.
- Multi-user collaboration and the complete enterprise IAM policy lifecycle remain outside this example. Dynamic
  JWKS/revocation, group/clearance/delegation claims, purpose authorization and tenant isolation are implemented; customer
  IdP certification, resource-classification policy, group lifecycle/orphan ownership and emergency-access governance
  still require deployment-specific integration.
- The visual core still lives inside `resource-gateway-examples`; it is shaped
  for future extraction, but is not yet a standalone artifact.

For full endpoint catalogs, implementation notes, tests, and historical detail,
use [REFERENCE.md](REFERENCE.md). For the visual canvas product guide, use
[docs/bloge-visual-canvas-product-and-system-guide.md](../docs/bloge-visual-canvas-product-and-system-guide.md).
