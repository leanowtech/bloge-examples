# Execution Data Control Plane Stage 2: Canvas Suite Publication Verification

> Date: 2026-07-15
> Scope: `/author/` Operator Detail `Executable Operator Suite`, immutable fixture/TestSuite
> publication, exact suite execution, aggregate evidence rendering, and responsive browser behavior.

## 1. Closed Gap

The Canvas previously offered two paths:

- inline operator micro-graph execution for fast exploratory feedback;
- one content-addressed governed fixture registration and execution per row.

The second path still left the table as UI-local state. It could not express one immutable suite
identity, case intent coverage, aggregate promotion policy, or one idempotent execution intent. The
Canvas now publishes all selected rows as one first-class `bloge.testSuite.v1` revision and executes
that exact revision through the common suite runner.

## 2. User Workflow

1. Open `/author/`, load or compose a graph, then double-click an executable node.
2. In `Executable Operator Suite`, edit each row's input, expected output, and resource transport
   response where applicable.
3. Assign each row one intent: `Golden`, `Negative`, `Boundary`, or `Regression`.
4. Use `Run Case` or `Run Exploratory` for fast inline `EXPLORATORY` evidence.
5. Use `Publish Case + Run` for a legitimate one-case governed suite, or
   `Publish Suite + Run` for the complete table.
6. Read the aggregate banner: execution status, coverage status, promotion eligibility,
   `suiteId@revision`, and `suiteRunId`. Each row shows its payload-free child run link and evidence
   class.

While publication or execution is in flight, the table is read-only so the response cannot be bound
to rows edited after the request was created. Starting any later exploratory run removes the prior
publication banner because that governed evidence no longer describes the table's current result.

`Apply Fixture` remains an authoring action that writes the row into the visual draft's node fixture.
It does not publish a testing control-plane asset.

## 3. Protocol Sequence

```text
Canvas rows
  -> discover exact OPERATOR target (TEST_SUITE_WRITE)
  -> register immutable fixture revision per row (TEST_FIXTURE_WRITE)
  -> construct content-addressed bloge.testSuite.v1
  -> register exact suite revision (TEST_SUITE_WRITE)
  -> execute suiteRef with COLLECT_ALL (TEST_EXECUTION)
  -> validate response identity and render payload-free aggregate evidence
```

The suite content freezes:

- exact operator runtime id and full implementation-closure fingerprint;
- lowered runtime input for every case;
- exact fixture bundle id, revision, and fingerprint;
- case id, name, and governance intent;
- coverage requirements for every row and represented case type;
- promotion requirements for all cases, certifiable evidence, and target eligibility;
- Canvas/node provenance metadata.

Unchanged content derives the same fixture and suite identities. The caller-owned idempotency key is
derived from the exact suite ref, so an unchanged repeat resolves to the same durable execution
intent. Any relevant target, input, expected output, transport response, or intent change produces a
new content address rather than overwriting history.

## 4. Safety Invariants

1. A Canvas suite contains 1-100 cases with unique bounded ids and supported intents.
2. Target discovery must return protocol v2, kind `OPERATOR`, the exact runtime id, and a full
   lowercase SHA-256 fingerprint.
3. Every fixture registration must return the requested id, revision 1, the stored-fixture protocol,
   and a full authoritative fingerprint before suite registration can begin.
4. Suite registration must return the requested outer identity, full authoritative fingerprint, and
   a canonically identical nested suite value: target, classification, ordered cases, lowered inputs,
   intents, fixture refs, tags, metadata, coverage policy, and promotion policy.
5. Execution evidence must bind the request id, exact suite ref, exact target, suite-run id, complete
   unique case set, original case intents, and exact fixture refs. Any drift fails closed.
6. The server remains the verdict authority, but the Canvas independently checks that child status,
   child run presence, evidence class, assertion counters, coverage facts, promotion facts, and
   aggregate status form one internally consistent terminal result. A detached green aggregate fails
   closed.
7. The table is frozen during an asynchronous action. An exploratory run invalidates a previously
   rendered governed publication banner before starting.
8. Aggregate mode does not render child input/output payloads. It exposes child run ids, bounded
   status, evidence class, and aggregate policy state.
9. `ELIGIBLE` means the suite policy is satisfied. It is not signed certification, ANEKE approval,
   or production publication.
10. The testing host supplies credentials. Purposes remain split across fixture write, suite write,
   and execution; production does not expose these endpoints.

## 5. Implementation Surface

| File | Responsibility |
| --- | --- |
| `resource-gateway-examples/src/main/frontend/src/types.ts` | TSDoc-documented Canvas case, immutable fixture/suite, aggregate evidence, and combined run contracts |
| `resource-gateway-examples/src/main/frontend/src/api.ts` | content addressing, fixture/suite publication, exact revision execution, and fail-closed identity validation |
| `resource-gateway-examples/src/main/frontend/src/AuthorCanvas.tsx` | case-intent editor, exploratory/published action split, publication state, and aggregate evidence mapping |
| `resource-gateway-examples/src/main/frontend/src/styles.css` | publication states plus desktop/mobile suite layout |
| `resource-gateway-examples/src/main/frontend/src/api.test.ts` | protocol request construction and registry/runner identity-drift rejection |
| `resource-gateway-examples/src/main/frontend/src/AuthorCanvas.test.tsx` | two-row suite publication, mixed intent, aggregate evidence, and payload-free rendering |

No Java API changed in this increment. Existing server domain JavaDoc and server-side protocol tests
remain authoritative; new frontend public contracts carry concise TSDoc at their declaration sites.

## 6. Automated Verification

The API tests prove:

- two rows publish two immutable fixtures and exactly one immutable suite;
- `GOLDEN` and `BOUNDARY` intents are retained in cases and coverage policy;
- suite execution uses the exact returned revision/fingerprint and deterministic idempotency key;
- a registry response rebound to another suite or with rewritten coverage/promotion policy is
  rejected before execution;
- execution evidence bound to another suite request is rejected;
- execution evidence that rewrites a case intent is rejected;
- a green aggregate with a pending child, missing child run, exploratory-only evidence, or assertion
  density below policy is rejected.

The Canvas test proves:

- the second row can select `BOUNDARY`;
- `Publish Suite + Run` performs two fixture writes, one suite write, and one aggregate execution;
- the UI renders `2/2 passed`, `coverage SATISFIED`, `promotion ELIGIBLE`, exact suite/run identity,
  and two child run links;
- aggregate mode does not render an actual-output payload block or call transient graph simulation;
- every row editor and add/remove action is disabled while publication is pending;
- a subsequent exploratory run removes the stale governed aggregate banner.

Focused command:

```bash
cd resource-gateway-examples/src/main/frontend
npm test -- --run src/api.test.ts src/AuthorCanvas.test.tsx
```

## 7. Real Browser Verification

The packaged Spring Boot service was started with the repository demo script on port `18080` and
opened through the real in-app Chromium browser. The browser workflow loaded `Loan policy fallback`,
double-clicked `Fetch applicant`, added a second `BOUNDARY` case, and selected
`Publish Suite + Run`.

Observed authoritative result:

- `2/2 passed`;
- `PASSED · coverage SATISFIED · promotion ELIGIBLE`;
- one immutable `canvas-httpResource-n1-...@1` suite identity;
- one aggregate suite-run id and two distinct child run ids;
- both child cases classified `CERTIFIABLE`;
- no child payload copied into the aggregate result panel.

Desktop inspection showed a readable full-width aggregate banner and two-column case editor. A
390 x 844 viewport exposed that the suite action row could overflow; the responsive stylesheet was
then changed so the summary owns a full row and actions use a bounded two-column grid with wrapping.
After rebuilding the packaged bundle, the same viewport showed the summary, `Run Exploratory`,
`Publish Suite + Run`, and `Add Case` without clipping. A second real two-case publication again
returned `2/2 passed`, `SATISFIED`, and `ELIGIBLE`; the long suite/run identity wrapped inside its
bounded banner.

After the stricter client-side protocol checks were added, the repository start script rebuilt and
started the current JAR on the same port. A third real two-case publication passed through the full
stored-suite and aggregate-consistency validation, produced two `CERTIFIABLE` child runs, and a
subsequent `Run Case` changed the row to `EXPLORATORY` while removing the governed aggregate banner.
The capability probe stayed healthy and the stop script removed the process cleanly.

## 8. Measured Result

Measured on 2026-07-15 with Java 25, Maven 3.9, Node 22, and the in-app Chromium browser:

- focused API/Canvas verification: 68 tests passed, 0 failures;
- full frontend suite: 150 tests passed, 0 failures;
- TypeScript plus Vite production build: passed; the existing bundle-size advisory remains
  non-blocking;
- Resource Gateway `mvn -Pfrontend clean verify`: 1748 tests, 0 failures, 0 errors, 0 skipped
  skips, with packaged browser tests and Spring Boot JAR build successful;
- standalone test-kit `clean verify`: 29 tests, 0 failures, 0 errors, 0 skipped; library/CLI JAR and
  public JavaDoc/doclint verification passed;
- real server-backed desktop and 390 x 844 two-case suite publications both returned `2/2 passed`,
  `coverage SATISFIED`, and `promotion ELIGIBLE` with distinct child run links.

## 9. Remaining Boundaries

- The right-side graph-level authoring Test Suite still uses transient visual simulation; this
  increment governs the operator-level suite in `Operator Detail`.
- There is no suite history/trend/list UI or abandoned `RUNNING` reconciliation worker.
- Promotion eligibility is not signed certification or a publish-gate decision.
- REPLAY, streaming/suspendable execution controls, deterministic random/UUID/function services,
  and physical test-runtime/network isolation remain in later stages.
