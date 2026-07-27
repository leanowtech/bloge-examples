# Contract & Scenario Authoring Implementation Status

> Goal: residual gap below 8% against the approved evolution plan
> Measurement: weighted, evidence-based capability matrix
> Latest round: Stage 0 semantic foundation

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
