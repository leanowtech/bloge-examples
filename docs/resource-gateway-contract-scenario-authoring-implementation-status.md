# Contract & Scenario Authoring Implementation Status

> Goal: residual gap below 8% against the approved evolution plan
> Measurement: weighted, evidence-based capability matrix
> Latest round: Stage 2 unified Graph/Operator authoring targets

## Assessment Method

Completion is measured against user-visible and industrial behavior, not file count. Each workstream
has a fixed weight. Credit requires current code plus meaningful green tests; a design document alone
does not count as implementation.

| Workstream | Weight |
|---|---:|
| Product information architecture | 18 |
| Schema workbench | 15 |
| Scenario builder | 17 |
| Compiler and adapters | 15 |
| Persistence and protocol | 10 |
| Compatibility and lineage | 10 |
| Security and governance | 8 |
| Samples, documentation, observability | 7 |
| Total | 100 |

## Round 1

Implemented evidence:

- versioned Java and TypeScript `ContractDraft` models;
- versioned Java and TypeScript `ScenarioDraftSet` models;
- exact target, Contract fingerprint, enterprise scope, input, behavior, assertion, and id validation;
- deterministic transient compiler for exact node REAL and RETURN;
- explicit fail-closed diagnostics for every advanced behavior that requires FixtureRule;
- deeply immutable authoring JSON values and cyclic-input rejection;
- strict JSON Schemas for Contract, Scenario, and validation reports;
- protocol/schema synchronization tests and compiler golden/failure tests;
- Schema round-trip and security policy documented.

Weighted assessment:

| Workstream | Achieved | Evidence | Largest remaining gap |
|---|---:|---|---|
| Product information architecture | 0/18 | Domain is not yet exposed in `/author/` | Contract rail and four-tab workspace |
| Schema workbench | 3/15 | Existing SchemaEnvelope/validator plus frozen round-trip policy | AST projection, field tree, form controls, raw diff |
| Scenario builder | 3/17 | Scenario domain and validation | Given/Dependencies/Then UI, run and compare |
| Compiler and adapters | 7/15 | Basic Java/TypeScript compiler with fail-closed tests | governed compiler and legacy migration adapters |
| Persistence and protocol | 1/10 | Authoritative schemas | repository, API, concurrency, workspace bundle |
| Compatibility and lineage | 1/10 | exact fingerprints expose stale inputs | semantic diff, impact and lineage |
| Security and governance | 2/8 | scope/fingerprint/waiver checks and policy | environment/purpose/role enforcement, secret scan |
| Samples, docs, observability | 2/7 | protocol guide, schemas, tests | product samples, guides, telemetry |
| Total achieved | **19/100** |  |  |

**Residual gap: 81%.**

## Next Targeted Iteration

Stage 1 must deliver the first complete user journey:

1. Contract rail opens a dedicated workspace.
2. Interface tab projects input/output schemas as a searchable field tree.
3. Schema-driven Graph Input form creates a Scenario without JSON.
4. Dependencies editor supports REAL and RETURN.
5. Expected Result supports whole-output and path equality.
6. Run & Compare uses the new compiler and shows actual versus expected.
7. Existing examples are projected into Scenario drafts.
8. Raw JSON remains an explicit, lossless Advanced path.

The next assessment will grant product credit only after real-browser verification demonstrates this
journey in the packaged `/author/` application.

## Verification Baseline

Stage 0 established the following green baseline on 2026-07-27:

- `mvn clean verify`: 5,625 tests, 0 failures, 0 errors, 4 existing skipped tests, packaged
  Spring Boot artifact built successfully;
- frontend `npm test`: 189 tests passed;
- frontend `npm run build`: production Vite bundle built successfully;
- the three authoritative protocol schemas parse as valid JSON;
- focused Contract/Scenario protocol, validation, and compiler tests: 13 passed.

The Maven verification includes the existing database, Spring application, protocol, and real-browser
integration suites. Each later round must preserve this baseline and add focused tests for its new
behavior.

## Round 2

Implemented evidence:

- the canvas exposes an always-visible, clickable Contract rail with input/output/scenario status;
- a dedicated four-view workspace covers Interface, Scenarios, Compatibility, and Run Evidence;
- graph input/output schemas are projected into searchable field trees;
- Scenario Given, exact-node REAL/RETURN dependencies, and whole-output/path equality assertions use
  schema-driven native controls;
- open-schema JSON controls retain incomplete local text without corrupting the last canonical value;
- existing built-in example table cases project automatically into first-class Scenario drafts;
- deterministic browser SHA-256 coordinates bind Scenario sets to the exact graph and Contract;
- graph or Contract drift blocks runs until the user performs an explicit, provenance-recorded rebase;
- Run & Compare reuses the simulation API and renders actual-versus-expected evidence and node status;
- unsupported governed behavior remains visible and fails closed in the transient compiler;
- graphical edits and Advanced Scenario JSON stay synchronized on the same canonical model;
- desktop, tablet, and narrow-screen layout rules are included for the workspace and rail.

Weighted assessment:

| Workstream | Achieved | Evidence | Largest remaining gap |
|---|---:|---|---|
| Product information architecture | 14/18 | Contract rail and four-view lifecycle workspace | operator target unification and deep-link focus |
| Schema workbench | 9/15 | field tree, schema forms, arrays/enums/scalars, open JSON fallback | Contract mutation, hybrid/raw classification and semantic validation |
| Scenario builder | 11/17 | Given/Dependencies/Then, examples, run and compare | full behavior, selector, match and assertion builders |
| Compiler and adapters | 8/15 | transient compiler, legacy case projection, fail-closed advanced routing | governed FixtureBundle/TestSuite compiler and bidirectional adapters |
| Persistence and protocol | 1/10 | authoritative schemas only | durable API, concurrency, revisions and portable workspace |
| Compatibility and lineage | 2/10 | exact stale coordinates and explicit rebase | semantic diff, field lineage, impact and guided migration |
| Security and governance | 2/8 | exact-coordinate and advanced-behavior fail-closed checks | principal/environment policy, secrets, publication separation |
| Samples, documentation, observability | 3/7 | built-in case projection, user flow and 204 frontend tests | guided samples, telemetry, performance and automated visual evidence |
| Total achieved | **50/100** |  |  |

**Residual gap: 50%.**

The Stage 1 product path is implemented, but its real-browser exit gate is not claimed complete in
this environment. The packaged application started successfully and the capability probe passed;
the in-app browser then blocked local-page inspection under its URL safety policy. Component
interaction tests and responsive CSS constraints are green, but reproducible screenshot and overlap
checks must be added to the repository before the browser gate receives credit.

## Next Targeted Iteration

Stage 2 must close the industrial Scenario and governed-publication loop:

1. add durable, scoped, optimistic-concurrency ScenarioDraftSet storage and APIs;
2. support every v1 dependency behavior, selector, matcher, consumption rule and schema check;
3. expand assertions to node, edge, invocation, schema, error and governance scopes;
4. compile exact Scenario drafts into immutable FixtureBundle and TestSuite revisions;
5. keep author, runner and publisher permissions separate and record publication lineage;
6. support graph and operator targets through one workspace model;
7. export/import a self-contained, secret-safe workspace bundle for VS Code and offline editing;
8. lazy-load the workspace to remove the current 604 kB main-bundle warning;
9. add deterministic browser screenshot, overflow and desktop/mobile interaction checks.

Stage 2 receives no publication credit until compiled assets are independently re-read, fingerprints
are re-verified, and a governed execution proves the same Scenario semantics end to end.

## Round 3

Implemented evidence:

- `ScenarioDraftSetRepository` defines scope-isolated current reads, retained history, and
  optimistic-concurrency writes;
- the H2 adapter uses the complete tenant/organization/project/environment/region/id coordinate as
  its key and stores every accepted revision as an immutable snapshot;
- every stored envelope carries a canonical Scenario fingerprint that is recomputed during reads;
- `ScenarioDraftSetAuthoringService` derives scope from verified identity, permits only test/staging,
  enforces classification clearance, and never accepts body scope as authority;
- every write resolves the current GraphDraft, recomputes the exact target fingerprint, projects the
  current Contract, validates inputs and behavior, and rejects stale coordinates;
- raw-secret scanning precedes structural validation and returns paths/codes without payload values;
- the API separates read and write purposes and is absent from the production profile;
- save, validate, current-read, and revision-history endpoints now have a strict stored-envelope
  JSON Schema;
- persistence tests cover restart, fingerprint verification, scope isolation, stale writes, stale
  target/Contract, clearance boundary, production denial, and secret non-disclosure.

Weighted assessment:

| Workstream | Achieved | Change from Round 2 |
|---|---:|---:|
| Product information architecture | 14/18 | 0 |
| Schema workbench | 9/15 | 0 |
| Scenario builder | 11/17 | 0 |
| Compiler and adapters | 8/15 | 0 |
| Persistence and protocol | 7/10 | +6 |
| Compatibility and lineage | 2/10 | 0 |
| Security and governance | 4/8 | +2 |
| Samples, documentation, observability | 4/7 | +1 |
| Total achieved | **59/100** | **+9** |

**Residual gap: 41%.**

The next slice must connect this durable asset to the full FixtureRule/TestSuite compiler and the
existing independently governed registries. Persistence alone is intentionally not described as
publication or evidence.

Round 3 focused verification:

- `ScenarioDraftSetPersistenceTest`: 8 passed;
- `ScenarioValidationServiceTest`: 4 passed;
- `ContractScenarioProtocolSchemaTest`: 1 passed;
- `ResourceGatewayApplicationTest`: 8 passed, including Spring bean and application startup wiring;
- total focused Java tests: 21 passed, 0 failures.

## Round 4

Implemented evidence:

- `ScenarioGovernedCompiler` deterministically lowers one exact Scenario revision into one
  content-addressed FixtureBundle per case and one dependency-closed TestSuite;
- all eight authoring behaviors map without semantic downgrade to REAL, RETURN, THROW, DELAY,
  TIMEOUT, REPLAY, SPY, and DENY;
- node, operator, resource, and built-in function coordinates preserve attempt, occurrence,
  correlation, input-match, consumption, and schema-check policy;
- output, node output, node status, invocation-use, and edge-transfer assertions compile into
  executable assertion or coverage-policy semantics;
- source target and Contract fingerprints remain in immutable fixture/suite metadata while the
  runtime target is supplied independently;
- canonical content determines every fixture and suite id, making retries convergent;
- empty suites, case-count overflow, hand-authored PROPERTY cases, malformed runtime fingerprints,
  invalid JSON Pointers, and incompatible policy enums fail closed before any registry write.

Weighted assessment:

| Workstream | Achieved | Change from Round 3 |
|---|---:|---:|
| Product information architecture | 14/18 | 0 |
| Schema workbench | 9/15 | 0 |
| Scenario builder | 11/17 | 0 |
| Compiler and adapters | 13/15 | +5 |
| Persistence and protocol | 7/10 | 0 |
| Compatibility and lineage | 3/10 | +1 |
| Security and governance | 5/8 | +1 |
| Samples, documentation, observability | 4/7 | 0 |
| Total achieved | **66/100** | **+7** |

**Residual gap: 34%.**

Compilation is now complete, but publication credit remains deliberately withheld. The next slice
must use a dedicated publisher permission, discover the runtime target independently, register and
re-read every immutable dependency, persist a payload-free lineage receipt, and prove retry and
partial-failure convergence.

Round 4 focused verification:

- `ScenarioGovernedCompilerTest`: 6 passed;
- `ScenarioValidationServiceTest`: 4 passed;
- all 10 focused tests passed with no failures or errors.

## Round 5

Implemented evidence:

- a dedicated `TEST_SCENARIO_PUBLISH` purpose separates author, publisher, reader, and runner;
- publication independently discovers the runtime Graph target and never trusts a caller-supplied
  runtime fingerprint;
- the complete compilation-plan fingerprint and compiler schema version are part of the
  publication coordinate, preventing semantic reuse after compiler evolution;
- an optimistic, full-scope H2 repository persists current saga state and every immutable
  transition;
- every FixtureBundle and TestSuite write is followed by an independent read plus id, revision,
  fingerprint, and canonical-content comparison;
- `PARTIAL` and `FAILED` receipts record only stage, machine code, and retryability; exact retries
  converge on content-addressed registry assets;
- completed publications are independently reverified on repeated publish calls;
- fixture, suite, and publication ids preserve their complete digest suffix even when source ids
  approach protocol limits;
- controller and control-plane adapter are physically absent outside test/staging;
- strict report/envelope schemas and API/startup documentation are synchronized.

Weighted assessment:

| Workstream | Achieved | Change from Round 4 |
|---|---:|---:|
| Product information architecture | 14/18 | 0 |
| Schema workbench | 9/15 | 0 |
| Scenario builder | 11/17 | 0 |
| Compiler and adapters | 15/15 | +2 |
| Persistence and protocol | 9/10 | +2 |
| Compatibility and lineage | 5/10 | +2 |
| Security and governance | 7/8 | +2 |
| Samples, documentation, observability | 5/7 | +1 |
| Total achieved | **75/100** | **+9** |

**Residual gap: 25%.**

The backend governed-publication exit gate is now met. The remaining gap is deliberately
user-facing and integration-facing: graphical controls for every behavior/assertion, browser
save/load/publish state, operator targets, portable workspace bundles, richer Contract
compatibility/lineage, code splitting, and repository-owned visual regression evidence.

Round 5 verification:

- `ScenarioPublicationServiceTest`: 6 passed, covering success, independent reads, payload
  non-disclosure, partial recovery, corrupt read rejection, compile blocking, permission/profile
  policy, exact revisions, restart, and scope isolation;
- `ScenarioGovernedCompilerTest`: 7 passed;
- `ContractScenarioProtocolSchemaTest`: 1 passed;
- `ResourceGatewayApplicationTest`: 8 passed;
- 30 focused tests passed with no failures or errors;
- a real Spring Boot process with the `test` profile assembled the complete testing control plane
  and Scenario publisher, reached `Started`, and then shut down gracefully.

## Round 2 Verification

- frontend `npm test`: 204 tests passed across 10 files;
- frontend `npm run build`: production bundle built successfully;
- focused Contract/Scenario workbench tests: 15 passed;
- `mvn -Pfrontend package -DskipTests`: packaged application built and copied the frontend to
  `/author`, `/rehearsals`, and `/showcase`;
- Spring Boot demo startup and `/api/integration/capabilities` readiness probe succeeded;
- bundle warning remains: main JavaScript is approximately 604 kB minified and requires Stage 2
  code splitting.

## Round 6

Implemented evidence:

- all eight governed dependency behaviors now have direct segmented controls and structure-aware
  output, transport, error, duration, and replay forms;
- the advanced dependency editor covers node/operator/resource/function selectors, graph path,
  correlation, attempts, occurrences, typed input matches, expected input, consumption, and schema
  waiver without requiring raw JSON;
- the assertion builder covers graph output, node output, node status, edge transfer, and dependency
  invocation with scope-valid operators and schema-derived expected values;
- selector-kind editing preserves an intentionally empty resource/function coordinate while the
  user is still typing, without weakening the final unique-coordinate validation;
- the browser workspace now saves the Graph, reads a server-authoritative Contract projection,
  loads/saves optimistic Scenario revisions, and publishes only a clean retained revision;
- local edits and rebases no longer impersonate server revisions;
- `bloge.scenarioContractProjection.v1` has a strict machine schema and Java/TypeScript protocol
  synchronization tests;
- the workspace is lazy-loaded and React/React Flow are split into stable vendor chunks; the
  production build has no 500 kB chunk warning;
- disabled governed actions use a neutral visual state instead of a misleading active blue.

Weighted assessment:

| Workstream | Achieved | Change from Round 5 |
|---|---:|---:|
| Product information architecture | 15/18 | +1 |
| Schema workbench | 9/15 | 0 |
| Scenario builder | 16/17 | +5 |
| Compiler and adapters | 15/15 | 0 |
| Persistence and protocol | 9/10 | 0 |
| Compatibility and lineage | 5/10 | 0 |
| Security and governance | 7/8 | 0 |
| Samples, documentation, observability | 6/7 | +1 |
| Total achieved | **82/100** | **+7** |

**Residual gap: 18%.**

Round 6 verification:

- frontend: 212 tests passed across 12 files;
- focused Java protocol/persistence tests: 10 passed;
- production build: 43 kB lazy Scenario workspace, 143 kB React runtime, 149 kB React Flow,
  289 kB application chunk; no oversize warning;
- packaged Spring Boot application started with the `test` profile and passed readiness;
- real Chrome verification loaded **Loan policy fallback**, opened the Contract workspace, selected
  ERROR, verified the canonical error code, and found no console warning/error;
- 1472×768 desktop and 390×844 mobile visual inspection found no incoherent overlap; mobile body
  and dialog widths were exactly 390 px and no sampled command overflowed the viewport.

## Round 7

Implemented evidence:

- the Interface tab now edits effect, idempotency, streaming/durability, compatibility, stable
  errors, WRITE reconciliation, and pre/post invariants with structured controls;
- non-schema promises round-trip through the versioned
  `visualLayout.graphContract.contractSemantics` extension and server-authoritative Contract
  projection;
- malformed or unsupported embedded semantics fail Graph validation with a stable diagnostic;
- `bloge.graphContractSemantics.v1` has a strict authoritative schema synchronized to serialized
  Java fields;
- the workspace exports and imports `bloge.visualAuthoringWorkspaceBundle.v1` for browser/VS Code
  handoff without requiring a Scenario server;
- import independently re-verifies Graph target and Contract fingerprints, complete enterprise
  scope, Scenario coordinate, classification, operator snapshot index, nested shape, and raw-secret
  policy before changing the canvas;
- exact server Graph objects are retained for export while the local canvas remains unchanged;
  authored node positions survive import instead of being auto-laid out;
- Contract semantics and workspace logic have focused component, round-trip, tamper, malformed
  input, credential, projection, and persistence validation tests.

Weighted assessment:

| Workstream | Achieved | Change from Round 6 |
|---|---:|---:|
| Product information architecture | 15/18 | 0 |
| Schema workbench | 12/15 | +3 |
| Scenario builder | 16/17 | 0 |
| Compiler and adapters | 15/15 | 0 |
| Persistence and protocol | 10/10 | +1 |
| Compatibility and lineage | 5/10 | 0 |
| Security and governance | 8/8 | +1 |
| Samples, documentation, observability | 6/7 | 0 |
| Total achieved | **87/100** | **+5** |

**Residual gap: 13%.**

Round 7 focused verification:

- frontend Contract/workspace/AuthorCanvas tests: 49 passed;
- Java Contract projection, Graph validation, and protocol-schema tests: 247 passed;
- production Vite build: 59 kB lazy workspace, 143 kB React runtime, 149 kB React Flow, and
  291 kB application chunks; no oversize warning;
- packaged Chrome verification authored a WRITE Contract, reconciliation protocol, stable error,
  and invariant, then rebased the two bundled Scenarios and restored workspace export eligibility;
- 1472px desktop and 390x844 mobile inspection found no incoherent overlap or browser
  warning/error; mobile body, dialog, and header widths remained exactly 390 px and semantic rows
  stayed within their containers.

The next iteration must close unified Graph/Operator targets in the same workspace. Stage 3 then
owns semantic compatibility, exact impact/lineage, and guided migration; repository-owned
accessibility/performance gates remain the final cross-cutting hardening slice.

## Round 8

Implemented evidence:

- Graph and Operator now use the same Contract/Scenario workspace, authoring model, durable
  Scenario API, governed compiler, publication saga, and evidence view;
- the server projects an authoritative Operator Contract from the policy-visible catalog, with
  exact input/output ports and catalog capability semantics;
- Operator workspaces automatically resume the latest stored Scenario revision, while missing
  drafts remain a clean first-use state and optimistic conflicts retain the stable HTTP 409
  problem contract;
- catalog-derived Operator semantics are read-only, so the UI does not imply that changing a
  Scenario mutates the operator library;
- virtual resource Operators preserve the business-facing design coordinate and independently
  lower to the declared runtime operator/input shape; both coordinates are retained in immutable
  publication lineage;
- governed registry verification recomputes canonical JSON fingerprints after a real serialization
  round-trip instead of relying on JVM numeric representation equality;
- Operator target validation rejects Graph-only node/edge scopes and namespace-restricted
  operators without an explicit namespace coordinate;
- Operator exploratory runs compile through an exact one-node projection, and dependencies can be
  added or removed graphically with an Operator selector as the target-aware default;
- Operator Scenario ids bind a readable prefix to the full SHA-256 operator-ref digest, and every
  load rechecks the exact asset and target coordinate before accepting stored content;
- publication-report v1 keeps its new target lineage fields optional for legacy readers and stored
  reports, avoiding an in-place protocol compatibility break;
- Scenario application services now live in `authoring.scenario`, outside the dependency-clean
  `visual` kernel; the existing architecture guard proves the kernel does not import gateway
  testing or integration implementations.

Weighted assessment:

| Workstream | Achieved | Change from Round 7 |
|---|---:|---:|
| Product information architecture | 17/18 | +2 |
| Schema workbench | 12/15 | 0 |
| Scenario builder | 17/17 | +1 |
| Compiler and adapters | 15/15 | 0 |
| Persistence and protocol | 10/10 | 0 |
| Compatibility and lineage | 6/10 | +1 |
| Security and governance | 8/8 | 0 |
| Samples, documentation, observability | 7/7 | +1 |
| Total achieved | **92/100** | **+5** |

**Residual gap: 8%.**

Round 8 focused verification:

- Java Scenario publication, compiler, persistence, validation, protocol, problem-contract,
  Contract projection, and architecture-boundary tests: 44 passed;
- frontend API/workspace/editor/compiler/AuthorCanvas tests: 101 passed;
- production frontend build and Maven focused build succeeded;
- a real browser opened the Operator workspace, restored revision 1, published its governed suite,
  and displayed `PUBLISHED` without a manual load;
- 1440x900 desktop and 390x844 mobile inspection found no body/dialog overflow;
- the 5,660-test Maven verification reached two failures: the architecture failure exposed and
  drove the `authoring.scenario` boundary correction; the unrelated provider-process readiness
  case passed immediately when rerun in isolation.

Stage 2 is functionally closed, but the goal requires a residual gap below 8%, not equal to it.
The next slice therefore implements Stage 3 semantic compatibility, field lineage, exact Scenario
impact, and guided migration before claiming completion.

## Round 9

Implemented evidence:

- every authoring-service save now persists the exact authoritative Contract as an immutable,
  integrity-verified baseline beside the accepted Scenario revision without changing
  `ScenarioDraftSet v1`;
- a versioned `bloge.contractCompatibilityReport.v1` binds the retained revision, current target,
  old/new Contract fingerprints, policy, deterministic findings, exact Scenario impacts, migration
  actions, and a canonical report fingerprint;
- the analyzer classifies input and output field addition, removal, explicit rename, requiredness,
  type, enum, and constraint drift in the correct compatibility direction;
- unknown Schema composition, unsupported keywords, non-schema semantic changes, target changes,
  and legacy revisions without baselines fail closed as `REVIEW_REQUIRED`;
- Given values and output assertions are traced to findings, so impact names exact Scenario ids and
  paths instead of reporting only coordinate staleness;
- safe migration supports declared defaults, removed inputs, `x-bloge-renamed-from` input moves,
  and output assertion rebinds; collisions and value conversions remain manual;
- stale banners route into Compatibility instead of performing a blind rebase;
- applying safe edits leaves the draft stale, while explicit review records report/revision/
  fingerprint/finding lineage before rebasing; save, run evidence, and publication remain separate
  gates;
- revision-zero local drafts have a separate acknowledged first-baseline path, avoiding a lifecycle
  dead end without weakening stored-revision policy.

Weighted assessment:

| Workstream | Achieved | Change from Round 8 |
|---|---:|---:|
| Product information architecture | 18/18 | +1 |
| Schema workbench | 13/15 | +1 |
| Scenario builder | 17/17 | 0 |
| Compiler and adapters | 15/15 | 0 |
| Persistence and protocol | 10/10 | 0 |
| Compatibility and lineage | 8/10 | +2 |
| Security and governance | 8/8 | 0 |
| Samples, documentation, observability | 7/7 | 0 |
| Total achieved | **95/100** | **+3** |

**Residual gap: 5%.**

Round 9 focused verification:

- compatibility analyzer and baseline persistence: 16 Java tests passed;
- compatibility protocol, existing Scenario publication, and architecture boundary: 26 Java tests
  passed together;
- frontend Compatibility migration, API, and workspace: 52 tests passed;
- the complete frontend suite passed 229 tests and the production Vite build succeeded with a
  70 kB lazy Contract/Scenario workspace chunk;
- final `mvn clean verify` passed 5,665 tests with zero failures/errors and four
  environment-conditional skips, including 36 Selenium/Chrome DOM cases;
- high-load verification exposed and fixed two pre-existing test-fixture races: process capture now
  waits for the forked child to complete `exec`, and late-receipt verification derives provider
  confirmation from the durable preparation timestamp. The process case also passed 20 independent
  stability reruns before the final full build.

Remaining work is deliberately bounded: historical publication reverse indexing and ANEKE report
delivery, finding-to-canvas deep-link highlighting, broader deterministic support for advanced JSON
Schema semantics, and repository-owned accessibility/performance browser gates. These are follow-up
hardening items; the implemented v1 path is fail-closed when it cannot decide.
