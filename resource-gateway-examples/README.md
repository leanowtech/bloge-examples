# BLOGE Resource Gateway Example

A standalone Spring Boot example demonstrating a production-grade API resource gateway
built on the bloge orchestration engine. Instead of hand-writing one operator per
external API, the gateway uses a single generic `httpResource` operator driven by
declarative `ResourceDescriptor` configurations, bloge expression evaluation for
parameter mapping, and a sealed `ResponseProtocol` hierarchy for vendor-agnostic
success/failure semantics.

> **Standalone project** — this module is intentionally **not** part of the root bloge
> Maven reactor. It depends on bloge artifacts from your local Maven repository and
> is built independently.

---

## Why standalone?

The resource gateway is a full Spring Boot application with its own dependency tree
(Spring Web, Jackson YAML, WireMock). Keeping it outside the reactor avoids coupling
the core build to Spring Boot version management and lets it evolve on its own release
cadence. It also serves as a realistic example of how downstream projects consume bloge
as a library — using `bloge-spring` starter auto-configuration directly with zero
manual runtime wiring.

## Starter integration

This example now uses the `bloge-spring` starter directly. There is no
`excludeName` list and no local runtime `@Configuration` for operator
discovery, DSL loading, or `GraphEngine` creation. It still runs the gateway in
the request-response engine mode; `bloge-durable` is present only because the
starter's property model references durable API types during Spring Boot binding.

Key gateway settings live in `application.yml`:

```yaml
spring.bloge:
  dsl-locations: classpath:bloge/gateway
  engine-mode: request-response
```

If you need gateway-specific engine builder tweaks beyond the
`request-response` preset, declare a `GraphEngineCustomizer` bean in
`GatewayConfiguration` instead of replacing the auto-configured engine.

---

## Architecture overview

The gateway is organised into four layers:

```
Serving Layer        REST controllers (UserDashboardController,
        │            ResourceExecuteController,
        │            AiSearchStreamingController) / SSE endpoints
        │
 Orchestration Layer  7 .bloge DSL graphs — declare API dependencies,
         │            the engine handles topological scheduling + concurrency
        │
Provider Layer       HttpResourceOperator — one generic operator that
        │            resolves a ResourceDescriptor, renders parameters,
        │            delegates to HttpRequestOperator, validates the
        │            response, and extracts the payload
        │
Cross-Cutting Layer  OperatorInterceptor chain — caching, rate limiting,
                     circuit breaking (ordered highest → lowest precedence)
```

**Adding a new external API** requires only a `ResourceDescriptor` configuration entry —
no new Java class.

---

## Public gateway API

### Browser showcase

The resource gateway now ships a static browser showcase at:

```text
http://localhost:8080/examples/gateway
```

The page opens on **Custom Composer**, a drag-and-drop graph builder. Users can
drag operators such as `HTTP Resource`, `Decision Table`, and `Transform` onto
the graph canvas. Resource operators are loaded from the visual operator catalog
as `resource:<resourceId>` virtual operators, so descriptor-backed APIs can be
dragged as schema-aware business operators and lowered back to `httpResource` at
runtime. Users can reposition existing nodes directly on the canvas, edit the
selected operator's properties, bind every schema-declared input field from a
schema-checked source picker or a manual expression, connect output handles to
input handles under schema type constraints, confirm dropped connections through
the server-side visual connection API before mutating the draft, validate
user-provided operator library JSON before importing it into the catalog,
save/load/delete H2-backed graph drafts with
revision-guarded field-level `PATCH` updates, validate and compile the draft through the
server-side visual graph APIs, inspect the
generated BLOGE DSL, run it with JSON context, and see diagnostics, output, graph
highlighting, and the decision-table matrix update together. Node-path bindings
carry both source output port and target input port metadata, so multi-port user
operators are validated against the selected port schemas instead of falling back
to the first declared port. When different input ports expose the same field name,
the draft stores a stable key such as `customer.id` while `targetPort` and
`targetPath` keep the actual schema location unambiguous. Nested object schemas
are expanded into field paths such as `applicant.score`, so imported operator
libraries can expose realistic business payloads without flattening them first.
Array bindings and edges compare item schemas, so `array<string>` cannot be wired
into an input that requires `array<integer>`. Literal `constant` bindings and
`objectTemplate` fields are checked against their target schema too, so fixed
values cannot bypass required nested input types. Operator `configSchema` is
also enforced: the browser inspector renders simple config controls for schema
fields, and the server blocks missing required config, type mismatches, enum
mismatches, and undeclared config fields when `additionalProperties=false`.
Raw secret material is rejected from imported operator libraries and saved graph
drafts; authoring artifacts may store only references such as `secretRef`.
Graph input bindings are schema-aware too: the composer exposes a dedicated
Graph Input Schema editor that accepts a `SchemaEnvelope` or raw JSON Schema,
stores it as the draft `inputSchema`, and keeps Context JSON as only the sample
runtime payload. The browser performs the same basic structural preflight for
blocking schema issues before activating that schema on the canvas, shows the
local schema diagnostics inline, and the source picker offers compatible
`ctx.*` values from the active declared schema.
The server validates the graph input schema with the same structural gate used
for operator port/config schemas, then blocks unknown or type-incompatible
`contextPath` bindings when the draft input schema is strict. Manual
`expression` bindings are not blind escape hatches: server validation checks
referenced `ctx.*` and `node.output.*` paths, and pure reference expressions are
type-checked against the target input schema. Node-path and expression
references also participate in DAG validation and DSL topological ordering, even
when the draft omits a matching visual edge. Output selections are checked
against the selected node's output port schema before compile/run as well, and
the browser composer exposes the output node/path saved into `GraphDraft.output`.
Each catalog operator exposes a server-computed fingerprint, and saved drafts
store per-node `operatorFingerprints`; validation blocks compile/run when a
draft was authored against an older schema/lowering fingerprint than the catalog
currently exposes.
Operator availability is also enforced by policy: imported operator definitions
may declare allowed `tenants`, `namespaces`, and `environments`; the browser
queries the catalog with the current draft scope from the Authoring Scope panel,
and server-side validation blocks validate/compile/run/publish if a hand-edited
draft references an operator outside that scope. Existing draft nodes whose
operator is filtered out by the current scope are shown as unavailable instead
of being silently treated as another operator type.
Stored drafts can be published into immutable visual graph artifacts that freeze
the generated DSL, draft snapshot, operator schema snapshots, fingerprints,
layout, and validation/generation reports for audit or later promotion. Published
artifacts can be run directly from their frozen DSL, so execution no longer
depends on whatever the current operator catalog exposes after publication.
The Drafts panel can also load revision history, preview an old snapshot on the
canvas, and restore it as a new latest revision through the same guarded patch
path.
The built-in `.bloge` scenarios remain available in the left rail and continue
to execute the public gateway endpoints.

To see the decision-table UX, run the default composer graph, edit the `R3`
decision row, or drag an `HTTP Resource` operator onto the canvas to turn the
policy into a resource-backed graph. The browser highlights the executed graph
path, highlights the matched rule row, and renders a decision summary card from
the same `ruleId` returned by the graph output.

Showcase metadata APIs:

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/gateway/examples/scenarios` | List the seven built-in visual scenarios |
| `GET` | `/api/gateway/examples/scenarios/{graphName}` | Load scenario metadata and run recipe |
| `GET` | `/api/gateway/examples/scenarios/{graphName}/diagram` | Load the `bloge.visualLayout.v1` diagram for a scenario |
| `POST` | `/api/gateway/examples/compose/run` | Compile and run submitted DSL with JSON context, returning diagnostics, output, layout, and decision-table metadata |
| `GET` | `/api/visual/operators` | List native, imported, and resource-backed visual operator definitions; supports `tenantId`, `namespace`, and `environment` policy filtering |
| `GET` | `/api/visual/drafts` | List stored visual graph drafts |
| `POST` | `/api/visual/drafts` | Save a new visual graph draft with assigned id and revision |
| `GET` | `/api/visual/drafts/{draftId}` | Load a stored visual graph draft |
| `GET` | `/api/visual/drafts/{draftId}/revisions` | List immutable draft revision snapshots, newest first |
| `GET` | `/api/visual/drafts/{draftId}/revisions/{revision}` | Load one immutable draft revision snapshot |
| `PUT` | `/api/visual/drafts/{draftId}` | Update a stored visual graph draft and increment revision |
| `PATCH` | `/api/visual/drafts/{draftId}` | Apply an `expectedRevision` JSON patch and reject stale edits with `409 CONFLICT` |
| `DELETE` | `/api/visual/drafts/{draftId}` | Delete a stored visual graph draft |
| `POST` | `/api/visual/drafts/validate` | Validate a visual graph draft against operator schemas, typed port edges, and DAG constraints |
| `POST` | `/api/visual/drafts/compile` | Validate a visual graph draft, then lower it to BLOGE DSL |
| `POST` | `/api/visual/connections/check` | Check a proposed source-to-target canvas connection against the same schema and DAG rules used by draft validation |
| `POST` | `/api/visual/drafts/run` | Validate, compile, and execute a transient visual graph draft |
| `POST` | `/api/visual/drafts/{draftId}/run` | Execute a stored visual graph draft with submitted context |
| `POST` | `/api/visual/drafts/{draftId}/publish` | Validate, compile, and publish an immutable visual graph artifact |
| `GET` | `/api/visual/publications` | List immutable visual graph publications |
| `GET` | `/api/visual/publications/{publicationId}` | Load a published visual graph artifact |
| `POST` | `/api/visual/publications/{publicationId}/run` | Execute a published artifact from its frozen DSL |

Visual run requests may pass `outputNode` to inspect a different node than the
draft's saved output selection. In that case the response returns the override
node's full output instead of reusing the saved `output.path`.

### Orchestration endpoints (`UserDashboardController`)

| Method | Path | Graph | Description |
|--------|------|-------|-------------|
| `GET` | `/api/gateway/dashboard/{userId}` | `userDashboard` | Parallel 5-service aggregation |
| `GET` | `/api/gateway/products/{productId}` | `productDetail` | Type-branched product enrichment |
| `GET` | `/api/gateway/orders/{userId}/enriched` | `enrichOrderList` | Foreach order enrichment |
| `GET` | `/api/gateway/credit-score/{userId}` | `creditScore` | Multi-provider degradation |
| `GET` | `/api/gateway/loan-policy/{applicantId}?amount=450000` | `loanDecisionPolicy` | Resource-backed decision-table policy |

All return a `GatewayResponse` wrapper:

```json
{ "success": true, "data": { … }, "error": null, "elapsedMs": 42 }
```

On graph failure the controller returns HTTP 502 with `success: false` and the error
message. Branched graphs may cancel their convergence transform node; the controller
falls back to branch-specific assemble nodes (e.g. `assemblePhysical`, `assembleDigital`,
`assembleGeneric` for product-detail; `assemblePrimary`, `assembleSecondary` for
credit-score).

### Unified execution endpoint (`ResourceExecuteController`)

| Method | Path | Graph | Description |
|--------|------|-------|-------------|
| `POST` | `/api/gateway/resources/execute` | `resourceDispatch` | Execute any registered resource by `resourceId` |

Request headers:

- `X-Tenant-Id` — tenant scope (defaults to `default`)
- `X-Namespace` — namespace scope (defaults to `default`)
- `Authorization` — forwarded unless the request supplies `headerOverrides.Authorization` or `authOverride`

Request body:

```json
{
  "resourceId": "user-service.getProfile",
  "params": { "userId": "user-123" },
  "headerOverrides": { "X-Correlation-Id": "req-42" },
  "authOverride": { "type": "bearer", "token": "override-token" },
  "timeoutOverride": "PT2S"
}
```

Successful responses still use `GatewayResponse`, but `data` contains the full
`HttpResourceOutput` envelope rather than only the extracted payload:

```json
{
  "success": true,
  "data": {
    "resourceId": "user-service.getProfile",
    "statusCode": 200,
    "payload": { "name": "Alice", "tier": "premium" },
    "rawBody": "{\"code\":0,\"data\":{\"name\":\"Alice\",\"tier\":\"premium\"}}",
    "duration": "PT0.015S",
    "success": true
  },
  "error": null,
  "elapsedMs": 19
}
```

Unknown `resourceId` values return HTTP 404; upstream execution / validation failures
return HTTP 502.

### Streaming endpoint (`AiSearchStreamingController`)

| Method | Path | Graph | Description |
|--------|------|-------|-------------|
| `GET` | `/api/gateway/ai/search/stream?q=…` | `aiEnrichedSearch` | SSE-streamed search |

Returns an `SseEmitter` that emits three event types in parallel:

- `meta` — search metadata (query, result count, categories, timestamp)
- `token` — LLM token stream (simulated via `MockLlmTokenStreamingOperator`)
- `citation` — citation frames with relevance scores

The streaming operators are mocks for demonstration purposes.

---

## Admin API

Base path: `/admin/resources`

| Method | Path | Description | Status |
|--------|------|-------------|--------|
| `GET` | `/admin/resources` | List all descriptors | 200 |
| `GET` | `/admin/resources/{resourceId}` | Get single descriptor | 200 / 404 |
| `POST` | `/admin/resources` | Create descriptor | 201 / 409 / 400 |
| `PUT` | `/admin/resources/{resourceId}` | Update descriptor | 200 / 404 / 400 |
| `DELETE` | `/admin/resources/{resourceId}` | Delete descriptor | 204 / 404 |

400 is returned when a descriptor contains an uncompilable bloge expression.

Visual resource design contracts live beside descriptors and provide the
input/output schemas used by the visual operator catalog:

| Method | Path | Description | Status |
|--------|------|-------------|--------|
| `GET` | `/admin/resource-design-contracts` | List all visual resource contracts | 200 |
| `GET` | `/admin/resource-design-contracts/{resourceId}` | Get one visual contract | 200 / 404 |
| `PUT` | `/admin/resource-design-contracts/{resourceId}` | Create or replace a visual contract | 200 / 400 |
| `DELETE` | `/admin/resource-design-contracts/{resourceId}` | Delete a visual contract | 204 |

User-provided visual operator libraries are imported through a separate admin API.
Imported operators join the same `/api/visual/operators` catalog as built-ins and
resource-backed virtual operators. The browser Operator Libraries panel calls
the validate endpoint directly, so authors can inspect an inline structured
diagnostic list before storing a library.

| Method | Path | Description | Status |
|--------|------|-------------|--------|
| `GET` | `/admin/visual-operator-libraries` | List imported operator libraries | 200 |
| `POST` | `/admin/visual-operator-libraries/validate` | Validate an operator library without storing it | 200 |
| `POST` | `/admin/visual-operator-libraries` | Import an operator library | 201 / 400 |
| `GET` | `/admin/visual-operator-libraries/{libraryId}` | Get one imported library | 200 / 404 |
| `PUT` | `/admin/visual-operator-libraries/{libraryId}` | Replace an imported library | 200 / 400 |
| `DELETE` | `/admin/visual-operator-libraries/{libraryId}` | Delete an imported library | 204 |

Create and update run the same validator before storage. The validator rejects
blank `libraryId`, blank or duplicate `operatorRef`, empty libraries, duplicate
port names, unsupported lowering modes, unsupported schema kinds, `required`
fields not declared in `properties`, and array schemas without `items` across
input, output, and config schemas, returning structured visual diagnostics
instead of accepting a library that will fail later on the canvas. Operator
`policy.tenants`, `policy.namespaces`, and `policy.environments` are stored with
the library and enforced when scoped drafts use the operator.

Minimal import example:

```bash
curl -X POST http://localhost:8080/admin/visual-operator-libraries \
  -H 'Content-Type: application/json' \
  -d '{
    "libraryId": "risk-policy",
    "displayName": "Risk policy operators",
    "version": "1.0.0",
    "owner": "risk-team",
    "operators": [{
      "operatorRef": "risk:eligibility",
      "display": {
        "name": "Eligibility",
        "description": "Evaluates a reusable eligibility predicate.",
        "tags": ["risk", "policy"]
      },
      "policy": {
        "tenants": ["demo-tenant"],
        "namespaces": ["local"],
        "environments": ["browser"]
      },
      "source": { "kind": "user-library", "virtual": true },
      "ports": {
        "inputs": [{
          "name": "inputs",
          "required": true,
          "schema": {
            "schema": {
              "type": "object",
              "properties": {
                "score": { "type": "integer" },
                "amount": { "type": "number" }
              },
              "required": ["score", "amount"]
            }
          }
        }],
        "outputs": [{
          "name": "output",
          "required": true,
          "schema": {
            "schema": {
              "type": "object",
              "properties": {
                "eligible": { "type": "boolean" },
                "ruleId": { "type": "string" }
              }
            }
          }
        }]
      },
      "lowering": {
        "mode": "transform",
        "operatorRef": "transform",
        "parameters": {
          "assignments": {
            "eligible": "{{input.score}} >= 700 && {{input.amount}} <= 300000",
            "ruleId": "\"ELIGIBILITY_V1\""
          }
        }
      }
    }]
  }'
```

---

## Orchestration graphs

Seven `.bloge` graphs live in `src/main/resources/bloge/gateway/`:

| Graph file | Pattern | Description |
|------------|---------|-------------|
| `user-dashboard.bloge` | Parallel fan-out | Fetches profile, orders, recommendations, wallet, and notifications concurrently; each node has independent timeout/retry/fallback settings |
| `loan-decision-policy.bloge` | Decision-table policy matrix | Fetches applicant risk facts, evaluates a `hit=unique` loan policy table, and returns the matched rule id |
| `product-detail.bloge` | Conditional branching | Fetches base product then branches on type (`physical` → shipping, `digital` → license, `otherwise` → generic) |
| `enrich-order-list.bloge` | Foreach enrichment | Fetches the order list then enriches each order with shipping + invoice data in parallel |
| `credit-score.bloge` | Provider degradation | Tries the primary credit provider; falls back to a secondary provider on failure |
| `resource-dispatch.bloge` | Generic single-node dispatch | Executes any registry-backed resource by `resourceId`, optional header/auth overrides, and timeout |
| `ai-enriched-search.bloge` | Mixed streaming | Three streaming operators (meta, LLM tokens, citations) executing concurrently |

---

## Implementation summary

### Resource model (`gateway.resource`)

| Type | Role |
|------|------|
| `ResourceDescriptor` | Immutable record — URL template, HTTP method, auth strategy, timeout, parameter mapping, response protocol, payload path |
| `ResponseProtocol` | Sealed interface: `HttpStatus`, `BodyCode`, `BodyFlag`, `StatusCodes`, `BlgeExpression` |
| `ParameterMapping` | Maps bloge expressions to URL path variables, query parameters, and request body |
| `ResourceRegistry` | Read-only lookup interface |
| `WritableResourceRegistry` | Mutable extension — register, update, deregister at runtime |

### Visual authoring (`gateway.visual`)

| Type | Role |
|------|------|
| `ResourceDesignContract` | Schema contract that turns a resource descriptor into a canvas-ready operator |
| `OperatorLibrary` | User-provided operator catalog bundle with schema-aware `OperatorDefinition` entries |
| `DatabaseOperatorLibraryRegistry` | H2-backed user operator-library registry, so imported operator catalogs survive restart |
| `DefaultVisualOperatorCatalog` | Combines native visual operators with `resource:<resourceId>` virtual operators |
| `GraphDraft` | Editable canvas graph model: input schema, nodes, port-aware bindings, edges, layout, output selection, and operator fingerprint snapshots |
| `DatabaseGraphDraftRepository` | H2-backed graph draft repository with revision assignment, immutable revision history, and expected-revision guarded updates |
| `GraphDraftValidator` | Validates operator references, operator fingerprint drift, operator scope policy, graph input `contextPath` bindings, literal constants, expression references, required schema inputs, node config against `configSchema`, port-aware node bindings, typed port edges, DAG shape, and output schema selection |
| `VisualConnectionCheckService` | Reuses draft validation to accept or reject one proposed canvas edge before the browser writes a binding |
| `GraphDraftDslGenerator` | Lowers visual drafts into executable BLOGE DSL |
| `VisualGraphRunService` | Reuses the dynamic BLOGE runner to validate, compile, and execute visual drafts |
| `VisualGraphPublication` | Immutable published visual graph artifact with DSL, draft, operator schema snapshots, fingerprints, layout, and validation reports |
| `DatabaseVisualGraphPublicationRepository` | H2-backed immutable publication repository |

### Expression evaluator (`gateway.expression`)

`BlgeExpressionEvaluator` — compiles bloge DSL expressions via `GraphLoader`, caches
compiled graphs, and evaluates them against arbitrary data contexts. Used by
`ResponseValidator` and `HttpResourceOperator`.

### Operator layer (`gateway.operator`)

| Type | Role |
|------|------|
| `HttpResourceOperator` | `@BlogeOperator("httpResource")` — resolves descriptor, normalizes DSL-assembled map input into `HttpResourceInput`, evaluates parameter expressions, renders URL, delegates to `HttpRequestOperator`, validates response, extracts payload |
| `HttpResourceInput` / `HttpResourceOutput` | Typed I/O records |
| `ResponseValidator` | Validates HTTP responses against a `ResponseProtocol` |
| `PayloadExtractor` | Extracts nested values via dot-notation paths |
| `UrlTemplateRenderer` | Replaces `{placeholder}` segments in URL templates |

### Mock streaming operators (`gateway.operator.streaming`)

| Type | Role |
|------|------|
| `MockLlmTokenStreamingOperator` | Emits simulated LLM tokens with 5 ms inter-token delay |
| `MockMetaStreamingOperator` | Emits a single metadata frame (query, totalResults, categories, timestamp) |
| `MockCitationStreamingOperator` | Emits three fixed citations with relevance scores |

### Exception types (`gateway.exception`)

- `ResourceNotFoundException` — unknown resource ID (not retryable)
- `ResourceDescriptorException` — invalid descriptor or uncompilable expression (not retryable)
- `ResourceCallException` — remote business-level failure (generally not retryable)
- `CircuitOpenException` — circuit breaker open (wait for half-open)
- `TenantRateLimitException` — tenant quota exceeded (wait for window reset)
- `ProviderCapacityException` — upstream 503-class failure (retryable with backoff)

### Persistence & admin (`gateway.resource`, `gateway.visual`)

| Type | Role |
|------|------|
| `DatabaseResourceRegistry` | `WritableResourceRegistry` backed by H2 via JDBC with an in-memory `ConcurrentHashMap` cache for hot-path reads |
| `DatabaseOperatorLibraryRegistry` | Persists imported visual operator libraries in H2 as JSON blobs with cache-backed reads |
| `DatabaseGraphDraftRepository` | Persists visual graph drafts and revision numbers in H2 |
| `ResourceRegistryAdminController` | REST CRUD at `/admin/resources` |
| `OperatorLibraryAdminController` | REST import/update/delete at `/admin/visual-operator-libraries` |

### Interceptor chain (`gateway.interceptor`)

Wired by `@Order` — highest precedence first:

| Order | Type | Role |
|-------|------|------|
| 1 | `ResponseCacheInterceptor` | TTL-based transparent response caching |
| 2 | `TenantRateLimiterInterceptor` | Multi-tenant rate limiting with two-level token buckets |
| 3 | `CircuitBreakerInterceptor` | Provider-scoped circuit breaking (CLOSED → OPEN → HALF_OPEN) |

`QuotaConfigProvider` supplies per-tenant quota configuration to the rate limiter.

### Streaming infrastructure (`gateway.streaming`)

| Type | Role |
|------|------|
| `SseBridgedStreamingOperator` | Bridges bloge streaming operator output into SSE events |
| `SseStreamingFacade` | High-level facade for SSE streaming aggregation (5-minute timeout) |
| `TapNodeChannel` | Channel abstraction that taps into graph node output |

### Serving & bootstrap (`gateway.gateway`)

| Type | Role |
|------|------|
| `UserDashboardController` | Orchestration endpoints for dashboard, products, orders, credit-score, loan-policy |
| `ResourceExecuteController` | Unified `resourceId` execution endpoint backed by `resourceDispatch` |
| `AiSearchStreamingController` | SSE streaming endpoint for AI search |
| `VisualOperatorCatalogController` / `VisualGraphDraftController` | Visual operator discovery, draft validation, compilation, and execution |
| `GatewayGraphService` | Shared graph-loading and execution service |
| `GatewayResponse` | Uniform JSON response wrapper record |
| `ResourceExecuteRequest` | Request DTO for unified resource dispatch (params, header/auth overrides, timeout) |
| `GatewayProperties` | `@ConfigurationProperties(prefix = "gateway")` — `baseUrl`, `seedDescriptors` |
| `ResourceDescriptorBootstrap` | Seeds 12 example descriptors on startup (idempotent, gated by `gateway.seed-descriptors`) |

### Other

| Type | Role |
|------|------|
| `TenantMdcCarrier` | Propagates `tenantId` through MDC for structured logging |
| `GatewayConfiguration` | `@Configuration` — Jackson, operators, registry, interceptor wiring |

### Planned (not yet implemented)

- `TracingInterceptor`, `MetricsInterceptor` — remaining cross-cutting interceptors

---

## Runtime configuration

| Property | Default | Description |
|----------|---------|-------------|
| `server.port` | `8080` | HTTP server port |
| `spring.datasource.url` | `jdbc:h2:file:./target/bloge-resource-gateway;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false` | H2 file-backed database |
| `spring.h2.console.enabled` | `true` | H2 web console at `/h2-console` |
| `spring.bloge.dsl-locations` | `classpath:bloge/gateway` | Graph source directory |
| `gateway.base-url` | `http://localhost:${server.port}/demo-upstream` | Base URL for seeded resource descriptor endpoints; defaults to the built-in demo upstream so local curls succeed |
| `gateway.seed-descriptors` | `true` | Auto-register and refresh the built-in demo descriptors on startup |

The test profile (`application-test.yml`) switches to an in-memory H2
(`jdbc:h2:mem:testdb`), a random server port, and redirects `gateway.base-url` to the
WireMock port.

### Seeded resource descriptors

On startup (when `gateway.seed-descriptors=true`) the bootstrap registers and re-syncs
11 built-in descriptors covering all five `ResponseProtocol` variants. By default they
point at the application's built-in `/demo-upstream` controllers, so the README curls
work in a fresh local run even if an older H2 file already exists:

`user-service.getProfile`, `order-service.listOrders`,
`recommendation-service.forUser`, `wallet-service.getBalance`,
`notification-service.unread`, `catalog-service.getProduct`,
`logistics-service.getShipping`, `license-service.getLicense`,
`invoice-service.getInvoice`, `credit-provider.primary`,
`credit-provider.secondary`

---

## Build & run

### 1. Install bloge artifacts into your local Maven repository

From the **root** of the bloge repository:

```bash
mvn -pl bloge-core,bloge-dsl,bloge-common-operators,bloge-spring,bloge-test \
    -am install -DskipTests -Dspotbugs.skip=true
```

### 2. Compile and test

```bash
cd bloge-examples-resource-gateway
mvn clean verify
```

### 3. Run the application

```bash
cd bloge-examples-resource-gateway
mvn spring-boot:run
```

The module now pins Spring Boot `3.5.13`. The Spring Boot `3.5.x` line is the first
whose official system requirements include Java 25. The `spring-boot-maven-plugin` is also configured
with `--enable-preview`, so a plain `mvn spring-boot:run` is enough on Java 25.

The gateway starts on `http://localhost:8080`.

By default the seeded descriptors target the built-in demo upstream at
`http://localhost:8080/demo-upstream`, so the graph-backed curl examples below return 200
without any extra setup.

If you want the seeded descriptors to point somewhere other than the built-in demo upstream,
override `gateway.base-url` at launch time:

```bash
mvn spring-boot:run \
  -Dspring-boot.run.arguments=--gateway.base-url=http://localhost:9091
```

### 4. Curl cookbook

#### Self-contained local flows (no extra upstream services required)

Inspect the seeded descriptor registry:

```bash
curl http://localhost:8080/admin/resources
curl http://localhost:8080/admin/resources/user-service.getProfile
```

Register a loopback descriptor that calls the gateway's own admin API, then inspect it:

```bash
curl -X POST http://localhost:8080/admin/resources \
  -H 'Content-Type: application/json' \
  -d '{
    "resourceId": "gateway.self.getResource",
    "urlTemplate": "http://localhost:8080/admin/resources/{resourceId}",
    "method": "GET",
    "defaultHeaders": {
      "Accept": "application/json"
    },
    "defaultTimeout": "PT5S",
    "parameterMapping": {
      "pathExpressions": {
        "resourceId": "ctx.params.resourceId"
      },
      "queryExpressions": {}
    },
    "responseProtocol": {
      "type": "httpStatus"
    }
  }'
curl http://localhost:8080/admin/resources/gateway.self.getResource
```

Execute that loopback descriptor through the generic resource-dispatch API. This returns
the standard execution envelope (`resourceId`, `statusCode`, `success`, `payload`,
`rawBody`, `duration`) without needing any external service:

```bash
curl -X POST http://localhost:8080/api/gateway/resources/execute \
  -H 'Content-Type: application/json' \
  -H 'X-Tenant-Id: demo-tenant' \
  -H 'X-Namespace: local' \
  -d '{
    "resourceId": "gateway.self.getResource",
    "params": {
      "resourceId": "user-service.getProfile"
    },
    "headerOverrides": {
      "Accept": "application/json"
    },
    "timeoutOverride": "PT2S"
  }'
```

Update and delete the loopback descriptor:

```bash
curl -X PUT http://localhost:8080/admin/resources/gateway.self.getResource \
  -H 'Content-Type: application/json' \
  -d '{
    "resourceId": "gateway.self.getResource",
    "urlTemplate": "http://localhost:8080/admin/resources/{resourceId}",
    "method": "GET",
    "defaultHeaders": {
      "Accept": "application/json",
      "X-Readme-Demo": "true"
    },
    "defaultTimeout": "PT10S",
    "parameterMapping": {
      "pathExpressions": {
        "resourceId": "ctx.params.resourceId"
      },
      "queryExpressions": {}
    },
    "responseProtocol": {
      "type": "httpStatus"
    }
  }'
curl -X DELETE http://localhost:8080/admin/resources/gateway.self.getResource
```

Watch the mock AI graph stream over SSE:

```bash
curl -N "http://localhost:8080/api/gateway/ai/search/stream?q=hello"
```

#### Graph-backed gateway flows (work out of the box with the built-in demo upstream)

The seeded orchestration graphs call APIs rooted at `gateway.base-url`. By default that is
the built-in demo upstream, so these curls succeed in a fresh local run. Override
`gateway.base-url` if you want the same graphs to call a real external service instead:

```bash
curl http://localhost:8080/api/gateway/dashboard/u1
curl http://localhost:8080/api/gateway/products/p1
curl http://localhost:8080/api/gateway/orders/u1/enriched
curl http://localhost:8080/api/gateway/credit-score/u1
curl "http://localhost:8080/api/gateway/loan-policy/prime?amount=450000"
```

The unified resource-execute endpoint is useful when you want one-off calls with
tenant scoping, namespace scoping, forwarded authorization, and per-request overrides:

```bash
curl -X POST http://localhost:8080/api/gateway/resources/execute \
  -H 'Content-Type: application/json' \
  -H 'X-Tenant-Id: acme-corp' \
  -H 'X-Namespace: prod' \
  -H 'Authorization: Bearer demo-token' \
  -d '{
    "resourceId": "user-service.getProfile",
    "params": {
      "userId": "u1"
    },
    "headerOverrides": {
      "Accept": "application/json",
      "X-Debug-Trace": "readme-demo"
    },
    "timeoutOverride": "PT3S"
  }'
```

---

## Test strategy

The test suite is organised into four layers (18 top-level test classes, 124 executed
tests, including nested JUnit suites):

### Layer 1 — Unit tests

Pure-logic tests with no Spring context.

| Class | Tests | Scope |
|-------|-------|-------|
| `BlgeExpressionEvaluatorTest` | 21 | Expression compilation, evaluation, caching |

### Layer 2 — Contract / component tests

Isolated component tests, some with lightweight Spring slices or mocks.

| Class | Tests | Scope |
|-------|-------|-------|
| `HttpResourceOperatorTest` | 11 | Descriptor resolution, parameter mapping, URL rendering, DSL map-input normalization |
| `ResponseValidatorTest` | 22 | All five `ResponseProtocol` variants |
| `ResponseCacheInterceptorTest` | 4 | Cache hit/miss, TTL expiry |
| `TenantRateLimiterInterceptorTest` | 3 | Token bucket, quota enforcement |
| `CircuitBreakerInterceptorTest` | 5 | State transitions, cool-down |
| `DatabaseResourceRegistryTest` | 11 | CRUD, H2 persistence, in-memory cache |
| `ResourceDescriptorBootstrapTest` | 7 | Seeding, refresh behavior, idempotency |
| `GatewayDslCompilationTest` | 7 | DSL parsing, graph loading |
| `Visual*Test` | 102 | Visual operator projection, imported libraries, catalog policy filtering, draft/publication persistence and history, revision-guarded patching, typed connection/edge validation, graph input schema gates, secret blocking, DSL lowering, runtime smoke path |

### Layer 3 — Orchestration tests

Per-graph tests that compile and execute each `.bloge` graph against mock operators.

| Class | Tests | Graph |
|-------|-------|-------|
| `UserDashboardGraphTest` | 2 | Parallel fan-out |
| `ProductDetailGraphTest` | 3 | Conditional branching |
| `EnrichOrderListGraphTest` | 1 | Foreach enrichment |
| `CreditScoreGraphTest` | 2 | Provider degradation |
| `AiEnrichedSearchGraphTest` | 2 | Streaming aggregation |

### Layer 4 — Integration tests

Spring Boot smoke coverage for the built-in demo upstream, manual
controller/graph/operator wiring with WireMock-backed upstreams, plus
standalone MockMvc coverage for the admin CRUD API.

| Class | Tests | Scope |
|-------|-------|-------|
| `ResourceRegistryAdminControllerTest` | 8 | Admin CRUD via MockMvc |
| `ResourceGatewayApplicationTest` | 2 | Spring Boot startup + built-in demo-upstream smoke coverage |
| `ResourceExecuteIntegrationTest` | 8 | Unified execute endpoint -> `resourceDispatch` -> `HttpResourceOperator` -> WireMock |
| `GatewayIntegrationTest` | 6 | Controller -> graph -> `HttpResourceOperator` -> WireMock end-to-end execution |

Test resources live under `src/test/resources/`, currently `application-test.yml`
plus `fixtures/` for shared mock payloads. The WireMock stubs are declared
programmatically inside `GatewayIntegrationTest`.

---

## Project layout

```
bloge-examples-resource-gateway/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/leanowtech/bloge/gateway/
    │   │   ├── ResourceGatewayApplication.java
    │   │   ├── carrier/           TenantMdcCarrier
    │   │   ├── config/            GatewayConfiguration
    │   │   ├── demo/              Built-in local upstream endpoints for README curls
    │   │   ├── exception/         6 domain exception types
    │   │   ├── expression/        BlgeExpressionEvaluator
    │   │   ├── gateway/           Controllers, GatewayGraphService, bootstrap, properties
    │   │   ├── interceptor/       Cache / rate-limiter / circuit-breaker + QuotaConfigProvider
    │   │   ├── operator/          HttpResourceOperator + helpers
    │   │   │   └── streaming/     3 mock streaming operators
    │   │   ├── resource/          Descriptor model, registries, admin controller
    │   │   ├── streaming/         SSE facade, bridged operator, TapNodeChannel
    │   │   └── visual/            Visual operator catalog, contracts, drafts, validation, DSL lowering
    │   └── resources/
    │       ├── application.yml
    │       └── bloge/gateway/     7 orchestration graphs (.bloge)
    └── test/
        ├── java/com/leanowtech/bloge/gateway/
        │   ├── ResourceGatewayApplicationTest
        │   ├── expression/        BlgeExpressionEvaluatorTest
        │   ├── gateway/           GatewayDslCompilationTest, ResourceDescriptorBootstrapTest
        │   ├── integration/       GatewayIntegrationTest (WireMock)
        │   ├── interceptor/       3 interceptor tests
        │   ├── operator/          HttpResourceOperatorTest, ResponseValidatorTest
        │   ├── orchestration/     5 per-graph tests
        │   └── resource/          DatabaseResourceRegistryTest, AdminControllerTest
        └── resources/
            ├── application-test.yml
            └── fixtures/          Mock JSON payloads reused by unit + integration tests
```

---

## Key design decisions

1. **One operator, many APIs** — `HttpResourceOperator` replaces per-API operator classes.
   Adding a new external API is a configuration change, not a code change.

2. **Sealed `ResponseProtocol`** — Java 25 exhaustive `switch` guarantees every protocol
   variant is handled; four structural variants cover ~85% of real-world vendor APIs,
   `BlgeExpression` covers the rest.

3. **Bloge expressions everywhere** — parameter mapping, response validation, and payload
   extraction all use the same bloge expression engine, so improvements to built-in
   functions benefit every layer simultaneously.

4. **Separation by concern, not by API** — the four-layer split means changing orchestration
   logic (`.bloge` graph) never touches operator code, and changing vendor auth never
   touches the graph.

5. **Branch-safe controller fallback** — branched graphs (product-detail, credit-score) may
   cancel their convergence transform node at runtime; controllers fall back to
   branch-specific assemble nodes to extract results.

---

## Known non-goals

- **No production upstream integrations** — out of the box the example talks to its own
  built-in demo upstream, and tests redirect that traffic to WireMock.
- **No authentication/authorisation** — endpoints are unauthenticated for demo simplicity.
- **No distributed tracing or metrics** — `TracingInterceptor` and `MetricsInterceptor`
  are not implemented; add them to the interceptor chain if needed.
