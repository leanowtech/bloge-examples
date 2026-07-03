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
Visual drafts now preserve streaming and durable operator capabilities in the
catalog contract, but this example's request-response visual runtime blocks
streaming or durable/suspendable nodes before compile, run, or publish.

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
runtime. The palette can filter large catalogs by operator kind, tag, imported
operator library owner, schema field names, port-qualified field names, config
field names, and field types.
Java operators registered in the Spring `OperatorRegistry` also enter
the same catalog from BLOGE metadata, with streaming and suspendable operators
marked as distinct Java source kinds and suspendable operators marked as durable
runtime operators, and run through a typed-input adapter, so
a visual DSL map can execute Java DTO/record operators without forcing authors
to rewrite them as `Map<String,Object>` operators. Java `UnionSchema` metadata
is projected as visual `oneOf` schema, so union-shaped Java operator inputs and
outputs still participate in the same conservative schema-aware connection
checks as imported operator libraries. Published visual graphs enter
the same palette as `publication:<publicationId>` subgraph operators: their
frozen graph input schema becomes the node input port, their saved output
selection becomes the node output port, named output paths such as
`facts.score` resolve through the selected output port instead of degrading to an
opaque subgraph output, whole-node multi-output selections are exposed as an
object keyed by output port name, and runtime lowering injects the
immutable `publicationId` into the hidden `visualPublication` executor. Users can reposition existing
nodes directly on the canvas, duplicate a configured selected node from the inspector or Cmd/Ctrl+D
without copying its edges, delete the selected node from the inspector or Delete/Backspace with the same
impact cleanup path, edit the
selected operator's properties, bind every schema-declared input field from a
compatible-first grouped schema-checked source picker or a manual expression, auto-bind required inputs
only when exactly one schema-compatible source candidate exists, connect output
handles to input handles under schema type constraints, confirm dropped
connections, required-input auto-bindings, and input/config source-picker selections through the server-side visual connection API before
mutating the draft, undo/redo unsaved local graph edits without waiting for a
server revision, search nodes on the active canvas by id, label, operator ref,
binding/config text, or dynamic schema path and jump from node-scoped
diagnostics back to the affected canvas node, use the Server Check diagnostic summary
to group errors by affected node with label/id context, filter/focus or step through repair targets with F8/Shift+F8,
see the current queue position, filtered issue count, hidden-node overflow count, and current repair node even when it falls outside the compact preview, and clear the active repair filter with Esc, inspect validation
diagnostics and active replay trace attribution for the selected node directly in the operator
inspector, inspect that node's upstream context/data/config/order inputs,
downstream consumers, branch routes, and delete impact, clear individual incoming or
downstream input/config/dependency/route relations from that impact view, or
detach all current node references including graph-output selection before
repairing or deleting a node, search the operator palette with multi-term
queries across label, operator ref, description, source kind/resource id, tag,
input/output/config schema fields, port-qualified field names, field types, and
JSON Schema field annotations such as `title`, `description`, `examples`, `default`, and `$comment`, filter it by operator type,
tag, source, imported operator library owner, capability, runtime readiness, or lowering mode for larger imported catalogs, and see the server-provided catalog mix counts for library owners, runtime-executable, design-only, runtime-blocked, governance-review, catalog-repair, streaming/durable, secret-bound, and external-effect operators, inspect each palette card's input/output port and
schema-field summary plus streaming/durable/suspendable/secret/effect capability badges before dragging, inspect the selected node's contract
coverage summary for input/output ports, required binding coverage, and config
field counts plus the server-derived operator runtime readiness contract across runtime-executable, design-only,
runtime-blocked, governance-review, and catalog-repair states, inspect the selected node's output connectability across data,
config, dependency, and route targets with ready/already-wired/blocked schema
status, blocked previews, and blocked-reason hover labels before dragging an edge, create ready connections from that panel through
the same server-side preflight, inspect the selected graph output's source port/path, schema type,
field count, and required-field count before running or publishing, preview a
user-provided operator library's operator count, port inventory, required
bindings, per-operator input/output/config schema field summaries with compact
schema annotation hints, config/output
field counts, dynamic schema surfaces, DSL-unsafe input/output fields and port names,
streaming/durable runtime requirements, external effects, non-idempotent side effects,
secret-bound operators, and scope-restricted policy summaries before importing it into the catalog, validate
user-provided operator library JSON or YAML source text before importing it, while the server-side
validate/import path expands safe local `#/$defs/*` schema references, blocks unresolved local refs with
`visual.schema.refUnresolved`, blocks remote schema refs with `visual.schema.refRemoteUnsupported`, warning-gates streaming/durable runtime requirements,
secret-backed execution, and non-idempotent external effects before storage, and
the browser profile prefers the server-derived `bloge.visualOperatorLibraryProfile.v1`
and `bloge.visualOperatorLibraryImportReadiness.v1` returned by validate/import
so catalog repair, runtime-blocked, governance-review, design-only counts, and
ack/force/evidence gates are not guessed from browser-only heuristics, while
per-operator import-time `runtimeBindingRequirements` show which schema-only,
remote-worker, AI-tool, event/message/webhook, streaming, durable, or unresolved
native operators need runtime-plane binding before executable graph use; those
operator-level requirement kinds, targets, and handoff lane/kind/target routing
metadata, owner `operatorLibraryId`, stable `requirementKey` / `runtimeBindingRequirementKeys`, and
binding/handoff/owner/source/lowering/readiness count maps are derived by the same
server planner later used by graph readiness and workspace runtime-binding
indexes, so large operator-library imports can be routed before authors create
draft graphs,
opt into `force=true` for explicit destructive operator-library replacement or
deletion after inspecting the server-provided impact review for affected drafts,
publications, operators, and diagnostic codes, jump from an affected draft chip
into that draft and focus the affected node, or select an affected publication
chip with the impacted frozen node index for review and recertification, keep
deprecated operator libraries and deprecated resource design contracts hidden
from the default palette while still resolving them for stored draft review via
`includeDeprecated`, and surface `visual.operator.lifecycle.deprecated` as a
node-scoped warning so publish requires explicit `ackWarnings` before production
promotion; downgrading an in-use operator library or resource design contract to
`DEPRECATED` is also warning-gated at validate/import/replace time so authors see
affected drafts or publications before storing the lifecycle change,
discover OpenAPI operations from JSON or YAML text, select one, project it into a visual resource contract draft, review the
generated request/response schema, save it back to the resource-contract
registry, and refresh the palette without leaving the browser,
save/load/delete H2-backed graph drafts with
revision-guarded field-level `PATCH` updates and per-revision audit metadata,
export/import portable draft bundles with operator snapshots and export-time
diagnostics for cross-environment review while preserving draft save, restore, delete, and import
actor/source/summary/reason evidence in revision metadata,
validate and compile the draft through the server-side visual graph APIs, inspect the
generated BLOGE DSL, run it with JSON context, and see diagnostics, output, graph
highlighting, graph-level runtime/design readiness, and the decision-table matrix update together. Node-path bindings
carry both source output port and target input port metadata, so multi-port user
operators are validated against the selected port schemas instead of falling back
to the first declared port. When different input ports expose the same field name,
the draft stores a stable key such as `customer.id` while `targetPort` and
`targetPath` keep the actual schema location unambiguous. Nested object schemas
are expanded into field paths such as `applicant.score`, so imported operator
libraries can expose realistic business payloads without flattening them first.
The browser also exposes whole-port root handles for user operators and graph
input `ctx`, allowing a compatible business object to be dragged as one binding
while the server still proves the nested required fields and target types. For
multi-port operators, root-port bindings use the port name as the stable draft
input key while leaving `targetPath` empty, so dragging `customer` and `order`
as whole objects does not collide in the saved draft. The same root-object
contract works when the source is another operator's named output port, so a
whole `customerFacts.customer` payload can feed a downstream `customer` input
port without flattening or rebinding every field.
Schema-declared config fields are canvas targets too: dragging an upstream
output to a `configSchema` handle writes the same expression-backed config value
as the inspector picker, runs the same server preflight, and keeps the visual
dependency visible without persisting config edges as executable data edges.
The canvas also supports explicit `dependency` edges for ordering node-backed
operators when no data field should be bound. These edges are stored as
node-to-node ordering constraints, canonicalize incoming `dependsOn`/`depends_on`
spelling to `dependency`, participate in the same DAG cycle gate, and lower to
BLOGE `depends_on = [...]` on generated `node { ... }` blocks. Dependency edges
are intentionally not exposed for transform or decision-table blocks because
those DSL forms cannot declare `depends_on`; data references remain the way to
sequence those blocks.
User-provided branch operators can also participate in the same canvas contract:
an imported operator with `lowering.mode = "branch"` exposes schema-checked
selector inputs and route source handles, while route edges carry explicit case
conditions such as `"physical"`, `true`, or `otherwise`. Route edges are stored
as control-flow edges, participate in the DAG cycle gate, and lower to BLOGE
`branch on ... { condition -> target }`. When the selector is a pure input
template, route conditions are checked against the selector schema, including
enum/const domains, nullable values, and scalar constraints, so a boolean route
cannot be attached to a string branch selector. Conditions are also de-duplicated
by their typed literal meaning, so `physical` and `"physical"` cannot create two
branches that render to the same string case. Because BLOGE branch selectors
must be node-output path expressions, the generator first materializes the
branch operator's selector as a small `transform <branchNode> { value = ... }`,
then branches on `<branchNode>.output.value`; this lets graph-input or upstream
bindings stay schema-aware without emitting invalid `branch on ctx.*` DSL.
For normal data connections the server preflight simulates the post-drop binding
state, replacing an existing edge for the same target endpoint while rejecting
root/field overlaps such as binding the whole `customer` port and then binding
`customer.id` separately. The preflight response also returns a canonical
`bindingKey`; when multiple input ports expose the same field path, the key is
port-qualified, for example `customer.id` versus `order.id`, so browser and API
clients store bindings without collapsing distinct port targets. The same
preflight disambiguation applies to duplicate dynamic paths that resolve through
residual `unevaluatedProperties` schemas.
The browser workflow covers this duplicate-path case through save/export, so a
schema author can model separate business objects that both expose fields such
as `id` without losing the target port in the stored draft.
Connection preflight also preserves draft-contract and graph input schema
blockers, so unsupported draft versions/statuses or invalid graph input schemas
cannot receive an accepted preview that later fails full validation.
The browser's immediate compatibility hints are advisory only: drag/drop,
input-picker, and config-picker mutations still ask the server-side connection
preflight for the final decision, so complex user-provided schemas are not
blocked by a stale or overly conservative browser heuristic. Picker candidates
with local mismatch hints stay selectable and carry the local reason in their
label while the server returns the accepted/rejected verdict; already-bound
input/config rows show local schema/type proof failures as advisory status while
keeping structural problems such as cycles or missing endpoints as local errors.
Transform policy bindings distinguish the default first-decision fallback from
an explicit user clear, so clearing the selected transform's policy node removes
the visual edge and persists as a `result = {}` transform in saved/exported
drafts instead of silently reconnecting to the fallback decision table.
Deleting a node also removes dependency/route edges and rewrites downstream
input/config/policy references that pointed at the deleted source, preventing
hidden stale bindings from being saved into the visual draft after canvas edits.
User-library transform and branch lowering templates default unbound optional
input placeholders to `null`, so partially bound optional schemas do not leak
`{{input.*}}` markers into generated DSL.
Object bindings and edges compare required nested fields, so an applicant object
without required `tier` cannot feed an input requiring `applicant.tier`, even
when `tier` exists only as an optional source field. Whole-object bindings also
compare all overlapping declared fields, reject source-declared or dynamically
allowed extra fields when the target object has `additionalProperties=false`,
and honor schema-shaped `additionalProperties` or residual
`unevaluatedProperties` on both sides. Array bindings and edges compare item
schemas, so `array<string>` cannot be wired into an input
that requires `array<integer>`. Enum and `const` value domains are checked too:
an output constrained to `LOW|HIGH` cannot feed an input constrained to
`APPROVE|REJECT`, a fixed `const: "REJECT"` output cannot feed a
`const: "APPROVE"` input, and an unconstrained string cannot feed an enum or
const input without an explicit transform. Numeric bounds are treated as
schema-enforced value domains too: a source score range must be a subset of the
target's `minimum`/`maximum` or `exclusiveMinimum`/`exclusiveMaximum` range
before the edge can be saved, and a source numeric `multipleOf` must be a
compatible step for the target `multipleOf`. String length constraints are enforced the same
way: a source `customerId` range must satisfy the target `minLength`/`maxLength`
domain, a source list must stay inside the target `minItems`/`maxItems`
range and guarantee `uniqueItems=true` when the target requires a duplicate-free
array. Array `contains` constraints are also proven conservatively: a source
must guarantee a compatible matching item schema and satisfy the target
`minContains`/`maxContains` range, unless it exposes a finite enum/const array
domain that can be checked directly. Array `prefixItems` constraints are
checked positionally too: tuple-like outputs must prove each constrained
leading item is compatible before they can feed a target array, while later
items continue to use the regular `items` schema. A source object must stay inside the target
`minProperties`/`maxProperties` range. String `pattern` and `format`
constraints are supported conservatively: finite `enum`/`const` source values
must match the target constraint, and non-finite string outputs must declare the
same pattern or format before they can feed a constrained target. Supported
formats are `email`, `uuid`, `uri`, `date`, `date-time`, and `duration`.
Object `propertyNames` constraints are enforced on dynamic map keys too: a
source must declare the same key-name schema, prove all possible declared keys
under `additionalProperties=false`, or expose a finite object enum/const domain
whose keys match before it can feed a constrained target.
Object `patternProperties` constraints are supported for map-style payloads as
well: matching dynamic keys are validated against the pattern's value schema,
and `additionalProperties=false` no longer rejects fields that are accepted by a
matching pattern.
Object `dependentRequired` constraints are enforced as conditional field
dependencies: if the source can produce a non-null trigger property such as
`cardNumber`, it must also guarantee the required companion properties before it
can feed a constrained target.
Object `dependentSchemas` constraints extend that conditional gate to whole
object subschemas, so a payment object that exposes `cardNumber` can be forced
to satisfy the additional billing schema before a connection, literal, or config
value is accepted.
Object `unevaluatedProperties` constraints are supported for the canvas schema
subset as residual object-field policy: after declared `properties`,
`patternProperties`, and any explicit `additionalProperties` are accounted for,
remaining dynamic fields can be forbidden or checked against a schema.
Schema mismatch diagnostics explain the failing path and reason, such as an
incompatible array `items` schema, an enum domain, numeric range or step, string
length range, string pattern or format, array item-count range, array uniqueness, array prefix item, array contains count, or object
property-count, property-name, pattern-property, dependent-required,
dependent-schema, or unevaluated-property requirement that is not a subset of the target domain, a missing required object
field, or a required field that the source object declares but does not
guarantee. Literal `constant`
bindings and `objectTemplate` fields are checked
against their target schema too, so fixed values cannot bypass required nested
input types; an `objectTemplate` for `applicant` must recursively provide
`applicant.score` before it satisfies that required nested input. Unsupported
input binding kinds are rejected before compile/run, so
hand-edited drafts cannot fall through to DSL literal lowering and bypass the
target schema gate. Operator `configSchema` is
also enforced: the browser inspector renders literal/source controls for schema
leaf fields, including nested object paths such as `limits.threshold`, and the
server blocks missing required config, type mismatches, enum/`const` mismatches,
numeric bound/`multipleOf`, string length, string pattern/format, array item-count,
`uniqueItems`, array `prefixItems`, array `contains`, object property-count, object property-name, object pattern-property, object dependent-required, object dependent-schema, and object unevaluated-property violations, and undeclared config fields when
`additionalProperties=false` or residual `unevaluatedProperties=false`. The inspector can
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
Imported operator libraries must use namespace-safe `operatorRef` values,
must not occupy system-projected namespaces such as `resource:` or
`publication:`, and must use single-token input/output port names, so palette
keys, canvas endpoints, and DSL paths share one address model. Library lifecycle status is explicit:
`ACTIVE` libraries enter the authoring catalog, `DEPRECATED` libraries are hidden
unless `/api/visual/operators?includeDeprecated=true` is used while remaining
resolvable for stored drafts, and `DISABLED` libraries remain stored for
audit/admin workflows but never enter the public canvas catalog. When a loaded
draft still references a deprecated operator, the browser fetches its schema as a
node-only spec so existing bindings stay schema-aware without re-adding that
operator to the drag palette. Import validation also rejects user libraries that
try to reuse an `operatorRef` already projected from the runtime Java operator
inventory, keeping every palette key owned by exactly one catalog source. If a
legacy stored library later collides with a newly projected Java operator, the
catalog deterministically keeps the runtime operator, hides the shadowed library
operator from the palette, and attaches a warning diagnostic to the retained
operator. Server/catalog-generated operator-level diagnostics are surfaced on
palette cards and inside the selected operator's contract panel, so catalog
ownership or projection warnings stay visible while composing. Input binding
rows also summarize how
many current sources are schema-compatible or blocked, with the first blocking
reason shown next to the target field.
Raw secret material is rejected from imported operator libraries and saved graph
drafts; authoring artifacts may store only references such as `secretRef`.
Graph input bindings are schema-aware too: the composer exposes a dedicated
Graph Input Schema editor that accepts a `SchemaEnvelope` or raw JSON Schema,
stores it as the draft `inputSchema`, and keeps Context JSON as only the sample
runtime payload. The browser performs a matching structural preflight for
blocking schema issues, including unsupported JSON Schema keywords, before
activating that schema on the canvas, shows the local schema diagnostics inline,
and the source picker offers compatible `ctx.*` values from the active declared
schema. Dynamic object maps are first-class in that picker: paths that flow
through schema-shaped `additionalProperties` or residual
`unevaluatedProperties` resolve to the dynamic-property schema, so ad hoc fields
such as `ctx.attributes.region` can still be matched against typed operator
inputs. The picker also mines the current Context JSON
sample for concrete dynamic keys that are accepted by the active graph input
schema, so map-style payloads do not require users to hand-type every expression;
dynamic key names are filtered through `propertyNames` before they appear as
picker sources. The browser workflow covers these dynamic `additionalProperties`,
`patternProperties`, and `propertyNames` picker bindings through generated DSL
and saved/exported draft bundles. Custom operator input ports that allow
map-style dynamic fields through `additionalProperties`, `patternProperties`, or
residual `unevaluatedProperties` also expose an Input Bindings control for adding
schema-checked target paths before binding them to context or upstream outputs.
Ports that mix declared `properties` with dynamic-map policy render both the
declared bindings and the added dynamic target paths.
Custom operator output ports with the same map-style schemas expose matching
Output Sources controls, so an author can add a schema-checked dynamic source
path, drag or select it as an upstream value, and keep the restored handle after
draft export/import.
The server validates the graph input schema with the same structural gate used
for operator port/config schemas, including nullable single-concrete type arrays
such as `["string", "null"]`, pure local `$defs` references such as
`{"$ref":"#/$defs/Customer"}`, strict object `properties`,
`additionalProperties`, `required`, enum value-domain, `const` shape checks,
numeric bound/`multipleOf` validation, string length validation, string
pattern/format validation, array item-count validation, `uniqueItems` validation, array `prefixItems` validation, object
property-count validation, object `propertyNames` validation, and object
`patternProperties`, `dependentRequired`, `dependentSchemas`, and
`unevaluatedProperties` validation, array `contains`
validation, then
blocks unknown or type-incompatible `contextPath` bindings when the draft input
schema is strict. The shared schema gate treats the currently supported
`SchemaEnvelope` as an explicit authoring contract: `format` must be
`json-schema`, `version` must be `2020-12`, and the schema body must stay within
the canvas-supported subset. Pure `#/$defs/...` `$ref` nodes are normalized to
their referenced schema before validation and type matching, and safe object
`allOf` compositions whose fragments normalize to object schemas are flattened
into ordinary object schemas; remote refs, unresolved refs, `$dynamicRef`, `$ref`
nodes with validation-affecting siblings, unsupported composition and conditional
keywords such as non-object or unsafe `allOf`, `not`, `if`, `then`, and `else`,
and unenforced constraint keywords such as unevaluated-item constraints are
rejected instead of being silently ignored. The shared gate supports `oneOf` and
`anyOf` as explicit visual union schemas: runtime value validation requires
`oneOf` to match exactly one branch and `anyOf` to match at least one branch, and
schema-aware connection checks stay conservative for union-to-target assignments.
The browser app mirrors those union rules for graph-input structural diagnostics,
readable type labels, local value matching, advisory connection hints, and
branch summaries in the selected-operator contract panel and operator-library
profile; server connection preview remains authoritative before a binding is
written.
Multi-concrete `type` arrays
such as `["integer", "string", "null"]` are also rejected; use explicit
`oneOf`/`anyOf` branches when a visual operator needs a non-null union. Manual
`expression` bindings are not blind escape hatches: server validation checks
referenced `ctx.*` and `node.output.*` paths, and pure reference expressions are
type-checked against the target input schema. Static literal expressions such as
`"high"`, `701`, `true`, and `null` are also treated as single-value source
schemas and checked against the target input or config schema, so hand-written
literal expressions cannot bypass the same enum/type gate as constants. Binding kinds are trimmed and
canonicalized to the supported draft tokens (`constant`, `contextPath`,
`nodePath`, `expression`, and `objectTemplate`). Stored graph edges support
`data`, `dependency`, and `route` kinds, with incoming edge kinds trimmed and canonicalized;
data edges must have unique ids and unique source/target connection signatures,
match a real semantic dependency such as a node-path binding or config
expression reference, and node-path bindings must be represented by a data edge
in stored drafts, so the line shown on the canvas cannot silently diverge from
what the DSL generator executes. Dependency edges are unique by source/target
node pair and are compiled as execution ordering only. Route edges are unique by
source/target/condition, must start from a branch-lowered operator, require a
non-empty condition, and compile as control-flow routing only.
The browser treats repeated attempts to draw an already-applied data connection
as an idempotent no-op instead of sending noisy duplicate edits to the server.
Expression references in node inputs and executable config also
participate in DAG validation and DSL topological ordering even when they are
not represented by a direct edge. Output selections are checked against the
selected node's declared output port schema before compile/run, and control-only
nodes such as branch routers cannot be selected as graph outputs.
Runtime output extraction uses the same dotted path semantics, including array
index segments such as `items.0.id`, so a schema-valid selection resolves to the
same payload fragment authors saw on the canvas.
The browser composer also enumerates schema-safe array item paths from `items`
and `prefixItems` as draggable handles and graph-output path options.
Structured `contextPath` and `nodePath` bindings that target schema array item
paths lower to BLOGE bracket expressions such as `ctx.scores[0]`,
`ctx[0]`, `node.output.items[0]`, and `node.output[0]`, keeping canvas
validation, generated DSL, and runtime execution aligned.
Expression bindings and user-operator lowering templates share that same schema
path model: template paths such as `{{input.scores.0}}` lower to bracket DSL,
and handwritten expressions such as `ctx.scores[0]` or `node.output.items[0]`
are resolved back through the declared schemas before save/run.
The validator also walks backward from the selected output through data,
dependency, route, input, and config references and emits non-blocking
`visual.graph.unreachableNode` warnings for nodes that do not contribute to that
output path, helping authors catch dangling business logic in large canvases.
The browser composer exposes the output node/path saved into `GraphDraft.output`.
Draft and publication runs also validate the submitted runtime Context JSON
against the frozen `GraphDraft.inputSchema` before compiling or executing, so an
authoring-time graph input contract cannot be bypassed by direct run API calls.
System context fields such as `tenantId` and `namespace` are ignored by this
business input-schema check unless the graph input schema explicitly declares
them, in which case they are validated like ordinary business inputs.
Runtime context diagnostics point at concrete JSON pointers such as
`/context/customer/id` for missing required fields, enum mismatches, type
mismatches, undeclared object properties, and invalid array items.
Each catalog operator exposes a server-computed fingerprint, and saved drafts
store per-node `operatorFingerprints` plus `operatorSnapshots`; compile/run/publish require executable
drafts to carry a fingerprint snapshot, and validation checks snapshots for
coverage, deleted-node leftovers, and drift so a draft authored against an
older schema/lowering fingerprint or carrying stale node snapshots is blocked
before execution. The node-level operator definition snapshot lets the usage
index explain a drift as `BREAKING_SCHEMA`, `RUNTIME_BINDING`, `GOVERNANCE`,
`POLICY`, `COMPATIBLE_SCHEMA`, or `METADATA` instead of only reporting that a
hash changed. Full `PUT` saves, field-level `PATCH` updates, guarded stored
runs, guarded deletes, and guarded publish requests all use the draft revision
observed by the caller; stale edits, runs, deletes, or publishes return
`409 CONFLICT` with `visual.draft.revisionConflict`
diagnostics instead of overwriting newer canvas state, executing a newer graph
than the caller saw, removing somebody else's newer draft revision, or
publishing a draft revision the user did not see. Draft create and update accept
only the `bloge.visualGraphDraft.v1` graph-draft contract and the supported
draft lifecycle status `DRAFT`; field-level patches re-check those contract
fields before saving and treat `schemaVersion` as a service-managed root.
`POST` creates a new draft identity even when the request body carries a stale
or existing `draftId`, so it cannot be used as an unguarded overwrite path.
Draft create, full `PUT` save, field-level `PATCH`, restore, delete, import,
and operator-fingerprint rebase accept actor/source/summary/reason audit
metadata, and create/full save mark the root draft path as changed so revision
history can distinguish whole-asset lifecycle events from field-level edits. The browser loads the
current server snapshot before patching when its local base revision is missing,
stops instead of saving if that snapshot proves the draft changed on the server,
and excludes service-managed schema version, identity, revision, audit,
fingerprint, and operator snapshot fields from field-level patch diffs. Browser Delete sends the current draft revision as a
query precondition, and Browser Publish first saves the current canvas through
the same guarded path, then sends the saved revision as the publication
precondition. Existing node fingerprint snapshots are
service-managed: PATCH rejects direct edits to identity, revision metadata, and
`operatorFingerprints`, and both save paths preserve the repository fingerprint
and operator definition snapshot for
existing nodes while only filling missing entries for new nodes from the active
catalog for the draft's current authoring scope. Malformed patch entries,
including missing operations, return structured visual diagnostics instead of
escaping as server errors. Routine metadata edits or full-form saves therefore
cannot silently rebase a draft onto a newer operator schema or turn a newly
hand-injected deprecated/out-of-scope operator into an executable node.
When an operator drift has been reviewed, clients can explicitly call
`POST /api/visual/drafts/{draftId}/operator-fingerprints/rebase` with the
observed `expectedRevision` and optional `nodeIds`; the server refreshes only
the selected service-managed fingerprint and operator definition snapshots from the active catalog,
rejects stale revisions with `409 CONFLICT`, and returns structured diagnostics
for unknown nodes or unavailable operators.
Stored drafts can also be exported as `bloge.visualGraphDraftExport.v1`
bundles that include the draft snapshot, source identity/revision, current
operator schema snapshots, export-time validation diagnostics, the full
export-time validation/readiness snapshot, and the source-environment dependency
report. Importing a
bundle creates a new draft identity and revision, refreshes node fingerprints
and operator snapshots from the active catalog for the imported draft's scope,
falls back to bundle-provided snapshots when the target catalog cannot resolve an
operator, rejects unsupported
bundle or draft contracts before storage with `actual`/`expected` diagnostic
metadata for schemaVersion mismatches, and returns a
`bloge.visualGraphDraftImportResult.v1` payload with source bundle schema,
source draft id/revision, target-environment diagnostics plus validation/readiness/action-readiness,
source dependency report, target dependency report, target runtime-binding handoff requirements plus stable keys, and a legacy
`dependencyReport` target alias for repairable issues such as missing operators, scope-mismatched operators, or schema-only
design graphs. The browser Drafts panel sends
`visual-canvas`/`gateway-browser` evidence for new draft saves and bundle imports. The Drafts panel
exposes this flow through Export/Import Bundle controls and a JSON bundle editor,
feeds the returned readiness and action gates back into Server Check, and consumes the returned target dependency report so imported
design-only or dependency-repair graphs can be reviewed without an immediate
manual validate/dependency refresh.
Complex canvas compositions can therefore be copied into another environment
without using a stale `draftId` as an overwrite path or losing their executable
versus design-artifact meaning.
Compile and run responses now also carry the server-side validation/readiness/action-readiness
used for that operation. Draft compile/run returns the current draft readiness,
while publication run returns the frozen publication readiness from the immutable
artifact. The browser keeps that readiness and the derived action gates in Server Check after compile, run,
publication run, and connection preflight diagnostics, so a design-only or
runtime-blocked graph does not lose its publish guidance just because the author
clicked another server action. Once Server Check has returned a non-executable
readiness, the browser also disables visual-draft Compile and Run Custom Graph
actions while keeping save, export, and `DESIGN` publication available through the server-derived `bloge.visualGraphActionReadiness.v1` gates; graph
edits reset the readiness snapshot back to "Not checked" so stale server
readiness does not lock a repaired draft.
Connection preflight also returns the candidate draft validation/readiness/action-readiness after
the preview edge, binding, or config expression is applied. Its top-level
diagnostics stay scoped to the proposed connection, so an unrelated draft issue
does not reject a drag/drop action, while the readiness still tells the author
whether the graph as a whole remains repair-required, design-only, or executable.
The same response includes a `bloge.visualConnectionCheckSummary.v1` summary
with the canonical binding key, binding/write shape, connection-scoped
diagnostic counts, replaced input binding keys, replaced edge ids, candidate
readiness state, `graphStillInvalid`, runtime-binding requirement count,
preview-scoped keys, and binding/handoff/owner/source/lowering/readiness
distribution counts, so large canvases and external governance consoles can route connection
decisions and design-only runtime handoff hints without parsing diagnostic
prose. The browser applies those replacement keys before writing the accepted
connection, which keeps root/field rebinding behavior aligned with the
server-side preview instead of leaving stale overlapping inputs behind.
The Browser Composer also prefetches `bloge.visualConnectionCandidates.v1`
when a normal data/config connection drag starts. The read model supports
focused windows (`targetNodeId`, `targetSurface`, `offset`, `limit`) and returns
per-candidate schema explanations with source/target labels, schema type
summaries, first diagnostic code, replacement counts, and runtime-binding
summary for schema-valid but non-executable candidates. Hover feedback prefers
the server candidate explanation, runtime-binding hint, or blocked diagnostic
when present, falls back to local schema hints when the read model is unavailable
or does not cover that target, and still runs `/api/visual/connections/check`
before writing the edge.
The selected-node Connectability inspector uses the same source-scoped candidate
read model as a short-lived server snapshot, so blocked previews and quick-connect
suggestions can show server-derived reasons before the author clicks; the click
path still runs connection check before mutating the draft.
Operator availability is also enforced by policy: imported operator definitions
may declare allowed `tenants`, `namespaces`, and `environments`; the browser
queries the active catalog with the current draft scope from the Authoring Scope
panel, then fetches deprecated specs only for operator refs already present in
the current draft. Server-side validation blocks validate/compile/run/publish if
a hand-edited draft references an operator outside that scope. Existing draft
nodes whose operator is filtered out by the current scope are shown as
unavailable instead of being silently treated as another operator type.
Stored drafts can be published into immutable visual graph artifacts that freeze
the draft snapshot, operator schema snapshots, fingerprints, layout,
validation/generation reports, and a publish-time dependency report for audit or
later promotion. The default
`artifactKind` is `EXECUTABLE`: publish compiles the generated DSL before
storage. The browser Server Check panel also lets authors switch the publish
mode to `DESIGN`, and now constrains that selector from the server-returned
graph readiness `artifactKinds`; API callers may pass `artifactKind: "DESIGN"`
directly to freeze a schema-valid but non-executable design artifact. This is useful when
the draft uses schema-only `lowering.mode=design` operators whose runtime
lowering does not exist yet. Design publications retain generation diagnostics such as
`visual.codegen.designOnlyOperator`; publication run returns
`visual.publication.designNotExecutable`, and design artifacts are not projected
back into the operator catalog as reusable subgraphs. The Publications panel
labels each artifact as `EXECUTABLE` or `DESIGN`, and disables run/golden actions
for design artifacts. It also renders the selected artifact's frozen graph
readiness, non-executable node rows, and frozen dependency summary from the
publication snapshot, so a design artifact can be reviewed without depending on
the current catalog. Immutable publications can also be exported as
`bloge.visualGraphPublicationExport.v1` bundles and imported into another
target repository through a schema-version-gated import path that preserves the
frozen publication snapshot while rejecting duplicate publication ids. The
import result returns both the source bundle dependency report and a
target-environment dependency report computed from the current target catalog
without rewriting the immutable artifact, so design-only artifacts can leave
the browser/database as portable control-plane assets before runtime binding
work exists. It also exposes the imported artifact's runtime-binding handoff
requirements and stable keys at the top level, so runtime-plane tools do not need
to depend on the nested publication snapshot shape.
Publish requests return validation/readiness/action-readiness on both accepted and rejected
attempts, then reject warning-level validation diagnostics
until the caller repeats the request with `ackWarnings=true`, so non-idempotent
side effects and similar promotion risks are explicitly reviewed before an
immutable artifact is stored.
Executable published artifacts can be run directly from their frozen DSL, so
execution no longer depends on whatever the current operator catalog exposes
after publication. Each executable artifact is also projected back into the
operator catalog as a reusable schema-aware subgraph operator scoped to the
publication's tenant, namespace, and environment. Its projected output schema
preserves the publication's saved output path, including named output-port paths
and whole-node multi-port output objects, so downstream drafts keep the same
schema gate when they reuse the subgraph. Dragging that operator into another
visual draft lowers to the reserved `visualPublication` runtime executor, which
loads and runs the frozen publication DSL by immutable id while keeping internal
`_bloge*` call-stack metadata out of graph-input schema validation. The
browser's Publications panel lists these immutable artifacts, refreshes after a
successful publish, and runs the selected artifact with the current Context JSON
without rewriting the draft currently being edited on the canvas.
Transient draft runs, stored draft runs, and publication runs also create
H2-backed visual run history records. Run responses include a `runId`; the
record captures the source kind, draft/publication identity, selected output,
node status map, diagnostics, errors, elapsed time, generated/frozen DSL, and
per-node runtime timings when available, shape-only context/output/result
summaries plus draft node snapshots so the
audit trail does not persist raw runtime payload values by default. The browser
Run History panel refreshes after visual draft and publication runs, supports
source/outcome/limit filters, shows a compact SLO summary for the same filter
window plus node health hot spots, and opens an individual record and shape-only run trace into the output
inspector while overlaying replay badges on matching canvas nodes; when an old
run contains nodes no longer present on the current canvas, the replay coverage
summary reports the missing nodes instead of silently implying a complete replay.
The run-history API
also accepts `sourceKind`, `draftId`, `publicationId`, `graphName`, `success`,
and `limit` query parameters for browser and automation use;
`/api/visual/runs/stats` reuses the same filters to aggregate total runs,
success rate, blocked/error counts, and p50/p95 latency;
`/api/visual/runs/node-stats` aggregates per-node run count, status distribution,
diagnostic/error attribution, selected-output count, runtime per-node latency,
and observed whole-run latency for legacy/correlation; `/api/visual/runs/{runId}/trace`
returns node status, operator metadata, per-node elapsed time when known,
selected-output markers, per-node diagnostic attribution, and result-shape
summaries without storing raw runtime payloads.
Published visual graph artifacts can also carry golden regression cases. A
golden case stores the publication id, sample context, selected output node, and
expected output. Golden case saves are contract-gated before they enter the
regression set: the golden case schema version must be supported, the sample
context must satisfy the publication's frozen graph input schema, the selected
output node must exist in the frozen draft snapshot, and path-scoped assertions
must use JSON Pointer paths. When `assertions` is empty the case uses exact-output equality;
advanced cases can instead provide deterministic assertions such as
`OUTPUT_EQUALS`, `PATH_EQUALS`, `PATH_APPROX_EQUALS`, `PATH_EXISTS`, and
`PATH_ABSENT` over the selected output with JSON Pointer paths.
`PATH_APPROX_EQUALS` expects `{ "value": number, "tolerance": number,
"relativeTolerance": number }` and passes when the actual numeric path value is
inside the larger absolute or relative tolerance, so suites can guard scores,
amounts, probabilities, and aggregate metrics without brittle exact-value
matching. `OUTPUT_MATCHES_SCHEMA` assertions validate the selected output
against a visual JSON schema or SchemaEnvelope, so golden suites
can enforce output contracts without pinning every runtime value. Running a case
reuses the publication's frozen DSL, writes a visual run-history record, and
returns pass/fail diagnostics without changing the draft currently on the
canvas. Run-time assertion validation remains in place for legacy or manually
inserted cases that predate the save gate. The Publications panel can save
exact-output cases or one or more browser-authored assertions from the latest publication
run output by choosing assertion modes, optional JSON Pointer paths, and expected
JSON values/schemas/tolerances; blank schema assertion values are inferred from the latest
output, and blank approximate assertion values are inferred from the latest
numeric value at the selected path with a tiny absolute tolerance. The browser queues multiple assertions before saving, so one case can combine contract, value, presence, absence, and tolerance checks. It can also run the selected case in place, or run the
full golden suite for the selected publication. The Publications panel can also
delete the selected golden case through the same API; changing the case set
immediately invalidates stale certification evidence. A successful suite can also be
saved as the publication's latest golden certification without
mutating the immutable publication artifact. Certification stores a fingerprint
of the evaluated golden case set, including assertions, and
`/certification/status` exposes a promotion-readiness gate
(`CERTIFIED`, `STALE`, `FAILED`, `MISSING_CASES`, or `UNCERTIFIED`) with
structured diagnostics. The Publications panel shows that status as
`Promotion ready`, `Certification stale`, or the relevant blocking state, so a
new, edited, or deleted golden case or assertion immediately invalidates old
certification evidence.
The Drafts panel can also load revision history, preview an old snapshot on the
canvas, and restore it as a new latest revision through a first-class guarded
restore API. Each stored revision carries `revisionMetadata` with created/updated
actor, change source, change summary, reason, and touched JSON pointer paths, giving the
example a concrete audit anchor for collaborative authoring and rollback. Browser
save, restore, delete, and import mutations provide default reason text so routine
design-asset changes are not stored as anonymous mechanical revisions. Draft
delete removes the current working draft but preserves immutable revision
history, so a deleted draft can still be recovered from a retained revision
through the same guarded restore API. The panel consumes a lightweight draft
history index, so deleted-but-recoverable drafts remain discoverable in the
selector instead of depending on somebody remembering the draft id, and import
reasons are visible without loading a full revision snapshot. The same
panel calls the draft revision diff API to show a latest-to-selected
`bloge.visualGraphDraftDiff.v1` review of graph-level, node-level, and edge-level
changes, including risk, summary, and node/edge add/remove/change counts before
preview or restore. Stored drafts also expose
`bloge.visualGraphDraftDependencies.v1`, a current-catalog dependency report
that groups operatorRefs, owner operator-library counts, node lineage, source/lowering/readiness counts, and
fingerprint states, while falling back to saved operator snapshots when a draft
is imported into an environment where the current catalog is missing an
operator and flagging scope-mismatch dependencies when an operator exists but is
not available to the draft's tenant/namespace/environment. The Drafts panel
loads the same report after save, load, import, restore, and fingerprint rebase,
and renders the dependency mix plus missing-catalog, scope-mismatch, drifted,
and missing-snapshot states beside the revision review. Operator and node rows
in that panel can focus the corresponding canvas node, and drifted or
missing-snapshot node rows expose the same guarded operator-fingerprint rebase
action as the selected-node inspector, so dependency review stays connected to
repair work on the graph without pretending a catalog-missing or scope-mismatched
operator can be rebased. Rebase is disabled while the local
canvas has unsaved graph changes against the current draft revision, so authors
must save or reload those edits before mutating the service-managed fingerprint
snapshot. The browser sends actor/source/summary/reason audit metadata with each
rebase so compatible drift refreshes are reviewable as explicit governance
revisions rather than anonymous mechanical repairs. If a rebase hits a draft revision conflict, the browser reloads the
server's latest draft onto the canvas, clears local edit history for that
governance action, refreshes the revision list and dependency report, and asks
the author to review the latest dependency view before retrying.
Operator-library import, delete, and governed restore also refresh the current
draft dependency panel and selected node risk view because catalog mutations can
immediately change stored draft readiness.
The Operator Libraries panel exposes the same product pattern for user-provided
operator catalogs: authors can load immutable library revision history by
selected library or explicit libraryId, preview a historical operator schema
snapshot in the JSON editor, and restore it as a governed latest revision. Each
registry revision carries `revisionMetadata` with actor, change source, change
summary, and reason, and the browser supplies audit metadata for import,
delete, and restore actions so the revision picker is reviewable without opening
the JSON payload. The panel also calls the revision diff API to show the
latest-to-selected `bloge.visualOperatorLibraryDiff.v1` risk summary, library
changes, and operator-level added/removed/changed surface before preview or
restore. A deleted library keeps its history target in the panel, so
accidental deletion can be recovered without relying on browser-local state;
SemVer rollback still requires the explicit `Rollback` toggle and warning
acknowledgement. The same panel can export the selected current library as a
`bloge.visualOperatorLibraryExport.v1` bundle containing the normalized library
snapshot, latest immutable registry revision, and export-time
validation/profile/impact result, then import a pasted bundle through the
target environment's governed `bloge.visualOperatorLibraryImportResult.v1`
contract. The import result preserves source identity and revision evidence
while returning the target preflight validation/profile/impact result plus the
new target registry revision, so user-provided schema-only operator catalogs
can be reviewed, promoted, or moved across environments as first-class
artifacts.
Local Undo/Redo is intentionally separate from that durable revision history:
it keeps a bounded browser-side builder snapshot stack for the current unsaved
editing session, covers node add/delete/move, bindings, config, dynamic schema
paths, graph input schema, authoring scope, and graph output selection, and is
cleared when a draft is loaded, imported, or replaced by a revision preview.
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
| `GET` | `/api/visual/operators` | List native, Java registry, imported, executable publication-backed subgraph, and resource-backed visual operator definitions; supports `tenantId`, `namespace`, and `environment` policy filtering, `sourceKind` / `operatorLibraryId` / `loweringMode` / `capability` / `runtimeReadiness` catalog facets, plus multi-term schema-aware search across input/output/config fields, field types, JSON Schema field annotations, library owner ids, and readiness summaries; response includes `facets.total/sourceKinds/operatorLibraryIds/loweringModes/capabilities/runtimeReadinessStates` counts |
| `GET` | `/api/visual/operators/{operatorRef}` | Return one visible `bloge.visualOperator.v1` definition under the same `tenantId` / `namespace` / `environment`, `includeDeprecated`, `resourceOnly`, `operatorLibraryId`, and catalog facet visibility gates used by the operator catalog; returns `404` when the operator is hidden or missing |
| `GET` | `/api/visual/operators/{operatorRef}/usage` | Return stored draft and immutable-publication usage of one operatorRef, including saved/frozen fingerprint status, changed-surface drift summaries, and `changeRisk/changeCategories/changeSummary` when snapshots allow risk classification |
| `GET` | `/api/visual/assets/overview` | Return `bloge.visualAssetOverview.v1`, an environment-level visual authoring overview that echoes the requested authoring scope while aggregating draft summaries, publication summaries, current operator catalog facets, and an action-readiness/runtime-binding-requirement-aware server-derived action queue with optional `tenantId` / `namespace` / `environment` scope filters plus `actionLimit` / `actionOffset` / `actionSeverity` / `actionType` / `actionTargetKind` / `actionOperatorRef` / `actionOperatorLibraryId` queue query controls and operatorRef/operatorLibraryId counts for runtime-plane triage |
| `GET` | `/api/visual/assets/runtime-binding-requirements` | Return `bloge.visualRuntimeBindingRequirements.v1`, a scope-aware, pageable runtime-binding gap index for active drafts and immutable publications, with `targetKind` / `operatorRef` / `operatorLibraryId` / `bindingKind` / `handoffLane` / `handoffKind` / `handoffTarget` / `sourceKind` / `loweringMode` / `readinessState` / `requirementKey` filters, stable requirement keys, operatorRef/operatorLibraryId counts, and handoff lane/kind/target fields for external runtime-plane routing |
| `GET` | `/api/visual/assets/runtime-binding-requirements/handoff-bundle` | Export the current runtime-binding gap query window as `bloge.visualRuntimeBindingHandoff.v1`, preserving source index lineage, normalized scope/filter, stable requirement keys, operator/library/routing counts, requirement rows, and per-operator contract snapshots with ports/config/lowering/readiness evidence for runtime-plane handoff without creating workflow state |
| `POST` | `/api/visual/assets/runtime-binding-requirements/handoff-review` | Review a `bloge.visualRuntimeBindingHandoff.v1` bundle against the current runtime-binding read model and return `bloge.visualRuntimeBindingHandoffReview.v1` with exported operator contract count, current/drifted/missing/new-current-window reconciliation status, field-change categories, and exported/current/new routing distributions for owner/lane assignment |
| `POST` | `/api/visual/assets/runtime-binding-requirements/implementation-bindings/validate` | Validate a stateless `bloge.visualRuntimeBindingImplementationBinding.v1` implementation proposal against a handoff operator contract snapshot and current catalog fingerprint, returning `bloge.visualRuntimeBindingImplementationValidation.v1` with ready-to-bind/requires-review/rejected state, evidence diagnostics, and catalog drift status without closing workflow state |
| `GET` | `/api/visual/assets/runtime-binding-requirements/implementation-bindings` | List stored `bloge.visualRuntimeBindingImplementationBindingRecord.v1` implementation proposals with optional `operatorRef` and `state` filters, preserving validation evidence for later bind/supersede workflow steps |
| `POST` | `/api/visual/assets/runtime-binding-requirements/implementation-bindings` | Submit and persist a valid runtime implementation proposal after the same contract/evidence validation gate; rejected proposals are not stored, duplicate `bindingId` returns `409 CONFLICT`, and accepted records remain control-plane evidence rather than closing runtime-binding requirements |
| `POST` | `/api/visual/assets/runtime-binding-requirements/implementation-bindings/{bindingId}/bind` | Transition a ready-to-bind or review-acknowledged proposal into the active `bound` lifecycle state with actor/reason audit evidence; rejects missing governance evidence, review-required proposals without `ackReview`, already bound/superseded records, and operators with an existing active binding |
| `POST` | `/api/visual/assets/runtime-binding-requirements/implementation-bindings/{bindingId}/supersede` | Replace one active bound implementation with another accepted proposal for the same operator, linking `supersedesBindingId` / `supersededByBindingId` and lifecycle events while still leaving executable catalog/readiness projection to a later runtime adapter integration |
| `GET` | `/api/visual/drafts` | List stored visual graph drafts with optional `tenantId` / `namespace` / `environment` scope filters |
| `GET` | `/api/visual/drafts/history` | List lightweight active/deleted draft history summaries with current/latest revision, revision count, latest actor/source/summary, recovery status, and optional `tenantId` / `namespace` / `environment` scope filters |
| `GET` | `/api/visual/drafts/summaries` | List `bloge.visualGraphDraftSummary.v1` draft asset summaries that combine history, server validation/readiness/action-readiness, diagnostic counts, and dependency counts without returning full draft JSON; supports optional `tenantId` / `namespace` / `environment` scope filters |
| `POST` | `/api/visual/drafts` | Save a new visual graph draft with server-assigned id/revision, ignoring submitted draft identity fields |
| `GET` | `/api/visual/drafts/{draftId}` | Load a stored visual graph draft |
| `GET` | `/api/visual/drafts/{draftId}/dependencies` | Summarize a stored draft as `bloge.visualGraphDraftDependencies.v1`, including distinct operator dependencies, per-node binding/edge lineage, source/lowering/runtime-readiness counts, current/missing/drifted/scope-mismatch fingerprint state, and scope policy diagnostics |
| `GET` | `/api/visual/drafts/{draftId}/export` | Export a portable draft bundle with operator snapshots, export-time diagnostics, validation/readiness/action-readiness, and source-environment dependency report |
| `POST` | `/api/visual/drafts/import` | Import a portable draft bundle as a new draft identity with current operator fingerprints/snapshots plus target-environment diagnostics, validation/readiness/action-readiness, source and target dependency reports, target runtime-binding handoff requirements and stable keys, legacy target `dependencyReport`, and optional `actor` / `changeSource` / `changeSummary` / `reason` revision audit metadata |
| `GET` | `/api/visual/drafts/{draftId}/revisions` | List immutable draft revision snapshots, newest first; retained history remains queryable after current draft deletion |
| `GET` | `/api/visual/drafts/{draftId}/revisions/{revision}` | Load one immutable draft revision snapshot, including retained history for deleted drafts |
| `GET` | `/api/visual/drafts/{draftId}/revisions/{baseRevision}/diff/{targetRevision}` | Compare two immutable draft snapshots as `bloge.visualGraphDraftDiff.v1`, including highest change risk, risk categories, summary, graph-level changes, node-level added/removed/changed surface, edge-level added/removed/changed surface, and node/edge change counts |
| `POST` | `/api/visual/drafts/{draftId}/revisions/{revision}/restore` | Restore one immutable draft snapshot as a new latest revision with `expectedRevision` concurrency guard, audit metadata, contract validation, and historical operator snapshot preservation; can recover a deleted draft when retained history exists |
| `PUT` | `/api/visual/drafts/{draftId}` | Replace a stored visual graph draft when the submitted `revision` matches; stale full saves return `409 CONFLICT` with current draft diagnostics |
| `PATCH` | `/api/visual/drafts/{draftId}` | Apply an `expectedRevision` JSON patch, reject stale edits with `409 CONFLICT`, and reject patches to service-managed identity/revision/fingerprint/snapshot fields |
| `POST` | `/api/visual/drafts/{draftId}/operator-fingerprints/rebase` | Explicitly refresh selected or all service-managed node fingerprint/operator snapshots against the current operator catalog using an `expectedRevision` guard plus optional actor/source/summary/reason revision audit metadata |
| `DELETE` | `/api/visual/drafts/{draftId}` | Delete the current visual graph draft pointer while preserving immutable revision history; optional `expectedRevision`, `actor`, `changeSource`, and `changeSummary` query parameters reject stale deletes and write deletion audit metadata |
| `POST` | `/api/visual/drafts/validate` | Validate a visual graph draft against operator schemas, typed port edges, and DAG constraints; response includes `bloge.visualGraphReadiness.v1` and `bloge.visualGraphActionReadiness.v1` so callers can distinguish runtime-executable, design-only, runtime-blocked, governance-review, draft-repair-required graphs and the allowed compile/run/DESIGN publish/EXECUTABLE publish actions |
| `POST` | `/api/visual/drafts/compile` | Validate a visual graph draft, lower it to BLOGE DSL, then compile the DSL; response includes validation/readiness/action-readiness so clients keep publish and action guidance after compile diagnostics |
| `POST` | `/api/visual/connections/check` | Check a proposed source-to-target canvas connection against the same schema and DAG rules used by draft validation, returning connection-scoped diagnostics, a machine-readable decision/replacement/runtime-binding summary, the canonical binding key for data/input bindings, and candidate draft validation/readiness/action-readiness |
| `POST` | `/api/visual/connections/candidates` | Discover schema-aware target endpoints for one dragged source endpoint, returning `bloge.visualConnectionCandidates.v1` accepted/rejected counts, focused target filters/windowing, per-candidate schema explanations, optional blocked diagnostics, preflight summaries with runtime-binding count/keys/routing counts, and the same binding keys produced by connection check |
| `POST` | `/api/visual/drafts/run` | Validate, compile, and execute a transient visual graph draft; response includes validation/readiness/action-readiness and a run history id |
| `POST` | `/api/visual/drafts/{draftId}/run` | Execute a stored visual graph draft with submitted context; response includes validation/readiness/action-readiness, and optional `expectedRevision` rejects stale runs with `409 CONFLICT` |
| `POST` | `/api/visual/drafts/{draftId}/publish` | Publish an immutable visual graph artifact; default `artifactKind=EXECUTABLE` validates, compiles, and stores frozen DSL, while `artifactKind=DESIGN` freezes a schema-valid non-executable design artifact with generation diagnostics; response includes validation/readiness/action-readiness on accepted and rejected attempts so clients can constrain publish artifact kinds and warning review gates; optional `expectedRevision` rejects stale publishes with `409 CONFLICT`; warning-level validation diagnostics require `ackWarnings=true`, and warning-acknowledged storage also requires non-empty `actor` and `reason` evidence that is frozen as publication metadata |
| `GET` | `/api/visual/publications` | List immutable visual graph publications |
| `GET` | `/api/visual/publications/summaries` | List `bloge.visualGraphPublicationSummary.v1` publication asset summaries with frozen artifact kind, readiness/action-readiness, diagnostic counts, dependency counts, and source/runtime-readiness distributions without returning full publication payloads; supports optional `tenantId` / `namespace` / `environment` scope filters |
| `POST` | `/api/visual/publications/import-bundle` | Import a portable `bloge.visualGraphPublicationExport.v1` bundle and return `bloge.visualGraphPublicationImportResult.v1` with source and target dependency reports plus target runtime-binding handoff requirements and stable keys; rejects unsupported bundle/publication schema versions, missing publication snapshots, and duplicate target publication ids |
| `GET` | `/api/visual/publications/{publicationId}` | Load a published visual graph artifact |
| `GET` | `/api/visual/publications/{publicationId}/dependencies` | Load the publish-time dependency report frozen with an immutable visual graph artifact |
| `GET` | `/api/visual/publications/{publicationId}/export` | Export an immutable publication as `bloge.visualGraphPublicationExport.v1`, including source lineage, frozen publication snapshot, validation/readiness, and publish-time dependency report |
| `POST` | `/api/visual/publications/{publicationId}/run` | Execute a published artifact from its frozen DSL and return the artifact's frozen validation/readiness/action-readiness |
| `GET` | `/api/visual/golden-cases?publicationId=...` | List golden regression cases bound to an immutable publication |
| `GET` | `/api/visual/golden-cases/{caseId}` | Load one golden regression case |
| `POST` | `/api/visual/golden-cases` | Save a golden regression case for an existing publication, with save-time schema-version/context/output-node/assertion validation, exact-output fallback, explicit output assertions, numeric tolerance assertions, or output-schema assertions |
| `DELETE` | `/api/visual/golden-cases/{caseId}` | Delete one golden regression case; the next certification status reflects the changed case-set fingerprint |
| `POST` | `/api/visual/golden-cases/{caseId}/run` | Execute a golden case with the publication's frozen DSL and return exact-output, value/tolerance assertion, or schema assertion diagnostics |
| `POST` | `/api/visual/golden-cases/publications/{publicationId}/run` | Execute every golden case bound to a publication and summarize total/passed/failed counts |
| `POST` | `/api/visual/golden-cases/publications/{publicationId}/certify` | Execute the publication golden suite and store the latest certification status |
| `GET` | `/api/visual/golden-cases/publications/{publicationId}/certification` | Load the latest golden certification for a publication |
| `GET` | `/api/visual/golden-cases/publications/{publicationId}/certification/status` | Load promotion-readiness status, including stale certification diagnostics |
| `GET` | `/api/visual/runs` | List visual graph run history records, newest first; supports `sourceKind`, `draftId`, `publicationId`, `graphName`, `success`, and `limit` filters |
| `GET` | `/api/visual/runs/stats` | Aggregate run-history health for the same filters, including success rate, blocked/error counts, and p50/p95/max latency |
| `GET` | `/api/visual/runs/node-stats` | Aggregate node-level run-history health for the same filters, including status counts, diagnostic/error attribution, selected-output counts, runtime per-node latency, and observed whole-run latency for legacy/correlation |
| `GET` | `/api/visual/runs/{runId}` | Load one visual graph run history record |
| `GET` | `/api/visual/runs/{runId}/trace` | Load one shape-only replay trace with node statuses, operator metadata, per-node elapsed time when known, selected output marker, per-node diagnostics, result summaries, errors, and generated/frozen DSL |

Visual run requests may pass `outputNode` to inspect a different node than the
draft's saved output selection. In that case the response returns the override
node's full output instead of reusing the saved `output.path`.

The selected-node inspector can load the operator usage index for the node's
`operatorRef`, showing stored draft usage, immutable publication usage,
fingerprint status, changed-surface summaries, and classified replacement risk
next to the local canvas impact view. It can also refresh the current
single-operator definition through `GET /api/visual/operators/{operatorRef}` under
the active authoring scope, falling back to deprecated visibility for imported
or restored draft nodes that are no longer in the active palette. Draft rows use the saved node-level
operator snapshot when available; publication rows use the frozen publication
snapshot, so both mutable drafts and immutable releases can explain whether
drift is a breaking schema change, runtime binding change, governance/policy
change, compatible schema growth, or metadata drift. Once loaded, the same usage status is reflected on the
canvas node as an operator usage badge so drift and missing-snapshot risk are
visible without reopening the inspector. The inspector also shows the selected
node's saved-vs-current fingerprint snapshot and exposes an explicit rebase
action for reviewed drift, with risk-specific copy distinguishing repair-before-rebase
from review-and-rebase cases, preserving the rule that ordinary draft saves never
silently overwrite existing operator fingerprints. After validation or trace
replay, the same selected-node inspector isolates the node's own diagnostics and
trace status so authors do not have to reverse-map global diagnostic lists by
hand.

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
| `POST` | `/admin/resource-design-contracts/validate` | Validate a visual resource contract without storing it and return `bloge.resourceDesignContractImpact.v1`; use `force=true` to suppress disablement impact diagnostics; same-resource draft/publication fingerprint drift and lifecycle downgrade are reported as warnings with changed-surface risk counts | 200 |
| `POST` | `/admin/resource-design-contracts/from-openapi/operations` | Discover HTTP operations from parsed `openApi` or raw JSON/YAML `openApiText` before selecting one for projection; returns path, method, operationId, tags, request/response media summaries, projection readiness, and structured diagnostics without storing anything | 200 |
| `POST` | `/admin/resource-design-contracts/from-openapi` | Project one OpenAPI operation from parsed `openApi` or raw JSON/YAML `openApiText` into a visual resource contract draft and reviewable runtime descriptor suggestion without storing either; returns the draft plus structured validation diagnostics and `bloge.resourceDesignContractImpact.v1` | 200 |
| `PUT` | `/admin/resource-design-contracts/{resourceId}` | Create or replace a visual contract; warning/error responses include `bloge.resourceDesignContractImpact.v1`, reject disablement of stored-draft `resource:<resourceId>` references unless `force=true`, require `ackWarnings=true` before storing warning-level lifecycle, publication, or fingerprint drift, and require non-empty `actor` and `reason` evidence whenever `force=true` or `ackWarnings=true` is used | 200 / 400 / 409 |
| `DELETE` | `/admin/resource-design-contracts/{resourceId}` | Delete a visual contract; rejects stored-draft and immutable-publication `resource:<resourceId>` references unless `force=true`, returning `bloge.resourceDesignContractImpact.v1` on conflict; forced deletes require non-empty `actor` and `reason` evidence | 204 / 400 / 409 |

Validate and upsert run the same resource-contract validator before storage.
The OpenAPI operation discovery and projection endpoints accept either a parsed OpenAPI 3 document in
`openApi` or raw JSON/YAML document text in `openApiText`; projection additionally
requires either `operationId` or `path` and `method`. Malformed document text is
reported with structured diagnostics. Projection maps path/query/header/cookie
parameters and JSON request bodies into `requestSchema`, projects the first 2xx
JSON response schema into `responseSchema`, rewrites local
`#/components/schemas/*` references into the visual schema `$defs` form, drops
unsupported non-string OpenAPI formats with warnings, and also returns a
reviewable `descriptorSuggestion` for the runtime `ResourceDescriptor`: OpenAPI
servers plus path are projected into `urlTemplate`, path/query parameters become
`ctx.params.*` mappings, header parameters become dynamic `headerExpressions`
(with bracket access such as `ctx.params["X-Request-Id"]` for non-identifier
header names), cookie parameters become dynamic `cookieExpressions`, JSON and
`application/x-www-form-urlencoded` and text `multipart/form-data` request bodies become `ctx.params.body`,
common OpenAPI security schemes become
review-only auth suggestions (`http` bearer/basic and header `apiKey`, using
placeholder credentials), while OAuth2, OpenID Connect, and mutualTLS schemes
produce explicit descriptor-suggestion warnings instead of unsafe runtime auth
guesses. Standard JSON headers plus HTTP-status response handling are filled in;
when an operation uses a JSON-compatible vendor media type such as
`application/vnd.example+json`, the selected media type is preserved in
`Accept` / `Content-Type` descriptor headers and reported as a review warning.
For `application/x-www-form-urlencoded`, the runtime descriptor keeps the form
media type and `HttpResourceOperator` encodes body maps into standard form
pairs before dispatch. For `multipart/form-data`, the runtime adds a boundary
when needed and encodes body maps into repeated form-data parts. Other non-JSON
request bodies such as binary media types are not silently projected; the
preview emits a warning and omits the body mapping until an explicit runtime
encoding strategy is configured.
OpenAPI schema references must stay inside the submitted document. Discovery
marks operations with unresolved local component schema references or remote
schema `$ref` values as `BLOCKED`; projection returns blocking diagnostics such
as `visual.resourceContract.openapi.refUnresolved` or
`visual.resourceContract.openapi.refUnsupported` without returning a contract or
descriptor draft. This avoids importing a resource operator contract whose
request/response schema cannot be trusted by the visual canvas.
Advanced security schemes remain in the design contract but are reported as
descriptor-suggestion warnings when they cannot be represented by the runtime
descriptor. When the resource already has a stored
design contract or registered runtime descriptor, the preview also returns
review diagnostics for request/response schema drift, contract metadata drift,
and descriptor field drift before authors save replacements. The endpoint then runs the same registry-aware validation
preflight as `/validate`. It never mutates the contract or descriptor registries;
authors still save the returned drafts through `PUT /admin/resource-design-contracts/{resourceId}`
and `/admin/resources` after reviewing diagnostics.
The browser Composer includes the same OpenAPI Resource Contract path: authors
paste raw OpenAPI JSON or YAML, Discover lists operations with READY/WARNING/BLOCKED
projection readiness for the response that Preview will actually project, shows a
compact readiness summary, and can fill operationId/path/method while updating the
projection status message. If the selected discovered operation is `BLOCKED`, the
browser blocks Preview before calling the projection endpoint; otherwise Preview generates contract and descriptor JSON
drafts, Save Contract writes the design contract through the admin API, Save
Descriptor creates or updates the runtime descriptor through `/admin/resources`,
and the visual operator catalog is refreshed so the corresponding
`resource:<resourceId>` operator can be dragged immediately once both sides exist.
The validator rejects unsupported request/response schema kinds, `required`
fields not declared in `properties`, array schemas without `items`, enum schemas
without values, unsupported schema envelope format/version, unsupported JSON
Schema remote or unresolved references, unsupported composition/constraint keywords
outside the safe object `allOf` subset and supported `oneOf`/`anyOf` union subset,
multi-concrete type arrays, and raw secret material in
contract examples. Contract lifecycle status is explicit and normalized:
`ACTIVE` resource-backed operators enter the default palette, `DEPRECATED`
contracts are hidden unless `/api/visual/operators?includeDeprecated=true` is
used while remaining resolvable for existing drafts, and `DISABLED` contracts
remain stored for admin/audit workflows but do not project executable catalog
operators. Supported object schemas may constrain dynamic key names with
`propertyNames` using the same string enum/const, length, pattern, and format
subset enforced elsewhere in the canvas, and may constrain dynamic key values
with regex-keyed `patternProperties` schemas or conditional object-field
dependencies with `dependentRequired` or whole-object dependent subschemas with
`dependentSchemas`, and may constrain remaining dynamic fields with
`unevaluatedProperties`. Supported array schemas may constrain tuple-like leading
items with `prefixItems` and may require matching elements with `contains` plus `minContains`/`maxContains`. Built-in bootstrap
contracts pass the same gate, so resource-backed virtual operators do not enter
the visual catalog with weaker schema guarantees than imported user operators.
Built-in and descriptor-projected operator schemas are covered by the same
catalog-level schema gate, keeping native, imported, and virtual operators on
one authoring contract.
Bootstrap seeds only missing built-in contracts and does not overwrite a
persisted contract that an author has already customized. Deleting a visual
resource design contract or replacing it with `DISABLED` is impact-aware: by
default the admin API rejects the operation with `409 CONFLICT` when any stored
draft still uses the corresponding `resource:<resourceId>` operator, rejects
deletion when immutable publications were authored with that resource-backed
operator, and requires `force=true` for an explicit destructive change. A
disablement that only affects immutable publications is warning-gated instead of
hard-blocked because the publication keeps its frozen DSL, but replay,
recertification, or republishing must still be reviewed before the change is
stored.
Replacing a catalog-visible contract with schema- or projection-relevant changes
also emits non-blocking fingerprint drift warnings for stored drafts and
immutable publications, including a concise changed-surface summary such as the
affected input/output/config schema, capability, policy, or lowering area, and
warns when an affected legacy draft or publication lacks a saved node fingerprint
snapshot. Direct replacement requires
`ackWarnings=true` before warning-level drift is stored; the browser OpenAPI
panel mirrors this with a second Save click after warnings are shown, renders
the server-provided impact review, and uses its change-risk summary in the
acknowledgement copy. Resource-contract writes that use `force=true` or
`ackWarnings=true` also require non-empty `actor` and `reason`, so high-risk
schema drift or destructive resource-backed catalog changes cannot be accepted
through an anonymous boolean flag.
Resource contract preflight responses also carry
`bloge.resourceDesignContractImpact.v1`, with affected resource ids,
`resource:<resourceId>` operator refs, stored draft ids, immutable publication
ids, draft node targets, publication node targets, diagnostic code counts, and `changeRiskCounts` derived from the same
`BREAKING_SCHEMA` / `COMPATIBLE_SCHEMA` / `RUNTIME_BINDING` / `GOVERNANCE` /
`POLICY` / `METADATA` categories used by imported operator libraries.

User-provided visual operator libraries are imported through a separate admin API.
Imported operators join the same `/api/visual/operators` catalog as built-ins and
resource-backed virtual operators when their library status is catalog-visible.
`GET /api/visual/operators/{operatorRef}/usage` returns the server-side usage
index for a catalog key, including stored draft node references, immutable
publication references, current-vs-saved fingerprint status, and changed-surface
summaries plus `changeRisk/changeCategories/changeSummary` when a draft's saved
node operator snapshot or a publication's frozen operator snapshot differs from
the current catalog definition.
The browser Operator Libraries panel calls
the validate endpoint directly, so authors can inspect an inline structured
diagnostic list, server-derived library profile, and machine-readable import
readiness summary before storing a library.
That profile reports operator/schema field counts, field-level JSON Schema
`title` / `description` / `examples` / `default` / `$comment` annotations for authoring review, runtime readiness facets,
catalog-repair, runtime-blocked, governance-review, and design-only summaries;
the readiness summary collapses diagnostics, profile, and impact into states
such as `design-only-importable`, `runtime-binding-required`,
`force-required`, or `catalog-repair-required`, including whether
`ackWarnings`, `force=true`, and actor/reason governance evidence are needed,
and renders import-time Runtime Binding Requirements so missing executable
lowering, runtime adapter, worker, tool, event, message, webhook, streaming, or
durable bindings can be routed before the library is broadly used in drafts.
if authors keep editing the JSON after validation, the panel falls back to a local
instant preview until the next server validation refreshes the authoritative profile.
That validation is registry-aware:
it reports cross-library `operatorRef` ownership conflicts and replacement
or disablement impact against stored drafts before an import or replace request mutates storage.
When a user-library operator uses native lowering, validation also warns if the
declared executable `lowering.operatorRef` is not visible in the runtime Java
operator inventory, so authors must explicitly acknowledge wrappers that rely on
an external executor before they are stored.
Imported operators may also use `lowering.mode = "design"` for schema-only
authoring when the concrete runtime implementation does not exist yet. These
operators enter the catalog, can be dragged onto the canvas, connected under
their input/output schema constraints, saved, revised, exported, and validated;
the validate response reports `bloge.visualGraphReadiness.v1`, so a schema-valid
design-only graph is explicitly `valid=true`, `executable=false`, and publishable
as a `DESIGN` artifact instead of being treated as a broken runtime graph;
compile/run and default `artifactKind=EXECUTABLE` publish then return a
deterministic `visual.codegen.designOnlyOperator` diagnostic until the operator
is rebound to an executable native/transform/branch lowering. That publish
rejection still includes validation readiness, so the browser can move the
author back to the `DESIGN` artifact path instead of treating the composition as
broken. The Browser Composer's Server Check area renders a dedicated readiness
panel for these graphs, showing that save/export/`DESIGN` publication remain
allowed while compile/run/`EXECUTABLE` publication wait for runtime binding.
That readiness payload also lists node-scoped `runtimeBindingRequirements` for
schema-valid but non-executable nodes, including the missing binding kind,
declared target such as worker topic/event type/channel/webhook path, handoff
lane/kind/target routing metadata, and the recommended promotion action, so
downstream runtime-plane work can be routed without scraping diagnostic prose.
The Workspace Overview action queue consumes the same requirements and emits
per-node `PLAN_DRAFT_RUNTIME_BINDING` or `PLAN_PUBLICATION_RUNTIME_BINDING`
items with stable keys plus related `operatorRef` and owner `operatorLibraryId`,
so runtime-plane binding work can be filtered, counted, and assigned by
user-provided operator or operator-library ownership directly from the overview
without loading each full draft or immutable publication.
Draft and publication summaries also carry an exact
`operatorLibraryIdsByOperatorRef` map derived from dependency evidence; overview
actions and the runtime-binding index use the current catalog owner map when it
exists and fall back to that snapshot map when a target environment has not
installed the source operator library yet.
Connection candidate discovery exposes the same runtime-binding evidence at two
granularities: candidate `summary` describes the whole preview draft, while
`explanation.targetRuntimeBinding` is filtered to the current drop target node.
The browser's hover/connectability title prefers the target-scoped impact so a
schema-compatible target is not blamed for unrelated design-only nodes elsewhere
in the graph.
For external integration teams that need a factual queue rather than a
recommendation list, `/api/visual/assets/runtime-binding-requirements` exposes
the same gaps as `bloge.visualRuntimeBindingRequirements.v1`, scoped and
pageable by tenant/namespace/environment and filterable by target kind, operator
reference, owner operator library id, binding kind, handoff lane, handoff work
kind, handoff route target, source kind, lowering mode, readiness state, or
stable requirement key. The same
Workspace Overview panel renders that index with filters, paging, and
draft/publication Open actions, and can export the current filtered window as a
`bloge.visualRuntimeBindingHandoff.v1` bundle with source-index lineage,
scope/filter, stable requirement keys, operator/library/routing counts,
requirement rows, and operator contract snapshots that carry ports, config
schema, lowering, policy/capability, owner, fingerprint, and runtime-readiness
evidence. It can also review the latest exported handoff bundle against
the current read model. The review includes exported/current/new-window routing
distributions by operator, owner library, binding kind, handoff lane/work/target,
source, lowering, readiness, and artifact kind, so design-time binding work is
visible, portable, assignable, and replay-checkable from the canvas without
pretending the graph is executable.
Runtime teams can then submit a stateless
`bloge.visualRuntimeBindingImplementationBinding.v1` proposal to the
implementation validation endpoint; the server checks the submitted
operator contract snapshot, operator fingerprint, current catalog fingerprint,
implementation metadata, test evidence, policy evidence, and rollback target,
and returns ready-to-bind/requires-review/rejected. The same request can be
submitted to the implementation binding endpoint, which stores valid proposals
as `bloge.visualRuntimeBindingImplementationBindingRecord.v1` records with
their validation evidence. Lifecycle endpoints can then mark a reviewed proposal
as `bound` or supersede one active binding with another, preserving actor/reason
audit events and replacement lineage. These records are now control-plane
implementation facts; they still do not rewrite graph artifacts or pretend an
executable adapter exists before catalog/readiness projection consumes them.
The Drafts panel renders a Draft Asset Index from server-side draft summaries
for the active Authoring Scope, so active and recoverable deleted drafts expose
design-only, runtime-blocked, governance-review, repair-required readiness, and
the derived action gates for warning acknowledgement before the author loads a
specific draft.
After publication, the Publications panel also renders a Published Artifact
Index from `bloge.visualGraphPublicationSummary.v1` for the active Authoring
Scope, counting `EXECUTABLE` versus `DESIGN` artifacts and surfacing frozen
readiness/action-readiness states such as design-only, runtime-blocked,
governance-review, warning-evidence review, and repair-required before the
author loads a full immutable publication payload.
The Workspace Overview panel consumes `bloge.visualAssetOverview.v1` to show the
same readiness distribution across drafts, immutable publications, and the
current operator catalog for the active Authoring Scope. The overview response
echoes the authoring scope used to derive the read model, then adds a
server-derived action queue for repair, runtime-binding, governance-review,
warning acknowledgement/evidence review, and design-asset tracking work. Large
schema-only workspaces can therefore be
triaged without pulling every graph or artifact body, and without mixing assets
from another tenant, namespace, or environment. Queue items include navigation
targets and stable `actionKey` values, so the browser or an external governance
worker can open the affected draft or publication, focus the relevant operator
in the palette, and de-duplicate repeated recommendations without treating the
queue itself as a stateful workflow engine. The browser also exposes the action
queue's server-side severity, type, target-kind, operator library, operatorRef,
and page-window controls, so a large design-only workspace can be reviewed as a
bounded governance queue rather than a fixed demo list.
Authors can still publish the draft as a non-executable
`artifactKind=DESIGN` artifact to freeze the schema-valid composition for
review and later runtime binding. Design-only
operators do not declare `lowering.operatorRef`, and their schema surface is not
constrained by BLOGE DSL field-name rules that only matter for executable
lowering. Catalog responses derive `runtimeReadiness` server-side, so a
user-provided library cannot claim that a schema-only or runtime-blocked
operator is executable by injecting its own readiness metadata.
Imported libraries may also declare runtime-binding surfaces for
`source.kind = "remote-worker"` with `lowering.mode = "remote-worker"` and
`lowering.parameters.workerTopic`, or `source.kind = "ai-tool"` with
`lowering.mode = "ai-tool"` and `lowering.parameters.toolRef`. External boundary
operators use the same non-executable contract shape: `source.kind =
"event-source"` with `lowering.parameters.eventType`, `source.kind =
"message-handler"` with `lowering.parameters.channel`, or `source.kind =
"webhook"` with `lowering.parameters.method` and `lowering.parameters.path`.
These are not executed by the current request-response visual runtime: they
validate as schema-authorable runtime-blocked operators, appear in
source/lowering/readiness facets, can be saved/exported/published as `DESIGN`
artifacts, and fail
compile/run/executable publish with `visual.codegen.runtimeBindingUnsupported`
until a worker, AI-tool, event, message, or webhook runtime is attached.
`POST /admin/visual-operator-libraries/from-asyncapi` can preview-project
AsyncAPI JSON or YAML into the same external-boundary operator-library draft
shape, including webhook method/path, message channels, event payload schema,
server-derived profile, and replacement impact evidence. The projection is
validate-only: it does not store the generated library, so authors or external
control planes still import the reviewed library through the governed
operator-library import path. For larger protocol documents,
`POST /admin/visual-operator-libraries/from-asyncapi/operations` first discovers
channel/root-operation/message projection candidates with source-kind, payload,
and readiness summaries; the projection request can then provide
`operationId`, `channel`, `action`, and `messageName` selectors or a batch
`selections[]` list to generate only the reviewed subset. The projection result
returns `availableOperations`, `selectedOperations`, `omittedOperationCount`,
`selectionApplied`, and a `bloge.asyncApiProjectionReview.v1`
`projectionReview` with coverage status, per-selector match evidence, unmatched
selector count, omitted operation reasons, and projection/source-kind counts, so
browser and external control planes can audit exactly which large-spec
candidates were projected or skipped. A batch selector that no longer matches any
candidate is rejected with `visual.library.asyncapi.selectionMissing` instead of
silently generating a partial operator library. The browser Operator
Libraries panel exposes this as a `Discover AsyncAPI` -> operation
multi-selection -> `From AsyncAPI` preview action: authors paste AsyncAPI into
the same source editor, choose one or more candidates when needed, preview the
generated library with projected/available/omitted counts plus a structured
AsyncAPI Projection Review panel for coverage, selector match failures, omitted
operation reasons, projection level counts, and selected source-kind mix. When a
selector is unmatched or ambiguous, the panel shows the selector target and match
count so large-spec imports cannot hide a stale selection behind generic
diagnostics. Authors then reuse the existing Validate/Import path and warning
acknowledgement flow.
The same validation warning-gates imported operators that declare
streaming/durable runtime requirements, secret-backed execution, or
non-idempotent external side effects; these warnings keep high-risk operators
out of the catalog until the caller repeats the import with `ackWarnings=true`.
It also emits non-blocking fingerprint drift warnings when a same-`operatorRef`
replacement changes schema- or executable-relevant metadata used by stored
draft snapshots. Those warnings include a concise changed-surface summary and
machine-readable `metadata.changeRisk` / `changeCategories`, classifying
replacement risk as `BREAKING_SCHEMA`, `COMPATIBLE_SCHEMA`, `RUNTIME_BINDING`,
`GOVERNANCE`, `POLICY`, or `METADATA`; authors can distinguish compatible
schema growth from schema-breaking or runtime/governance changes before runtime
validation blocks execution. Drafts missing the affected node's fingerprint
snapshot are warned as legacy/unsafe-to-assume-compatible drafts.
Replacement validation also applies SemVer governance to the library version
itself, even when no stored draft currently references the changed operator:
version regression is a blocking error, breaking schema/removal changes require
a major bump unless acknowledged, and additive or compatible schema changes
require at least a minor bump unless acknowledged. The SemVer diagnostics carry
the same `changeRisk`, `changeCategories`, `changeSummary`, and `operatorRefs`
metadata as drift warnings, so the impact review can surface version-discipline
violations beside draft/publication impact.
The same validation warns when a replacement or removal touches immutable
publications that were authored with the affected operatorRef, also including
the changed-surface summary and affected publication node target for same-ref replacements. Existing publications
keep running from their frozen DSL, but the warning marks artifacts
that should be reviewed before replay, recertification, or republishing.
The same panel exposes a `Force` switch that passes `force=true` to validate,
import, replace, and delete requests when an author intentionally accepts the
stored-draft or published-artifact impact. When the edited JSON uses an existing `libraryId`, the
browser sends a `PUT` replace request; otherwise it sends a `POST` import. Import
and replace actions run the same validation preflight before mutating storage;
if validation returns only warnings, the panel keeps the request pending,
renders the server-provided impact review plus the structured diagnostics, and requires a second click on the same JSON,
same `Force` setting, and same warning diagnostics; that mutation request sends
`ackWarnings=true` before it writes the library. The warning acknowledgement
message is risk-aware: breaking schema, runtime binding, governance, policy,
compatible schema growth, and metadata drift get distinct review copy instead
of a generic warning banner. The impact contract also carries `changeRiskCounts`
plus `draftTargets` with affected node indexes. Affected draft chips in the
review are actionable: selecting one loads the draft through the normal draft
loader and focuses the impacted node so the author can inspect fingerprints,
schema drift, and bindings before choosing a rebase or repair path.

| Method | Path | Description | Status |
|--------|------|-------------|--------|
| `GET` | `/admin/visual-operator-libraries` | List imported operator libraries | 200 |
| `POST` | `/admin/visual-operator-libraries/validate` | Validate an operator library without storing it and return `valid`, `diagnostics`, server-derived `bloge.visualOperatorLibraryProfile.v1`, `bloge.visualOperatorLibraryImpact.v1`, and `bloge.visualOperatorLibraryImportReadiness.v1` with importable/design-only/runtime-binding/force/ack/evidence state; use `force=true` to suppress stored-draft removal/disablement impact diagnostics; runtime-capability, governance, unresolved native lowering executable, same-ref fingerprint drift, replacement SemVer governance, and immutable-publication impact risks are reported as warnings unless they are blocking errors such as version regression | 200 |
| `POST` | `/admin/visual-operator-libraries/validate-text` | Parse raw JSON or YAML operator-library source text on the server and then run the same validation/profile/impact/readiness review path without storing it; malformed source returns structured `visual.library.source.*` diagnostics | 200 / 400 |
| `POST` | `/admin/visual-operator-libraries/import-text` | Parse raw JSON or YAML operator-library source text on the server and store it through the same governed import/replace path, including `force`, `ackWarnings`, impact review, SemVer governance, and revision audit metadata; high-risk writes using `force=true` or `ackWarnings=true` require non-empty `actor` and `reason` evidence | 201 / 200 / 400 / 409 |
| `POST` | `/admin/visual-operator-libraries/from-asyncapi/operations` | Discover AsyncAPI channel/root-operation/message candidates from parsed `asyncApi` or raw JSON/YAML `asyncApiText` before projection; returns source-kind, payload, tags, and projection readiness summaries so browser or external control planes can select a reviewed subset | 200 |
| `POST` | `/admin/visual-operator-libraries/from-asyncapi` | Preview-project parsed `asyncApi` or raw JSON/YAML `asyncApiText` into a `bloge.visualOperatorLibrary.v1` draft using runtime-blocked `event-source`, `message-handler`, or `webhook` operators, optionally narrowed by single `operationId` / `channel` / `action` / `messageName` selectors or batch `selections[]`, then return `bloge.asyncApiOperatorLibraryImportResult.v1` with the generated library, validation/profile/impact evidence, `availableOperations` / `selectedOperations` / `omittedOperationCount` / `selectionApplied` projection-audit evidence, and `bloge.asyncApiProjectionReview.v1` coverage / selector-match / omitted-operation review; unmatched batch selectors are rejected with structured diagnostics instead of silently producing a partial library; this endpoint does not store the generated library | 200 |
| `POST` | `/admin/visual-operator-libraries/import-bundle` | Import a `bloge.visualOperatorLibraryExport.v1` bundle into the target registry through the same governed validation, warning acknowledgement, impact, SemVer, readiness, and revision audit path; unsupported export bundle schema versions are rejected before the library snapshot enters registry preflight; responses use `bloge.visualOperatorLibraryImportResult.v1` with source bundle identity, target mutation action, target latest revision, and target preflight validation/profile/impact/readiness evidence; high-risk writes using `force=true` or `ackWarnings=true` require non-empty `actor` and `reason` evidence | 201 / 200 / 400 / 409 |
| `POST` | `/admin/visual-operator-libraries` | Import or re-import an operator library; rejected or warning-gated responses include `bloge.visualOperatorLibraryProfile.v1`, `bloge.visualOperatorLibraryImpact.v1`, and `bloge.visualOperatorLibraryImportReadiness.v1`, reject removal or disablement of stored-draft operator refs unless `force=true`, require `ackWarnings=true` before storing warning-level runtime-capability, governance, executable-resolution, SemVer, or replacement impact, and accept optional `actor` / `changeSource` / `changeSummary` / `reason` query params for the stored registry revision audit metadata; high-risk writes using `force=true` or `ackWarnings=true` require non-empty `actor` and `reason` evidence | 201 / 400 / 409 |
| `GET` | `/admin/visual-operator-libraries/{libraryId}` | Get one imported library | 200 / 404 |
| `GET` | `/admin/visual-operator-libraries/{libraryId}/export` | Export the current library as `bloge.visualOperatorLibraryExport.v1`, including normalized library snapshot, latest registry revision evidence, and export-time validation/profile/impact result | 200 / 404 |
| `GET` | `/admin/visual-operator-libraries/{libraryId}/revisions` | List immutable create/replace/delete/restore registry snapshots for an imported library, newest first, including `revisionMetadata` audit fields; delete snapshots remain queryable after the current library is removed | 200 / 404 |
| `GET` | `/admin/visual-operator-libraries/{libraryId}/revisions/{revision}` | Load one immutable operator-library registry snapshot | 200 / 404 |
| `GET` | `/admin/visual-operator-libraries/{libraryId}/revisions/{baseRevision}/diff/{targetRevision}` | Compare two immutable registry snapshots as `bloge.visualOperatorLibraryDiff.v1`, including highest change risk, risk categories, summary, library-level changes, operator-level added/removed/changed surface, and operator change counts | 200 / 404 |
| `POST` | `/admin/visual-operator-libraries/{libraryId}/revisions/{revision}/restore` | Restore one immutable snapshot as a new latest library revision; uses the same validation, impact, and warning acknowledgement gates as replacement, blocks SemVer regression by default, allows controlled rollback only with `allowVersionRegression=true&ackWarnings=true`, and accepts optional registry revision audit metadata query params; high-risk writes using `force=true` or `ackWarnings=true` require non-empty `actor` and `reason` evidence | 200 / 400 / 404 / 409 |
| `PUT` | `/admin/visual-operator-libraries/{libraryId}` | Replace an imported library; rejected or warning-gated responses include `bloge.visualOperatorLibraryProfile.v1` and `bloge.visualOperatorLibraryImpact.v1`, reject removal or disablement of stored-draft operator refs unless `force=true`, reject SemVer regression, require `ackWarnings=true` before storing warning-level runtime-capability, governance, executable-resolution, SemVer, or replacement impact, and accept optional registry revision audit metadata query params; high-risk writes using `force=true` or `ackWarnings=true` require non-empty `actor` and `reason` evidence | 200 / 400 / 409 |
| `DELETE` | `/admin/visual-operator-libraries/{libraryId}` | Delete an imported library; rejects stored-draft references and published-artifact references unless `force=true`, returning `bloge.visualOperatorLibraryProfile.v1` and `bloge.visualOperatorLibraryImpact.v1` on conflict, and accepts optional registry revision audit metadata query params for the delete snapshot; forced deletes require non-empty `actor` and `reason` evidence | 204 / 400 / 409 |

Create and update run the same validator before storage. The validator accepts
only the `bloge.visualOperatorLibrary.v1` library contract and
`bloge.visualOperator.v1` operator contract before storage. It rejects
unsupported lifecycle status values, unsupported capability `effect` or
`idempotency` labels, non-semantic library/operator version tokens, blank or
namespace-unsafe `libraryId`, blank or duplicate `operatorRef`,
`operatorRef` values already owned by another stored library, system-reserved
refs such as `httpResource`, `bloge:decisionTable`, `bloge:transform`, and the
`resource:` and `publication:` namespaces, imported operators that claim
system-managed source kinds such as resource descriptors, published visual
graphs, or Java runtime operators, including case/whitespace variants of those
source kinds, arbitrary imported source kinds outside `user-library`,
`remote-worker`, `ai-tool`, `event-source`, `message-handler`, and `webhook`,
refs already projected from runtime Java
operators, user-supplied operator diagnostics, empty libraries, null operator
entries, null input/output port entries, duplicate port names, unsupported
lowering modes, runtime-binding source/lowering mismatches, remote-worker
lowering without `workerTopic`, AI-tool lowering without `toolRef`,
event-source lowering without `eventType`, message-handler lowering without
`channel`, webhook lowering without `method` or `path`,
design-only or runtime-binding lowering with an executable `operatorRef`, native lowering without a namespace-safe executable
`operatorRef`, native lowering that targets system-managed executors or
namespaces such as `httpResource`, `bloge:decisionTable`, `bloge:transform`,
`resource:*`, `publication:*`, and `visualPublication`, transform lowering without executable `assignments`, branch
lowering with data output ports, missing branch selector expressions, or branch
selector templates that do not resolve to scalar declared inputs, transform
assignments that do not match output schema fields or declared input template
references, assignment expressions that are statically known to violate the
declared output schema, parent-object assignments that cannot prove nested
required output fields, unsupported schema kinds, multi-concrete type arrays,
`required` fields not declared in `properties`, and array schemas without
`items` across input, output, and config schemas, plus input port names, non-default
output port names, or schema path fields that cannot be rendered as BLOGE DSL path segments
for executable lowering modes.
It also rejects unsupported
schema envelope format/version, JSON Schema remote or unresolved references,
unsupported composition/conditional keywords outside the safe object `allOf`
subset and supported `oneOf`/`anyOf` union subset, and constraint keywords the
canvas does not currently enforce, so
imported schemas cannot imply validation behavior that drag/drop hints, server
validation, or DSL generation will ignore. Schema
`default` values must
also match their declared type/kind, enum/`const` domain, numeric bounds and
`multipleOf` constraints, string length constraints, string pattern/format constraints,
array item-count constraints, `uniqueItems` constraints, object property-count
constraints, object `propertyNames`, `patternProperties`, and
`dependentRequired`/`dependentSchemas` constraints, array `prefixItems` and `contains` constraints, required object properties, array item schema, and `additionalProperties`/`unevaluatedProperties` policy so
canvas-generated default node config cannot start invalid. The browser consumes both root object defaults
and nested field-level defaults from `configSchema` when a node is dragged from
the palette. Schema `enum` and `const` values are held to the same array item
schema, prefix item schema, uniqueness, object required-field, nested property, and
`additionalProperties`/`unevaluatedProperties`/`propertyNames`/`patternProperties`/`dependentRequired`/`dependentSchemas` rules, so a user-imported fixed value domain cannot
describe objects or arrays that the surrounding schema would reject. Capability labels are trimmed and canonicalized to uppercase before
validation, and lowering modes are trimmed and canonicalized to lowercase, so
semantically valid imports are stored on one contract. Imported operators that
omit `source` are normalized to `source.kind=user-library`, keeping catalog
provenance deterministic instead of implying a built-in or partner-owned source.
Invalid libraries return structured visual diagnostics instead of
accepting a library that will fail later on the canvas.
When a native-lowered imported operator points at an executable
`lowering.operatorRef` that is not visible in the runtime Java inventory, the
admin API returns a warning-level diagnostic and requires `ackWarnings=true`
before create or replace stores the library.
Operator `policy.tenants`, `policy.namespaces`, and `policy.environments` are stored with
the library and enforced when scoped drafts use the operator. A policy scope may
be empty/unrestricted, a single `*`, or explicit values, but cannot mix `*` with
concrete values in the same scope. `DEPRECATED`
libraries stop appearing in the default palette but continue to resolve for
stored draft validation/compile/run, and the browser keeps their schemas
available only for existing nodes; use `DISABLED` when operators must be removed
from all executable authoring paths. User operator library changes are
impact-aware: by default the admin API rejects deletion, replacement, or
re-import with `409 CONFLICT` when the operation would remove an `operatorRef`
still used by any stored draft or make its library non-catalog-visible by
setting it to `DISABLED`, and `force=true` is required for an explicit
destructive change. Immutable publications are not invalidated by current
catalog changes because they keep frozen DSL and operator snapshots. The
validate endpoint reports publication-level warnings when a changed, removed, or
disabled operatorRef overlaps a published artifact, and direct library deletion
requires `force=true` when published artifacts still reference the library.
Browser DSL preview and server codegen keep
namespace-safe executable refs intact by quoting native
operator refs that cannot be written as bare BLOGE `IDENT(.IDENT)*` references,
for example `node policy : "risk:legacyPolicy"`. Native input lowering also
groups `targetPort`/nested `targetPath` bindings into object literals, so
schema-shaped inputs such as `applicant.score` compile as
`applicant = { score: ctx.score }` instead of illegal dotted input-field
assignments. Transform lowering also expands template references below a root
port object binding, so `{{input.customer.id}}` can render as `ctx.customer.id`
when the whole `customer` port is bound at once, and whitespace-padded imported
templates such as `{{ input.customer.id }}` lower through the same code path.
Native operator `configSchema`
values are also lowered as a business `config` input object while `timeout` and `retryAttempts` stay as
execution config; structured config expressions remain expressions inside that
object. If a native node also binds an operator input path that lowers to the
root `config` input field, the browser hides that ordinary input target once
business config is present, and draft validation plus connection preflight reject
the collision before codegen can emit ambiguous BLOGE input. Because the current BLOGE object-literal/path grammar does not support quoted
field names or bracket-style property access, object-template fields, graph
input schema path fields, context/node binding source and target path segments,
data edge endpoint paths, graph output-selection paths, native config keys,
authored expression reference path segments, `bloge:transform` assignment keys, and
decision-table input/output keys must be DSL-safe field identifiers, and non-default
output port names must also be DSL-safe because they lower into `node.output.<port>`
paths; validation/codegen return structured
diagnostics instead of emitting DSL that will fail later, and the browser filters
DSL-unsafe schema paths, unsafe input-port targets, and non-default output port names out of source pickers,
schema-derived node handles, default node input bindings, and path-specific graph output options, and hides
whole-output graph selections unless every output port can be rendered safely, and
normalizes graph-output defaults to the first legal option before authors can wire
them into a draft. Operator library
validation applies the same DSL-safe field-name gate to imported input ports,
input schemas, and output schemas across executable lowering modes, plus native config
schemas, including object fields nested under array `prefixItems` and dynamic
`additionalProperties`/`unevaluatedProperties`/`patternProperties` schemas, and transform assignment targets, returning
`visual.operator.lowering.dslField.invalid` before an unsafe library enters the
catalog. `GraphDraftValidator` repeats the non-default output-port gate for stored
or foreign catalog entries before `nodePath` bindings, expression references, data
edges, path-specific graph output selections, or whole-output graph selections can reach DSL generation. Graph names and node ids are also guarded against DSL reserved
identifiers before validation/codegen can emit an invalid `graph` or `node`
block. Branch lowering consumes `lowering.parameters.expression` as a template over the
operator's declared inputs, materializes that selector through a generated
`transform`, and renders `route` edges as branch cases. This keeps imported
branch operators declarative while honoring the BLOGE compiler requirement that
branch conditions read from node outputs.
Browser connection hints mirror the server's stricter schema rules
for object required-field proof, enum value-domain subsets, and supported
`oneOf`/`anyOf` visual union schemas. Target `oneOf` stays conservative unless
exactly one branch can receive the source, or the author selects a root
`targetUnionBranch` in the input inspector; that branch selection is saved on the
binding, sent with connection preflight, and reused by binding plus data-edge
validation. Local hints include the same kind of failure reason the server
returns while the server validator remains the publish/run authority. Draft compile and publish calls run
the generated DSL through the BLOGE compiler before returning success, so a user
library with a missing runtime native operator cannot produce a published artifact.

Minimal import example:

```bash
curl -X POST http://localhost:8080/admin/visual-operator-libraries \
  -H 'Content-Type: application/json' \
  -d '{
    "schemaVersion": "bloge.visualOperatorLibrary.v1",
    "libraryId": "risk-policy",
    "displayName": "Risk policy operators",
    "version": "1.0.0",
    "owner": "risk-team",
    "operators": [{
      "schemaVersion": "bloge.visualOperator.v1",
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
| `OperatorLibraryExportBundle` | Portable operator-library package with source identity, current library snapshot, latest registry revision evidence, and export-time validation/profile/impact/readiness result |
| `OperatorLibraryImportReadiness` | Server-derived operator-library import decision summary, separating importable/design-only/runtime-binding/governance/force/catalog-repair states from raw diagnostics and listing per-operator import-time runtime binding requirements with stable keys plus binding/handoff/owner/source/lowering/readiness distribution counts |
| `OperatorLibraryImportResult` | Target-environment result for importing an operator-library export bundle, including source identity, import decision, target latest revision, and target preflight validation/profile/impact/readiness evidence |
| `OperatorLibraryRevision` | Immutable create/replace/delete/restore audit snapshot for user-provided operator-library registry changes |
| `DatabaseOperatorLibraryRegistry` | H2-backed user operator-library registry with current catalog storage plus immutable revision snapshots, so imported operator catalogs and their governance history survive restart |
| `JavaOperatorInventoryProjector` | Projects registered BLOGE Java operators into visual operator definitions using registry metadata, schemas, annotations, and capabilities |
| `VisualGraphPublicationOperatorProjector` | Projects immutable publications into `publication:<publicationId>` schema-aware subgraph operators backed by frozen DSL |
| `DefaultVisualOperatorCatalog` | Combines native visual operators, Java registry operators, imported user libraries, executable publication-backed subgraph operators, and `resource:<resourceId>` virtual operators |
| `GraphDraft` | Editable canvas graph model: input schema, nodes, port-aware bindings, edges, layout, output selection, operator fingerprint snapshots, and node-level operator definition snapshots |
| `GraphDraftHistorySummary` | Lightweight active/deleted draft history index entry for browser and external recovery control planes, including latest revision actor/source/summary/reason |
| `GraphDraftDiff` | Machine-readable graph draft revision diff with graph/node/edge change surfaces, node and edge add/remove/change counts, and risk-classified review summaries |
| `GraphDraftDependencyReport` | Machine-readable dependency report used for stored drafts, migration bundles, import results, and frozen publications, with distinct operatorRef usage, owner operator-library counts, per-node binding/edge upstream and downstream lineage, source/lowering/readiness counts, saved-vs-current/scope-mismatch fingerprint state, and scope policy diagnostics |
| `GraphDraftRevisionRestoreRequest` | Governed restore request for turning one immutable draft revision into a new latest revision with optimistic locking and actor/source/summary/reason audit metadata |
| `VisualGraphReadiness` | Server-derived graph runtime/design readiness (`bloge.visualGraphReadiness.v1`) with node readiness rows and runtime binding requirements for schema-valid but non-executable design artifacts |
| `VisualGraphActionReadiness` | Server-derived graph action gate summary (`bloge.visualGraphActionReadiness.v1`) for compile, run, DESIGN publication, EXECUTABLE publication, warning acknowledgement, and governance evidence requirements |
| `VisualRuntimeBindingHandoffBundle` | Portable `bloge.visualRuntimeBindingHandoff.v1` snapshot derived from the runtime-binding requirement index for assigning schema-only/design-only runtime implementation gaps to external runtime-plane teams, including operator-library owner counts for batch routing and operator contract snapshots for schema/lowering/readiness implementation handoff |
| `VisualRuntimeBindingHandoffReview` | Read-only `bloge.visualRuntimeBindingHandoffReview.v1` reconciliation report that compares a handoff bundle with the current runtime-binding requirement read model by stable key, exported operator contract count, field changes, and exported/current/new-window routing distributions |
| `VisualRuntimeBindingImplementationValidation` | Stateless `bloge.visualRuntimeBindingImplementationValidation.v1` pre-bind gate that validates a runtime team's implementation proposal against a handoff operator contract snapshot, current catalog fingerprint, implementation metadata, test evidence, policy evidence, and rollback target without closing runtime-binding workflow state |
| `VisualRuntimeBindingImplementationBinding` | Persistent `bloge.visualRuntimeBindingImplementationBindingRecord.v1` proposal/lifecycle record with validation snapshot, implementation metadata, bound/superseded state, actor/reason lifecycle events, and supersede lineage |
| `VisualRuntimeBindingImplementationLifecycleResult` | Mutation response for bind/supersede lifecycle transitions, returning accepted/rejected state, affected binding records, and structured diagnostics |
| `GraphDraftExportBundle` | Portable draft package with source identity, draft snapshot, operator snapshots, export-time diagnostics, validation/readiness/action-readiness, and source dependency report |
| `GraphDraftImportResult` | Import response contract with source bundle/draft identity, stored draft identity, target-environment compatibility diagnostics, validation/readiness/action-readiness, source dependency report, target dependency report, target runtime-binding handoff requirements and stable keys, and legacy target `dependencyReport` |
| `DatabaseGraphDraftRepository` | H2-backed graph draft repository with revision assignment, immutable revision history, expected-revision guarded updates, deletion audit snapshots, and retained history for deleted-draft recovery |
| `GraphDraftValidator` | Validates the `bloge.visualGraphDraft.v1` draft contract, `bloge.visualLayout.v1` presentation contract including node/edge coverage, operator references, operator fingerprint drift, operator scope policy, request-response runtime capability gates for streaming/durable/remote-worker/AI-tool/event-source/message-handler/webhook operators, schema-only design operator authoring, non-idempotent side-effect and secret-backed execution governance warnings, graph input `contextPath` bindings, binding kind and edge kind allow-lists, literal constants, expression references, required schema inputs, node config against `configSchema`, port-aware node bindings, DSL-safe source/target/output port segments, typed data edges, explicit dependency edges, branch route edges, edge identity/connection uniqueness, data edge/semantic dependency consistency, DAG shape, output schema selection, and output-reachability warnings for dangling nodes |
| `VisualConnectionCheckService` | Reuses draft validation to accept or reject one proposed canvas edge before the browser writes a binding, including schema, source/target port-segment, path diagnostics, a stable decision/replacement/runtime-binding summary, and candidate draft validation/readiness/action-readiness; also exposes source-endpoint target candidate discovery so a canvas can ask the server for accepted/blocked/non-executable drop targets before authoring the edge |
| `GraphDraftDslGenerator` | Lowers visual drafts into executable BLOGE DSL, and deterministically blocks schema-only design operators until runtime lowering is bound |
| `VisualGraphRunService` | Reuses the dynamic BLOGE runner to validate draft input context, compile, and execute visual drafts or frozen publications while returning draft or frozen-publication validation/readiness/action-readiness |
| `InputCoercingOperatorRegistry` | Runtime adapter used by the dynamic runner to coerce visual DSL map inputs into Java DTO/record operator inputs before execution |
| `VisualGraphPublicationOperator` | Reserved runtime executor for publication-backed subgraph operators; resolves the immutable publication and executes its frozen DSL with recursion-depth protection |
| `VisualGraphPublication` | Immutable published visual graph artifact with DSL, draft, operator schema snapshots, fingerprints, layout, validation reports, publication metadata, and frozen dependency report |
| `DatabaseVisualGraphPublicationRepository` | H2-backed immutable publication repository |

Publication-backed operators expose the published graph input/output schemas to the
canvas, but keep `publicationId` and the selected output node service-managed.
Hand-authored draft config for those fields is rejected during validation and
ignored during DSL lowering, so the executable subgraph remains bound to the
immutable publication artifact selected by the catalog.

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
| `DatabaseOperatorLibraryRegistry` | Persists imported visual operator libraries and immutable revision snapshots in H2 as JSON blobs with cache-backed reads |
| `OperatorLibraryDiff` | Compares two immutable operator-library revision snapshots into library-level and operator-level change-risk review data |
| `DatabaseGraphDraftRepository` | Persists visual graph drafts and revision numbers in H2, preserving immutable revision history after current draft deletion |
| `GraphDraftDiff` | Compares two immutable draft revision snapshots into graph-level, node-level, and edge-level change-risk review data |
| `ResourceRegistryAdminController` | REST CRUD at `/admin/resources` |
| `OperatorLibraryAdminController` | REST import/update/delete/revision/diff at `/admin/visual-operator-libraries`, including warning-gated runtime capability, SemVer, and governance preflight |

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
| `VisualOperatorCatalogController` / `VisualOperatorUsageController` / `VisualGraphDraftController` | Visual operator discovery, usage impact indexing, draft revision diff/restore, validation, compilation, and execution |
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
      "queryExpressions": {},
      "headerExpressions": {
        "X-Request-Id": "ctx.params[\"X-Request-Id\"]"
      },
      "cookieExpressions": {}
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
      "resourceId": "user-service.getProfile",
      "X-Request-Id": "readme-req-1"
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
      "queryExpressions": {},
      "headerExpressions": {},
      "cookieExpressions": {}
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

The test suite is organised into four layers (69 test report classes, 949 test
cases reported by Surefire, including nested JUnit suites):

### Layer 1 — Unit tests

Pure-logic tests with no Spring context.

| Class | Tests | Scope |
|-------|-------|-------|
| `BlgeExpressionEvaluatorTest` | 21 | Expression compilation, evaluation, caching |

### Layer 2 — Contract / component tests

Isolated component tests, some with lightweight Spring slices or mocks.

| Class | Tests | Scope |
|-------|-------|-------|
| `HttpResourceOperatorTest` | 17 | Descriptor resolution, parameter mapping, dynamic header/cookie mapping, URL rendering, form body encoding, DSL map-input normalization |
| `ResponseValidatorTest` | 22 | All five `ResponseProtocol` variants |
| `ResponseCacheInterceptorTest` | 4 | Cache hit/miss, TTL expiry |
| `TenantRateLimiterInterceptorTest` | 3 | Token bucket, quota enforcement |
| `CircuitBreakerInterceptorTest` | 5 | State transitions, cool-down |
| `DatabaseResourceRegistryTest` | 13 | CRUD, H2 persistence, expression validation, in-memory cache |
| `ResourceDescriptorBootstrapTest` | 7 | Seeding, refresh behavior, idempotency |
| `GatewayDslCompilationTest` | 7 | DSL parsing, graph loading |
| Gateway example API suite | 29 | Dynamic composer service/controller, visual runtime context, system metadata context, selected array output paths, array-index context binding runtime lowering, output-node override gates, design publication run blocking, publication-backed subgraph runtime smoke path, scenario catalog, and example graph endpoints |
| Visual authoring suite | 798 | Visual operator projection including Java `UnionSchema` oneOf projection, durable capability projection for suspendable Java operators, server-derived graph runtime/design readiness contract, server-derived operator runtime readiness and spoof-resistant readiness derivation, operator usage index and fingerprint status API, resource design contract persistence and gates, resource-contract lifecycle/catalog gates, resource-contract in-use delete/disable protection, resource-contract fingerprint drift/missing-snapshot preflight warnings, resource-contract warning acknowledgement and changed-surface drift summaries, governance-evidenced high-risk resource-contract mutations, imported libraries, server-derived operator-library profile contract, namespace-safe library-id, semantic-version, system-source-kind, server-managed operator diagnostics, null operator/port import diagnostics, null stored-library operator/port resilience and query-scoped catalog-level hidden-operator diagnostics, system operatorRef namespace, runtime Java inventory collision and catalog shadowing, native lowering executable-resolution warning gates, schema-only design lowering gates, design artifact publication and persistence gates, browser-selectable readiness-constrained design publication mode, import-time runtime capability and governance warning gates, and system-managed executable lowering gates, registry-aware and impact-aware library validation, catalog lifecycle gates, deprecated operator draft resolution and active-scope fingerprinting, catalog token gates, policy filtering, catalog source/lowering/capability/runtime-readiness facet filtering, and catalog facets response counts, multi-term catalog search by input/output/config schema fields and field types, policy wildcard scope gates, cross-library operatorRef ownership, operator-library in-use change protection, operator-library immutable revision history, revision audit metadata, revision diff change-risk review, governed restore, schema-version-gated portable operator-library export/import bundles, governance-evidenced high-risk operator-library mutations, and warning-acknowledged operator-library replacement, same-ref fingerprint drift/missing-snapshot preflight warnings with breaking/compatible/runtime/governance/policy/metadata change-risk classification, executable publication-backed subgraph catalog projection, design publication catalog exclusion, named-output and whole multi-output schema projection, frozen-id DSL lowering, and service-managed publication config gates, immutable-publication operator drift/removal impact warnings, immutable-publication delete protection, system-reserved operatorRef gates, import-time lowering gates including DSL-safe field-name gates for executable input ports, input schemas, output ports, output schemas, nested array prefix items, dynamic object schemas, and `oneOf`/`anyOf` union schemas, branch lowering gates, schema default value gates, nullable type-array gates, local `$defs` reference and safe object `allOf` normalization gates, transform assignment output-schema and nested required-output guarantee gates, OpenAPI operation discovery, text multipart/form-data projection, binary multipart warning gates, auth/security descriptor suggestions, descriptor/schema diff warnings, and unsupported-security diagnostics, AsyncAPI operation/message discovery and selected-subset operator-library projection with available/selected/omitted projection-audit evidence, unsupported schema envelope and JSON Schema keyword gates, const value-domain gates, enum/const array-object shape gates, `oneOf`/`anyOf` runtime and connection compatibility gates, request-response runtime capability gates for streaming/durable operators, design-only draft validation with compile-time executable blocking, non-idempotent side-effect and secret-backed execution governance warnings, publish-time warning acknowledgement governance evidence gates, explicit target union branch selection validation/preflight, visualLayout schemaVersion/rootId/operatorRef/reference/node-edge coverage/group id/group membership/edge-kind/metadata/geometry/viewport contract gates, numeric bound/`multipleOf`, string length, string pattern/format, array item-count, array `uniqueItems`, array `prefixItems`, array `contains`, object property-count, object `propertyNames`, object `patternProperties`, object `dependentRequired`, object `dependentSchemas`, and object `unevaluatedProperties` schema gates, built-in and virtual catalog schema-gate parity, draft/publication persistence, history, draft deletion history retention, draft history index, deleted draft recovery, draft revision diff change-risk review and governed restore, stored draft dependency report with missing-catalog snapshot fallback and scope-mismatch policy diagnostics, browser Drafts dependency report panel with fingerprint-drift rebase action, unsaved-local-edit rebase guard, rebase revision-conflict recovery, and catalog-missing/scope-mismatch no-rebase review, portable export/import bundles with target-environment diagnostics, import-result dependency reports and import revision audit evidence, publish-time frozen publication dependency reports, publication-bound golden regression case persistence/run/suite/certification diagnostics, golden case save-time schema-version/context/output-node/assertion gates, golden case delete lifecycle, golden assertion modes including output-schema and numeric tolerance assertions, and stale-aware golden certification promotion gate, run trace replay shape summaries, node diagnostic attribution, node health aggregation, canvas replay badges, and replay coverage mismatch warnings, draft lifecycle status gates, revision audit metadata, full-save/PATCH fingerprint preservation, explicit operator fingerprint snapshot rebase with revision and catalog-scope guards, service-managed fingerprint snapshot gates, structured malformed patch diagnostics, server-assigned create identity, revision-guarded full-save, patch, stored-run, delete, and publish conflict handling, operator fingerprint drift preservation and execution snapshot coverage gates, typed connection/edge validation including nullable-source compatibility and local `$defs` references, edge identity uniqueness plus binding and edge kind allow-lists, binding and edge kind canonicalization, explicit dependency edge validation/preflight/DSL lowering, branch route edge validation/preflight/DSL lowering, selector-domain gates, and semantic route-condition duplicate gates, static literal expression gates, input/config/root-port source-picker server preflight with duplicate-connection rejection, server-authoritative fallback after local heuristic mismatch, dynamic decision-table output type schema preflight, and DSL-safe endpoint/source-target-port diagnostics, root-array bracket lowering, and array config target shape preservation, post-drop binding simulation, canonical binding keys for duplicate-path input ports including residual `unevaluatedProperties` dynamic paths, existing input-binding replacement during connection preflight, draft-contract/input-schema blocker preservation, and nested config paths, duplicate target input ownership, root-port object binding from context and upstream operator output, stable root-port input keys, object required fields, object schema structure gates, required-array schema gates, nested input/config objectTemplate required fields, DSL-safe objectTemplate field diagnostics, and object-compatible targets, enum value-domain and shape gates, standard JSON Schema config enum gates, standard JSON Schema config const gates, numeric bound/`multipleOf`, string length, string pattern/format, array item-count, array `uniqueItems`, array `prefixItems`, array `contains`, object property-count, object `propertyNames`, object `patternProperties`, object `dependentRequired`, object `dependentSchemas`, and object `unevaluatedProperties` config gates, nested config expression references and configSchema type gates, native config input lowering, native config/input root collision diagnostics, and DSL field-key diagnostics, transform assignment field-key diagnostics, decision-table input/output field-key diagnostics, graph/node/input-schema DSL identifier diagnostics including dynamic object schemas, graph input schema path-field, binding source/target path-segment, data-edge/output-selection path-segment, draft source/target/whole-output port-segment diagnostics, and array-index path diagnostics, operator-library array-index template gates, codegen bracket lowering for schema array-index paths, bracket expression schema validation and expression-edge consistency, root-array bracket expression schema validation and dependency extraction, canonical array-index gates in connection preview and publication projection, and expression reference path-segment diagnostics, browser DSL-safe schema source filtering including array item output handles/options, transform/branch template whitespace lowering, unbound optional lowering-template defaulting, data edge/semantic dependency consistency, bracket expression dependency extraction, graph input schema gates, runtime context value diagnostics, secret blocking, DSL lowering, compiler gating, dependency ordering, runtime smoke path, browser-facing workflow smoke path including operator usage index and explicit fingerprint rebase API, OpenAPI JSON/YAML contract/descriptor preview/save UI wiring, draft export/import, run-history node-stats API/UI wiring, and publication golden case assertion/run/suite/certification/status/delete wiring, Selenium Chrome DOM smoke for OpenAPI contract/descriptor preview/save, draft export/import, transform policy binding clear/save/export, node delete downstream binding cleanup/save/export, duplicate-path multi-port binding save/export, duplicate dynamic `unevaluatedProperties` target-path add/bind/save/export, mixed declared plus dynamic `unevaluatedProperties` target-path add/bind/save/export, duplicate dynamic `unevaluatedProperties` source-path add/bind/save/export/restore, server-side dynamic `unevaluatedProperties` source-path output-selection and connection preflight gates, dynamic additional-, pattern-, and property-name context binding save/export, and golden case save/run/suite/certification status/delete UI, user-library JSON/YAML import, AsyncAPI operation discovery/selection and selected operator-library browser projection/import with projected/available/omitted status, palette-to-canvas drag, schema-aware connection drag, schema-incompatible connection rejection without DSL pollution, route/dependency/config connection drag, no-input user-operator UI schema projection, server validation failure diagnostics rendering, selected-node diagnostics inspector rendering, Server Check diagnostic summary/filter rendering, selected-node duplicate/delete button/shortcut affordance, operator-library impact review wiring, run history query/filter/stats UI, draft revision preview/restore/delete, draft save/run/publish, browser readiness-gated DESIGN artifact save/export/publish with Compile/Run Custom Graph and publication run/golden disabled for non-executable visual readiness, page warning diagnostics, selected-node fingerprint snapshot rebase affordance, and publication run |
| Browser asset JS suite | 13 | Non-Selenium `app.js` probe for bracket array-index DSL helpers, whitespace-tolerant template descendants, context/source expression rendering, default array-path binding generation, structured binding round-trips, target union branch binding serialization, multi-port root-array parsing, browser-side `oneOf`/`anyOf` union schema structural diagnostics, readable type labels, value matching, conservative connection compatibility hints, explicit target branch local compatibility selection, selected-operator contract branch summaries, and operator-library profile branch summaries, DSL-safe static/dynamic source and target schema handle filtering, unsafe input-port target filtering and default-input seeding suppression, plus unsafe and mixed output-port source/output option filtering plus graph-output default normalization, binding candidate compatibility summaries, compatible-first grouped source candidate ordering, required-input auto-bind candidate planning and server-preflight wiring, selected-node connectability summaries, blocked previews, server-backed blocked-reason labels, and quick-connect preflight wiring, operator-library profile statistics, AsyncAPI operator-library discovery/preview wiring and projection-audit message rendering, server-provided operator-library profile rendering, impact review summaries with affected-draft actions, replacement change-risk counts, and risk-aware acknowledgement copy, selected-library export wiring, and per-operator schema summaries for port inventory, required fields, dynamic schema surfaces, DSL-unsafe input/output fields/ports, design-only authoring badges/profile summaries, streaming/durable runtime requirements, effects, non-idempotent side effects, secret-bound operators, scope-restricted policy summaries, and safe HTML rendering, multi-term palette search by input/output/config schema fields and field types, native config/input root target filtering, golden output-schema and numeric tolerance assertion payload generation, queued multi-assertion payload generation, array config path preservation, array config unknown-field diagnostics, graph input dynamic-object DSL diagnostics, OpenAPI operation discovery selector, media labels, readiness-summary helpers, and frozen-publication readiness review helpers, frozen-publication dependency summary helpers, operator catalog diagnostic rendering/search, source/capability/runtime-readiness/lowering palette facet filtering, catalog readiness facet summary rendering, operator runtime readiness panel rendering with server-provided readiness preferred over browser fallback, compile/run/connection preflight readiness preservation, readiness-aware Compile/Run action gating, server-authoritative connection candidate preview with local fallback, server-authoritative connection preflight messaging and advisory status after local schema heuristic mismatch, local builder undo/redo history stack behavior, draft revision diff review and restore wiring, canvas node search, visual diagnostic summary/filter/queue rendering, selected-node diagnostics panel isolation, selected-node duplicate/delete button/shortcut behavior, node impact lineage summaries with incoming/downstream relation clearing and bulk graph-output detach, selected-node operator usage index rendering with change-risk guidance, fingerprint snapshot status/rebase wiring, publish warning acknowledgement retry wiring, and canvas usage badge status mapping, graph output contract summaries, diagnostic target and visualLayout pointer resolution, visualLayout group region/kind helpers and canvas band rendering source/styling, run trace summary counts, replay badge labels, replay coverage mismatch summaries, and array config reference cleanup |

The Selenium DOM smoke prefers `-Dwebdriver.chrome.driver` or a cached Selenium
ChromeDriver under `~/.cache/selenium/chromedriver`, so local browser tests do
not hang on Selenium Manager discovery. It is skipped with a JUnit assumption
when Chrome/WebDriver cannot be started, and repeated browser startup failures
are cached inside the test JVM so the rest of the DOM smoke exits quickly.
It also covers browser-rendered bracket DSL for schema array-index connections.

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
| `ResourceRegistryAdminControllerTest` | 9 | Admin CRUD and descriptor JSON round-trip via MockMvc |
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
