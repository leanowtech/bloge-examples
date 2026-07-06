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
| `POST http://localhost:8080/api/gateway/graphs/contracts/tests/run` | Run schema-gated mock/table contract suites |
| `POST http://localhost:8080/api/gateway/graphs/contracts/tests/suites/run-all` | Run every stored contract suite with coverage policy checks |

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

## Notice The Product Loop

1. **Import or use a resource contract**: seeded resources such as
   `user-service.getProfile`, `order-service.listOrders`, and
   `credit-provider.primary` all run through `HttpResourceOperator`.
2. **Compose the business flow**: connect descriptors, transforms, decisions,
   subgraphs, and design-only operators under JSON Schema constraints.
3. **Prove and promote it**: validate, simulate with mock or real evidence,
   publish reusable graph products, and protect them with golden cases.

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
| Controls | Response cache, tenant rate limiting, circuit breaking, and tenant context |

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
`/api/gateway/graphs/contracts/tests/run` lets integrators run table-driven
suites against the real graph with downstream APIs replaced by deterministic
resource mocks. Stored suites are available under
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
3. Validate, import, drag operators, wire schemas, simulate, and export.

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
- Multi-user collaboration and production IAM are outside this example.
- The visual core still lives inside `resource-gateway-examples`; it is shaped
  for future extraction, but is not yet a standalone artifact.

For full endpoint catalogs, implementation notes, tests, and historical detail,
use [REFERENCE.md](REFERENCE.md). For the visual canvas product guide, use
[docs/bloge-visual-canvas-product-and-system-guide.md](../docs/bloge-visual-canvas-product-and-system-guide.md).
