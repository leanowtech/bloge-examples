# Contract & Scenario Authoring Implementation Status

> Goal: residual gap below 8% against the approved evolution plan
> Measurement: weighted, evidence-based capability matrix
> Latest round: Stage 2 governed compilation

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

## Round 2 Verification

- frontend `npm test`: 204 tests passed across 10 files;
- frontend `npm run build`: production bundle built successfully;
- focused Contract/Scenario workbench tests: 15 passed;
- `mvn -Pfrontend package -DskipTests`: packaged application built and copied the frontend to
  `/author`, `/rehearsals`, and `/showcase`;
- Spring Boot demo startup and `/api/integration/capabilities` readiness probe succeeded;
- bundle warning remains: main JavaScript is approximately 604 kB minified and requires Stage 2
  code splitting.
