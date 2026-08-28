# Resource Gateway 1.3.0 Implementation Ledger

Updated: 2026-08-28
Source of truth: `docs/rg-evolution-design-1.3.0.md`
Branch: `codex/bloge-dag-workbench2`

## Status model

`Implemented` means the behavior and its focused tests are present in the current
source tree. `Accepted` additionally requires the independent command evidence
recorded below. `Residual` identifies a known operational or maintainability
follow-up that does not block the 1.3.0 acceptance chain.

## Requirement trace

### 8.0 Engineering constraints and rollback device

| Requirement | Status | Current evidence |
| --- | --- | --- |
| Fail-closed pure `resolveSpine`, `?spine=v1` rollback, and tool coordinates outside GraphDraft/scenario wire schemas | Accepted | `spine/authorSpine.ts` and focused tests; the final browser chain also verifies `spine=off` hides the v1 spine and preserves the v2 workspace. |
| New capability directories rather than another monolithic canvas | Accepted | `spine/`, `external-api/`, `tool/`, `fixture-asset/`, and `decision-scenario/` modules are covered by the frontend gates. `AuthorCanvas.tsx` remains the integration mount point. |

### 8.1 Phase A: foundation and navigation

| Requirement | Status | Current evidence |
| --- | --- | --- |
| Launcher with five named intents | Accepted | `spine/Launcher.tsx`, launcher tests, and the frontend UX/i18n gates. |
| Tool breadcrumb and Define→Prove thread rail | Accepted | `spine/ObjectBreadcrumb.tsx`, `spine/ToolThreadRail.tsx`, component tests, and the final spine-on browser flow. |
| Spine-off mount isolation and viewport overflow checks | Accepted | Frontend UX/host gates and the 1280 px final browser chain's spine-off and no-horizontal-overflow assertions. |

### 8.2 Phase B: in-place resource definition, tool signature, publish, and composition

| Requirement | Status | Current evidence |
| --- | --- | --- |
| External API definition and descriptor/design-contract mapping | Accepted | The same 1280 px WebDriver chain visibly defines a dynamic external API/resource, then carries it through resource selection and composition. Model, component, and transport tests cover the two write contracts. |
| Tool signature, publication, and published-tool composition | Accepted | The chain covers tool signature, publication, public palette projection, and a published tool being composed into the authoring flow; frontend gates cover the corresponding components and transports. |
| Bounded response-sample schema inference | Accepted | `externalApiModel.ts` and focused model tests, included in the final frontend gate. |

### 8.3 Phase C: simulate capture, governed promotion, and cross-graph reuse

| Requirement | Status | Current evidence |
| --- | --- | --- |
| Simulation request preserves persisted draft identity and scope | Accepted | `toGraphDraft`/`toSimulationRequest` regression tests prove `draftId`, `revision`, `tenantId`, `namespace`, and `environment` are sent for a saved draft, while an unsaved draft keeps the legacy omission behavior. The final browser chain executes the saved-draft simulation path. |
| Graph-node fixture promotion with server-derived provenance | Accepted | `GraphNodeFixturePromotionService`, controller, transaction/service/controller tests, and the browser chain's visible promote flow. Promotion wire canonicalizes request `fixtureId` while retaining response/asset `fixtureAssetId`. |
| Payload-free governed catalog, exact scope, and usage accounting | Accepted | Fixture collection/controller/repository/service tests and the visible reviewer/approval/activation chain. The browser asserts usage `0→1→1`, including replay idempotency. Promotion now requires complete tenant/environment identity and rejects scope mismatches before draft lookup; production, staging, test, and Java fallback identities explicitly authorize all governed fixture purposes: `CORRECTNESS_READ`, `CORRECTNESS_WRITE`, `CORRECTNESS_REVIEW`, `CORRECTNESS_FIXTURE_MATERIAL_READ`, and `CORRECTNESS_FIXTURE_MATERIAL_WRITE`. |
| Governed fixture resolution and schema/staleness checks | Accepted | Resolver, visual simulation, repository/accounting tests and the second-graph reuse leg in the browser chain. |
| Output/protocol/transport fidelity through the bounded kernel adapter | Accepted | Focused compiler/runtime/adapter tests and the browser chain's three visible server-projected fidelities: `OUTPUT_LEVEL`, `PROTOCOL_DERIVED`, and `TRANSPORT_LEVEL`. The browser never treats the selected value as proof; it checks server `nodeFidelity`. |
| Persisted fixture reuse in a second graph | Accepted | One 1280 px WebDriver session creates a fresh second draft, binds the active governed fixture, verifies the bound fidelity, and replays all three fidelity values with usage remaining idempotent. |

### 8.4 Phase D: decision-table scenario enumeration and plan output

| Requirement | Status | Current evidence |
| --- | --- | --- |
| Bounded predicate parsing, deterministic enumeration, cap, deduplication, and fingerprints | Accepted | `decision-scenario/decisionScenario.ts` and model tests; opaque combinatorial inputs fall back to bounded per-rule author samples without evaluation or SMT. |
| Scalar/object/plan output and model-only dispatch boundary | Accepted | Decision workbench/model tests and the visible plan-output selection in the final chain. |
| Scenario generation, persistence, expected Return fixture, and simulation | Accepted | The same browser chain performs decision enumeration, visibly saves `r1`, uses the expected Return fixture, saves `r2`, and runs the case. The Return selector/compiler commits are listed below. |
| Four-dimensional honest result | Accepted | The browser checks draft, execution, assertions, and contract as passed; governance remains explicitly `not-checked`, and the UI does not collapse this into generic `Passed` or `Ready for promotion`. |

### 8.5 Acceptance chain and gates

Status: **Accepted**.

`VisualAuthoringBrowserDomTest` runs the complete 1.3.0 chain in one 1280 px
WebDriver session. The chain covers:

- dynamic external API/resource definition;
- tool signature, public publication, and composition;
- persisted draft identity in the simulation request;
- server-derived `SCENARIO` lineage;
- visible promote, reviewer verify, approve, and activate actions;
- fresh-tab second graph and governed fixture reuse;
- usage `0→1→1` and server-projected `OUTPUT_LEVEL` / `PROTOCOL_DERIVED` / `TRANSPORT_LEVEL` fidelity;
- decision enumeration in the second business graph at the same visible decision-node coordinate, visible expected Return selection, `r2` save, and four-dimensional evidence;
- both `spine=off` and no-`spine`-query rollback paths, plus horizontal-overflow checks;
- explicit purpose authorization and promotion identity/scope closure.

The browser evidence is recorded at two levels: focused chain `1/1`, and the
browser test class `50/50`.

## Final gate evidence

| Gate | Result |
| --- | --- |
| `mvn -f resource-gateway-examples/pom.xml clean verify -Pfrontend` | `7,649` tests; failures `0`, errors `0`, skips `0`; `BUILD SUCCESS` at `2026-08-28 21:59 +08` (20:31). |
| Frontend i18n gate | `39` checks passed. |
| Frontend UX gate | `51` checks passed. |
| Frontend host gate | `21` checks passed. |
| Frontend TypeScript/Vite/bundle gates | `tsc`, Vite, and bundle checks passed. |
| AuthorCanvas startup closure | `348.17 KiB`, `21` files, within the `350 KiB` budget. |
| Browser focused chain | `1/1`. |
| Browser test class | `50/50`. |

## Key implementation commits

The current acceptance evidence includes these follow-up commits:

| Commit | Change |
| --- | --- |
| `69c0937c4` | Defer author context panels. |
| `e891b2136` | Fall back opaque decision samples. |
| `98891e307` | Align fixture promotion wire. |
| `c62b6b639` | Derive fixture lineage from simulation evidence. |
| `8ef119baf` | Accept canonical `fixtureId` promotion input. |
| `9ddd7bcbc` | Ignore visual layout in simulation lineage. |
| `1e02dae20` | Ignore server snapshot metadata in lineage. |
| `0606caba9` | Preserve persisted draft identity in simulation requests. |
| `1f044abf8` | Keep the 1.3 acceptance on one business chain. |
| `b11773c2a` | Stabilize governed chain node selection. |
| `9d3b49d6d` | Close fixture promotion identity and scope checks. |
| `89da4e476` | Authorize fixture lifecycle purposes in production, staging, test, and Java fallback defaults. |
| `69480e56e` | Keep Phase D on the second business graph coordinate and verify both rollback paths. |

Existing expected Return selector/compiler commits remain part of the trace:

`592cd32ea` (author expected Return fixtures), `792c88dc9` (frontend Return
fixture targeting), `08984dc4a` (server Return fixture targeting),
`6cab7f836` (Return fixture targeting documentation), `6dda799f8` (server
Return fixture compilation), and `3ed315351` (frontend Return fixture
compilation).

## Residuals and follow-up

These items are explicit residuals, not blockers for the accepted 1.3.0 chain:

1. The in-memory capture-evidence adapter is single-instance and bounded by a
   30-minute TTL and `max4096` entries. A distributed deployment requires a
   shared capture-evidence adapter before relying on cross-instance promotion.
2. Frontend resolver/stage-list logic is duplicated in places, and the
   `AuthorCanvas` coordinator remains a maintainability hotspot. The next
   maintainability increment can extract those seams without changing the
   accepted wire or browser contract.
3. The external working-tree modification to
   `resource-gateway-examples/src/main/frontend/src/fixture-asset/GraphNodeFixtureControls.tsx`
   is outside this ledger update and is not included in this commit.

## Assessment

Current assessed gap: **approximately 1%**, below the design target of `<3%`.

The requirement trace is accepted because the final full gate is green, the
browser chain exercises the cross-phase user-visible contract in one 1280 px
session, and Phase B/C/D behavior has focused unit/component/backend evidence.
The remaining items are bounded deployment and maintainability follow-ups; they
do not leave an unverified 1.3.0 acceptance requirement. Work can stop at this
milestone without relabelling those residuals as completed product guarantees.
