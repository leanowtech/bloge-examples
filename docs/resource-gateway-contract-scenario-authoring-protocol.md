# Resource Gateway Contract & Scenario Authoring Protocol

> Status: Stage 3 semantic compatibility and guided Scenario migration implemented for Graph and
> Operator targets; portable workspace v1 remains Graph-scoped
> Protocols: `bloge.contractDraft.v1`, `bloge.scenarioDraftSet.v1`,
> `bloge.graphContractSemantics.v1`, `bloge.scenarioContractProjection.v1`,
> `bloge.visualAuthoringWorkspaceBundle.v1`, `bloge.scenarioPublicationReport.v1`,
> `bloge.contractCompatibilityReport.v1`

This document is the code-facing companion to
[the evolution plan](resource-gateway-contract-scenario-authoring-evolution-plan.md).
It records the protocol boundary that later UI, persistence, publication, VS Code, and ANEKE
integration work must preserve.

## Product Model

The authoring lifecycle is:

```text
Contract
  input/output/error/effect/invariants/compatibility
    ↓ exact contract fingerprint
Scenario
  Given input + Dependencies behavior + Then assertions
    ↓ compile
SimulationRequest or FixtureBundle + TestSuite
    ↓ run
Run Evidence
```

`ContractDraft` and `ScenarioDraftSet` are mutable authoring assets. They are not execution
evidence and do not replace immutable testing control-plane assets.

Author-facing names and wire names intentionally differ:

| Author-facing concept | Authoring protocol | Existing execution protocol |
|---|---|---|
| Contract | `ContractDraft` | GraphDraft schema, operator contract, exported GraphContract |
| Graph Input | `ScenarioDraft.given.input` | `SimulationRequest.context`, `TestSuite.TestCase.input` |
| Dependency Behavior | `DependencyBehaviorDraft` | `NodeFixture`, `FixtureRule` |
| Expected Result | `AssertionDraft` | `FixtureBundle.Assertion` |
| Scenario | `ScenarioDraft` | one exact FixtureBundle ref plus one TestSuite case |

## Authoritative Schemas

- [Contract Draft](schemas/bloge-contract-draft-v1.schema.json)
- [Scenario Draft Set](schemas/bloge-scenario-draft-set-v1.schema.json)
- [Scenario Validation Report](schemas/bloge-scenario-validation-report-v1.schema.json)
- [Stored Scenario Draft Set](schemas/bloge-stored-scenario-draft-set-v1.schema.json)
- [Scenario Contract Projection](schemas/bloge-scenario-contract-projection-v1.schema.json)
- [Graph Contract Semantics](schemas/bloge-graph-contract-semantics-v1.schema.json)
- [Visual Authoring Workspace Bundle](schemas/bloge-visual-authoring-workspace-bundle-v1.schema.json)
- [Scenario Publication Report](schemas/bloge-scenario-publication-report-v1.schema.json)
- [Stored Scenario Publication](schemas/bloge-stored-scenario-publication-v1.schema.json)

All schemas use JSON Schema 2020-12, reject unknown top-level fields, and bind target and Contract
identity with exact SHA-256 fingerprints.

## Java Boundaries

| Responsibility | Implementation |
|---|---|
| Contract protocol | `visual.contract.ContractDraft` |
| Graph/Operator projection | `visual.contract.ContractDraftProjectionService` |
| Embedded graph semantics | `visual.contract.GraphContractSemantics` |
| Scenario protocol | `authoring.scenario.ScenarioDraftSet` |
| Exact-input validation | `authoring.scenario.ScenarioValidationService` |
| Validation report | `authoring.scenario.ScenarioValidationReport` |
| Transient compilation | `authoring.scenario.ScenarioSimulationCompiler` |
| Compiled transient plan | `authoring.scenario.ScenarioSimulationPlan` |
| Mutable Scenario repository | `authoring.scenario.ScenarioDraftSetRepository` |
| H2 persistence adapter | `authoring.scenario.DatabaseScenarioDraftSetRepository` |
| Authenticated authoring service | `authoring.scenario.ScenarioDraftSetAuthoringService` |
| Authoring HTTP surface | `authoring.scenario.ScenarioDraftSetController` |
| Server-authoritative Contract coordinate | `authoring.scenario.ScenarioContractProjection` |
| Governed compiler | `authoring.scenario.ScenarioGovernedCompiler` |
| Recoverable publication saga | `authoring.scenario.ScenarioPublicationService` |
| Immutable Contract baseline | `authoring.scenario.ScenarioContractBaseline` |
| Compatibility analyzer | `authoring.scenario.ScenarioContractCompatibilityService` |
| Compatibility report | `authoring.scenario.ContractCompatibilityReport` |

The Java records deeply freeze payload-bearing maps and lists. Cyclic values fail closed before
serialization or fingerprinting.

## Durable Authoring API

The Stage 2 persistence slice is available only in `test` and `staging` profiles:

| Method | Path | Purpose | Meaning |
|---|---|---|---|
| `POST` | `/api/visual/scenario-draft-sets/validate` | `TEST_SUITE_WRITE` | Validate a local draft against the current exact graph and Contract |
| `PUT` | `/api/visual/scenario-draft-sets/{id}?expectedRevision=N` | `TEST_SUITE_WRITE` | Create at revision `0` or update the exact revision observed by the caller |
| `GET` | `/api/visual/scenario-draft-sets/{id}` | `TEST_SUITE_READ` | Read the current revision in the authenticated enterprise scope |
| `GET` | `/api/visual/scenario-draft-sets/{id}/revisions` | `TEST_SUITE_READ` | Read immutable retained history newest first |
| `GET` | `/api/visual/scenario-draft-sets/targets/graphs/{draftId}/contract` | `TEST_SUITE_READ` | Reproject the exact stored Graph as a server-authoritative Contract coordinate |
| `GET` | `/api/visual/scenario-draft-sets/targets/operators/{operatorRef}/contract` | `TEST_SUITE_READ` | Project one policy-visible catalog Operator as a server-authoritative Contract coordinate |
| `GET` | `/api/visual/scenario-draft-sets/{id}/compatibility?revision=N` | `TEST_SUITE_READ` | Compare one retained Contract baseline with the current authoritative target |
| `POST` | `/api/visual/scenario-draft-sets/{id}/publications?revision=N` | `TEST_SCENARIO_PUBLISH` | Publish one exact retained revision as immutable fixtures and suite |

The body scope must exactly match the authenticated tenant, organization, project, environment, and
region. The service independently resolves the stored GraphDraft or catalog Operator, recomputes or
reads its authoritative fingerprint, projects the current Contract, verifies the Contract
fingerprint, validates Scenario values, scans for raw credentials, and then applies optimistic
concurrency. Operator tenant/environment policy is enforced; namespace-only policy fails closed
until the Scenario target scope has an explicit namespace coordinate. A conflict returns
`RG.SCENARIO.REVISION_CONFLICT` with the current revision; it never silently overwrites another
author's work.

The stored envelope carries a canonical fingerprint and is re-verified when read from persistence.
Scenario authoring storage is mutable by revision, while every retained revision remains immutable.
Saving is not publishing: it grants no fixture, suite, execution, or certification status.

The browser must not hash its pre-save Graph and assume that coordinate remains authoritative.
Saving a Graph can add the retained revision and resolved operator snapshots. The client therefore
reads `bloge.scenarioContractProjection.v1` after Graph save, rebases explicitly to that exact
target/Contract coordinate, and only then saves the Scenario. The workspace enables publication
only for a clean retained Scenario revision.

## Frontend Boundaries

| Responsibility | Implementation |
|---|---|
| Contract/Scenario domain | `src/contract-scenario/domain.ts` |
| Canonical browser fingerprint | `src/contract-scenario/fingerprint.ts` |
| Schema projection and path access | `src/contract-scenario/schemaWorkbench.ts` |
| Existing test-case projection and result comparison | `src/contract-scenario/scenarioAuthoring.ts` |
| Transient compiler | `src/contract-scenario/scenarioCompiler.ts` |
| Contract rail | `src/contract-scenario/ContractRail.tsx` |
| Contract/Scenario workspace | `src/contract-scenario/ContractScenarioWorkspace.tsx` |
| Schema field tree and value form | `src/contract-scenario/SchemaFieldTree.tsx`, `SchemaValueForm.tsx` |
| Complete dependency behavior editor | `src/contract-scenario/DependencyBehaviorEditor.tsx` |
| Scope-aware assertion builder | `src/contract-scenario/AssertionBuilder.tsx` |
| Structured Contract semantics | `src/contract-scenario/ContractSemanticsEditor.tsx` |
| Portable workspace verification | `src/contract-scenario/workspaceBundle.ts` |

`AuthorCanvas.tsx` does not own these protocol definitions. It opens the workspace, supplies the
current exact target, projects existing canvas examples/table cases, executes compiled simulation
requests, and applies the resulting canvas state.

The workspace provides four views:

1. **Interface** projects graph input/output schemas as field trees, provides structured controls
   for execution effect, idempotency, stable errors, compatibility, and invariants, and preserves
   the complete Contract in Advanced JSON.
2. **Scenarios** edits Given input; REAL, RETURN, ERROR, DELAY, TIMEOUT, REPLAY, OBSERVE, and
   MUST_NOT_CALL dependency behavior; node/operator/resource/function selectors; attempts,
   occurrences, input matches, consumption and schema waiver; plus output, node, edge, status, and
   invocation assertions through schema-driven controls.
3. **Compatibility** exposes exact target and Contract coordinate drift and requires explicit
   rebase.
4. **Run Evidence** compares assertions with the latest exploratory response and shows node status.

The workspace header exposes four independent lifecycle actions. **Save Graph** establishes the
server coordinate; opening a stored target automatically attempts to resume the latest retained
Scenario revision, while **Load Scenario** remains the explicit refresh command; **Save Scenario**
applies optimistic concurrency only when the Scenario is current and dirty; **Publish** requires a
clean saved revision and the separate publisher purpose. A missing retained Scenario is a normal
first-authoring state and is silent. A real optimistic-concurrency conflict is returned through the
stable integration problem contract as retryable HTTP 409 rather than leaking as HTTP 500. Disabled
controls use a neutral visual state so an unavailable governed action is not mistaken for an active
command.

Operator Scenario asset ids contain a bounded readable prefix plus the complete SHA-256 digest of
the exact operator reference. This prevents normalized references such as `risk:score` and
`risk-score` from colliding. Every automatic or manual load also verifies the returned asset id and
exact target kind/id before accepting the payload.

The header also exposes **Export Workspace** and **Import Workspace**. Export constructs the bundle
from the exact authoritative Graph object whenever the canvas still matches its saved snapshot.
Import preserves authored node positions, installs the bundled Scenario revision and Contract
coordinate atomically in the browser, and never silently runs auto-layout over reviewed diagrams.

Selecting an advanced dependency behavior never produces a weaker simulation. The frontend
transient compiler retains the behavior and returns a fail-closed diagnostic; the server-side
governed compiler is the only path that may lower those semantics into testing-control-plane
assets.

Dependencies can be added and removed graphically. A new Operator-target dependency starts with an
Operator selector, so its normal authoring path never requires Advanced JSON.

## Contract Compatibility And Guided Migration

Every Scenario revision saved through the authoring service captures the exact server-authoritative
Contract in `visual_scenario_contract_baselines`, keyed by the same enterprise scope, asset id, and
revision. The snapshot is integrity-verified when read. It is not embedded in
`bloge.scenarioDraftSet.v1`, so existing strict v1 clients do not receive a new field.

`bloge.contractCompatibilityReport.v1` contains:

- exact retained Scenario revision and old/new Contract fingerprints;
- current target coordinate and applied compatibility policy;
- field-level INPUT, OUTPUT, and CONTRACT findings;
- deterministic `UNCHANGED`, `COMPATIBLE`, `BREAKING`, or `REVIEW_REQUIRED` classification;
- exact impacted Scenario ids and paths;
- safe or manual migration actions;
- a canonical report fingerprint that excludes `generatedAt`.

The deterministic subset handles object fields, requiredness, scalar type sets, enum sets, bounded
constraints, arrays, and explicit `x-bloge-renamed-from`. Input and output changes are classified in
their correct variance direction. A remaining `$ref`, conditional/composition keyword, unsupported
array tuple, unknown keyword, non-schema semantic change, changed runtime target, or missing legacy
baseline is never guessed compatible; it becomes `REVIEW_REQUIRED`.

The UI applies only edits that do not invent values: declared defaults, removed Given fields,
explicit input renames, and explicit output assertion rebinds. It leaves the draft stale until the
author records a review. That rebase stores the report fingerprint, source revision, old/new
Contract fingerprints, finding ids, and review time in Scenario provenance. The returned draft must
still pass current Contract validation, be saved as a new revision, rerun, and republished.

The report does not yet contain historical publication reverse indexes or an ANEKE gate decision.
Those require exact publication lineage indexing rather than inference.

## Editable Contract Semantics

Graph input/output schemas remain first-class `GraphDraft` fields. Non-schema promises are stored
under the versioned `visualLayout.graphContract.contractSemantics` extension as
`bloge.graphContractSemantics.v1`:

- stable error code, type, meaning, and retryability;
- PURE, READ, WRITE, or UNKNOWN effect;
- idempotency, streaming, and durability declarations;
- WRITE reconciliation protocol, reconciler reference, reversibility, and extension metadata;
- preconditions and postconditions;
- compatibility mode and UNKNOWN migration policy;
- JSON-Pointer keyed field governance metadata.

The server projects these fields into the authoritative `ContractDraft` and rejects unsupported or
malformed embedded semantics before Graph persistence. The browser preview deliberately ignores an
unknown future semantics version so a newer bundle remains inspectable, but it cannot be saved as a
valid current Graph until the server recognizes that version. Editing semantics changes the Graph
and Contract fingerprints; existing Scenario coordinates therefore become stale and require an
explicit rebase.

## Portable Authoring Workspace

`bloge.visualAuthoringWorkspaceBundle.v1` is the service-independent handoff format for the browser,
VS Code, and offline review.

| Asset | Bundle rule |
|---|---|
| GraphDraft | Exact object used to calculate the target fingerprint |
| Contract projection | Exact enterprise scope, Contract, and Contract fingerprint |
| ScenarioDraftSet | Exact mutable authoring revision bound to that Contract |
| Operator index | Node id, operator ref, and optional snapshot fingerprint |
| Publication references | Immutable fixture/suite coordinates only |
| Classification | Must equal Scenario metadata classification |

Import parses JSON, rejects unknown top-level fields and malformed nested authoring assets, scans
for raw credential material, recomputes the Contract and Graph fingerprints, and compares target,
scope, classification, and operator indexes before mutating the canvas. A failure returns a stable
`WorkspaceBundleError.code` and value-free JSON Pointer paths. Secret references are allowed; raw
secrets are not. Publication references do not make an offline bundle published or executable.

## Transient Compiler

The transient compiler maps:

| Scenario value | Existing simulation request |
|---|---|
| `given.input` | `context` |
| exact node `RETURN` | request-scoped `fixtures[nodeId]` |
| exact node `REAL` | removes a persisted authoring fixture for that node |
| selected graph output | `outputNode` |
| Expected Result | retained in `ScenarioSimulationPlan.assertions` for post-run comparison |

The following values are not representable by `NodeFixture` and therefore fail closed:

- ERROR, DELAY, TIMEOUT, REPLAY, OBSERVE, and MUST_NOT_CALL;
- transport-boundary RETURN;
- operator/resource/function-wide selectors;
- attempt, occurrence, correlation-key, or input-match selectors;
- more than one transient behavior for the same node.

These controls must compile through `FixtureRule` and the governed testing control plane. No adapter
may replace them with a superficially similar fixed output.

## Governed Compiler

`ScenarioGovernedCompiler` is deterministic and side-effect free. It receives the current visual
Graph when the target kind is GRAPH, or the authoritative catalog Operator when the target kind is
OPERATOR, the projected Contract, one exact Scenario revision, and a runtime target independently
discovered from the testing control plane. It produces registration requests, but does not itself
write either registry.

For a virtual Operator, `OperatorExecutionLowering` resolves two distinct coordinates:

- the design target remains the business-facing catalog ref and fingerprint, for example
  `resource:user-service.getProfile`;
- the runtime target is the catalog-declared executable ref and independently discovered
  fingerprint, for example `httpResource`;
- Scenario Given values are validated against the design Contract, then deterministically lowered
  into the runtime input. A resource descriptor adds its governed `resourceId`, retains `params`,
  and preserves supported transport test overrides.

The compilation plan, Fixture metadata, TestSuite metadata, TestCase metadata, and publication
report retain enough design/runtime lineage to audit this translation. A runtime id that disagrees
with catalog lowering fails closed.

| Authoring semantic | Governed protocol |
|---|---|
| Scenario | one `FixtureBundle` plus one `TestSuite.TestCase` |
| REAL / RETURN | `FixtureRule` REAL / RETURN |
| ERROR | `FixtureRule` THROW |
| DELAY / TIMEOUT | deterministic logical clock plus DELAY / TIMEOUT |
| REPLAY | exact replay reference |
| OBSERVE / MUST_NOT_CALL | SPY / DENY |
| node/operator/resource/function selector | exact `FixtureRule.Selector` coordinate |
| attempt/occurrence/correlation/path match | selector and `Match.pathEquals` |
| consumption and schema waiver | `FixtureRule.Consumption` and `SchemaCheck` |
| output/node/status assertion | `FixtureBundle.Assertion` |
| invocation assertion | fixture-rule use assertion |
| edge assertion | suite edge-transfer coverage requirement |

Fixture and suite identifiers are derived from canonical content. The same source revision and
runtime target therefore produce byte-equivalent registration requests and converge on the same
immutable identities after a retry.

Compilation fails closed before registry writes when:

- the Scenario or Contract coordinate is stale;
- no Scenario exists or the control-plane limit of 100 cases is exceeded;
- a runtime target is not an exact `sha256:` target of the same kind and the Graph name or
  catalog-declared lowered Operator ref;
- an Operator Scenario uses Graph-only node or edge selectors/assertions;
- an assertion path is not a JSON Pointer;
- selector order, consumption policy, schema-check mode, or assertion pairing is invalid;
- a hand-authored `PROPERTY` case tries to bypass the validator-proven property materializer.

The publication transaction must still register, independently re-read, and fingerprint-check every
fixture and the suite. A successful compilation plan is not publication evidence.

## Governed Publication Saga

`ScenarioPublicationService` implements publication as a recoverable saga because Scenario
storage, the FixtureBundle registry, and the TestSuite registry are independent durability
boundaries:

1. Resolve one retained Scenario revision in the verified five-dimensional enterprise scope.
2. Resolve the current Graph or Operator and Contract again.
3. Ask the testing control plane for the current runtime target; callers cannot supply its
   fingerprint.
4. Compile and fingerprint the complete compilation plan.
5. Persist an `IN_PROGRESS` payload-free report before the first external write.
6. Register each content-addressed fixture, independently read it, and compare identity,
   fingerprint, and complete canonical content.
7. Persist each verified fixture reference.
8. Register the dependency-closed suite, independently read it, and perform the same checks.
9. Persist `PUBLISHED` only after all dependencies are independently verified.

Failures leave `FAILED` or `PARTIAL` state with stage, machine code, and retryability, but without
Scenario input, dependency response, assertion expected value, or runtime payload. Retrying an
exact partial publication reuses the same asset identities and is therefore convergent. A completed
publication is independently re-read again on subsequent publish calls.

Canonical content equality is fingerprint equality over canonical JSON, not Java object
`equals`. This deliberately tolerates representation-only JSON round-trip changes such as an
integral metadata value deserializing as `Integer` instead of `Long`, while still detecting any
semantic byte change. The publisher recomputes the returned asset fingerprint at its own trust
boundary instead of trusting the registry envelope.

The publication identity binds:

- stored Scenario id, revision, and fingerprint;
- visual target kind, id, and fingerprint plus the Contract fingerprint;
- independently discovered runtime target;
- compiler plan schema version;
- canonical fingerprint of the complete compilation plan.

The plan fingerprint is essential: a compiler semantic change creates a different publication
coordinate instead of silently reusing a receipt produced by an older lowering algorithm. Bounded
fixture, suite, and publication ids always retain the complete 64-character digest suffix.

Publication uses `X-Purpose: TEST_SCENARIO_PUBLISH`; authoring uses `TEST_SUITE_WRITE`, receipt reads
use `TEST_SUITE_READ`, and execution uses `TEST_EXECUTION`. The controller and its testing-control-
plane adapter are physically absent outside `test` and `staging`.

Authoritative wire schemas:

- `docs/schemas/bloge-scenario-publication-report-v1.schema.json`
- `docs/schemas/bloge-stored-scenario-publication-v1.schema.json`

## Schema Round-Trip Policy

The eventual field workbench classifies a schema before editing:

| Level | Keywords | Editing rule |
|---|---|---|
| Native | `type`, `properties`, `required`, `additionalProperties`, `items`, `prefixItems`, `enum`, `const`, `default`, `examples`, numeric/string/array/object bounds | Structured controls may edit directly |
| Hybrid | `oneOf`, `anyOf`, safe `allOf`, `if/then/else`, `patternProperties`, `dependentRequired`, `dependentSchemas`, `contains`, `propertyNames`, `unevaluatedProperties`, unknown annotations | Structured projection may edit known paths; untouched source fragments must round-trip byte-equivalently after canonicalization |
| Raw-only | unresolved external `$ref`, `$dynamicRef`, recursive dynamic anchors, unsupported custom semantics, or a keyword whose effect cannot be localized | Structured mutation is disabled; raw editor and explicit review remain available |

Rules:

1. Unknown keywords are preserved in the canonical schema AST.
2. Structured edits are JSON-Pointer patches, not whole-document reconstruction.
3. Native-to-Hybrid or Hybrid-to-Raw-only transitions are visible.
4. A Raw-only schema is never overwritten by generated form output.
5. A failed parse leaves the previous canonical Contract unchanged.
6. `UNKNOWN` compatibility blocks automatic migration.

## Security Decision

The authoring layer is payload-bearing, while Run Evidence remains payload-free by default.

1. Scenario persistence requires tenant, organization, project, and environment scope.
2. Missing target or Contract fingerprints block validation and execution.
3. Production does not accept inline Scenario execution or fixture injection.
4. Raw credentials are forbidden in examples, inputs, dependency outputs, expected results, and
   portable workspace bundles; only secret references are allowed.
5. CAPTURED values remain Draft values until explicit review and publication.
6. Schema-check waivers require a reason and cannot produce certifiable evidence.
7. Publishing remains a separate permission from authoring and running.
8. Advanced dependency controls never downgrade to transient simulation.

## Verification

```bash
npm --prefix resource-gateway-examples/src/main/frontend test
npm --prefix resource-gateway-examples/src/main/frontend run build
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ContractDraftTest,ScenarioValidationServiceTest,ScenarioSimulationCompilerTest,ScenarioGovernedCompilerTest,ContractScenarioProtocolSchemaTest \
  test
```

The protocol-schema test also verifies that serialized Java record fields and authoritative schema
properties remain synchronized.
