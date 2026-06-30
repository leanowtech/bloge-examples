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
input handles under schema type constraints, confirm dropped connections and
input/config source-picker selections through the server-side visual connection API before
mutating the draft, validate
user-provided operator library JSON before importing it into the catalog, opt
into `force=true` for explicit destructive operator-library replacement or
deletion after inspecting impact diagnostics,
save/load/delete H2-backed graph drafts with
revision-guarded field-level `PATCH` updates and per-revision audit metadata,
validate and compile the draft through the server-side visual graph APIs, inspect the
generated BLOGE DSL, run it with JSON context, and see diagnostics, output, graph
highlighting, and the decision-table matrix update together. Node-path bindings
carry both source output port and target input port metadata, so multi-port user
operators are validated against the selected port schemas instead of falling back
to the first declared port. When different input ports expose the same field name,
the draft stores a stable key such as `customer.id` while `targetPort` and
`targetPath` keep the actual schema location unambiguous. Nested object schemas
are expanded into field paths such as `applicant.score`, so imported operator
libraries can expose realistic business payloads without flattening them first.
The browser also exposes whole-port root handles for user operators and graph
input `ctx`, allowing a compatible business object to be dragged as one binding
while the server still proves the nested required fields and target types.
Schema-declared config fields are canvas targets too: dragging an upstream
output to a `configSchema` handle writes the same expression-backed config value
as the inspector picker, runs the same server preflight, and keeps the visual
dependency visible without persisting config edges as executable data edges.
Object bindings and edges compare required nested fields, so an applicant object
without required `tier` cannot feed an input requiring `applicant.tier`, even
when `tier` exists only as an optional source field. Array bindings
and edges compare item schemas, so `array<string>` cannot be wired into an input
that requires `array<integer>`. Enum value domains are checked too: an output
constrained to `LOW|HIGH` cannot feed an input constrained to `APPROVE|REJECT`,
and an unconstrained string cannot feed an enum input without an explicit
transform. Literal `constant` bindings and `objectTemplate` fields are checked
against their target schema too, so fixed values cannot bypass required nested
input types; an `objectTemplate` for `applicant` must recursively provide
`applicant.score` before it satisfies that required nested input. Unsupported
input binding kinds are rejected before compile/run, so
hand-edited drafts cannot fall through to DSL literal lowering and bypass the
target schema gate. Operator `configSchema` is
also enforced: the browser inspector renders literal/source controls for schema
leaf fields, including nested object paths such as `limits.threshold`, and the
server blocks missing required config, type mismatches, enum mismatches, and
undeclared config fields when `additionalProperties=false`. The inspector can
switch a config field from a literal value to a source-backed expression using
the same compatible `ctx.*` and upstream output picker used by input bindings,
and those picker selections are preflighted by the same server schema gate
before the draft mutates. Nested config picker previews are written as real
object paths rather than flat dotted keys, so sibling config such as
`limits.mode` is preserved while `limits.threshold` is tested, and saved drafts
validate those nested config expressions against the same configSchema path.
Structured config expressions such as `{ "kind": "expression",
"expr": "ctx.threshold" }` are allowed without pretending to be literals; pure
`ctx.*` or `node.output.*` references are checked against the target
`configSchema` type when it can be proven, and the DSL preview/codegen lowers
those structured expressions back to plain BLOGE DSL expressions.
Imported operator libraries must use namespace-safe `operatorRef` values and
single-token input/output port names, so palette keys, canvas endpoints, and DSL
paths share one address model. Library lifecycle status is explicit:
`ACTIVE` libraries enter the authoring catalog, `DEPRECATED` libraries are hidden
unless `/api/visual/operators?includeDeprecated=true` is used while remaining
resolvable for stored drafts, and `DISABLED` libraries remain stored for
audit/admin workflows but never enter the public canvas catalog. When a loaded
draft still references a deprecated operator, the browser fetches its schema as a
node-only spec so existing bindings stay schema-aware without re-adding that
operator to the drag palette.
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
for operator port/config schemas, including strict object `properties`,
`additionalProperties`, `required`, and enum value-domain shape checks, then
blocks unknown or type-incompatible `contextPath` bindings when the draft input
schema is strict. Manual
`expression` bindings are not blind escape hatches: server validation checks
referenced `ctx.*` and `node.output.*` paths, and pure reference expressions are
type-checked against the target input schema. Data edges must have unique ids
and unique source/target connection signatures, match a real semantic dependency
such as a node-path binding or config expression reference, and node-path
bindings must be represented by a data edge in stored drafts, so the line shown
on the canvas cannot silently diverge from what the DSL generator executes.
The browser treats repeated attempts to draw an already-applied data connection
as an idempotent no-op instead of sending noisy duplicate edits to the server.
Expression references in node inputs and executable config also
participate in DAG validation and DSL topological ordering even when they are
not represented by a direct edge. Output selections are checked
against the selected node's output port schema before compile/run as well, and
the browser composer exposes the output node/path saved into `GraphDraft.output`.
Each catalog operator exposes a server-computed fingerprint, and saved drafts
store per-node `operatorFingerprints`; compile/run/publish require executable
drafts to carry a fingerprint snapshot, and validation checks snapshots for
coverage and drift so a draft authored against an older schema/lowering
fingerprint is blocked before execution. Full `PUT` saves, field-level `PATCH`
updates, guarded stored runs, guarded deletes, and guarded publish requests all
use the draft revision observed by the caller; stale edits, runs, deletes, or
publishes return `409 CONFLICT` with `visual.draft.revisionConflict`
diagnostics instead of overwriting newer canvas state, executing a newer graph
than the caller saw, removing somebody else's newer draft revision, or
publishing a draft revision the user did not see. `POST` creates a new draft
identity even when the request body carries a stale or existing `draftId`, so it
cannot be used as an unguarded overwrite path. The browser loads the current
server snapshot before patching when its local base revision is missing, stops
instead of saving if that snapshot proves the draft changed on the server, and
excludes service-managed identity, revision, audit, and fingerprint fields from
field-level patch diffs. Browser Delete sends the current draft revision as a
query precondition, and Browser Publish first saves the current canvas through
the same guarded path, then sends the saved revision as the publication
precondition. Existing node fingerprint snapshots are
service-managed: PATCH rejects direct edits to identity, revision metadata, and
`operatorFingerprints`, and both save paths preserve the repository snapshot for
existing nodes while only filling missing entries for new nodes from the active
catalog for the draft's current authoring scope. Malformed patch entries,
including missing operations, return structured visual diagnostics instead of
escaping as server errors. Routine metadata edits or full-form saves therefore
cannot silently rebase a draft onto a newer operator schema or turn a newly
hand-injected deprecated/out-of-scope operator into an executable node.
Operator availability is also enforced by policy: imported operator definitions
may declare allowed `tenants`, `namespaces`, and `environments`; the browser
queries the active catalog with the current draft scope from the Authoring Scope
panel, then fetches deprecated specs only for operator refs already present in
the current draft. Server-side validation blocks validate/compile/run/publish if
a hand-edited draft references an operator outside that scope. Existing draft
nodes whose operator is filtered out by the current scope are shown as
unavailable instead of being silently treated as another operator type.
Stored drafts can be published into immutable visual graph artifacts that freeze
the generated DSL, draft snapshot, operator schema snapshots, fingerprints,
layout, and validation/generation reports for audit or later promotion. Published
artifacts can be run directly from their frozen DSL, so execution no longer
depends on whatever the current operator catalog exposes after publication. The
browser's Publications panel lists these immutable artifacts, refreshes after a
successful publish, and runs the selected artifact with the current Context JSON
without rewriting the draft currently being edited on the canvas.
The Drafts panel can also load revision history, preview an old snapshot on the
canvas, and restore it as a new latest revision through the same guarded patch
path. Each stored revision carries `revisionMetadata` with created/updated
actor, change source, change summary, and touched JSON pointer paths, giving the
example a concrete audit anchor for collaborative authoring and rollback.
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
| `POST` | `/api/visual/drafts` | Save a new visual graph draft with server-assigned id/revision, ignoring submitted draft identity fields |
| `GET` | `/api/visual/drafts/{draftId}` | Load a stored visual graph draft |
| `GET` | `/api/visual/drafts/{draftId}/revisions` | List immutable draft revision snapshots, newest first |
| `GET` | `/api/visual/drafts/{draftId}/revisions/{revision}` | Load one immutable draft revision snapshot |
| `PUT` | `/api/visual/drafts/{draftId}` | Replace a stored visual graph draft when the submitted `revision` matches; stale full saves return `409 CONFLICT` with current draft diagnostics |
| `PATCH` | `/api/visual/drafts/{draftId}` | Apply an `expectedRevision` JSON patch, reject stale edits with `409 CONFLICT`, and reject patches to service-managed identity/revision/fingerprint fields |
| `DELETE` | `/api/visual/drafts/{draftId}` | Delete a stored visual graph draft; optional `expectedRevision` query parameter rejects stale deletes with `409 CONFLICT` |
| `POST` | `/api/visual/drafts/validate` | Validate a visual graph draft against operator schemas, typed port edges, and DAG constraints |
| `POST` | `/api/visual/drafts/compile` | Validate a visual graph draft, lower it to BLOGE DSL, then compile the DSL |
| `POST` | `/api/visual/connections/check` | Check a proposed source-to-target canvas connection against the same schema and DAG rules used by draft validation |
| `POST` | `/api/visual/drafts/run` | Validate, compile, and execute a transient visual graph draft |
| `POST` | `/api/visual/drafts/{draftId}/run` | Execute a stored visual graph draft with submitted context; optional `expectedRevision` rejects stale runs with `409 CONFLICT` |
| `POST` | `/api/visual/drafts/{draftId}/publish` | Validate, compile, and publish an immutable visual graph artifact; optional `expectedRevision` rejects stale publishes with `409 CONFLICT` |
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

Visual resource design contracts live beside descriptors, are stored in an
H2-backed registry, and provide the input/output schemas used by the visual
operator catalog:

| Method | Path | Description | Status |
|--------|------|-------------|--------|
| `GET` | `/admin/resource-design-contracts` | List all visual resource contracts | 200 |
| `GET` | `/admin/resource-design-contracts/{resourceId}` | Get one visual contract | 200 / 404 |
| `POST` | `/admin/resource-design-contracts/validate` | Validate a visual resource contract without storing it | 200 |
| `PUT` | `/admin/resource-design-contracts/{resourceId}` | Create or replace a visual contract | 200 / 400 |
| `DELETE` | `/admin/resource-design-contracts/{resourceId}` | Delete a visual contract; rejects stored-draft `resource:<resourceId>` references unless `force=true` | 204 / 409 |

Validate and upsert run the same resource-contract validator before storage.
The validator rejects unsupported request/response schema kinds, `required`
fields not declared in `properties`, array schemas without `items`, enum schemas
without values, and raw secret material in contract examples. Built-in bootstrap
contracts pass the same gate, so resource-backed virtual operators do not enter
the visual catalog with weaker schema guarantees than imported user operators.
Bootstrap seeds only missing built-in contracts and does not overwrite a
persisted contract that an author has already customized. Deleting a visual
resource design contract is impact-aware: by default the admin API rejects
deletion with `409 CONFLICT` when any stored draft still uses the corresponding
`resource:<resourceId>` operator, and `force=true` is required for an explicit
destructive removal.

User-provided visual operator libraries are imported through a separate admin API.
Imported operators join the same `/api/visual/operators` catalog as built-ins and
resource-backed virtual operators when their library status is catalog-visible.
The browser Operator Libraries panel calls
the validate endpoint directly, so authors can inspect an inline structured
diagnostic list before storing a library. That validation is registry-aware:
it reports cross-library `operatorRef` ownership conflicts and replacement
impact against stored drafts before an import or replace request mutates storage.
It also emits non-blocking fingerprint drift warnings when a same-`operatorRef`
replacement changes schema- or executable-relevant metadata used by stored
draft snapshots, so authors can review and resave affected drafts before
runtime validation blocks execution. Drafts missing the affected node's
fingerprint snapshot are warned as legacy/unsafe-to-assume-compatible drafts.
The same panel exposes a `Force` switch that passes `force=true` to validate,
import, replace, and delete requests when an author intentionally accepts the
stored-draft impact. When the edited JSON uses an existing `libraryId`, the
browser sends a `PUT` replace request; otherwise it sends a `POST` import. Import
and replace actions run the same validation preflight before mutating storage;
if validation returns only warnings, the panel keeps the request pending,
renders the structured diagnostics, and requires a second click on the same JSON,
same `Force` setting, and same warning diagnostics before it writes the library.

| Method | Path | Description | Status |
|--------|------|-------------|--------|
| `GET` | `/admin/visual-operator-libraries` | List imported operator libraries | 200 |
| `POST` | `/admin/visual-operator-libraries/validate` | Validate an operator library without storing it; use `force=true` to suppress stored-draft removal impact diagnostics; same-ref fingerprint drift is reported as a warning | 200 |
| `POST` | `/admin/visual-operator-libraries` | Import or re-import an operator library; rejects removal of stored-draft operator refs unless `force=true` | 201 / 400 / 409 |
| `GET` | `/admin/visual-operator-libraries/{libraryId}` | Get one imported library | 200 / 404 |
| `PUT` | `/admin/visual-operator-libraries/{libraryId}` | Replace an imported library; rejects removal of stored-draft operator refs unless `force=true` | 200 / 400 / 409 |
| `DELETE` | `/admin/visual-operator-libraries/{libraryId}` | Delete an imported library; rejects stored-draft references unless `force=true` | 204 / 409 |

Create and update run the same validator before storage. The validator rejects
unsupported lifecycle status values, blank `libraryId`, blank or duplicate `operatorRef`, `operatorRef` values already
owned by another stored library, system-reserved refs such as `httpResource`,
`bloge:decisionTable`, `bloge:transform`, and the `resource:` namespace, empty
libraries, duplicate port names, unsupported lowering modes, native lowering
without a namespace-safe executable `operatorRef`, transform lowering without
executable `assignments`, transform assignments that do not match output schema
fields or declared input template references, unsupported schema kinds,
`required` fields not declared in `properties`, and array schemas without
`items` across input, output, and config schemas. Schema `default` values must
also match their declared type/kind, enum domain, required object properties,
array item schema, and `additionalProperties` policy so canvas-generated default
node config cannot start invalid. The browser consumes both root object defaults
and nested field-level defaults from `configSchema` when a node is dragged from
the palette. Invalid libraries return structured visual diagnostics instead of
accepting a library that will fail later on the canvas.
Operator `policy.tenants`, `policy.namespaces`, and `policy.environments` are stored with
the library and enforced when scoped drafts use the operator. `DEPRECATED`
libraries stop appearing in the default palette but continue to resolve for
stored draft validation/compile/run, and the browser keeps their schemas
available only for existing nodes; use `DISABLED` when operators must be removed
from all executable authoring paths. User operator library changes are
impact-aware: by default the admin API rejects deletion, replacement, or
re-import with `409 CONFLICT` when the operation would remove an `operatorRef`
still used by any stored draft, and `force=true` is required for an explicit
destructive removal.
Browser DSL preview and server codegen keep
namespace-safe executable refs intact by quoting native
operator refs that cannot be written as bare BLOGE `IDENT(.IDENT)*` references,
for example `node policy : "risk:legacyPolicy"`. Native input lowering also
groups `targetPort`/nested `targetPath` bindings into object literals, so
schema-shaped inputs such as `applicant.score` compile as
`applicant = { score: ctx.score }` instead of illegal dotted input-field
assignments. Transform lowering also expands template references below a root
port object binding, so `{{input.customer.id}}` can render as `ctx.customer.id`
when the whole `customer` port is bound at once. Native operator `configSchema` values are also lowered as a
business `config` input object while `timeout` and `retryAttempts` stay as
execution config; structured config expressions remain expressions inside that
object. Because the current BLOGE object-literal grammar does not support quoted
field names, native config keys must be DSL-safe field identifiers and codegen
returns `visual.codegen.configKey.invalid` instead of emitting DSL that will
fail later. Operator library validation applies the same DSL-safe field-name
gate to native input/config schemas and transform assignment targets, returning
`visual.operator.lowering.dslField.invalid` before an unsafe library enters the
catalog. Browser connection hints mirror the server's stricter schema rules
for object required-field proof and enum value-domain subsets, while the server
validator remains the publish/run authority. Draft compile and publish calls run
the generated DSL through the BLOGE compiler before returning success, so a user
library with a missing runtime native operator cannot produce a published artifact.

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
| `DatabaseResourceDesignContractRegistry` | H2-backed visual resource contract registry, so resource-backed operator schemas survive restart |
| `ResourceDesignContractValidator` | Blocks invalid resource authoring schemas and raw secret examples before resource contracts enter the virtual operator catalog |
| `OperatorLibrary` | User-provided operator catalog bundle with schema-aware `OperatorDefinition` entries |
| `DatabaseOperatorLibraryRegistry` | H2-backed user operator-library registry, so imported operator catalogs survive restart |
| `DefaultVisualOperatorCatalog` | Combines native visual operators with `resource:<resourceId>` virtual operators |
| `GraphDraft` | Editable canvas graph model: input schema, nodes, port-aware bindings, edges, layout, output selection, and operator fingerprint snapshots |
| `DatabaseGraphDraftRepository` | H2-backed graph draft repository with revision assignment, immutable revision history, and expected-revision guarded updates |
| `GraphDraftValidator` | Validates operator references, operator fingerprint drift, operator scope policy, graph input `contextPath` bindings, binding kind allow-list, literal constants, expression references, required schema inputs, node config against `configSchema`, port-aware node bindings, typed port edges, edge identity/connection uniqueness, data edge/semantic dependency consistency, DAG shape, and output schema selection |
| `VisualConnectionCheckService` | Reuses preview-mode draft validation to accept or reject one proposed canvas edge before the browser writes a binding |
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

The test suite is organised into four layers (40 top-level test classes, 360 executed
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
| Gateway example API suite | 13 | Dynamic composer service/controller, scenario catalog, example graph endpoints |
| Visual authoring suite | 219 | Visual operator projection, resource design contract persistence and gates, resource-contract in-use delete protection, imported libraries, registry-aware and impact-aware library validation, catalog lifecycle gates, deprecated operator draft resolution and active-scope fingerprinting, catalog token gates and policy filtering, cross-library operatorRef ownership, operator-library in-use change protection and same-ref fingerprint drift/missing-snapshot preflight warnings, system-reserved operatorRef gates, import-time lowering gates including DSL-safe field-name gates, schema default value gates, draft/publication persistence and history, revision audit metadata, full-save/PATCH fingerprint preservation, service-managed fingerprint snapshot gates, structured malformed patch diagnostics, server-assigned create identity, revision-guarded full-save, patch, stored-run, delete, and publish conflict handling, operator fingerprint drift preservation and execution snapshot coverage gates, typed connection/edge validation including edge identity uniqueness and binding kind allow-list, input/config source-picker server preflight with duplicate-connection rejection and nested config paths, duplicate target input ownership, root-port object binding, object required fields, object schema structure gates, required-array schema gates, nested objectTemplate required fields, enum value-domain and shape gates, standard JSON Schema config enum gates, nested config expression references and configSchema type gates, native config input lowering and DSL field-key diagnostics, data edge/semantic dependency consistency, graph input schema gates, secret blocking, DSL lowering, compiler gating, dependency ordering, runtime smoke path |

### Layer 3 — Orchestration tests

Per-graph tests that compile and execute each `.bloge` graph against mock operators.

| Class | Tests | Graph |
|-------|-------|-------|
| `UserDashboardGraphTest` | 2 | Parallel fan-out |
| `ProductDetailGraphTest` | 3 | Conditional branching |
| `EnrichOrderListGraphTest` | 1 | Foreach enrichment |
| `CreditScoreGraphTest` | 2 | Provider degradation |
| `LoanDecisionPolicyGraphTest` | 2 | Decision-table policy |
| `AiEnrichedSearchGraphTest` | 2 | Streaming aggregation |

### Layer 4 — Integration tests

Spring Boot smoke coverage for the built-in demo upstream, manual
controller/graph/operator wiring with WireMock-backed upstreams, plus
standalone MockMvc coverage for the admin CRUD API.

| Class | Tests | Scope |
|-------|-------|-------|
| `ResourceRegistryAdminControllerTest` | 8 | Admin CRUD via MockMvc |
| `ResourceGatewayApplicationTest` | 3 | Spring Boot startup + built-in demo-upstream smoke coverage |
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
