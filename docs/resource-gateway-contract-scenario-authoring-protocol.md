# Resource Gateway Contract & Scenario Authoring Protocol

> Status: Stage 1 authoring vertical slice implemented
> Protocols: `bloge.contractDraft.v1`, `bloge.scenarioDraftSet.v1`, `bloge.scenarioValidationReport.v1`

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

All three schemas use JSON Schema 2020-12, reject unknown top-level fields, and bind target and
contract identity with exact SHA-256 fingerprints.

## Java Boundaries

| Responsibility | Implementation |
|---|---|
| Contract protocol | `visual.contract.ContractDraft` |
| Graph projection | `visual.contract.ContractDraftProjectionService` |
| Scenario protocol | `visual.scenario.ScenarioDraftSet` |
| Exact-input validation | `visual.scenario.ScenarioValidationService` |
| Validation report | `visual.scenario.ScenarioValidationReport` |
| Transient compilation | `visual.scenario.ScenarioSimulationCompiler` |
| Compiled transient plan | `visual.scenario.ScenarioSimulationPlan` |
| Mutable Scenario repository | `visual.scenario.ScenarioDraftSetRepository` |
| H2 persistence adapter | `visual.scenario.DatabaseScenarioDraftSetRepository` |
| Authenticated authoring service | `visual.scenario.ScenarioDraftSetAuthoringService` |
| Authoring HTTP surface | `visual.scenario.ScenarioDraftSetController` |

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

The body scope must exactly match the authenticated tenant, organization, project, environment, and
region. The service independently resolves the stored GraphDraft, recomputes its fingerprint,
projects the current Contract, verifies the Contract fingerprint, validates Scenario values, scans
for raw credentials, and then applies optimistic concurrency. A conflict returns
`RG.SCENARIO.REVISION_CONFLICT` with the current revision; it never silently overwrites another
author's work.

The stored envelope carries a canonical fingerprint and is re-verified when read from persistence.
Scenario authoring storage is mutable by revision, while every retained revision remains immutable.
Saving is not publishing: it grants no fixture, suite, execution, or certification status.

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

`AuthorCanvas.tsx` does not own these protocol definitions. It opens the workspace, supplies the
current exact target, projects existing canvas examples/table cases, executes compiled simulation
requests, and applies the resulting canvas state.

The Stage 1 workspace provides four views:

1. **Interface** projects graph input/output schemas as field trees while preserving the complete
   Contract in Advanced JSON.
2. **Scenarios** edits Given input, exact-node REAL/RETURN dependencies, and whole-output/path
   equality assertions through schema-driven controls.
3. **Compatibility** exposes exact target and Contract coordinate drift and requires explicit
   rebase.
4. **Run Evidence** compares assertions with the latest exploratory response and shows node status.

Selecting an advanced dependency behavior never produces a weaker simulation. The frontend
transient compiler retains the behavior and returns a fail-closed diagnostic; the server-side
governed compiler is the only path that may lower those semantics into testing-control-plane
assets.

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
Graph, its projected Contract, one exact Scenario revision, and a runtime target independently
discovered from the testing control plane. It produces registration requests, but does not itself
write either registry.

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
- a runtime target is not an exact `sha256:` GRAPH target for the same graph name;
- an assertion path is not a JSON Pointer;
- selector order, consumption policy, schema-check mode, or assertion pairing is invalid;
- a hand-authored `PROPERTY` case tries to bypass the validator-proven property materializer.

The publication transaction must still register, independently re-read, and fingerprint-check every
fixture and the suite. A successful compilation plan is not publication evidence.

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
