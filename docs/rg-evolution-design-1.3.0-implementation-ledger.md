# Resource Gateway 1.3.0 Implementation Ledger

Updated: 2026-08-28
Source of truth: `docs/rg-evolution-design-1.3.0.md`
Branch: `codex/bloge-dag-workbench2`

## Status model

`Implemented` means the behavior and its focused tests are present in the current
source tree. `Accepted` additionally requires independent command evidence in
this ledger. `Open` means the design requirement still needs implementation or
the existing evidence is insufficient.

## Requirement trace

### 8.0 Engineering constraints and rollback device

| Requirement | Status | Current evidence and remaining work |
| --- | --- | --- |
| Fail-closed pure `resolveSpine`, `?spine=v1` rollback, and tool coordinates outside GraphDraft/scenario wire schemas | Implemented | `spine/authorSpine.ts`, `spine/authorSpine.test.ts` cover the pure functions and wire-boundary model. |
| New capability directories rather than another monolithic canvas | Implemented | Dedicated `spine/`, `external-api/`, `tool/`, `fixture-asset/`, and `decision-scenario/` modules exist. `AuthorCanvas.tsx` remains the mount point, as allowed by the design. |

### 8.1 Phase A: foundation and navigation

| Requirement | Status | Current evidence and remaining work |
| --- | --- | --- |
| Launcher with five named intents | Implemented | `spine/Launcher.tsx` and `Launcher.test.tsx`. |
| Tool breadcrumb and Define→Prove thread rail | Implemented | `spine/ObjectBreadcrumb.tsx`, `spine/ToolThreadRail.tsx`, and component tests. |
| Spine-off mount isolation and 1280/390 overflow checks | Partially implemented | Component tests cover mount behavior; a fresh full frontend build and real-browser geometry evidence are still required. |

### 8.2 Phase B: in-place resource definition, tool signature, publish, and composition

| Requirement | Status | Current evidence and remaining work |
| --- | --- | --- |
| External API form mapped to descriptor plus design contract | Implemented | `external-api/ExternalApiAuthoring.tsx`, `externalApiModel.ts`, `externalApiTransport.ts`, and focused model/component/transport tests. |
| Bounded response-sample schema inference | Implemented | `externalApiModel.ts` and `externalApiModel.test.ts`. |
| Tool signature, publish action, and published-tool palette facets | Implemented | `tool/ToolSignatureBadge.tsx`, `ToolAuthoringPanel.tsx`, `ToolPaletteFacets.tsx`, and focused tests. |
| E2E from resource definition through publication and composition | Open | No dedicated real-browser test yet proves the full Phase B sequence. |

### 8.3 Phase C: simulate capture, governed promotion, and cross-graph reuse

| Requirement | Status | Current evidence and remaining work |
| --- | --- | --- |
| Graph-node fixture promotion endpoint with server-derived provenance | Implemented | `GraphNodeFixturePromotionService`, controller, transaction test, service test, and controller test. |
| Payload-free governed fixture catalog with exact scope, pagination, compatibility filtering, reverse usage count, and no-store caching | Implemented | `FixtureAssetCollectionController`, `FixtureAssetCollectionService`, database repository test, controller test, and service test. |
| Governed fixture resolution during visual simulation with schema/staleness checks and idempotent usage accounting | Implemented | `GovernedFixtureSimulationResolver`, `VisualGraphSimulationControllerTest`, `GovernedFixtureSimulationResolverTest`, and repository/accounting tests. |
| Output/protocol/transport fidelity routing through the bounded kernel adapter | Implemented | `ExecutionModeHints`, `ExecutionControlCompiler`, `TestDoubleFactory`, `VisualSimulationKernelAdapter`, and focused compiler/runtime/adapter tests. |
| AuthorCanvas picker, pin/promote controls, compatibility filtering, refresh, staleness, and fidelity projection | Implemented | `AuthorCanvas.tsx`, `fixture-asset/*`, `draftModel.ts`, and focused frontend tests. The latest fidelity-control test has an uncommitted assertion and needs packaging with the docs update. |
| Real-browser chain: simulate → capture → promote → reuse in another graph with usage count increment | Open | Backend and component levels are covered separately. The explicit 1280 px browser acceptance chain remains missing. |

### 8.4 Phase D: decision-table scenario enumeration and plan output

| Requirement | Status | Current evidence and remaining work |
| --- | --- | --- |
| Bounded predicate parsing, representative values, deterministic enumeration, truncation, deduplication, and fingerprints | Implemented | `decision-scenario/decisionScenario.ts`, `decisionScenarioModel.ts`, and focused pure-function tests. |
| Scalar/object/plan output and model-only dispatch boundary | Implemented | Decision workbench and model tests cover output-kind selection and honest dispatch modeling. |
| Workbench generation, persistence, staleness, and explicit re-enumeration | Implemented | `DecisionScenarioWorkbench.tsx` and focused workbench/model tests. |
| E2E decision-table scenario generation followed by fixture simulation | Open | No dedicated real-browser test yet proves the Phase D handoff and simulation. |

### 8.5 Acceptance chain and gates

| Requirement | Status | Current evidence and remaining work |
| --- | --- | --- |
| Unit/component test gates | Pending acceptance | Source and tests are present. Fresh independent frontend and focused backend command evidence is required after the final slices. |
| Full backend `clean verify` | Pending acceptance | The latest fidelity commits have focused evidence but do not yet have a post-HEAD full gate. |
| Four-dimensional honest result, no new giant dialog, no main-line regression | Pending acceptance | Existing UX/domain tests provide partial evidence. The final E2E must exercise these properties together. |
| Real-browser 1280 px acceptance chain across define → compose → publish → simulate → capture → promote → reuse → enumerate → simulate → four-dimensional result | Open | This is the dominant remaining acceptance gap. Existing Selenium launchers cover other flows but not this 1.3.0 chain. |

## Iteration assessment

* Baseline through `8c5432f59` established promotion, catalog, picker/reuse,
  simulation, and frontend integration.
* Through `21374ae8c`, runtime resource fidelity is routed through the kernel,
  per-node evidence reaches the canvas, duplicate wiring is removed, and focused
  tests cover protocol/transport behavior.
* Remaining gap is concentrated in the explicit end-to-end acceptance chain and
  final independent green gates/docs packaging.
* Current assessed gap: approximately 12%. The largest unclosed requirement is
  the 8.5 real-browser chain; secondary risk is that full-suite evidence does not
  yet include the newest commits.

## Next slice

1. Run fresh frontend and focused backend gates after packaging the current
   README and fidelity-control assertion.
2. Add a bounded 1280 px real-browser acceptance producer/test for the 8.5
   chain, reusing `VisualAuthoringBrowserDomTest` infrastructure.
3. Run the full backend gate and record all commands, counts, and artifact
   evidence in this ledger.
4. Reassess the requirement-by-requirement gap; stop only when it is below 3%.
