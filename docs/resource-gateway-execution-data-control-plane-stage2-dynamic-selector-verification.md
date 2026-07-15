# Execution Data Control Plane Stage 2 Dynamic Selector Verification

## 1. Scope

This increment activates the `attempts` and `occurrences` fields that were already frozen in
`bloge.fixtureRule.v1`. It lets one immutable fixture bundle control different retry calls and
different re-entries of the same structural invocation site without rewriting the graph or placing
test state in `GraphContext`.

The implementation reuses the exact coordinates already emitted by `TestRunEvidence`; it does not
create a second runtime identity model.

## 2. Frozen semantics

| Coordinate | Scope | Increment trigger | Does not increment |
|---|---|---|---|
| `attempt` | one site occurrence | actual delegate call, including retry | nested graph re-entry |
| `occurrence` | invocation site + correlation key | resolver binds that site again | retry of the current binding |
| `graphOccurrence` | graph path + correlation key | containing graph executes again | retry inside one graph execution |

Selector arrays obey these rules:

- values are one-based integers;
- each array contains at most 100 unique values in `1..100000`;
- wire order must be strictly increasing so equivalent selectors have one canonical fingerprint;
- values inside one array are OR alternatives;
- non-empty `attempts` and `occurrences` arrays are combined with AND;
- an empty array does not constrain that dimension;
- zero remains reserved only for legacy evidence producers that cannot emit a coordinate.

Parallel foreach/loop correlation is significant. Occurrence is not a global counter: two different
correlation keys each begin their own occurrence series at one. This prevents scheduling order from
silently deciding which item consumes an occurrence-specific fixture.

## 3. Precedence and ambiguity

`SelectorResolver` now freezes every structurally applicable candidate in descending specificity.
Dynamic attempt/occurrence constraints, correlation constraints, and input match constraints add
specificity. A constrained rule can therefore override an explicit general rule; the general rule
remains available as a declared fallback when the constrained rule does not match.

Same-precedence rules may coexist only when preflight can prove at least one disjoint dimension:

- non-overlapping attempt sets;
- non-overlapping occurrence sets;
- different exact correlation keys;
- different exact resource references;
- different canonical inputs;
- conflicting equality values for the same JSON Pointer.

Declaration order never resolves an overlap. Unprovable peers return
`CONTROL_PLAN_AMBIGUOUS` before graph execution. Runtime repeats the winning-precedence check as a
defense against planner/matcher drift.

## 4. Fail-closed behavior

Dynamic selection does not weaken isolation:

- a selected external-effect site still cannot use `REAL`, `SPY`, `ALLOW_REAL`, or
  `FALLBACK_TO_REAL` in the current deployment model;
- if a retry or occurrence has no matching candidate, the declared `onUnmatched` policy applies;
- the default unmatched policy produces `FIXTURE_UNMATCHED` and never invokes the real operator;
- every dynamic rule keeps an independent consumption ledger, so a scripted attempt that never
  happens produces `FIXTURE_UNUSED` when required;
- attempt/occurrence arrays participate in the fixture and effective-plan fingerprints.

## 5. Test-kit authoring

The independent Java test-kit exposes bounded builder methods:

```java
FixtureBundleBuilder fixture = FixtureBundleBuilder
        .graph(target.graphId(), target.fingerprint())
        .id("provider-retry")
        .logicalClock(Instant.parse("2026-07-15T09:00:00Z"))
        .rule("first-timeout")
            .node("provider")
            .attempts(1)
            .timeout(Duration.ofSeconds(3))
            .add()
        .rule("second-return")
            .node("provider")
            .attempts(2)
            .returnValue(Map.of("status", "ready"))
            .add();
```

The builder sorts arguments and rejects empty, duplicate, zero, negative, oversized, or out-of-range
coordinate sets before a network request is created. Raw protocol clients must send the already
canonical strictly increasing representation.

## 6. Implementation map

| Responsibility | Implementation |
|---|---|
| Protocol semantics and JavaDoc | `FixtureRule.Selector` |
| Bounds and canonical-order validation | `SafetyPreflight` |
| Structural candidate resolution and precedence | `SelectorResolver` |
| Runtime attempt/occurrence matching | `FixtureMatcher` |
| Binding and retry coordinate propagation | `TestDoubleFactory` |
| Independent client authoring | test-kit `FixtureBundleBuilder.RuleBuilder` |
| Machine contract | `testing-control-plane-v1.schema.json` |

## 7. Verification gates

Run from the repository root:

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ExecutionControlCompilerTest,TestRunServiceTest,TestingControlProtocolSchemaTest test

mvn -f resource-gateway-test-kit/pom.xml \
  -Dtest=FixtureBundleBuilderTest test
```

The server matrix proves:

- attempt 1 injects a logical timeout and attempt 2 returns a fixed value through BLOGE's real retry
  chain;
- an unconfigured second retry fails with `FIXTURE_UNMATCHED` and makes zero real external calls;
- nested graph occurrence 1 fails and occurrence 2 recovers through the parent's real retry;
- dynamic candidates sort ahead of a general fallback;
- disjoint attempt rules coexist while overlapping peers are rejected;
- non-increasing, duplicate, and out-of-range coordinates are rejected;
- JSON Schema freezes uniqueness and range bounds.

The focused server behavior matrix passes 46 tests, the schema matrix passes 4 tests, and the
test-kit builder matrix passes 4 tests, all with zero failures or errors.

## 8. Explicit non-claims

This increment does not activate:

- `STREAM` or streaming/suspendable invocation control;
- durable-resume restoration of a partially consumed dynamic plan;
- a behavior `sequence` field; multiple explicit rules remain the auditable script;
- deterministic scheduling across different correlation keys;
- random/UUID/function execution services;
- a self-contained per-coordinate schedule in `EffectiveExecutionPlan`; plan v2 commits the fixture
  fingerprint and ordered rule references, while execution still resolves dynamic rule details from
  that immutable fixture revision;
- semantic branch/rule/retry/fallback/compensation coverage gates.

The next correctness increment must version semantic coverage artifacts without changing the
canonical shape of already signed v1 suite evidence. That decision is frozen in
[ADR-003](adr/ADR-003-semantic-coverage-protocol-versioning.md).
